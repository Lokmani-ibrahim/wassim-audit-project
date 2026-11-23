
package com.fintech.ledger;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.port.LedgerProvider;
import com.fintech.notifications.FinancialTransactionNotificationService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import com.fintech.notifications.TransactionNotificationDirection;
import com.fintech.notifications.TransactionNotificationType;
import com.fintech.kyc.CustomerDebitAuthorization;

import java.util.Objects;
import java.util.UUID;

@Service
public class InternalTransferService {

    private final LedgerProvider ledgerProvider;

    private final FinancialTransactionNotificationService
            transactionNotificationService;
    private final CustomerDebitAuthorization debitAuthorization;

    public InternalTransferService(
            LedgerProvider ledgerProvider,
            FinancialTransactionNotificationService transactionNotificationService,
            CustomerDebitAuthorization debitAuthorization
    ) {
        this.ledgerProvider =
                Objects.requireNonNull(ledgerProvider);

        this.transactionNotificationService =
                Objects.requireNonNull(transactionNotificationService);

        this.debitAuthorization =
                Objects.requireNonNull(debitAuthorization);
    }

    public LedgerOperationResult transfer(
            UUID sourceUserId,
            UUID destinationUserId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId
    ) {

        Objects.requireNonNull(
                sourceUserId,
                "sourceUserId must not be null"
        );

        Objects.requireNonNull(
                destinationUserId,
                "destinationUserId must not be null"
        );

        Objects.requireNonNull(
                currency,
                "currency must not be null"
        );

        Objects.requireNonNull(
                operationId,
                "operationId must not be null"
        );

        if (sourceUserId.equals(destinationUserId)) {
            throw new IllegalArgumentException(
                    "sourceUserId and destinationUserId must be different"
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

        // A suspended, closed, or unverified sender
        // must never initiate an outgoing transfer.

        try {

            // This check happens BEFORE any Formance call.
            requireSenderAuthorized(sourceUserId);

        } catch (AccessDeniedException denial) {

            // We know the ledger provider has not yet
            // been invoked by this transfer method.
            throw new TransferAuthorizationDeniedBeforePostingException(
                    denial
            );
        }

        /*
         * The financial operation happens first.
         *
         * Formance performs:
         *
         * users:{source}:wallet:{currency}:available
         *                ->
         * users:{destination}:wallet:{currency}:available
         */
        LedgerOperationResult result =
                ledgerProvider.transferBetweenUsers(
                        sourceUserId,
                        destinationUserId,
                        currency,
                        amountMinor,
                        requiredOperationId
                );

        /*
         * Validate the ledger response before notifications
         * or a subsequent SUCCEEDED idempotency update.
         * A mismatch does not prove that nothing posted.
         */
        String expectedReference =
                "internal_transfer:" + requiredOperationId;

        if (result == null
                || !expectedReference.equals(result.reference())) {
            throw new IllegalStateException(
                    "Transfer ledger response identity is unconfirmed; "
                            + "reconciliation is required"
            );
        }

        /*
         * Sender notification.
         *
         * Same Formance transaction, but from the sender's
         * perspective this is a DEBIT.
         */
        transactionNotificationService.enqueueSafely(
                sourceUserId,
                result.transactionId(),
                result.reference(),
                TransactionNotificationType.TRANSFER,
                TransactionNotificationDirection.DEBIT,
                currency.currencyCode(),
                currency.formanceAsset(),
                amountMinor
        );

        /*
         * Recipient notification.
         *
         * Same Formance transaction, but from the recipient's
         * perspective this is a CREDIT.
         */
        transactionNotificationService.enqueueSafely(
                destinationUserId,
                result.transactionId(),
                result.reference(),
                TransactionNotificationType.TRANSFER,
                TransactionNotificationDirection.CREDIT,
                currency.currencyCode(),
                currency.formanceAsset(),
                amountMinor
        );

        return result;
    }

    public void requireSenderAuthorized(UUID sourceUserId) {

        Objects.requireNonNull(
                sourceUserId,
                "sourceUserId"
        );

        debitAuthorization.requireEligible(sourceUserId);
    }
}
