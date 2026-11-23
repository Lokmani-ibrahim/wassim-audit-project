package com.fintech.kyc.api;

import com.fintech.kyc.KycStartResult;

import java.util.UUID;

public record KycStartResponse(
        UUID userId,
        String status,
        String provider,
        String verificationMode
) {

    public static KycStartResponse from(
            KycStartResult result
    ) {
        return new KycStartResponse(
                result.userId(),
                result.status(),
                result.provider(),
                result.verificationMode()
        );
    }
}