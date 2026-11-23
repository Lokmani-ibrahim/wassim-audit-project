
package com.fintech.kyc;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable KYC application errors.
 *
 * Do not rename an error code without coordinating
 * the API contract with the mobile application.
 */
public enum KycApiErrorCode {

    ONBOARDING_REQUIRED(
            HttpStatus.CONFLICT,
            "Personal onboarding must be completed before starting KYC"
    ),

    KYC_NOT_STARTED(
            HttpStatus.CONFLICT,
            "Start KYC verification first"
    ),

    KYC_ALREADY_APPROVED(
            HttpStatus.CONFLICT,
            "KYC verification is already approved"
    ),

    KYC_FINAL_REJECTED(
            HttpStatus.CONFLICT,
            "KYC verification has been rejected"
    ),

    ACCOUNT_RESTRICTED(
            HttpStatus.FORBIDDEN,
            "Customer account is not eligible for KYC"
    ),

    KYC_MANUAL_REVIEW_REQUIRED(
            HttpStatus.FORBIDDEN,
            "KYC requires administrative review"
    ),

    KYC_APPLICANT_INVALID(
            HttpStatus.CONFLICT,
            "A valid Sumsub applicant is required"
    ),

    KYC_STATE_NOT_ELIGIBLE(
            HttpStatus.CONFLICT,
            "KYC is not eligible for an SDK session"
    ),

    SUMSUB_UPSTREAM_ERROR(
            HttpStatus.BAD_GATEWAY,
            "Sumsub returned an invalid response"
    ),

    SUMSUB_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Identity verification is temporarily unavailable. Please try again later."
    );

    private final HttpStatus status;
    private final String message;

    KycApiErrorCode(
            HttpStatus status,
            String message
    ) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
