package com.fintech.ledger.adapter.formance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FormanceAccountsCursor(
        List<FormanceAccountData> data,
        boolean hasMore,
        String next,
        long pageSize,
        String previous
) {
}
