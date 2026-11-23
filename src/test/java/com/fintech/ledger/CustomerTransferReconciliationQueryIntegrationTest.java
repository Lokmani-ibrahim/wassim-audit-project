
package com.fintech.ledger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.transfers.customer-api-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class CustomerTransferReconciliationQueryIntegrationTest {

    private static final Instant NOW =
            Instant.parse("2026-10-30T20:00:00Z");

    private static final Instant STALE_BEFORE =
            NOW.minusSeconds(300);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CustomerTransferReconciliationQueryService service;

    private final UUID senderId = UUID.randomUUID();
    private final UUID otherSenderId = UUID.randomUUID();
    private final UUID recipientId = UUID.randomUUID();

    private UUID insert(
            UUID sender,
            String status,
            Instant updatedAt
    ) {

        UUID key = UUID.randomUUID();

        Long transactionId =
                "SUCCEEDED".equals(status) ? 9001L : null;

        String reference =
                "SUCCEEDED".equals(status)
                        ? "internal_transfer:confirmed"
                        : null;

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
                    ledger_reference,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sender,
                key,
                recipientId,
                "EUR",
                5_000L,
                "ct_" + key.toString().replace("-", ""),
                status,
                transactionId,
                reference,
                Timestamp.from(updatedAt.minusSeconds(60)),
                Timestamp.from(updatedAt)
        );

        return key;
    }

    @AfterEach
    void cleanup() {

        jdbc.update(
                """
                DELETE FROM customer_transfer_idempotency
                WHERE sender_user_id IN (?, ?)
                """,
                senderId,
                otherSenderId
        );
    }

    @Test
    void unknownTransferIsAlwaysListed() {

        UUID key = insert(
                senderId,
                "UNKNOWN",
                NOW
        );

        var results = service.findForSender(
                senderId,
                STALE_BEFORE,
                10
        );

        assertEquals(1, results.size());

        var transfer = results.get(0);

        assertEquals(key, transfer.requestKey());
        assertEquals(senderId, transfer.senderUserId());

        assertEquals(
                CustomerTransferReconciliationQueryService
                        .UnresolvedStatus.UNKNOWN,
                transfer.status()
        );

        assertEquals(5_000L, transfer.amountMinor());
        assertEquals("EUR", transfer.currency().name());
    }

    @Test
    void recentInProgressTransferIsExcluded() {

        insert(
                senderId,
                "IN_PROGRESS",
                NOW
        );

        var results = service.findForSender(
                senderId,
                STALE_BEFORE,
                10
        );

        assertTrue(results.isEmpty());
    }

    @Test
    void staleInProgressTransferIsListed() {

        UUID key = insert(
                senderId,
                "IN_PROGRESS",
                NOW.minusSeconds(600)
        );

        var results = service.findForSender(
                senderId,
                STALE_BEFORE,
                10
        );

        assertEquals(1, results.size());

        assertEquals(
                key,
                results.get(0).requestKey()
        );

        assertEquals(
                CustomerTransferReconciliationQueryService
                        .UnresolvedStatus.IN_PROGRESS,
                results.get(0).status()
        );
    }

    @Test
    void successfulTransferIsNeverListed() {

        insert(
                senderId,
                "SUCCEEDED",
                NOW.minusSeconds(900)
        );

        var results = service.findForSender(
                senderId,
                STALE_BEFORE,
                10
        );

        assertTrue(results.isEmpty());
    }

    @Test
    void otherCustomersTransfersAreExcluded() {

        insert(
                otherSenderId,
                "UNKNOWN",
                NOW.minusSeconds(600)
        );

        var results = service.findForSender(
                senderId,
                STALE_BEFORE,
                10
        );

        assertTrue(results.isEmpty());
    }

    @Test
    void resultsAreOrderedAndLimited() {

        UUID olderKey = insert(
                senderId,
                "UNKNOWN",
                NOW.minusSeconds(900)
        );

        insert(
                senderId,
                "UNKNOWN",
                NOW.minusSeconds(600)
        );

        List<CustomerTransferReconciliationQueryService
                .UnresolvedTransfer> results =
                service.findForSender(
                        senderId,
                        STALE_BEFORE,
                        1
                );

        assertEquals(1, results.size());

        assertEquals(
                olderKey,
                results.get(0).requestKey()
        );
    }

    @Test
    void invalidLimitIsRejected() {

        assertThrows(
                IllegalArgumentException.class,
                () -> service.findForSender(
                        senderId,
                        STALE_BEFORE,
                        0
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.findForSender(
                        senderId,
                        STALE_BEFORE,
                        101
                )
        );
    }
}
