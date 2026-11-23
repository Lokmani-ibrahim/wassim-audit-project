package com.fintech.kyc;

import java.util.Objects;
import java.util.UUID;

public record KycApprovedEvent(
        UUID userId
) {

    public KycApprovedEvent {

        Objects.requireNonNull(
                userId,
                "userId must not be null"
        );
    }
}