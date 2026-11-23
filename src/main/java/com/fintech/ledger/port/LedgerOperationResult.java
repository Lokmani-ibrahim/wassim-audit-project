package com.fintech.ledger.port;

import java.util.Objects;

public record LedgerOperationResult(
        long transactionId,
        String reference
) {

    public LedgerOperationResult {

        if (transactionId < 0) {
            throw new IllegalArgumentException(
                    "transactionId must be nonnegative"
            );
        }

        Objects.requireNonNull(
                reference,
                "reference must not be null"
        );

        if (reference.isBlank()) {
            throw new IllegalArgumentException(
                    "reference must not be blank"
            );
        }
    }
}