
package com.fintech.ledger;

import com.fintech.ledger.adapter.formance.FormanceInternalTransferEvidenceVerifier;
import com.fintech.ledger.adapter.formance.FormanceInternalTransferEvidenceVerifier.ExpectedTransfer;
import com.fintech.ledger.adapter.formance.FormanceInternalTransferEvidenceVerifier.Finding;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.notifications.FinancialTransactionNotificationService;
import com.fintech.notifications.TransactionNotificationDirection;
import com.fintech.notifications.TransactionNotificationType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class CustomerTransferManualReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(
            CustomerTransferManualReconciliationService.class
    );

    private static final Duration MIN_IN_PROGRESS_AGE =
            Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;
    private final FormanceInternalTransferEvidenceVerifier verifier;
    private final FinancialTransactionNotificationService notifications;
    private final TransactionTemplate transactionTemplate;

    public CustomerTransferManualReconciliationService(
            JdbcTemplate jdbc,
            FormanceInternalTransferEvidenceVerifier verifier,
            PlatformTransactionManager transactionManager,
            FinancialTransactionNotificationService notifications
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.verifier = Objects.requireNonNull(verifier);
        this.notifications = Objects.requireNonNull(notifications);
        this.transactionTemplate =
                new TransactionTemplate(transactionManager);
    }

    private record StoredTransfer(
            UUID recipientId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId,
            String status,
            Instant updatedAt
    ) {
    }

    private static boolean eligibleForConfirmation(
            StoredTransfer transfer,
            Instant staleBefore
    ) {
        if ("UNKNOWN".equals(transfer.status())) {
            return true;
        }

        return "IN_PROGRESS".equals(transfer.status())
                && !transfer.updatedAt().isAfter(staleBefore);
    }

    /**
     * Confirms a transfer only when fresh Formance evidence
     * matches the original PostgreSQL claim.
     *
     * PostgreSQL status and audit are committed together.
     * Notifications are attempted only after commit.
     *
     * No Formance writes or transfer retries are performed.
     *
     * Exposed through the feature-gated admin controller.
     */
    @PreAuthorize("hasAuthority('ROLE_RECONCILIATION_ADMIN')")
    public LedgerOperationResult confirmPostedTransfer(
            UUID senderId,
            UUID requestKey
    ) {
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(requestKey, "requestKey");

        if (TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Reconciliation must not run inside "
                            + "an existing transaction"
            );
        }

        Authentication authentication =
                SecurityContextHolder.getContext()
                        .getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()) {
            throw new AccessDeniedException(
                    "Authenticated reviewer required"
            );
        }

        String actor = authentication.getName();

        if (actor == null
                || actor.isBlank()
                || actor.length() > 255) {
            throw new AccessDeniedException(
                    "Invalid reviewer identity"
            );
        }

        // STEP 1: Load the existing committed claim.
        StoredTransfer original =
                load(senderId, requestKey, false);

        Instant staleBefore =
                Instant.now().minus(MIN_IN_PROGRESS_AGE);

        if (!eligibleForConfirmation(original, staleBefore)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only UNKNOWN or stale IN_PROGRESS "
                            + "transfers can be confirmed"
            );
        }

        // STEP 2: Verify the original transfer identity
        // against fresh, read-only Formance evidence.
        ExpectedTransfer expected = new ExpectedTransfer(
                senderId,
                original.recipientId(),
                original.currency(),
                original.amountMinor(),
                original.operationId()
        );

        var evidence = verifier.inspect(expected);

        if (evidence == null
                || evidence.finding() != Finding.MATCHED
                || evidence.transactionId() == null
                || evidence.transactionId() < 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Insufficient Formance evidence "
                            + "to confirm the transfer"
            );
        }

        Long transactionId = evidence.transactionId();

        String reference =
                "internal_transfer:" + original.operationId();

        // STEP 3: Commit SUCCEEDED and the audit record
        // atomically, using the existing row-locking design.
        final LedgerOperationResult result;

        try {
            result = Objects.requireNonNull(
                    transactionTemplate.execute(tx -> {

                        StoredTransfer current =
                                load(senderId, requestKey, true);

                        if (!original.equals(current)) {
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Transfer state changed during "
                                            + "evidence verification"
                            );
                        }

                        if (!eligibleForConfirmation(
                                current,
                                staleBefore
                        )) {
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Transfer is no longer eligible "
                                            + "for reconciliation"
                            );
                        }

                        int updated = jdbc.update(
                                """
                                UPDATE customer_transfer_idempotency
                                SET status = 'SUCCEEDED',
                                    ledger_transaction_id = ?,
                                    ledger_reference = ?,
                                    updated_at = NOW()
                                WHERE sender_user_id = ?
                                  AND request_key = ?
                                  AND status = ?
                                """,
                                transactionId,
                                reference,
                                senderId,
                                requestKey,
                                original.status()
                        );

                        if (updated != 1) {
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Transfer could not be reconciled"
                            );
                        }

                        jdbc.update(
                                """
                                INSERT INTO
                                    customer_transfer_reconciliation_audit (
                                        audit_id,
                                        sender_user_id,
                                        request_key,
                                        operation_id,
                                        action,
                                        previous_status,
                                        resulting_status,
                                        formance_transaction_id,
                                        actor_subject
                                    )
                                VALUES (
                                    ?, ?, ?, ?,
                                    'CONFIRM_POSTED',
                                    ?,
                                    'SUCCEEDED',
                                    ?, ?
                                )
                                """,
                                UUID.randomUUID(),
                                senderId,
                                requestKey,
                                original.operationId(),
                                original.status(),
                                transactionId,
                                actor
                        );

                        return new LedgerOperationResult(
                                transactionId,
                                reference
                        );
                    }),
                    "Reconciliation transaction returned null"
            );

        } catch (DataAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Reconciliation persistence failed",
                    exception
            );
        }

        /*
         * STEP 4: The database transaction has committed.
         *
         * An exception while enqueuing email must never:
         * - undo financial reconciliation
         * - change SUCCEEDED back to UNKNOWN
         * - trigger another Formance transfer
         *
         * The notification module uses an idempotent
         * outbox key based on transaction ID and user ID.
         */

        enqueueAfterCommit(
                senderId,
                result,
                original,
                TransactionNotificationDirection.DEBIT
        );

        enqueueAfterCommit(
                original.recipientId(),
                result,
                original,
                TransactionNotificationDirection.CREDIT
        );

        return result;
    }

    private void enqueueAfterCommit(
            UUID userId,
            LedgerOperationResult result,
            StoredTransfer transfer,
            TransactionNotificationDirection direction
    ) {
        try {
            notifications.enqueueSafely(
                    userId,
                    result.transactionId(),
                    result.reference(),
                    TransactionNotificationType.TRANSFER,
                    direction,
                    transfer.currency().currencyCode(),
                    transfer.currency().formanceAsset(),
                    transfer.amountMinor()
            );
        } catch (RuntimeException exception) {

            // Extra protection if a notification implementation
            // unexpectedly propagates an exception.
            log.error(
                    "Reconciled transfer notification failed: "
                            + "operationId={} userId={} direction={}",
                    transfer.operationId(),
                    userId,
                    direction,
                    exception
            );
        }
    }

    private StoredTransfer load(
            UUID senderId,
            UUID requestKey,
            boolean forUpdate
    ) {
        String sql = """
                SELECT
                    recipient_user_id,
                    currency,
                    amount_minor,
                    operation_id,
                    status,
                    updated_at
                FROM customer_transfer_idempotency
                WHERE sender_user_id = ?
                  AND request_key = ?
                """ + (forUpdate ? " FOR UPDATE" : "");

        List<StoredTransfer> rows = jdbc.query(
                sql,
                (rs, index) -> new StoredTransfer(
                        rs.getObject(
                                "recipient_user_id",
                                UUID.class
                        ),
                        LedgerCurrency.valueOf(
                                rs.getString("currency")
                        ),
                        rs.getLong("amount_minor"),
                        rs.getString("operation_id"),
                        rs.getString("status"),
                        rs.getObject(
                                "updated_at",
                                OffsetDateTime.class
                        ).toInstant()
                ),
                senderId,
                requestKey
        );

        if (rows.size() != 1) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Transfer record not found"
            );
        }

        return rows.get(0);
    }
}
