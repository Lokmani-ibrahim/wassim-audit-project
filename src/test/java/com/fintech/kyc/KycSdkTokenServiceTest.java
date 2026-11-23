
package com.fintech.kyc;

import com.fintech.identity.CustomerAccountLookup;
import com.fintech.identity.CustomerAccountSummary;
import com.fintech.identity.IdentityAccountService;
import com.fintech.identity.KycSdkAccess;

import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.port.KycProvider;
import com.fintech.kyc.repository.KycStatusRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KycSdkTokenServiceTest {

    @Mock
    private CustomerAccountLookup customerAccounts;

    @Mock
    private IdentityAccountService identityAccounts;

    @Mock
    private KycStatusRepository kycStatuses;

    @Mock
    private KycProvider provider;

    private KycSdkTokenService service;

    private final UUID userId = UUID.randomUUID();

    private final String subject = "keycloak-sdk-test";

    @BeforeEach
    void setup() {
        service = new KycSdkTokenService(
                customerAccounts,
                identityAccounts,
                kycStatuses,
                provider
        );
    }

    /*
     * ----------------------------------------------------
     * TEST FIXTURES
     * ----------------------------------------------------
     */

    private void givenAccess(KycSdkAccess access) {

        when(customerAccounts.findByKeycloakSubject(subject))
                .thenReturn(
                        Optional.of(
                                new CustomerAccountSummary(
                                        userId,
                                        "customer@example.com",
                                        null,
                                        "PENDING_KYC",
                                        Instant.now()
                                )
                        )
                );

        when(identityAccounts.kycSdkAccess(
                userId,
                subject
        )).thenReturn(access);
    }

    private KycStatusEntity kycWithStatus(
            KycStatus status
    ) {

        KycStatusEntity kyc =
                new KycStatusEntity(userId);

        if (status != KycStatus.NOT_STARTED) {

            kyc.startVerification(
                    "SUMSUB",
                    "sumsub-applicant-123"
            );
        }

        switch (status) {

            case IN_REVIEW ->
                    kyc.markInReview();

            case APPROVED ->
                    kyc.approve();

            case REJECTED ->
                    kyc.reject();

            case RESUBMISSION_REQUIRED ->
                    kyc.markResubmissionRequired();

            default -> {
            }
        }

        return kyc;
    }

    private void givenKyc(KycStatus status) {

        when(kycStatuses.findByUserId(userId))
                .thenReturn(
                        Optional.of(
                                kycWithStatus(status)
                        )
                );
    }

    private void givenValidSession() {

        when(provider.createVerificationSession(
                new KycProvider
                        .CreateVerificationSessionCommand(
                        userId
                )
        )).thenReturn(
                new KycProvider.VerificationSession(
                        "test-sdk-access-token",
                        600
                )
        );
    }

    /*
     * Shared assertion for Fix #9.
     *
     * Confirms:
     *
     * 1. The service throws KycApiException.
     * 2. The error code is exactly the expected one.
     * 3. The HTTP status remains compatible.
     *
     * This directly verifies the service's error
     * selection, not only the HTTP serialization.
     */
    private void assertKycError(
            KycApiErrorCode expectedCode
    ) {

        KycApiException exception =
                assertThrows(
                        KycApiException.class,
                        () -> service.createSession(subject)
                );

        assertEquals(
                expectedCode,
                exception.getErrorCode()
        );

        assertEquals(
                expectedCode.status(),
                exception.getStatusCode()
        );
    }

    /*
     * ----------------------------------------------------
     * SUCCESSFUL SDK SESSION TESTS
     * ----------------------------------------------------
     */

    @Test
    void pendingCustomerCanObtainSdkToken() {

        givenAccess(KycSdkAccess.STANDARD);
        givenKyc(KycStatus.PENDING);
        givenValidSession();

        var session = service.createSession(subject);

        assertEquals(
                "test-sdk-access-token",
                session.token()
        );

        assertEquals(
                600,
                session.expiresInSeconds()
        );

        verify(provider, times(1))
                .createVerificationSession(
                        new KycProvider
                                .CreateVerificationSessionCommand(
                                userId
                        )
                );
    }

    @Test
    void customerInReviewCanRenewToken() {

        givenAccess(KycSdkAccess.STANDARD);
        givenKyc(KycStatus.IN_REVIEW);
        givenValidSession();

        var session = service.createSession(subject);

        assertNotNull(session);

        assertEquals(
                "test-sdk-access-token",
                session.token()
        );
    }

    @Test
    void initialResubmissionIsAllowed() {

        givenAccess(KycSdkAccess.STANDARD);
        givenKyc(KycStatus.RESUBMISSION_REQUIRED);
        givenValidSession();

        var session = service.createSession(subject);

        assertNotNull(session);

        assertEquals(
                "test-sdk-access-token",
                session.token()
        );
    }

    @Test
    void suspendedKycReverificationCanObtainToken() {

        givenAccess(KycSdkAccess.REVERIFICATION);
        givenKyc(KycStatus.RESUBMISSION_REQUIRED);
        givenValidSession();

        var session = service.createSession(subject);

        assertEquals(
                "test-sdk-access-token",
                session.token()
        );

        assertEquals(
                600,
                session.expiresInSeconds()
        );
    }

    /*
     * ----------------------------------------------------
     * ACCOUNT SECURITY RESTRICTIONS
     * ----------------------------------------------------
     */

    @Test
    void reverificationRequiresMatchingKycStatus() {

        givenAccess(KycSdkAccess.REVERIFICATION);
        givenKyc(KycStatus.PENDING);

        assertKycError(
                KycApiErrorCode.ACCOUNT_RESTRICTED
        );

        verifyNoInteractions(provider);
    }

    @Test
    void restrictedAccountCannotObtainToken() {

        givenAccess(KycSdkAccess.RESTRICTED);

        assertKycError(
                KycApiErrorCode.ACCOUNT_RESTRICTED
        );

        /*
         * The account restriction takes precedence.
         *
         * KYC state is not consulted and no provider
         * call is made.
         */
        verifyNoInteractions(
                kycStatuses,
                provider
        );
    }

    /*
     * ----------------------------------------------------
     * STABLE KYC STATE ERROR CODES
     * ----------------------------------------------------
     */

    @Test
    void notStartedKycReturnsConflict() {

        givenAccess(KycSdkAccess.STANDARD);
        givenKyc(KycStatus.NOT_STARTED);

        assertKycError(
                KycApiErrorCode.KYC_NOT_STARTED
        );

        verifyNoInteractions(provider);
    }

    @Test
    void approvedKycReturnsConflict() {

        givenAccess(KycSdkAccess.STANDARD);
        givenKyc(KycStatus.APPROVED);

        assertKycError(
                KycApiErrorCode.KYC_ALREADY_APPROVED
        );

        /*
         * An already approved customer must not
         * receive another verification session.
         */
        verifyNoInteractions(provider);
    }

    @Test
    void rejectedKycReturnsConflict() {

        givenAccess(KycSdkAccess.STANDARD);
        givenKyc(KycStatus.REJECTED);

        assertKycError(
                KycApiErrorCode.KYC_FINAL_REJECTED
        );

        verifyNoInteractions(provider);
    }

    /*
     * ----------------------------------------------------
     * ONBOARDING AND AUTHENTICATION
     * ----------------------------------------------------
     */

    @Test
    void missingOnboardingIsRejected() {

        when(customerAccounts.findByKeycloakSubject(subject))
                .thenReturn(Optional.empty());

        /*
         * Preserve the existing exception class.
         *
         * KycApiErrorHandler converts this to:
         *
         * HTTP 409
         * errorCode = ONBOARDING_REQUIRED
         */
        assertThrows(
                KycOnboardingRequiredException.class,
                () -> service.createSession(subject)
        );

        verifyNoInteractions(
                identityAccounts,
                kycStatuses,
                provider
        );
    }

    @Test
    void blankSubjectIsUnauthorized() {

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.createSession(" ")
                );

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                exception.getStatusCode()
        );

        verifyNoInteractions(
                customerAccounts,
                identityAccounts,
                kycStatuses,
                provider
        );
    }

    /*
     * ----------------------------------------------------
     * SUMSUB APPLICANT VALIDATION
     * ----------------------------------------------------
     */

    @Test
    void missingSumsubApplicantReturnsConflict() {

        givenAccess(KycSdkAccess.STANDARD);

        KycStatusEntity kyc =
                mock(KycStatusEntity.class);

        when(kyc.getStatus())
                .thenReturn(KycStatus.PENDING);

        when(kyc.getProvider())
                .thenReturn("SUMSUB");

        /*
         * Provider applicant ID is intentionally null.
         */
        when(kycStatuses.findByUserId(userId))
                .thenReturn(Optional.of(kyc));

        assertKycError(
                KycApiErrorCode.KYC_APPLICANT_INVALID
        );

        verifyNoInteractions(provider);
    }

    @Test
    void legacyProviderIsRejected() {

        givenAccess(KycSdkAccess.STANDARD);

        KycStatusEntity kyc =
                mock(KycStatusEntity.class);

        when(kyc.getStatus())
                .thenReturn(KycStatus.PENDING);

        /*
         * Only SUMSUB is supported.
         *
         * Legacy MOCK records must not be allowed
         * to create real verification sessions.
         */
        when(kyc.getProvider())
                .thenReturn("MOCK");

        when(kycStatuses.findByUserId(userId))
                .thenReturn(Optional.of(kyc));

        assertKycError(
                KycApiErrorCode.KYC_APPLICANT_INVALID
        );

        verifyNoInteractions(provider);
    }

    /*
     * ----------------------------------------------------
     * ADMINISTRATIVE REVIEW
     * ----------------------------------------------------
     */

    @Test
    void manualReviewBlocksSdkSession() {

        givenAccess(KycSdkAccess.STANDARD);

        KycStatusEntity kyc =
                mock(KycStatusEntity.class);

        when(kyc.getStatus())
                .thenReturn(KycStatus.PENDING);

        when(kyc.getProvider())
                .thenReturn("SUMSUB");

        when(kyc.getProviderApplicantId())
                .thenReturn("sumsub-applicant-123");

        when(kyc.isManualReviewRequired())
                .thenReturn(true);

        when(kycStatuses.findByUserId(userId))
                .thenReturn(Optional.of(kyc));

        assertKycError(
                KycApiErrorCode.KYC_MANUAL_REVIEW_REQUIRED
        );

        /*
         * Manual-review restrictions must not
         * be bypassed by SDK-token issuance.
         */
        verifyNoInteractions(provider);
    }

    /*
     * ----------------------------------------------------
     * INVALID PROVIDER RESPONSE
     * ----------------------------------------------------
     */

    @Test
    void malformedProviderResponseIsRejected() {

        givenAccess(KycSdkAccess.STANDARD);
        givenKyc(KycStatus.PENDING);

        /*
         * Sumsub returns a malformed session.
         *
         * An empty token or invalid expiration
         * must never be returned to mobile.
         */
        when(provider.createVerificationSession(
                new KycProvider
                        .CreateVerificationSessionCommand(
                        userId
                )
        )).thenReturn(
                new KycProvider.VerificationSession(
                        "",
                        0
                )
        );

        assertKycError(
                KycApiErrorCode.SUMSUB_UPSTREAM_ERROR
        );

        verify(provider, times(1))
                .createVerificationSession(
                        new KycProvider
                                .CreateVerificationSessionCommand(
                                userId
                        )
                );
    }
}
