package com.fintech.ledger.api;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record CustomerInternalTransferRequest(

        @NotNull
        UUID destinationUserId,

        @NotNull
        LedgerCurrency currency,

        @Positive
        long amountMinor,

        @NotNull
        UUID idempotencyKey

) {
}