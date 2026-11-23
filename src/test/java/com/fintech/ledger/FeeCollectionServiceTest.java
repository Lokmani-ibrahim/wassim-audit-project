package com.fintech.ledger;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.port.LedgerProvider;
import com.fintech.notifications.FinancialTransactionNotificationService;
import com.fintech.notifications.TransactionNotificationDirection;
import com.fintech.notifications.TransactionNotificationType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FeeCollectionServiceTest {

    @Test
    void successfulFeeCreatesDebitNotification() {

        LedgerProvider ledgerProvider =
                mock(LedgerProvider.class);

        FinancialTransactionNotificationService notificationService =
                mock(FinancialTransactionNotificationService.class);

        FeeCollectionService service =
                new FeeCollectionService(
                        ledgerProvider,
                        notificationService
                );

        UUID userId = UUID.randomUUID();
        LedgerCurrency currency = LedgerCurrency.EUR;
        long amountMinor = 500L;
        String operationId = "fee_001";

        LedgerOperationResult result =
                new LedgerOperationResult(
                        93L,
                        "fee_collection:fee_001"
                );

        when(
                ledgerProvider.collectFee(
                        userId,
                        currency,
                        amountMinor,
                        operationId
                )
        ).thenReturn(result);

        LedgerOperationResult returned =
                service.collect(
                        userId,
                        currency,
                        amountMinor,
                        operationId
                );

        assertThat(returned).isEqualTo(result);

        verify(ledgerProvider).collectFee(
                userId,
                currency,
                amountMinor,
                operationId
        );

        verify(notificationService).enqueueSafely(
                userId,
                result.transactionId(),
                result.reference(),
                TransactionNotificationType.FEE,
                TransactionNotificationDirection.DEBIT,
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
    void ledgerFailureDoesNotCreateNotification() {

        LedgerProvider ledgerProvider =
                mock(LedgerProvider.class);

        FinancialTransactionNotificationService notificationService =
                mock(FinancialTransactionNotificationService.class);

        FeeCollectionService service =
                new FeeCollectionService(
                        ledgerProvider,
                        notificationService
                );

        UUID userId = UUID.randomUUID();

        when(
                ledgerProvider.collectFee(
                        userId,
                        LedgerCurrency.EUR,
                        500L,
                        "fee_failure_001"
                )
        ).thenThrow(
                new RuntimeException("ledger failure")
        );

        assertThatThrownBy(
                () -> service.collect(
                        userId,
                        LedgerCurrency.EUR,
                        500L,
                        "fee_failure_001"
                )
        ).isInstanceOf(RuntimeException.class);

        verifyNoInteractions(notificationService);
    }

    @Test
    void rejectsInvalidAmountBeforeCallingLedger() {

        LedgerProvider ledgerProvider =
                mock(LedgerProvider.class);

        FinancialTransactionNotificationService notificationService =
                mock(FinancialTransactionNotificationService.class);

        FeeCollectionService service =
                new FeeCollectionService(
                        ledgerProvider,
                        notificationService
                );

        assertThatThrownBy(
                () -> service.collect(
                        UUID.randomUUID(),
                        LedgerCurrency.EUR,
                        0L,
                        "fee_invalid_001"
                )
        ).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(
                ledgerProvider,
                notificationService
        );
    }
}
