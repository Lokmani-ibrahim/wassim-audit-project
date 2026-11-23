package com.fintech.ledger;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
class LedgerCustomerFinancialReadService implements CustomerFinancialRead {
    private final CustomerWalletService wallets;
    private final CustomerTransactionHistoryService history;

    LedgerCustomerFinancialReadService(CustomerWalletService wallets,
                                       CustomerTransactionHistoryService history) {
        this.wallets = wallets;
        this.history = history;
    }

    @Override
    public WalletPage wallets(UUID userId) {
        CustomerWallets result = wallets.getWalletsForUser(userId);
        List<Wallet> rows = result.wallets().stream()
                .filter(wallet -> !CustomerWallet.NOT_PROVISIONED.equals(wallet.status()))
                .map(wallet -> new Wallet(wallet.currency().name(), wallet.scale(),
                        wallet.status(), wallet.availableMinor(), wallet.pendingMinor()))
                .toList();
        // Retrieval time is measured after both provider reads; it is not a ledger snapshot.
        return new WalletPage(Instant.now(), rows);
    }

    @Override
    public TransactionPage transactions(UUID userId, String currency,
                                        int pageSize, String providerCursor) {
        LedgerCurrency selected = LedgerCurrency.valueOf(currency);
        CustomerTransactionPage result = history.getHistoryForUser(
                userId, selected, pageSize, providerCursor);
        List<Transaction> rows = result.items().stream()
                .map(item -> new Transaction(Long.toString(item.transactionId()),
                        item.type().name(), item.direction().name(), item.currency().name(),
                        item.currency().scale(), Long.toString(item.amountMinor()),
                        item.status().name(), item.occurredAt()))
                .toList();
        return new TransactionPage(rows, result.hasMore(), result.nextCursor());
    }
}
