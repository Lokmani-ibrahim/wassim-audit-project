package com.fintech.ledger.api;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import java.util.UUID;

public record SyntheticCreditResponse(

        UUID userId,
        LedgerCurrency currency,
        long amountMinor,
        long transactionId,
        String reference

) {
}