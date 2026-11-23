
package com.fintech.kyc;

import com.fintech.identity.entity.AccountStatus;
import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.kyc.adapter.sumsub.SumsubReviewedEvent;
import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.repository.KycStatusRepository;
import com.fintech.ledger.InternalTransferService;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerProvider;
import com.fintech.notifications.FinancialTransactionNotificationService;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
@Transactional
@Rollback
class CustomerDebitAuthorizationPersistenceIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KycStatusRepository kycRepository;

    @Autowired
    private CustomerDebitAuthorization authorization;

    @Autowired
    private SumsubDecisionService decisionService;

    @Autowired
    private EntityManager entityManager;

    private UUID userId;
    private String applicantId;

    @BeforeEach
    void setup() {

        String unique = UUID.randomUUID().toString();

        UserEntity user = userRepository.saveAndFlush(
                new UserEntity(
                        "sumsub-authorization-" + unique,
                        "sumsub-authorization-" + unique
                                + "@example.com",
                        null
                )
        );

        userId = user.getId();
        applicantId = "sandbox-applicant-" + unique;

        KycStatusEntity kyc =
                new KycStatusEntity(userId);

        kyc.startVerification("SUMSUB", applicantId);

        kycRepository.saveAndFlush(kyc);

        entityManager.clear();
    }

    private void approveLocally() {

        KycStatusEntity kyc =
                kycRepository.findByUserId(userId)
                        .orElseThrow();

        kyc.approve();
        kycRepository.saveAndFlush(kyc);

        UserEntity user =
                userRepository.findById(userId)
                        .orElseThrow();

        user.activate();
        userRepository.saveAndFlush(user);

        entityManager.clear();
    }

    private void assertOutgoingTransferDenied() {

        LedgerProvider ledger =
                mock(LedgerProvider.class);

        FinancialTransactionNotificationService notifications =
                mock(FinancialTransactionNotificationService.class);

        InternalTransferService transfers =
                new InternalTransferService(
                        ledger,
                        notifications,
                        authorization
                );

        assertThrows(
                AccessDeniedException.class,
                () -> transfers.transfer(
                        userId,
                        UUID.randomUUID(),
                        LedgerCurrency.EUR,
                        5_000L,
                        "blocked-" + UUID.randomUUID()
                )
        );

        // No financial operations or notifications.
        verifyNoInteractions(ledger, notifications);
    }

    @Test
    void approvedActiveCustomerCanInitiateDebit() {

        approveLocally();

        assertDoesNotThrow(
                () -> authorization.requireEligible(userId)
        );
    }

    @Test
    void pendingCustomerCannotTransfer() {

        assertEquals(
                KycStatus.PENDING,
                kycRepository.findByUserId(userId)
                        .orElseThrow()
                        .getStatus()
        );

        assertOutgoingTransferDenied();
    }

    @Test
    void suspendedCustomerCannotTransfer() {

        approveLocally();

        UserEntity user =
                userRepository.findById(userId)
                        .orElseThrow();

        user.suspend();
        userRepository.saveAndFlush(user);

        entityManager.clear();

        assertOutgoingTransferDenied();
    }

    @Test
    void rejectedKycCannotTransferEvenIfAccountIsActive() {

        approveLocally();

        KycStatusEntity kyc =
                kycRepository.findByUserId(userId)
                        .orElseThrow();

        kyc.reject();
        kycRepository.saveAndFlush(kyc);

        entityManager.clear();

        // Even though AccountStatus is ACTIVE,
        // rejected KYC must block the debit.
        assertOutgoingTransferDenied();
    }

    @Test
    void sumsubRejectionRevokesOutgoingTransferPermission() {

        // Start with an approved, active customer.
        approveLocally();

        assertDoesNotThrow(
                () -> authorization.requireEligible(userId)
        );

        // Simulate a newer validated Sumsub decision.
        SumsubReviewedEvent rejection =
                new SumsubReviewedEvent(
                        userId,
                        applicantId,
                        "id-and-liveness",
                        SumsubReviewedEvent.Decision.FINAL_REJECTION,
                        Instant.parse("2026-10-27T17:00:00Z")
                );

        var outcome =
                decisionService.applyVerifiedReview(rejection);

        assertEquals(
                SumsubDecisionService.Outcome.APPLIED,
                outcome
        );

        entityManager.flush();
        entityManager.clear();

        // KYC must be rejected in PostgreSQL.
        assertEquals(
                KycStatus.REJECTED,
                kycRepository.findByUserId(userId)
                        .orElseThrow()
                        .getStatus()
        );

        // Account must also be suspended.
        assertEquals(
                AccountStatus.SUSPENDED,
                userRepository.findById(userId)
                        .orElseThrow()
                        .getAccountStatus()
        );

        // Most important: no Formance transfer.
        assertOutgoingTransferDenied();
    }
}
