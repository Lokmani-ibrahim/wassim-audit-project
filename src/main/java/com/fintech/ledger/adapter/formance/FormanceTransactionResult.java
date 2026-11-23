package com.fintech.ledger.adapter.formance;

/**
 * Result exposed by the low-level Formance client.
 */
public record FormanceTransactionResult(
        long transactionId,
        String reference
) {
}