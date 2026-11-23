
package com.fintech.ledger;

import com.fintech.ledger.adapter.formance.FormanceInternalTransferEvidenceVerifier;
import com.fintech.ledger.adapter.formance.FormanceInternalTransferEvidenceVerifier.ExpectedTransfer;
import com.fintech.ledger.adapter.formance.FormanceInternalTransferEvidenceVerifier.Finding;
import com.fintech.ledger.adapter.formance.FormanceInternalTransferEvidenceVerifier.Result;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.transfers.customer-api-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class CustomerTransferManualReconciliationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CustomerTransferManualReconciliationService service;

    @MockitoBean(enforceOverride = true)
    private FormanceInternalTransferEvidenceVerifier verifier;

    private final UUID senderId = UUID.randomUUID();
    private final UUID recipientId = UUID.randomUUID();
    private final UUID requestKey = UUID.randomUUID();

    private String operationId() {
        return "ct_"
                + senderId.toString().replace("-", "")
                + "_"
                + requestKey.toString().replace("-", "");
    }

    private ExpectedTransfer expected() {
        return new ExpectedTransfer(
                senderId,
                recipientId,
                LedgerCurrency.EUR,
                5_000L,
                operationId()
        );
    }

    @BeforeEach
    void setup() {
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
                VALUES (?, ?, ?, 'EUR', 5000, ?, 'UNKNOWN')
                """,
                senderId,
                requestKey,
                recipientId,
                operationId()
        );
    }

    @AfterEach
    void cleanup() {
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
    }

    private String status() {
        return jdbc.queryForObject(
                """
                SELECT status
                FROM customer_transfer_idempotency
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                String.class,
                senderId,
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
                senderId,
                requestKey
        );

        return count == null ? 0 : count;
    }

    private void mockConfirmedPosting() {
        when(verifier.inspect(expected())).thenReturn(
                new Result(
                        Finding.MATCHED,
                        23L,
                        "Verified historical posting"
                )
        );
    }

    @Test
    @WithMockUser(
            username = "ops-reviewer-001",
            authorities = "ROLE_RECONCILIATION_ADMIN"
    )
    void matchingEvidenceUpdatesStatusAndWritesAudit() {

        mockConfirmedPosting();

        var result = service.confirmPostedTransfer(
                senderId,
                requestKey
        );

        assertEquals(23L, result.transactionId());
        assertEquals(
                "internal_transfer:" + operationId(),
                result.reference()
        );

        assertEquals("SUCCEEDED", status());
        assertEquals(1, auditCount());

        String actor = jdbc.queryForObject(
                """
                SELECT actor_subject
                FROM customer_transfer_reconciliation_audit
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                String.class,
                senderId,
                requestKey
        );

        assertEquals("ops-reviewer-001", actor);

        verify(verifier).inspect(expected());
    }

    @Test
    @WithMockUser(
            authorities = "ROLE_RECONCILIATION_ADMIN"
    )
    void missingLedgerEvidenceCannotConfirm() {

        when(verifier.inspect(expected())).thenReturn(
                new Result(
                        Finding.NOT_FOUND_UNCONFIRMED,
                        null,
                        "No matching posting"
                )
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPostedTransfer(
                        senderId,
                        requestKey
                )
        );

        assertEquals(
                HttpStatus.CONFLICT,
                error.getStatusCode()
        );

        assertEquals("UNKNOWN", status());
        assertEquals(0, auditCount());
    }

    @Test
    @WithMockUser(
            authorities = "ROLE_RECONCILIATION_ADMIN"
    )
    void inProgressCannotBeReconciledByThisWorkflow() {

        jdbc.update(
                """
                UPDATE customer_transfer_idempotency
                SET status = 'IN_PROGRESS'
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                senderId,
                requestKey
        );

        assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPostedTransfer(
                        senderId,
                        requestKey
                )
        );

        assertEquals("IN_PROGRESS", status());
        assertEquals(0, auditCount());

        verifyNoInteractions(verifier);
    }

    @Test
    @WithMockUser(authorities = "ROLE_CUSTOMER")
    void customerCannotReconcileTheirOwnTransfer() {

        assertThrows(
                AccessDeniedException.class,
                () -> service.confirmPostedTransfer(
                        senderId,
                        requestKey
                )
        );

        assertEquals("UNKNOWN", status());
        assertEquals(0, auditCount());

        verifyNoInteractions(verifier);
    }

    @Test
    @WithMockUser(
            authorities = "ROLE_RECONCILIATION_ADMIN"
    )
    void successfulReconciliationCannotBeRepeated() {

        mockConfirmedPosting();

        service.confirmPostedTransfer(
                senderId,
                requestKey
        );

        assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPostedTransfer(
                        senderId,
                        requestKey
                )
        );

        assertEquals("SUCCEEDED", status());
        assertEquals(1, auditCount());

        verify(verifier, times(1)).inspect(expected());
    }


    @Test
    @WithMockUser(
            authorities = "ROLE_RECONCILIATION_ADMIN"
    )
    void concurrentDatabaseChangeInvalidatesEvidenceSnapshot() {

        // Arrange: an abandoned transfer older than 15 minutes.
        jdbc.update(
                """
                UPDATE customer_transfer_idempotency
                SET status = 'IN_PROGRESS',
                    updated_at = NOW() - INTERVAL '30 minutes'
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                senderId,
                requestKey
        );

        when(verifier.inspect(expected())).thenAnswer(invocation -> {

            // Simulate a concurrent database modification
            // while external Formance evidence is being read.
            //
            // Change the AMOUNT rather than only updated_at.
            // The original evidence was verified for 5000.
            int changed = jdbc.update(
                    """
                    UPDATE customer_transfer_idempotency
                    SET amount_minor = amount_minor + 1,
                        updated_at = NOW()
                    WHERE sender_user_id = ?
                      AND request_key = ?
                    """,
                    senderId,
                    requestKey
            );

            assertEquals(
                    1,
                    changed,
                    "Concurrent modification must affect one row"
            );

            Long newAmount = jdbc.queryForObject(
                    """
                    SELECT amount_minor
                    FROM customer_transfer_idempotency
                    WHERE sender_user_id = ?
                      AND request_key = ?
                    """,
                    Long.class,
                    senderId,
                    requestKey
            );

            assertEquals(Long.valueOf(5001L), newAmount);

            return new Result(
                    Finding.MATCHED,
                    23L,
                    "Evidence for original amount"
            );
        });

        // The original amount and the current amount
        // now differ. Reconciliation must reject the snapshot.
        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPostedTransfer(
                        senderId,
                        requestKey
                )
        );

        assertEquals(
                HttpStatus.CONFLICT,
                error.getStatusCode()
        );

        assertEquals("IN_PROGRESS", status());
        assertEquals(0, auditCount());

        verify(verifier, times(1)).inspect(expected());
    }


    @Test
    @WithMockUser(
            username = "ops-reviewer-001",
            authorities = "ROLE_RECONCILIATION_ADMIN"
    )
    void auditInsertFailureRollsBackTransferConfirmation() {

        // STEP 1: Simulate an audit-table conflict.
        //
        // V9 enforces UNIQUE(sender_user_id, request_key).
        // Insert an artificial existing audit record so
        // the reconciliation audit INSERT will fail.
        //
        // This inconsistent fixture is for fault injection
        // only; it does not represent a valid business state.

        UUID existingAuditId = UUID.randomUUID();

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
                    ?,
                    ?
                )
                """,
                existingAuditId,
                senderId,
                requestKey,
                operationId(),
                17L,
                "existing-test-record"
        );

        assertEquals("UNKNOWN", status());
        assertEquals(1, auditCount());

        // STEP 2: Formance evidence is valid.
        // The failure must occur during audit persistence,
        // not during transaction verification.

        mockConfirmedPosting();

        // STEP 3: Reconciliation attempts to update the
        // transfer to SUCCEEDED, then insert its audit.
        //
        // The audit INSERT violates the unique constraint.
        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPostedTransfer(
                        senderId,
                        requestKey
                )
        );

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                failure.getStatusCode()
        );

        // STEP 4: Both changes must roll back together.
        // The transfer must still be UNKNOWN.
        assertEquals(
                "UNKNOWN",
                status(),
                "Audit failure must roll back the status update"
        );

        // STEP 5: The original audit remains unchanged.
        // No second audit record was committed.
        assertEquals(1, auditCount());

        UUID persistedAuditId = jdbc.queryForObject(
                """
                SELECT audit_id
                FROM customer_transfer_reconciliation_audit
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                UUID.class,
                senderId,
                requestKey
        );

        assertEquals(existingAuditId, persistedAuditId);

        // STEP 6: The failed transaction must not leave
        // a confirmed Formance transaction ID in PostgreSQL.
        var transferRow = jdbc.queryForMap(
                """
                SELECT
                    status,
                    ledger_transaction_id,
                    ledger_reference
                FROM customer_transfer_idempotency
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                senderId,
                requestKey
        );

        assertEquals(
                "UNKNOWN",
                transferRow.get("status")
        );

        assertNull(
                transferRow.get("ledger_transaction_id")
        );

        assertNull(
                transferRow.get("ledger_reference")
        );

        // STEP 7: The Formance evidence was checked once.
        // No ledger posting or transfer retry occurs here.
        verify(verifier, times(1)).inspect(expected());
    }

    @Test
    @WithMockUser(
            username = "ops-reviewer-001",
            authorities = "ROLE_RECONCILIATION_ADMIN"
    )
    void staleInProgressCanBeConfirmedWithVerifiedPosting() {

        jdbc.update(
                """
                UPDATE customer_transfer_idempotency
                SET status = 'IN_PROGRESS',
                    updated_at = NOW() - INTERVAL '30 minutes'
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                senderId,
                requestKey
        );

        mockConfirmedPosting();

        var result = service.confirmPostedTransfer(
                senderId,
                requestKey
        );

        assertEquals(23L, result.transactionId());
        assertEquals("SUCCEEDED", status());
        assertEquals(1, auditCount());

        String previousStatus = jdbc.queryForObject(
                """
                SELECT previous_status
                FROM customer_transfer_reconciliation_audit
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                String.class,
                senderId,
                requestKey
        );

        assertEquals("IN_PROGRESS", previousStatus);

        verify(verifier).inspect(expected());
    }

    @Test
    @WithMockUser(
            authorities = "ROLE_RECONCILIATION_ADMIN"
    )
    void recentInProgressCannotBeReconciled() {

        jdbc.update(
                """
                UPDATE customer_transfer_idempotency
                SET status = 'IN_PROGRESS',
                    updated_at = NOW()
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                senderId,
                requestKey
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPostedTransfer(
                        senderId,
                        requestKey
                )
        );

        assertEquals(
                HttpStatus.CONFLICT,
                error.getStatusCode()
        );

        assertEquals("IN_PROGRESS", status());
        assertEquals(0, auditCount());

        // No Formance lookup should occur for
        // a transfer that isn't stale.
        verifyNoInteractions(verifier);
    }

    @Test
    @WithMockUser(
            authorities = "ROLE_RECONCILIATION_ADMIN"
    )
    void staleInProgressWithoutEvidenceRemainsBlocked() {

        jdbc.update(
                """
                UPDATE customer_transfer_idempotency
                SET status = 'IN_PROGRESS',
                    updated_at = NOW() - INTERVAL '30 minutes'
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                senderId,
                requestKey
        );

        when(verifier.inspect(expected())).thenReturn(
                new Result(
                        Finding.NOT_FOUND_UNCONFIRMED,
                        null,
                        "No confirmed posting found"
                )
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPostedTransfer(
                        senderId,
                        requestKey
                )
        );

        assertEquals(
                HttpStatus.CONFLICT,
                error.getStatusCode()
        );

        assertEquals("IN_PROGRESS", status());
        assertEquals(0, auditCount());

        verify(verifier).inspect(expected());
    }

    @Test
    @WithMockUser(
            authorities = "ROLE_RECONCILIATION_ADMIN"
    )
    void concurrentRefreshInvalidatesStaleSnapshot() {

        jdbc.update(
                """
                UPDATE customer_transfer_idempotency
                SET status = 'IN_PROGRESS',
                    updated_at = NOW() - INTERVAL '30 minutes'
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                senderId,
                requestKey
        );

        when(verifier.inspect(expected())).thenAnswer(
                invocation -> {

                    // Simulate the original worker updating
                    // the claim during the Formance lookup.
                    jdbc.update(
                            """
                            UPDATE customer_transfer_idempotency
                            SET updated_at = NOW()
                            WHERE sender_user_id = ?
                              AND request_key = ?
                            """,
                            senderId,
                            requestKey
                    );

                    return new Result(
                            Finding.MATCHED,
                            23L,
                            "Verified posting"
                    );
                }
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPostedTransfer(
                        senderId,
                        requestKey
                )
        );

        assertEquals(
                HttpStatus.CONFLICT,
                error.getStatusCode()
        );

        // The database changed during evidence lookup.
        // The old snapshot must not be committed.
        assertEquals("IN_PROGRESS", status());
        assertEquals(0, auditCount());
    }


}
