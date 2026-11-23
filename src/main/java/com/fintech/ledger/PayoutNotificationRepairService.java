
package com.fintech.ledger;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.notifications.FinancialTransactionNotificationService;
import com.fintech.notifications.TransactionNotificationDirection;
import com.fintech.notifications.TransactionNotificationType;
import com.fintech.notifications.NotificationOutboxService;

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
        prefix = "neos.payouts",
        name = "notification-repair-enabled",
        havingValue = "true"
)
public class PayoutNotificationRepairService {

    private static final Logger log =
            LoggerFactory.getLogger(
                    PayoutNotificationRepairService.class
            );

    public enum Outcome {
        OUTBOX_PRESENT,
        STILL_MISSING,
        INVALID_LEDGER_EVIDENCE,
        UNSUPPORTED_TRANSACTION_ID
    }

    public record RepairResult(
            UUID payoutId,
            Outcome outcome
    ) {
    }

    public record RepairBatch(
            List<RepairResult> results,
            UUID nextCursor
    ) {
    }

    private record Candidate(
            UUID payoutId,
            UUID userId,
            String currency,
            long amountMinor,
            String operationId,
            Long transactionId,
            String reference
    ) {
    }

    private final JdbcTemplate jdbc;
    private final FinancialTransactionNotificationService notifications;
    private final NotificationOutboxService outboxService;

    public PayoutNotificationRepairService(
            JdbcTemplate jdbc,
            FinancialTransactionNotificationService notifications,
            NotificationOutboxService outboxService
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.notifications = Objects.requireNonNull(notifications);
        this.outboxService = Objects.requireNonNull(outboxService);
    }

    /**
     * Repairs missing outbox entries for confirmed
     * Formance payout ledger settlements.
     *
     * No Formance calls.
     * No payout status mutations.
     * No direct email sends.
     *
     * The caller can pass nextCursor to scan
     * subsequent batches.
     */
    @PreAuthorize("hasAuthority('ROLE_RECONCILIATION_ADMIN')")
    public RepairBatch repairBatch(
            UUID afterPayoutId,
            int batchSize
    ) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException(
                    "batchSize must be between 1 and 100"
            );
        }

        if (TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Repair must run outside an existing transaction"
            );
        }


        String sql = """
        SELECT p.payout_id,
               p.user_id,
               p.currency,
               p.amount_minor,
               p.operation_id,
               p.settle_ledger_transaction_id,
               p.settle_ledger_reference
        FROM payout_requests p
        WHERE p.status = 'LEDGER_SETTLED'
        """
                + (afterPayoutId == null
                ? ""
                : " AND p.payout_id > ? ")
                + """
        ORDER BY p.payout_id
        LIMIT ?
        """;


        var mapper =
                (org.springframework.jdbc.core.RowMapper<Candidate>)
                        (rs, row) -> new Candidate(
                                rs.getObject(
                                        "payout_id", UUID.class
                                ),
                                rs.getObject(
                                        "user_id", UUID.class
                                ),
                                rs.getString("currency"),
                                rs.getLong("amount_minor"),
                                rs.getString("operation_id"),
                                rs.getObject(
                                        "settle_ledger_transaction_id",
                                        Long.class
                                ),
                                rs.getString(
                                        "settle_ledger_reference"
                                )
                        );

        List<Candidate> candidates =
                afterPayoutId == null
                        ? jdbc.query(sql, mapper, batchSize)
                        : jdbc.query(
                        sql,
                        mapper,
                        afterPayoutId,
                        batchSize
                );

        List<RepairResult> results = new ArrayList<>();

        for (Candidate candidate : candidates) {
            results.add(repairOne(candidate));
        }

        UUID nextCursor = candidates.isEmpty()
                ? null
                : candidates.get(candidates.size() - 1)
                  .payoutId();

        return new RepairBatch(
                List.copyOf(results),
                nextCursor
        );
    }

    private RepairResult repairOne(Candidate payout) {

        // No notification can be reconstructed from
        // incomplete or inconsistent ledger evidence.
        if (payout.transactionId() == null
                || payout.transactionId() < 0
                || payout.amountMinor() <= 0
                || !(
                "payout_settle:" + payout.operationId()
        ).equals(payout.reference())) {

            return new RepairResult(
                    payout.payoutId(),
                    Outcome.INVALID_LEDGER_EVIDENCE
            );
        }

        // The current FinancialTransactionNotificationService
        // rejects transaction ID zero. Do not silently
        // report that repair succeeded for this case.


        String notificationKey =
                "TRANSACTION_COMPLETED:"
                        + payout.transactionId()
                        + ":"
                        + payout.userId()
                        + ":EMAIL";

        try {

            if (outboxService.transactionEmailExists(
                    payout.userId(),
                    payout.transactionId()
            )) {
                return new RepairResult(
                        payout.payoutId(),
                        Outcome.OUTBOX_PRESENT
                );
            }
            LedgerCurrency currency =
                    LedgerCurrency.valueOf(payout.currency());
            // enqueueSafely() catches failures internally,
            // including a missing recipient email.
            notifications.enqueueSafely(
                    payout.userId(),
                    payout.transactionId(),
                    payout.reference(),
                    TransactionNotificationType.PAYOUT,
                    TransactionNotificationDirection.DEBIT,
                    currency.currencyCode(),
                    currency.formanceAsset(),
                    payout.amountMinor()
            );

            // Never trust enqueueSafely() returning normally
            // as proof of successful persistence.
            boolean exists =
                    outboxService.transactionEmailExists(
                            payout.userId(),
                            payout.transactionId()
                    );

            return new RepairResult(
                    payout.payoutId(),
                    exists
                            ? Outcome.OUTBOX_PRESENT
                            : Outcome.STILL_MISSING
            );

        } catch (RuntimeException exception) {

            log.warn(
                    "Payout notification repair failed: payoutId={}",
                    payout.payoutId(),
                    exception
            );

            return new RepairResult(
                    payout.payoutId(),
                    Outcome.STILL_MISSING
            );
        }
    }
}
