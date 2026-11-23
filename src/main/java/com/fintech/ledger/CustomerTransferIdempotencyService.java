
package com.fintech.ledger;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class CustomerTransferIdempotencyService {

    private final JdbcTemplate jdbc;
    private final InternalTransferService transferService;
    private final CustomerTransferAdmissionService admissionService;


    public CustomerTransferIdempotencyService(
            JdbcTemplate jdbc,
            InternalTransferService transferService,
            CustomerTransferAdmissionService admissionService
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transferService =
                Objects.requireNonNull(transferService);
        this.admissionService =
                Objects.requireNonNull(admissionService);
    }


    // IMPORTANT:
    // Do not wrap this entire method in @Transactional.
    //
    // The idempotency claim must COMMIT before calling
    // the external Formance ledger.
    public LedgerOperationResult execute(
            UUID senderId,
            UUID recipientId,
            LedgerCurrency currency,
            long amountMinor,
            UUID requestKey,
            String operationId
    ) {

        Objects.requireNonNull(senderId);
        Objects.requireNonNull(recipientId);
        Objects.requireNonNull(currency);
        Objects.requireNonNull(requestKey);
        Objects.requireNonNull(operationId);

        if (amountMinor <= 0) {
            throw new IllegalArgumentException(
                    "Transfer amount must be positive"
            );
        }

        if (operationId.isBlank()) {
            throw new IllegalArgumentException(
                    "Operation ID must not be blank"
            );
        }

        if (TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Transfer orchestration must not run "
                            + "inside an existing transaction"
            );
        }

        // STEP 1: Atomically claim the request key.

// STEP 1: Claim and authorize in a short,
// committed PostgreSQL transaction.
//
// The admission service locks the sender's users row,
// checks ACTIVE + KYC APPROVED, and inserts the claim.
//
// Formance has not been called yet.
        boolean claimed = admissionService.claimNew(
                senderId,
                recipientId,
                currency,
                amountMinor,
                requestKey,
                operationId
        );

// STEP 2: The request key already exists.
// Preserve the existing idempotency behavior.
        if (!claimed) {
            return handleExisting(
                    senderId,
                    requestKey,
                    recipientId,
                    currency,
                    amountMinor
            );
        }





        // STEP 3A: Validate sender authorization before
        // attempting any external financial operation.
        //
        // At this point, we own a committed IN_PROGRESS
        // claim, but Formance has NOT been called.

        try {

            transferService.requireSenderAuthorized(senderId);

        } catch (RuntimeException preflightFailure) {

            // Since no ledger operation was attempted,
            // this claim can safely be released.
            //
            // Restrict deletion to our exact request.
            try {

                int released = jdbc.update(
                        """
                        DELETE FROM customer_transfer_idempotency
                        WHERE sender_user_id = ?
                          AND request_key = ?
                          AND status = 'IN_PROGRESS'
                        """,
                        senderId,
                        requestKey
                );

                if (released != 1) {
                    throw new IllegalStateException(
                            "Unable to release pre-execution claim"
                    );
                }

            } catch (RuntimeException cleanupFailure) {

                // Do not pretend that the claim was released.
                preflightFailure.addSuppressed(cleanupFailure);

                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Transfer validation failed and "
                                + "claim cleanup requires review",
                        preflightFailure
                );
            }

            // A known business authorization failure
            // is a normal denial, not a Formance outage.
            if (preflightFailure instanceof AccessDeniedException) {
                throw preflightFailure;
            }

            // Other preflight failures may be temporary,
            // but no ledger operation was attempted.
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Transfer authorization unavailable; "
                            + "no ledger operation was attempted",
                    preflightFailure
            );
        }

        // STEP 3B: Sender passed preflight.
        // Begin the potentially irreversible ledger operation.
        // Keep the existing try/catch below unchanged.

        try {

            LedgerOperationResult result =
                    Objects.requireNonNull(
                            transferService.transfer(
                                    senderId,
                                    recipientId,
                                    currency,
                                    amountMinor,
                                    operationId
                            ),
                            "Transfer result cannot be null"
                    );

            // STEP 4: Persist the confirmed success.
            int updated = jdbc.update(
                    """
                    UPDATE customer_transfer_idempotency
                    SET status = 'SUCCEEDED',
                        ledger_transaction_id = ?,
                        ledger_reference = ?,
                        updated_at = NOW()
                    WHERE sender_user_id = ?
                      AND request_key = ?
                      AND status = 'IN_PROGRESS'
                    """,
                    result.transactionId(),
                    result.reference(),
                    senderId,
                    requestKey
            );

            if (updated != 1) {
                throw new IllegalStateException(
                        "Unable to persist confirmed transfer result"
                );
            }

            return result;

        } catch (TransferAuthorizationDeniedBeforePostingException denial) {

            // The InternalTransferService guarantees this
            // exception originated before Formance submission.
            //
            // The committed claim can therefore be released.
            try {

                int released = jdbc.update(
                        """
                        DELETE FROM customer_transfer_idempotency
                        WHERE sender_user_id = ?
                          AND request_key = ?
                          AND operation_id = ?
                          AND status = 'IN_PROGRESS'
                        """,
                        senderId,
                        requestKey,
                        operationId
                );

                if (released != 1) {
                    throw new IllegalStateException(
                            "Unable to release pre-ledger transfer claim"
                    );
                }

            } catch (RuntimeException cleanupFailure) {

                // If cleanup fails, never claim that the
                // idempotency record was safely released.
                denial.addSuppressed(cleanupFailure);

                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Pre-ledger authorization was denied, "
                                + "but claim cleanup requires review",
                        denial
                );
            }

            // No Formance operation was submitted.
            // Preserve the authorization-denied response.
            throw denial;



    } catch (RuntimeException exception) {

            // The ledger operation may have completed
            // before an exception or timeout occurred.
            //
            // DO NOT automatically retry it here.
            try {

                jdbc.update(
                        """
                        UPDATE customer_transfer_idempotency
                        SET status = 'UNKNOWN',
                            updated_at = NOW()
                        WHERE sender_user_id = ?
                          AND request_key = ?
                          AND status = 'IN_PROGRESS'
                        """,
                        senderId,
                        requestKey
                );

            } catch (RuntimeException storageFailure) {

                // Preserve the original exception.
                // IN_PROGRESS is also a blocked state.
                exception.addSuppressed(storageFailure);
            }

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Transfer outcome not confirmed. "
                            + "Reconciliation is required.",
                    exception
            );
        }
    }

    private LedgerOperationResult handleExisting(
            UUID senderId,
            UUID requestKey,
            UUID recipientId,
            LedgerCurrency currency,
            long amountMinor
    ) {

        List<StoredRequest> rows = jdbc.query(
                """
                SELECT
                    recipient_user_id,
                    currency,
                    amount_minor,
                    status,
                    ledger_transaction_id,
                    ledger_reference
                FROM customer_transfer_idempotency
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                (rs, rowNum) -> {

                    long id = rs.getLong(
                            "ledger_transaction_id"
                    );

                    Long transactionId =
                            rs.wasNull() ? null : id;

                    return new StoredRequest(
                            (UUID) rs.getObject(
                                    "recipient_user_id"
                            ),
                            rs.getString("currency"),
                            rs.getLong("amount_minor"),
                            rs.getString("status"),
                            transactionId,
                            rs.getString("ledger_reference")
                    );
                },
                senderId,
                requestKey
        );

        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Idempotency record missing or duplicated"
            );
        }

        StoredRequest original = rows.get(0);

        // The key must always represent the same transfer.
        if (!original.recipientId().equals(recipientId)
                || !original.currency().equals(currency.name())
                || original.amountMinor() != amountMinor) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Idempotency key already used with "
                            + "different transfer parameters"
            );
        }

        // Exact retry of a completed operation:
        // return the original result without calling Formance.
        if ("SUCCEEDED".equals(original.status())) {

            return new LedgerOperationResult(
                    Objects.requireNonNull(
                            original.transactionId()
                    ),
                    Objects.requireNonNull(
                            original.reference()
                    )
            );
        }

        // Still processing, interrupted, or outcome uncertain.
        // Never start another financial operation blindly.
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Transfer is in progress or requires "
                        + "reconciliation"
        );
    }

    private record StoredRequest(
            UUID recipientId,
            String currency,
            long amountMinor,
            String status,
            Long transactionId,
            String reference
    ) {
    }
}
