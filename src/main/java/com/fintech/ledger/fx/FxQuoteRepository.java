package com.fintech.ledger.fx;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class FxQuoteRepository {

    private final JdbcTemplate jdbc;

    public FxQuoteRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public Optional<StoredQuote> findByUserAndRequestKey(
            UUID userId,
            UUID idempotencyKey
    ) {
        List<StoredQuote> rows = jdbc.query(
                """
                SELECT *
                FROM fx_sandbox_quotes
                WHERE user_id = ?
                  AND idempotency_key = ?
                """,
                FxQuoteRepository::mapRow,
                userId,
                idempotencyKey
        );

        return rows.stream().findFirst();
    }

    public Optional<StoredQuote> findOwnedQuote(
            UUID quoteId,
            UUID userId
    ) {
        List<StoredQuote> rows = jdbc.query(
                """
                SELECT *
                FROM fx_sandbox_quotes
                WHERE quote_id = ?
                  AND user_id = ?
                """,
                FxQuoteRepository::mapRow,
                quoteId,
                userId
        );

        return rows.stream().findFirst();
    }

    /*
     * PostgreSQL's unique constraint arbitrates
     * concurrent requests using the same key.
     *
     * A losing request must read and return the
     * original quote, not create another quote.
     */
    public void insertIfAbsent(StoredQuote quote) {

        Objects.requireNonNull(quote);

        jdbc.update(
                """
                INSERT INTO fx_sandbox_quotes (
                    quote_id,
                    user_id,
                    idempotency_key,
                    source_currency,
                    destination_currency,
                    source_minor,
                    destination_minor,
                    source_scale,
                    destination_scale,
                    exchange_rate,
                    pricing_mode,
                    executable,
                    created_at,
                    expires_at
                )
                VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'SANDBOX_INDICATIVE',
                    FALSE,
                    ?, ?
                )
                ON CONFLICT (user_id, idempotency_key)
                DO NOTHING
                """,
                quote.quoteId(),
                quote.userId(),
                quote.idempotencyKey(),
                quote.sourceCurrency().currencyCode(),
                quote.destinationCurrency().currencyCode(),
                new BigDecimal(quote.sourceMinor()),
                new BigDecimal(quote.destinationMinor()),
                quote.sourceScale(),
                quote.destinationScale(),
                quote.exchangeRate(),
                Timestamp.from(quote.asOf()),
                Timestamp.from(quote.expiresAt())
        );
    }

    private static StoredQuote mapRow(
            ResultSet rs,
            int rowNumber
    ) throws SQLException {

        return new StoredQuote(
                rs.getObject("quote_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("idempotency_key", UUID.class),

                LedgerCurrency.fromCode(
                        rs.getString("source_currency")
                ),

                LedgerCurrency.fromCode(
                        rs.getString("destination_currency")
                ),

                rs.getBigDecimal("source_minor")
                        .toBigIntegerExact(),

                rs.getBigDecimal("destination_minor")
                        .toBigIntegerExact(),

                rs.getInt("source_scale"),
                rs.getInt("destination_scale"),

                rs.getBigDecimal("exchange_rate"),

                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant()
        );
    }

    public record StoredQuote(
            UUID quoteId,
            UUID userId,
            UUID idempotencyKey,

            LedgerCurrency sourceCurrency,
            LedgerCurrency destinationCurrency,

            BigInteger sourceMinor,
            BigInteger destinationMinor,

            int sourceScale,
            int destinationScale,

            BigDecimal exchangeRate,

            Instant asOf,
            Instant expiresAt
    ) {
    }
}