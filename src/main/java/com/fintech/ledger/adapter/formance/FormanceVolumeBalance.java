package com.fintech.ledger.adapter.formance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigInteger;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FormanceVolumeBalance(
        String account,
        String asset,
        BigInteger balance,
        BigInteger input,
        BigInteger output
) {
}