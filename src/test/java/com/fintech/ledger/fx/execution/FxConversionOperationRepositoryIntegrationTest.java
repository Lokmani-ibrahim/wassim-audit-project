
package com.fintech.ledger.fx.execution;

import com.fintech.ledger.fx.execution
        .FxConversionOperationRepository.ClaimResult;
import com.fintech.ledger.fx.execution
        .FxConversionOperationRepository.NewOperation;
import com.fintech.ledger.fx.execution
        .FxConversionOperationRepository.Status;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class FxConversionOperationRepositoryIntegrationTest {

    @Autowired
    private FxConversionOperationRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> customers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (UUID customer : customers) {
            jdbc.update(
                    "DELETE FROM fx_conversion_operations WHERE user_id = ?",
                    customer
            );
        }
    }

    @Test
    void migrationV15IsApplied() {

        Boolean applied = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM flyway_schema_history
                    WHERE version = '15'
                      AND success = TRUE
                )
                """,
                Boolean.class
        );

        assertThat(applied).isTrue();
    }

    @Test
    void sameRequestReturnsOriginalOperation() {

        UUID customer = customer();
        UUID key = UUID.randomUUID();
        String providerQuoteId = quoteId();
        Instant expiry = expiry();

        NewOperation original =
                operation(customer, key, providerQuoteId, expiry);

        NewOperation retry = new NewOperation(
                UUID.randomUUID(),
                customer,
                key,
                "PARTNER_FX",
                providerQuoteId,
                LedgerCurrency.EUR,
                LedgerCurrency.USD,
                10_000L,
                11_000L,
                new BigDecimal("1.10"),
                expiry
        );

        ClaimResult first = repository.claim(original);
        ClaimResult second = repository.claim(retry);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();

        assertThat(second.operation().operationId())
                .isEqualTo(first.operation().operationId());

        assertThat(second.operation().ledgerReference())
                .isEqualTo(first.operation().ledgerReference());

        assertThat(second.operation().status())
                .isEqualTo(Status.CLAIMED);
    }

    @Test
    void conflictingRequestParametersAreRejected() {

        UUID customer = customer();
        UUID key = UUID.randomUUID();
        String quoteId = quoteId();
        Instant expiry = expiry();

        repository.claim(
                operation(customer, key, quoteId, expiry)
        );

        NewOperation conflict = new NewOperation(
                UUID.randomUUID(),
                customer,
                key,
                "PARTNER_FX",
                quoteId,
                LedgerCurrency.EUR,
                LedgerCurrency.USD,
                20_000L,
                22_000L,
                new BigDecimal("1.10"),
                expiry
        );

        assertThatThrownBy(() -> repository.claim(conflict))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException exception =
                            (ResponseStatusException) error;

                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void oneProviderQuoteCannotBeClaimedTwice() {

        UUID customer = customer();
        String providerQuoteId = quoteId();
        Instant expiry = expiry();

        repository.claim(
                operation(
                        customer,
                        UUID.randomUUID(),
                        providerQuoteId,
                        expiry
                )
        );

        NewOperation duplicate = operation(
                customer,
                UUID.randomUUID(),
                providerQuoteId,
                expiry
        );

        assertThatThrownBy(() -> repository.claim(duplicate))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException exception =
                            (ResponseStatusException) error;

                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void operationTransitionsRequireReconciliationAfterUnknown() {

        UUID customer = customer();

        ClaimResult claim = repository.claim(
                operation(
                        customer,
                        UUID.randomUUID(),
                        quoteId(),
                        expiry()
                )
        );

        UUID operationId = claim.operation().operationId();

        assertThat(repository.markPosting(operationId))
                .isTrue();

        assertThat(repository.markPosting(operationId))
                .isFalse();

        assertThat(repository.markUnknown(operationId))
                .isTrue();

        assertThat(repository.markPosting(operationId))
                .isFalse();

        /*
         * This method represents a result confirmed
         * by a future evidence-verification component.
         *
         * No Formance call occurs in this test.
         */
        assertThat(repository.markSucceeded(operationId, 12345L))
                .isTrue();

        assertThat(repository.markUnknown(operationId))
                .isFalse();

        var stored = repository.findByRequestKey(
                customer,
                claim.operation().idempotencyKey()
        ).orElseThrow();

        assertThat(stored.status())
                .isEqualTo(Status.SUCCEEDED);

        assertThat(stored.ledgerTransactionId())
                .isEqualTo(12345L);
    }

    @Test
    void concurrentClaimsStoreOnlyOneOperation()
            throws Exception {

        UUID customer = customer();
        UUID key = UUID.randomUUID();
        String providerQuoteId = quoteId();
        Instant quoteExpiry = expiry();

        NewOperation first =
                operation(customer, key, providerQuoteId, quoteExpiry);

        NewOperation second = new NewOperation(
                UUID.randomUUID(),
                customer,
                key,
                "PARTNER_FX",
                providerQuoteId,
                LedgerCurrency.EUR,
                LedgerCurrency.USD,
                10_000L,
                11_000L,
                new BigDecimal("1.10"),
                quoteExpiry
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<ClaimResult> a = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Start timed out");
                }
                return repository.claim(first);
            });

            Future<ClaimResult> b = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Start timed out");
                }
                return repository.claim(second);
            });

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .isTrue();

            start.countDown();

            ClaimResult resultA =
                    a.get(30, TimeUnit.SECONDS);

            ClaimResult resultB =
                    b.get(30, TimeUnit.SECONDS);

            assertThat(resultA.operation().operationId())
                    .isEqualTo(resultB.operation().operationId());

            assertThat(resultA.created() != resultB.created())
                    .isTrue();

            Integer count = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM fx_conversion_operations
                    WHERE user_id = ?
                      AND idempotency_key = ?
                    """,
                    Integer.class,
                    customer,
                    key
            );

            assertThat(count).isEqualTo(1);

        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsSandboxProvider() {

        UUID customer = customer();

        assertThatThrownBy(() -> new NewOperation(
                UUID.randomUUID(),
                customer,
                UUID.randomUUID(),
                "SANDBOX_INDICATIVE",
                quoteId(),
                LedgerCurrency.EUR,
                LedgerCurrency.USD,
                10_000L,
                11_000L,
                new BigDecimal("1.10"),
                expiry()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private UUID customer() {

        UUID id = UUID.randomUUID();

        customers.add(id);

        return id;
    }

    private static String quoteId() {
        return "quote-" + UUID.randomUUID();
    }

    private static Instant expiry() {
        return Instant.now()
                .plusSeconds(600)
                .truncatedTo(ChronoUnit.MICROS);
    }

    private static NewOperation operation(
            UUID customer,
            UUID key,
            String providerQuoteId,
            Instant expiry
    ) {

        return new NewOperation(
                UUID.randomUUID(),
                customer,
                key,
                "PARTNER_FX",
                providerQuoteId,
                LedgerCurrency.EUR,
                LedgerCurrency.USD,
                10_000L,
                11_000L,
                new BigDecimal("1.10"),
                expiry
        );
    }
}
