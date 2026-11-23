package com.fintech.ledger;

import java.util.UUID;

public record RecipientLookupResult(
        UUID recipientUserId,
        String maskedEmail
) {
}
