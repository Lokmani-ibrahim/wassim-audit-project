package com.fintech.ledger;

/**
 * The ledger provider was reachable, but rejected
 * the requested financial operation.
 *
 * Examples:
 * - insufficient funds
 * - duplicate reference
 * - invalid posting
 * - schema violation
 */
public class LedgerTransactionRejectedException
        extends RuntimeException {

    public LedgerTransactionRejectedException(
            String message
    ) {
        super(message);
    }

    public LedgerTransactionRejectedException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}