package com.fintech.shared.money;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {

    @Test
    void shouldCreateMoneyWithNormalizedCurrencyCode() {
        Money money = Money.of(1050, " eur ");

        assertEquals(1050, money.amountMinor());
        assertEquals("EUR", money.currencyCode());
    }

    @Test
    void shouldAddMoneyWithSameCurrency() {
        Money first = Money.of(1000, "EUR");
        Money second = Money.of(500, "EUR");

        Money result = first.add(second);

        assertEquals(1500, result.amountMinor());
        assertEquals("EUR", result.currencyCode());
    }

    @Test
    void shouldSubtractMoneyWithSameCurrency() {
        Money balance = Money.of(1000, "EUR");
        Money payment = Money.of(400, "EUR");

        Money result = balance.subtract(payment);

        assertEquals(600, result.amountMinor());
    }

    @Test
    void shouldRejectDifferentCurrencies() {
        Money euros = Money.of(1000, "EUR");
        Money dollars = Money.of(500, "USD");

        assertThrows(
                IllegalArgumentException.class,
                () -> euros.add(dollars)
        );
    }

    @Test
    void shouldRejectNullCurrencyCode() {
        assertThrows(
                NullPointerException.class,
                () -> Money.of(1000, null)
        );
    }

    @Test
    void shouldDetectZeroPositiveAndNegativeValues() {
        assertTrue(Money.of(0, "EUR").isZero());
        assertTrue(Money.of(100, "EUR").isPositive());
        assertTrue(Money.of(-100, "EUR").isNegative());
    }

    @Test
    void shouldNegateMoney() {
        Money amount = Money.of(1000, "EUR");

        Money result = amount.negate();

        assertEquals(-1000, result.amountMinor());
        assertEquals("EUR", result.currencyCode());
    }

    @Test
    void shouldFailOnAdditionOverflow() {
        Money max = Money.of(Long.MAX_VALUE, "EUR");
        Money one = Money.of(1, "EUR");

        assertThrows(
                ArithmeticException.class,
                () -> max.add(one)
        );
    }
}