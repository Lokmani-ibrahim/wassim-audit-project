
package com.fintech.ledger.fx.execution;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class FxConversionOperationRepository {

    private final JdbcTemplate jdbc;

    public FxConversionOperationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /*
     * Never call this inside a surrounding transaction.
     *
     * The claim must commit before the future orchestrator
     * attempts any Formance posting.
     *
     * This class does not call Formance.
     */
    public ClaimResult claim(NewOperation request) {

        requireOutsideTransaction();
        Objects.requireNonNull(request);

        Optional<StoredOperation> previous = findByRequestKey(
                request.userId(),
                request.idempotencyKey()
        );

        if (previous.isPresent()) {
            requireSameRequest(previous.get(), request);
            return new ClaimResult(false, previous.get());
        }

        if (!request.quoteExpiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "FX quote has expired"
            );
        }

        String reference = reference(request.operationId());

        int inserted;

        try {
            inserted = jdbc.update(
                    """
                    INSERT INTO fx_conversion_operations (
                        operation_id,
                        user_id,
                        idempotency_key,
                        provider,
                        provider_quote_id,
                        source_currency,
                        destination_currency,
                        source_minor,
                        destination_minor,
                        exchange_rate,
                        quote_expires_at,
                        ledger_reference,
                        status
                    )
                    VALUES (
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'CLAIMED'
                    )
                    ON CONFLICT (user_id, idempotency_key)
                    DO NOTHING
                    """,
                    request.operationId(),
                    request.userId(),
                    request.idempotencyKey(),
                    request.provider(),
                    request.providerQuoteId(),
                    request.sourceCurrency().currencyCode(),
                    request.destinationCurrency().currencyCode(),
                    request.sourceMinor(),
                    request.destinationMinor(),
                    request.exchangeRate(),
                    Timestamp.from(request.quoteExpiresAt()),
                    reference
            );

        } catch (DataIntegrityViolationException exception) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "FX quote or operation is already claimed",
                    exception
            );
        }

        StoredOperation stored = findByRequestKey(
                request.userId(),
                request.idempotencyKey()
        ).orElseThrow(() ->
                new IllegalStateException(
                        "Committed FX claim cannot be found"
                )
        );

        requireSameRequest(stored, request);

        return new ClaimResult(inserted == 1, stored);
    }

    public Optional<StoredOperation> findByRequestKey(
            UUID userId,
            UUID idempotencyKey
    ) {

        Objects.requireNonNull(userId);
        Objects.requireNonNull(idempotencyKey);

        List<StoredOperation> rows = jdbc.query(
                """
                SELECT *
                FROM fx_conversion_operations
                WHERE user_id = ?
                  AND idempotency_key = ?
                """,
                (rs, row) -> {

                    long transactionId = rs.getLong(
                            "ledger_transaction_id"
                    );

                    Long confirmedTransactionId =
                            rs.wasNull() ? null : transactionId;

                    return new StoredOperation(
                            rs.getObject(
                                    "operation_id",
                                    UUID.class
                            ),
                            rs.getObject(
                                    "user_id",
                                    UUID.class
                            ),
                            rs.getObject(
                                    "idempotency_key",
                                    UUID.class
                            ),
                            rs.getString("provider"),
                            rs.getString("provider_quote_id"),
                            LedgerCurrency.fromCode(
                                    rs.getString("source_currency")
                            ),
                            LedgerCurrency.fromCode(
                                    rs.getString("destination_currency")
                            ),
                            rs.getLong("source_minor"),
                            rs.getLong("destination_minor"),
                            rs.getBigDecimal("exchange_rate"),
                            rs.getTimestamp(
                                    "quote_expires_at"
                            ).toInstant(),
                            rs.getString("ledger_reference"),
                            Status.valueOf(rs.getString("status")),
                            confirmedTransactionId
                    );
                },
                userId,
                idempotencyKey
        );

        return rows.stream().findFirst();
    }

    /*
     * This transition is the durable boundary immediately
     * BEFORE an external Formance request may be attempted.
     *
     * If NEOS crashes after POSTING is committed, the
     * operation must be reconciled, not blindly restarted.
     */
    public boolean markPosting(UUID operationId) {

        requireOutsideTransaction();
        Objects.requireNonNull(operationId);

        return jdbc.update(
                """
                UPDATE fx_conversion_operations
                SET status = 'POSTING',
                    updated_at = NOW()
                WHERE operation_id = ?
                  AND status = 'CLAIMED'
                """,
                operationId
        ) == 1;
    }

    /*
     * Use after a timeout or otherwise ambiguous posting
     * outcome. UNKNOWN must block automatic re-execution.
     */
    public boolean markUnknown(UUID operationId) {

        requireOutsideTransaction();
        Objects.requireNonNull(operationId);

        return jdbc.update(
                """
                UPDATE fx_conversion_operations
                SET status = 'UNKNOWN',
                    updated_at = NOW()
                WHERE operation_id = ?
                  AND status = 'POSTING'
                """,
                operationId
        ) == 1;
    }

    /*
     * The caller must independently verify Formance evidence
     * before invoking this method.
     *
     * A positive transaction ID alone is not proof that
     * the correct financial postings occurred.
     */
    public boolean markSucceeded(
            UUID operationId,
            long verifiedLedgerTransactionId
    ) {

        requireOutsideTransaction();
        Objects.requireNonNull(operationId);

        if (verifiedLedgerTransactionId <= 0) {
            throw new IllegalArgumentException(
                    "Verified ledger transaction ID must be positive"
            );
        }

        return jdbc.update(
                """
                UPDATE fx_conversion_operations
                SET status = 'SUCCEEDED',
                    ledger_transaction_id = ?,
                    updated_at = NOW()
                WHERE operation_id = ?
                  AND status IN ('POSTING', 'UNKNOWN')
                  AND ledger_transaction_id IS NULL
                """,
                verifiedLedgerTransactionId,
                operationId
        ) == 1;
    }

    private static void requireSameRequest(
            StoredOperation stored,
            NewOperation request
    ) {

        boolean same =
                stored.userId().equals(request.userId())
                        && stored.idempotencyKey().equals(
                        request.idempotencyKey()
                )
                        && stored.provider().equals(request.provider())
                        && stored.providerQuoteId().equals(
                        request.providerQuoteId()
                )
                        && stored.sourceCurrency()
                        == request.sourceCurrency()
                        && stored.destinationCurrency()
                        == request.destinationCurrency()
                        && stored.sourceMinor() == request.sourceMinor()
                        && stored.destinationMinor()
                        == request.destinationMinor()
                        && stored.exchangeRate().compareTo(
                        request.exchangeRate()
                ) == 0
                        && stored.quoteExpiresAt().equals(
                        request.quoteExpiresAt()
                );

        if (!same) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Idempotency key reused with different "
                            + "FX conversion parameters"
            );
        }
    }

    private static void requireOutsideTransaction() {

        if (TransactionSynchronizationManager
                .isActualTransactionActive()) {

            throw new IllegalStateException(
                    "FX operation transitions must commit "
                            + "outside an enclosing transaction"
            );
        }
    }

    private static String reference(UUID operationId) {

        return "fx_convert:"
                + operationId.toString().replace("-", "");
    }

    public enum Status {
        CLAIMED,
        POSTING,
        UNKNOWN,
        SUCCEEDED
    }

    public record NewOperation(
            UUID operationId,
            UUID userId,
            UUID idempotencyKey,
            String provider,
            String providerQuoteId,
            LedgerCurrency sourceCurrency,
            LedgerCurrency destinationCurrency,
            long sourceMinor,
            long destinationMinor,
            BigDecimal exchangeRate,
            Instant quoteExpiresAt
    ) {

        public NewOperation {

            Objects.requireNonNull(operationId);
            Objects.requireNonNull(userId);
            Objects.requireNonNull(idempotencyKey);
            Objects.requireNonNull(provider);
            Objects.requireNonNull(providerQuoteId);
            Objects.requireNonNull(sourceCurrency);
            Objects.requireNonNull(destinationCurrency);
            Objects.requireNonNull(exchangeRate);
            Objects.requireNonNull(quoteExpiresAt);

            provider = provider.trim();
            providerQuoteId = providerQuoteId.trim();

            if (provider.isEmpty() || provider.length() > 64
                    || providerQuoteId.isEmpty()
                    || providerQuoteId.length() > 128) {

                throw new IllegalArgumentException(
                        "Invalid FX provider identity"
                );
            }

            String normalized =
                    provider.toUpperCase(java.util.Locale.ROOT);

            if (normalized.contains("SANDBOX")
                    || normalized.contains("MOCK")
                    || normalized.contains("TEST")) {

                throw new IllegalArgumentException(
                        "Sandbox pricing cannot create "
                                + "executable FX operations"
                );
            }

            if (sourceCurrency == destinationCurrency) {
                throw new IllegalArgumentException(
                        "FX currencies must differ"
                );
            }

            if (sourceMinor <= 0 || destinationMinor <= 0) {
                throw new IllegalArgumentException(
                        "FX amounts must be positive"
                );
            }

            if (exchangeRate.signum() <= 0
                    || exchangeRate.scale() > 18
                    || exchangeRate.precision() > 38) {

                throw new IllegalArgumentException(
                        "Invalid FX exchange-rate precision"
                );
            }

            quoteExpiresAt = quoteExpiresAt.truncatedTo(
                    ChronoUnit.MICROS
            );
        }
    }

    public record StoredOperation(
            UUID operationId,
            UUID userId,
            UUID idempotencyKey,
            String provider,
            String providerQuoteId,
            LedgerCurrency sourceCurrency,
            LedgerCurrency destinationCurrency,
            long sourceMinor,
            long destinationMinor,
            BigDecimal exchangeRate,
            Instant quoteExpiresAt,
            String ledgerReference,
            Status status,
            Long ledgerTransactionId
    ) {
    }

    public record ClaimResult(
            boolean created,
            StoredOperation operation
    ) {
    }
}
