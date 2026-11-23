package com.fintech.ledger.fx;

import com.fintech.identity.IdentityLookupService;
import com.fintech.kyc.CustomerDebitAuthorization;
import com.fintech.ledger.fx.api.FxQuoteRequest;
import com.fintech.ledger.fx.api.FxQuoteResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FxQuoteServiceTest {

    private static final String SUBJECT = "customer-subject";

    @Mock
    private IdentityLookupService identityLookupService;

    @Mock
    private CustomerDebitAuthorization authorization;

    @Mock
    private FxQuoteRepository repository;

    private UUID customerId;

    private AtomicReference<FxQuoteRepository.StoredQuote> saved;

    @BeforeEach
    void setup() {

        customerId = UUID.randomUUID();

        saved = new AtomicReference<>();

        lenient()
                .when(
                        identityLookupService
                                .findUserIdByKeycloakSubject(SUBJECT)
                )
                .thenReturn(Optional.of(customerId));

        /*
         * Lightweight in-memory behavior for service tests.
         *
         * PostgreSQL's real unique constraint and concurrent
         * insert behavior must be checked separately in a
         * database integration test.
         */
        lenient()
                .when(
                        repository.findByUserAndRequestKey(
                                eq(customerId),
                                any(UUID.class)
                        )
                )
                .thenAnswer(invocation -> {

                    UUID key = invocation.getArgument(1);

                    FxQuoteRepository.StoredQuote current =
                            saved.get();

                    if (current == null
                            || !current.idempotencyKey().equals(key)) {
                        return Optional.empty();
                    }

                    return Optional.of(current);
                });

        lenient()
                .doAnswer(invocation -> {

                    FxQuoteRepository.StoredQuote candidate =
                            invocation.getArgument(0);

                    saved.compareAndSet(null, candidate);

                    return null;

                })
                .when(repository)
                .insertIfAbsent(any());
    }

    private FxQuoteService service(String rates) {

        return new FxQuoteService(
                identityLookupService,
                authorization,
                new FxSandboxRateProvider(rates),
                repository,
                true,
                true
        );
    }

    private static FxQuoteRequest request(
            UUID key,
            String source,
            String destination,
            String amount
    ) {

        return new FxQuoteRequest(
                key,
                source,
                destination,
                amount
        );
    }

    @Test
    void persistsQuoteWithCustomerAndQuoteId() {

        UUID key = UUID.randomUUID();

        FxQuoteResponse result = service("EUR/USD=1.10")
                .quote(
                        SUBJECT,
                        request(key, "EUR", "USD", "10000")
                );

        assertNotNull(result.quoteId());
        assertEquals(key, result.idempotencyKey());

        assertEquals("EUR", result.sourceCurrency());
        assertEquals("USD", result.destinationCurrency());

        assertEquals("10000", result.sourceMinor());
        assertEquals("11000", result.destinationMinor());

        assertEquals("1.1", result.exchangeRate());
        assertEquals("SANDBOX_INDICATIVE", result.pricingMode());

        assertFalse(result.executable());

        assertNotNull(result.asOf());
        assertEquals(
                result.asOf().plusSeconds(30),
                result.expiresAt()
        );

        verify(repository).insertIfAbsent(any());

        verify(authorization).requireEligible(customerId);
    }

    @Test
    void respectsDifferentCurrencyScales() {

        FxQuoteResponse result = service("EUR/TND=3.20")
                .quote(
                        SUBJECT,
                        request(
                                UUID.randomUUID(),
                                "EUR",
                                "TND",
                                "1234"
                        )
                );

        assertEquals(2, result.sourceScale());
        assertEquals(3, result.destinationScale());
        assertEquals("39488", result.destinationMinor());
    }

    @Test
    void retryReturnsExactlyTheOriginalQuote() {

        UUID key = UUID.randomUUID();

        FxQuoteService firstService =
                service("EUR/USD=1.10");

        FxQuoteResponse first = firstService.quote(
                SUBJECT,
                request(key, "EUR", "USD", "10000")
        );

        /*
         * Simulate a configuration change.
         * The retry must not receive the new rate.
         */
        FxQuoteService changedRateService =
                service("EUR/USD=1.25");

        FxQuoteResponse retry = changedRateService.quote(
                SUBJECT,
                request(key, "EUR", "USD", "10000")
        );

        assertEquals(first, retry);

        verify(repository, times(1))
                .insertIfAbsent(any());
    }

    @Test
    void retryDoesNotExtendExpiration() {

        UUID key = UUID.randomUUID();

        FxQuoteService service =
                service("EUR/USD=1.10");

        FxQuoteResponse first = service.quote(
                SUBJECT,
                request(key, "EUR", "USD", "10000")
        );

        FxQuoteResponse retry = service.quote(
                SUBJECT,
                request(key, "EUR", "USD", "10000")
        );

        assertEquals(first.quoteId(), retry.quoteId());
        assertEquals(first.expiresAt(), retry.expiresAt());
    }

    @Test
    void rejectsIdempotencyKeyReuseWithDifferentAmount() {

        UUID key = UUID.randomUUID();

        FxQuoteService service =
                service("EUR/USD=1.10");

        service.quote(
                SUBJECT,
                request(key, "EUR", "USD", "10000")
        );

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.quote(
                                SUBJECT,
                                request(
                                        key,
                                        "EUR",
                                        "USD",
                                        "20000"
                                )
                        )
                );

        assertEquals(
                HttpStatus.CONFLICT,
                exception.getStatusCode()
        );
    }

    @Test
    void rejectsIdempotencyKeyReuseWithDifferentPair() {

        UUID key = UUID.randomUUID();

        FxQuoteService service =
                service("EUR/USD=1.10,EUR/GBP=0.85");

        service.quote(
                SUBJECT,
                request(key, "EUR", "USD", "10000")
        );

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.quote(
                                SUBJECT,
                                request(
                                        key,
                                        "EUR",
                                        "GBP",
                                        "10000"
                                )
                        )
                );

        assertEquals(
                HttpStatus.CONFLICT,
                exception.getStatusCode()
        );
    }

    @Test
    void rejectsSameCurrencyConversion() {

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service("EUR/USD=1.10")
                                .quote(
                                        SUBJECT,
                                        request(
                                                UUID.randomUUID(),
                                                "EUR",
                                                "EUR",
                                                "10000"
                                        )
                                )
                );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                exception.getStatusCode()
        );
    }

    @Test
    void rejectsZeroAmount() {

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service("EUR/USD=1.10")
                                .quote(
                                        SUBJECT,
                                        request(
                                                UUID.randomUUID(),
                                                "EUR",
                                                "USD",
                                                "0"
                                        )
                                )
                );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                exception.getStatusCode()
        );
    }

    @Test
    void failsClosedWhenRateIsMissing() {

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service("")
                                .quote(
                                        SUBJECT,
                                        request(
                                                UUID.randomUUID(),
                                                "EUR",
                                                "USD",
                                                "10000"
                                        )
                                )
                );

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                exception.getStatusCode()
        );
    }

    @Test
    void refusesQuotesWhenFeatureIsDisabled() {

        FxQuoteService disabled =
                new FxQuoteService(
                        identityLookupService,
                        authorization,
                        new FxSandboxRateProvider("EUR/USD=1.10"),
                        repository,
                        false,
                        true
                );

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> disabled.quote(
                                SUBJECT,
                                request(
                                        UUID.randomUUID(),
                                        "EUR",
                                        "USD",
                                        "10000"
                                )
                        )
                );

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                exception.getStatusCode()
        );
    }

    @Test
    void rejectsDuplicateConfiguredRatePairs() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new FxSandboxRateProvider(
                        "EUR/USD=1.10,EUR/USD=1.20"
                )
        );
    }

    @Test
    void rejectsMissingIdempotencyKey() {

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service("EUR/USD=1.10")
                                .quote(
                                        SUBJECT,
                                        request(
                                                null,
                                                "EUR",
                                                "USD",
                                                "10000"
                                        )
                                )
                );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                exception.getStatusCode()
        );
    }

    @Test
    void unknownQuoteReturnsNotFound() {

        when(
                repository.findOwnedQuote(
                        any(UUID.class),
                        eq(customerId)
                )
        ).thenReturn(Optional.empty());

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service("EUR/USD=1.10")
                                .getQuote(
                                        SUBJECT,
                                        UUID.randomUUID()
                                )
                );

        assertEquals(
                HttpStatus.NOT_FOUND,
                exception.getStatusCode()
        );
    }
}