package com.fintech.ledger.fx.demo;

import com.fintech.identity.IdentityLookupService;
import com.fintech.kyc.CustomerDebitAuthorization;
import com.fintech.ledger.adapter.formance.FormanceFxConversionEvidenceVerifier;
import com.fintech.ledger.fx.FxQuoteRepository;
import com.fintech.ledger.fx.demo.FxDemoConversionRepository.Status;
import com.fintech.ledger.fx.demo.FxDemoConversionRepository.Stored;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/*
 * BUG-FX-3: after an uncertain outcome (503, 409, timeout) the client
 * only knows the idempotency key it sent. It must be able to read its
 * own operation with that key, whatever the status.
 */
class FxDemoConversionLookupTest {

    private static final String SUBJECT = "kc-subject";
    private static final UUID USER_ID = UUID.randomUUID();

    private final IdentityLookupService identities = mock(IdentityLookupService.class);
    private final FxDemoConversionRepository operations = mock(FxDemoConversionRepository.class);
    private final FxDemoFormanceAdapter ledger = mock(FxDemoFormanceAdapter.class);

    private final FxDemoConversionService service = new FxDemoConversionService(
            identities,
            mock(CustomerDebitAuthorization.class),
            mock(FxQuoteRepository.class),
            operations,
            ledger,
            mock(FormanceFxConversionEvidenceVerifier.class),
            "sponsor"
    );

    @Test
    void unresolvedOperationIsFoundByKeyWithReconciliationFlag() {
        UUID key = UUID.randomUUID();
        when(identities.findUserIdByKeycloakSubject(SUBJECT)).thenReturn(Optional.of(USER_ID));
        Stored stored = stored(key, Status.SETTLE_UNKNOWN);
        when(operations.findByRequestKey(USER_ID, key)).thenReturn(Optional.of(stored));

        var result = service.findByRequestKey(SUBJECT, key);

        assertThat(result.operationId()).isEqualTo(stored.operationId());
        assertThat(result.status()).isEqualTo("SETTLE_UNKNOWN");
        assertThat(result.requiresReconciliation()).isTrue();
        verifyNoInteractions(ledger);
    }

    @Test
    void succeededAndRejectedOperationsAreFinal() {
        UUID key = UUID.randomUUID();
        when(identities.findUserIdByKeycloakSubject(SUBJECT)).thenReturn(Optional.of(USER_ID));

        when(operations.findByRequestKey(USER_ID, key)).thenReturn(Optional.of(stored(key, Status.SUCCEEDED)));
        assertThat(service.findByRequestKey(SUBJECT, key).requiresReconciliation()).isFalse();

        when(operations.findByRequestKey(USER_ID, key)).thenReturn(Optional.of(stored(key, Status.REJECTED)));
        var rejected = service.findByRequestKey(SUBJECT, key);
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.requiresReconciliation()).isFalse();
    }

    @Test
    void unknownKeyMeansNothingWasClaimed() {
        UUID key = UUID.randomUUID();
        when(identities.findUserIdByKeycloakSubject(SUBJECT)).thenReturn(Optional.of(USER_ID));
        when(operations.findByRequestKey(USER_ID, key)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByRequestKey(SUBJECT, key))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void unknownCustomerIsForbidden() {
        when(identities.findUserIdByKeycloakSubject(SUBJECT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByRequestKey(SUBJECT, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static Stored stored(UUID key, Status status) {
        UUID id = UUID.randomUUID();
        String hex = id.toString().replace("-", "");
        boolean succeeded = status == Status.SUCCEEDED;
        return new Stored(
                id, USER_ID, UUID.randomUUID(), key, "sponsor",
                LedgerCurrency.EUR, LedgerCurrency.USD,
                10_000L, 10_800L, new BigDecimal("1.08"),
                status,
                "fx_reserve:" + hex, succeeded ? 141L : null,
                "fx_convert:" + hex, succeeded ? 142L : null
        );
    }
}
