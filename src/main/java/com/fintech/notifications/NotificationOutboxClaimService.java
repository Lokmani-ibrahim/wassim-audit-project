package com.fintech.notifications;

import com.fintech.notifications.entity.NotificationOutboxEntity;
import com.fintech.notifications.repository.NotificationOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class NotificationOutboxClaimService {

    private static final Duration PROCESSING_LEASE =
            Duration.ofMinutes(
                    5
            );

    private final NotificationOutboxRepository repository;

    public NotificationOutboxClaimService(
            NotificationOutboxRepository repository
    ) {

        this.repository =
                Objects.requireNonNull(
                        repository,
                        "repository must not be null"
                );
    }

    @Transactional
    public List<ClaimedNotification> claimReady(
            int batchSize
    ) {

        if (batchSize < 1
                || batchSize > 100) {

            throw new IllegalArgumentException(
                    "batchSize must be between 1 and 100"
            );
        }

        Instant now =
                Instant.now();

        Instant leaseUntil =
                now.plus(
                        PROCESSING_LEASE
                );

        List<NotificationOutboxEntity> entities =
                repository.lockReadyBatch(
                        now,
                        batchSize
                );

        entities.forEach(
                entity ->
                        entity.claim(
                                leaseUntil
                        )
        );

        repository.flush();

        return entities
                .stream()
                .map(
                        NotificationOutboxClaimService::toClaim
                )
                .toList();
    }

    @Transactional
    public void markDelivered(
            UUID id
    ) {

        NotificationOutboxEntity entity =
                repository
                        .findById(
                                id
                        )
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Notification outbox item not found: "
                                                        + id
                                        )
                        );

        entity.markProviderAccepted();
    }

    @Transactional
    public void scheduleRetry(
            UUID id,
            Duration delay,
            String error
    ) {

        Objects.requireNonNull(
                delay,
                "delay must not be null"
        );

        if (delay.isNegative()) {

            throw new IllegalArgumentException(
                    "delay must not be negative"
            );
        }

        NotificationOutboxEntity entity =
                repository
                        .findById(
                                id
                        )
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Notification outbox item not found: "
                                                        + id
                                        )
                        );

        entity.scheduleRetry(
                Instant.now().plus(
                        delay
                ),
                error
        );
    }

    @Transactional
    public void markDeadLetter(
            UUID id,
            String error
    ) {

        NotificationOutboxEntity entity =
                repository
                        .findById(
                                id
                        )
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Notification outbox item not found: "
                                                        + id
                                        )
                        );

        entity.markDeadLetter(
                error
        );
    }

    private static ClaimedNotification toClaim(
            NotificationOutboxEntity entity
    ) {

        return new ClaimedNotification(
                entity.getId(),
                entity.getNotificationKey(),
                entity.getEventType(),
                entity.getChannel(),
                entity.getUserId(),
                entity.getRecipient(),
                entity.getPayloadJson(),
                entity.getAttemptCount()
        );
    }
}