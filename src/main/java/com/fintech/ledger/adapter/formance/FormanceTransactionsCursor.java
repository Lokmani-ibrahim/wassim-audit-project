package com.fintech.ledger.adapter.formance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FormanceTransactionsCursor(
        long pageSize,
        boolean hasMore,
        String next,
        List<FormanceTransactionData> data
) {
}