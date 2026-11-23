
package com.fintech.ledger;

import org.springframework.security.access.AccessDeniedException;

/**
 * Sender authorization failed before Formance was called.
 *
 * This exception must never be thrown to represent
 * a failure originating from the ledger provider.
 */
public final class TransferAuthorizationDeniedBeforePostingException
        extends AccessDeniedException {

    public TransferAuthorizationDeniedBeforePostingException(
            AccessDeniedException cause
    ) {
        super(
                "Sender authorization denied before ledger submission",
                cause
        );
    }
}
