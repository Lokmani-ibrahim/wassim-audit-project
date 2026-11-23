
package com.fintech.ledger;

import org.springframework.security.access.AccessDeniedException;

public final class PayoutAuthorizationDeniedBeforePostingException
        extends AccessDeniedException {

    public PayoutAuthorizationDeniedBeforePostingException(
            AccessDeniedException cause
    ) {
        super(
                "Payout authorization denied before Formance submission",
                cause
        );
    }
}
