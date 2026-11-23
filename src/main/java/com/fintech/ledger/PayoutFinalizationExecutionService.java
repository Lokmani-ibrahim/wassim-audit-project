
package com.fintech.ledger;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.port.LedgerProvider;
import com.fintech.notifications.FinancialTransactionNotificationService;
import com.fintech.notifications.TransactionNotificationDirection;
import com.fintech.notifications.TransactionNotificationType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        prefix = "neos.payouts",
        name = "ledger-finalization-enabled",
        havingValue = "true"
)
public class PayoutFinalizationExecutionService {

    private static final Logger log =
            LoggerFactory.getLogger(
                    PayoutFinalizationExecutionService.class
            );

    private enum Action {
        SETTLE(
                "SETTLE_IN_PROGRESS",
                "SETTLE_UNKNOWN",
                "LEDGER_SETTLED",
                "payout_settle:"
        ),
        RELEASE(
                "RELEASE_IN_PROGRESS",
                "RELEASE_UNKNOWN",
                "RELEASED",
                "payout_release:"
        );

        final String inProgress;
        final String unknown;
        final String completed;
        final String referencePrefix;

        Action(
                String inProgress,
                String unknown,
                String completed,
                String referencePrefix
        ) {
            this.inProgress = inProgress;
            this.unknown = unknown;
            this.completed = completed;
            this.referencePrefix = referencePrefix;
        }
    }

    private record Payout(
            UUID payoutId,
            UUID userId,
            String bankId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId,
            String status,
            Long settleTransactionId,
            String settleReference,
            Long releaseTransactionId,
            String releaseReference
    ) {
    }

    private record Claim(
            Payout payout,
            LedgerOperationResult replay
    ) {
    }

    private final JdbcTemplate jdbc;
    private final LedgerProvider ledgerProvider;
    private final FinancialTransactionNotificationService notifications;
    private final TransactionTemplate transactions;

    public PayoutFinalizationExecutionService(
            JdbcTemplate jdbc,
            LedgerProvider ledgerProvider,
            FinancialTransactionNotificationService notifications,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.ledgerProvider = Objects.requireNonNull(ledgerProvider);
        this.notifications = Objects.requireNonNull(notifications);

        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager)
        );

        this.transactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
    }

    /**
     * Formance ledger settlement only.
     *
     * NOT evidence that an external beneficiary
     * received a bank payment.
     *
     * Do not enable against real customer funds until
     * bank-payment evidence and operational authorization
     * have been integrated.
     */
    public LedgerOperationResult settleLedgerOnly(
            UUID payoutId
    ) {
        return execute(payoutId, Action.SETTLE);
    }

    /**
     * Returns reserved funds to the available wallet.
     * No external bank payout is performed.
     */
    public LedgerOperationResult releaseHold(
            UUID payoutId
    ) {
        return execute(payoutId, Action.RELEASE);
    }

    private Claim claim(
            UUID payoutId,
            Action action
    ) {
        List<Payout> rows = jdbc.query(
                """
                SELECT payout_id,
                       user_id,
                       settlement_bank_id,
                       currency,
                       amount_minor,
                       operation_id,
                       status,
                       settle_ledger_transaction_id,
                       settle_ledger_reference,
                       release_ledger_transaction_id,
                       release_ledger_reference
                FROM payout_requests
                WHERE payout_id = ?
                FOR UPDATE
                """,
                (rs, row) -> new Payout(
                        rs.getObject("payout_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("settlement_bank_id"),
                        LedgerCurrency.valueOf(
                                rs.getString("currency")
                        ),
                        rs.getLong("amount_minor"),
                        rs.getString("operation_id"),
                        rs.getString("status"),
                        rs.getObject(
                                "settle_ledger_transaction_id",
                                Long.class
                        ),
                        rs.getString("settle_ledger_reference"),
                        rs.getObject(
                                "release_ledger_transaction_id",
                                Long.class
                        ),
                        rs.getString("release_ledger_reference")
                ),
                payoutId
        );

        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Payout request not found"
            );
        }

        Payout payout = rows.get(0);

        // Exact replay of an already confirmed result.
        // No second ledger posting.
        if (action.completed.equals(payout.status())) {

            Long transactionId = action == Action.SETTLE
                    ? payout.settleTransactionId()
                    : payout.releaseTransactionId();

            String reference = action == Action.SETTLE
                    ? payout.settleReference()
                    : payout.releaseReference();

            return new Claim(
                    payout,
                    new LedgerOperationResult(
                            transactionId,
                            reference
                    )
            );
        }

        // Only RESERVED can transition into either
        // finalization branch.
        //
        // This also blocks *_UNKNOWN and opposing
        // *_IN_PROGRESS states.
        if (!"RESERVED".equals(payout.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Payout finalization blocked in state "
                            + payout.status()
            );
        }

        if (action == Action.SETTLE
                && (payout.bankId() == null
                || payout.bankId().isBlank())) {
            throw new IllegalStateException(
                    "Settlement bank is not recorded"
            );
        }

        int updated = jdbc.update(
                """
                UPDATE payout_requests
                SET status = ?,
                    updated_at = NOW()
                WHERE payout_id = ?
                  AND status = 'RESERVED'
                """,
                action.inProgress,
                payoutId
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "Unable to claim payout finalization"
            );
        }

        return new Claim(payout, null);
    }

    private void transitionUnknown(
            UUID payoutId,
            Action action
    ) {
        int updated = jdbc.update(
                """
                UPDATE payout_requests
                SET status = ?,
                    updated_at = NOW()
                WHERE payout_id = ?
                  AND status = ?
                """,
                action.unknown,
                payoutId,
                action.inProgress
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "Unable to record uncertain payout outcome"
            );
        }
    }

    private void markUnknownBestEffort(
            UUID payoutId,
            Action action,
            RuntimeException originalFailure
    ) {
        try {
            transactions.executeWithoutResult(status ->
                    transitionUnknown(payoutId, action)
            );
        } catch (RuntimeException persistenceFailure) {

            originalFailure.addSuppressed(
                    persistenceFailure
            );

            // The committed IN_PROGRESS state remains
            // a retry blocker if UNKNOWN cannot be saved.
            log.error(
                    "Unable to persist uncertain payout outcome: payoutId={}",
                    payoutId,
                    persistenceFailure
            );
        }
    }

    private void confirm(
            UUID payoutId,
            Action action,
            LedgerOperationResult result
    ) {
        final String sql;

        if (action == Action.SETTLE) {
            sql = """
                UPDATE payout_requests
                SET status = 'LEDGER_SETTLED',
                    settle_ledger_transaction_id = ?,
                    settle_ledger_reference = ?,
                    updated_at = NOW()
                WHERE payout_id = ?
                  AND status = 'SETTLE_IN_PROGRESS'
                """;
        } else {
            sql = """
                UPDATE payout_requests
                SET status = 'RELEASED',
                    release_ledger_transaction_id = ?,
                    release_ledger_reference = ?,
                    updated_at = NOW()
                WHERE payout_id = ?
                  AND status = 'RELEASE_IN_PROGRESS'
                """;
        }

        int updated = jdbc.update(
                sql,
                result.transactionId(),
                result.reference(),
                payoutId
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "Unable to persist confirmed payout finalization"
            );
        }
    }

    private void notifyAfterSettlement(
            Payout payout,
            LedgerOperationResult result
    ) {
        try {
            notifications.enqueueSafely(
                    payout.userId(),
                    result.transactionId(),
                    result.reference(),
                    TransactionNotificationType.PAYOUT,
                    TransactionNotificationDirection.DEBIT,
                    payout.currency().currencyCode(),
                    payout.currency().formanceAsset(),
                    payout.amountMinor()
            );
        } catch (RuntimeException notificationFailure) {

            // Never turn a confirmed financial movement
            // into an apparent financial failure.
            log.error(
                    "Ledger settlement persisted but notification failed: payoutId={}",
                    payout.payoutId(),
                    notificationFailure
            );
        }
    }

    private LedgerOperationResult execute(
            UUID payoutId,
            Action action
    ) {
        Objects.requireNonNull(payoutId, "payoutId");

        // PostgreSQL transaction commits before
        // the external Formance invocation.
        Claim claim = Objects.requireNonNull(
                transactions.execute(
                        status -> claim(payoutId, action)
                )
        );

        if (claim.replay() != null) {
            return claim.replay();
        }

        Payout payout = claim.payout();
        LedgerOperationResult result;

        try {
            if (action == Action.SETTLE) {
                result = ledgerProvider.settlePayout(
                        payout.userId(),
                        payout.bankId(),
                        payout.currency(),
                        payout.amountMinor(),
                        payout.operationId()
                );
            } else {
                result = ledgerProvider.releasePayout(
                        payout.userId(),
                        payout.currency(),
                        payout.amountMinor(),
                        payout.operationId()
                );
            }
        } catch (RuntimeException providerFailure) {

            // The provider might have received the posting.
            // Never automatically resubmit.
            markUnknownBestEffort(
                    payoutId,
                    action,
                    providerFailure
            );

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Payout finalization outcome uncertain; reconciliation required",
                    providerFailure
            );
        }

        String expectedReference =
                action.referencePrefix + payout.operationId();

        if (result == null
                || result.transactionId() < 0
                || !expectedReference.equals(result.reference())) {

            RuntimeException invalidEvidence =
                    new IllegalStateException(
                            "Formance finalization evidence mismatch"
                    );

            markUnknownBestEffort(
                    payoutId,
                    action,
                    invalidEvidence
            );

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Unverified payout result; reconciliation required",
                    invalidEvidence
            );
        }

        try {
            transactions.executeWithoutResult(
                    status -> confirm(
                            payoutId,
                            action,
                            result
                    )
            );
        } catch (RuntimeException persistenceFailure) {

            // The ledger call returned success.
            // Preserve IN_PROGRESS if confirmation
            // could not be committed.
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Payout posted but database confirmation requires reconciliation",
                    persistenceFailure
            );
        }

        // Notification occurs only after confirmed
        // settlement evidence has been committed.
        if (action == Action.SETTLE) {
            notifyAfterSettlement(payout, result);
        }

        return result;
    }
}
