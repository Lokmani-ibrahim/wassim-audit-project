
package com.fintech.kyc;

import com.fintech.identity.IdentityAccountService;
import com.fintech.identity.entity.AccountStatus;
import com.fintech.identity.entity.AccountSuspensionReason;
import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;

import com.fintech.kyc.adapter.sumsub.SumsubReviewedEvent;
import com.fintech.kyc.adapter.sumsub.SumsubReviewedEvent.Decision;
import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.port.KycProvider;
import com.fintech.kyc.repository.KycStatusRepository;

import com.fintech.ledger.WalletProvisioningService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@EnabledIfEnvironmentVariable(
        named = "RUN_KYC_REVERIFICATION_CONTAINER_IT",
        matches = "true"
)
@ActiveProfiles("dev")
@DirtiesContext(
        classMode = DirtiesContext.ClassMode.AFTER_CLASS
)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "neos.ledger.wallet-provisioning.recovery.enabled=false",
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false",
        "neos.ledger.formance.base-url=http://127.0.0.1:1"
})
class KycReverificationLifecycleContainerIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:18-alpine")
                    .withDatabaseName("kyc_reverification_it")
                    .withUsername("kyc_test")
                    .withPassword("kyc_test_only");

    @DynamicPropertySource
    static void databaseProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "spring.datasource.url",
                postgres::getJdbcUrl
        );
        registry.add(
                "spring.datasource.username",
                postgres::getUsername
        );
        registry.add(
                "spring.datasource.password",
                postgres::getPassword
        );
        registry.add(
                "spring.flyway.url",
                postgres::getJdbcUrl
        );
        registry.add(
                "spring.flyway.user",
                postgres::getUsername
        );
        registry.add(
                "spring.flyway.password",
                postgres::getPassword
        );
    }

    @Autowired
    private UserRepository users;

    @Autowired
    private KycStatusRepository kycStatuses;

    @Autowired
    private IdentityAccountService accounts;

    @Autowired
    private SumsubDecisionService decisions;

    @Autowired
    private KycSdkTokenService sdkTokens;

    @Autowired
    private KycWalletProvisioningEligibility eligibility;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /*
     * External operations are mocked.
     * Database and account lifecycle logic remain real.
     */
    @MockitoBean(enforceOverride = true)
    private KycProvider provider;

    @MockitoBean(enforceOverride = true)
    private WalletProvisioningService walletProvisioningService;

    private record Fixture(
            UUID userId,
            String subject,
            String applicantId
    ) {
    }

    private Fixture createCustomer() {

        String unique = UUID.randomUUID().toString();
        String subject = "kyc-reverification-" + unique;
        String applicantId = "sumsub-" + unique;

        UserEntity user = users.saveAndFlush(
                new UserEntity(
                        subject,
                        unique + "@example.com",
                        null
                )
        );

        KycStatusEntity kyc =
                new KycStatusEntity(user.getId());

        kyc.startVerification(
                "SUMSUB",
                applicantId
        );

        kycStatuses.saveAndFlush(kyc);

        return new Fixture(
                user.getId(),
                subject,
                applicantId
        );
    }

    private SumsubDecisionService.Outcome review(
            Fixture fixture,
            Decision decision,
            String eventTime
    ) {
        return decisions.applyVerifiedReview(
                new SumsubReviewedEvent(
                        fixture.userId(),
                        fixture.applicantId(),
                        "id-and-liveness",
                        decision,
                        Instant.parse(eventTime)
                )
        );
    }

    private UserEntity account(Fixture fixture) {
        return users.findById(fixture.userId())
                .orElseThrow();
    }

    private KycStatusEntity kyc(Fixture fixture) {
        return kycStatuses.findByUserId(
                fixture.userId()
        ).orElseThrow();
    }


    @Test
    void approvalResubmissionAndReapprovalRestoresAccess() {

        Fixture fixture = createCustomer();

        Integer migrationCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM flyway_schema_history
                        WHERE version = '13'
                          AND success = true
                        """,
                        Integer.class
                );

        assertEquals(1, migrationCount);

        /*
         * STEP 1 — Initial verification approved.
         *
         * Customer becomes ACTIVE.
         * Wallet provisioning must be triggered once.
         */
        assertEquals(
                SumsubDecisionService.Outcome.APPLIED,
                review(
                        fixture,
                        Decision.APPROVED,
                        "2026-11-04T12:00:00Z"
                )
        );

        assertEquals(
                KycStatus.APPROVED,
                kyc(fixture).getStatus()
        );

        assertEquals(
                AccountStatus.ACTIVE,
                account(fixture).getAccountStatus()
        );

        assertNull(
                account(fixture).getSuspensionReason()
        );

        assertTrue(
                eligibility.isEligible(fixture.userId())
        );

        /*
         * Initial approval must initiate wallet
         * provisioning exactly once.
         */
        verify(
                walletProvisioningService,
                timeout(5000).times(1)
        ).provisionCustomerWallets(fixture.userId());

        /*
         * STEP 2 — Sumsub requires resubmission.
         *
         * Account becomes SUSPENDED specifically
         * for KYC reverification.
         *
         * Financial operations must remain blocked.
         */
        assertEquals(
                SumsubDecisionService.Outcome.APPLIED,
                review(
                        fixture,
                        Decision.RESUBMISSION_REQUIRED,
                        "2026-11-04T12:01:00Z"
                )
        );

        assertEquals(
                KycStatus.RESUBMISSION_REQUIRED,
                kyc(fixture).getStatus()
        );

        assertEquals(
                AccountStatus.SUSPENDED,
                account(fixture).getAccountStatus()
        );

        assertEquals(
                AccountSuspensionReason.KYC_REVERIFICATION,
                account(fixture).getSuspensionReason()
        );

        assertFalse(
                eligibility.isEligible(fixture.userId())
        );

        /*
         * Resubmission must not trigger
         * wallet provisioning.
         */
        verify(
                walletProvisioningService,
                times(1)
        ).provisionCustomerWallets(fixture.userId());

        /*
         * STEP 3 — Customer obtains a new SDK token.
         *
         * KYC reverification is allowed even though
         * financial account access remains suspended.
         */
        when(
                provider.createVerificationSession(
                        new KycProvider
                                .CreateVerificationSessionCommand(
                                fixture.userId()
                        )
                )
        ).thenReturn(
                new KycProvider.VerificationSession(
                        "test-renewed-token",
                        600
                )
        );

        var session = sdkTokens.createSession(
                fixture.subject()
        );

        assertEquals(
                "test-renewed-token",
                session.token()
        );

        assertEquals(
                600,
                session.expiresInSeconds()
        );

        /*
         * SDK-token issuance must not reactivate
         * the account or provision wallets.
         */
        assertEquals(
                AccountStatus.SUSPENDED,
                account(fixture).getAccountStatus()
        );

        assertFalse(
                eligibility.isEligible(fixture.userId())
        );

        verify(
                walletProvisioningService,
                times(1)
        ).provisionCustomerWallets(fixture.userId());

        /*
         * STEP 4 — Sumsub approves reverification.
         *
         * Customer becomes ACTIVE again.
         * The suspension reason is cleared.
         */
        assertEquals(
                SumsubDecisionService.Outcome.APPLIED,
                review(
                        fixture,
                        Decision.APPROVED,
                        "2026-11-04T12:02:00Z"
                )
        );

        assertEquals(
                KycStatus.APPROVED,
                kyc(fixture).getStatus()
        );

        assertEquals(
                AccountStatus.ACTIVE,
                account(fixture).getAccountStatus()
        );

        assertNull(
                account(fixture).getSuspensionReason()
        );

        assertTrue(
                eligibility.isEligible(fixture.userId())
        );

        /*
         * STEP 5 — No duplicate wallet provisioning.
         *
         * Initial approval already initiated
         * provisioning of the customer's wallets.
         *
         * Reverification must only restore access
         * to the existing account.
         */
        verify(
                walletProvisioningService,
                times(1)
        ).provisionCustomerWallets(fixture.userId());

        /*
         * STEP 6 — Duplicate Sumsub approval webhook.
         *
         * Must return DUPLICATE without changing
         * the account or triggering provisioning.
         */
        assertEquals(
                SumsubDecisionService.Outcome.DUPLICATE,
                review(
                        fixture,
                        Decision.APPROVED,
                        "2026-11-04T12:02:00Z"
                )
        );

        assertEquals(
                AccountStatus.ACTIVE,
                account(fixture).getAccountStatus()
        );

        assertEquals(
                KycStatus.APPROVED,
                kyc(fixture).getStatus()
        );

        assertNull(
                account(fixture).getSuspensionReason()
        );

        /*
         * Still exactly one provisioning call
         * after the duplicate webhook.
         */
        verify(
                walletProvisioningService,
                times(1)
        ).provisionCustomerWallets(fixture.userId());
    }

    @Test
    void administrativeSuspensionCannotBeOverridden() {

        Fixture fixture = createCustomer();

        review(
                fixture,
                Decision.APPROVED,
                "2026-11-04T13:00:00Z"
        );

        review(
                fixture,
                Decision.RESUBMISSION_REQUIRED,
                "2026-11-04T13:01:00Z"
        );

        /*
         * A trusted administrative action
         * takes precedence over KYC reverification.
         */
        accounts.suspendAdministratively(
                fixture.userId()
        );

        assertEquals(
                AccountSuspensionReason.ADMINISTRATIVE,
                account(fixture).getSuspensionReason()
        );

        /*
         * A later verified Sumsub approval
         * must not restore the account.
         */
        review(
                fixture,
                Decision.APPROVED,
                "2026-11-04T13:02:00Z"
        );

        assertEquals(
                AccountStatus.SUSPENDED,
                account(fixture).getAccountStatus()
        );

        assertEquals(
                AccountSuspensionReason.ADMINISTRATIVE,
                account(fixture).getSuspensionReason()
        );

        assertFalse(
                eligibility.isEligible(fixture.userId())
        );

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> sdkTokens.createSession(
                                fixture.subject()
                        )
                );

        assertEquals(
                HttpStatus.FORBIDDEN,
                exception.getStatusCode()
        );
    }

    @Test
    void finalRejectionCannotReactivateCustomer() {

        Fixture fixture = createCustomer();

        review(
                fixture,
                Decision.APPROVED,
                "2026-11-04T14:00:00Z"
        );

        review(
                fixture,
                Decision.RESUBMISSION_REQUIRED,
                "2026-11-04T14:01:00Z"
        );

        review(
                fixture,
                Decision.FINAL_REJECTION,
                "2026-11-04T14:02:00Z"
        );

        assertEquals(
                KycStatus.REJECTED,
                kyc(fixture).getStatus()
        );

        assertEquals(
                AccountStatus.SUSPENDED,
                account(fixture).getAccountStatus()
        );

        assertEquals(
                AccountSuspensionReason.KYC_FINAL_REJECTION,
                account(fixture).getSuspensionReason()
        );

        assertFalse(
                eligibility.isEligible(fixture.userId())
        );

        /*
         * A subsequent approval cannot
         * override the final-rejection restriction.
         */
        review(
                fixture,
                Decision.APPROVED,
                "2026-11-04T14:03:00Z"
        );

        assertEquals(
                AccountStatus.SUSPENDED,
                account(fixture).getAccountStatus()
        );

        assertEquals(
                AccountSuspensionReason.KYC_FINAL_REJECTION,
                account(fixture).getSuspensionReason()
        );

        assertFalse(
                eligibility.isEligible(fixture.userId())
        );

        assertThrows(
                ResponseStatusException.class,
                () -> sdkTokens.createSession(
                        fixture.subject()
                )
        );
    }

    @Test
    void staleApprovalCannotUndoResubmission() {

        Fixture fixture = createCustomer();

        review(
                fixture,
                Decision.APPROVED,
                "2026-11-04T15:00:00Z"
        );

        review(
                fixture,
                Decision.RESUBMISSION_REQUIRED,
                "2026-11-04T15:02:00Z"
        );

        assertEquals(
                SumsubDecisionService.Outcome.STALE,
                review(
                        fixture,
                        Decision.APPROVED,
                        "2026-11-04T15:01:00Z"
                )
        );

        assertEquals(
                AccountStatus.SUSPENDED,
                account(fixture).getAccountStatus()
        );

        assertEquals(
                AccountSuspensionReason.KYC_REVERIFICATION,
                account(fixture).getSuspensionReason()
        );

        assertEquals(
                KycStatus.RESUBMISSION_REQUIRED,
                kyc(fixture).getStatus()
        );
    }
}
