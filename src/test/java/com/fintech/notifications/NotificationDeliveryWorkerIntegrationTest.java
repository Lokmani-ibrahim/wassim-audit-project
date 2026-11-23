package com.fintech.notifications;

import com.fintech.notifications.entity.NotificationOutboxEntity;
import com.fintech.notifications.port.EmailNotificationPort;
import com.fintech.notifications.repository.NotificationOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        properties = {
                "neos.notifications.microsoft-graph.enabled=false",
                "neos.notifications.dispatcher.enabled=false"
        }
)
@Import(NotificationDeliveryWorkerIntegrationTest.TestConfig.class)
class NotificationDeliveryWorkerIntegrationTest {

    @Autowired
    private NotificationOutboxService outboxService;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private NotificationDeliveryWorker worker;

    @Autowired
    private TestEmailNotificationPort emailPort;

    @BeforeEach
    void resetEmailPort() {
        emailPort.reset();
    }

    @Test
    void successfulDeliveryBecomesProviderAccepted() {

        UUID userId = UUID.randomUUID();
        long transactionId = 20_001L;

        enqueue(
                userId,
                transactionId,
                TransactionNotificationType.INCOMING_PAYMENT,
                TransactionNotificationDirection.CREDIT,
                10_000L,
                "incoming_payment:worker_success_" + userId
        );

        String key =
                notificationKey(
                        transactionId,
                        userId
                );

        int processed =
                worker.runOnce(
                        100
                );

        NotificationOutboxEntity row =
                outboxRepository
                        .findByNotificationKey(
                                key
                        )
                        .orElseThrow();

        assertThat(
                processed
        ).isGreaterThanOrEqualTo(
                1
        );

        assertThat(
                row.getStatus()
        ).isEqualTo(
                NotificationOutboxStatus.PROVIDER_ACCEPTED
        );

        assertThat(
                row.getAttemptCount()
        ).isEqualTo(
                1
        );

        assertThat(
                row.getPublishedAt()
        ).isNotNull();

        assertThat(
                emailPort.sentCount()
        ).isGreaterThanOrEqualTo(
                1
        );
    }

    @Test
    void retryableFailureRetriesAndThenBecomesProviderAccepted() {

        UUID userId = UUID.randomUUID();
        long transactionId = 20_002L;

        String recipient =
                recipient(
                        userId
                );

        enqueue(
                userId,
                transactionId,
                TransactionNotificationType.TRANSFER,
                TransactionNotificationDirection.DEBIT,
                5_000L,
                "internal_transfer:worker_retry_" + userId
        );

        String key =
                notificationKey(
                        transactionId,
                        userId
                );

        /*
         * Fail specifically for THIS notification recipient.
         *
         * Other pending rows from previous integration tests
         * must not consume the simulated provider failure.
         */
        emailPort.failNextFor(
                recipient,
                new EmailDeliveryException(
                        "temporary Graph 503",
                        true,
                        Duration.ZERO
                )
        );

        worker.runOnce(
                100
        );

        NotificationOutboxEntity afterFailure =
                outboxRepository
                        .findByNotificationKey(
                                key
                        )
                        .orElseThrow();

        assertThat(
                afterFailure.getStatus()
        ).isEqualTo(
                NotificationOutboxStatus.RETRY
        );

        assertThat(
                afterFailure.getAttemptCount()
        ).isEqualTo(
                1
        );

        /*
         * The targeted failure was consumed once.
         * This retry must therefore succeed.
         */
        worker.runOnce(
                100
        );

        NotificationOutboxEntity afterRetry =
                outboxRepository
                        .findByNotificationKey(
                                key
                        )
                        .orElseThrow();

        assertThat(
                afterRetry.getStatus()
        ).isEqualTo(
                NotificationOutboxStatus.PROVIDER_ACCEPTED
        );

        assertThat(
                afterRetry.getAttemptCount()
        ).isEqualTo(
                2
        );

        assertThat(
                afterRetry.getPublishedAt()
        ).isNotNull();
    }

    @Test
    void permanentFailureBecomesDeadLetter() {

        UUID userId = UUID.randomUUID();
        long transactionId = 20_003L;

        String recipient =
                recipient(
                        userId
                );

        enqueue(
                userId,
                transactionId,
                TransactionNotificationType.PAYOUT,
                TransactionNotificationDirection.DEBIT,
                3_000L,
                "payout_settle:worker_permanent_" + userId
        );

        String key =
                notificationKey(
                        transactionId,
                        userId
                );

        /*
         * Fail only the row created by this test.
         */
        emailPort.failNextFor(
                recipient,
                new EmailDeliveryException(
                        "Graph 403 Forbidden",
                        false,
                        null
                )
        );

        worker.runOnce(
                100
        );

        NotificationOutboxEntity row =
                outboxRepository
                        .findByNotificationKey(
                                key
                        )
                        .orElseThrow();

        assertThat(
                row.getStatus()
        ).isEqualTo(
                NotificationOutboxStatus.DEAD_LETTER
        );

        assertThat(
                row.getAttemptCount()
        ).isEqualTo(
                1
        );

        assertThat(
                row.getLastError()
        ).contains(
                "403"
        );
    }

    private void enqueue(
            UUID userId,
            long transactionId,
            TransactionNotificationType type,
            TransactionNotificationDirection direction,
            long amountMinor,
            String reference
    ) {

        TransactionNotificationPayload payload =
                new TransactionNotificationPayload(
                        userId,
                        transactionId,
                        type,
                        direction,
                        "EUR",
                        "EUR/2",
                        amountMinor,
                        reference,
                        Instant.now()
                );

        outboxService.enqueueTransactionEmail(
                recipient(
                        userId
                ),
                payload
        );
    }

    private String recipient(
            UUID userId
    ) {

        return "worker."
                + userId
                + "@neos.local";
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

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        TestEmailNotificationPort testEmailNotificationPort() {

            return new TestEmailNotificationPort();
        }
    }

    static final class TestEmailNotificationPort
            implements EmailNotificationPort {

        private final Map<String, Deque<RuntimeException>>
                failuresByRecipient =
                new HashMap<>();

        private int sentCount;

        @Override
        public synchronized void sendHtml(
                String recipient,
                String subject,
                String htmlBody
        ) {

            Deque<RuntimeException> failures =
                    failuresByRecipient
                            .get(
                                    recipient
                            );

            RuntimeException failure =
                    failures == null
                            ? null
                            : failures.pollFirst();

            if (failures != null
                    && failures.isEmpty()) {

                failuresByRecipient.remove(
                        recipient
                );
            }

            if (failure != null) {
                throw failure;
            }

            sentCount++;
        }

        synchronized void failNextFor(
                String recipient,
                RuntimeException exception
        ) {

            failuresByRecipient
                    .computeIfAbsent(
                            recipient,
                            ignored ->
                                    new ArrayDeque<>()
                    )
                    .addLast(
                            exception
                    );
        }

        synchronized int sentCount() {

            return sentCount;
        }

        synchronized void reset() {

            failuresByRecipient.clear();
            sentCount = 0;
        }
    }
}