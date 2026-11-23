package com.fintech.notifications;

import com.fintech.notifications.entity.NotificationOutboxEntity;
import com.fintech.notifications.repository.NotificationOutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        properties = {
                "neos.notifications.microsoft-graph.enabled=false",
                "neos.notifications.dispatcher.enabled=false"
        }
)
class NotificationOutboxIntegrationTest {

    @Autowired
    private NotificationOutboxService outboxService;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Test
    void persistsTransactionNotification() {

        UUID userId = UUID.randomUUID();
        long transactionId = 10_001L;

        TransactionNotificationPayload payload =
                new TransactionNotificationPayload(
                        userId,
                        transactionId,
                        TransactionNotificationType.INCOMING_PAYMENT,
                        TransactionNotificationDirection.CREDIT,
                        "EUR",
                        "EUR/2",
                        10_000L,
                        "incoming_payment:outbox_001_" + userId,
                        Instant.now()
                );

        outboxService.enqueueTransactionEmail(
                "outbox." + userId + "@neos.local",
                payload
        );

        String notificationKey =
                notificationKey(
                        transactionId,
                        userId
                );

        NotificationOutboxEntity entity =
                outboxRepository
                        .findByNotificationKey(notificationKey)
                        .orElseThrow();

        assertThat(entity.getNotificationKey())
                .isEqualTo(notificationKey);

        assertThat(entity.getEventType())
                .isEqualTo("TRANSACTION_COMPLETED");

        assertThat(entity.getChannel())
                .isEqualTo(NotificationChannel.EMAIL);

        assertThat(entity.getUserId())
                .isEqualTo(userId);

        assertThat(entity.getRecipient())
                .isEqualTo("outbox." + userId + "@neos.local");

        assertThat(entity.getStatus())
                .isEqualTo(NotificationOutboxStatus.PENDING);

        assertThat(entity.getAttemptCount())
                .isZero();

        assertThat(entity.getPayloadJson())
                .contains("\"type\":\"INCOMING_PAYMENT\"")
                .contains("\"direction\":\"CREDIT\"")
                .contains("\"currency\":\"EUR\"")
                .contains("\"asset\":\"EUR/2\"")
                .contains("\"amountMinor\":10000");
    }

    @Test
    void retryingSameTransactionNotificationDoesNotCreateDuplicate() {

        UUID userId = UUID.randomUUID();
        long transactionId = 10_002L;

        TransactionNotificationPayload payload =
                new TransactionNotificationPayload(
                        userId,
                        transactionId,
                        TransactionNotificationType.FEE,
                        TransactionNotificationDirection.DEBIT,
                        "EUR",
                        "EUR/2",
                        500L,
                        "fee_collection:outbox_002_" + userId,
                        Instant.now()
                );

        String recipient =
                "outbox.dedupe." + userId + "@neos.local";

        outboxService.enqueueTransactionEmail(
                recipient,
                payload
        );

        outboxService.enqueueTransactionEmail(
                recipient,
                payload
        );

        String notificationKey =
                notificationKey(
                        transactionId,
                        userId
                );

        assertThat(
                outboxRepository.countByNotificationKey(
                        notificationKey
                )
        ).isEqualTo(1L);

        NotificationOutboxEntity entity =
                outboxRepository
                        .findByNotificationKey(notificationKey)
                        .orElseThrow();

        assertThat(entity.getStatus())
                .isEqualTo(NotificationOutboxStatus.PENDING);

        assertThat(entity.getAttemptCount())
                .isZero();
    }

    private String notificationKey(
            long transactionId,
            UUID userId
    ) {

        return "TRANSACTION_COMPLETED:"
                + transactionId
                + ":"
                + userId
                + ":EMAIL";
    }
}
