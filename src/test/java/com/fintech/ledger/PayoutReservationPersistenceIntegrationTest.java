
package com.fintech.ledger;

import com.fintech.identity.entity.AccountStatus;
import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;

import com.fintech.kyc.SumsubDecisionService;
import com.fintech.kyc.adapter.sumsub.SumsubReviewedEvent;
import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.repository.KycStatusRepository;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.port.LedgerProvider;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
@Transactional
@Rollback
class PayoutReservationPersistenceIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KycStatusRepository kycRepository;

    @Autowired
    private PayoutReservationService payoutService;

    @Autowired
    private SumsubDecisionService decisionService;

    @Autowired
    private EntityManager entityManager;

    // Replace the real Formance adapter.
    // PostgreSQL and authorization remain real.
    @MockitoBean(enforceOverride = true)
    private LedgerProvider ledgerProvider;

    private UUID userId;
    private String applicantId;

    @BeforeEach
    void setup() {

        String unique = UUID.randomUUID().toString();

        UserEntity user = userRepository.saveAndFlush(
                new UserEntity(
                        "payout-auth-" + unique,
                        "payout-auth-" + unique + "@example.com",
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

        entityManager.clear();
    }

    private void approveLocally() {

        KycStatusEntity kyc = kycRepository
                .findByUserId(userId)
                .orElseThrow();

        kyc.approve();

        kycRepository.saveAndFlush(kyc);

        UserEntity user = userRepository
                .findById(userId)
                .orElseThrow();

        user.activate();

        userRepository.saveAndFlush(user);

        entityManager.clear();
    }

    private void assertPayoutDenied() {

        assertThrows(
                AccessDeniedException.class,
                () -> payoutService.reserve(
                        userId,
                        LedgerCurrency.EUR,
                        5_000L,
                        "blocked-" + UUID.randomUUID()
                )
        );

        // Critical: no payout funds were reserved.
        verify(ledgerProvider, never())
                .reservePayout(
                        any(UUID.class),
                        any(LedgerCurrency.class),
                        anyLong(),
                        anyString()
                );
    }

    @Test
    void approvedActiveCustomerCanReservePayout() {

        approveLocally();

        LedgerOperationResult expected =
                new LedgerOperationResult(
                        100L,
                        "payout_reserve:test_001"
                );

        when(ledgerProvider.reservePayout(
                userId,
                LedgerCurrency.EUR,
                5_000L,
                "test_001"
        )).thenReturn(expected);

        LedgerOperationResult result =
                payoutService.reserve(
                        userId,
                        LedgerCurrency.EUR,
                        5_000L,
                        "test_001"
                );

        assertEquals(expected, result);

        verify(ledgerProvider, times(1))
                .reservePayout(
                        userId,
                        LedgerCurrency.EUR,
                        5_000L,
                        "test_001"
                );
    }

    @Test
    void pendingKycCannotReservePayout() {

        assertEquals(
                KycStatus.PENDING,
                kycRepository.findByUserId(userId)
                        .orElseThrow()
                        .getStatus()
        );

        assertPayoutDenied();
    }

    @Test
    void suspendedCustomerCannotReservePayout() {

        approveLocally();

        UserEntity user = userRepository
                .findById(userId)
                .orElseThrow();

        user.suspend();

        userRepository.saveAndFlush(user);
        entityManager.clear();

        assertEquals(
                AccountStatus.SUSPENDED,
                userRepository.findById(userId)
                        .orElseThrow()
                        .getAccountStatus()
        );

        assertPayoutDenied();
    }

    @Test
    void rejectedKycCannotReserveEvenIfAccountIsActive() {

        approveLocally();

        KycStatusEntity kyc = kycRepository
                .findByUserId(userId)
                .orElseThrow();

        kyc.reject();

        kycRepository.saveAndFlush(kyc);
        entityManager.clear();

        // Account is still ACTIVE, but KYC is rejected.
        assertEquals(
                AccountStatus.ACTIVE,
                userRepository.findById(userId)
                        .orElseThrow()
                        .getAccountStatus()
        );

        assertEquals(
                KycStatus.REJECTED,
                kycRepository.findByUserId(userId)
                        .orElseThrow()
                        .getStatus()
        );

        assertPayoutDenied();
    }

    @Test
    void sumsubRejectionRevokesPayoutPermission() {

        // STEP 1: Customer has approved KYC.
        approveLocally();

        assertEquals(
                AccountStatus.ACTIVE,
                userRepository.findById(userId)
                        .orElseThrow()
                        .getAccountStatus()
        );

        // STEP 2: A newer Sumsub FINAL rejection arrives.
        SumsubReviewedEvent rejection =
                new SumsubReviewedEvent(
                        userId,
                        applicantId,
                        "id-and-liveness",
                        SumsubReviewedEvent.Decision.FINAL_REJECTION,
                        Instant.parse("2026-10-30T18:00:00Z")
                );

        var outcome =
                decisionService.applyVerifiedReview(rejection);

        assertEquals(
                SumsubDecisionService.Outcome.APPLIED,
                outcome
        );

        entityManager.flush();
        entityManager.clear();

        // STEP 3: KYC must be rejected.
        assertEquals(
                KycStatus.REJECTED,
                kycRepository.findByUserId(userId)
                        .orElseThrow()
                        .getStatus()
        );

        // STEP 4: Account must be suspended.
        assertEquals(
                AccountStatus.SUSPENDED,
                userRepository.findById(userId)
                        .orElseThrow()
                        .getAccountStatus()
        );

        // STEP 5: No new payout reservation is allowed.
        assertPayoutDenied();
    }
}
