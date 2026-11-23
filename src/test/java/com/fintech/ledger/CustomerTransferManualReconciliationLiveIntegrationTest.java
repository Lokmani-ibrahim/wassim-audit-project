
package com.fintech.ledger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.transfers.customer-api-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
@EnabledIfEnvironmentVariable(
        named = "RUN_FORMANCE_RECON_IT",
        matches = "true"
)
class CustomerTransferManualReconciliationLiveIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CustomerTransferManualReconciliationService service;

    private final UUID requestKey = UUID.randomUUID();

    @DynamicPropertySource
    static void formanceProperties(DynamicPropertyRegistry registry) {

        registry.add(
                "neos.ledger.formance.base-url",
                () -> env("FORMANCE_BASE_URL", "http://localhost:8080")
        );

        registry.add(
                "neos.ledger.formance.ledger-name",
                () -> env("FORMANCE_LEDGER_NAME", "neos")
        );

        registry.add(
                "neos.ledger.formance.schema-version",
                () -> env("FORMANCE_SCHEMA_VERSION", "v1.1.0")
        );

        registry.add(
                "neos.ledger.formance.connect-timeout",
                () -> "2s"
        );

        registry.add(
                "neos.ledger.formance.read-timeout",
                () -> "10s"
        );
    }

    private static String env(String name, String defaultValue) {
        return System.getenv().getOrDefault(name, defaultValue);
    }

    private UUID senderId() {
        return UUID.fromString(env(
                "FORMANCE_LOOKUP_SENDER_ID",
                "00000000-0000-0000-0000-00000000c201"
        ));
    }

    private UUID recipientId() {
        return UUID.fromString(env(
                "FORMANCE_LOOKUP_RECIPIENT_ID",
                "00000000-0000-0000-0000-00000000c202"
        ));
    }

    private String operationId() {
        return env(
                "FORMANCE_LOOKUP_OPERATION_ID",
                "c1_transfer_002"
        );
    }

    private long amountMinor() {
        return Long.parseLong(env(
                "FORMANCE_LOOKUP_AMOUNT_MINOR",
                "500"
        ));
    }

    @BeforeEach
    void verifyDevelopmentDatabase() {

        String database = jdbc.queryForObject(
                "SELECT current_database()",
                String.class
        );

        assertEquals(
                "fintech_dev",
                database,
                "This integration test must run against "
                        + "the local development database only"
        );
    }

    private void insertUnknownClaim(long amount) {

        jdbc.update(
                """
                INSERT INTO customer_transfer_idempotency (
                    sender_user_id,
                    request_key,
                    recipient_user_id,
                    currency,
                    amount_minor,
                    operation_id,
                    status
                )
                VALUES (?, ?, ?, 'EUR', ?, ?, 'UNKNOWN')
                """,
                senderId(),
                requestKey,
                recipientId(),
                amount,
                operationId()
        );
    }

    private Map<String, Object> transferRow() {

        return jdbc.queryForMap(
                """
                SELECT
                    status,
                    ledger_transaction_id,
                    ledger_reference
                FROM customer_transfer_idempotency
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                senderId(),
                requestKey
        );
    }

    private int auditCount() {

        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM customer_transfer_reconciliation_audit
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                Integer.class,
                senderId(),
                requestKey
        );

        return count == null ? 0 : count;
    }

    @AfterEach
    void cleanup() {

        // Remove only the temporary test record.
        // Never delete all records for this sender.
        jdbc.update(
                """
                DELETE FROM customer_transfer_reconciliation_audit
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                senderId(),
                requestKey
        );

        jdbc.update(
                """
                DELETE FROM customer_transfer_idempotency
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                senderId(),
                requestKey
        );
    }

    @Test
    @WithMockUser(
            username = "ops-live-reconciler",
            authorities = "ROLE_RECONCILIATION_ADMIN"
    )
    void realFormancePostingCanBeReconciledAndAudited() {

        // Create a temporary UNKNOWN claim for an
        // internal transfer already present in Formance.
        insertUnknownClaim(amountMinor());

        assertEquals(
                "UNKNOWN",
                transferRow().get("status")
        );

        assertEquals(0, auditCount());

        // This calls the REAL Formance client and verifier.
        // No mocked ledger evidence.
        var result = service.confirmPostedTransfer(
                senderId(),
                requestKey
        );

        assertNotNull(result);
        assertTrue(result.transactionId() >= 0);

        String expectedReference =
                "internal_transfer:" + operationId();

        assertEquals(
                expectedReference,
                result.reference()
        );

        // PostgreSQL must now contain the confirmed result.
        Map<String, Object> row = transferRow();

        assertEquals(
                "SUCCEEDED",
                row.get("status")
        );

        assertEquals(
                result.transactionId(),
                ((Number) row.get("ledger_transaction_id"))
                        .longValue()
        );

        assertEquals(
                expectedReference,
                row.get("ledger_reference")
        );

        // The confirmation must be audited.
        assertEquals(1, auditCount());

        Map<String, Object> audit = jdbc.queryForMap(
                """
                SELECT
                    action,
                    previous_status,
                    resulting_status,
                    formance_transaction_id,
                    actor_subject
                FROM customer_transfer_reconciliation_audit
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                senderId(),
                requestKey
        );

        assertEquals("CONFIRM_POSTED", audit.get("action"));
        assertEquals("UNKNOWN", audit.get("previous_status"));
        assertEquals("SUCCEEDED", audit.get("resulting_status"));

        assertEquals(
                result.transactionId(),
                ((Number) audit.get("formance_transaction_id"))
                        .longValue()
        );

        assertEquals(
                "ops-live-reconciler",
                audit.get("actor_subject")
        );

        System.out.println(
                "Real Formance reconciliation succeeded. "
                        + "Transaction ID: " + result.transactionId()
        );
    }

    @Test
    @WithMockUser(
            username = "ops-live-reconciler",
            authorities = "ROLE_RECONCILIATION_ADMIN"
    )
    void incorrectAmountCannotBeReconciled() {

        // Deliberately store an incorrect amount for the
        // existing historical Formance transaction.
        insertUnknownClaim(amountMinor() + 1);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPostedTransfer(
                        senderId(),
                        requestKey
                )
        );

        assertEquals(
                HttpStatus.CONFLICT,
                error.getStatusCode()
        );

        // No false financial confirmation.
        Map<String, Object> row = transferRow();

        assertEquals("UNKNOWN", row.get("status"));

        assertNull(row.get("ledger_transaction_id"));
        assertNull(row.get("ledger_reference"));

        assertEquals(0, auditCount());
    }

    @Test
    @WithMockUser(
            username = "ops-live-reconciler",
            authorities = "ROLE_RECONCILIATION_ADMIN"
    )
    void realStaleInProgressTransferCanBeReconciled() {

        // STEP 1: Create a disposable PostgreSQL claim
        // representing an interrupted financial operation.
        insertUnknownClaim(amountMinor());

        int updated = jdbc.update(
                """
                UPDATE customer_transfer_idempotency
                SET status = 'IN_PROGRESS',
                    updated_at = NOW() - INTERVAL '30 minutes'
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                senderId(),
                requestKey
        );

        assertEquals(1, updated);
        assertEquals("IN_PROGRESS", transferRow().get("status"));

        // STEP 2: Verify the historical transfer in REAL Formance.
        // No new financial posting is created.
        var result = service.confirmPostedTransfer(
                senderId(),
                requestKey
        );

        assertNotNull(result);
        assertTrue(result.transactionId() >= 0);

        // STEP 3: The persisted transfer must now be SUCCEEDED.
        var row = transferRow();

        assertEquals("SUCCEEDED", row.get("status"));

        assertEquals(
                result.transactionId(),
                ((Number) row.get("ledger_transaction_id"))
                        .longValue()
        );

        assertEquals(
                "internal_transfer:" + operationId(),
                row.get("ledger_reference")
        );

        // STEP 4: Verify that reconciliation recorded
        // the actual previous state, IN_PROGRESS.
        assertEquals(1, auditCount());

        var audit = jdbc.queryForMap(
                """
                SELECT
                    previous_status,
                    resulting_status,
                    formance_transaction_id,
                    actor_subject
                FROM customer_transfer_reconciliation_audit
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                senderId(),
                requestKey
        );

        assertEquals(
                "IN_PROGRESS",
                audit.get("previous_status")
        );

        assertEquals(
                "SUCCEEDED",
                audit.get("resulting_status")
        );

        assertEquals(
                result.transactionId(),
                ((Number) audit.get("formance_transaction_id"))
                        .longValue()
        );

        assertEquals(
                "ops-live-reconciler",
                audit.get("actor_subject")
        );

        System.out.println(
                "Stale IN_PROGRESS reconciled using "
                        + "existing Formance transaction "
                        + result.transactionId()
        );
    }

    @Test
    @WithMockUser(
            username = "ops-live-reconciler",
            authorities = "ROLE_RECONCILIATION_ADMIN"
    )
    void incorrectStaleInProgressTransferCannotBeConfirmed() {

        // The actual historical transaction has a
        // different amount from this temporary claim.
        insertUnknownClaim(amountMinor() + 1);

        jdbc.update(
                """
                UPDATE customer_transfer_idempotency
                SET status = 'IN_PROGRESS',
                    updated_at = NOW() - INTERVAL '30 minutes'
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                senderId(),
                requestKey
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPostedTransfer(
                        senderId(),
                        requestKey
                )
        );

        assertEquals(
                HttpStatus.CONFLICT,
                error.getStatusCode()
        );

        // The financial claim stays blocked.
        var row = transferRow();

        assertEquals("IN_PROGRESS", row.get("status"));
        assertNull(row.get("ledger_transaction_id"));
        assertNull(row.get("ledger_reference"));

        // No false reconciliation audit.
        assertEquals(0, auditCount());
    }

}
