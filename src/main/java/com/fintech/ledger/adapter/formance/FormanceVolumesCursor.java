package com.fintech.ledger.adapter.formance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FormanceVolumesCursor(
        List<FormanceVolumeBalance> data,
        boolean hasMore,
        String next,
        long pageSize,
        String previous
) {
}