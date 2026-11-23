
package com.fintech.ledger;

import com.fintech.identity.IdentityAccountService;
import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.kyc.CustomerDebitAuthorization;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.repository.KycStatusRepository;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.ledger.port.LedgerProvider;
import com.fintech.notifications.FinancialTransactionNotificationService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.transfers.customer-api-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class CustomerTransferExecutionBoundaryIntegrationTest {

    @Autowired
    private UserRepository users;

    @Autowired
    private KycStatusRepository kycStatuses;

    @Autowired
    private IdentityAccountService accounts;

    @Autowired
    private CustomerDebitAuthorization authorization;

    @Autowired
    private CustomerTransferAdmissionService admission;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID senderId;
    private UUID recipientId;
    private UUID requestKey;

    @BeforeEach
    void setup() {

        String unique = UUID.randomUUID().toString();

        UserEntity user = new UserEntity(
                "execution-boundary-" + unique,
                "execution-boundary-" + unique + "@example.com",
                null
        );

        user.activate();

        senderId = users.saveAndFlush(user).getId();

        recipientId = UUID.randomUUID();
        requestKey = UUID.randomUUID();

        KycStatusEntity kyc =
                new KycStatusEntity(senderId);

        kyc.startVerification(
                "SUMSUB",
                "sandbox-" + unique
        );

        kyc.approve();
        kycStatuses.saveAndFlush(kyc);
    }

    private String operationId() {
        return "step52_" +
                requestKey.toString().replace("-", "");
    }

    private CustomerTransferIdempotencyService orchestration(
            InternalTransferService transfers
    ) {
        return new CustomerTransferIdempotencyService(
                jdbc,
                transfers,
                admission
        );
    }

    private LedgerOperationResult execute(
            CustomerTransferIdempotencyService service
    ) {
        return service.execute(
                senderId,
                recipientId,
                LedgerCurrency.EUR,
                5_000L,
                requestKey,
                operationId()
        );
    }

    private int claimCount() {

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

        return count == null ? 0 : count;
    }

    private String claimStatus() {

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

    @AfterEach
    void cleanup() {

        if (senderId == null) {
            return;
        }

        jdbc.update(
                """
                DELETE FROM customer_transfer_idempotency
                WHERE sender_user_id = ?
                """,
                senderId
        );

        jdbc.update(
                """
                DELETE FROM kyc_status
                WHERE user_id = ?
                """,
                senderId
        );

        jdbc.update(
                """
                DELETE FROM users
                WHERE id = ?
                """,
                senderId
        );
    }

    @Test
    void suspensionBetweenAdmissionAndPostingReleasesClaim()
            throws Exception {

        LedgerProvider ledger = mock(LedgerProvider.class);

        FinancialTransactionNotificationService notifications =
                mock(FinancialTransactionNotificationService.class);

        CountDownLatch secondCheckReached =
                new CountDownLatch(1);

        CountDownLatch allowSecondCheck =
                new CountDownLatch(1);

        AtomicInteger checkCount = new AtomicInteger();

        // Real transfer service behavior, with a test
        // barrier inserted BEFORE the second authorization.
        InternalTransferService transfers =
                new InternalTransferService(
                        ledger,
                        notifications,
                        authorization
                ) {
                    @Override
                    public void requireSenderAuthorized(UUID userId) {

                        if (checkCount.incrementAndGet() == 2) {

                            secondCheckReached.countDown();

                            try {
                                if (!allowSecondCheck.await(
                                        10,
                                        TimeUnit.SECONDS
                                )) {
                                    throw new IllegalStateException(
                                            "Authorization barrier timed out"
                                    );
                                }
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(
                                        exception
                                );
                            }
                        }

                        super.requireSenderAuthorized(userId);
                    }
                };

        CustomerTransferIdempotencyService service =
                orchestration(transfers);

        ExecutorService executor =
                Executors.newSingleThreadExecutor();

        Future<LedgerOperationResult> pending =
                executor.submit(() -> execute(service));

        try {

            // Admission has committed and the initial
            // preflight passed. The second check is paused.
            assertTrue(
                    secondCheckReached.await(
                            10,
                            TimeUnit.SECONDS
                    )
            );

            assertEquals("IN_PROGRESS", claimStatus());
            assertEquals(1, claimCount());

            // Suspend the REAL persisted customer.
            accounts.suspendIfActive(senderId);

            // Resume the second authorization check.
            allowSecondCheck.countDown();

            ExecutionException failed = assertThrows(
                    ExecutionException.class,
                    () -> pending.get(
                            10,
                            TimeUnit.SECONDS
                    )
            );

            // Known pre-Formance denial.
            assertInstanceOf(
                    TransferAuthorizationDeniedBeforePostingException.class,
                    failed.getCause()
            );

            // No uncertain claim should remain.
            assertEquals(0, claimCount());

            // MOST IMPORTANT:
            // The ledger and notifications were untouched.
            verifyNoInteractions(ledger, notifications);

            assertEquals(2, checkCount.get());

        } finally {

            allowSecondCheck.countDown();

            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(
                            10,
                            TimeUnit.SECONDS
                    )
            );
        }
    }

    @Test
    void ledgerProviderDenialRemainsUnknownAndBlocksRetry() {

        LedgerProvider ledger = mock(LedgerProvider.class);

        FinancialTransactionNotificationService notifications =
                mock(FinancialTransactionNotificationService.class);

        // Even an AccessDeniedException from the provider
        // cannot prove that no ledger posting occurred.
        doThrow(new AccessDeniedException(
                "Provider rejected request after submission"
        )).when(ledger)
                .transferBetweenUsers(
                        senderId,
                        recipientId,
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId()
                );

        InternalTransferService transfers =
                new InternalTransferService(
                        ledger,
                        notifications,
                        authorization
                );

        CustomerTransferIdempotencyService service =
                orchestration(transfers);

        ResponseStatusException first = assertThrows(
                ResponseStatusException.class,
                () -> execute(service)
        );

        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                first.getStatusCode()
        );

        // The provider was invoked, so the outcome is
        // uncertain even though the exception was 403-like.
        assertEquals("UNKNOWN", claimStatus());

        ResponseStatusException retry = assertThrows(
                ResponseStatusException.class,
                () -> execute(service)
        );

        assertEquals(
                HttpStatus.CONFLICT,
                retry.getStatusCode()
        );

        assertEquals("UNKNOWN", claimStatus());

        // No second ledger submission.
        verify(ledger, times(1))
                .transferBetweenUsers(
                        senderId,
                        recipientId,
                        LedgerCurrency.EUR,
                        5_000L,
                        operationId()
                );

        verifyNoInteractions(notifications);
    }
}
