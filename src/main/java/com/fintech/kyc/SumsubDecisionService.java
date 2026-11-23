
package com.fintech.kyc;

import com.fintech.identity.IdentityAccountService;
import com.fintech.kyc.adapter.sumsub.SumsubReviewedEvent;
import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.repository.KycStatusRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
public class SumsubDecisionService {

    public enum Outcome {
        APPLIED,
        DUPLICATE,
        STALE
    }

    private final KycStatusRepository repository;
    private final IdentityAccountService accounts;
    private final ApplicationEventPublisher events;

    public SumsubDecisionService(
            KycStatusRepository repository,
            IdentityAccountService accounts,
            ApplicationEventPublisher events
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.accounts = Objects.requireNonNull(accounts);
        this.events = Objects.requireNonNull(events);
    }

    /**
     * Applies an authenticated, verified Sumsub review.
     *
     * This method must be called only after webhook
     * signature verification and payload validation.
     *
     * KYC state, account state and event publication
     * participate in the same database transaction.
     */
    @Transactional
    public Outcome applyVerifiedReview(
            SumsubReviewedEvent event
    ) {
        Objects.requireNonNull(event, "event");

        // Lock KYC to serialize concurrent decisions.
        KycStatusEntity kyc = repository
                .findByUserIdForUpdate(event.userId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "KYC record not found"
                        )
                );

        // Reject incorrect provider or applicant.
        if (!"SUMSUB".equals(kyc.getProvider())) {
            throw new IllegalStateException(
                    "KYC provider mismatch"
            );
        }

        if (!event.applicantId().equals(
                kyc.getProviderApplicantId())) {
            throw new IllegalStateException(
                    "Sumsub applicant ID mismatch"
            );
        }

        if (kyc.getStatus() == KycStatus.NOT_STARTED) {
            throw new IllegalStateException(
                    "KYC verification has not started"
            );
        }

        KycStatus targetStatus = switch (event.decision()) {
            case APPROVED -> KycStatus.APPROVED;
            case FINAL_REJECTION -> KycStatus.REJECTED;
            case RESUBMISSION_REQUIRED ->
                    KycStatus.RESUBMISSION_REQUIRED;
        };

        // Preserve webhook ordering and idempotency.
        Instant lastEvent = kyc.getLastProviderEventAt();

        if (lastEvent != null) {

            if (event.eventTime().isBefore(lastEvent)) {
                return Outcome.STALE;
            }

            if (event.eventTime().equals(lastEvent)) {

                if (kyc.getStatus() == targetStatus) {
                    return Outcome.DUPLICATE;
                }

                if (kyc.isInitialFinalRejectionLocked()
                        && kyc.isManualReviewRequired()
                        && event.decision().name().equals(
                        kyc.getManualReviewTrigger())) {
                    return Outcome.DUPLICATE;
                }

                throw new IllegalStateException(
                        "Conflicting Sumsub decisions "
                                + "at the same timestamp"
                );
            }
        }

        /*
         * An initial FINAL rejection cannot be undone
         * automatically by subsequent Sumsub events.
         */
        if (kyc.isInitialFinalRejectionLocked()) {

            if (event.decision()
                    != SumsubReviewedEvent.Decision
                    .FINAL_REJECTION) {

                kyc.requireManualReviewFor(
                        event.decision().name()
                );
            }

            kyc.recordProviderEventAt(
                    event.eventTime()
            );

            repository.saveAndFlush(kyc);

            return Outcome.APPLIED;
        }

        /*
         * Initial final rejection before any previous
         * approval establishes a permanent review lock.
         */
        if (event.decision()
                == SumsubReviewedEvent.Decision.FINAL_REJECTION
                && !kyc.hasEverBeenApproved()) {

            kyc.lockInitialFinalRejection();
        }

        KycStatus previousStatus = kyc.getStatus();

// Capture this before calling kyc.approve().
// Reverification must not create another
// wallet-provisioning event.
        boolean previouslyApproved = kyc.hasEverBeenApproved();

        switch (event.decision()) {

            case APPROVED -> {

                /*
                 * Initial KYC:
                 * PENDING_KYC -> ACTIVE
                 *
                 * Valid reverification:
                 * SUSPENDED/KYC_REVERIFICATION -> ACTIVE
                 *
                 * Administrative, final-rejection,
                 * legacy and closed restrictions remain.
                 */
                boolean activated =
                        accounts.activateAfterVerifiedKyc(
                                event.userId()
                        );

                kyc.approve();

                /*
                 * Publish an event only when approval
                 * results in a new account activation.
                 *
                 * Existing wallet account addresses and
                 * provisioning keys remain deterministic.
                 */
                if (!previouslyApproved
                        && previousStatus != KycStatus.APPROVED
                        && activated) {

                    events.publishEvent(
                            new KycApprovedEvent(event.userId())
                    );
                }
            }

            case FINAL_REJECTION -> {

                kyc.reject();

                /*
                 * Replace KYC_REVERIFICATION with
                 * KYC_FINAL_REJECTION when appropriate.
                 *
                 * Never remove an administrative
                 * or other protected restriction.
                 */
                accounts.suspendForKycFinalRejection(
                        event.userId()
                );
            }

            case RESUBMISSION_REQUIRED -> {

                kyc.markResubmissionRequired();

                /*
                 * Previously ACTIVE customers lose
                 * financial access while completing
                 * the new Sumsub verification.
                 *
                 * Already suspended or closed accounts
                 * are not downgraded or reclassified.
                 */
                accounts.suspendForKycReverificationIfActive(
                        event.userId()
                );
            }
        }

        kyc.recordProviderEventAt(
                event.eventTime()
        );

        repository.saveAndFlush(kyc);

        return Outcome.APPLIED;
    }
}
