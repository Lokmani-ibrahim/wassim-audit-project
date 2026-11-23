package com.fintech.notifications.entity;

import com.fintech.notifications.NotificationChannel;
import com.fintech.notifications.NotificationOutboxStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "notification_outbox",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_notification_outbox_key",
                        columnNames = "notification_key"
                )
        }
)
public class NotificationOutboxEntity {

    @Id
    private UUID id;

    @Column(
            name = "notification_key",
            nullable = false,
            length = 255
    )
    private String notificationKey;

    @Column(
            name = "event_type",
            nullable = false,
            length = 64
    )
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "channel",
            nullable = false,
            length = 32
    )
    private NotificationChannel channel;

    @Column(
            name = "user_id",
            nullable = false
    )
    private UUID userId;

    @Column(
            name = "recipient",
            nullable = false,
            length = 320
    )
    private String recipient;

    @Column(
            name = "payload_json",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 32
    )
    private NotificationOutboxStatus status;

    @Column(
            name = "attempt_count",
            nullable = false
    )
    private int attemptCount;

    @Column(
            name = "next_attempt_at",
            nullable = false
    )
    private Instant nextAttemptAt;

    @Column(
            name = "last_error"
    )
    private String lastError;

    @Column(
            name = "published_at"
    )
    private Instant publishedAt;

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

    protected NotificationOutboxEntity() {
        // Required by JPA.
    }

    public NotificationOutboxEntity(
            String notificationKey,
            String eventType,
            NotificationChannel channel,
            UUID userId,
            String recipient,
            String payloadJson
    ) {

        this.id =
                UUID.randomUUID();

        this.notificationKey =
                requireText(
                        notificationKey,
                        "notificationKey"
                );

        this.eventType =
                requireText(
                        eventType,
                        "eventType"
                );

        this.channel =
                Objects.requireNonNull(
                        channel,
                        "channel must not be null"
                );

        this.userId =
                Objects.requireNonNull(
                        userId,
                        "userId must not be null"
                );

        this.recipient =
                requireText(
                        recipient,
                        "recipient"
                );

        this.payloadJson =
                requireText(
                        payloadJson,
                        "payloadJson"
                );

        this.status =
                NotificationOutboxStatus.PENDING;

        this.attemptCount =
                0;

        this.nextAttemptAt =
                Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getNotificationKey() {
        return notificationKey;
    }

    public String getEventType() {
        return eventType;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public NotificationOutboxStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private static String requireText(
            String value,
            String field
    ) {

        Objects.requireNonNull(
                value,
                field + " must not be null"
        );

        String trimmed =
                value.trim();

        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }

        return trimmed;
    }
    public void claim(
            Instant leaseUntil
    ) {

        Objects.requireNonNull(
                leaseUntil,
                "leaseUntil must not be null"
        );

        if (status != NotificationOutboxStatus.PENDING
                && status != NotificationOutboxStatus.RETRY
                && status != NotificationOutboxStatus.PROCESSING) {

            throw new IllegalStateException(
                    "Notification cannot be claimed from status "
                            + status
            );
        }

        this.status =
                NotificationOutboxStatus.PROCESSING;

        this.attemptCount++;

        /*
         * While PROCESSING this field represents the lease expiry.
         *
         * If the process crashes, another worker may reclaim the row
         * once this timestamp is reached.
         */
        this.nextAttemptAt =
                leaseUntil;

        this.lastError =
                null;
    }

    public void markProviderAccepted() {

        if (status != NotificationOutboxStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Only PROCESSING notifications can be accepted by the provider"
            );
        }

        status =
                NotificationOutboxStatus.PROVIDER_ACCEPTED;

        publishedAt =
                java.time.Instant.now();

        nextAttemptAt =
                publishedAt;

        lastError =
                null;

        updatedAt =
                publishedAt;
    }

    public void scheduleRetry(
            Instant nextAttemptAt,
            String error
    ) {

        requireProcessing();

        this.status =
                NotificationOutboxStatus.RETRY;

        this.nextAttemptAt =
                Objects.requireNonNull(
                        nextAttemptAt,
                        "nextAttemptAt must not be null"
                );

        this.lastError =
                normalizeError(
                        error
                );
    }

    public void markDeadLetter(
            String error
    ) {

        requireProcessing();

        this.status =
                NotificationOutboxStatus.DEAD_LETTER;

        this.lastError =
                normalizeError(
                        error
                );
    }

    private void requireProcessing() {

        if (status != NotificationOutboxStatus.PROCESSING) {

            throw new IllegalStateException(
                    "Notification must be PROCESSING but was "
                            + status
            );
        }
    }

    private static String normalizeError(
            String error
    ) {

        if (error == null
                || error.isBlank()) {

            return "Unknown notification delivery failure";
        }

        String trimmed =
                error.trim();

        /*
         * Prevent gigantic remote-provider error bodies from
         * filling the database.
         */
        return trimmed.length() <= 2_000
                ? trimmed
                : trimmed.substring(
                0,
                2_000
        );
    }
}