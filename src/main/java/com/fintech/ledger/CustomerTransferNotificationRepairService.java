
package com.fintech.ledger;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.notifications.FinancialTransactionNotificationService;
import com.fintech.notifications.NotificationOutboxService;
import com.fintech.notifications.TransactionNotificationDirection;
import com.fintech.notifications.TransactionNotificationType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        prefix = "neos.transfers.reconciliation",
        name = "notification-repair-enabled",
        havingValue = "true"
)
public class CustomerTransferNotificationRepairService {

    private static final Logger log =
            LoggerFactory.getLogger(
                    CustomerTransferNotificationRepairService.class
            );

    public enum Outcome {
        OUTBOX_PRESENT,
        STILL_MISSING,
        INVALID_CONFIRMED_EVIDENCE
    }

    public record RepairResult(
            UUID requestKey,
            Outcome senderOutcome,
            Outcome recipientOutcome
    ) {
    }

    public record RepairBatch(
            List<RepairResult> results,
            UUID nextCursor
    ) {
    }

    private record Candidate(
            UUID senderId,
            UUID requestKey,
            UUID recipientId,
            String currency,
            long amountMinor,
            String operationId,
            Long transactionId,
            String reference,
            Long auditedTransactionId,
            String auditedOperationId
    ) {
    }

    private final JdbcTemplate jdbc;
    private final FinancialTransactionNotificationService notifications;
    private final NotificationOutboxService outbox;

    public CustomerTransferNotificationRepairService(
            JdbcTemplate jdbc,
            FinancialTransactionNotificationService notifications,
            NotificationOutboxService outbox
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.notifications = Objects.requireNonNull(notifications);
        this.outbox = Objects.requireNonNull(outbox);
    }

    /**
     * Repairs email outbox records for transfers that
     * have already been confirmed through reconciliation.
     *
     * No Formance calls.
     * No financial postings.
     * No transfer status changes.
     * No direct email delivery.
     *
     * Pagination is scoped to one sender.
     */
    @PreAuthorize("hasAuthority('ROLE_RECONCILIATION_ADMIN')")
    public RepairBatch repairForSender(
            UUID senderId,
            UUID afterRequestKey,
            int batchSize
    ) {
        Objects.requireNonNull(senderId, "senderId");

        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException(
                    "batchSize must be between 1 and 100"
            );
        }

        if (TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Notification repair must run outside "
                            + "an existing transaction"
            );
        }

        /*
         * Only rows with a successful reconciliation
         * audit are eligible.
         *
         * We do not trust an unconfirmed UNKNOWN claim.
         */
        String sql = """
                SELECT
                    t.sender_user_id,
                    t.request_key,
                    t.recipient_user_id,
                    t.currency,
                    t.amount_minor,
                    t.operation_id,
                    t.ledger_transaction_id,
                    t.ledger_reference,
                    a.formance_transaction_id
                        AS audited_transaction_id,
                    a.operation_id
                        AS audited_operation_id
                FROM customer_transfer_idempotency t
                JOIN customer_transfer_reconciliation_audit a
                  ON a.sender_user_id = t.sender_user_id
                 AND a.request_key = t.request_key
                WHERE t.sender_user_id = ?
                  AND t.status = 'SUCCEEDED'
                  AND a.action = 'CONFIRM_POSTED'
                  AND a.resulting_status = 'SUCCEEDED'
                """
                + (afterRequestKey == null
                ? ""
                : " AND t.request_key > ? ")
                + """
                ORDER BY t.request_key
                LIMIT ?
                """;

        var mapper =
                (org.springframework.jdbc.core.RowMapper<Candidate>)
                        (rs, row) -> new Candidate(
                                rs.getObject(
                                        "sender_user_id", UUID.class
                                ),
                                rs.getObject(
                                        "request_key", UUID.class
                                ),
                                rs.getObject(
                                        "recipient_user_id", UUID.class
                                ),
                                rs.getString("currency"),
                                rs.getLong("amount_minor"),
                                rs.getString("operation_id"),
                                rs.getObject(
                                        "ledger_transaction_id", Long.class
                                ),
                                rs.getString("ledger_reference"),
                                rs.getObject(
                                        "audited_transaction_id", Long.class
                                ),
                                rs.getString("audited_operation_id")
                        );

        List<Candidate> candidates;

        if (afterRequestKey == null) {
            candidates = jdbc.query(
                    sql, mapper, senderId, batchSize
            );
        } else {
            candidates = jdbc.query(
                    sql,
                    mapper,
                    senderId,
                    afterRequestKey,
                    batchSize
            );
        }

        List<RepairResult> results = new ArrayList<>();

        for (Candidate candidate : candidates) {
            results.add(repairOne(candidate));
        }

        UUID nextCursor = candidates.isEmpty()
                ? null
                : candidates.get(candidates.size() - 1)
                  .requestKey();

        return new RepairBatch(
                List.copyOf(results),
                nextCursor
        );
    }

    private RepairResult repairOne(Candidate transfer) {

        /*
         * Require consistent persisted financial evidence
         * before reconstructing notifications.
         */
        if (transfer.senderId() == null
                || transfer.recipientId() == null
                || transfer.senderId().equals(
                transfer.recipientId()
        )
                || transfer.operationId() == null
                || !transfer.operationId().matches(
                "[A-Za-z0-9_-]{1,128}"
        )
                || transfer.amountMinor() <= 0
                || transfer.transactionId() == null
                || transfer.transactionId() < 0
                || !transfer.transactionId().equals(
                transfer.auditedTransactionId()
        )
                || !transfer.operationId().equals(
                transfer.auditedOperationId()
        )
                || !(
                "internal_transfer:"
                        + transfer.operationId()
        ).equals(transfer.reference())) {

            return invalid(transfer.requestKey());
        }

        final LedgerCurrency currency;

        try {
            currency = LedgerCurrency.valueOf(
                    transfer.currency()
            );
        } catch (RuntimeException exception) {
            return invalid(transfer.requestKey());
        }

        Outcome senderOutcome = repairSide(
                transfer.senderId(),
                transfer,
                currency,
                TransactionNotificationDirection.DEBIT
        );

        Outcome recipientOutcome = repairSide(
                transfer.recipientId(),
                transfer,
                currency,
                TransactionNotificationDirection.CREDIT
        );

        return new RepairResult(
                transfer.requestKey(),
                senderOutcome,
                recipientOutcome
        );
    }

    private Outcome repairSide(
            UUID userId,
            Candidate transfer,
            LedgerCurrency currency,
            TransactionNotificationDirection direction
    ) {
        try {

            if (outbox.transactionEmailExists(
                    userId,
                    transfer.transactionId()
            )) {
                return Outcome.OUTBOX_PRESENT;
            }

            /*
             * enqueueSafely may swallow persistence errors.
             * Its return is not evidence of a durable record.
             */
            notifications.enqueueSafely(
                    userId,
                    transfer.transactionId(),
                    transfer.reference(),
                    TransactionNotificationType.TRANSFER,
                    direction,
                    currency.currencyCode(),
                    currency.formanceAsset(),
                    transfer.amountMinor()
            );

            /*
             * Re-read the actual outbox after enqueueing.
             */
            return outbox.transactionEmailExists(
                    userId,
                    transfer.transactionId()
            )
                    ? Outcome.OUTBOX_PRESENT
                    : Outcome.STILL_MISSING;

        } catch (RuntimeException exception) {

            log.warn(
                    "Transfer notification repair failed: "
                            + "requestKey={} userId={} direction={}",
                    transfer.requestKey(),
                    userId,
                    direction,
                    exception
            );

            return Outcome.STILL_MISSING;
        }
    }

    private static RepairResult invalid(UUID requestKey) {
        return new RepairResult(
                requestKey,
                Outcome.INVALID_CONFIRMED_EVIDENCE,
                Outcome.INVALID_CONFIRMED_EVIDENCE
        );
    }
}
