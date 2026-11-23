
package com.fintech.ledger;

import com.fintech.ledger.adapter.formance.FormancePayoutEvidenceVerifier;
import com.fintech.ledger.adapter.formance.FormancePayoutEvidenceVerifier.Action;
import com.fintech.ledger.adapter.formance.FormancePayoutEvidenceVerifier.ExpectedPayout;
import com.fintech.ledger.adapter.formance.FormancePayoutEvidenceVerifier.Finding;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.notifications.FinancialTransactionNotificationService;
import com.fintech.notifications.TransactionNotificationDirection;
import com.fintech.notifications.TransactionNotificationType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
@ConditionalOnProperty(
        prefix = "neos.payouts",
        name = "reconciliation-enabled",
        havingValue = "true"
)
public class PayoutManualReconciliationService {

    private static final Logger log =
            LoggerFactory.getLogger(
                    PayoutManualReconciliationService.class
            );

    private static final Duration MIN_IN_PROGRESS_AGE =
            Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;
    private final FormancePayoutEvidenceVerifier verifier;
    private final FinancialTransactionNotificationService notifications;
    private final TransactionTemplate transactions;

    public PayoutManualReconciliationService(
            JdbcTemplate jdbc,
            FormancePayoutEvidenceVerifier verifier,
            FinancialTransactionNotificationService notifications,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.verifier = Objects.requireNonNull(verifier);
        this.notifications = Objects.requireNonNull(notifications);

        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager)
        );

        this.transactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
    }

    private record StoredPayout(
            UUID userId,
            String bankId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId,
            String status,
            Long reserveId,
            String reserveReference,
            Long settleId,
            String settleReference,
            Long releaseId,
            String releaseReference,
            Instant updatedAt
    ) {
    }

    private StoredPayout load(
            UUID payoutId,
            boolean forUpdate
    ) {
        String sql = """
                SELECT user_id,
                       settlement_bank_id,
                       currency,
                       amount_minor,
                       operation_id,
                       status,
                       reserve_ledger_transaction_id,
                       reserve_ledger_reference,
                       settle_ledger_transaction_id,
                       settle_ledger_reference,
                       release_ledger_transaction_id,
                       release_ledger_reference,
                       updated_at
                FROM payout_requests
                WHERE payout_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");

        List<StoredPayout> rows = jdbc.query(
                sql,
                (rs, row) -> new StoredPayout(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("settlement_bank_id"),
                        LedgerCurrency.valueOf(
                                rs.getString("currency")
                        ),
                        rs.getLong("amount_minor"),
                        rs.getString("operation_id"),
                        rs.getString("status"),
                        rs.getObject(
                                "reserve_ledger_transaction_id",
                                Long.class
                        ),
                        rs.getString("reserve_ledger_reference"),
                        rs.getObject(
                                "settle_ledger_transaction_id",
                                Long.class
                        ),
                        rs.getString("settle_ledger_reference"),
                        rs.getObject(
                                "release_ledger_transaction_id",
                                Long.class
                        ),
                        rs.getString("release_ledger_reference"),
                        rs.getObject(
                                "updated_at",
                                OffsetDateTime.class
                        ).toInstant()
                ),
                payoutId
        );

        if (rows.size() != 1) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Payout record not found"
            );
        }

        return rows.get(0);
    }

    private static Action actionFor(String status) {

        return switch (status) {
            case "RESERVE_UNKNOWN",
                 "RESERVE_IN_PROGRESS" -> Action.RESERVE;

            case "SETTLE_UNKNOWN",
                 "SETTLE_IN_PROGRESS" -> Action.SETTLE;

            case "RELEASE_UNKNOWN",
                 "RELEASE_IN_PROGRESS" -> Action.RELEASE;

            default -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Payout is not eligible for reconciliation"
            );
        };
    }

    private static boolean eligible(
            StoredPayout payout,
            Instant staleBefore
    ) {
        if (payout.status().endsWith("_UNKNOWN")) {
            return true;
        }

        return payout.status().endsWith("_IN_PROGRESS")
                && !payout.updatedAt().isAfter(staleBefore);
    }

    private static String resultingStatus(Action action) {

        return switch (action) {
            case RESERVE -> "RESERVED";
            case SETTLE -> "LEDGER_SETTLED";
            case RELEASE -> "RELEASED";
        };
    }

    private static String actorSubject() {

        Authentication authentication =
                SecurityContextHolder.getContext()
                        .getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()) {

            throw new AccessDeniedException(
                    "Authenticated reconciliation reviewer required"
            );
        }

        String actor = authentication.getName();

        if (actor == null
                || actor.isBlank()
                || actor.length() > 255) {

            throw new AccessDeniedException(
                    "Invalid reconciliation reviewer identity"
            );
        }

        return actor;
    }

    /**
     * Confirms one already-posted payout operation.
     *
     * No Formance writes. No automatic retries.
     *
     * The expected operation is derived exclusively
     * from the stored payout status and parameters.
     *
     * Not exposed as a customer HTTP endpoint.
     */
    @PreAuthorize("hasAuthority('ROLE_RECONCILIATION_ADMIN')")
    public LedgerOperationResult confirmPosted(
            UUID payoutId
    ) {
        Objects.requireNonNull(payoutId, "payoutId");

        if (TransactionSynchronizationManager
                .isActualTransactionActive()) {

            throw new IllegalStateException(
                    "Reconciliation cannot run inside "
                            + "an enclosing transaction"
            );
        }

        String actor = actorSubject();

        // 1. Snapshot original state without row locks.
        StoredPayout original = load(payoutId, false);

        Action action = actionFor(original.status());

        Instant staleBefore =
                Instant.now().minus(MIN_IN_PROGRESS_AGE);

        if (!eligible(original, staleBefore)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Payout execution is not yet stale"
            );
        }

        // 2. Expected evidence is based on PostgreSQL,
        // never on operator-supplied financial parameters.
        ExpectedPayout expected = new ExpectedPayout(
                action,
                original.userId(),
                original.bankId(),
                original.currency(),
                original.amountMinor(),
                original.operationId()
        );

        FormancePayoutEvidenceVerifier.Result evidence;

        try {
            // Read-only network lookup, outside the DB transaction.
            evidence = verifier.inspect(expected);

        } catch (RuntimeException lookupFailure) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Formance evidence lookup failed",
                    lookupFailure
            );
        }

        if (evidence == null
                || evidence.finding() != Finding.MATCHED
                || evidence.transactionId() == null
                || evidence.transactionId() < 0) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Insufficient Formance evidence; "
                            + "payout remains unresolved"
            );
        }

        long transactionId = evidence.transactionId();
        String reference = expected.reference();
        String nextStatus = resultingStatus(action);

        // 3. Re-read under FOR UPDATE and atomically
        // persist both the confirmation and audit entry.
        LedgerOperationResult result;

        try {
            result = Objects.requireNonNull(
                    transactions.execute(tx -> {

                        StoredPayout current =
                                load(payoutId, true);

                        // Reject any change during remote lookup.
                        if (!original.equals(current)) {
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Payout changed during reconciliation"
                            );
                        }

                        if (actionFor(current.status()) != action
                                || !eligible(current, staleBefore)) {

                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Payout is no longer eligible"
                            );
                        }

                        final String updateSql = switch (action) {

                            case RESERVE -> """
                                    UPDATE payout_requests
                                    SET status = 'RESERVED',
                                        reserve_ledger_transaction_id = ?,
                                        reserve_ledger_reference = ?,
                                        updated_at = NOW()
                                    WHERE payout_id = ?
                                      AND status = ?
                                    """;

                            case SETTLE -> """
                                    UPDATE payout_requests
                                    SET status = 'LEDGER_SETTLED',
                                        settle_ledger_transaction_id = ?,
                                        settle_ledger_reference = ?,
                                        updated_at = NOW()
                                    WHERE payout_id = ?
                                      AND status = ?
                                    """;

                            case RELEASE -> """
                                    UPDATE payout_requests
                                    SET status = 'RELEASED',
                                        release_ledger_transaction_id = ?,
                                        release_ledger_reference = ?,
                                        updated_at = NOW()
                                    WHERE payout_id = ?
                                      AND status = ?
                                    """;
                        };

                        int updated = jdbc.update(
                                updateSql,
                                transactionId,
                                reference,
                                payoutId,
                                original.status()
                        );

                        if (updated != 1) {
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Unable to reconcile payout state"
                            );
                        }

                        jdbc.update(
                                """
                                INSERT INTO payout_reconciliation_audit (
                                    audit_id,
                                    payout_id,
                                    action,
                                    previous_status,
                                    resulting_status,
                                    formance_transaction_id,
                                    formance_reference,
                                    actor_subject
                                )
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                                UUID.randomUUID(),
                                payoutId,
                                action.name(),
                                original.status(),
                                nextStatus,
                                transactionId,
                                reference,
                                actor
                        );

                        return new LedgerOperationResult(
                                transactionId,
                                reference
                        );
                    }),
                    "Reconciliation returned no result"
            );

        } catch (DataAccessException persistenceFailure) {

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Unable to persist payout reconciliation",
                    persistenceFailure
            );
        }

        // 4. Notification follows the committed
        // ledger state, never the uncertain state.
        if (action == Action.SETTLE) {
            try {
                notifications.enqueueSafely(
                        original.userId(),
                        result.transactionId(),
                        result.reference(),
                        TransactionNotificationType.PAYOUT,
                        TransactionNotificationDirection.DEBIT,
                        original.currency().currencyCode(),
                        original.currency().formanceAsset(),
                        original.amountMinor()
                );
            } catch (RuntimeException notificationFailure) {
                log.error(
                        "Reconciled payout notification failed: payoutId={}",
                        payoutId,
                        notificationFailure
                );
            }
        }

        return result;
    }
}
