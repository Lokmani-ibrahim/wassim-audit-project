package com.fintech.ledger.fx.api;

import java.time.Instant;
import java.util.UUID;

public record FxQuoteResponse(

        UUID quoteId,
        UUID idempotencyKey,

        String sourceCurrency,
        String destinationCurrency,

        String sourceMinor,
        String destinationMinor,

        int sourceScale,
        int destinationScale,

        String exchangeRate,

        String pricingMode,
        boolean executable,

        Instant asOf,
        Instant expiresAt
) {
}