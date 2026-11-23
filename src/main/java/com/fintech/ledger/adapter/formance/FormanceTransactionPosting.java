package com.fintech.ledger.adapter.formance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FormanceTransactionPosting(
        String source,
        String destination,
        long amount,
        String asset
) {
}