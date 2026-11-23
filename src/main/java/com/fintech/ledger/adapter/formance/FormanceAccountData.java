package com.fintech.ledger.adapter.formance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FormanceAccountData(
        String address,
        Map<String, String> metadata
) {
}