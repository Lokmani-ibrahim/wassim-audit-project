
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "neos.transfers.reconciliation.notification-repair-enabled=true",
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class CustomerTransferNotificationRepairIntegrationTest {

    @Autowired
    private CustomerTransferNotificationRepairService repair;

    @Autowired
    private UserRepository users;

    @Autowired
    private NotificationOutboxRepository outbox;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean(enforceOverride = true)
    private UserContactLookup contacts;

    private UUID senderId;
    private UUID recipientId;
    private UUID requestKey;
    private UUID auditId;
    private String operationId;
    private String senderEmail;
    private String recipientEmail;
    private long transactionId;

    @BeforeEach
    void setup() {

        String unique = UUID.randomUUID().toString();

        senderEmail = "transfer-sender-" + unique
                + "@example.com";

        recipientEmail = "transfer-recipient-" + unique
                + "@example.com";

        UserEntity sender = users.saveAndFlush(
                new UserEntity(
                        "repair-sender-" + unique,
                        senderEmail,
                        null
                )
        );

        UserEntity recipient = users.saveAndFlush(
                new UserEntity(
                        "repair-recipient-" + unique,
                        recipientEmail,
                        null
                )
        );

        senderId = sender.getId();
        recipientId = recipient.getId();
        requestKey = UUID.randomUUID();
        auditId = UUID.randomUUID();

        operationId =
                "ct_repair_"
                        + requestKey.toString()
                        .replace("-", "");

        transactionId =
                UUID.randomUUID()
                        .getMostSignificantBits()
                        & Long.MAX_VALUE;

        when(contacts.findEmail(senderId))
                .thenReturn(Optional.of(senderEmail));

        when(contacts.findEmail(recipientId))
                .thenReturn(Optional.of(recipientEmail));

        jdbc.update(
                """
                INSERT INTO customer_transfer_idempotency (
                    sender_user_id,
                    request_key,
                    recipient_user_id,
                    currency,
                    amount_minor,
                    operation_id,
                    status,
                    ledger_transaction_id,
                    ledger_reference
                )
                VALUES (?, ?, ?, 'EUR', 5000, ?,
                        'SUCCEEDED', ?, ?)
                """,
                senderId,
                requestKey,
                recipientId,
                operationId,
                transactionId,
                reference()
        );

        jdbc.update(
                """
                INSERT INTO customer_transfer_reconciliation_audit (
                    audit_id,
                    sender_user_id,
                    request_key,
                    operation_id,
                    action,
                    previous_status,
                    resulting_status,
                    formance_transaction_id,
                    actor_subject
                )
                VALUES (
                    ?, ?, ?, ?,
                    'CONFIRM_POSTED',
                    'UNKNOWN',
                    'SUCCEEDED',
                    ?, 'repair-test-reviewer'
                )
                """,
                auditId,
                senderId,
                requestKey,
                operationId,
                transactionId
        );
    }

    private String reference() {
        return "internal_transfer:" + operationId;
    }

    private String key(UUID userId) {
        return "TRANSACTION_COMPLETED:"
                + transactionId
                + ":"
                + userId
                + ":EMAIL";
    }

    private CustomerTransferNotificationRepairService.RepairBatch
    runRepair() {
        return repair.repairForSender(
                senderId,
                null,
                25
        );
    }

    @AfterEach
    void cleanup() {

        if (senderId == null || recipientId == null) {
            return;
        }

        jdbc.update(
                """
                DELETE FROM notification_outbox
                WHERE user_id IN (?, ?)
                """,
                senderId,
                recipientId
        );

        jdbc.update(
                """
                DELETE FROM customer_transfer_reconciliation_audit
                WHERE sender_user_id = ?
                """,
                senderId
        );

        jdbc.update(
                """
                DELETE FROM customer_transfer_idempotency
                WHERE sender_user_id = ?
                """,
                senderId
        );

        jdbc.update(
                "DELETE FROM users WHERE id IN (?, ?)",
                senderId,
                recipientId
        );
    }

    @Test
    @WithMockUser(authorities = "ROLE_RECONCILIATION_ADMIN")
    void missingSenderAndRecipientNotificationsAreRepaired() {

        var batch = runRepair();

        assertEquals(1, batch.results().size());

        var result = batch.results().get(0);

        assertEquals(
                CustomerTransferNotificationRepairService.Outcome
                        .OUTBOX_PRESENT,
                result.senderOutcome()
        );

        assertEquals(
                CustomerTransferNotificationRepairService.Outcome
                        .OUTBOX_PRESENT,
                result.recipientOutcome()
        );

        assertEquals(
                1,
                outbox.countByNotificationKey(key(senderId))
        );

        assertEquals(
                1,
                outbox.countByNotificationKey(key(recipientId))
        );

        var senderNotification = outbox
                .findByNotificationKey(key(senderId))
                .orElseThrow();

        var recipientNotification = outbox
                .findByNotificationKey(key(recipientId))
                .orElseThrow();

        assertTrue(
                senderNotification.getPayloadJson()
                        .contains("\"direction\":\"DEBIT\"")
        );

        assertTrue(
                recipientNotification.getPayloadJson()
                        .contains("\"direction\":\"CREDIT\"")
        );

        assertTrue(
                senderNotification.getPayloadJson()
                        .contains("\"type\":\"TRANSFER\"")
        );

        assertTrue(
                recipientNotification.getPayloadJson()
                        .contains("\"type\":\"TRANSFER\"")
        );
    }

    @Test
    @WithMockUser(authorities = "ROLE_RECONCILIATION_ADMIN")
    void repeatedRepairNeverDuplicatesOutboxRecords() {

        runRepair();
        runRepair();

        assertEquals(
                1,
                outbox.countByNotificationKey(key(senderId))
        );

        assertEquals(
                1,
                outbox.countByNotificationKey(key(recipientId))
        );

        // Existing notifications, including dead letters,
        // must not be reset or requeued by repair.
        jdbc.update(
                """
                UPDATE notification_outbox
                SET status = 'DEAD_LETTER'
                WHERE notification_key = ?
                """,
                key(senderId)
        );

        runRepair();

        assertEquals(
                "DEAD_LETTER",
                outbox.findByNotificationKey(key(senderId))
                        .orElseThrow()
                        .getStatus()
                        .name()
        );

        assertEquals(
                1,
                outbox.countByNotificationKey(key(senderId))
        );
    }

    @Test
    @WithMockUser(authorities = "ROLE_RECONCILIATION_ADMIN")
    void missingSenderEmailCanBeRepairedLater() {

        when(contacts.findEmail(senderId))
                .thenReturn(Optional.empty());

        var first = runRepair().results().get(0);

        assertEquals(
                CustomerTransferNotificationRepairService.Outcome
                        .STILL_MISSING,
                first.senderOutcome()
        );

        assertEquals(
                CustomerTransferNotificationRepairService.Outcome
                        .OUTBOX_PRESENT,
                first.recipientOutcome()
        );

        when(contacts.findEmail(senderId))
                .thenReturn(Optional.of(senderEmail));

        var second = runRepair().results().get(0);

        assertEquals(
                CustomerTransferNotificationRepairService.Outcome
                        .OUTBOX_PRESENT,
                second.senderOutcome()
        );

        assertEquals(
                1,
                outbox.countByNotificationKey(key(senderId))
        );

        assertEquals(
                1,
                outbox.countByNotificationKey(key(recipientId))
        );
    }

    @Test
    @WithMockUser(authorities = "ROLE_RECONCILIATION_ADMIN")
    void inconsistentPersistedReferencePreventsRepair() {

        jdbc.update(
                """
                UPDATE customer_transfer_idempotency
                SET ledger_reference = 'invalid_reference'
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                senderId,
                requestKey
        );

        var result = runRepair().results().get(0);

        assertEquals(
                CustomerTransferNotificationRepairService.Outcome
                        .INVALID_CONFIRMED_EVIDENCE,
                result.senderOutcome()
        );

        assertEquals(
                CustomerTransferNotificationRepairService.Outcome
                        .INVALID_CONFIRMED_EVIDENCE,
                result.recipientOutcome()
        );

        assertEquals(
                0,
                outbox.countByNotificationKey(key(senderId))
        );

        assertEquals(
                0,
                outbox.countByNotificationKey(key(recipientId))
        );

        verifyNoInteractions(contacts);
    }

    @Test
    @WithMockUser(authorities = "ROLE_CUSTOMER")
    void ordinaryCustomerCannotRepairNotifications() {

        assertThrows(
                AccessDeniedException.class,
                this::runRepair
        );

        assertEquals(
                0,
                outbox.countByNotificationKey(key(senderId))
        );

        assertEquals(
                0,
                outbox.countByNotificationKey(key(recipientId))
        );
    }
}
