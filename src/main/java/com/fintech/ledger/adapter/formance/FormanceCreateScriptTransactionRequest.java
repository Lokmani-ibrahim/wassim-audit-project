package com.fintech.ledger.adapter.formance;

import java.util.Map;
import java.util.Objects;

public record FormanceCreateScriptTransactionRequest(
        FormanceScript script,
        Map<String, String> metadata,
        String reference
) {

    public FormanceCreateScriptTransactionRequest {

        Objects.requireNonNull(
                script,
                "script must not be null"
        );

        Objects.requireNonNull(
                metadata,
                "metadata must not be null"
        );

        metadata = Map.copyOf(metadata);

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