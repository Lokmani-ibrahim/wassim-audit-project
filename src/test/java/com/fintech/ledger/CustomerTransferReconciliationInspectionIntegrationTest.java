
package com.fintech.ledger;

import com.fintech.ledger.adapter.formance.FormanceInternalTransferEvidenceVerifier;
import com.fintech.ledger.adapter.formance.FormanceInternalTransferEvidenceVerifier.ExpectedTransfer;
import com.fintech.ledger.adapter.formance.FormanceInternalTransferEvidenceVerifier.Finding;
import com.fintech.ledger.adapter.formance.FormanceInternalTransferEvidenceVerifier.Result;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.transfers.customer-api-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class CustomerTransferReconciliationInspectionIntegrationTest {

    private static final Instant NOW =
            Instant.parse("2026-10-30T20:00:00Z");

    private static final Instant STALE_BEFORE =
            NOW.minusSeconds(300);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CustomerTransferReconciliationInspectionService service;

    // Only the external Formance evidence lookup is mocked.
    // The reconciliation query uses real PostgreSQL.
    @MockitoBean(enforceOverride = true)
    private FormanceInternalTransferEvidenceVerifier verifier;

    private final UUID senderId = UUID.randomUUID();
    private final UUID otherSenderId = UUID.randomUUID();
    private final UUID recipientId = UUID.randomUUID();

    private String operationId(UUID requestKey) {
        return "ct_" + requestKey.toString().replace("-", "");
    }

    private UUID insert(
            UUID sender,
            String status,
            Instant updatedAt
    ) {

        UUID requestKey = UUID.randomUUID();

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
                requestKey,
                recipientId,
                "EUR",
                5_000L,
                operationId(requestKey),
                status,
                transactionId,
                reference,
                Timestamp.from(updatedAt.minusSeconds(60)),
                Timestamp.from(updatedAt)
        );

        return requestKey;
    }

    private String persistedStatus(UUID sender, UUID requestKey) {

        return jdbc.queryForObject(
                """
                SELECT status
                FROM customer_transfer_idempotency
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                String.class,
                sender,
                requestKey
        );
    }

    private ExpectedTransfer expected(UUID requestKey) {

        return new ExpectedTransfer(
                senderId,
                recipientId,
                LedgerCurrency.EUR,
                5_000L,
                operationId(requestKey)
        );
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
    void verifiedTransferDoesNotAutomaticallyChangeDatabase() {

        UUID key = insert(
                senderId,
                "UNKNOWN",
                NOW
        );

        when(verifier.inspect(eq(expected(key))))
                .thenReturn(
                        new Result(
                                Finding.MATCHED,
                                9101L,
                                "Verified ledger posting"
                        )
                );

        var inspections = service.inspectForSender(
                senderId,
                STALE_BEFORE,
                10
        );

        assertEquals(1, inspections.size());

        var result = inspections.get(0);

        assertEquals(key, result.requestKey());

        assertEquals(
                CustomerTransferReconciliationInspectionService
                        .Finding.MATCHED,
                result.finding()
        );

        assertEquals(
                9101L,
                result.formanceTransactionId()
        );

        // No automatic database reconciliation.
        assertEquals(
                "UNKNOWN",
                persistedStatus(senderId, key)
        );

        verify(verifier).inspect(expected(key));
    }

    @Test
    void notFoundDoesNotAuthorizeRetry() {

        UUID key = insert(
                senderId,
                "UNKNOWN",
                NOW
        );

        when(verifier.inspect(any(ExpectedTransfer.class)))
                .thenReturn(
                        new Result(
                                Finding.NOT_FOUND_UNCONFIRMED,
                                null,
                                "No confirmed transaction found"
                        )
                );

        var inspections = service.inspectForSender(
                senderId,
                STALE_BEFORE,
                10
        );

        assertEquals(1, inspections.size());

        assertEquals(
                CustomerTransferReconciliationInspectionService
                        .Finding.NOT_FOUND_UNCONFIRMED,
                inspections.get(0).finding()
        );

        assertNull(
                inspections.get(0).formanceTransactionId()
        );

        // Still blocked until formally reconciled.
        assertEquals(
                "UNKNOWN",
                persistedStatus(senderId, key)
        );
    }

    @Test
    void staleInProgressIsInspectedButRecentOneIsSkipped() {

        UUID staleKey = insert(
                senderId,
                "IN_PROGRESS",
                NOW.minusSeconds(600)
        );

        UUID recentKey = insert(
                senderId,
                "IN_PROGRESS",
                NOW
        );

        when(verifier.inspect(any(ExpectedTransfer.class)))
                .thenReturn(
                        new Result(
                                Finding.INCONCLUSIVE,
                                null,
                                "Insufficient evidence"
                        )
                );

        var inspections = service.inspectForSender(
                senderId,
                STALE_BEFORE,
                10
        );

        assertEquals(1, inspections.size());

        assertEquals(
                staleKey,
                inspections.get(0).requestKey()
        );

        verify(verifier, times(1))
                .inspect(any(ExpectedTransfer.class));

        assertEquals(
                "IN_PROGRESS",
                persistedStatus(senderId, staleKey)
        );

        assertEquals(
                "IN_PROGRESS",
                persistedStatus(senderId, recentKey)
        );
    }

    @Test
    void successfulAndOtherCustomersTransfersAreExcluded() {

        insert(
                senderId,
                "SUCCEEDED",
                NOW.minusSeconds(600)
        );

        insert(
                otherSenderId,
                "UNKNOWN",
                NOW.minusSeconds(600)
        );

        var inspections = service.inspectForSender(
                senderId,
                STALE_BEFORE,
                10
        );

        assertTrue(inspections.isEmpty());

        verifyNoInteractions(verifier);
    }

    @Test
    void formanceLookupFailureDoesNotChangeFinancialState() {

        UUID key = insert(
                senderId,
                "UNKNOWN",
                NOW
        );

        when(verifier.inspect(any(ExpectedTransfer.class)))
                .thenThrow(
                        new IllegalStateException(
                                "Simulated Formance outage"
                        )
                );

        var inspections = service.inspectForSender(
                senderId,
                STALE_BEFORE,
                10
        );

        assertEquals(1, inspections.size());

        assertEquals(
                CustomerTransferReconciliationInspectionService
                        .Finding.LOOKUP_FAILED,
                inspections.get(0).finding()
        );

        assertNull(
                inspections.get(0).formanceTransactionId()
        );

        assertEquals(
                "UNKNOWN",
                persistedStatus(senderId, key)
        );
    }

    @Test
    void invalidInspectionLimitIsRejected() {

        assertThrows(
                IllegalArgumentException.class,
                () -> service.inspectForSender(
                        senderId,
                        STALE_BEFORE,
                        0
                )
        );

        verifyNoInteractions(verifier);
    }
}
