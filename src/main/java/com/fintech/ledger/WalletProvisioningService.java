package com.fintech.ledger;

import com.fintech.ledger.adapter.formance.FormanceClient;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddress;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddressFactory;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class WalletProvisioningService {

    private final FormanceClient formanceClient;

    public WalletProvisioningService(
            FormanceClient formanceClient
    ) {
        this.formanceClient =
                Objects.requireNonNull(
                        formanceClient,
                        "formanceClient must not be null"
                );
    }

    public void provisionCustomerWallets(
            UUID userId
    ) {

        Objects.requireNonNull(
                userId,
                "userId must not be null"
        );

        for (LedgerCurrency currency
                : LedgerCurrency.values()) {

            provisionAvailableWallet(
                    userId,
                    currency
            );
        }
    }

    private void provisionAvailableWallet(
            UUID userId,
            LedgerCurrency currency
    ) {

        LedgerAccountAddress account =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                userId,
                                currency
                        );

        Map<String, String> metadata =
                Map.of(
                        "neos.account_kind",
                        "customer_available",

                        "neos.user_id",
                        userId.toString(),

                        "neos.currency",
                        currency.currencyCode(),

                        "neos.wallet_status",
                        "ACTIVE"
                );

        String idempotencyKey =
                "wallet-provision:"
                        + userId
                        + ":"
                        + currency.currencyCode();

        formanceClient.addAccountMetadata(
                account.value(),
                metadata,
                idempotencyKey
        );
    }
}