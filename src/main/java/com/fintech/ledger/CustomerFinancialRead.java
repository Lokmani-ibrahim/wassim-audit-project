package com.fintech.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Customer-scoped ledger reads for authorized staff services. */
public interface CustomerFinancialRead {
    WalletPage wallets(UUID userId);
    TransactionPage transactions(UUID userId, String currency, int pageSize, String providerCursor);

    record WalletPage(Instant retrievedAt, List<Wallet> wallets) {}
    record Wallet(String currency, int scale, String status,
                  String availableMinor, String pendingMinor) {}
    record TransactionPage(List<Transaction> items, boolean hasMore, String nextCursor) {}
    record Transaction(String transactionId, String type, String direction,
                       String currency, int scale, String amountMinor,
                       String status, Instant occurredAt) {}
}
