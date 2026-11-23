package com.fintech.onboarding.api;

import com.fintech.onboarding.PersonalOnboardingResult;

import java.util.UUID;

public record PersonalOnboardingResponse(
        UUID userId,
        UUID deviceId,
        String kycStatus,
        String termsVersion
) {

    public static PersonalOnboardingResponse from(
            PersonalOnboardingResult result
    ) {
        return new PersonalOnboardingResponse(
                result.userId(),
                result.deviceId(),
                result.kycStatus(),
                result.termsVersion()
        );
    }
}