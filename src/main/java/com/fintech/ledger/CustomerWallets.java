package com.fintech.ledger;

import java.time.Instant;
import java.util.List;

public record CustomerWallets(

        CustomerWalletsStatus walletsStatus,
        Instant asOf,
        List<CustomerWallet> wallets

) {
}
