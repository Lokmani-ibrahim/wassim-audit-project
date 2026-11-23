package com.fintech.kyc;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class KycOnboardingRequiredException extends RuntimeException {

    public KycOnboardingRequiredException() {
        super("Personal onboarding must be completed before starting KYC");
    }
}