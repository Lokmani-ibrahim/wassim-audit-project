package com.fintech.ledger.port;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import java.util.UUID;

public interface LedgerProvider {

    void verifyConnection();

    LedgerOperationResult transferBetweenUsers(
            UUID sourceUserId,
            UUID destinationUserId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId
    );

    LedgerOperationResult reservePayout(
            UUID userId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId
    );

    LedgerOperationResult releasePayout(
            UUID userId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId
    );

    LedgerOperationResult settlePayout(
            UUID userId,
            String bankId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId
    );

    LedgerOperationResult collectFee(
            UUID userId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId
    );

    LedgerOperationResult recordIncomingPayment(
            UUID userId,
            String bankId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId
    );

    LedgerOperationResult recordUnattributedIncomingPayment(
            String bankId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId
    );

    LedgerOperationResult attributeIncomingPayment(
            UUID userId,
            LedgerCurrency currency,
            long amountMinor,
            String operationId
    );
}