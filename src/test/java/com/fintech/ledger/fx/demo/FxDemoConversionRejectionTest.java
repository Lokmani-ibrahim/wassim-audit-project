package com.fintech.ledger.fx.demo;

import com.fintech.identity.IdentityLookupService;
import com.fintech.kyc.CustomerDebitAuthorization;
import com.fintech.ledger.LedgerTransactionRejectedException;
import com.fintech.ledger.adapter.formance.FormanceFxConversionEvidenceVerifier;
import com.fintech.ledger.fx.FxQuoteRepository;
import com.fintech.ledger.fx.FxQuoteRepository.StoredQuote;
import com.fintech.ledger.fx.demo.FxDemoConversionRepository.ClaimResult;
import com.fintech.ledger.fx.demo.FxDemoConversionRepository.Status;
import com.fintech.ledger.fx.demo.FxDemoConversionRepository.Stored;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/*
 * BUG-FX-2: Formance refusing the reservation for insufficient funds
 * is a definitive rejection (422, status REJECTED), not an outcome
 * that "requires review" (503, RESERVE_UNKNOWN). Any other ledger 4xx
 * keeps the cautious unresolved path.
 */
class FxDemoConversionRejectionTest {

    private static final String SUBJECT = "kc-subject";
    private static final UUID USER_ID = UUID.randomUUID();

    private final IdentityLookupService identities = mock(IdentityLookupService.class);
    private final CustomerDebitAuthorization authorization = mock(CustomerDebitAuthorization.class);
    private final FxQuoteRepository quotes = mock(FxQuoteRepository.class);
    private final FxDemoConversionRepository operations = mock(FxDemoConversionRepository.class);
    private final FxDemoFormanceAdapter ledger = mock(FxDemoFormanceAdapter.class);
    private final FormanceFxConversionEvidenceVerifier verifier =
            mock(FormanceFxConversionEvidenceVerifier.class);

    private final FxDemoConversionService service = new FxDemoConversionService(
            identities, authorization, quotes, operations, ledger, verifier, "sponsor"
    );

    private final StoredQuote quote = new StoredQuote(
            UUID.randomUUID(), USER_ID, UUID.randomUUID(),
            LedgerCurrency.EUR, LedgerCurrency.USD,
            BigInteger.valueOf(1_000_000L), BigInteger.valueOf(1_080_000L),
            2, 2, new BigDecimal("1.08"),
            Instant.now(), Instant.now().plusSeconds(30)
    );
    private final UUID requestKey = UUID.randomUUID();

    @BeforeEach
    void customerAndQuote() {
        when(identities.findUserIdByKeycloakSubject(SUBJECT)).thenReturn(Optional.of(USER_ID));
        when(quotes.findOwnedQuote(quote.quoteId(), USER_ID)).thenReturn(Optional.of(quote));
        when(operations.findByRequestKey(USER_ID, requestKey)).thenReturn(Optional.empty());
    }

    @Test
    void insufficientFundsAtReservationIsADefinitiveRejection() {
        Stored claimed = operation(Status.CLAIMED);
        when(operations.claim(USER_ID, requestKey, quote, "sponsor"))
                .thenReturn(new ClaimResult(true, claimed));
        when(operations.beginReserve(claimed.operationId())).thenReturn(true);
        when(operations.reserveRejected(claimed.operationId())).thenReturn(true);
        when(ledger.reserve(any())).thenThrow(formanceRejection(
                "{\"errorCode\":\"INSUFFICIENT_FUND\",\"errorMessage\":\"account had insufficient funds\"}"
        ));

        assertThatThrownBy(() -> service.convert(SUBJECT, quote.quoteId(), requestKey))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        verify(operations).reserveRejected(claimed.operationId());
        verify(operations, never()).reserveUnknown(any());
        verify(ledger, never()).settle(any());
    }

    @Test
    void anotherLedgerRefusalStaysUnresolved() {
        Stored claimed = operation(Status.CLAIMED);
        when(operations.claim(USER_ID, requestKey, quote, "sponsor"))
                .thenReturn(new ClaimResult(true, claimed));
        when(operations.beginReserve(claimed.operationId())).thenReturn(true);
        when(operations.reserveUnknown(claimed.operationId())).thenReturn(true);
        when(ledger.reserve(any())).thenThrow(formanceRejection(
                "{\"errorCode\":\"CONFLICT\",\"errorMessage\":\"reference already used\"}"
        ));

        assertThatThrownBy(() -> service.convert(SUBJECT, quote.quoteId(), requestKey))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        verify(operations).reserveUnknown(claimed.operationId());
        verify(operations, never()).reserveRejected(any());
    }

    @Test
    void retryOfARejectedOperationIsRejectedAgainWithoutTouchingTheLedger() {
        when(operations.findByRequestKey(USER_ID, requestKey))
                .thenReturn(Optional.of(operation(Status.REJECTED)));

        assertThatThrownBy(() -> service.convert(SUBJECT, quote.quoteId(), requestKey))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        verifyNoInteractions(ledger);
    }

    @Test
    void insufficientFundsIsRecognisedOnlyFromFormance400Body() {
        assertThat(FxDemoConversionService.isInsufficientFunds(
                formanceRejection("{\"errorCode\":\"INSUFFICIENT_FUND\"}"))).isTrue();
        assertThat(FxDemoConversionService.isInsufficientFunds(
                formanceRejection("{\"errorCode\":\"VALIDATION\"}"))).isFalse();
        assertThat(FxDemoConversionService.isInsufficientFunds(
                new IllegalStateException("timeout"))).isFalse();
    }

    private Stored operation(Status status) {
        UUID id = UUID.randomUUID();
        String hex = id.toString().replace("-", "");
        return new Stored(
                id, USER_ID, quote.quoteId(), requestKey, "sponsor",
                LedgerCurrency.EUR, LedgerCurrency.USD,
                1_000_000L, 1_080_000L, new BigDecimal("1.08"),
                status, "fx_reserve:" + hex, null, "fx_convert:" + hex, null
        );
    }

    private static LedgerTransactionRejectedException formanceRejection(String body) {
        HttpClientErrorException http = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8
        );
        return new LedgerTransactionRejectedException("Failed to create Formance transaction", http);
    }
}
