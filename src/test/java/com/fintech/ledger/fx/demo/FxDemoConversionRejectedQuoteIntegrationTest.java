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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * BUG-FX-2 (V17): a REJECTED operation reserved nothing, so its quote
 * can be used again; a live operation still blocks the quote.
 */
@SpringBootTest(properties = {
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class FxDemoConversionRejectedQuoteIntegrationTest {

    @Autowired
    private FxDemoConversionRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> customers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (UUID customer : customers) {
            jdbc.update("DELETE FROM fx_demo_conversions WHERE user_id = ?", customer);
        }
    }

    @Test
    void migrationV17IsApplied() {
        Boolean migrated = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '17' AND success = TRUE)",
                Boolean.class
        );
        assertThat(migrated).isTrue();
    }

    @Test
    void rejectedOperationReleasesItsQuote() {
        UUID userId = customer();
        StoredQuote quote = quote(userId);

        var first = repository.claim(userId, UUID.randomUUID(), quote, "sponsor");
        UUID operationId = first.operation().operationId();
        assertThat(repository.beginReserve(operationId)).isTrue();
        assertThat(repository.reserveRejected(operationId)).isTrue();

        var second = repository.claim(userId, UUID.randomUUID(), quote, "sponsor");

        assertThat(second.created()).isTrue();
        assertThat(second.operation().operationId()).isNotEqualTo(operationId);
        assertThat(repository.findOwned(userId, operationId).orElseThrow().status())
                .isEqualTo(Status.REJECTED);
    }

    @Test
    void liveOperationStillBlocksItsQuote() {
        UUID userId = customer();
        StoredQuote quote = quote(userId);

        var first = repository.claim(userId, UUID.randomUUID(), quote, "sponsor");
        assertThat(repository.beginReserve(first.operation().operationId())).isTrue();

        assertThatThrownBy(() -> repository.claim(userId, UUID.randomUUID(), quote, "sponsor"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void onlyAReservationInFlightCanBeRejected() {
        UUID userId = customer();
        var claimed = repository.claim(userId, UUID.randomUUID(), quote(userId), "sponsor");

        // CLAIMED (reservation not started) cannot jump to REJECTED.
        assertThat(repository.reserveRejected(claimed.operation().operationId())).isFalse();
    }

    private UUID customer() {
        UUID id = UUID.randomUUID();
        customers.add(id);
        return id;
    }

    private static StoredQuote quote(UUID userId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return new StoredQuote(
                UUID.randomUUID(), userId, UUID.randomUUID(),
                LedgerCurrency.EUR, LedgerCurrency.USD,
                BigInteger.valueOf(10_000L), BigInteger.valueOf(10_800L),
                2, 2, new BigDecimal("1.08"),
                now, now.plusSeconds(30)
        );
    }
}
