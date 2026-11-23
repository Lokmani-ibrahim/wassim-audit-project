package com.fintech.ledger.adapter.formance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FormanceTransactionsResponse(
        FormanceTransactionsCursor cursor
) {
}