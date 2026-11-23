package com.fintech.notifications;

import java.time.Instant;
import java.util.UUID;

/**
 * One in-app notification, derived from an outbox row.
 *
 * Exposes only what GET /api/transactions already shows
 * the customer (transactionId, type, direction, currency,
 * amountMinor, reference), plus:
 *
 * - id: the outbox row id. Created once and never changed,
 *   so the app can key its device-side read state on it.
 * - testCredit: true for a back-office synthetic credit
 *   (EMAIL-2). See NotificationFeedService.
 * - createdAt: when the notification was recorded, not the
 *   ledger timestamp.
 *
 * Never exposed: recipient, notification key, channel,
 * delivery status, attempts, provider errors, user id,
 * Formance asset.
 */
public record NotificationFeedItem(

        UUID id,
        TransactionNotificationType type,
        TransactionNotificationDirection direction,
        String currency,
        long amountMinor,
        long transactionId,
        String reference,
        boolean testCredit,
        Instant createdAt

) {
}
