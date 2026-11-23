package com.fintech.ledger.fx;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Component
public class FxSandboxRateProvider {

    private final Map<String, BigDecimal> rates;

    public FxSandboxRateProvider(
            @Value("${NEOS_FX_SANDBOX_RATES:}")
            String specification
    ) {
        Objects.requireNonNull(specification);

        Map<String, BigDecimal> configured = new HashMap<>();

        if (!specification.isBlank()) {

            for (String entry : specification.split(",")) {

                String[] parts = entry.trim().split("=", -1);

                if (parts.length != 2) {
                    throw new IllegalArgumentException(
                            "Invalid FX sandbox rate configuration"
                    );
                }

                String[] pair = parts[0].trim().split("/", -1);

                if (pair.length != 2) {
                    throw new IllegalArgumentException(
                            "Invalid FX currency pair"
                    );
                }

                LedgerCurrency source =
                        LedgerCurrency.fromCode(pair[0]);

                LedgerCurrency destination =
                        LedgerCurrency.fromCode(pair[1]);

                if (source == destination) {
                    throw new IllegalArgumentException(
                            "FX pair must contain different currencies"
                    );
                }

                BigDecimal rate;

                try {
                    rate = new BigDecimal(parts[1].trim())
                            .stripTrailingZeros();
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException(
                            "Invalid FX sandbox exchange rate",
                            exception
                    );
                }

                if (rate.signum() <= 0
                        || rate.precision() > 18
                        || rate.scale() > 12) {

                    throw new IllegalArgumentException(
                            "FX sandbox rate must be positive "
                                    + "and have supported precision"
                    );
                }

                String key = key(source, destination);

                if (configured.putIfAbsent(key, rate) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate FX sandbox currency pair"
                    );
                }
            }
        }

        this.rates = Map.copyOf(configured);
    }

    public BigDecimal rateFor(
            LedgerCurrency source,
            LedgerCurrency destination
    ) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(destination);

        BigDecimal rate = rates.get(key(source, destination));

        if (rate == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "FX sandbox rate is not configured for this pair"
            );
        }

        return rate;
    }

    private static String key(
            LedgerCurrency source,
            LedgerCurrency destination
    ) {
        return source.currencyCode()
                + "/"
                + destination.currencyCode();
    }
}