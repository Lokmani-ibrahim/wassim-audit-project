package com.fintech.kyc;

import com.fintech.identity.IdentityAccountService;
import com.fintech.kyc.adapter.sumsub.SumsubReviewedEvent;
import com.fintech.kyc.adapter.sumsub.SumsubReviewedEvent.Decision;
import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.repository.KycStatusRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SumsubDecisionServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final String applicantId = "sandbox-applicant-123";

    private final Instant eventTime =
            Instant.parse("2026-10-26T20:00:00Z");

    private KycStatusRepository repository;
    private IdentityAccountService accounts;
    private ApplicationEventPublisher events;
    private SumsubDecisionService service;
    private KycStatusEntity kyc;

    @BeforeEach
    void setup() {
        repository = mock(KycStatusRepository.class);
        accounts = mock(IdentityAccountService.class);
        events = mock(ApplicationEventPublisher.class);

        kyc = new KycStatusEntity(userId);
        kyc.startVerification("SUMSUB", applicantId);

        when(repository.findByUserIdForUpdate(userId))
                .thenReturn(Optional.of(kyc));

        service = new SumsubDecisionService(
                repository, accounts, events
        );
    }

    private SumsubReviewedEvent review(Decision decision) {
        return new SumsubReviewedEvent(
                userId,
                applicantId,
                "id-and-liveness",
                decision,
                eventTime
        );
    }

    @Test
    void approvalActivatesAndPublishesEvent() {

        when(accounts.activateAfterVerifiedKyc(userId))
                .thenReturn(true);

        var outcome = service.applyVerifiedReview(
                review(Decision.APPROVED)
        );

        assertEquals(
                SumsubDecisionService.Outcome.APPLIED,
                outcome
        );
        assertEquals(KycStatus.APPROVED, kyc.getStatus());
        assertEquals(
                eventTime, kyc.getLastProviderEventAt()
        );

        verify(accounts).activateAfterVerifiedKyc(userId);
        verify(events).publishEvent(
                new KycApprovedEvent(userId)
        );
    }

    @Test
    void duplicateApprovalDoesNotPublishTwice() {

        when(accounts.activateAfterVerifiedKyc(userId))
                .thenReturn(true);

        var event = review(Decision.APPROVED);

        service.applyVerifiedReview(event);
        var outcome = service.applyVerifiedReview(event);

        assertEquals(
                SumsubDecisionService.Outcome.DUPLICATE,
                outcome
        );

        verify(events, times(1)).publishEvent(
                new KycApprovedEvent(userId)
        );
        verify(accounts, times(1))
                .activateAfterVerifiedKyc(userId);
    }

    @Test
    void finalRejectionDoesNotActivateCustomer() {

        service.applyVerifiedReview(
                review(Decision.FINAL_REJECTION)
        );

        assertEquals(KycStatus.REJECTED, kyc.getStatus());

        verify(accounts).suspendForKycFinalRejection(userId);
        verify(accounts, never())
                .activateAfterVerifiedKyc(userId);
        verifyNoInteractions(events);
    }

    @Test
    void resubmissionDoesNotApproveCustomer() {

        service.applyVerifiedReview(
                review(Decision.RESUBMISSION_REQUIRED)
        );

        assertEquals(
                KycStatus.RESUBMISSION_REQUIRED,
                kyc.getStatus()
        );

        verify(accounts).suspendForKycReverificationIfActive(userId);
        verifyNoInteractions(events);
    }

    @Test
    void rejectsWrongApplicantId() {

        var wrong = new SumsubReviewedEvent(
                userId,
                "wrong-applicant",
                "id-and-liveness",
                Decision.APPROVED,
                eventTime
        );

        assertThrows(
                IllegalStateException.class,
                () -> service.applyVerifiedReview(wrong)
        );

        verifyNoInteractions(accounts, events);
    }

    @Test
    void ignoresOlderEvents() {

        kyc.recordProviderEventAt(
                eventTime.plusSeconds(60)
        );

        var outcome = service.applyVerifiedReview(
                review(Decision.APPROVED)
        );

        assertEquals(
                SumsubDecisionService.Outcome.STALE,
                outcome
        );

        verifyNoInteractions(accounts, events);
    }

    @Test
    void refusesConflictingDecisionsAtSameTimestamp() {

        kyc.reject();
        kyc.recordProviderEventAt(eventTime);

        assertThrows(
                IllegalStateException.class,
                () -> service.applyVerifiedReview(
                        review(Decision.APPROVED)
                )
        );

        verifyNoInteractions(accounts, events);
    }

    @Test
    void doesNotPublishApprovalWhenActivationIsBlocked() {

        when(accounts.activateAfterVerifiedKyc(userId))
                .thenReturn(false);

        service.applyVerifiedReview(
                review(Decision.APPROVED)
        );

        assertEquals(KycStatus.APPROVED, kyc.getStatus());

        // Restricted or previously activated accounts
        // must not receive a new provisioning event.
        verifyNoInteractions(events);
    }
}