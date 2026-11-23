package com.fintech.onboarding.api;

import com.fintech.onboarding.CustomerProfile;

import java.time.Instant;
import java.util.UUID;

public record CustomerProfileResponse(
        UUID userId,
        String email,
        String phoneNumber,
        String accountStatus,
        String kycStatus,
        Instant onboardedAt,
        Instant createdAt
) {

    public static CustomerProfileResponse from(
            CustomerProfile profile
    ) {
        return new CustomerProfileResponse(
                profile.userId(),
                profile.email(),
                profile.phoneNumber(),
                profile.accountStatus(),
                profile.kycStatus(),
                profile.onboardedAt(),
                profile.createdAt()
        );
    }
}
