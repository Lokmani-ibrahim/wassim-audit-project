package com.fintech.ledger;

public class LedgerProviderUnavailableException
        extends RuntimeException {

    public LedgerProviderUnavailableException(
            String message
    ) {
        super(message);
    }

    public LedgerProviderUnavailableException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}