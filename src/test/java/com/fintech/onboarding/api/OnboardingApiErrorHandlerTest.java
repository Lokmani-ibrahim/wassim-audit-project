package com.fintech.onboarding.api;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OnboardingApiErrorHandlerTest {
    @Test
    void unrelatedIntegrityFailureIsNotReportedAsIdentityConflict() {
        var response = new OnboardingApiErrorHandler()
                .integrity(new DataIntegrityViolationException("unrelated integrity failure"));
        assertEquals(500, response.getStatusCode().value());
        assertEquals("ONBOARDING_SAVE_FAILED", response.getBody().errorCode());
    }

    @Test
    void onlyKnownUniqueConstraintsReceiveIdentityOrRetryCodes() {
        var handler = new OnboardingApiErrorHandler();
        assertEquals("ONBOARDING_IDENTITY_CONFLICT",
                handler.integrity(violation("23505", "users_email_key")).getBody().errorCode());
        assertEquals("ONBOARDING_RETRY_REQUIRED",
                handler.integrity(violation("23505", "users_keycloak_subject_key")).getBody().errorCode());
        assertEquals("ONBOARDING_SAVE_FAILED",
                handler.integrity(violation("23505", "devices_user_id_installation_id_key"))
                        .getBody().errorCode());
        assertEquals("ONBOARDING_SAVE_FAILED",
                handler.integrity(violation("23503", "users_email_key")).getBody().errorCode());
    }

    private DataIntegrityViolationException violation(String sqlState, String constraintName) {
        var cause = new ConstraintViolationException("test", new SQLException("test", sqlState),
                constraintName);
        return new DataIntegrityViolationException("test", cause);
    }
}
