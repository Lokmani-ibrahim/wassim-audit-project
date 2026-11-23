package com.fintech.kyc;

import java.util.UUID;

public record KycStartResult(
        UUID userId,
        String status,
        String provider,
        String verificationMode
) {
}