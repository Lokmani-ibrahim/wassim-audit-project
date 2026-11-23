
package com.fintech.ledger;

import com.fintech.ledger.adapter.formance.FormanceInternalTransferEvidenceVerifier;
import com.fintech.ledger.adapter.formance.FormanceInternalTransferEvidenceVerifier.ExpectedTransfer;
import com.fintech.ledger.adapter.formance.FormanceInternalTransferEvidenceVerifier.Finding;
import com.fintech.ledger.adapter.formance.FormanceInternalTransferEvidenceVerifier.Result;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.notifications.FinancialTransactionNotificationService;
import com.fintech.notifications.TransactionNotificationDirection;
import com.fintech.notifications.TransactionNotificationType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class CustomerTransferReconciliationNotificationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CustomerTransferManualReconciliationService service;

    @MockitoBean(enforceOverride = true)
    private FormanceInternalTransferEvidenceVerifier verifier;

    @MockitoBean(enforceOverride = true)
    private FinancialTransactionNotificationService notifications;

    private final UUID senderId = UUID.randomUUID();
    private final UUID recipientId = UUID.randomUUID();
    private final UUID requestKey = UUID.randomUUID();

    private String operationId() {
        return "ct_"
                + senderId.toString().replace("-", "")
                + "_"
                + requestKey.toString().replace("-", "");
    }

    private String reference() {
        return "internal_transfer:" + operationId();
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

    private String storedStatus() {
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

    private void matchingEvidence() {
        when(verifier.inspect(expected())).thenReturn(
                new Result(
                        Finding.MATCHED,
                        23L,
                        "Verified Formance posting"
                )
        );
    }

    private void verifyBothNotifications() {
        verify(notifications).enqueueSafely(
                senderId,
                23L,
                reference(),
                TransactionNotificationType.TRANSFER,
                TransactionNotificationDirection.DEBIT,
                "EUR",
                LedgerCurrency.EUR.formanceAsset(),
                5_000L
        );

        verify(notifications).enqueueSafely(
                recipientId,
                23L,
                reference(),
                TransactionNotificationType.TRANSFER,
                TransactionNotificationDirection.CREDIT,
                "EUR",
                LedgerCurrency.EUR.formanceAsset(),
                5_000L
        );
    }

    @Test
    @WithMockUser(
            username = "ops-reconciliation-reviewer",
            authorities = "ROLE_RECONCILIATION_ADMIN"
    )
    void confirmedTransferEnqueuesBothNotificationsAfterCommit() {
        matchingEvidence();

        // Verify the status is committed when the
        // notification service is first invoked.
        doAnswer(invocation -> {
            assertEquals("SUCCEEDED", storedStatus());
            assertEquals(1, auditCount());
            return null;
        }).when(notifications).enqueueSafely(
                any(UUID.class),
                anyLong(),
                anyString(),
                eq(TransactionNotificationType.TRANSFER),
                any(TransactionNotificationDirection.class),
                anyString(),
                anyString(),
                anyLong()
        );

        var result = service.confirmPostedTransfer(
                senderId,
                requestKey
        );

        assertEquals(23L, result.transactionId());
        assertEquals(reference(), result.reference());
        assertEquals("SUCCEEDED", storedStatus());
        assertEquals(1, auditCount());

        verifyBothNotifications();
        verifyNoMoreInteractions(notifications);

        verify(verifier, times(1)).inspect(expected());
    }

    @Test
    @WithMockUser(
            authorities = "ROLE_RECONCILIATION_ADMIN"
    )
    void insufficientEvidenceNeverEnqueuesNotifications() {
        when(verifier.inspect(expected())).thenReturn(
                new Result(
                        Finding.NOT_FOUND_UNCONFIRMED,
                        null,
                        "No confirmed posting"
                )
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.confirmPostedTransfer(
                        senderId,
                        requestKey
                )
        );

        assertEquals(
                HttpStatus.CONFLICT,
                exception.getStatusCode()
        );

        assertEquals("UNKNOWN", storedStatus());
        assertEquals(0, auditCount());
        verifyNoInteractions(notifications);
    }

    @Test
    @WithMockUser(
            authorities = "ROLE_RECONCILIATION_ADMIN"
    )
    void notificationFailureDoesNotUndoReconciliation() {
        matchingEvidence();

        doThrow(
                new IllegalStateException("Outbox unavailable")
        ).when(notifications).enqueueSafely(
                senderId,
                23L,
                reference(),
                TransactionNotificationType.TRANSFER,
                TransactionNotificationDirection.DEBIT,
                "EUR",
                LedgerCurrency.EUR.formanceAsset(),
                5_000L
        );

        assertDoesNotThrow(
                () -> service.confirmPostedTransfer(
                        senderId,
                        requestKey
                )
        );

        assertEquals("SUCCEEDED", storedStatus());
        assertEquals(1, auditCount());

        // A failed sender notification must not prevent
        // the recipient notification from being attempted.
        verifyBothNotifications();
    }

    @Test
    @WithMockUser(
            authorities = "ROLE_RECONCILIATION_ADMIN"
    )
    void repeatedConfirmationDoesNotReenqueueNotifications() {
        matchingEvidence();

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

        assertEquals("SUCCEEDED", storedStatus());
        assertEquals(1, auditCount());

        verifyBothNotifications();
        verifyNoMoreInteractions(notifications);
        verify(verifier, times(1)).inspect(expected());
    }
}
