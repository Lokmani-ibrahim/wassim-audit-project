package com.fintech.onboarding.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PersonalOnboardingRequest(

        @Size(max = 32)
        String phoneNumber,

        @NotBlank
        @Size(max = 255)
        String installationId,

        @NotBlank
        @Pattern(
                regexp = "ANDROID|IOS|WEB",
                message = "platform must be ANDROID, IOS or WEB"
        )
        String platform,

        @NotBlank
        @Size(max = 50)
        String termsVersion
) {
}