package com.fintech.ledger;

import com.fintech.identity.IdentityLookupService;
import com.fintech.kyc.KycStatusLookup;
import com.fintech.ledger.adapter.formance.FormanceAccountData;
import com.fintech.ledger.adapter.formance.FormanceAccountsResponse;
import com.fintech.ledger.adapter.formance.FormanceClient;
import com.fintech.ledger.adapter.formance.FormanceVolumeBalance;
import com.fintech.ledger.adapter.formance.FormanceVolumesResponse;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class CustomerWalletService {

    private static final int PAGE_SIZE = 100;

    /*
     * Safety cap on pagination.
     *
     * A customer has 6 available accounts plus one
     * hold account per payout. 20 pages of 100 rows
     * is far beyond any realistic customer.
     */
    private static final int MAX_PAGES = 20;

    private static final String WALLET_STATUS_METADATA =
            "neos.wallet_status";

    private final IdentityLookupService identityLookupService;
    private final KycStatusLookup kycStatusLookup;
    private final FormanceClient formanceClient;

    public CustomerWalletService(
            IdentityLookupService identityLookupService,
            KycStatusLookup kycStatusLookup,
            FormanceClient formanceClient
    ) {

        this.identityLookupService =
                Objects.requireNonNull(
                        identityLookupService,
                        "identityLookupService must not be null"
                );

        this.kycStatusLookup =
                Objects.requireNonNull(
                        kycStatusLookup,
                        "kycStatusLookup must not be null"
                );

        this.formanceClient =
                Objects.requireNonNull(
                        formanceClient,
                        "formanceClient must not be null"
                );
    }

    public CustomerWallets getWallets(
            String keycloakSubject
    ) {

        Objects.requireNonNull(
                keycloakSubject,
                "keycloakSubject must not be null"
        );

        if (keycloakSubject.isBlank()) {
            throw new IllegalArgumentException(
                    "keycloakSubject must not be blank"
            );
        }

        UUID userId =
                identityLookupService
                        .findProfileByKeycloakSubject(
                                keycloakSubject
                        )
                        .orElseThrow(
                                CustomerWalletsCustomerNotFoundException::new
                        )
                        .userId();

        return getWalletsForUser(userId);
    }

    /** Staff callers must resolve and authorize the selected customer first. */
    public CustomerWallets getWalletsForUser(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Instant asOf =
                Instant.now();

        Map<LedgerCurrency, String> walletStatuses;
        Map<LedgerCurrency, BigInteger> available =
                new EnumMap<>(LedgerCurrency.class);
        Map<LedgerCurrency, BigInteger> pending =
                new EnumMap<>(LedgerCurrency.class);

        try {

            walletStatuses =
                    readWalletStatuses(
                            userId
                    );

            readBalances(
                    userId,
                    available,
                    pending
            );

        } catch (LedgerProviderUnavailableException
                 | LedgerTransactionRejectedException exception) {

            /*
             * A read never returns partial or guessed
             * balances. A Formance 4xx on a read is an
             * integration fault, not a customer error,
             * so it is reported the same way.
             */
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Wallet balances are temporarily unavailable",
                    exception
            );
        }

        List<CustomerWallet> wallets =
                new ArrayList<>();

        for (LedgerCurrency currency
                : LedgerCurrency.values()) {

            String status =
                    walletStatuses.get(currency);

            if (status == null) {

                wallets.add(
                        new CustomerWallet(
                                currency,
                                currency.scale(),
                                currency.formanceAsset(),
                                CustomerWallet.NOT_PROVISIONED,
                                null,
                                null
                        )
                );

                continue;
            }

            wallets.add(
                    new CustomerWallet(
                            currency,
                            currency.scale(),
                            currency.formanceAsset(),
                            status,
                            available
                                    .getOrDefault(
                                            currency,
                                            BigInteger.ZERO
                                    )
                                    .toString(),
                            pending
                                    .getOrDefault(
                                            currency,
                                            BigInteger.ZERO
                                    )
                                    .toString()
                    )
            );
        }

        return new CustomerWallets(
                walletsStatus(
                        userId,
                        walletStatuses.size()
                ),
                asOf,
                List.copyOf(wallets)
        );
    }

    private CustomerWalletsStatus walletsStatus(
            UUID userId,
            int provisionedCount
    ) {

        if (provisionedCount
                == LedgerCurrency.values().length) {

            return CustomerWalletsStatus.READY;
        }

        boolean kycApproved =
                kycStatusLookup
                        .findStatus(userId)
                        .map("APPROVED"::equals)
                        .orElse(false);

        /*
         * Wallets are provisioned only after KYC approval.
         *
         * Partially provisioned wallets of a customer whose
         * KYC is no longer APPROVED are still reported as
         * PROVISIONING: existing balances are always shown
         * as they are.
         */
        if (kycApproved
                || provisionedCount > 0) {

            return CustomerWalletsStatus.PROVISIONING;
        }

        return CustomerWalletsStatus.NOT_ELIGIBLE;
    }

    /*
     * A wallet is provisioned when its available account
     * carries the neos.wallet_status metadata written by
     * WalletProvisioningService.
     *
     * Formance creates an account implicitly on its first
     * movement, so account existence alone is not enough.
     */
    private Map<LedgerCurrency, String> readWalletStatuses(
            UUID userId
    ) {

        Map<String, Object> filter =
                Map.of(
                        "$match",
                        Map.of(
                                "address",
                                availablePattern(userId)
                        )
                );

        Map<LedgerCurrency, String> statuses =
                new EnumMap<>(LedgerCurrency.class);

        String cursor = null;

        for (int page = 0; page < MAX_PAGES; page++) {

            FormanceAccountsResponse response =
                    formanceClient.getAccounts(
                            filter,
                            PAGE_SIZE,
                            cursor
                    );

            for (FormanceAccountData account
                    : response.cursor().data()) {

                Optional<WalletAccount> walletAccount =
                        WalletAccount.parse(
                                userId,
                                account.address()
                        );

                String status =
                        account.metadata() == null
                                ? null
                                : account.metadata().get(
                                        WALLET_STATUS_METADATA
                                );

                if (walletAccount.isEmpty()
                        || walletAccount.get().hold()
                        || status == null
                        || status.isBlank()) {

                    continue;
                }

                statuses.put(
                        walletAccount.get().currency(),
                        status
                );
            }

            if (!response.cursor().hasMore()) {
                return statuses;
            }

            cursor = response.cursor().next();
        }

        throw new LedgerProviderUnavailableException(
                "Formance wallet account listing exceeded "
                        + MAX_PAGES
                        + " pages"
        );
    }

    /*
     * One paginated /volumes query covers every available
     * and hold account of the customer.
     */
    private void readBalances(
            UUID userId,
            Map<LedgerCurrency, BigInteger> available,
            Map<LedgerCurrency, BigInteger> pending
    ) {

        Map<String, Object> filter =
                Map.of(
                        "$or",
                        List.of(
                                Map.of(
                                        "$match",
                                        Map.of(
                                                "account",
                                                availablePattern(userId)
                                        )
                                ),
                                Map.of(
                                        "$match",
                                        Map.of(
                                                "account",
                                                holdsPattern(userId)
                                        )
                                )
                        )
                );

        String cursor = null;

        for (int page = 0; page < MAX_PAGES; page++) {

            FormanceVolumesResponse response =
                    formanceClient.getVolumes(
                            filter,
                            PAGE_SIZE,
                            cursor
                    );

            for (FormanceVolumeBalance volume
                    : response.cursor().data()) {

                Optional<WalletAccount> walletAccount =
                        WalletAccount.parse(
                                userId,
                                volume.account()
                        );

                if (walletAccount.isEmpty()
                        || volume.balance() == null) {
                    continue;
                }

                LedgerCurrency currency =
                        walletAccount.get().currency();

                // Only the canonical asset of the currency
                // (for example EUR/2) is a wallet balance.
                if (!currency.formanceAsset()
                        .equals(volume.asset())) {
                    continue;
                }

                (walletAccount.get().hold()
                        ? pending
                        : available)
                        .merge(
                                currency,
                                volume.balance(),
                                BigInteger::add
                        );
            }

            if (!response.cursor().hasMore()) {
                return;
            }

            cursor = response.cursor().next();
        }

        throw new LedgerProviderUnavailableException(
                "Formance wallet volumes exceeded "
                        + MAX_PAGES
                        + " pages"
        );
    }

    /*
     * Formance v2 partial address: an empty segment
     * matches any value, and the number of segments
     * must match.
     *
     * users:{userId}:wallet:{CUR}:available
     */
    private static String availablePattern(
            UUID userId
    ) {
        return "users:" + userId + ":wallet::available";
    }

    /*
     * users:{userId}:wallet:{CUR}:holds:{operationId}
     */
    private static String holdsPattern(
            UUID userId
    ) {
        return "users:" + userId + ":wallet::holds:";
    }

    private record WalletAccount(
            LedgerCurrency currency,
            boolean hold
    ) {

        static Optional<WalletAccount> parse(
                UUID userId,
                String address
        ) {

            String prefix =
                    "users:" + userId + ":wallet:";

            if (address == null
                    || !address.startsWith(prefix)) {
                return Optional.empty();
            }

            String[] segments =
                    address
                            .substring(prefix.length())
                            .split(":", -1);

            Optional<LedgerCurrency> currency =
                    currencyOf(segments[0]);

            if (currency.isEmpty()) {
                return Optional.empty();
            }

            if (segments.length == 2
                    && "available".equals(segments[1])) {

                return Optional.of(
                        new WalletAccount(
                                currency.get(),
                                false
                        )
                );
            }

            if (segments.length == 3
                    && "holds".equals(segments[1])
                    && !segments[2].isEmpty()) {

                return Optional.of(
                        new WalletAccount(
                                currency.get(),
                                true
                        )
                );
            }

            return Optional.empty();
        }

        private static Optional<LedgerCurrency> currencyOf(
                String code
        ) {

            for (LedgerCurrency currency
                    : LedgerCurrency.values()) {

                if (currency.currencyCode().equals(code)) {
                    return Optional.of(currency);
                }
            }

            return Optional.empty();
        }
    }
}
