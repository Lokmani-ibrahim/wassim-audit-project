
package com.fintech.ledger.fx.demo;

import com.fintech.ledger.fx.FxQuoteRepository.StoredQuote;
import com.fintech.ledger.fx.demo.FxDemoConversionRepository.Status;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class FxDemoConversionRepositoryIntegrationTest {

    @Autowired
    private FxDemoConversionRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> customers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (UUID customer : customers) {
            jdbc.update(
                    """
                    DELETE FROM fx_demo_conversions
                    WHERE user_id = ?
                    """,
                    customer
            );
        }
    }

    @Test
    void migrationV16IsApplied() {

        Boolean migrated = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM flyway_schema_history
                    WHERE version = '16'
                      AND success = TRUE
                )
                """,
                Boolean.class
        );

        assertThat(migrated).isTrue();
    }

    @Test
    void identicalRequestReturnsSameOperation() {

        UUID userId = customer();
        UUID key = UUID.randomUUID();
        StoredQuote quote = quote(userId);

        var first = repository.claim(
                userId,
                key,
                quote,
                "sponsor"
        );

        var retry = repository.claim(
                userId,
                key,
                quote,
                "sponsor"
        );

        assertThat(first.created()).isTrue();
        assertThat(retry.created()).isFalse();

        assertThat(retry.operation().operationId())
                .isEqualTo(first.operation().operationId());

        assertThat(retry.operation().reserveReference())
                .isEqualTo(first.operation().reserveReference());

        assertThat(retry.operation().settlementReference())
                .isEqualTo(first.operation().settlementReference());
    }

    @Test
    void differentQuoteCannotReuseRequestKey() {

        UUID userId = customer();
        UUID key = UUID.randomUUID();

        repository.claim(
                userId,
                key,
                quote(userId),
                "sponsor"
        );

        assertThatThrownBy(() ->
                repository.claim(
                        userId,
                        key,
                        quote(userId),
                        "sponsor"
                )
        ).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {

                    ResponseStatusException exception =
                            (ResponseStatusException) error;

                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void sameQuoteCannotBeUsedByTwoOperations() {

        UUID userId = customer();
        StoredQuote quote = quote(userId);

        repository.claim(
                userId,
                UUID.randomUUID(),
                quote,
                "sponsor"
        );

        assertThatThrownBy(() ->
                repository.claim(
                        userId,
                        UUID.randomUUID(),
                        quote,
                        "sponsor"
                )
        ).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {

                    ResponseStatusException exception =
                            (ResponseStatusException) error;

                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void stateTransitionsRejectDuplicateExecution() {

        UUID userId = customer();

        var claim = repository.claim(
                userId,
                UUID.randomUUID(),
                quote(userId),
                "sponsor"
        );

        UUID operationId = claim.operation().operationId();

        assertThat(repository.beginReserve(operationId))
                .isTrue();

        assertThat(repository.beginReserve(operationId))
                .isFalse();

        assertThat(
                repository.reserveConfirmed(operationId, 100L)
        ).isTrue();

        assertThat(repository.beginSettlement(operationId))
                .isTrue();

        assertThat(repository.beginSettlement(operationId))
                .isFalse();

        assertThat(
                repository.settlementConfirmed(operationId, 101L)
        ).isTrue();

        var stored = repository.findOwned(
                userId,
                operationId
        ).orElseThrow();

        assertThat(stored.status())
                .isEqualTo(Status.SUCCEEDED);

        assertThat(stored.reserveTransactionId())
                .isEqualTo(100L);

        assertThat(stored.settlementTransactionId())
                .isEqualTo(101L);

        assertThat(repository.beginReserve(operationId))
                .isFalse();
    }

    @Test
    void unknownReservationCannotBeBlindlyRetried() {

        UUID userId = customer();

        var claim = repository.claim(
                userId,
                UUID.randomUUID(),
                quote(userId),
                "sponsor"
        );

        UUID operationId = claim.operation().operationId();

        assertThat(repository.beginReserve(operationId))
                .isTrue();

        assertThat(repository.reserveUnknown(operationId))
                .isTrue();

        assertThat(repository.beginReserve(operationId))
                .isFalse();

        assertThat(repository.beginSettlement(operationId))
                .isFalse();
    }

    @Test
    void anotherCustomerCannotReadOperation() {

        UUID userA = customer();
        UUID userB = customer();

        var claim = repository.claim(
                userA,
                UUID.randomUUID(),
                quote(userA),
                "sponsor"
        );

        assertThat(
                repository.findOwned(
                        userB,
                        claim.operation().operationId()
                )
        ).isEmpty();
    }

    private UUID customer() {

        UUID id = UUID.randomUUID();

        customers.add(id);

        return id;
    }

    private static StoredQuote quote(UUID userId) {

        Instant now = Instant.now()
                .truncatedTo(ChronoUnit.MICROS);

        return new StoredQuote(
                UUID.randomUUID(),
                userId,
                UUID.randomUUID(),

                LedgerCurrency.EUR,
                LedgerCurrency.USD,

                BigInteger.valueOf(10_000L),
                BigInteger.valueOf(11_000L),

                2,
                2,

                new BigDecimal("1.10"),

                now,
                now.plusSeconds(30)
        );
    }
}
