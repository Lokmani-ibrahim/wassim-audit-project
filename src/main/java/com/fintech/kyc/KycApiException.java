
package com.fintech.kyc;

import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

/**
 * Application-level KYC failure with a stable error code.
 *
 * Extends ResponseStatusException so existing tests
 * and HTTP status behavior remain compatible.
 */
public class KycApiException extends ResponseStatusException {

    private final KycApiErrorCode errorCode;

    public KycApiException(KycApiErrorCode errorCode) {
        super(
                Objects.requireNonNull(errorCode).status(),
                errorCode.message()
        );

        this.errorCode = errorCode;
    }

    public KycApiErrorCode getErrorCode() {
        return errorCode;
    }
}
