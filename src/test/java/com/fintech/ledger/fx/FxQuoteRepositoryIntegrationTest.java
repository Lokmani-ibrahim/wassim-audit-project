
package com.fintech.ledger.fx;

import com.fintech.ledger.fx.FxQuoteRepository.StoredQuote;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class FxQuoteRepositoryIntegrationTest {

    @Autowired
    private FxQuoteRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayV14IsAppliedAndQuoteTableExists() {

        Boolean migrated = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM flyway_schema_history
                    WHERE version = '14'
                      AND success = TRUE
                )
                """,
                Boolean.class
        );

        assertThat(migrated).isTrue();

        Boolean tableExists = jdbc.queryForObject(
                """
                SELECT to_regclass(
                    'fx_sandbox_quotes'
                ) IS NOT NULL
                """,
                Boolean.class
        );

        assertThat(tableExists).isTrue();
    }

    @Test
    void quotesAreIsolatedByCustomer() {

        UUID customerA = UUID.randomUUID();
        UUID customerB = UUID.randomUUID();

        UUID sharedRequestKey = UUID.randomUUID();

        StoredQuote quoteA = quote(
                customerA,
                sharedRequestKey,
                10_000,
                11_000
        );

        StoredQuote quoteB = quote(
                customerB,
                sharedRequestKey,
                20_000,
                22_000
        );

        try {

            repository.insertIfAbsent(quoteA);
            repository.insertIfAbsent(quoteB);

            assertThat(
                    repository.findOwnedQuote(
                            quoteA.quoteId(),
                            customerA
                    )
            ).isPresent();

            assertThat(
                    repository.findOwnedQuote(
                            quoteA.quoteId(),
                            customerB
                    )
            ).isEmpty();

            assertThat(
                    repository.findOwnedQuote(
                            quoteB.quoteId(),
                            customerA
                    )
            ).isEmpty();

            assertThat(
                    repository.findByUserAndRequestKey(
                            customerA,
                            sharedRequestKey
                    ).orElseThrow().quoteId()
            ).isEqualTo(quoteA.quoteId());

            assertThat(
                    repository.findByUserAndRequestKey(
                            customerB,
                            sharedRequestKey
                    ).orElseThrow().quoteId()
            ).isEqualTo(quoteB.quoteId());

        } finally {
            cleanup(customerA);
            cleanup(customerB);
        }
    }

    @Test
    void concurrentSameKeyCreatesOnlyOneQuote()
            throws Exception {

        UUID customer = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();

        StoredQuote first = quote(
                customer,
                requestKey,
                10_000,
                11_000
        );

        StoredQuote second = quote(
                customer,
                requestKey,
                20_000,
                22_000
        );

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch ready =
                new CountDownLatch(2);

        CountDownLatch start =
                new CountDownLatch(1);

        try {

            Future<?> firstTask = executor.submit(
                    () -> insertConcurrently(
                            first,
                            ready,
                            start
                    )
            );

            Future<?> secondTask = executor.submit(
                    () -> insertConcurrently(
                            second,
                            ready,
                            start
                    )
            );

            assertThat(
                    ready.await(10, TimeUnit.SECONDS)
            ).isTrue();

            start.countDown();

            firstTask.get(30, TimeUnit.SECONDS);
            secondTask.get(30, TimeUnit.SECONDS);

            Integer storedCount = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM fx_sandbox_quotes
                    WHERE user_id = ?
                      AND idempotency_key = ?
                    """,
                    Integer.class,
                    customer,
                    requestKey
            );

            assertThat(storedCount).isEqualTo(1);

            StoredQuote winner =
                    repository.findByUserAndRequestKey(
                            customer,
                            requestKey
                    ).orElseThrow();

            assertThat(winner.quoteId())
                    .isIn(
                            first.quoteId(),
                            second.quoteId()
                    );

            if (winner.quoteId().equals(first.quoteId())) {

                assertThat(winner.sourceMinor())
                        .isEqualTo(first.sourceMinor());

                assertThat(winner.destinationMinor())
                        .isEqualTo(first.destinationMinor());

            } else {

                assertThat(winner.sourceMinor())
                        .isEqualTo(second.sourceMinor());

                assertThat(winner.destinationMinor())
                        .isEqualTo(second.destinationMinor());
            }

            /*
             * The losing insert must not overwrite
             * the winning quote.
             */
            repository.insertIfAbsent(
                    winner.quoteId().equals(first.quoteId())
                            ? second
                            : first
            );

            assertThat(
                    repository.findByUserAndRequestKey(
                            customer,
                            requestKey
                    ).orElseThrow().quoteId()
            ).isEqualTo(winner.quoteId());

        } finally {

            start.countDown();

            executor.shutdownNow();

            executor.awaitTermination(
                    10,
                    TimeUnit.SECONDS
            );

            cleanup(customer);
        }
    }

    @Test
    void databaseRejectsExecutableSandboxQuotes() {

        UUID customer = UUID.randomUUID();

        StoredQuote stored = quote(
                customer,
                UUID.randomUUID(),
                10_000,
                11_000
        );

        try {

            repository.insertIfAbsent(stored);

            assertThatThrownBy(() ->
                    jdbc.update(
                            """
                            UPDATE fx_sandbox_quotes
                            SET executable = TRUE
                            WHERE quote_id = ?
                            """,
                            stored.quoteId()
                    )
            ).isInstanceOf(
                    DataIntegrityViolationException.class
            );

            Boolean executable = jdbc.queryForObject(
                    """
                    SELECT executable
                    FROM fx_sandbox_quotes
                    WHERE quote_id = ?
                    """,
                    Boolean.class,
                    stored.quoteId()
            );

            assertThat(executable).isFalse();

        } finally {
            cleanup(customer);
        }
    }

    private void insertConcurrently(
            StoredQuote quote,
            CountDownLatch ready,
            CountDownLatch start
    ) {

        ready.countDown();

        try {

            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Concurrent test start timed out"
                );
            }

            repository.insertIfAbsent(quote);

        } catch (InterruptedException exception) {

            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Concurrent insert interrupted",
                    exception
            );
        }
    }

    private static StoredQuote quote(
            UUID customer,
            UUID requestKey,
            long sourceMinor,
            long destinationMinor
    ) {

        Instant createdAt =
                Instant.now().truncatedTo(
                        ChronoUnit.MICROS
                );

        return new StoredQuote(
                UUID.randomUUID(),
                customer,
                requestKey,

                LedgerCurrency.EUR,
                LedgerCurrency.USD,

                BigInteger.valueOf(sourceMinor),
                BigInteger.valueOf(destinationMinor),

                2,
                2,

                new BigDecimal("1.10"),

                createdAt,
                createdAt.plusSeconds(30)
        );
    }

    private void cleanup(UUID customerId) {

        jdbc.update(
                """
                DELETE FROM fx_sandbox_quotes
                WHERE user_id = ?
                """,
                customerId
        );
    }
}
