
package com.fintech.ledger;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.port.LedgerProvider;
import com.fintech.notifications.FinancialTransactionNotificationService;
import com.fintech.notifications.TransactionNotificationDirection;
import com.fintech.notifications.TransactionNotificationType;
import org.junit.jupiter.api.Test;
import com.fintech.kyc.CustomerDebitAuthorization;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class InternalTransferServiceTest {

    @Test
    void successfulTransferCreatesDebitAndCreditNotifications() {
        CustomerDebitAuthorization debitAuthorization =
                mock(CustomerDebitAuthorization.class);
        LedgerProvider ledgerProvider =
                mock(LedgerProvider.class);

        FinancialTransactionNotificationService notificationService =
                mock(FinancialTransactionNotificationService.class);

        InternalTransferService service =
                new InternalTransferService(
                        ledgerProvider,
                        notificationService,
                        debitAuthorization
                );

        UUID sourceUserId = UUID.randomUUID();
        UUID destinationUserId = UUID.randomUUID();
        LedgerCurrency currency = LedgerCurrency.EUR;
        long amountMinor = 5_000L;
        String operationId = "transfer_001";

        LedgerOperationResult result =
                new LedgerOperationResult(
                        92L,
                        "internal_transfer:transfer_001"
                );

        when(
                ledgerProvider.transferBetweenUsers(
                        sourceUserId,
                        destinationUserId,
                        currency,
                        amountMinor,
                        operationId
                )
        ).thenReturn(result);

        LedgerOperationResult returned =
                service.transfer(
                        sourceUserId,
                        destinationUserId,
                        currency,
                        amountMinor,
                        operationId
                );

        assertThat(returned).isEqualTo(result);

        verify(ledgerProvider).transferBetweenUsers(
                sourceUserId,
                destinationUserId,
                currency,
                amountMinor,
                operationId
        );

        verify(notificationService).enqueueSafely(
                sourceUserId,
                result.transactionId(),
                result.reference(),
                TransactionNotificationType.TRANSFER,
                TransactionNotificationDirection.DEBIT,
                currency.currencyCode(),
                currency.formanceAsset(),
                amountMinor
        );

        verify(notificationService).enqueueSafely(
                destinationUserId,
                result.transactionId(),
                result.reference(),
                TransactionNotificationType.TRANSFER,
                TransactionNotificationDirection.CREDIT,
                currency.currencyCode(),
                currency.formanceAsset(),
                amountMinor
        );

        verifyNoMoreInteractions(
                ledgerProvider,
                notificationService
        );
    }

    @Test
    void ledgerFailureDoesNotCreateNotifications() {

        LedgerProvider ledgerProvider =
                mock(LedgerProvider.class);
        CustomerDebitAuthorization debitAuthorization =
                mock(CustomerDebitAuthorization.class);

        FinancialTransactionNotificationService notificationService =
                mock(FinancialTransactionNotificationService.class);

        InternalTransferService service =
                new InternalTransferService(
                        ledgerProvider,
                        notificationService,
                        debitAuthorization
                );

        UUID sourceUserId = UUID.randomUUID();
        UUID destinationUserId = UUID.randomUUID();

        when(
                ledgerProvider.transferBetweenUsers(
                        sourceUserId,
                        destinationUserId,
                        LedgerCurrency.EUR,
                        5_000L,
                        "transfer_failure_001"
                )
        ).thenThrow(
                new RuntimeException("ledger failure")
        );

        assertThatThrownBy(
                () -> service.transfer(
                        sourceUserId,
                        destinationUserId,
                        LedgerCurrency.EUR,
                        5_000L,
                        "transfer_failure_001"
                )
        ).isInstanceOf(RuntimeException.class);

        verifyNoInteractions(notificationService);
    }

    @Test
    void rejectsInvalidAmountBeforeCallingLedger() {

        LedgerProvider ledgerProvider =
                mock(LedgerProvider.class);
        CustomerDebitAuthorization debitAuthorization =
                mock(CustomerDebitAuthorization.class);

        FinancialTransactionNotificationService notificationService =
                mock(FinancialTransactionNotificationService.class);

        InternalTransferService service =
                new InternalTransferService(
                        ledgerProvider,
                        notificationService,
                        debitAuthorization
                );

        assertThatThrownBy(
                () -> service.transfer(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        LedgerCurrency.EUR,
                        0L,
                        "transfer_invalid_001"
                )
        ).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(
                ledgerProvider,
                notificationService
        );
    }

    @Test
    void suspendedSenderCannotTransferMoney() {

        LedgerProvider ledgerProvider =
                mock(LedgerProvider.class);

        FinancialTransactionNotificationService notificationService =
                mock(FinancialTransactionNotificationService.class);

        CustomerDebitAuthorization authorization =
                mock(CustomerDebitAuthorization.class);

        InternalTransferService service =
                new InternalTransferService(
                        ledgerProvider,
                        notificationService,
                        authorization
                );

        UUID sourceUserId = UUID.randomUUID();
        UUID destinationUserId = UUID.randomUUID();

        doThrow(
                new org.springframework.security.access
                        .AccessDeniedException(
                        "Customer account suspended"
                )
        ).when(authorization)
                .requireEligible(sourceUserId);

        assertThatThrownBy(
                () -> service.transfer(
                        sourceUserId,
                        destinationUserId,
                        LedgerCurrency.EUR,
                        5_000L,
                        "blocked-transfer-001"
                )
        ).isInstanceOf(
                org.springframework.security.access
                        .AccessDeniedException.class
        );

        verify(authorization)
                .requireEligible(sourceUserId);

        // Critical: no financial movement whatsoever.
        verifyNoInteractions(
                ledgerProvider,
                notificationService
        );
    }

    @Test
    void mismatchedLedgerReferenceRequiresReconciliation() {

        LedgerProvider ledgerProvider =
                mock(LedgerProvider.class);

        CustomerDebitAuthorization authorization =
                mock(CustomerDebitAuthorization.class);

        FinancialTransactionNotificationService notifications =
                mock(FinancialTransactionNotificationService.class);

        InternalTransferService service =
                new InternalTransferService(
                        ledgerProvider,
                        notifications,
                        authorization
                );

        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        String operationId = "transfer_002";

        when(ledgerProvider.transferBetweenUsers(
                sender,
                recipient,
                LedgerCurrency.EUR,
                5_000L,
                operationId
        )).thenReturn(
                new LedgerOperationResult(
                        94L,
                        "internal_transfer:some_other_operation"
                )
        );

        assertThatThrownBy(
                () -> service.transfer(
                        sender,
                        recipient,
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "reconciliation is required"
                );

        verify(ledgerProvider, times(1))
                .transferBetweenUsers(
                        sender,
                        recipient,
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId
                );

        // No notifications for an unconfirmed result.
        verifyNoInteractions(notifications);
    }

    @Test
    void nullLedgerResponseRequiresReconciliation() {

        LedgerProvider ledgerProvider =
                mock(LedgerProvider.class);

        CustomerDebitAuthorization authorization =
                mock(CustomerDebitAuthorization.class);

        FinancialTransactionNotificationService notifications =
                mock(FinancialTransactionNotificationService.class);

        InternalTransferService service =
                new InternalTransferService(
                        ledgerProvider,
                        notifications,
                        authorization
                );

        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        String operationId = "transfer_003";

        // An unstubbed mock provider returns null.
        assertThatThrownBy(
                () -> service.transfer(
                        sender,
                        recipient,
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "reconciliation is required"
                );

        verify(ledgerProvider, times(1))
                .transferBetweenUsers(
                        sender,
                        recipient,
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId
                );

        verifyNoInteractions(notifications);
    }
}
