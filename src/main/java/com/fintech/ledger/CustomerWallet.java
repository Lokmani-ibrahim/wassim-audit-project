package com.fintech.ledger;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

/**
 * Amounts are integer minor units encoded as strings.
 *
 * Formance amounts are arbitrary-precision integers;
 * a JSON number would lose precision in JavaScript
 * clients above 2^53 - 1.
 *
 * availableMinor and pendingMinor are null when the
 * wallet is NOT_PROVISIONED, never "0".
 */
public record CustomerWallet(

        LedgerCurrency currency,
        int scale,
        String asset,
        String status,
        String availableMinor,
        String pendingMinor

) {

    public static final String NOT_PROVISIONED =
            "NOT_PROVISIONED";
}
