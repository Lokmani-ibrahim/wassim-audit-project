package com.fintech.ledger.adapter.formance;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JSON request sent to the Formance Ledger v2
 * create-transaction endpoint.
 */
public record FormanceCreateTransactionRequest(
        List<FormancePosting> postings,
        Map<String, String> metadata,
        String reference
) {

    public FormanceCreateTransactionRequest {

        Objects.requireNonNull(
                postings,
                "postings must not be null"
        );

        Objects.requireNonNull(
                metadata,
                "metadata must not be null"
        );

        Objects.requireNonNull(
                reference,
                "reference must not be null"
        );

        if (postings.isEmpty()) {
            throw new IllegalArgumentException(
                    "postings must not be empty"
            );
        }

        if (reference.isBlank()) {
            throw new IllegalArgumentException(
                    "reference must not be blank"
            );
        }

        postings = List.copyOf(postings);
        metadata = Map.copyOf(metadata);
    }
}