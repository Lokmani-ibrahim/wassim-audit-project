
package com.fintech.ledger.port;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LedgerOperationResultTest {

    @Test
    void acceptsZeroTransactionId() {
        var result = new LedgerOperationResult(
                0L,
                "payout_settle:test"
        );

        assertEquals(0L, result.transactionId());
    }

    @Test
    void rejectsNegativeTransactionId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LedgerOperationResult(
                        -1L,
                        "payout_settle:test"
                )
        );
    }
}
