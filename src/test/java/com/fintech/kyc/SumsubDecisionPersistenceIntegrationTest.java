package com.fintech.kyc;

import com.fintech.identity.entity.AccountStatus;
import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.kyc.adapter.sumsub.SumsubReviewedEvent;
import com.fintech.kyc.adapter.sumsub.SumsubReviewedEvent.Decision;
import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.repository.KycStatusRepository;
import com.fintech.ledger.WalletProvisioningService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class SumsubDecisionPersistenceIntegrationTest {

    @Autowired
    private SumsubDecisionService decisionService;

    @Autowired
    private KycStatusRepository kycRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private FailedEventPublications failedPublications;

    // Prevent real Formance calls while preserving
    // the actual AFTER_COMMIT event listener.
    @MockitoBean(enforceOverride = true)
    private WalletProvisioningService walletProvisioningService;

    private UUID userId;
    private String applicantId;

    private final Instant baseTime =
            Instant.parse("2026-10-27T12:00:00Z");

    @BeforeEach
    void setup() {

        String unique = UUID.randomUUID().toString();

        UserEntity user = userRepository.saveAndFlush(
                new UserEntity(
                        "sumsub-decision-" + unique,
                        "sumsub-decision-" + unique + "@example.com",
                        null
                )
        );

        userId = user.getId();
        applicantId = "sandbox-applicant-" + unique;

        KycStatusEntity kyc =
                new KycStatusEntity(userId);

        kyc.startVerification(
                "SUMSUB",
                applicantId
        );

        kycRepository.saveAndFlush(kyc);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                """
                DELETE FROM event_publication
                WHERE event_type = ?
                  AND serialized_event LIKE ?
                """,
                KycApprovedEvent.class.getName(),
                "%" + userId + "%"
        );
        // Only remove records created by this test.
        if (userId != null) {

            kycRepository.findByUserId(userId)
                    .ifPresent(kycRepository::delete);

            userRepository.findById(userId)
                    .ifPresent(userRepository::delete);
        }
    }

    private SumsubReviewedEvent review(
            Decision decision,
            Instant eventTime
    ) {
        return new SumsubReviewedEvent(
                userId,
                applicantId,
                "id-and-liveness",
                decision,
                eventTime
        );
    }
    private int publicationCountForCurrentUser() {

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM event_publication
                WHERE event_type = ?
                  AND serialized_event LIKE ?
                """,
                Integer.class,
                KycApprovedEvent.class.getName(),
                "%" + userId + "%"
        );

        return count == null ? 0 : count;
    }

    private KycStatusEntity persistedKyc() {
        return kycRepository.findByUserId(userId)
                .orElseThrow();
    }

    private AccountStatus persistedAccountStatus() {
        return userRepository.findById(userId)
                .orElseThrow()
                .getAccountStatus();
    }

    @Test
    void approvalCommitsAndTriggersWalletProvisioning() {

        var outcome = decisionService.applyVerifiedReview(
                review(Decision.APPROVED, baseTime)
        );

        assertEquals(
                SumsubDecisionService.Outcome.APPLIED,
                outcome
        );

        assertEquals(
                KycStatus.APPROVED,
                persistedKyc().getStatus()
        );

        assertEquals(
                AccountStatus.ACTIVE,
                persistedAccountStatus()
        );

        assertEquals(
                baseTime,
                persistedKyc().getLastProviderEventAt()
        );

        verify(walletProvisioningService, times(1))
                .provisionCustomerWallets(userId);
    }

    @Test
    void duplicateApprovalDoesNotProvisionTwice() {

        var approval = review(
                Decision.APPROVED,
                baseTime
        );

        var first =
                decisionService.applyVerifiedReview(approval);

        var second =
                decisionService.applyVerifiedReview(approval);

        assertEquals(
                SumsubDecisionService.Outcome.APPLIED,
                first
        );

        assertEquals(
                SumsubDecisionService.Outcome.DUPLICATE,
                second
        );

        verify(walletProvisioningService, times(1))
                .provisionCustomerWallets(userId);
    }

    @Test
    void finalRejectionDoesNotActivateCustomer() {

        decisionService.applyVerifiedReview(
                review(Decision.FINAL_REJECTION, baseTime)
        );

        assertEquals(
                KycStatus.REJECTED,
                persistedKyc().getStatus()
        );

        assertEquals(
                AccountStatus.PENDING_KYC,
                persistedAccountStatus()
        );

        verifyNoInteractions(walletProvisioningService);
    }

    @Test
    void resubmissionDoesNotActivateCustomer() {

        decisionService.applyVerifiedReview(
                review(
                        Decision.RESUBMISSION_REQUIRED,
                        baseTime
                )
        );

        assertEquals(
                KycStatus.RESUBMISSION_REQUIRED,
                persistedKyc().getStatus()
        );

        assertEquals(
                AccountStatus.PENDING_KYC,
                persistedAccountStatus()
        );

        assertNull(
                persistedKyc().getDecidedAt()
        );

        verifyNoInteractions(walletProvisioningService);
    }

    @Test
    void newerFinalRejectionSuspendsApprovedCustomer() {

        decisionService.applyVerifiedReview(
                review(Decision.APPROVED, baseTime)
        );

        decisionService.applyVerifiedReview(
                review(
                        Decision.FINAL_REJECTION,
                        baseTime.plusSeconds(60)
                )
        );

        assertEquals(
                KycStatus.REJECTED,
                persistedKyc().getStatus()
        );

        assertEquals(
                AccountStatus.SUSPENDED,
                persistedAccountStatus()
        );

        // Only the original approval provisioned wallets.
        verify(walletProvisioningService, times(1))
                .provisionCustomerWallets(userId);
    }

    @Test
    void transactionRollbackPreventsActivationAndProvisioning() {
        assertEquals(
                0,
                publicationCountForCurrentUser(),
                "Rolled-back approval must not create a durable event"
        );

        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        assertThrows(
                IllegalStateException.class,
                () -> transaction.executeWithoutResult(status -> {

                    // Joins this surrounding transaction.
                    decisionService.applyVerifiedReview(
                            review(Decision.APPROVED, baseTime)
                    );

                    // No AFTER_COMMIT event should run yet.
                    verifyNoInteractions(
                            walletProvisioningService
                    );

                    throw new IllegalStateException(
                            "Intentional rollback for testing"
                    );
                })
        );

        // All database changes must have rolled back.
        assertEquals(
                KycStatus.PENDING,
                persistedKyc().getStatus()
        );

        assertEquals(
                AccountStatus.PENDING_KYC,
                persistedAccountStatus()
        );

        assertNull(
                persistedKyc().getLastProviderEventAt()
        );

        // No wallet provisioning on rollback.
        verifyNoInteractions(walletProvisioningService);
    }

    @Test
    void applicantMismatchCannotChangeDatabaseState() {

        var invalid = new SumsubReviewedEvent(
                userId,
                "wrong-applicant-id",
                "id-and-liveness",
                Decision.APPROVED,
                baseTime
        );

        assertThrows(
                IllegalStateException.class,
                () -> decisionService.applyVerifiedReview(
                        invalid
                )
        );

        assertEquals(
                KycStatus.PENDING,
                persistedKyc().getStatus()
        );

        assertEquals(
                AccountStatus.PENDING_KYC,
                persistedAccountStatus()
        );

        verifyNoInteractions(walletProvisioningService);
    }
    @Test
    void committedApprovalIsDurablyPublished() {

        decisionService.applyVerifiedReview(
                review(Decision.APPROVED, baseTime)
        );

        assertEquals(
                KycStatus.APPROVED,
                persistedKyc().getStatus()
        );

        assertEquals(
                1,
                publicationCountForCurrentUser()
        );

        verify(walletProvisioningService, times(1))
                .provisionCustomerWallets(userId);
    }
    @Test
    void failedWalletProvisioningRemainsRecoverable() {

        // Simulate Formance being unavailable.
        doThrow(new IllegalStateException(
                "Simulated Formance outage"
        ))
                .when(walletProvisioningService)
                .provisionCustomerWallets(userId);

        // The KYC transaction should still commit.
        var outcome = decisionService.applyVerifiedReview(
                review(Decision.APPROVED, baseTime)
        );

        assertEquals(
                SumsubDecisionService.Outcome.APPLIED,
                outcome
        );

        // KYC and account activation remain committed.
        assertEquals(
                KycStatus.APPROVED,
                persistedKyc().getStatus()
        );

        assertEquals(
                AccountStatus.ACTIVE,
                persistedAccountStatus()
        );

        // The listener was invoked, but failed.
        verify(walletProvisioningService, times(1))
                .provisionCustomerWallets(userId);

        // The failure must remain durably recorded.
        Integer failedCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM event_publication
                WHERE event_type = ?
                  AND serialized_event LIKE ?
                  AND status = 'FAILED'
                  AND completion_date IS NULL
                """,
                Integer.class,
                KycApprovedEvent.class.getName(),
                "%" + userId + "%"
        );

        assertEquals(
                1,
                failedCount,
                "Failed wallet provisioning must remain retryable"
        );
    }

    @Test
    void failedWalletProvisioningCanBeRetriedSuccessfully()
            throws InterruptedException {

        // First attempt: Formance is unavailable.
        // Second attempt: Formance recovers.
        doThrow(new IllegalStateException(
                "Simulated Formance outage"
        ))
                .doNothing()
                .when(walletProvisioningService)
                .provisionCustomerWallets(userId);

        // 1. Approve the customer.
        var outcome = decisionService.applyVerifiedReview(
                review(Decision.APPROVED, baseTime)
        );

        assertEquals(
                SumsubDecisionService.Outcome.APPLIED,
                outcome
        );

        assertEquals(
                KycStatus.APPROVED,
                persistedKyc().getStatus()
        );

        assertEquals(
                AccountStatus.ACTIVE,
                persistedAccountStatus()
        );

        // 2. Verify the first provisioning attempt failed.
        Integer failedBefore = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM event_publication
                WHERE event_type = ?
                  AND serialized_event LIKE ?
                  AND status = 'FAILED'
                  AND completion_date IS NULL
                """,
                Integer.class,
                KycApprovedEvent.class.getName(),
                "%" + userId + "%"
        );

        assertEquals(1, failedBefore);

        verify(walletProvisioningService, times(1))
                .provisionCustomerWallets(userId);

        // 3. Retry only this customer's approval event.
        // Do not replay another customer's failed events.
        var options = ResubmissionOptions.defaults()
                .withBatchSize(10)
                .withMaxInFlight(2)
                .withFilter(publication -> {

                    if (!(publication.getEvent()
                            instanceof KycApprovedEvent event)) {
                        return false;
                    }

                    if (!event.userId().equals(userId)) {
                        return false;
                    }

                    // Only retry while KYC is still approved
                    // and the account remains active.
                    boolean approved = kycRepository
                            .findByUserId(userId)
                            .map(kyc ->
                                    kyc.getStatus() == KycStatus.APPROVED
                            )
                            .orElse(false);

                    boolean active = userRepository
                            .findById(userId)
                            .map(user ->
                                    user.getAccountStatus()
                                            == AccountStatus.ACTIVE
                            )
                            .orElse(false);

                    System.out.printf(
                            "REPLAY_FILTER: matchingUser=%s, approved=%s, active=%s%n",
                            event.userId().equals(userId),
                            approved,
                            active
                    );
                    return approved && active;
                });

        failedPublications.resubmit(options);

        // 4. Wait for publication completion.
        // Resubmission may execute asynchronously.
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(10);

        int completed = 0;

        while (System.nanoTime() < deadline) {

            Integer count = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM event_publication
                    WHERE event_type = ?
                      AND serialized_event LIKE ?
                      AND status = 'COMPLETED'
                      AND completion_date IS NOT NULL
                    """,
                    Integer.class,
                    KycApprovedEvent.class.getName(),
                    "%" + userId + "%"
            );

            completed = count == null ? 0 : count;

            if (completed == 1) {
                break;
            }

            Thread.sleep(100);
        }

        var publicationRows = jdbcTemplate.queryForList(
                """
                SELECT status, publication_date, completion_date
                FROM event_publication
                WHERE event_type = ?
                  AND serialized_event LIKE ?
                """,
                KycApprovedEvent.class.getName(),
                "%" + userId + "%"
        );

        System.out.println(
                "REPLAY_STATUS: " + publicationRows
        );

        long provisioningCalls = mockingDetails(walletProvisioningService)
                .getInvocations()
                .stream()
                .filter(invocation ->
                        invocation.getMethod()
                                .getName()
                                .equals("provisionCustomerWallets")
                )
                .count();

        System.out.println(
                "REPLAY_PROVISIONING_CALLS: " + provisioningCalls
        );
        // 5. Publication must be completed.
        assertEquals(
                1,
                completed,
                "Retried publication should become COMPLETED"
        );

        // 6. The wallet service was called exactly twice:
        // once failed, once successful.
        verify(walletProvisioningService, times(2))
                .provisionCustomerWallets(userId);

        // 7. The retry did not alter the KYC decision.
        assertEquals(
                KycStatus.APPROVED,
                persistedKyc().getStatus()
        );

        assertEquals(
                AccountStatus.ACTIVE,
                persistedAccountStatus()
        );
    }

    @Test
    void revokedApprovalCannotProvisionWalletsOnRetry()
            throws InterruptedException {

        // STEP 1: Simulate a Formance outage.
        doThrow(new IllegalStateException(
                "Simulated Formance outage"
        ))
                .when(walletProvisioningService)
                .provisionCustomerWallets(userId);

        // STEP 2: Sumsub initially approves the customer.
        var approval = decisionService.applyVerifiedReview(
                review(Decision.APPROVED, baseTime)
        );

        assertEquals(
                SumsubDecisionService.Outcome.APPLIED,
                approval
        );

        assertEquals(
                KycStatus.APPROVED,
                persistedKyc().getStatus()
        );

        assertEquals(
                AccountStatus.ACTIVE,
                persistedAccountStatus()
        );

        // Provisioning was attempted but failed.
        verify(walletProvisioningService, times(1))
                .provisionCustomerWallets(userId);

        Integer failedBefore = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM event_publication
                WHERE event_type = ?
                  AND serialized_event LIKE ?
                  AND status = 'FAILED'
                  AND completion_date IS NULL
                """,
                Integer.class,
                KycApprovedEvent.class.getName(),
                "%" + userId + "%"
        );

        assertEquals(1, failedBefore);

        // STEP 3: Sumsub subsequently rejects the customer.
        decisionService.applyVerifiedReview(
                review(
                        Decision.FINAL_REJECTION,
                        baseTime.plusSeconds(60)
                )
        );

        assertEquals(
                KycStatus.REJECTED,
                persistedKyc().getStatus()
        );

        assertEquals(
                AccountStatus.SUSPENDED,
                persistedAccountStatus()
        );

        // STEP 4: Retry the old approval publication.
        // IMPORTANT: Filter only by user ID.
        // We want to exercise the REAL production listener's
        // eligibility check, not bypass it with the test filter.
        var options = ResubmissionOptions.defaults()
                .withBatchSize(10)
                .withMaxInFlight(2)
                .withFilter(publication -> {

                    if (!(publication.getEvent()
                            instanceof KycApprovedEvent event)) {
                        return false;
                    }

                    return event.userId().equals(userId);
                });

        failedPublications.resubmit(options);

        // STEP 5: Wait for the obsolete event to complete.
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(10);

        int completed = 0;

        while (System.nanoTime() < deadline) {

            Integer count = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM event_publication
                    WHERE event_type = ?
                      AND serialized_event LIKE ?
                      AND status = 'COMPLETED'
                      AND completion_date IS NOT NULL
                    """,
                    Integer.class,
                    KycApprovedEvent.class.getName(),
                    "%" + userId + "%"
            );

            completed = count == null ? 0 : count;

            if (completed == 1) {
                break;
            }

            Thread.sleep(100);
        }

        assertEquals(
                1,
                completed,
                "Obsolete approval event should be completed"
        );

        // STEP 6: Crucial security assertion.
        // The original failed attempt is the ONLY call.
        // The retry must NOT invoke Formance again.
        verify(walletProvisioningService, times(1))
                .provisionCustomerWallets(userId);

        // STEP 7: Rejection and suspension remain intact.
        assertEquals(
                KycStatus.REJECTED,
                persistedKyc().getStatus()
        );

        assertEquals(
                AccountStatus.SUSPENDED,
                persistedAccountStatus()
        );
    }

}