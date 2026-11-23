package com.fintech.ledger.api;

import com.fintech.ledger.RecipientLookupResult;

import java.util.UUID;

public record RecipientLookupResponse(
        UUID recipientUserId,
        String maskedEmail
) {

    public static RecipientLookupResponse from(
            RecipientLookupResult result
    ) {
        return new RecipientLookupResponse(
                result.recipientUserId(),
                result.maskedEmail()
        );
    }
}
