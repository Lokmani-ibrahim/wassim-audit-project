package com.fintech.notifications;

import java.util.UUID;

public record ClaimedNotification(

        UUID id,
        String notificationKey,
        String eventType,
        NotificationChannel channel,
        UUID userId,
        String recipient,
        String payloadJson,
        int attemptNumber

) {
}