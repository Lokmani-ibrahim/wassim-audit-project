package com.fintech.ledger;

import com.fintech.identity.IdentityLookupService;
import com.fintech.kyc.KycStatusLookup;
import com.fintech.ledger.adapter.formance.FormanceAccountData;
import com.fintech.ledger.adapter.formance.FormanceAccountsCursor;
import com.fintech.ledger.adapter.formance.FormanceAccountsResponse;
import com.fintech.ledger.adapter.formance.FormanceClient;
import com.fintech.ledger.adapter.formance.FormanceVolumeBalance;
import com.fintech.ledger.adapter.formance.FormanceVolumesCursor;
import com.fintech.ledger.adapter.formance.FormanceVolumesResponse;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class CustomerWalletServiceTest {

    private static final String SUBJECT =
            "keycloak-wallet-customer";

    private final UUID userId = UUID.randomUUID();

    private IdentityLookupService identityLookupService;
    private KycStatusLookup kycStatusLookup;
    private FormanceClient formanceClient;
    private CustomerWalletService service;

    @BeforeEach
    void setup() {

        identityLookupService = mock(IdentityLookupService.class);
        kycStatusLookup = mock(KycStatusLookup.class);
        formanceClient = mock(FormanceClient.class);

        service = new CustomerWalletService(
                identityLookupService,
                kycStatusLookup,
                formanceClient
        );

        when(identityLookupService
                .findProfileByKeycloakSubject(SUBJECT))
                .thenReturn(Optional.of(
                        new IdentityLookupService.IdentityProfile(
                                userId,
                                "customer@neos.local",
                                null
                        )
                ));
    }

    @Test
    void reportsAvailableAndSummedHoldsAsStringsForEveryCurrency() {

        givenProvisioned(LedgerCurrency.values());

        givenVolumes(
                volume("EUR:available", "EUR/2", "9700"),
                volume("EUR:holds:op1", "EUR/2", "300"),
                volume("EUR:holds:op2", "EUR/2", "0"),
                volume("EUR:holds:op3", "EUR/2", "50"),
                volume("AED:available", "AED/2", "9007199254740993123"),
                volume("MUR:available", "MUR/2", "-123"),
                volume("TND:available", "TND/3", "4500")
        );

        CustomerWallets result = service.getWallets(SUBJECT);

        assertEquals(CustomerWalletsStatus.READY, result.walletsStatus());
        assertNotNull(result.asOf());
        assertEquals(
                Arrays.asList(LedgerCurrency.values()),
                result.wallets().stream()
                        .map(CustomerWallet::currency)
                        .toList()
        );

        CustomerWallet eur = wallet(result, LedgerCurrency.EUR);
        assertEquals("ACTIVE", eur.status());
        assertEquals(2, eur.scale());
        assertEquals("EUR/2", eur.asset());
        assertEquals("9700", eur.availableMinor());
        assertEquals("350", eur.pendingMinor());

        CustomerWallet tnd = wallet(result, LedgerCurrency.TND);
        assertEquals(3, tnd.scale());
        assertEquals("4500", tnd.availableMinor());
        assertEquals("0", tnd.pendingMinor());

        assertEquals("9007199254740993123", wallet(result, LedgerCurrency.AED).availableMinor());
        assertEquals("-123", wallet(result, LedgerCurrency.MUR).availableMinor());

        // Provisioned but never moved: zero, not null.
        CustomerWallet usd = wallet(result, LedgerCurrency.USD);
        assertEquals("0", usd.availableMinor());
        assertEquals("0", usd.pendingMinor());
    }

    @Test
    void notProvisionedWalletHasNullAmountsNeverZero() {

        givenProvisioned(LedgerCurrency.EUR);
        when(kycStatusLookup.findStatus(userId))
                .thenReturn(Optional.of("APPROVED"));

        // Movement on an account without provisioning
        // metadata must not be reported as a balance.
        givenVolumes(
                volume("EUR:available", "EUR/2", "100"),
                volume("GBP:available", "GBP/2", "500")
        );

        CustomerWallets result = service.getWallets(SUBJECT);

        assertEquals(
                CustomerWalletsStatus.PROVISIONING,
                result.walletsStatus()
        );

        CustomerWallet gbp = wallet(result, LedgerCurrency.GBP);
        assertEquals(CustomerWallet.NOT_PROVISIONED, gbp.status());
        assertNull(gbp.availableMinor());
        assertNull(gbp.pendingMinor());

        assertEquals(
                "100",
                wallet(result, LedgerCurrency.EUR).availableMinor()
        );
    }

    @Test
    void notApprovedCustomerWithoutWalletsIsNotEligible() {

        givenProvisioned();
        givenVolumes();
        when(kycStatusLookup.findStatus(userId))
                .thenReturn(Optional.of("PENDING"));

        CustomerWallets result = service.getWallets(SUBJECT);

        assertEquals(
                CustomerWalletsStatus.NOT_ELIGIBLE,
                result.walletsStatus()
        );
        assertEquals(
                LedgerCurrency.values().length,
                result.wallets().size()
        );
        assertTrue(
                result.wallets().stream().allMatch(wallet ->
                        CustomerWallet.NOT_PROVISIONED.equals(wallet.status())
                                && wallet.availableMinor() == null
                )
        );
    }

    @Test
    void approvedCustomerWithoutWalletsIsProvisioning() {

        givenProvisioned();
        givenVolumes();
        when(kycStatusLookup.findStatus(userId))
                .thenReturn(Optional.of("APPROVED"));

        assertEquals(
                CustomerWalletsStatus.PROVISIONING,
                service.getWallets(SUBJECT).walletsStatus()
        );
    }

    @Test
    void walletsOfRevokedCustomerAreStillReportedAsTheyAre() {

        givenProvisioned(LedgerCurrency.values());
        givenVolumes(volume("EUR:available", "EUR/2", "1200"));
        when(kycStatusLookup.findStatus(userId))
                .thenReturn(Optional.of("REJECTED"));

        CustomerWallets result = service.getWallets(SUBJECT);

        assertEquals(CustomerWalletsStatus.READY, result.walletsStatus());
        assertEquals(
                "1200",
                wallet(result, LedgerCurrency.EUR).availableMinor()
        );
    }

    @Test
    void keepsBalancesBeyondLongRangeExact() {

        givenProvisioned(LedgerCurrency.values());
        givenVolumes(
                volume(
                        "USD:available",
                        "USD/2",
                        "123456789012345678901234567890"
                )
        );

        assertEquals(
                "123456789012345678901234567890",
                wallet(
                        service.getWallets(SUBJECT),
                        LedgerCurrency.USD
                ).availableMinor()
        );
    }

    @Test
    void ignoresForeignAssetsAndOtherAccounts() {

        givenProvisioned(LedgerCurrency.values());
        givenVolumes(
                volume("EUR:available", "EUR/3", "999"),
                volume("XYZ:available", "XYZ/2", "999"),
                volume("EUR:available:extra", "EUR/2", "999"),
                new FormanceVolumeBalance(
                        "users:" + UUID.randomUUID() + ":wallet:EUR:available",
                        "EUR/2",
                        new BigInteger("999"),
                        new BigInteger("999"),
                        BigInteger.ZERO
                )
        );

        CustomerWallet eur =
                wallet(service.getWallets(SUBJECT), LedgerCurrency.EUR);

        assertEquals("0", eur.availableMinor());
        assertEquals("0", eur.pendingMinor());
    }

    @Test
    void followsVolumeCursors() {

        givenProvisioned(LedgerCurrency.values());

        when(formanceClient.getVolumes(any(), eq(100), isNull()))
                .thenReturn(volumes(
                        true,
                        "page-2",
                        volume("EUR:available", "EUR/2", "100")
                ));

        when(formanceClient.getVolumes(any(), eq(100), eq("page-2")))
                .thenReturn(volumes(
                        false,
                        null,
                        volume("EUR:holds:op1", "EUR/2", "40")
                ));

        CustomerWallet eur =
                wallet(service.getWallets(SUBJECT), LedgerCurrency.EUR);

        assertEquals("100", eur.availableMinor());
        assertEquals("40", eur.pendingMinor());
    }

    @Test
    void ledgerOutageIsServiceUnavailableWithoutPartialData() {

        givenProvisioned(LedgerCurrency.values());

        when(formanceClient.getVolumes(any(), anyInt(), any()))
                .thenThrow(new LedgerProviderUnavailableException(
                        "Formance down"
                ));

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.getWallets(SUBJECT)
                );

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                exception.getStatusCode()
        );
    }

    @Test
    void ledgerRejectedReadIsServiceUnavailable() {

        when(formanceClient.getAccounts(any(), anyInt(), any()))
                .thenThrow(new LedgerTransactionRejectedException(
                        "HTTP 400"
                ));

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.getWallets(SUBJECT)
                );

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                exception.getStatusCode()
        );
    }

    @Test
    void customerWithoutProfileIsNotFound() {

        when(identityLookupService
                .findProfileByKeycloakSubject("unknown"))
                .thenReturn(Optional.empty());

        assertThrows(
                CustomerWalletsCustomerNotFoundException.class,
                () -> service.getWallets("unknown")
        );

        verifyNoInteractions(formanceClient);
    }

    private void givenProvisioned(LedgerCurrency... currencies) {

        List<FormanceAccountData> accounts = new ArrayList<>();

        for (LedgerCurrency currency : currencies) {
            accounts.add(
                    new FormanceAccountData(
                            address(currency.currencyCode() + ":available"),
                            Map.of("neos.wallet_status", "ACTIVE")
                    )
            );
        }

        when(formanceClient.getAccounts(any(), anyInt(), any()))
                .thenReturn(
                        new FormanceAccountsResponse(
                                new FormanceAccountsCursor(
                                        accounts,
                                        false,
                                        null,
                                        100,
                                        null
                                )
                        )
                );
    }

    private void givenVolumes(FormanceVolumeBalance... balances) {

        when(formanceClient.getVolumes(any(), anyInt(), any()))
                .thenReturn(volumes(false, null, balances));
    }

    private static FormanceVolumesResponse volumes(
            boolean hasMore,
            String next,
            FormanceVolumeBalance... balances
    ) {
        return new FormanceVolumesResponse(
                new FormanceVolumesCursor(
                        List.of(balances),
                        hasMore,
                        next,
                        100,
                        null
                ),
                null
        );
    }

    private FormanceVolumeBalance volume(
            String suffix,
            String asset,
            String balance
    ) {
        return new FormanceVolumeBalance(
                address(suffix),
                asset,
                new BigInteger(balance),
                new BigInteger(balance),
                BigInteger.ZERO
        );
    }

    private String address(String suffix) {
        return "users:" + userId + ":wallet:" + suffix;
    }

    private static CustomerWallet wallet(
            CustomerWallets wallets,
            LedgerCurrency currency
    ) {
        return wallets.wallets().stream()
                .filter(wallet -> wallet.currency() == currency)
                .findFirst()
                .orElseThrow();
    }
}
