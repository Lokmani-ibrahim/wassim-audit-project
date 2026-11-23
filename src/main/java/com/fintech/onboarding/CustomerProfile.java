package com.fintech.onboarding;

import java.time.Instant;
import java.util.UUID;

public record CustomerProfile(
        UUID userId,
        String email,
        String phoneNumber,
        String accountStatus,
        String kycStatus,
        Instant onboardedAt,
        Instant createdAt
) {
}
