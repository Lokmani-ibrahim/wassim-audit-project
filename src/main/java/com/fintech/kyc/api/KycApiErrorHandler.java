
package com.fintech.kyc.api;

import com.fintech.kyc.KycApiErrorCode;
import com.fintech.kyc.KycApiException;
import com.fintech.kyc.KycOnboardingRequiredException;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralized exception handler for KYC REST endpoints.
 *
 * Returns stable, machine-readable error codes
 * for the NEOS mobile application.
 *
 * Restricted to the KYC API package.
 */
@RestControllerAdvice(
        basePackages = "com.fintech.kyc.api"
)
public class KycApiErrorHandler {

    /**
     * Handle known KYC business errors.
     */
    @ExceptionHandler(KycApiException.class)
    public ResponseEntity<KycErrorResponse> handleKycError(
            KycApiException exception
    ) {

        KycApiErrorCode code = exception.getErrorCode();

        return response(
                code.status(),
                code.name(),
                code.message()
        );
    }

    /**
     * Preserve onboarding HTTP 409 behavior while
     * providing a stable ONBOARDING_REQUIRED error.
     */
    @ExceptionHandler(
            KycOnboardingRequiredException.class
    )
    public ResponseEntity<KycErrorResponse>
    handleOnboardingRequired(
            KycOnboardingRequiredException exception
    ) {

        KycApiErrorCode code =
                KycApiErrorCode.ONBOARDING_REQUIRED;

        return response(
                code.status(),
                code.name(),
                code.message()
        );
    }

    /**
     * Build a standardized non-cacheable JSON response.
     */
    private ResponseEntity<KycErrorResponse> response(
            HttpStatus status,
            String errorCode,
            String message
    ) {

        return ResponseEntity
                .status(status)
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.PRAGMA,
                        "no-cache"
                )
                .body(
                        new KycErrorResponse(
                                errorCode,
                                message,
                                status.value()
                        )
                );
    }

    /**
     * Public JSON error contract.
     */
    public record KycErrorResponse(
            String errorCode,
            String message,
            int status
    ) {
    }
}
