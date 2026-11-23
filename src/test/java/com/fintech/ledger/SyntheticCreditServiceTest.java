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

class SyntheticCreditServiceTest {

    @Test
    void creditsThroughRealIncomingPaymentAbstraction() {

        LedgerProvider ledgerProvider =
                mock(LedgerProvider.class);

        FinancialTransactionNotificationService notificationService =
                mock(FinancialTransactionNotificationService.class);

        SyntheticCreditService service =
                new SyntheticCreditService(
                        ledgerProvider,
                        notificationService
                );

        UUID userId = UUID.randomUUID();
        LedgerCurrency currency = LedgerCurrency.EUR;
        long amountMinor = 10_000L;
        String operationId = "c3_credit_001";

        LedgerOperationResult expected =
                new LedgerOperationResult(
                        91L,
                        "incoming_payment:c3_credit_001"
                );

        when(
                ledgerProvider.recordIncomingPayment(
                        userId,
                        "sponsor",
                        currency,
                        amountMinor,
                        operationId
                )
        ).thenReturn(expected);

        LedgerOperationResult actual =
                service.credit(
                        userId,
                        currency,
                        amountMinor,
                        operationId
                );

        assertThat(actual).isEqualTo(expected);

        verify(ledgerProvider).recordIncomingPayment(
                userId,
                "sponsor",
                currency,
                amountMinor,
                operationId
        );

        verify(notificationService).enqueueSafely(
                userId,
                expected.transactionId(),
                expected.reference(),
                TransactionNotificationType.INCOMING_PAYMENT,
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
    void ledgerFailureDoesNotCreateNotification() {

        LedgerProvider ledgerProvider =
                mock(LedgerProvider.class);

        FinancialTransactionNotificationService notificationService =
                mock(FinancialTransactionNotificationService.class);

        SyntheticCreditService service =
                new SyntheticCreditService(
                        ledgerProvider,
                        notificationService
                );

        UUID userId = UUID.randomUUID();

        when(
                ledgerProvider.recordIncomingPayment(
                        userId,
                        "sponsor",
                        LedgerCurrency.EUR,
                        10_000L,
                        "c3_credit_failure_001"
                )
        ).thenThrow(
                new RuntimeException("ledger failure")
        );

        assertThatThrownBy(
                () -> service.credit(
                        userId,
                        LedgerCurrency.EUR,
                        10_000L,
                        "c3_credit_failure_001"
                )
        ).isInstanceOf(RuntimeException.class);

        verifyNoInteractions(notificationService);
    }

    @Test
    void rejectsZeroAmount() {

        LedgerProvider ledgerProvider =
                mock(LedgerProvider.class);

        FinancialTransactionNotificationService notificationService =
                mock(FinancialTransactionNotificationService.class);

        SyntheticCreditService service =
                new SyntheticCreditService(
                        ledgerProvider,
                        notificationService
                );

        assertThatThrownBy(
                () -> service.credit(
                        UUID.randomUUID(),
                        LedgerCurrency.EUR,
                        0L,
                        "c3_credit_zero_001"
                )
        ).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(
                ledgerProvider,
                notificationService
        );
    }

    @Test
    void rejectsNegativeAmount() {

        LedgerProvider ledgerProvider =
                mock(LedgerProvider.class);

        FinancialTransactionNotificationService notificationService =
                mock(FinancialTransactionNotificationService.class);

        SyntheticCreditService service =
                new SyntheticCreditService(
                        ledgerProvider,
                        notificationService
                );

        assertThatThrownBy(
                () -> service.credit(
                        UUID.randomUUID(),
                        LedgerCurrency.EUR,
                        -1L,
                        "c3_credit_negative_001"
                )
        ).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(
                ledgerProvider,
                notificationService
        );
    }

    @Test
    void rejectsBlankOperationId() {

        LedgerProvider ledgerProvider =
                mock(LedgerProvider.class);

        FinancialTransactionNotificationService notificationService =
                mock(FinancialTransactionNotificationService.class);

        SyntheticCreditService service =
                new SyntheticCreditService(
                        ledgerProvider,
                        notificationService
                );

        assertThatThrownBy(
                () -> service.credit(
                        UUID.randomUUID(),
                        LedgerCurrency.EUR,
                        10_000L,
                        "   "
                )
        ).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(
                ledgerProvider,
                notificationService
        );
    }
}
