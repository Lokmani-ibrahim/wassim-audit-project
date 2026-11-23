package com.fintech.identity.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "devices",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_devices_user_installation",
                        columnNames = {"user_id", "installation_id"}
                )
        }
)
public class DeviceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false
    )
    private UserEntity user;

    @Column(
            name = "installation_id",
            nullable = false,
            length = 255
    )
    private String installationId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "platform",
            nullable = false,
            length = 20
    )
    private DevicePlatform platform;

    @Column(
            name = "trusted",
            nullable = false
    )
    private boolean trusted = false;

    @CreationTimestamp
    @Column(
            name = "first_seen_at",
            nullable = false,
            updatable = false
    )
    private Instant firstSeenAt;

    @Column(
            name = "last_seen_at",
            nullable = false
    )
    private Instant lastSeenAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected DeviceEntity() {
        // Required by JPA/Hibernate
    }

    public DeviceEntity(
            UserEntity user,
            String installationId,
            DevicePlatform platform
    ) {
        this.user = user;
        this.installationId = installationId;
        this.platform = platform;
        this.lastSeenAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UserEntity getUser() {
        return user;
    }

    public String getInstallationId() {
        return installationId;
    }

    public DevicePlatform getPlatform() {
        return platform;
    }

    public boolean isTrusted() {
        return trusted;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void markSeen() {
        this.lastSeenAt = Instant.now();
    }

    public void trust() {
        this.trusted = true;
    }

    public void untrust() {
        this.trusted = false;
    }

    public void revoke() {
        this.revokedAt = Instant.now();
        this.trusted = false;
    }
}