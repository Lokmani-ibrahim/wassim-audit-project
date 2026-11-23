package com.fintech.ledger;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.port.LedgerProvider;
import com.fintech.notifications.FinancialTransactionNotificationService;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;
import com.fintech.notifications.TransactionNotificationDirection;
import com.fintech.notifications.TransactionNotificationType;

@Service
public class FeeCollectionService {

    private final LedgerProvider ledgerProvider;

    private final FinancialTransactionNotificationService
            transactionNotificationService;

    public FeeCollectionService(
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

    public LedgerOperationResult collect(
            UUID userId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId
    ) {

        Objects.requireNonNull(
                userId,
                "userId must not be null"
        );

        Objects.requireNonNull(
                currency,
                "currency must not be null"
        );

        Objects.requireNonNull(
                operationId,
                "operationId must not be null"
        );

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

        /*
         * Financial operation first.
         *
         * Formance:
         *
         * users:{userId}:wallet:{currency}:available
         *              ->
         * platform:fees:{currency}
         */
        LedgerOperationResult result =
                ledgerProvider.collectFee(
                        userId,
                        currency,
                        amountMinor,
                        requiredOperationId
                );

        /*
         * From the customer's perspective this completed
         * transaction is a DEBIT.
         */
        transactionNotificationService.enqueueSafely(
                userId,
                result.transactionId(),
                result.reference(),
                TransactionNotificationType.FEE,
                TransactionNotificationDirection.DEBIT,
                currency.currencyCode(),
                currency.formanceAsset(),
                amountMinor
        );

        return result;
    }
}