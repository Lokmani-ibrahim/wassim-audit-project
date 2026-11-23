
package com.fintech.ledger.fx.demo;

import com.fintech.identity.IdentityLookupService;
import com.fintech.kyc.CustomerDebitAuthorization;

import com.fintech.ledger.adapter.formance.FormanceFxConversionEvidenceVerifier;
import com.fintech.ledger.adapter.formance.FormanceFxConversionEvidenceVerifier.ExpectedFxSettlement;
import com.fintech.ledger.adapter.formance.FormanceFxConversionEvidenceVerifier.Finding;
import com.fintech.ledger.adapter.formance.FormanceTransactionResult;

import com.fintech.ledger.fx.FxQuoteRepository;
import com.fintech.ledger.fx.FxQuoteRepository.StoredQuote;

import com.fintech.ledger.fx.demo.FxDemoConversionRepository.ClaimResult;
import com.fintech.ledger.fx.demo.FxDemoConversionRepository.Status;
import com.fintech.ledger.fx.demo.FxDemoConversionRepository.Stored;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
@Profile("demo & !prod")
@ConditionalOnProperty(
        prefix = "neos.fx.demo",
        name = "enabled",
        havingValue = "true"
)
public class FxDemoConversionService {

    private final IdentityLookupService identities;
    private final CustomerDebitAuthorization authorization;
    private final FxQuoteRepository quotes;
    private final FxDemoConversionRepository operations;
    private final FxDemoFormanceAdapter ledger;
    private final FormanceFxConversionEvidenceVerifier verifier;

    private final String bankId;

    public FxDemoConversionService(
            IdentityLookupService identities,
            CustomerDebitAuthorization authorization,
            FxQuoteRepository quotes,
            FxDemoConversionRepository operations,
            FxDemoFormanceAdapter ledger,
            FormanceFxConversionEvidenceVerifier verifier,

            @Value("${neos.fx.demo.bank-id:sponsor}")
            String bankId
    ) {

        this.identities = Objects.requireNonNull(identities);
        this.authorization = Objects.requireNonNull(authorization);
        this.quotes = Objects.requireNonNull(quotes);
        this.operations = Objects.requireNonNull(operations);
        this.ledger = Objects.requireNonNull(ledger);
        this.verifier = Objects.requireNonNull(verifier);

        this.bankId = Objects.requireNonNull(bankId).trim();

        if (this.bankId.isBlank()) {
            throw new IllegalArgumentException(
                    "Demo sponsor bank ID cannot be blank"
            );
        }
    }

    /*
     * No surrounding PostgreSQL transaction:
     *
     * Claim -> commit -> external Formance call.
     *
     * Each ledger attempt is preceded by a committed
     * status transition, just like the NEOS payout
     * and transfer idempotency architecture.
     */
    public DemoResult convert(
            String keycloakSubject,
            UUID quoteId,
            UUID requestKey
    ) {

        requireOutsideTransaction();

        Objects.requireNonNull(quoteId);
        Objects.requireNonNull(requestKey);

        UUID userId = resolveUser(keycloakSubject);

        authorization.requireEligible(userId);

        /*
         * An identical completed retry returns its
         * original result, without calling Formance.
         */
        var existing = operations.findByRequestKey(
                userId,
                requestKey
        );

        if (existing.isPresent()) {
            return existingResult(existing.get(), quoteId);
        }

        StoredQuote quote = quotes.findOwnedQuote(
                quoteId,
                userId
        ).orElseThrow(() ->
                new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "FX quote not found"
                )
        );

        if (!Instant.now().isBefore(quote.expiresAt())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "FX demo quote has expired"
            );
        }

        if (quote.sourceCurrency()
                == quote.destinationCurrency()
                || quote.sourceMinor().signum() <= 0
                || quote.destinationMinor().signum() <= 0) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid FX quote"
            );
        }

        /*
         * In normal production, FX-01 quotes remain
         * non-executable.
         *
         * This is a deliberately separate DEMO endpoint
         * that accepts an indicative quote only while
         * the demo profile and explicit feature flag
         * are enabled on an isolated test ledger.
         */
        ClaimResult claim = operations.claim(
                userId,
                requestKey,
                quote,
                bankId
        );

        Stored operation = claim.operation();

        if (!claim.created()) {
            return existingResult(operation, quoteId);
        }

        ExpectedFxSettlement expected = expected(operation);

        // -------------------------------------------------
        // PHASE 1: Reserve source funds in real Formance.
        // -------------------------------------------------

        if (!operations.beginReserve(operation.operationId())) {
            throw unresolved(
                    "Unable to claim FX reservation"
            );
        }

        try {

            FormanceTransactionResult reserved =
                    ledger.reserve(expected);

            long reserveId = checkedTransactionId(
                    reserved,
                    operation.reserveReference()
            );

            if (!operations.reserveConfirmed(
                    operation.operationId(),
                    reserveId
            )) {
                throw new IllegalStateException(
                        "Unable to confirm FX reservation"
                );
            }

        } catch (RuntimeException failure) {

            /*
             * BUG-FX-2: a definitive refusal (insufficient funds)
             * posted nothing. It is a final rejection, not an
             * outcome that requires review.
             */
            if (isInsufficientFunds(failure)
                    && operations.reserveRejected(
                            operation.operationId()
                    )) {
                throw insufficientFunds(failure);
            }

            markReserveUnknownBestEffort(
                    operation.operationId(),
                    failure
            );

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "FX reservation outcome requires review; "
                            + "do not retry with another key",
                    failure
            );
        }

        // -------------------------------------------------
        // PHASE 2: Simulated bank settlement in Formance.
        // -------------------------------------------------

        if (!operations.beginSettlement(
                operation.operationId()
        )) {
            throw unresolved(
                    "FX settlement state requires review"
            );
        }

        try {

            FormanceTransactionResult posted =
                    ledger.settle(expected);

            long postedId = checkedTransactionId(
                    posted,
                    operation.settlementReference()
            );

            /*
             * A successful POST response alone
             * does not confirm a successful FX
             * transaction.
             *
             * Re-read Formance and verify every
             * expected posting and metadata field.
             */
            var evidence = verifier.inspect(expected);

            if (evidence == null
                    || evidence.finding() != Finding.MATCHED
                    || evidence.transactionId() == null
                    || evidence.transactionId() != postedId) {

                throw new IllegalStateException(
                        "Formance FX evidence is not confirmed"
                );
            }

            if (!operations.settlementConfirmed(
                    operation.operationId(),
                    postedId
            )) {
                throw new IllegalStateException(
                        "Unable to persist verified FX settlement"
                );
            }

        } catch (RuntimeException failure) {

            markSettlementUnknownBestEffort(
                    operation.operationId(),
                    failure
            );

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "FX settlement outcome requires review; "
                            + "do not retry with another key",
                    failure
            );
        }

        Stored confirmed = operations.findOwned(
                userId,
                operation.operationId()
        ).orElseThrow(() ->
                new IllegalStateException(
                        "Confirmed FX operation not found"
                )
        );

        return response(confirmed);
    }

    public DemoResult get(
            String keycloakSubject,
            UUID operationId
    ) {

        UUID userId = resolveUser(keycloakSubject);

        Stored stored = operations.findOwned(
                userId,
                operationId
        ).orElseThrow(() ->
                new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "FX demo conversion not found"
                )
        );

        return response(stored);
    }

    /*
     * Own operations only: the query is scoped by the user.
     * Any status is returned (requiresReconciliation tells
     * the client whether the outcome is still open).
     */
    public DemoResult findByRequestKey(
            String keycloakSubject,
            UUID requestKey
    ) {

        UUID userId = resolveUser(keycloakSubject);

        if (requestKey == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Missing idempotency key"
            );
        }

        Stored stored = operations.findByRequestKey(
                userId,
                requestKey
        ).orElseThrow(() ->
                new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "FX demo conversion not found"
                )
        );

        return response(stored);
    }

    private DemoResult existingResult(
            Stored operation,
            UUID requestedQuoteId
    ) {

        if (!operation.quoteId().equals(requestedQuoteId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Idempotency key used with another quote"
            );
        }

        if (operation.status() == Status.REJECTED) {
            throw insufficientFunds(null);
        }

        if (operation.status() != Status.SUCCEEDED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "FX conversion is in progress or requires "
                            + "reconciliation; operationId="
                            + operation.operationId()
            );
        }

        return response(operation);
    }

    private ExpectedFxSettlement expected(Stored operation) {

        String identifier = operation.operationId()
                .toString()
                .replace("-", "");

        return new ExpectedFxSettlement(
                operation.operationId(),
                operation.userId(),
                operation.bankId(),

                operation.sourceCurrency(),
                operation.destinationCurrency(),

                operation.sourceMinor(),
                operation.destinationMinor(),

                operation.exchangeRate(),
                operation.quoteId().toString(),

                "simulated_fx_" + identifier
        );
    }

    private UUID resolveUser(String keycloakSubject) {

        if (keycloakSubject == null
                || keycloakSubject.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Missing customer identity"
            );
        }

        return identities.findUserIdByKeycloakSubject(
                keycloakSubject
        ).orElseThrow(() ->
                new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Customer profile not found"
                )
        );
    }

    private static long checkedTransactionId(
            FormanceTransactionResult result,
            String expectedReference
    ) {

        if (result == null
                || !expectedReference.equals(result.reference())) {
            throw new IllegalStateException(
                    "Formance returned an unexpected transaction"
            );
        }

        Long transactionId = result.transactionId();

        if (transactionId == null || transactionId < 0) {
            throw new IllegalStateException(
                    "Formance transaction ID is invalid"
            );
        }

        return transactionId;
    }

    private void markReserveUnknownBestEffort(
            UUID operationId,
            RuntimeException original
    ) {

        try {
            if (!operations.reserveUnknown(operationId)) {
                original.addSuppressed(
                        new IllegalStateException(
                                "Reservation state remains unresolved"
                        )
                );
            }
        } catch (RuntimeException storageFailure) {
            original.addSuppressed(storageFailure);
        }
    }

    private void markSettlementUnknownBestEffort(
            UUID operationId,
            RuntimeException original
    ) {

        try {
            if (!operations.settlementUnknown(operationId)) {
                original.addSuppressed(
                        new IllegalStateException(
                                "Settlement state remains unresolved"
                        )
                );
            }
        } catch (RuntimeException storageFailure) {
            original.addSuppressed(storageFailure);
        }
    }

    /*
     * Formance v2 answers a posting that would overdraw a
     * non-overdraft account with HTTP 400 and
     * {"errorCode":"INSUFFICIENT_FUND", ...}. Any other 4xx
     * (duplicate reference, conflict, ...) may mean the posting
     * exists, so it stays an unresolved outcome.
     */
    static boolean isInsufficientFunds(Throwable failure) {

        for (Throwable cause = failure;
             cause != null;
             cause = cause.getCause()) {

            if (cause instanceof RestClientResponseException response
                    && response.getStatusCode().value() == 400
                    && response.getResponseBodyAsString()
                    .contains("INSUFFICIENT_FUND")) {
                return true;
            }
        }

        return false;
    }

    private static ResponseStatusException insufficientFunds(
            Throwable cause
    ) {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Insufficient funds; nothing was converted",
                cause
        );
    }

    private static ResponseStatusException unresolved(
            String reason
    ) {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                reason
        );
    }

    private static void requireOutsideTransaction() {

        if (TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new IllegalStateException(
                    "FX conversion cannot execute inside "
                            + "an existing database transaction"
            );
        }
    }

    private static DemoResult response(Stored operation) {

        Status status = operation.status();

        boolean unresolved =
                status == Status.RESERVE_POSTING
                        || status == Status.RESERVE_UNKNOWN
                        || status == Status.SETTLE_POSTING
                        || status == Status.SETTLE_UNKNOWN;

        return new DemoResult(
                operation.operationId(),
                operation.quoteId(),
                operation.requestKey(),
                status.name(),
                true,

                operation.sourceCurrency().currencyCode(),
                operation.destinationCurrency().currencyCode(),

                Long.toString(operation.sourceMinor()),
                Long.toString(operation.destinationMinor()),

                operation.exchangeRate()
                        .stripTrailingZeros()
                        .toPlainString(),

                operation.reserveReference(),
                operation.reserveTransactionId(),

                operation.settlementReference(),
                operation.settlementTransactionId(),

                unresolved
        );
    }

    public record DemoResult(
            UUID operationId,
            UUID quoteId,
            UUID idempotencyKey,
            String status,
            boolean simulated,

            String sourceCurrency,
            String destinationCurrency,
            String sourceMinor,
            String destinationMinor,
            String exchangeRate,

            String reservationReference,
            Long reservationTransactionId,

            String settlementReference,
            Long settlementTransactionId,

            boolean requiresReconciliation
    ) {
    }
}
