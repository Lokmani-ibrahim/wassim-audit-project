
package com.fintech.ledger;

import com.fintech.identity.UserContactLookup;
import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.notifications.repository.NotificationOutboxRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@SpringBootTest(properties = {
        "neos.payouts.notification-repair-enabled=true",
        "neos.payouts.ledger-finalization-enabled=false",
        "neos.payouts.reconciliation-enabled=false",
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.backoffice.synthetic-credit.enabled=false",
        "neos.transfers.customer-api-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class PayoutNotificationRepairIntegrationTest {

    @Autowired
    private PayoutNotificationRepairService repair;

    @Autowired
    private UserRepository users;

    @Autowired
    private NotificationOutboxRepository outbox;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean(enforceOverride = true)
    private UserContactLookup contacts;

    private UUID userId;
    private UUID payoutId;
    private String operationId;
    private String email;

    @BeforeEach
    void setup() {

        String unique = UUID.randomUUID().toString();

        email = "payout-repair-" + unique
                + "@example.com";

        UserEntity user = users.saveAndFlush(
                new UserEntity(
                        "payout-repair-" + unique,
                        email,
                        null
                )
        );

        userId = user.getId();
        payoutId = UUID.randomUUID();

        operationId = "po_"
                + payoutId.toString().replace("-", "");

        when(contacts.findEmail(userId))
                .thenReturn(Optional.of(email));

        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "payout-repair-reviewer",
                                "unused",
                                List.of(
                                        new SimpleGrantedAuthority(
                                                "ROLE_RECONCILIATION_ADMIN"
                                        )
                                )
                        )
                );
    }

    private void insertSettled(long transactionId) {

        jdbc.update(
                """
                INSERT INTO payout_requests (
                    payout_id,
                    user_id,
                    request_key,
                    operation_id,
                    currency,
                    amount_minor,
                    settlement_bank_id,
                    status,
                    reserve_ledger_transaction_id,
                    reserve_ledger_reference,
                    settle_ledger_transaction_id,
                    settle_ledger_reference
                )
                VALUES (
                    ?, ?, ?, ?, 'EUR', 5000,
                    'sponsor', 'LEDGER_SETTLED',
                    801, ?, ?, ?
                )
                """,
                payoutId,
                userId,
                UUID.randomUUID(),
                operationId,
                "payout_reserve:" + operationId,
                transactionId,
                "payout_settle:" + operationId
        );
    }

    private String notificationKey(long transactionId) {
        return "TRANSACTION_COMPLETED:"
                + transactionId
                + ":"
                + userId
                + ":EMAIL";
    }

    private PayoutNotificationRepairService.RepairBatch runRepair() {
        return repair.repairBatch(null, 25);
    }

    @AfterEach
    void cleanup() {

        SecurityContextHolder.clearContext();

        if (userId == null) {
            return;
        }

        jdbc.update(
                "DELETE FROM notification_outbox WHERE user_id = ?",
                userId
        );

        jdbc.update(
                "DELETE FROM payout_requests WHERE user_id = ?",
                userId
        );

        jdbc.update(
                "DELETE FROM users WHERE id = ?",
                userId
        );
    }

    @Test
    void missingNotificationIsReconstructed() {

        insertSettled(802L);

        assertEquals(
                0,
                outbox.countByNotificationKey(
                        notificationKey(802L)
                )
        );

        var batch = runRepair();

        assertEquals(1, batch.results().size());

        assertEquals(
                PayoutNotificationRepairService.Outcome.OUTBOX_PRESENT,
                batch.results().get(0).outcome()
        );

        assertEquals(
                1,
                outbox.countByNotificationKey(
                        notificationKey(802L)
                )
        );

        var notification = outbox.findByNotificationKey(
                notificationKey(802L)
        ).orElseThrow();

        assertEquals(email, notification.getRecipient());
        assertEquals(userId, notification.getUserId());

        assertTrue(
                notification.getPayloadJson().contains(
                        "\"type\":\"PAYOUT\""
                )
        );

        assertTrue(
                notification.getPayloadJson().contains(
                        "\"amountMinor\":5000"
                )
        );
    }

    @Test
    void existingOutboxIsNotDuplicatedOrRequeued() {

        insertSettled(802L);

        runRepair();

        jdbc.update(
                """
                UPDATE notification_outbox
                SET status = 'DEAD_LETTER'
                WHERE notification_key = ?
                """,
                notificationKey(802L)
        );

        var replay = runRepair();

        assertEquals(1, replay.results().size());

        assertEquals(
                PayoutNotificationRepairService.Outcome.OUTBOX_PRESENT,
                replay.results().get(0).outcome()
        );

        assertEquals(
                1,
                outbox.countByNotificationKey(
                        notificationKey(802L)
                )
        );

        assertEquals(
                "DEAD_LETTER",
                outbox.findByNotificationKey(
                        notificationKey(802L)
                ).orElseThrow().getStatus().name()
        );
    }

    @Test
    void missingEmailRemainsEligibleForLaterRepair() {

        insertSettled(802L);

        when(contacts.findEmail(userId))
                .thenReturn(Optional.empty());

        var first = runRepair();

        assertEquals(1, first.results().size());

        assertEquals(
                PayoutNotificationRepairService.Outcome.STILL_MISSING,
                first.results().get(0).outcome()
        );

        assertEquals(
                0,
                outbox.countByNotificationKey(
                        notificationKey(802L)
                )
        );

        when(contacts.findEmail(userId))
                .thenReturn(Optional.of(email));

        var second = runRepair();

        assertEquals(
                PayoutNotificationRepairService.Outcome.OUTBOX_PRESENT,
                second.results().get(0).outcome()
        );

        assertEquals(
                1,
                outbox.countByNotificationKey(
                        notificationKey(802L)
                )
        );
    }

    @Test
    void ordinaryCustomerCannotRunRepair() {

        insertSettled(802L);

        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "ordinary-customer",
                                "unused",
                                List.of(
                                        new SimpleGrantedAuthority(
                                                "ROLE_CUSTOMER"
                                        )
                                )
                        )
                );

        assertThrows(
                AccessDeniedException.class,
                this::runRepair
        );

        assertEquals(
                0,
                outbox.countByNotificationKey(
                        notificationKey(802L)
                )
        );
    }


    @Test
    void zeroTransactionIdCanBeRepaired() {

        insertSettled(0L);

        var batch = runRepair();

        assertEquals(1, batch.results().size());

        assertEquals(
                PayoutNotificationRepairService.Outcome.OUTBOX_PRESENT,
                batch.results().get(0).outcome()
        );

        assertEquals(
                1,
                outbox.countByNotificationKey(
                        notificationKey(0L)
                )
        );

        verify(contacts).findEmail(userId);
    }

    @Test
    void simultaneousRepairsCreateOnlyOneOutboxRecord()
            throws Exception {

        insertSettled(805L);

        // Force two repair workers to reach recipient
        // lookup before either can insert the notification.
        CyclicBarrier barrier = new CyclicBarrier(2);

        doAnswer(invocation -> {
            barrier.await(15, TimeUnit.SECONDS);
            return Optional.of(email);
        }).when(contacts).findEmail(userId);

        Callable<PayoutNotificationRepairService.RepairBatch> task =
                () -> {

                    // SecurityContext is thread-local.
                    SecurityContextHolder.getContext()
                            .setAuthentication(
                                    new UsernamePasswordAuthenticationToken(
                                            "concurrent-repair-reviewer",
                                            "unused",
                                            List.of(
                                                    new SimpleGrantedAuthority(
                                                            "ROLE_RECONCILIATION_ADMIN"
                                                    )
                                            )
                                    )
                            );

                    try {
                        return repair.repairBatch(null, 25);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                };

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {

            Future<PayoutNotificationRepairService.RepairBatch> first =
                    executor.submit(task);

            Future<PayoutNotificationRepairService.RepairBatch> second =
                    executor.submit(task);

            var firstResult = first.get(
                    25, TimeUnit.SECONDS
            );

            var secondResult = second.get(
                    25, TimeUnit.SECONDS
            );

            assertEquals(1, firstResult.results().size());
            assertEquals(1, secondResult.results().size());

            assertEquals(
                    PayoutNotificationRepairService.Outcome.OUTBOX_PRESENT,
                    firstResult.results().get(0).outcome()
            );

            assertEquals(
                    PayoutNotificationRepairService.Outcome.OUTBOX_PRESENT,
                    secondResult.results().get(0).outcome()
            );

            // Critical financial notification invariant:
            // concurrent repair does not create duplicate rows.
            assertEquals(
                    1,
                    outbox.countByNotificationKey(
                            notificationKey(805L)
                    )
            );

        } finally {
            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(
                            10, TimeUnit.SECONDS
                    )
            );
        }
    }


}
