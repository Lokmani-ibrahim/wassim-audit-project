package com.fintech.ledger.api;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SyntheticCreditRequest(

        @NotNull
        UUID userId,

        @NotNull
        LedgerCurrency currency,

        @Positive
        long amountMinor,

        @NotBlank
        @Size(max = 128)
        @Pattern(
                regexp = "^[A-Za-z0-9_-]+$",
                message = "operationId may contain only letters, numbers, '_' and '-'"
        )
        String operationId

) {
}