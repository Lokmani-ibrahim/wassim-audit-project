package com.fintech.ledger.fx;

import com.fintech.identity.IdentityLookupService;
import com.fintech.kyc.CustomerDebitAuthorization;
import com.fintech.ledger.fx.api.FxQuoteRequest;
import com.fintech.ledger.fx.api.FxQuoteResponse;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Service
public class FxQuoteService {

    private static final long QUOTE_TTL_SECONDS = 30;

    private static final BigInteger MAX_MINOR =
            BigInteger.valueOf(Long.MAX_VALUE);

    private final IdentityLookupService identityLookupService;
    private final CustomerDebitAuthorization authorization;
    private final FxSandboxRateProvider rateProvider;
    private final FxQuoteRepository repository;

    private final boolean quotesEnabled;
    private final boolean sandboxMode;

    public FxQuoteService(
            IdentityLookupService identityLookupService,
            CustomerDebitAuthorization authorization,
            FxSandboxRateProvider rateProvider,
            FxQuoteRepository repository,

            @Value("${NEOS_FX_QUOTES_ENABLED:false}")
            boolean quotesEnabled,

            @Value("${NEOS_FX_SANDBOX_MODE:false}")
            boolean sandboxMode
    ) {
        this.identityLookupService =
                Objects.requireNonNull(identityLookupService);

        this.authorization =
                Objects.requireNonNull(authorization);

        this.rateProvider =
                Objects.requireNonNull(rateProvider);

        this.repository =
                Objects.requireNonNull(repository);

        this.quotesEnabled = quotesEnabled;
        this.sandboxMode = sandboxMode;
    }

    /*
     * This transaction contains only database operations
     * and local deterministic arithmetic.
     *
     * There is no external Formance submission.
     *
     * Therefore keeping the insert and subsequent read
     * in one database transaction is safe.
     */
    @Transactional
    public FxQuoteResponse quote(
            String keycloakSubject,
            FxQuoteRequest request
    ) {

        requireSandboxEnabled();

        Objects.requireNonNull(request);

        UUID userId = authenticatedEligibleUser(
                keycloakSubject
        );

        UUID requestKey = request.idempotencyKey();

        if (requestKey == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Missing idempotency key"
            );
        }

        LedgerCurrency source =
                currency(request.sourceCurrency());

        LedgerCurrency destination =
                currency(request.destinationCurrency());

        if (source == destination) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Source and destination currencies must differ"
            );
        }

        BigInteger sourceMinor =
                parseAmount(request.sourceMinor());

        /*
         * First check for an existing quote.
         *
         * An identical retry must return the original
         * price and expiration, even if sandbox rates
         * changed in the meantime.
         */
        var existing = repository.findByUserAndRequestKey(
                userId,
                requestKey
        );

        if (existing.isPresent()) {

            FxQuoteRepository.StoredQuote stored =
                    existing.get();

            requireSameRequest(
                    stored,
                    source,
                    destination,
                    sourceMinor
            );

            return response(stored);
        }

        BigDecimal rate =
                rateProvider.rateFor(source, destination);

        BigDecimal sourceMajor =
                new BigDecimal(sourceMinor)
                        .movePointLeft(source.scale());

        BigDecimal destinationMajor =
                sourceMajor.multiply(rate);

        BigInteger destinationMinor =
                destinationMajor
                        .movePointRight(destination.scale())
                        .setScale(0, RoundingMode.DOWN)
                        .toBigIntegerExact();

        if (destinationMinor.signum() <= 0
                || destinationMinor.compareTo(MAX_MINOR) > 0) {

            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Destination amount is outside supported limits"
            );
        }

        Instant now = Instant.now()
                .truncatedTo(ChronoUnit.MICROS);

        FxQuoteRepository.StoredQuote candidate =
                new FxQuoteRepository.StoredQuote(
                        UUID.randomUUID(),
                        userId,
                        requestKey,

                        source,
                        destination,

                        sourceMinor,
                        destinationMinor,

                        source.scale(),
                        destination.scale(),

                        rate,

                        now,
                        now.plusSeconds(QUOTE_TTL_SECONDS)
                );

        /*
         * The unique database constraint protects
         * concurrent attempts with the same request key.
         *
         * We deliberately return the persisted winner,
         * never an independently calculated losing quote.
         */
        repository.insertIfAbsent(candidate);

        FxQuoteRepository.StoredQuote persisted =
                repository.findByUserAndRequestKey(
                        userId,
                        requestKey
                ).orElseThrow(() ->
                        new IllegalStateException(
                                "Persisted FX quote could not be found"
                        )
                );

        requireSameRequest(
                persisted,
                source,
                destination,
                sourceMinor
        );

        return response(persisted);
    }

    @Transactional(readOnly = true)
    public FxQuoteResponse getQuote(
            String keycloakSubject,
            UUID quoteId
    ) {

        requireSandboxEnabled();

        UUID userId = authenticatedEligibleUser(
                keycloakSubject
        );

        if (quoteId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Missing quote ID"
            );
        }

        /*
         * The repository query includes the user ID.
         * A customer cannot retrieve another customer's quote.
         */
        FxQuoteRepository.StoredQuote stored =
                repository.findOwnedQuote(
                        quoteId,
                        userId
                ).orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "FX quote not found"
                        )
                );

        return response(stored);
    }

    private UUID authenticatedEligibleUser(
            String keycloakSubject
    ) {

        if (keycloakSubject == null
                || keycloakSubject.isBlank()) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Missing authenticated customer"
            );
        }

        UUID userId = identityLookupService
                .findUserIdByKeycloakSubject(
                        keycloakSubject
                )
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.FORBIDDEN,
                                "Customer profile not found"
                        )
                );

        authorization.requireEligible(userId);

        return userId;
    }

    private void requireSandboxEnabled() {

        if (!quotesEnabled || !sandboxMode) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "FX sandbox quotes are disabled"
            );
        }
    }

    private static void requireSameRequest(
            FxQuoteRepository.StoredQuote stored,
            LedgerCurrency source,
            LedgerCurrency destination,
            BigInteger sourceMinor
    ) {

        if (stored.sourceCurrency() != source
                || stored.destinationCurrency() != destination
                || !stored.sourceMinor().equals(sourceMinor)) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Idempotency key already used "
                            + "with different quote parameters"
            );
        }
    }

    private static BigInteger parseAmount(String value) {

        if (value == null
                || !value.matches("[0-9]{1,18}")) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid source amount"
            );
        }

        BigInteger amount = new BigInteger(value);

        if (amount.signum() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Source amount must be positive"
            );
        }

        return amount;
    }

    private static LedgerCurrency currency(String code) {

        try {
            return LedgerCurrency.fromCode(code);

        } catch (IllegalArgumentException
                 | NullPointerException exception) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported currency",
                    exception
            );
        }
    }

    private static FxQuoteResponse response(
            FxQuoteRepository.StoredQuote stored
    ) {

        return new FxQuoteResponse(
                stored.quoteId(),
                stored.idempotencyKey(),

                stored.sourceCurrency().currencyCode(),
                stored.destinationCurrency().currencyCode(),

                stored.sourceMinor().toString(),
                stored.destinationMinor().toString(),

                stored.sourceScale(),
                stored.destinationScale(),

                stored.exchangeRate()
                        .stripTrailingZeros()
                        .toPlainString(),

                "SANDBOX_INDICATIVE",
                false,

                stored.asOf(),
                stored.expiresAt()
        );
    }
}