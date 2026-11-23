package com.fintech.ledger.api;

public record CustomerInternalTransferResponse(
        long transactionId,
        String reference
) {
}