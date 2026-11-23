package com.fintech.ledger;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.port.LedgerProvider;
import com.fintech.notifications.FinancialTransactionNotificationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import com.fintech.notifications.TransactionNotificationDirection;
import com.fintech.notifications.TransactionNotificationType;

import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        prefix = "neos.backoffice.synthetic-credit",
        name = "enabled",
        havingValue = "true"
)
public class SyntheticCreditService {

    private static final String SPONSOR_BANK_ID =
            "sponsor";

    private final LedgerProvider ledgerProvider;

    private final FinancialTransactionNotificationService
            transactionNotificationService;

    public SyntheticCreditService(
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

    public LedgerOperationResult credit(
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

        if (amountMinor <= 0) {
            throw new IllegalArgumentException(
                    "amountMinor must be positive"
            );
        }

        Objects.requireNonNull(
                operationId,
                "operationId must not be null"
        );

        String requiredOperationId =
                operationId.trim();

        if (requiredOperationId.isEmpty()) {
            throw new IllegalArgumentException(
                    "operationId must not be blank"
            );
        }

        /*
         * IMPORTANT:
         *
         * Synthetic credit deliberately reuses the exact same
         * accounting path as a real attributed incoming payment.
         *
         * It must NEVER perform:
         *
         *     world -> customer
         *
         * directly.
         *
         * LedgerProvider.recordIncomingPayment() produces:
         *
         *     world
         *       ->
         *     bank:sponsor:master:{currency}
         *
         * and
         *
         *     control:safeguarded:{currency}
         *       ->
         *     users:{userId}:wallet:{currency}:available
         *
         * atomically inside Formance.
         */
        LedgerOperationResult result =
                ledgerProvider.recordIncomingPayment(
                        userId,
                        SPONSOR_BANK_ID,
                        currency,
                        amountMinor,
                        requiredOperationId
                );

        /*
         * The financial transaction has already succeeded
         * at this point.
         *
         * Notification persistence is deliberately isolated
         * from the money movement.
         *
         * FinancialTransactionNotificationService.enqueueSafely()
         * must never allow an email/outbox failure to turn a
         * successful ledger transaction into a failed API request.
         */
        transactionNotificationService.enqueueSafely(
                userId,
                result.transactionId(),
                result.reference(),
                TransactionNotificationType.INCOMING_PAYMENT,
                TransactionNotificationDirection.CREDIT,
                currency.currencyCode(),
                currency.formanceAsset(),
                amountMinor
        );

        return result;
    }
}