package com.fintech.ledger;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.port.LedgerProvider;
import com.fintech.notifications.FinancialTransactionNotificationService;
import com.fintech.notifications.TransactionNotificationDirection;
import com.fintech.notifications.TransactionNotificationType;

import java.util.Objects;
import java.util.UUID;

/**
 * Legacy direct-ledger settlement implementation.
 *
 * Retained temporarily for isolated sandbox tests.
 * Not registered as a production Spring bean.
 *
 * Production payout settlement must go through
 * PayoutFinalizationExecutionService.
 */

public class PayoutSettlementService {

    private final LedgerProvider ledgerProvider;

    private final FinancialTransactionNotificationService
            transactionNotificationService;

    public PayoutSettlementService(
            LedgerProvider ledgerProvider,
            FinancialTransactionNotificationService transactionNotificationService
    ) {

        this.ledgerProvider =
                Objects.requireNonNull(
                        ledgerProvider,
                        "ledgerProvider must not be null"
                );

        this.transactionNotificationService =
                Objects.requireNonNull(
                        transactionNotificationService,
                        "transactionNotificationService must not be null"
                );
    }

    public LedgerOperationResult settle(
            UUID userId,
            String bankId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId
    ) {

        Objects.requireNonNull(
                userId,
                "userId must not be null"
        );

        Objects.requireNonNull(
                bankId,
                "bankId must not be null"
        );

        Objects.requireNonNull(
                currency,
                "currency must not be null"
        );

        Objects.requireNonNull(
                operationId,
                "operationId must not be null"
        );

        if (bankId.isBlank()) {
            throw new IllegalArgumentException(
                    "bankId must not be blank"
            );
        }

        if (amountMinor <= 0) {
            throw new IllegalArgumentException(
                    "amountMinor must be positive"
            );
        }

        String requiredOperationId =
                operationId.trim();

        if (requiredOperationId.isEmpty()) {
            throw new IllegalArgumentException(
                    "operationId must not be blank"
            );
        }

        String requiredBankId =
                bankId.trim();

        /*
         * Financial settlement happens first.
         *
         * Formance:
         *
         * bank:{bankId}:master:{currency}
         *              ->
         * world
         *
         * and
         *
         * users:{user}:wallet:{currency}:holds:{operationId}
         *              ->
         * control:safeguarded:{currency}
         *
         * The payout notification must ONLY be created after
         * this settlement succeeds.
         *
         * reservePayout() is not a completed customer transaction.
         */
        LedgerOperationResult result =
                ledgerProvider.settlePayout(
                        userId,
                        requiredBankId,
                        currency,
                        amountMinor,
                        requiredOperationId
                );

        transactionNotificationService.enqueueSafely(
                userId,
                result.transactionId(),
                result.reference(),
                TransactionNotificationType.PAYOUT,
                TransactionNotificationDirection.DEBIT,
                currency.currencyCode(),
                currency.formanceAsset(),
                amountMinor
        );

        return result;
    }
}