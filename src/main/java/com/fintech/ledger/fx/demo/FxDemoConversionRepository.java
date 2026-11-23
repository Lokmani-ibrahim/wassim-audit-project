
package com.fintech.ledger.fx.demo;

import com.fintech.ledger.fx.FxQuoteRepository.StoredQuote;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class FxDemoConversionRepository {

    private final JdbcTemplate jdbc;

    public FxDemoConversionRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public enum Status {
        CLAIMED,
        RESERVE_POSTING,
        RESERVE_UNKNOWN,
        RESERVED,
        SETTLE_POSTING,
        SETTLE_UNKNOWN,
        SUCCEEDED,
        // The ledger refused the reservation outright (nothing reserved).
        REJECTED
    }

    public record Stored(
            UUID operationId,
            UUID userId,
            UUID quoteId,
            UUID requestKey,
            String bankId,
            LedgerCurrency sourceCurrency,
            LedgerCurrency destinationCurrency,
            long sourceMinor,
            long destinationMinor,
            java.math.BigDecimal exchangeRate,
            Status status,
            String reserveReference,
            Long reserveTransactionId,
            String settlementReference,
            Long settlementTransactionId
    ) {
    }

    public record ClaimResult(
            boolean created,
            Stored operation
    ) {
    }

    public ClaimResult claim(
            UUID userId,
            UUID requestKey,
            StoredQuote quote,
            String bankId
    ) {

        requireOutsideTransaction();

        Objects.requireNonNull(userId);
        Objects.requireNonNull(requestKey);
        Objects.requireNonNull(quote);
        Objects.requireNonNull(bankId);

        if (!userId.equals(quote.userId())) {
            throw new IllegalArgumentException(
                    "FX quote does not belong to customer"
            );
        }

        Optional<Stored> existing =
                findByRequestKey(userId, requestKey);

        if (existing.isPresent()) {
            requireSameQuote(existing.get(), quote.quoteId());
            return new ClaimResult(false, existing.get());
        }

        UUID operationId = UUID.randomUUID();

        String identifier =
                operationId.toString().replace("-", "");

        String reserveReference =
                "fx_reserve:" + identifier;

        String settlementReference =
                "fx_convert:" + identifier;

        final long sourceMinor;
        final long destinationMinor;

        try {
            sourceMinor = quote.sourceMinor().longValueExact();
            destinationMinor =
                    quote.destinationMinor().longValueExact();
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "FX amount is outside supported range",
                    exception
            );
        }

        int inserted;

        try {
            inserted = jdbc.update(
                    """
                    INSERT INTO fx_demo_conversions (
                        operation_id,
                        user_id,
                        quote_id,
                        request_key,
                        bank_id,
                        source_currency,
                        destination_currency,
                        source_minor,
                        destination_minor,
                        exchange_rate,
                        status,
                        reserve_reference,
                        settlement_reference,
                        simulated
                    )
                    VALUES (
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'CLAIMED', ?, ?, TRUE
                    )
                    ON CONFLICT (user_id, request_key)
                    DO NOTHING
                    """,
                    operationId,
                    userId,
                    quote.quoteId(),
                    requestKey,
                    bankId,
                    quote.sourceCurrency().currencyCode(),
                    quote.destinationCurrency().currencyCode(),
                    sourceMinor,
                    destinationMinor,
                    quote.exchangeRate(),
                    reserveReference,
                    settlementReference
            );
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "FX demo quote already used by another operation",
                    exception
            );
        }

        Stored stored = findByRequestKey(
                userId,
                requestKey
        ).orElseThrow(() ->
                new IllegalStateException(
                        "Committed FX demo operation not found"
                )
        );

        requireSameQuote(stored, quote.quoteId());

        return new ClaimResult(inserted == 1, stored);
    }

    public Optional<Stored> findByRequestKey(
            UUID userId,
            UUID requestKey
    ) {

        List<Stored> rows = jdbc.query(
                """
                SELECT *
                FROM fx_demo_conversions
                WHERE user_id = ?
                  AND request_key = ?
                """,
                FxDemoConversionRepository::mapRow,
                userId,
                requestKey
        );

        return rows.stream().findFirst();
    }

    public Optional<Stored> findOwned(
            UUID userId,
            UUID operationId
    ) {

        List<Stored> rows = jdbc.query(
                """
                SELECT *
                FROM fx_demo_conversions
                WHERE user_id = ?
                  AND operation_id = ?
                """,
                FxDemoConversionRepository::mapRow,
                userId,
                operationId
        );

        return rows.stream().findFirst();
    }

    public boolean beginReserve(UUID operationId) {
        return transition(
                operationId,
                Status.CLAIMED,
                Status.RESERVE_POSTING
        );
    }

    public boolean reserveUnknown(UUID operationId) {
        return transition(
                operationId,
                Status.RESERVE_POSTING,
                Status.RESERVE_UNKNOWN
        );
    }

    /*
     * Only for a definitive ledger refusal of the reservation
     * (e.g. insufficient funds): nothing was posted, so the
     * operation is final and its quote becomes usable again
     * (uq_fx_demo_quote_not_rejected, V17).
     */
    public boolean reserveRejected(UUID operationId) {
        return transition(
                operationId,
                Status.RESERVE_POSTING,
                Status.REJECTED
        );
    }

    public boolean reserveConfirmed(
            UUID operationId,
            long transactionId
    ) {

        requireOutsideTransaction();
        requireTransactionId(transactionId);

        return jdbc.update(
                """
                UPDATE fx_demo_conversions
                SET status = 'RESERVED',
                    reserve_transaction_id = ?,
                    updated_at = NOW()
                WHERE operation_id = ?
                  AND status = 'RESERVE_POSTING'
                """,
                transactionId,
                operationId
        ) == 1;
    }

    public boolean beginSettlement(UUID operationId) {
        return transition(
                operationId,
                Status.RESERVED,
                Status.SETTLE_POSTING
        );
    }

    public boolean settlementUnknown(UUID operationId) {
        return transition(
                operationId,
                Status.SETTLE_POSTING,
                Status.SETTLE_UNKNOWN
        );
    }

    /*
     * Call only after the settlement evidence
     * verifier confirms the entire Formance transaction.
     */
    public boolean settlementConfirmed(
            UUID operationId,
            long transactionId
    ) {

        requireOutsideTransaction();
        requireTransactionId(transactionId);

        return jdbc.update(
                """
                UPDATE fx_demo_conversions
                SET status = 'SUCCEEDED',
                    settlement_transaction_id = ?,
                    updated_at = NOW()
                WHERE operation_id = ?
                  AND status = 'SETTLE_POSTING'
                  AND reserve_transaction_id IS NOT NULL
                """,
                transactionId,
                operationId
        ) == 1;
    }

    private boolean transition(
            UUID operationId,
            Status previous,
            Status next
    ) {

        requireOutsideTransaction();

        return jdbc.update(
                """
                UPDATE fx_demo_conversions
                SET status = ?,
                    updated_at = NOW()
                WHERE operation_id = ?
                  AND status = ?
                """,
                next.name(),
                operationId,
                previous.name()
        ) == 1;
    }

    private static void requireSameQuote(
            Stored stored,
            UUID quoteId
    ) {

        if (!stored.quoteId().equals(quoteId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Idempotency key used for another FX quote"
            );
        }
    }

    private static void requireTransactionId(long id) {

        if (id < 0) {
            throw new IllegalArgumentException(
                    "Invalid Formance transaction ID"
            );
        }
    }

    private static void requireOutsideTransaction() {

        if (TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new IllegalStateException(
                    "FX demo execution must not run "
                            + "inside a database transaction"
            );
        }
    }

    private static Stored mapRow(
            ResultSet rs,
            int row
    ) throws SQLException {

        return new Stored(
                rs.getObject("operation_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("quote_id", UUID.class),
                rs.getObject("request_key", UUID.class),
                rs.getString("bank_id"),
                LedgerCurrency.fromCode(
                        rs.getString("source_currency")
                ),
                LedgerCurrency.fromCode(
                        rs.getString("destination_currency")
                ),
                rs.getLong("source_minor"),
                rs.getLong("destination_minor"),
                rs.getBigDecimal("exchange_rate"),
                Status.valueOf(rs.getString("status")),
                rs.getString("reserve_reference"),
                rs.getObject(
                        "reserve_transaction_id",
                        Long.class
                ),
                rs.getString("settlement_reference"),
                rs.getObject(
                        "settlement_transaction_id",
                        Long.class
                )
        );
    }
}
