package com.fintech.shared.money;

import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

public record Money(
        long amountMinor,
        Currency currency
) {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
    }

    public static Money of(long amountMinor, String currencyCode) {
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");

        return new Money(
                amountMinor,
                Currency.getInstance(
                        currencyCode.trim().toUpperCase(Locale.ROOT)
                )
        );
    }

    public Money add(Money other) {
        requireSameCurrency(other);

        return new Money(
                Math.addExact(this.amountMinor, other.amountMinor),
                this.currency
        );
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);

        return new Money(
                Math.subtractExact(this.amountMinor, other.amountMinor),
                this.currency
        );
    }

    public Money negate() {
        return new Money(
                Math.negateExact(this.amountMinor),
                this.currency
        );
    }

    public boolean isZero() {
        return amountMinor == 0;
    }

    public boolean isPositive() {
        return amountMinor > 0;
    }

    public boolean isNegative() {
        return amountMinor < 0;
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other money must not be null");

        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot operate on different currencies: "
                            + this.currency.getCurrencyCode()
                            + " and "
                            + other.currency.getCurrencyCode()
            );
        }
    }
}