package com.fintech.ledger.internal.taxonomy;

import java.util.Locale;
import java.util.Objects;

/**
 * Canonical currency / Formance asset registry for Neos.
 *
 * We intentionally define the precision explicitly instead of deriving
 * it dynamically from Java Currency.
 *
 * Formance treats EUR/2, EUR/3, etc. as different assets, so Neos must
 * have exactly one canonical representation for every supported currency.
 *
 * This enum describes currencies understood by the ledger.
 * Whether a currency is enabled for a specific environment is a
 * separate configuration concern.
 */
public enum LedgerCurrency {

    EUR(2),
    USD(2),
    GBP(2),
    AED(2),
    MUR(2),
    TND(3);

    private final int scale;

    LedgerCurrency(int scale) {
        this.scale = scale;
    }

    public int scale() {
        return scale;
    }

    public String currencyCode() {
        return name();
    }

    /**
     * Canonical Formance UMN asset identifier.
     *
     * Examples:
     * EUR -> EUR/2
     * TND -> TND/3
     */
    public String formanceAsset() {
        return name() + "/" + scale;
    }

    public static LedgerCurrency fromCode(String currencyCode) {
        Objects.requireNonNull(
                currencyCode,
                "currencyCode must not be null"
        );

        String normalized = currencyCode
                .trim()
                .toUpperCase(Locale.ROOT);

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "currencyCode must not be blank"
            );
        }

        try {
            return LedgerCurrency.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unsupported Neos ledger currency: " + normalized,
                    exception
            );
        }
    }
}