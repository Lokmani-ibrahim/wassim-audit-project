
package com.fintech.ledger;

import com.fintech.identity.entity.AccountStatus;
import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.kyc.KycApprovedEvent;
import com.fintech.kyc.SumsubDecisionService;
import com.fintech.kyc.adapter.sumsub.SumsubReviewedEvent;
import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.repository.KycStatusRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@EnabledIfEnvironmentVariable(
        named = "RUN_WALLET_RECOVERY_CONTAINER_IT",
        matches = "true"
)
@ActiveProfiles("dev")
@SpringBootTest(properties = {
        "neos.ledger.wallet-provisioning.recovery.enabled=true",
        "neos.ledger.wallet-provisioning.recovery.initial-delay-ms=5000",
        "neos.ledger.wallet-provisioning.recovery.fixed-delay-ms=500",
        "neos.ledger.wallet-provisioning.recovery.batch-size=50",
        "neos.ledger.wallet-provisioning.recovery.max-in-flight=2",
        "neos.ledger.wallet-provisioning.recovery.min-age=PT0S",

        "spring.modulith.events.republish-outstanding-events-on-restart=false",
        "spring.flyway.enabled=true",

        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false",

        // Additional safeguard against accidental local ledger calls.
        "neos.ledger.formance.base-url=http://127.0.0.1:1"
})
@DirtiesContext(
        classMode = DirtiesContext.ClassMode.AFTER_CLASS
)
class WalletProvisioningAutomaticRecoveryIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:18-alpine")
                    .withDatabaseName("wallet_recovery_it")
                    .withUsername("recovery_test")
                    .withPassword("recovery_test_only");

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
    private SumsubDecisionService decisionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KycStatusRepository kycRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /*
     * Only the outbound Formance operation is simulated.
     *
     * The following remain real:
     * - PostgreSQL
     * - Flyway migrations
     * - Sumsub decision persistence
     * - Spring Modulith event publication registry
     * - AFTER_COMMIT listener
     * - Recovery scheduler
     */
    @MockitoBean(enforceOverride = true)
    private WalletProvisioningService walletProvisioningService;

    @Test
    void failedProvisioningIsAutomaticallyRecovered()
            throws Exception {

        AtomicBoolean formanceAvailable =
                new AtomicBoolean(false);

        String unique = UUID.randomUUID().toString();

        UserEntity user = userRepository.saveAndFlush(
                new UserEntity(
                        "automatic-recovery-" + unique,
                        "automatic-recovery-" + unique
                                + "@example.com",
                        null
                )
        );

        UUID userId = user.getId();
        String applicantId = "sandbox-applicant-" + unique;

        KycStatusEntity kyc = new KycStatusEntity(userId);

        kyc.startVerification(
                "SUMSUB",
                applicantId
        );

        kycRepository.saveAndFlush(kyc);

        /*
         * Formance initially fails.
         * After recovery, the same call succeeds.
         */
        doAnswer(invocation -> {

            if (!formanceAvailable.get()) {
                throw new IllegalStateException(
                        "Simulated Formance outage"
                );
            }

            return null;

        }).when(walletProvisioningService)
                .provisionCustomerWallets(userId);

        /*
         * STEP 1: Approve the customer.
         */
        var outcome = decisionService.applyVerifiedReview(
                new SumsubReviewedEvent(
                        userId,
                        applicantId,
                        "id-and-liveness",
                        SumsubReviewedEvent.Decision.APPROVED,
                        Instant.parse("2026-11-04T12:00:00Z")
                )
        );

        assertEquals(
                SumsubDecisionService.Outcome.APPLIED,
                outcome
        );

        assertEquals(
                KycStatus.APPROVED,
                kycRepository.findByUserId(userId)
                        .orElseThrow()
                        .getStatus()
        );

        assertEquals(
                AccountStatus.ACTIVE,
                userRepository.findById(userId)
                        .orElseThrow()
                        .getAccountStatus()
        );

        /*
         * STEP 2: Confirm the failed publication exists.
         */
        assertEquals(
                1,
                publicationCount(userId, "FAILED")
        );

        verify(
                walletProvisioningService,
                times(1)
        ).provisionCustomerWallets(userId);

        /*
         * STEP 3: Simulate Formance becoming available.
         *
         * Do not call FailedEventPublications.resubmit().
         * The Spring scheduler must perform recovery.
         */
        formanceAvailable.set(true);

        /*
         * STEP 4: Wait for automatic replay and completion.
         */
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(30);

        boolean completed = false;

        while (System.nanoTime() < deadline) {

            if (publicationCount(userId, "COMPLETED") == 1) {
                completed = true;
                break;
            }

            Thread.sleep(100);
        }

        assertTrue(
                completed,
                "Scheduler did not automatically recover "
                        + "the failed KYC wallet publication"
        );

        /*
         * STEP 5: Confirm one failed attempt followed
         * by one successful recovery invocation.
         */
        verify(
                walletProvisioningService,
                times(2)
        ).provisionCustomerWallets(userId);

        /*
         * STEP 6: Recovery must not alter KYC or
         * account activation state.
         */
        assertEquals(
                KycStatus.APPROVED,
                kycRepository.findByUserId(userId)
                        .orElseThrow()
                        .getStatus()
        );

        assertEquals(
                AccountStatus.ACTIVE,
                userRepository.findById(userId)
                        .orElseThrow()
                        .getAccountStatus()
        );

        assertEquals(
                0,
                publicationCount(userId, "FAILED")
        );
    }

    private int publicationCount(
            UUID userId,
            String status
    ) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM event_publication
                WHERE event_type = ?
                  AND serialized_event LIKE ?
                  AND status = ?
                """,
                Integer.class,
                KycApprovedEvent.class.getName(),
                "%" + userId + "%",
                status
        );

        return count == null ? 0 : count;
    }
}
