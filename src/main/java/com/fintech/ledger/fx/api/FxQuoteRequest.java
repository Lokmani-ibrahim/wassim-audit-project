package com.fintech.ledger.fx.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record FxQuoteRequest(

        @NotNull
        UUID idempotencyKey,

        @NotBlank
        String sourceCurrency,

        @NotBlank
        String destinationCurrency,

        @NotBlank
        @Pattern(
                regexp = "[0-9]{1,18}",
                message = "sourceMinor must contain 1 to 18 digits"
        )
        String sourceMinor
) {
}