
package com.fintech.ledger;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;

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
public class PayoutReservationExecutionService {

    private final JdbcTemplate jdbc;
    private final PayoutReservationService reservationService;
    private final TransactionTemplate transactions;

    public PayoutReservationExecutionService(
            JdbcTemplate jdbc,
            PayoutReservationService reservationService,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.reservationService =
                Objects.requireNonNull(reservationService);

        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager)
        );

        // Critical: transition commits BEFORE Formance.
        // Do not join an enclosing caller transaction.
        this.transactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
    }

    private record Payout(
            UUID payoutId,
            UUID userId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId,
            String status,
            Long reserveTransactionId,
            String reserveReference
    ) {
    }

    private record Claim(
            Payout payout,
            LedgerOperationResult replay
    ) {
    }

    private Claim claim(UUID payoutId) {

        List<Payout> rows = jdbc.query(
                """
                SELECT payout_id,
                       user_id,
                       currency,
                       amount_minor,
                       operation_id,
                       status,
                       reserve_ledger_transaction_id,
                       reserve_ledger_reference
                FROM payout_requests
                WHERE payout_id = ?
                FOR UPDATE
                """,
                (rs, row) -> new Payout(
                        rs.getObject("payout_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
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
                        rs.getString("reserve_ledger_reference")
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

        if ("RESERVED".equals(payout.status())) {

            // Return persisted evidence.
            // Never repeat the ledger submission.
            return new Claim(
                    payout,
                    new LedgerOperationResult(
                            payout.reserveTransactionId(),
                            payout.reserveReference()
                    )
            );
        }

        if (!"REQUESTED".equals(payout.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Payout cannot be reserved in state "
                            + payout.status()
            );
        }

        int updated = jdbc.update(
                """
                UPDATE payout_requests
                SET status = 'RESERVE_IN_PROGRESS',
                    updated_at = NOW()
                WHERE payout_id = ?
                  AND status = 'REQUESTED'
                """,
                payoutId
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "Unable to claim payout reservation"
            );
        }

        return new Claim(payout, null);
    }

    private void transition(
            UUID payoutId,
            String expectedStatus,
            String nextStatus
    ) {

        int updated = jdbc.update(
                """
                UPDATE payout_requests
                SET status = ?,
                    updated_at = NOW()
                WHERE payout_id = ?
                  AND status = ?
                """,
                nextStatus,
                payoutId,
                expectedStatus
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "Unexpected payout reservation state"
            );
        }
    }

    private void markUnknown(UUID payoutId) {

        transactions.executeWithoutResult(status ->
                transition(
                        payoutId,
                        "RESERVE_IN_PROGRESS",
                        "RESERVE_UNKNOWN"
                )
        );
    }

    public LedgerOperationResult reserve(UUID payoutId) {

        Objects.requireNonNull(payoutId, "payoutId");

        // A short, committed PostgreSQL transaction.
        Claim claim = Objects.requireNonNull(
                transactions.execute(
                        status -> claim(payoutId)
                )
        );

        if (claim.replay() != null) {
            return claim.replay();
        }

        Payout payout = claim.payout();

        LedgerOperationResult result;

        try {

            // No open payout database transaction here.
            //
            // Authorization happens inside this service
            // BEFORE the Formance provider is invoked.
            result = reservationService.reserve(
                    payout.userId(),
                    payout.currency(),
                    payout.amountMinor(),
                    payout.operationId()
            );

        } catch (
                PayoutAuthorizationDeniedBeforePostingException denial
        ) {

            // We know no Formance posting occurred.
            try {
                transactions.executeWithoutResult(status ->
                        transition(
                                payoutId,
                                "RESERVE_IN_PROGRESS",
                                "REQUESTED"
                        )
                );
            } catch (RuntimeException cleanupFailure) {

                denial.addSuppressed(cleanupFailure);

                // The claim remains blocked if cleanup failed.
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Pre-ledger payout denial requires "
                                + "claim cleanup review",
                        denial
                );
            }

            throw denial;

        } catch (RuntimeException failure) {

            // Once the provider might have been called,
            // never assume the reservation failed.
            try {
                markUnknown(payoutId);
            } catch (RuntimeException persistenceFailure) {

                failure.addSuppressed(
                        persistenceFailure
                );

                // RESERVE_IN_PROGRESS must remain blocked.
            }

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Payout reservation outcome is uncertain; "
                            + "reconciliation required",
                    failure
            );
        }

        String expectedReference =
                "payout_reserve:" + payout.operationId();

        if (result == null
                || result.transactionId() < 0
                || !expectedReference.equals(
                result.reference()
        )) {

            // A malformed provider result is NOT proof
            // that no posting occurred.
            try {
                markUnknown(payoutId);
            } catch (RuntimeException ignored) {
                // IN_PROGRESS remains blocked.
            }

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Reservation evidence could not be verified"
            );
        }

        try {

            // Persist the exact Formance confirmation.
            transactions.executeWithoutResult(status -> {

                int updated = jdbc.update(
                        """
                        UPDATE payout_requests
                        SET status = 'RESERVED',
                            reserve_ledger_transaction_id = ?,
                            reserve_ledger_reference = ?,
                            updated_at = NOW()
                        WHERE payout_id = ?
                          AND status = 'RESERVE_IN_PROGRESS'
                        """,
                        result.transactionId(),
                        result.reference(),
                        payoutId
                );

                if (updated != 1) {
                    throw new IllegalStateException(
                            "Unable to persist reservation evidence"
                    );
                }
            });

        } catch (RuntimeException persistenceFailure) {

            // Formance already returned success.
            // Never resubmit because the database update failed.
            // The durable IN_PROGRESS claim blocks retries.
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Reservation posted, but confirmation "
                            + "requires reconciliation",
                    persistenceFailure
            );
        }

        return result;
    }
}
