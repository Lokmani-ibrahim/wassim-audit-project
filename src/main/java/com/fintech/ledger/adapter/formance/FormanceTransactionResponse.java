package com.fintech.ledger.adapter.formance;

/**
 * Minimal representation of the Formance response.
 *
 * We intentionally deserialize only fields Neos currently needs.
 * Jackson ignores the remaining response fields.
 */
public record FormanceTransactionResponse(
        Data data
) {

    public record Data(
            long id,
            String reference
    ) {
    }
}