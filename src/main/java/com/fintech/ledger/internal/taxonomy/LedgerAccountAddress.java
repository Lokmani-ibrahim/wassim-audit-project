package com.fintech.ledger.internal.taxonomy;

import java.util.Objects;

/**
 * Strongly typed Formance account reference used by the Neos ledger module.
 *
 * Keeping the currency next to the account address reduces the risk of
 * accidentally using, for example:
 *
 * users:<id>:wallet:EUR:available
 *
 * with the Formance asset:
 *
 * USD/2
 */
public record LedgerAccountAddress(
        String value,
        LedgerCurrency currency,
        LedgerAccountKind kind
) {

    public LedgerAccountAddress {

        Objects.requireNonNull(
                value,
                "value must not be null"
        );

        Objects.requireNonNull(
                currency,
                "currency must not be null"
        );

        Objects.requireNonNull(
                kind,
                "kind must not be null"
        );

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "value must not be blank"
            );
        }
    }

    /**
     * Returns the canonical Formance asset associated
     * with this account.
     */
    public String formanceAsset() {
        return currency.formanceAsset();
    }

    @Override
    public String toString() {
        return value;
    }
}