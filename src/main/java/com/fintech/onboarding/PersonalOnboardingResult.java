package com.fintech.onboarding;

import java.util.UUID;

public record PersonalOnboardingResult(
        UUID userId,
        UUID deviceId,
        String kycStatus,
        String termsVersion
) {
}