package com.fintech.ledger;

import com.fintech.identity.IdentityLookupService;
import com.fintech.ledger.adapter.formance.FormanceClient;
import com.fintech.ledger.adapter.formance.FormanceTransactionData;
import com.fintech.ledger.adapter.formance.FormanceTransactionPosting;
import com.fintech.ledger.adapter.formance.FormanceTransactionsCursor;
import com.fintech.ledger.adapter.formance.FormanceTransactionsResponse;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * BUG-FX-1: an FX demo conversion must appear in the history of
 * both wallets, as one CONVERSION item each: a DEBIT on the source
 * wallet (fx_reserve) and a CREDIT on the destination wallet
 * (fx_convert). Formance is mocked; only the mapping is under test.
 */
class CustomerTransactionHistoryConversionTest {

    private static final String SUBJECT = "kc-subject";
    private static final UUID USER_ID =
            UUID.fromString("7bf8f8ad-1133-419d-9a13-9c3883690de0");
    private static final String OPERATION = "3f2b1c0d4e5f60718293a4b5c6d7e8f9";

    private final IdentityLookupService identities = mock(IdentityLookupService.class);
    private final FormanceClient formance = mock(FormanceClient.class);
    private final CustomerTransactionHistoryService service =
            new CustomerTransactionHistoryService(identities, formance);

    private final String usdAvailable = "users:" + USER_ID + ":wallet:usd:available";

    @BeforeEach
    void customer() {
        when(identities.findProfileByKeycloakSubject(SUBJECT))
                .thenReturn(Optional.of(
                        new IdentityLookupService.IdentityProfile(USER_ID, "c@neos.local", null)
                ));
    }

    @Test
    void sourceWalletShowsOneConversionDebit() {
        String hold = "users:" + USER_ID + ":wallet:eur:holds:fx_" + OPERATION;
        String account = accountFor(LedgerCurrency.EUR);
        page(account, transaction(
                141L,
                "fx_reserve:" + OPERATION,
                Map.of("neos.operation", "fx_reserve"),
                List.of(new FormanceTransactionPosting(account, hold, 10_000L, "EUR/2"))
        ));

        CustomerTransactionPage page = service.getHistory(SUBJECT, LedgerCurrency.EUR, 20, null);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo(CustomerTransactionType.CONVERSION);
            assertThat(item.direction()).isEqualTo(CustomerTransactionDirection.DEBIT);
            assertThat(item.amountMinor()).isEqualTo(10_000L);
            assertThat(item.reference()).isEqualTo("fx_reserve:" + OPERATION);
        });
    }

    @Test
    void destinationWalletShowsOneConversionCreditFromTheFourPostingSettlement() {
        String account = accountFor(LedgerCurrency.USD);
        String eurHold = "users:" + USER_ID + ":wallet:eur:holds:fx_" + OPERATION;
        page(account, transaction(
                142L,
                "fx_convert:" + OPERATION,
                Map.of("neos.operation", "fx_convert"),
                List.of(
                        new FormanceTransactionPosting(eurHold, "control:safeguarded:eur", 10_000L, "EUR/2"),
                        new FormanceTransactionPosting("bank:sponsor:master:eur", "world", 10_000L, "EUR/2"),
                        new FormanceTransactionPosting("world", "bank:sponsor:master:usd", 10_800L, "USD/2"),
                        new FormanceTransactionPosting("control:safeguarded:usd", account, 10_800L, "USD/2")
                )
        ));

        CustomerTransactionPage page = service.getHistory(SUBJECT, LedgerCurrency.USD, 20, null);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo(CustomerTransactionType.CONVERSION);
            assertThat(item.direction()).isEqualTo(CustomerTransactionDirection.CREDIT);
            assertThat(item.amountMinor()).isEqualTo(10_800L);
            assertThat(item.currency()).isEqualTo(LedgerCurrency.USD);
        });
    }

    @Test
    void settlementSeenFromTheSourceWalletAddsNothing() {
        // Defensive: if Formance ever returned fx_convert for the source
        // available account, it has no posting touching that account.
        String account = accountFor(LedgerCurrency.EUR);
        page(account, transaction(
                142L,
                "fx_convert:" + OPERATION,
                Map.of("neos.operation", "fx_convert"),
                List.of(new FormanceTransactionPosting("control:safeguarded:usd", usdAvailable, 10_800L, "USD/2"))
        ));

        assertThat(service.getHistory(SUBJECT, LedgerCurrency.EUR, 20, null).items()).isEmpty();
    }

    private static String accountFor(LedgerCurrency currency) {
        return com.fintech.ledger.internal.taxonomy.LedgerAccountAddressFactory
                .customerAvailable(USER_ID, currency)
                .value();
    }

    private void page(String account, FormanceTransactionData... data) {
        when(formance.getTransactionsForAccount(eq(account), anyInt(), any()))
                .thenReturn(new FormanceTransactionsResponse(
                        new FormanceTransactionsCursor(20, false, null, List.of(data))
                ));
    }

    private static FormanceTransactionData transaction(
            long id,
            String reference,
            Map<String, String> metadata,
            List<FormanceTransactionPosting> postings
    ) {
        Instant at = Instant.parse("2026-11-06T10:00:00Z");
        return new FormanceTransactionData(postings, metadata, at, reference, id, at, at, false);
    }
}
