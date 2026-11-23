
package com.fintech.ledger;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;
import com.fintech.identity.UserAccountStatusLookup;
import com.fintech.kyc.CustomerDebitAuthorization;

import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.BeforeEach;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.transfers.customer-api-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class CustomerTransferIdempotencyResilienceIntegrationTest {

    @Autowired
    private CustomerTransferIdempotencyService service;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean(enforceOverride = true)
    private InternalTransferService transferService;
    @Autowired
    private CustomerTransferAdmissionService admissionService;

    private final UUID senderId = UUID.randomUUID();
    private final UUID recipientId = UUID.randomUUID();
    private final UUID requestKey = UUID.randomUUID();
    @MockitoBean(enforceOverride = true)
    private UserAccountStatusLookup accounts;

    @MockitoBean(enforceOverride = true)
    private CustomerDebitAuthorization debitAuthorization;

    @BeforeEach
    void allowApprovedKycAdmission() {
        when(debitAuthorization
                .lockAndCheckApprovedForAdmission(senderId))
                .thenReturn(true);
    }

    private String operationId() {
        return "ct_"
                + senderId.toString().replace("-", "")
                + "_"
                + requestKey.toString().replace("-", "");
    }

    private LedgerOperationResult executeTransfer() {
        return service.execute(
                senderId,
                recipientId,
                LedgerCurrency.EUR,
                5_000L,
                requestKey,
                operationId()
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


    private CustomerTransferIdempotencyService
    withSimulatedResultWriteFailure(boolean failAllStatusUpdates) {

        // Use the real PostgreSQL DataSource, but intercept
        // selected result-status UPDATE statements.
        JdbcTemplate failingJdbc = new JdbcTemplate(
                Objects.requireNonNull(jdbc.getDataSource())
        ) {

            @Override
            public int update(String sql, Object... args) {

                boolean isStatusUpdate = sql.contains(
                        "UPDATE customer_transfer_idempotency"
                );

                boolean isSuccessUpdate = sql.contains(
                        "SET status = 'SUCCEEDED'"
                );

                if (isStatusUpdate
                        && (failAllStatusUpdates || isSuccessUpdate)) {

                    throw new DataAccessResourceFailureException(
                            "Simulated PostgreSQL result-write failure"
                    );
                }

                return super.update(sql, args);
            }
        };

        return new CustomerTransferIdempotencyService(
                failingJdbc,
                transferService,
                admissionService
        );
    }

    @AfterEach
    void cleanup() {
        jdbc.update(
                """
                DELETE FROM customer_transfer_idempotency
                WHERE sender_user_id = ?
                """,
                senderId
        );
    }

    @Test
    void formanceTimeoutLeavesUnknownAndBlocksRetry() {

        // Simulate a timeout where Formance might
        // already have executed the transaction.
        doThrow(new IllegalStateException(
                "Simulated Formance timeout"
        ))
                .when(transferService)
                .transfer(
                        senderId,
                        recipientId,
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId()
                );

        // First attempt: outcome uncertain.
        ResponseStatusException first =
                assertThrows(
                        ResponseStatusException.class,
                        this::executeTransfer
                );

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                first.getStatusCode()
        );

        // The uncertainty must survive in PostgreSQL.
        assertEquals(
                "UNKNOWN",
                storedStatus()
        );

        // An exact retry must NOT initiate
        // another potentially duplicate transfer.
        ResponseStatusException retry =
                assertThrows(
                        ResponseStatusException.class,
                        this::executeTransfer
                );

        assertEquals(
                HttpStatus.CONFLICT,
                retry.getStatusCode()
        );

        assertEquals(
                "UNKNOWN",
                storedStatus()
        );

        // Critical financial integrity assertion:
        // the transfer service was called only once.
        verify(transferService, times(1))
                .transfer(
                        senderId,
                        recipientId,
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId()
                );
    }

    @Test
    void concurrentRequestsWithSameKeyExecuteOnlyOnce()
            throws Exception {

        CountDownLatch transferStarted =
                new CountDownLatch(1);

        CountDownLatch allowTransferToFinish =
                new CountDownLatch(1);

        // Simulate a slow Formance operation.
        // The first request will remain IN_PROGRESS
        // while we submit the second one.
        when(transferService.transfer(
                senderId,
                recipientId,
                LedgerCurrency.EUR,
                5_000L,
                operationId()
        )).thenAnswer(invocation -> {

            transferStarted.countDown();

            boolean released =
                    allowTransferToFinish.await(
                            10,
                            TimeUnit.SECONDS
                    );

            if (!released) {
                throw new IllegalStateException(
                        "Test transfer was not released"
                );
            }

            return new LedgerOperationResult(
                    9001L,
                    "internal_transfer:concurrent"
            );
        });

        ExecutorService executor =
                Executors.newSingleThreadExecutor();

        try {
            // Request A begins on a separate thread.
            Future<LedgerOperationResult> firstAttempt =
                    executor.submit(
                            this::executeTransfer
                    );

            // Wait until it reaches the mocked
            // Formance operation.
            assertTrue(
                    transferStarted.await(
                            10,
                            TimeUnit.SECONDS
                    ),
                    "First request did not start"
            );

            // The claim must already be committed.
            assertEquals(
                    "IN_PROGRESS",
                    storedStatus()
            );

            // Request B uses exactly the same key
            // while request A is still processing.
            ResponseStatusException secondAttempt =
                    assertThrows(
                            ResponseStatusException.class,
                            this::executeTransfer
                    );

            assertEquals(
                    HttpStatus.CONFLICT,
                    secondAttempt.getStatusCode()
            );

            // Request B must not start another transfer.
            verify(transferService, times(1))
                    .transfer(
                            senderId,
                            recipientId,
                            LedgerCurrency.EUR,
                            5_000L,
                            operationId()
                    );

            // Allow request A to complete.
            allowTransferToFinish.countDown();

            LedgerOperationResult result =
                    firstAttempt.get(
                            10,
                            TimeUnit.SECONDS
                    );

            assertEquals(
                    9001L,
                    result.transactionId()
            );

            assertEquals(
                    "SUCCEEDED",
                    storedStatus()
            );

            // After completion, the same key
            // returns the persisted result.
            LedgerOperationResult replay =
                    executeTransfer();

            assertEquals(
                    result,
                    replay
            );

            // Still exactly one financial operation.
            verify(transferService, times(1))
                    .transfer(
                            senderId,
                            recipientId,
                            LedgerCurrency.EUR,
                            5_000L,
                            operationId()
                    );

        } finally {
            allowTransferToFinish.countDown();

            executor.shutdown();

            if (!executor.awaitTermination(
                    10,
                    TimeUnit.SECONDS
            )) {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void successfulLedgerTransferWithFailedResultWriteStaysUnknown() {

        // Formance reports a confirmed successful transfer.
        when(transferService.transfer(
                senderId,
                recipientId,
                LedgerCurrency.EUR,
                5_000L,
                operationId()
        )).thenReturn(
                new LedgerOperationResult(
                        9101L,
                        "internal_transfer:confirmed"
                )
        );

        // Simulate a failure only when NEOS tries
        // to persist status SUCCEEDED.
        CustomerTransferIdempotencyService failingService =
                withSimulatedResultWriteFailure(false);

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> failingService.execute(
                        senderId,
                        recipientId,
                        LedgerCurrency.EUR,
                        5_000L,
                        requestKey,
                        operationId()
                )
        );

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                failure.getStatusCode()
        );

        // The database must retain the uncertain outcome.
        assertEquals(
                "UNKNOWN",
                storedStatus()
        );

        // Retrying must not execute another transfer.
        ResponseStatusException retry = assertThrows(
                ResponseStatusException.class,
                this::executeTransfer
        );

        assertEquals(
                HttpStatus.CONFLICT,
                retry.getStatusCode()
        );

        // Critical: Formance was invoked exactly once.
        verify(transferService, times(1))
                .transfer(
                        senderId,
                        recipientId,
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId()
                );
    }

    @Test
    void totalStatusWriteFailurePreservesClaimAndBlocksRetry() {

        when(transferService.transfer(
                senderId,
                recipientId,
                LedgerCurrency.EUR,
                5_000L,
                operationId()
        )).thenReturn(
                new LedgerOperationResult(
                        9102L,
                        "internal_transfer:confirmed-before-db-failure"
                )
        );

        // All status UPDATE operations fail.
        // The original INSERT claim still succeeds.
        CustomerTransferIdempotencyService failingService =
                withSimulatedResultWriteFailure(true);

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> failingService.execute(
                        senderId,
                        recipientId,
                        LedgerCurrency.EUR,
                        5_000L,
                        requestKey,
                        operationId()
                )
        );

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                failure.getStatusCode()
        );

        // Because even the UNKNOWN update failed,
        // the original IN_PROGRESS claim remains.
        assertEquals(
                "IN_PROGRESS",
                storedStatus()
        );

        // A retry sees the existing claim and refuses
        // to initiate another financial operation.
        ResponseStatusException retry = assertThrows(
                ResponseStatusException.class,
                this::executeTransfer
        );

        assertEquals(
                HttpStatus.CONFLICT,
                retry.getStatusCode()
        );

        verify(transferService, times(1))
                .transfer(
                        senderId,
                        recipientId,
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId()
                );
    }

    @Test
    void suspendedSenderIsDeniedWithoutCreatingUnknown() {

        // The account is already suspended before
        // the transfer execution phase.
        doThrow(new AccessDeniedException(
                "Customer account suspended"
        ))
                .when(transferService)
                .requireSenderAuthorized(senderId);

        AccessDeniedException denial = assertThrows(
                AccessDeniedException.class,
                this::executeTransfer
        );

        assertEquals(
                "Customer account suspended",
                denial.getMessage()
        );

        // No unresolved idempotency claim should remain.
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM customer_transfer_idempotency
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                Integer.class,
                senderId,
                requestKey
        );

        assertEquals(0, count);

        // CRITICAL: Never attempt the financial operation.
        verify(transferService, never())
                .transfer(
                        senderId,
                        recipientId,
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId()
                );
    }

    @Test
    void preflightInfrastructureFailureDoesNotExecuteTransfer() {

        // Authorization database temporarily unavailable.
        doThrow(new IllegalStateException(
                "Authorization database unavailable"
        ))
                .when(transferService)
                .requireSenderAuthorized(senderId);

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                this::executeTransfer
        );

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                failure.getStatusCode()
        );

        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM customer_transfer_idempotency
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                Integer.class,
                senderId,
                requestKey
        );

        // The pre-execution claim was safely released.
        assertEquals(0, count);

        verify(transferService, never())
                .transfer(
                        senderId,
                        recipientId,
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId()
                );
    }

    @Test
    void denialDuringExecutionRemainsConservativelyUnknown() {

        // Preflight succeeds.
        // A later exception occurs in the transfer phase.
        //
        // We cannot safely infer that Formance was never
        // reached based only on the exception type.
        doThrow(new AccessDeniedException(
                "Execution-phase denial"
        ))
                .when(transferService)
                .transfer(
                        senderId,
                        recipientId,
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId()
                );

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                this::executeTransfer
        );

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                failure.getStatusCode()
        );

        // A post-preflight failure is conservatively
        // classified as uncertain.
        assertEquals(
                "UNKNOWN",
                storedStatus()
        );

        verify(transferService, times(1))
                .transfer(
                        senderId,
                        recipientId,
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId()
                );
    }


    @Test
    void suspensionBeforeAdmissionPreventsLedgerExecution() {

        doThrow(new AccessDeniedException(
                "Customer account is suspended"
        ))
                .when(accounts)
                .requireActiveForTransferAdmission(senderId);

        assertThrows(
                AccessDeniedException.class,
                this::executeTransfer
        );

        Integer remainingClaims = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM customer_transfer_idempotency
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                Integer.class,
                senderId,
                requestKey
        );

        assertEquals(0, remainingClaims);

        // Admission was rejected before the transfer
        // service or external Formance operation.
        verifyNoInteractions(transferService);
    }

    @Test
    void denialAtSecondAuthorizationCheckReleasesClaim() {

        // The first authorization preflight succeeds.
        // The second check, inside transfer(), rejects
        // before any Formance call.
        doThrow(
                new TransferAuthorizationDeniedBeforePostingException(
                        new AccessDeniedException(
                                "Account suspended before posting"
                        )
                )
        ).when(transferService)
                .transfer(
                        senderId,
                        recipientId,
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId()
                );

        assertThrows(
                TransferAuthorizationDeniedBeforePostingException.class,
                this::executeTransfer
        );

        // The idempotency claim should not remain UNKNOWN
        // or IN_PROGRESS when no posting occurred.
        Integer remainingClaims = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM customer_transfer_idempotency
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                Integer.class,
                senderId,
                requestKey
        );

        assertEquals(0, remainingClaims);

        verify(transferService, times(1))
                .transfer(
                        senderId,
                        recipientId,
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId()
                );
    }


}
