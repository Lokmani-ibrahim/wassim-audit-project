package com.fintech.notifications.repository;

import com.fintech.notifications.entity.NotificationOutboxEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only view of notification_outbox for the customer
 * in-app notification feed (GET /api/notifications).
 *
 * Deliberately separate from NotificationOutboxRepository:
 * it exposes no save/delete method and never touches the
 * delivery lifecycle (status, attempts, leases).
 *
 * Ordering is newest first with the row id as tie-breaker,
 * so keyset pagination on (created_at, id) is total and
 * stable. Backed by idx_notification_outbox_user
 * (user_id, created_at DESC).
 */
public interface NotificationFeedRepository
        extends Repository<NotificationOutboxEntity, UUID> {

    @Query(
            """
            SELECT n
            FROM NotificationOutboxEntity n
            WHERE n.userId = :userId
            AND n.eventType = :eventType
            ORDER BY n.createdAt DESC, n.id DESC
            """
    )
    List<NotificationOutboxEntity> findFirstPage(
            @Param("userId")
            UUID userId,

            @Param("eventType")
            String eventType,

            Limit limit
    );

    @Query(
            """
            SELECT n
            FROM NotificationOutboxEntity n
            WHERE n.userId = :userId
            AND n.eventType = :eventType
            AND (
                n.createdAt < :createdAt
                OR (
                    n.createdAt = :createdAt
                    AND n.id < :id
                )
            )
            ORDER BY n.createdAt DESC, n.id DESC
            """
    )
    List<NotificationOutboxEntity> findPageAfter(
            @Param("userId")
            UUID userId,

            @Param("eventType")
            String eventType,

            @Param("createdAt")
            Instant createdAt,

            @Param("id")
            UUID id,

            Limit limit
    );
}
