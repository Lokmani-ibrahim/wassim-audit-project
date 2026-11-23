package com.fintech.ledger;

import java.util.List;

public record CustomerTransactionPage(

        List<CustomerTransactionItem> items,
        boolean hasMore,
        String nextCursor

) {
}