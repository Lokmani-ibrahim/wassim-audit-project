
package com.fintech.ledger;

import com.fintech.identity.IdentityAccountService;
import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.repository.KycStatusRepository;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.port.LedgerProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.transfers.customer-api-enabled=false",
        "neos.backoffice.synthetic-credit.enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class PayoutReservationExecutionIntegrationTest {

    @Autowired
    private PayoutRequestAdmissionService admission;

    @Autowired
    private PayoutReservationExecutionService execution;

    @Autowired
    private IdentityAccountService accounts;

    @Autowired
    private UserRepository users;

    @Autowired
    private KycStatusRepository kycStatuses;

    @Autowired
    private JdbcTemplate jdbc;

    // Real PostgreSQL, real KYC, real payout services.
    // Only the external ledger provider is replaced.
    @MockitoBean(enforceOverride = true)
    private LedgerProvider ledgerProvider;

    private UUID userId;
    private UUID payoutId;
    private String operationId;

    @BeforeEach
    void setup() {

        String unique = UUID.randomUUID().toString();

        UserEntity user = new UserEntity(
                "payout-execution-" + unique,
                "payout-execution-" + unique + "@example.com",
                null
        );

        user.activate();

        userId = users.saveAndFlush(user).getId();

        KycStatusEntity kyc =
                new KycStatusEntity(userId);

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
    }

    private LedgerOperationResult expectedResult() {

        return new LedgerOperationResult(
                501L,
                "payout_reserve:" + operationId
        );
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

    private void stubSuccessfulReservation() {

        when(ledgerProvider.reservePayout(
                userId,
                LedgerCurrency.EUR,
                5_000L,
                operationId
        )).thenReturn(expectedResult());
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
    void successfulReservationIsDurableAndReplayNeverReposts() {

        stubSuccessfulReservation();

        LedgerOperationResult first =
                execution.reserve(payoutId);

        LedgerOperationResult replay =
                execution.reserve(payoutId);

        assertEquals(expectedResult(), first);
        assertEquals(first, replay);
        assertEquals("RESERVED", status());

        Long transactionId = jdbc.queryForObject(
                """
                SELECT reserve_ledger_transaction_id
                FROM payout_requests
                WHERE payout_id = ?
                """,
                Long.class,
                payoutId
        );

        assertEquals(501L, transactionId);

        verify(ledgerProvider, times(1))
                .reservePayout(
                        userId,
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId
                );
    }

    @Test
    void providerFailureBecomesUnknownAndRetryIsBlocked() {

        // An authorization-like error originating from
        // the provider is still an uncertain posting.
        when(ledgerProvider.reservePayout(
                userId,
                LedgerCurrency.EUR,
                5_000L,
                operationId
        )).thenThrow(
                new AccessDeniedException("Provider rejected")
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> execution.reserve(payoutId)
        );

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                error.getStatusCode()
        );

        assertEquals("RESERVE_UNKNOWN", status());

        ResponseStatusException retry = assertThrows(
                ResponseStatusException.class,
                () -> execution.reserve(payoutId)
        );

        assertEquals(
                HttpStatus.CONFLICT,
                retry.getStatusCode()
        );

        verify(ledgerProvider, times(1))
                .reservePayout(
                        userId,
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId
                );
    }

    @Test
    void suspensionBeforePostingReleasesExecutionClaim() {

        accounts.suspendIfActive(userId);

        assertThrows(
                PayoutAuthorizationDeniedBeforePostingException.class,
                () -> execution.reserve(payoutId)
        );

        assertEquals("REQUESTED", status());

        // No financial reservation was attempted.
        verifyNoInteractions(ledgerProvider);
    }

    @Test
    void concurrentRetryCannotSubmitSecondReservation()
            throws Exception {

        CountDownLatch providerEntered =
                new CountDownLatch(1);

        CountDownLatch releaseProvider =
                new CountDownLatch(1);

        when(ledgerProvider.reservePayout(
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
                        "Provider test barrier timed out"
                );
            }

            return expectedResult();
        });

        ExecutorService workers =
                Executors.newSingleThreadExecutor();

        Future<LedgerOperationResult> first =
                workers.submit(
                        () -> execution.reserve(payoutId)
                );

        try {

            assertTrue(
                    providerEntered.await(
                            10,
                            TimeUnit.SECONDS
                    )
            );

            // First request is currently inside
            // the mocked provider. Claim already committed.
            assertEquals(
                    "RESERVE_IN_PROGRESS",
                    status()
            );

            ResponseStatusException retry = assertThrows(
                    ResponseStatusException.class,
                    () -> execution.reserve(payoutId)
            );

            assertEquals(
                    HttpStatus.CONFLICT,
                    retry.getStatusCode()
            );

            releaseProvider.countDown();

            assertEquals(
                    expectedResult(),
                    first.get(10, TimeUnit.SECONDS)
            );

            assertEquals("RESERVED", status());

            verify(ledgerProvider, times(1))
                    .reservePayout(
                            userId,
                            LedgerCurrency.EUR,
                            5_000L,
                            operationId
                    );

        } finally {

            releaseProvider.countDown();
            workers.shutdownNow();

            assertTrue(
                    workers.awaitTermination(
                            10,
                            TimeUnit.SECONDS
                    )
            );
        }
    }
}
