package com.fintech.ledger.adapter.formance;

import java.util.Objects;

public record FormanceScript(
        String plain
) {

    public FormanceScript {

        Objects.requireNonNull(
                plain,
                "plain must not be null"
        );

        if (plain.isBlank()) {
            throw new IllegalArgumentException(
                    "plain must not be blank"
            );
        }
    }
}