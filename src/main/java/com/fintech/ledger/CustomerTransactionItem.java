package com.fintech.ledger;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import java.time.Instant;

public record CustomerTransactionItem(

        long transactionId,
        CustomerTransactionType type,
        CustomerTransactionDirection direction,
        LedgerCurrency currency,
        long amountMinor,
        CustomerTransactionStatus status,
        String reference,
        Instant occurredAt

) {
}