
package com.fintech.ledger;

import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.repository.KycStatusRepository;
import com.fintech.ledger.adapter.formance.FormancePayoutEvidenceVerifier;
import com.fintech.ledger.adapter.formance.FormancePayoutEvidenceVerifier.*;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.notifications.FinancialTransactionNotificationService;
import com.fintech.notifications.TransactionNotificationDirection;
import com.fintech.notifications.TransactionNotificationType;

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
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "neos.payouts.reconciliation-enabled=true",
        "neos.payouts.ledger-finalization-enabled=false",
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.backoffice.synthetic-credit.enabled=false",
        "neos.transfers.customer-api-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class PayoutManualReconciliationIntegrationTest {

    @Autowired
    private PayoutManualReconciliationService reconciliation;

    @Autowired
    private PayoutRequestAdmissionService admission;

    @Autowired
    private UserRepository users;

    @Autowired
    private KycStatusRepository kycStatuses;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean(enforceOverride = true)
    private FormancePayoutEvidenceVerifier verifier;

    @MockitoBean(enforceOverride = true)
    private FinancialTransactionNotificationService notifications;

    private UUID userId;
    private UUID payoutId;
    private String operationId;

    @BeforeEach
    void setup() {

        String unique = UUID.randomUUID().toString();

        UserEntity user = new UserEntity(
                "payout-recon-" + unique,
                "payout-recon-" + unique + "@example.com",
                null
        );

        user.activate();

        userId = users.saveAndFlush(user).getId();

        KycStatusEntity kyc = new KycStatusEntity(userId);

        kyc.startVerification(
                "SUMSUB",
                "sandbox-" + unique
        );
        kyc.approve();

        kycStatuses.saveAndFlush(kyc);

        var request = admission.admit(
                userId,
                UUID.randomUUID(),
                LedgerCurrency.EUR,
                5_000L,
                "sponsor"
        );

        payoutId = request.payoutId();
        operationId = request.operationId();

        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "payout-reviewer",
                                "unused",
                                List.of(new SimpleGrantedAuthority(
                                        "ROLE_RECONCILIATION_ADMIN"
                                ))
                        )
                );
    }

    private void unknownReservation() {

        jdbc.update(
                """
                UPDATE payout_requests
                SET status = 'RESERVE_UNKNOWN',
                    updated_at = NOW()
                WHERE payout_id = ?
                """,
                payoutId
        );
    }

    private void reserved() {

        jdbc.update(
                """
                UPDATE payout_requests
                SET status = 'RESERVED',
                    reserve_ledger_transaction_id = 601,
                    reserve_ledger_reference = ?,
                    updated_at = NOW()
                WHERE payout_id = ?
                """,
                "payout_reserve:" + operationId,
                payoutId
        );
    }

    private void unknownFinalization(String state) {

        reserved();

        jdbc.update(
                """
                UPDATE payout_requests
                SET status = ?,
                    updated_at = NOW()
                WHERE payout_id = ?
                """,
                state,
                payoutId
        );
    }

    private void matched() {

        when(verifier.inspect(any(ExpectedPayout.class)))
                .thenReturn(new Result(
                        Finding.MATCHED,
                        900L,
                        "Exact Formance transaction matched"
                ));
    }

    private String status() {

        return jdbc.queryForObject(
                """
                SELECT status
                FROM payout_requests
                WHERE payout_id = ?
                """,
                String.class,
                payoutId
        );
    }

    private int auditCount() {

        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM payout_reconciliation_audit
                WHERE payout_id = ?
                """,
                Integer.class,
                payoutId
        );

        return count == null ? 0 : count;
    }

    @AfterEach
    void cleanup() {

        SecurityContextHolder.clearContext();

        if (userId == null) {
            return;
        }

        // Delete test fixtures in foreign-key order.
        // Production reconciliation never deletes audit rows.
        jdbc.update(
                "DELETE FROM payout_reconciliation_audit WHERE payout_id = ?",
                payoutId
        );

        jdbc.update(
                "DELETE FROM payout_requests WHERE user_id = ?",
                userId
        );

        jdbc.update(
                "DELETE FROM kyc_status WHERE user_id = ?",
                userId
        );

        jdbc.update(
                "DELETE FROM users WHERE id = ?",
                userId
        );
    }

    @Test
    void unknownReserveCanBeConfirmedExactlyOnce() {

        unknownReservation();
        matched();

        var result = reconciliation.confirmPosted(payoutId);

        assertEquals(900L, result.transactionId());
        assertEquals(
                "payout_reserve:" + operationId,
                result.reference()
        );

        assertEquals("RESERVED", status());
        assertEquals(1, auditCount());

        assertThrows(
                ResponseStatusException.class,
                () -> reconciliation.confirmPosted(payoutId)
        );

        verify(verifier, times(1))
                .inspect(any(ExpectedPayout.class));

        verifyNoInteractions(notifications);
    }

    @Test
    void unknownSettlementIsConfirmedAndNotifiedAfterCommit() {

        unknownFinalization("SETTLE_UNKNOWN");
        matched();

        doAnswer(invocation -> {

            // Notification is not enqueued until the
            // database transaction is committed.
            assertEquals("LEDGER_SETTLED", status());
            assertEquals(1, auditCount());

            return null;

        }).when(notifications).enqueueSafely(
                userId,
                900L,
                "payout_settle:" + operationId,
                TransactionNotificationType.PAYOUT,
                TransactionNotificationDirection.DEBIT,
                LedgerCurrency.EUR.currencyCode(),
                LedgerCurrency.EUR.formanceAsset(),
                5_000L
        );

        reconciliation.confirmPosted(payoutId);

        assertEquals("LEDGER_SETTLED", status());
        assertEquals(1, auditCount());

        String action = jdbc.queryForObject(
                """
                SELECT action
                FROM payout_reconciliation_audit
                WHERE payout_id = ?
                """,
                String.class,
                payoutId
        );

        assertEquals("SETTLE", action);

        verify(notifications, times(1)).enqueueSafely(
                userId,
                900L,
                "payout_settle:" + operationId,
                TransactionNotificationType.PAYOUT,
                TransactionNotificationDirection.DEBIT,
                LedgerCurrency.EUR.currencyCode(),
                LedgerCurrency.EUR.formanceAsset(),
                5_000L
        );
    }

    @Test
    void unknownReleaseCanBeConfirmedWithoutDebitNotification() {

        unknownFinalization("RELEASE_UNKNOWN");
        matched();

        reconciliation.confirmPosted(payoutId);

        assertEquals("RELEASED", status());
        assertEquals(1, auditCount());

        verifyNoInteractions(notifications);
    }

    @Test
    void missingEvidenceDoesNotChangePayout() {

        unknownFinalization("SETTLE_UNKNOWN");

        when(verifier.inspect(any(ExpectedPayout.class)))
                .thenReturn(new Result(
                        Finding.NOT_FOUND_UNCONFIRMED,
                        null,
                        "No matching transaction"
                ));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> reconciliation.confirmPosted(payoutId)
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals("SETTLE_UNKNOWN", status());
        assertEquals(0, auditCount());

        verifyNoInteractions(notifications);
    }

    @Test
    void freshInProgressCannotBeConfirmed() {

        reserved();

        jdbc.update(
                """
                UPDATE payout_requests
                SET status = 'SETTLE_IN_PROGRESS',
                    updated_at = NOW()
                WHERE payout_id = ?
                """,
                payoutId
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> reconciliation.confirmPosted(payoutId)
        );

        assertEquals(409, error.getStatusCode().value());

        verifyNoInteractions(verifier);
        assertEquals(0, auditCount());
    }

    @Test
    void staleInProgressCanBeConfirmedWithEvidence() {

        reserved();

        jdbc.update(
                """
                UPDATE payout_requests
                SET status = 'SETTLE_IN_PROGRESS',
                    updated_at = NOW() - INTERVAL '16 minutes'
                WHERE payout_id = ?
                """,
                payoutId
        );

        matched();

        reconciliation.confirmPosted(payoutId);

        assertEquals("LEDGER_SETTLED", status());
        assertEquals(1, auditCount());
    }

    @Test
    void payoutMutationDuringLookupBlocksConfirmation() {

        unknownReservation();

        when(verifier.inspect(any(ExpectedPayout.class)))
                .thenAnswer(invocation -> {

                    // Simulate a concurrent modification
                    // between read-only lookup and DB lock.
                    jdbc.update(
                            """
                            UPDATE payout_requests
                            SET updated_at =
                                updated_at + INTERVAL '1 second'
                            WHERE payout_id = ?
                            """,
                            payoutId
                    );

                    return new Result(
                            Finding.MATCHED,
                            900L,
                            "Exact match"
                    );
                });

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> reconciliation.confirmPosted(payoutId)
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals("RESERVE_UNKNOWN", status());
        assertEquals(0, auditCount());
    }

    @Test
    void reviewerMustHaveReconciliationAdminAuthority() {

        unknownReservation();

        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "ordinary-customer",
                                "unused",
                                List.of(new SimpleGrantedAuthority(
                                        "ROLE_CUSTOMER"
                                ))
                        )
                );

        assertThrows(
                AccessDeniedException.class,
                () -> reconciliation.confirmPosted(payoutId)
        );

        verifyNoInteractions(verifier);
        assertEquals("RESERVE_UNKNOWN", status());
        assertEquals(0, auditCount());
    }
}
