
package com.fintech.ledger;

import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.repository.KycStatusRepository;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.port.LedgerProvider;
import com.fintech.notifications.FinancialTransactionNotificationService;
import com.fintech.notifications.TransactionNotificationDirection;
import com.fintech.notifications.TransactionNotificationType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "neos.payouts.ledger-finalization-enabled=true",
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.backoffice.synthetic-credit.enabled=false",
        "neos.transfers.customer-api-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class PayoutFinalizationExecutionIntegrationTest {

    @Autowired
    private PayoutRequestAdmissionService admission;

    @Autowired
    private PayoutReservationExecutionService reservation;

    @Autowired
    private PayoutFinalizationExecutionService finalization;

    @Autowired
    private UserRepository users;

    @Autowired
    private KycStatusRepository kycStatuses;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean(enforceOverride = true)
    private LedgerProvider ledgerProvider;

    @MockitoBean(enforceOverride = true)
    private FinancialTransactionNotificationService notifications;

    private UUID userId;
    private UUID payoutId;
    private String operationId;

    @BeforeEach
    void setup() {

        String unique = UUID.randomUUID().toString();

        UserEntity user = new UserEntity(
                "payout-finalization-" + unique,
                "payout-finalization-" + unique + "@example.com",
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

        var admitted = admission.admit(
                userId,
                UUID.randomUUID(),
                LedgerCurrency.EUR,
                5_000L,
                "sponsor"
        );

        payoutId = admitted.payoutId();
        operationId = admitted.operationId();

        when(ledgerProvider.reservePayout(
                userId,
                LedgerCurrency.EUR,
                5_000L,
                operationId
        )).thenReturn(
                new LedgerOperationResult(
                        601L,
                        "payout_reserve:" + operationId
                )
        );

        // Create a real persisted RESERVED state.
        reservation.reserve(payoutId);
        assertEquals("RESERVED", status());

        // Remove the setup call from interaction counts,
        // but retain the configured mock behavior.
        clearInvocations(ledgerProvider, notifications);
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

    private LedgerOperationResult settleResult() {

        return new LedgerOperationResult(
                701L,
                "payout_settle:" + operationId
        );
    }

    private LedgerOperationResult releaseResult() {

        return new LedgerOperationResult(
                702L,
                "payout_release:" + operationId
        );
    }

    private void stubSettlement() {

        when(ledgerProvider.settlePayout(
                userId,
                "sponsor",
                LedgerCurrency.EUR,
                5_000L,
                operationId
        )).thenReturn(settleResult());
    }

    private void stubRelease() {

        when(ledgerProvider.releasePayout(
                userId,
                LedgerCurrency.EUR,
                5_000L,
                operationId
        )).thenReturn(releaseResult());
    }

    private void assertConflict(Executable action) {

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                action
        );

        assertEquals(
                409,
                exception.getStatusCode().value()
        );
    }

    @AfterEach
    void cleanup() {

        if (userId == null) {
            return;
        }

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
    void settlementIsDurableIdempotentAndBlocksRelease() {

        stubSettlement();

        // Verify the notification only happens after
        // LEDGER_SETTLED has committed in PostgreSQL.
        doAnswer(invocation -> {
            assertEquals("LEDGER_SETTLED", status());
            return null;
        }).when(notifications).enqueueSafely(
                userId,
                701L,
                "payout_settle:" + operationId,
                TransactionNotificationType.PAYOUT,
                TransactionNotificationDirection.DEBIT,
                LedgerCurrency.EUR.currencyCode(),
                LedgerCurrency.EUR.formanceAsset(),
                5_000L
        );

        assertEquals(
                settleResult(),
                finalization.settleLedgerOnly(payoutId)
        );

        assertEquals("LEDGER_SETTLED", status());

        assertEquals(
                settleResult(),
                finalization.settleLedgerOnly(payoutId)
        );

        assertConflict(
                () -> finalization.releaseHold(payoutId)
        );

        verify(ledgerProvider, times(1))
                .settlePayout(
                        userId,
                        "sponsor",
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId
                );

        verify(ledgerProvider, never()).releasePayout(
                any(UUID.class),
                any(LedgerCurrency.class),
                anyLong(),
                anyString()
        );

        verify(notifications, times(1)).enqueueSafely(
                userId,
                701L,
                "payout_settle:" + operationId,
                TransactionNotificationType.PAYOUT,
                TransactionNotificationDirection.DEBIT,
                LedgerCurrency.EUR.currencyCode(),
                LedgerCurrency.EUR.formanceAsset(),
                5_000L
        );
    }

    @Test
    void releaseIsDurableIdempotentAndBlocksSettlement() {

        stubRelease();

        assertEquals(
                releaseResult(),
                finalization.releaseHold(payoutId)
        );

        assertEquals("RELEASED", status());

        assertEquals(
                releaseResult(),
                finalization.releaseHold(payoutId)
        );

        assertConflict(
                () -> finalization.settleLedgerOnly(payoutId)
        );

        verify(ledgerProvider, times(1)).releasePayout(
                userId,
                LedgerCurrency.EUR,
                5_000L,
                operationId
        );

        verify(ledgerProvider, never()).settlePayout(
                any(UUID.class),
                anyString(),
                any(LedgerCurrency.class),
                anyLong(),
                anyString()
        );

        verifyNoInteractions(notifications);
    }

    @Test
    void uncertainSettlementBlocksSettlementAndRelease() {

        when(ledgerProvider.settlePayout(
                userId,
                "sponsor",
                LedgerCurrency.EUR,
                5_000L,
                operationId
        )).thenThrow(
                new IllegalStateException(
                        "Formance response lost"
                )
        );

        assertThrows(
                ResponseStatusException.class,
                () -> finalization.settleLedgerOnly(payoutId)
        );

        assertEquals("SETTLE_UNKNOWN", status());

        assertConflict(
                () -> finalization.settleLedgerOnly(payoutId)
        );

        assertConflict(
                () -> finalization.releaseHold(payoutId)
        );

        verify(ledgerProvider, times(1)).settlePayout(
                userId,
                "sponsor",
                LedgerCurrency.EUR,
                5_000L,
                operationId
        );

        verifyNoInteractions(notifications);
    }

    @Test
    void uncertainReleaseBlocksReleaseAndSettlement() {

        when(ledgerProvider.releasePayout(
                userId,
                LedgerCurrency.EUR,
                5_000L,
                operationId
        )).thenThrow(
                new IllegalStateException(
                        "Formance response lost"
                )
        );

        assertThrows(
                ResponseStatusException.class,
                () -> finalization.releaseHold(payoutId)
        );

        assertEquals("RELEASE_UNKNOWN", status());

        assertConflict(
                () -> finalization.releaseHold(payoutId)
        );

        assertConflict(
                () -> finalization.settleLedgerOnly(payoutId)
        );

        verify(ledgerProvider, times(1)).releasePayout(
                userId,
                LedgerCurrency.EUR,
                5_000L,
                operationId
        );

        verifyNoInteractions(notifications);
    }

    @Test
    void concurrentSettlementBlocksRelease()
            throws Exception {

        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);

        when(ledgerProvider.settlePayout(
                userId,
                "sponsor",
                LedgerCurrency.EUR,
                5_000L,
                operationId
        )).thenAnswer(invocation -> {

            providerEntered.countDown();

            if (!releaseProvider.await(
                    10,
                    TimeUnit.SECONDS
            )) {
                throw new IllegalStateException(
                        "Test barrier timed out"
                );
            }

            return settleResult();
        });

        ExecutorService workers =
                Executors.newSingleThreadExecutor();

        Future<LedgerOperationResult> pending =
                workers.submit(
                        () -> finalization.settleLedgerOnly(payoutId)
                );

        try {

            assertTrue(providerEntered.await(
                    10,
                    TimeUnit.SECONDS
            ));

            assertEquals(
                    "SETTLE_IN_PROGRESS",
                    status()
            );

            assertConflict(
                    () -> finalization.releaseHold(payoutId)
            );

            releaseProvider.countDown();

            assertEquals(
                    settleResult(),
                    pending.get(10, TimeUnit.SECONDS)
            );

            assertEquals("LEDGER_SETTLED", status());

            verify(ledgerProvider, never()).releasePayout(
                    any(UUID.class),
                    any(LedgerCurrency.class),
                    anyLong(),
                    anyString()
            );

        } finally {

            releaseProvider.countDown();
            workers.shutdownNow();

            assertTrue(workers.awaitTermination(
                    10,
                    TimeUnit.SECONDS
            ));
        }
    }

    @Test
    void concurrentReleaseBlocksSettlement()
            throws Exception {

        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);

        when(ledgerProvider.releasePayout(
                userId,
                LedgerCurrency.EUR,
                5_000L,
                operationId
        )).thenAnswer(invocation -> {

            providerEntered.countDown();

            if (!releaseProvider.await(
                    10,
                    TimeUnit.SECONDS
            )) {
                throw new IllegalStateException(
                        "Test barrier timed out"
                );
            }

            return releaseResult();
        });

        ExecutorService workers =
                Executors.newSingleThreadExecutor();

        Future<LedgerOperationResult> pending =
                workers.submit(
                        () -> finalization.releaseHold(payoutId)
                );

        try {

            assertTrue(providerEntered.await(
                    10,
                    TimeUnit.SECONDS
            ));

            assertEquals(
                    "RELEASE_IN_PROGRESS",
                    status()
            );

            assertConflict(
                    () -> finalization.settleLedgerOnly(payoutId)
            );

            releaseProvider.countDown();

            assertEquals(
                    releaseResult(),
                    pending.get(10, TimeUnit.SECONDS)
            );

            assertEquals("RELEASED", status());

            verify(ledgerProvider, never()).settlePayout(
                    any(UUID.class),
                    anyString(),
                    any(LedgerCurrency.class),
                    anyLong(),
                    anyString()
            );

        } finally {

            releaseProvider.countDown();
            workers.shutdownNow();

            assertTrue(workers.awaitTermination(
                    10,
                    TimeUnit.SECONDS
            ));
        }
    }

    @Test
    void malformedSettlementEvidenceRemainsUnknown() {

        when(ledgerProvider.settlePayout(
                userId,
                "sponsor",
                LedgerCurrency.EUR,
                5_000L,
                operationId
        )).thenReturn(
                new LedgerOperationResult(
                        704L,
                        "incorrect-reference"
                )
        );

        assertThrows(
                ResponseStatusException.class,
                () -> finalization.settleLedgerOnly(payoutId)
        );

        assertEquals("SETTLE_UNKNOWN", status());

        assertConflict(
                () -> finalization.releaseHold(payoutId)
        );

        verify(ledgerProvider, times(1)).settlePayout(
                userId,
                "sponsor",
                LedgerCurrency.EUR,
                5_000L,
                operationId
        );

        verifyNoInteractions(notifications);
    }
}
