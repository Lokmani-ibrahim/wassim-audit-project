package com.fintech.notifications.repository;

import com.fintech.notifications.NotificationOutboxStatus;
import com.fintech.notifications.entity.NotificationOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationOutboxRepository
        extends JpaRepository<NotificationOutboxEntity, UUID> {

    Optional<NotificationOutboxEntity> findByNotificationKey(
            String notificationKey
    );

    long countByNotificationKey(
            String notificationKey
    );

    /*
     * PostgreSQL durable worker claim.
     *
     * FOR UPDATE SKIP LOCKED means:
     *
     * Worker A locks rows 1-10.
     * Worker B does not wait for them.
     * Worker B simply claims other available rows.
     *
     * PROCESSING rows whose lease expired are also eligible.
     */
    @Query(
            value = """
                    SELECT *
                    FROM notification_outbox
                    WHERE (
                        status IN ('PENDING', 'RETRY')
                        OR status = 'PROCESSING'
                    )
                    AND next_attempt_at <= :now
                    ORDER BY created_at ASC
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                    """,
            nativeQuery = true
    )
    List<NotificationOutboxEntity> lockReadyBatch(
            @Param("now")
            Instant now,

            @Param("batchSize")
            int batchSize
    );
}