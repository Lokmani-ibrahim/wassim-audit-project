package com.fintech.kyc.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kyc_status")
public class KycStatusEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(
            name = "user_id",
            nullable = false,
            unique = true
    )
    private UUID userId;

    @Column(
            name = "provider",
            nullable = false,
            length = 50
    )
    private String provider = "SUMSUB";

    @Column(
            name = "provider_applicant_id",
            length = 255
    )
    private String providerApplicantId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 32
    )
    private KycStatus status = KycStatus.NOT_STARTED;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "last_provider_event_at")
    private Instant lastProviderEventAt;
    @Column(name = "ever_approved", nullable = false)
    private boolean everApproved = false;

    @Column(
            name = "initial_final_rejection_locked",
            nullable = false
    )
    private boolean initialFinalRejectionLocked = false;

    @Column(name = "manual_review_required", nullable = false)
    private boolean manualReviewRequired = false;

    @Column(name = "manual_review_trigger", length = 32)
    private String manualReviewTrigger;

    @Column(name = "manual_review_trigger_at")
    private Instant manualReviewTriggerAt;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @UpdateTimestamp
    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt;

    protected KycStatusEntity() {
        // Required by JPA/Hibernate
    }

    public KycStatusEntity(UUID userId) {
        this.userId = userId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderApplicantId() {
        return providerApplicantId;
    }

    public KycStatus getStatus() {
        return status;
    }
    public Instant getLastProviderEventAt() {
        return lastProviderEventAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void start(String providerApplicantId) {
        this.providerApplicantId = providerApplicantId;
        this.status = KycStatus.PENDING;
        this.startedAt = Instant.now();
        this.decidedAt = null;
    }

    public void markInReview() {
        this.status = KycStatus.IN_REVIEW;
    }

    public void approve() {

        if (initialFinalRejectionLocked) {
            throw new IllegalStateException(
                    "Initial final rejection requires "
                            + "administrative review"
            );
        }

        this.status = KycStatus.APPROVED;
        this.decidedAt = Instant.now();

        // Persist this fact even if a later review rejects
        // the customer and changes the current status.
        this.everApproved = true;
    }

    public void reject() {
        this.status = KycStatus.REJECTED;
        this.decidedAt = Instant.now();
    }
    public void startVerification(
            String provider,
            String providerApplicantId
    ) {
        if (initialFinalRejectionLocked) {
            throw new IllegalStateException(
                    "Initial final rejection cannot "
                            + "be changed to resubmission"
            );
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }

        if (providerApplicantId == null || providerApplicantId.isBlank()) {
            throw new IllegalArgumentException(
                    "providerApplicantId is required"
            );
        }

        this.provider = provider;
        this.providerApplicantId = providerApplicantId;
        this.status = KycStatus.PENDING;

        if (this.startedAt == null) {
            this.startedAt = Instant.now();
        }
    }
    public void markResubmissionRequired() {
        if (initialFinalRejectionLocked) {
            throw new IllegalStateException(
                    "Initial final rejection cannot "
                            + "be changed to resubmission"
            );
        }
        this.status = KycStatus.RESUBMISSION_REQUIRED;
        this.decidedAt = null;
    }

    public void recordProviderEventAt(Instant eventTime) {
        this.lastProviderEventAt = java.util.Objects.requireNonNull(
                eventTime,
                "eventTime is required"
        );
    }
    public boolean hasEverBeenApproved() {
        return everApproved;
    }

    public boolean isInitialFinalRejectionLocked() {
        return initialFinalRejectionLocked;
    }

    public boolean isManualReviewRequired() {
        return manualReviewRequired;
    }

    public String getManualReviewTrigger() {
        return manualReviewTrigger;
    }

    public Instant getManualReviewTriggerAt() {
        return manualReviewTriggerAt;
    }

    public void lockInitialFinalRejection() {
        if (everApproved) {
            throw new IllegalStateException(
                    "Cannot apply initial rejection lock "
                            + "to a previously approved customer"
            );
        }

        initialFinalRejectionLocked = true;
    }

    public void requireManualReviewFor(String decision) {
        if (!initialFinalRejectionLocked) {
            throw new IllegalStateException(
                    "No initial final-rejection lock exists"
            );
        }

        if (decision == null || decision.isBlank()) {
            throw new IllegalArgumentException(
                    "Decision is required"
            );
        }

        manualReviewRequired = true;
        manualReviewTrigger = decision;
        manualReviewTriggerAt = Instant.now();
    }

}