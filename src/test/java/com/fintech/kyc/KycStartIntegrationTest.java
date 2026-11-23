
package com.fintech.kyc;

import com.fintech.identity.entity.AccountStatus;
import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.port.KycProvider;
import com.fintech.kyc.repository.KycStatusRepository;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.app-token=test-app-token",
        "neos.kyc.sumsub.secret-key=test-secret"
})
@AutoConfigureMockMvc
@Transactional
@Rollback
class KycStartIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KycStatusRepository kycStatusRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean(enforceOverride = true)
    private KycProvider kycProvider;

    @BeforeEach
    void configureProvider() {

        when(kycProvider.createApplicant(any()))
                .thenAnswer(invocation -> {

                    KycProvider.CreateApplicantCommand command =
                            invocation.getArgument(0);

                    return new KycProvider.Applicant(
                            "sandbox-" + command.userId()
                    );
                });
    }

    @Test
    void customerStartsSumsubWithoutAutomaticApproval()
            throws Exception {

        String subject = uniqueSubject();

        UUID userId = createOnboardedCustomer(subject);

        mockMvc.perform(
                        post("/api/kyc/start")
                                .with(customerJwt(subject))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId")
                        .value(userId.toString()))
                .andExpect(jsonPath("$.status")
                        .value("PENDING"))
                .andExpect(jsonPath("$.provider")
                        .value("SUMSUB"))
                .andExpect(jsonPath("$.verificationMode")
                        .value("NON_PROD"));

        KycStatusEntity kyc =
                kycStatusRepository
                        .findByUserId(userId)
                        .orElseThrow();

        assertThat(kyc.getStatus())
                .isEqualTo(KycStatus.PENDING);

        assertThat(kyc.getProvider())
                .isEqualTo("SUMSUB");

        assertThat(kyc.getProviderApplicantId())
                .isEqualTo("sandbox-" + userId);

        assertThat(kyc.getStartedAt())
                .isNotNull();

        assertThat(kyc.getDecidedAt())
                .isNull();

        assertAccountStatus(
                userId,
                AccountStatus.PENDING_KYC
        );

        verify(kycProvider, times(1))
                .createApplicant(any());
    }

    @Test
    void retryingSumsubStartDoesNotCreateAnotherApplicant()
            throws Exception {

        String subject = uniqueSubject();

        UUID userId = createOnboardedCustomer(subject);

        mockMvc.perform(
                        post("/api/kyc/start")
                                .with(customerJwt(subject))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("PENDING"));

        KycStatusEntity first =
                kycStatusRepository
                        .findByUserId(userId)
                        .orElseThrow();

        String applicantId =
                first.getProviderApplicantId();

        Instant startedAt =
                first.getStartedAt();

        mockMvc.perform(
                        post("/api/kyc/start")
                                .with(customerJwt(subject))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("PENDING"));

        KycStatusEntity second =
                kycStatusRepository
                        .findByUserId(userId)
                        .orElseThrow();

        assertThat(second.getProviderApplicantId())
                .isEqualTo(applicantId);

        assertThat(second.getStartedAt())
                .isEqualTo(startedAt);

        assertThat(second.getDecidedAt())
                .isNull();

        assertAccountStatus(
                userId,
                AccountStatus.PENDING_KYC
        );

        verify(kycProvider, times(1))
                .createApplicant(any());
    }

    @Test
    void approvedRetryCannotReactivateSuspendedAccount()
            throws Exception {

        verifyApprovedRetryPreservesStatus(
                AccountStatus.SUSPENDED
        );
    }

    @Test
    void approvedRetryCannotReactivateClosedAccount()
            throws Exception {

        verifyApprovedRetryPreservesStatus(
                AccountStatus.CLOSED
        );
    }

    @Test
    void startingVerificationCannotActivateSuspendedAccount()
            throws Exception {

        verifyInitialStartPreservesStatus(
                AccountStatus.SUSPENDED
        );
    }

    @Test
    void startingVerificationCannotActivateClosedAccount()
            throws Exception {

        verifyInitialStartPreservesStatus(
                AccountStatus.CLOSED
        );
    }

    @Test
    void nonCustomerCannotStartKyc() throws Exception {

        mockMvc.perform(
                        post("/api/kyc/start")
                                .with(jwt()
                                        .jwt(token -> token.subject(
                                                "compliance-user"
                                        ))
                                        .authorities(
                                                new SimpleGrantedAuthority(
                                                        "ROLE_COMPLIANCE_OFFICER"
                                                )
                                        ))
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedRequestCannotStartKyc()
            throws Exception {

        mockMvc.perform(
                        post("/api/kyc/start")
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onboardingIsRequiredBeforeKyc()
            throws Exception {

        String unknownSubject = uniqueSubject();

        mockMvc.perform(
                        post("/api/kyc/start")
                                .with(customerJwt(unknownSubject))
                )
                .andExpect(status().isConflict());
    }

    @Test
    void sumsubTimeoutReturns503WithoutAdvancingKyc()
            throws Exception {

        String subject = uniqueSubject();
        UUID userId = createOnboardedCustomer(subject);

        /*
         * Override the default successful provider stub.
         */
        doThrow(
                new KycApiException(
                        KycApiErrorCode.SUMSUB_UNAVAILABLE
                )
        ).when(kycProvider).createApplicant(any());

        mockMvc.perform(
                        post("/api/kyc/start")
                                .with(customerJwt(subject))
                )
                .andExpect(status().isServiceUnavailable())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("SUMSUB_UNAVAILABLE")
                )
                .andExpect(
                        jsonPath("$.status")
                                .value(503)
                );

        /*
         * PostgreSQL must still contain NOT_STARTED.
         */
        assertPersistedKycState(
                userId,
                "NOT_STARTED",
                null,
                AccountStatus.PENDING_KYC
        );

        verify(kycProvider, times(1))
                .createApplicant(any());
    }

    @Test
    void invalidApplicantResponseReturns502WithoutAdvancingKyc()
            throws Exception {

        String subject = uniqueSubject();
        UUID userId = createOnboardedCustomer(subject);

        /*
         * Simulate a malformed provider response.
         *
         * This exercises KycStartService's own
         * validation of the provider result.
         */
        doReturn(null)
                .when(kycProvider)
                .createApplicant(any());

        mockMvc.perform(
                        post("/api/kyc/start")
                                .with(customerJwt(subject))
                )
                .andExpect(status().isBadGateway())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("SUMSUB_UPSTREAM_ERROR")
                )
                .andExpect(
                        jsonPath("$.status")
                                .value(502)
                );

        assertPersistedKycState(
                userId,
                "NOT_STARTED",
                null,
                AccountStatus.PENDING_KYC
        );

        verify(kycProvider, times(1))
                .createApplicant(any());
    }

    @Test
    void sdkTokenTimeoutReturns503WithoutChangingKyc()
            throws Exception {

        String subject = uniqueSubject();
        UUID userId = createOnboardedCustomer(subject);

        /*
         * First establish a valid PENDING KYC record.
         *
         * The existing BeforeEach provider stub
         * returns a deterministic applicant ID.
         */
        mockMvc.perform(
                        post("/api/kyc/start")
                                .with(customerJwt(subject))
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.status").value("PENDING")
                );

        /*
         * Isolate the SDK-token request's interactions.
         */
        clearInvocations(kycProvider);

        doThrow(
                new KycApiException(
                        KycApiErrorCode.SUMSUB_UNAVAILABLE
                )
        ).when(kycProvider)
                .createVerificationSession(any());

        mockMvc.perform(
                        post("/api/kyc/sdk-token")
                                .with(customerJwt(subject))
                )
                .andExpect(status().isServiceUnavailable())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("SUMSUB_UNAVAILABLE")
                )
                .andExpect(
                        jsonPath("$.status")
                                .value(503)
                )
                .andExpect(
                        jsonPath("$.token").doesNotExist()
                );

        /*
         * An SDK-token outage must not change
         * the existing KYC status or applicant.
         */
        assertPersistedKycState(
                userId,
                "PENDING",
                "sandbox-" + userId,
                AccountStatus.PENDING_KYC
        );

        verify(kycProvider, times(1))
                .createVerificationSession(any());

        verify(kycProvider, never())
                .createApplicant(any());
    }

    @Test
    void invalidSdkResponseReturns502WithoutChangingKyc()
            throws Exception {

        String subject = uniqueSubject();
        UUID userId = createOnboardedCustomer(subject);

        /*
         * Initialize the applicant successfully.
         */
        mockMvc.perform(
                        post("/api/kyc/start")
                                .with(customerJwt(subject))
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.status").value("PENDING")
                );

        clearInvocations(kycProvider);

        /*
         * The provider rejects a malformed SDK response.
         */
        doThrow(
                new KycApiException(
                        KycApiErrorCode.SUMSUB_UPSTREAM_ERROR
                )
        ).when(kycProvider)
                .createVerificationSession(any());

        mockMvc.perform(
                        post("/api/kyc/sdk-token")
                                .with(customerJwt(subject))
                )
                .andExpect(status().isBadGateway())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("SUMSUB_UPSTREAM_ERROR")
                )
                .andExpect(
                        jsonPath("$.status")
                                .value(502)
                )
                .andExpect(
                        jsonPath("$.token").doesNotExist()
                );

        assertPersistedKycState(
                userId,
                "PENDING",
                "sandbox-" + userId,
                AccountStatus.PENDING_KYC
        );

        verify(kycProvider, times(1))
                .createVerificationSession(any());

        verify(kycProvider, never())
                .createApplicant(any());
    }


    private void verifyApprovedRetryPreservesStatus(
            AccountStatus blockedStatus
    ) throws Exception {

        String subject = uniqueSubject();

        UUID userId = createOnboardedCustomer(subject);

        /*
         * Prepare an already-approved Sumsub customer.
         * No KYC start operation should occur here.
         */
        KycStatusEntity kyc =
                kycStatusRepository
                        .findByUserId(userId)
                        .orElseThrow();

        kyc.startVerification(
                "SUMSUB",
                "sandbox-" + userId
        );

        kyc.approve();

        kycStatusRepository.saveAndFlush(kyc);

        UserEntity user =
                userRepository.findById(userId)
                        .orElseThrow();

        user.activate();

        userRepository.saveAndFlush(user);

        blockAccount(userId, blockedStatus);

        /*
         * A retry must only read the existing KYC state.
         */
        mockMvc.perform(
                        post("/api/kyc/start")
                                .with(customerJwt(subject))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("APPROVED"))
                .andExpect(jsonPath("$.provider")
                        .value("SUMSUB"));

        assertAccountStatus(userId, blockedStatus);

        verify(kycProvider, never())
                .createApplicant(any());
    }

    private void verifyInitialStartPreservesStatus(
            AccountStatus blockedStatus
    ) throws Exception {

        String subject = uniqueSubject();

        UUID userId = createOnboardedCustomer(subject);

        blockAccount(userId, blockedStatus);

        mockMvc.perform(
                        post("/api/kyc/start")
                                .with(customerJwt(subject))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("PENDING"))
                .andExpect(jsonPath("$.provider")
                        .value("SUMSUB"));

        assertAccountStatus(userId, blockedStatus);

        verify(kycProvider, times(1))
                .createApplicant(any());
    }

    private void blockAccount(
            UUID userId,
            AccountStatus blockedStatus
    ) {

        UserEntity user =
                userRepository.findById(userId)
                        .orElseThrow();

        if (blockedStatus == AccountStatus.SUSPENDED) {
            user.suspend();

        } else if (blockedStatus == AccountStatus.CLOSED) {
            user.close();

        } else {
            throw new IllegalArgumentException(
                    "Expected blocked account status"
            );
        }

        userRepository.saveAndFlush(user);
    }

    private void assertAccountStatus(
            UUID userId,
            AccountStatus expected
    ) {
        assertThat(
                userRepository.findById(userId)
                        .orElseThrow()
                        .getAccountStatus()
        ).isEqualTo(expected);
    }

    private UUID createOnboardedCustomer(
            String subject
    ) {

        UserEntity user =
                userRepository.saveAndFlush(
                        new UserEntity(
                                subject,
                                subject + "@example.com",
                                "+21620123456"
                        )
                );

        kycStatusRepository.saveAndFlush(
                new KycStatusEntity(user.getId())
        );

        return user.getId();
    }


    private void assertPersistedKycState(
            UUID userId,
            String expectedKycStatus,
            String expectedApplicantId,
            AccountStatus expectedAccountStatus
    ) {

        /*
         * Query PostgreSQL directly instead of relying
         * on an entity from the JPA persistence context.
         */

        String actualKycStatus =
                jdbcTemplate.queryForObject(
                        """
                        SELECT status
                        FROM kyc_status
                        WHERE user_id = ?
                        """,
                        String.class,
                        userId
                );

        String actualApplicantId =
                jdbcTemplate.queryForObject(
                        """
                        SELECT provider_applicant_id
                        FROM kyc_status
                        WHERE user_id = ?
                        """,
                        String.class,
                        userId
                );

        Boolean verificationNotStartedOrDecided =
                jdbcTemplate.queryForObject(
                        """
                        SELECT decided_at IS NULL
                        FROM kyc_status
                        WHERE user_id = ?
                        """,
                        Boolean.class,
                        userId
                );

        String actualAccountStatus =
                jdbcTemplate.queryForObject(
                        """
                        SELECT account_status
                        FROM users
                        WHERE id = ?
                        """,
                        String.class,
                        userId
                );

        assertThat(actualKycStatus)
                .isEqualTo(expectedKycStatus);

        assertThat(actualApplicantId)
                .isEqualTo(expectedApplicantId);

        assertThat(actualAccountStatus)
                .isEqualTo(expectedAccountStatus.name());

        assertThat(verificationNotStartedOrDecided)
                .isTrue();
    }

    private String uniqueSubject() {
        return "kyc-sumsub-" + UUID.randomUUID();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor
    customerJwt(String subject) {

        return jwt()
                .jwt(token -> token.subject(subject))
                .authorities(
                        new SimpleGrantedAuthority(
                                "ROLE_CUSTOMER"
                        )
                );
    }
}
