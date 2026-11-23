
package com.fintech.identity.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(
            name = "keycloak_subject",
            nullable = false,
            unique = true,
            length = 255
    )
    private String keycloakSubject;

    @Column(
            name = "email",
            nullable = false,
            unique = true,
            length = 320
    )
    private String email;

    @Column(
            name = "phone_number",
            length = 32
    )
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "account_type",
            nullable = false,
            length = 32
    )
    private AccountType accountType = AccountType.PERSONAL;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "account_status",
            nullable = false,
            length = 32
    )
    private AccountStatus accountStatus =
            AccountStatus.PENDING_KYC;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "suspension_reason",
            length = 32
    )
    private AccountSuspensionReason suspensionReason;

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

    protected UserEntity() {
        // Required by JPA.
    }

    public UserEntity(
            String keycloakSubject,
            String email,
            String phoneNumber
    ) {
        this.keycloakSubject = keycloakSubject;
        this.email = email;
        this.phoneNumber = phoneNumber;
    }

    public UUID getId() {
        return id;
    }

    public String getKeycloakSubject() {
        return keycloakSubject;
    }

    public String getEmail() {
        return email;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public AccountStatus getAccountStatus() {
        return accountStatus;
    }

    public AccountSuspensionReason getSuspensionReason() {
        return suspensionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void updateEmail(String email) {
        this.email = email;
    }

    public void updatePhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    /**
     * Initial activation after successful KYC.
     *
     * Never activates a suspended or closed account.
     */
    public void activate() {
        if (accountStatus != AccountStatus.PENDING_KYC) {
            throw new IllegalStateException(
                    "Only pending KYC accounts can be activated"
            );
        }

        accountStatus = AccountStatus.ACTIVE;
        suspensionReason = null;
    }

    /**
     * Restores a customer who was suspended specifically
     * for KYC reverification.
     *
     * Administrative, unspecified and final-rejection
     * suspensions cannot use this transition.
     */
    public void reactivateAfterKycReverification() {

        if (accountStatus != AccountStatus.SUSPENDED
                || suspensionReason
                != AccountSuspensionReason.KYC_REVERIFICATION) {

            throw new IllegalStateException(
                    "Account is not eligible for "
                            + "automatic KYC reactivation"
            );
        }

        accountStatus = AccountStatus.ACTIVE;
        suspensionReason = null;
    }

    /**
     * Existing generic suspension operation.
     *
     * Generic suspensions are treated as administrative.
     * Calling this again also overrides a pre-existing
     * KYC-only restriction.
     */
    public void suspend() {

        if (accountStatus == AccountStatus.CLOSED) {
            throw new IllegalStateException(
                    "A closed account cannot be suspended"
            );
        }

        accountStatus = AccountStatus.SUSPENDED;
        suspensionReason =
                AccountSuspensionReason.ADMINISTRATIVE;
    }

    /**
     * Restrict financial access while the customer
     * completes another Sumsub verification.
     *
     * Only ACTIVE accounts can enter this state.
     * Existing restrictions are never downgraded.
     */
    public void suspendForKycReverification() {

        if (accountStatus == AccountStatus.ACTIVE) {

            accountStatus = AccountStatus.SUSPENDED;
            suspensionReason =
                    AccountSuspensionReason.KYC_REVERIFICATION;
        }
    }

    /**
     * A final KYC rejection must override a previous
     * KYC_REVERIFICATION restriction.
     *
     * Administrative and legacy restrictions remain intact.
     */
    public void suspendForKycFinalRejection() {

        if (accountStatus == AccountStatus.ACTIVE
                || (
                accountStatus == AccountStatus.SUSPENDED
                        && suspensionReason
                        == AccountSuspensionReason.KYC_REVERIFICATION
        )) {

            accountStatus = AccountStatus.SUSPENDED;
            suspensionReason =
                    AccountSuspensionReason.KYC_FINAL_REJECTION;
        }
    }

    /**
     * Closure is terminal for automatic KYC transitions.
     */
    public void close() {
        accountStatus = AccountStatus.CLOSED;
        suspensionReason = null;
    }
}
