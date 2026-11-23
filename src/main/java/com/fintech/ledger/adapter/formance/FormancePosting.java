package com.fintech.ledger.adapter.formance;

import java.util.Objects;

/**
 * Low-level representation of a Formance posting.
 *
 * This type belongs exclusively to the Formance adapter.
 * It must not be exposed to controllers or other business modules.
 */
public record FormancePosting(
        String source,
        String destination,
        String asset,
        long amount
) {

    public FormancePosting {

        requireText(source, "source");
        requireText(destination, "destination");
        requireText(asset, "asset");

        if (amount <= 0) {
            throw new IllegalArgumentException(
                    "amount must be greater than zero"
            );
        }
    }

    private static void requireText(
            String value,
            String fieldName
    ) {

        Objects.requireNonNull(
                value,
                fieldName + " must not be null"
        );

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank"
            );
        }
    }
}