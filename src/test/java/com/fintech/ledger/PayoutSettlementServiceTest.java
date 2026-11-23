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

class PayoutSettlementServiceTest {

    @Test
    void successfulSettlementCreatesPayoutDebitNotification() {

        LedgerProvider ledgerProvider =
                mock(LedgerProvider.class);

        FinancialTransactionNotificationService notificationService =
                mock(FinancialTransactionNotificationService.class);

        PayoutSettlementService service =
                new PayoutSettlementService(
                        ledgerProvider,
                        notificationService
                );

        UUID userId = UUID.randomUUID();

        LedgerOperationResult result =
                new LedgerOperationResult(
                        94L,
                        "payout_settle:payout_001"
                );

        when(
                ledgerProvider.settlePayout(
                        userId,
                        "sponsor",
                        LedgerCurrency.EUR,
                        3_000L,
                        "payout_001"
                )
        ).thenReturn(result);

        LedgerOperationResult returned =
                service.settle(
                        userId,
                        "sponsor",
                        LedgerCurrency.EUR,
                        3_000L,
                        "payout_001"
                );

        assertThat(returned).isEqualTo(result);

        verify(notificationService).enqueueSafely(
                userId,
                result.transactionId(),
                result.reference(),
                TransactionNotificationType.PAYOUT,
                TransactionNotificationDirection.DEBIT,
                LedgerCurrency.EUR.currencyCode(),
                LedgerCurrency.EUR.formanceAsset(),
                3_000L
        );

        verifyNoMoreInteractions(notificationService);
    }

    @Test
    void failedSettlementDoesNotCreateNotification() {

        LedgerProvider ledgerProvider =
                mock(LedgerProvider.class);

        FinancialTransactionNotificationService notificationService =
                mock(FinancialTransactionNotificationService.class);

        PayoutSettlementService service =
                new PayoutSettlementService(
                        ledgerProvider,
                        notificationService
                );

        UUID userId = UUID.randomUUID();

        when(
                ledgerProvider.settlePayout(
                        userId,
                        "sponsor",
                        LedgerCurrency.EUR,
                        3_000L,
                        "payout_002"
                )
        ).thenThrow(
                new RuntimeException("settlement rejected")
        );

        assertThatThrownBy(
                () -> service.settle(
                        userId,
                        "sponsor",
                        LedgerCurrency.EUR,
                        3_000L,
                        "payout_002"
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

        PayoutSettlementService service =
                new PayoutSettlementService(
                        ledgerProvider,
                        notificationService
                );

        assertThatThrownBy(
                () -> service.settle(
                        UUID.randomUUID(),
                        "sponsor",
                        LedgerCurrency.EUR,
                        0L,
                        "payout_003"
                )
        ).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(
                ledgerProvider,
                notificationService
        );
    }

    @Test
    void rejectsBlankBankIdBeforeCallingLedger() {

        LedgerProvider ledgerProvider =
                mock(LedgerProvider.class);

        FinancialTransactionNotificationService notificationService =
                mock(FinancialTransactionNotificationService.class);

        PayoutSettlementService service =
                new PayoutSettlementService(
                        ledgerProvider,
                        notificationService
                );

        assertThatThrownBy(
                () -> service.settle(
                        UUID.randomUUID(),
                        "   ",
                        LedgerCurrency.EUR,
                        3_000L,
                        "payout_004"
                )
        ).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(
                ledgerProvider,
                notificationService
        );
    }
}
