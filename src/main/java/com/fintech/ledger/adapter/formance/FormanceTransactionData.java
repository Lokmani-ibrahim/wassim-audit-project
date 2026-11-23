package com.fintech.ledger.adapter.formance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FormanceTransactionData(
        List<FormanceTransactionPosting> postings,
        Map<String, String> metadata,
        Instant timestamp,
        String reference,
        Long id,
        Instant insertedAt,
        Instant updatedAt,
        Boolean reverted
) {
}