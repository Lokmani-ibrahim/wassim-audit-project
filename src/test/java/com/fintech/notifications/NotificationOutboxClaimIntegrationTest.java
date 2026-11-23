package com.fintech.notifications;




import com.fintech.notifications.entity.NotificationOutboxEntity;
import com.fintech.notifications.repository.NotificationOutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class NotificationOutboxClaimIntegrationTest {

    @Autowired
    private NotificationOutboxService outboxService;

    @Autowired
    private NotificationOutboxClaimService claimService;

    @Autowired
    private NotificationOutboxRepository repository;

    @Test
    void claimsPendingNotificationAndMarksDelivered() {

        NotificationOutboxEntity created =
                createNotification();

        List<ClaimedNotification> claims =
                claimService.claimReady(
                        10
                );

        ClaimedNotification claim =
                claims
                        .stream()
                        .filter(
                                item ->
                                        item.id()
                                                .equals(
                                                        created.getId()
                                                )
                        )
                        .findFirst()
                        .orElseThrow();

        assertThat(
                claim.attemptNumber()
        ).isEqualTo(
                1
        );

        NotificationOutboxEntity processing =
                repository
                        .findById(
                                created.getId()
                        )
                        .orElseThrow();

        assertThat(
                processing.getStatus()
        ).isEqualTo(
                NotificationOutboxStatus.PROCESSING
        );

        assertThat(
                processing.getAttemptCount()
        ).isEqualTo(
                1
        );

        /*
         * The same item must not be claimable while its
         * processing lease is still active.
         */
        assertThat(
                claimService
                        .claimReady(
                                10
                        )
                        .stream()
                        .noneMatch(
                                item ->
                                        item.id()
                                                .equals(
                                                        created.getId()
                                                )
                        )
        ).isTrue();

        claimService.markDelivered(
                created.getId()
        );

        NotificationOutboxEntity delivered =
                repository
                        .findById(
                                created.getId()
                        )
                        .orElseThrow();

        assertThat(
                delivered.getStatus()
        ).isEqualTo(
                NotificationOutboxStatus.PROVIDER_ACCEPTED
        );
    }

    @Test
    void failedNotificationCanBeRetried() {

        NotificationOutboxEntity created =
                createNotification();

        ClaimedNotification firstClaim =
                claimService
                        .claimReady(
                                100
                        )
                        .stream()
                        .filter(
                                item ->
                                        item.id()
                                                .equals(
                                                        created.getId()
                                                )
                        )
                        .findFirst()
                        .orElseThrow();

        assertThat(
                firstClaim.attemptNumber()
        ).isEqualTo(
                1
        );

        /*
         * Zero delay is useful here so the row becomes
         * immediately eligible again.
         */
        claimService.scheduleRetry(
                created.getId(),
                Duration.ZERO,
                "Graph returned HTTP 503"
        );

        NotificationOutboxEntity retrying =
                repository
                        .findById(
                                created.getId()
                        )
                        .orElseThrow();

        assertThat(
                retrying.getStatus()
        ).isEqualTo(
                NotificationOutboxStatus.RETRY
        );

        assertThat(
                retrying.getLastError()
        ).contains(
                "503"
        );

        ClaimedNotification secondClaim =
                claimService
                        .claimReady(
                                100
                        )
                        .stream()
                        .filter(
                                item ->
                                        item.id()
                                                .equals(
                                                        created.getId()
                                                )
                        )
                        .findFirst()
                        .orElseThrow();

        assertThat(
                secondClaim.attemptNumber()
        ).isEqualTo(
                2
        );
    }

    private NotificationOutboxEntity createNotification() {

        UUID userId =
                UUID.randomUUID();

        long transactionId =
                Math.abs(
                        System.nanoTime()
                );

        TransactionNotificationPayload payload =
                new TransactionNotificationPayload(
                        userId,
                        transactionId,
                        TransactionNotificationType.INCOMING_PAYMENT,
                        TransactionNotificationDirection.CREDIT,
                        "EUR",
                        "EUR/2",
                        10_000,
                        "incoming_payment:c4_claim_test_"
                                + UUID.randomUUID(),
                        Instant.now()
                );

        return outboxService
                .enqueueTransactionEmail(
                        "customer@neos.local",
                        payload
                );
    }
}