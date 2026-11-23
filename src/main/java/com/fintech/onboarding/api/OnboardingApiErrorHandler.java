package com.fintech.onboarding.api;

import com.fintech.identity.OnboardingIdentityConflictException;
import com.fintech.identity.UnusableIdentityClaimsException;
import com.fintech.identity.api.AuthController;
import com.fintech.onboarding.UnsupportedTermsVersionException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = { AuthController.class, PersonalOnboardingController.class })
public class OnboardingApiErrorHandler {
    public record ErrorBody(String errorCode, String message, int status) {}

    @ExceptionHandler(UnusableIdentityClaimsException.class)
    ResponseEntity<ErrorBody> unusableIdentity() {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_CLAIMS_UNUSABLE",
                "The identity provider did not supply a usable account identity.");
    }

    @ExceptionHandler(OnboardingIdentityConflictException.class)
    ResponseEntity<ErrorBody> identityConflict() {
        return error(HttpStatus.CONFLICT, "ONBOARDING_IDENTITY_CONFLICT",
                "This account cannot be completed with the current identity.");
    }

    // Invoked after the service transaction has rolled back. Only the two known users keys
    // receive specific outcomes; unrelated integrity failures remain server errors.
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorBody> integrity(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())) {
                String constraint = violation.getConstraintName();
                if ("users_email_key".equals(constraint)) return identityConflict();
                if ("users_keycloak_subject_key".equals(constraint))
                    return error(HttpStatus.CONFLICT, "ONBOARDING_RETRY_REQUIRED",
                            "Please check your account status and try again.");
            }
        }
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "ONBOARDING_SAVE_FAILED",
                "We could not save your information right now.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> invalidRequest() {
        return error(HttpStatus.BAD_REQUEST, "ONBOARDING_INVALID_REQUEST",
                "Please review the information you entered.");
    }

    @ExceptionHandler(UnsupportedTermsVersionException.class)
    ResponseEntity<ErrorBody> unsupportedTerms() {
        return error(HttpStatus.BAD_REQUEST, "TERMS_VERSION_UNSUPPORTED",
                "Please update the app before continuing.");
    }

    private ResponseEntity<ErrorBody> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ErrorBody(code, message, status.value()));
    }
}
