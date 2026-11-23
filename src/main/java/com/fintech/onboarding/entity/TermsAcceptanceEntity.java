package com.fintech.onboarding.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "terms_acceptances",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_terms_acceptances_user_version",
                        columnNames = {
                                "user_id",
                                "terms_version"
                        }
                )
        }
)
public class TermsAcceptanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(
            name = "user_id",
            nullable = false
    )
    private UUID userId;

    @Column(
            name = "terms_version",
            nullable = false,
            length = 50
    )
    private String termsVersion;

    @CreationTimestamp
    @Column(
            name = "accepted_at",
            nullable = false,
            updatable = false
    )
    private Instant acceptedAt;

    protected TermsAcceptanceEntity() {
        // Required by JPA/Hibernate
    }

    public TermsAcceptanceEntity(
            UUID userId,
            String termsVersion
    ) {
        this.userId = userId;
        this.termsVersion = termsVersion;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTermsVersion() {
        return termsVersion;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }
}