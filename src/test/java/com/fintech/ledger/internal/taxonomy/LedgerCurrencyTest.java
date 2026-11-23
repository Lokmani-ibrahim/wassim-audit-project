package com.fintech.ledger.internal.taxonomy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerCurrencyTest {

    @Test
    void exposesCanonicalFormanceAssets() {

        assertThat(
                LedgerCurrency.EUR.formanceAsset()
        ).isEqualTo("EUR/2");

        assertThat(
                LedgerCurrency.USD.formanceAsset()
        ).isEqualTo("USD/2");

        assertThat(
                LedgerCurrency.GBP.formanceAsset()
        ).isEqualTo("GBP/2");

        assertThat(
                LedgerCurrency.AED.formanceAsset()
        ).isEqualTo("AED/2");

        assertThat(
                LedgerCurrency.MUR.formanceAsset()
        ).isEqualTo("MUR/2");

        assertThat(
                LedgerCurrency.TND.formanceAsset()
        ).isEqualTo("TND/3");
    }

    @Test
    void parsesCurrencyCodesCaseInsensitively() {

        assertThat(
                LedgerCurrency.fromCode(" eur ")
        ).isEqualTo(LedgerCurrency.EUR);

        assertThat(
                LedgerCurrency.fromCode("tNd")
        ).isEqualTo(LedgerCurrency.TND);
    }

    @Test
    void rejectsUnsupportedCurrency() {

        assertThatThrownBy(
                () -> LedgerCurrency.fromCode("JPY")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Unsupported Neos ledger currency"
                );
    }

    @Test
    void rejectsBlankCurrency() {

        assertThatThrownBy(
                () -> LedgerCurrency.fromCode("  ")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "must not be blank"
                );
    }
}