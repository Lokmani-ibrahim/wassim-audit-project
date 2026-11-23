
package com.fintech.ledger;

import com.fintech.identity.IdentityAccountService;
import com.fintech.identity.entity.AccountStatus;
import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.repository.KycStatusRepository;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.transfers.customer-api-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class CustomerTransferSuspensionConcurrencyIntegrationTest {

    @Autowired
    private UserRepository users;

    @Autowired
    private KycStatusRepository kycStatuses;

    @Autowired
    private CustomerTransferAdmissionService admission;

    @Autowired
    private IdentityAccountService accounts;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID senderId;
    private final UUID recipientId = UUID.randomUUID();
    private final UUID requestKey = UUID.randomUUID();

    private ExecutorService executor;

    @BeforeEach
    void setup() {

        String unique = UUID.randomUUID().toString();

        UserEntity user = new UserEntity(
                "concurrency-" + unique,
                "concurrency-" + unique + "@example.com",
                null
        );

        user.activate();
        users.saveAndFlush(user);
        senderId = user.getId();

        KycStatusEntity kyc =
                new KycStatusEntity(senderId);

        kyc.startVerification(
                "SUMSUB",
                "sandbox-" + unique
        );

        kyc.approve();
        kycStatuses.saveAndFlush(kyc);

        executor = Executors.newFixedThreadPool(2);
    }

    private boolean claim() {

        return admission.claimNew(
                senderId,
                recipientId,
                LedgerCurrency.EUR,
                5000L,
                requestKey,
                "concurrent-" + requestKey
        );
    }

    private int claimCount() {

        return jdbc.queryForObject(
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
    }

    private static void awaitLatch(CountDownLatch latch) {

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Test coordination timed out"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @AfterEach
    void cleanup() throws Exception {

        if (executor != null) {
            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(
                            10,
                            TimeUnit.SECONDS
                    )
            );
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
    void suspensionFirstBlocksAdmission() throws Exception {

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        TransactionTemplate tx =
                new TransactionTemplate(transactionManager);

        Future<?> suspension = executor.submit(() -> {

            tx.executeWithoutResult(status -> {

                // Lock the actual account row.
                UserEntity user = users
                        .findByIdForUpdate(senderId)
                        .orElseThrow();

                user.suspend();
                users.saveAndFlush(user);

                lockHeld.countDown();

                // Keep the suspension uncommitted
                // while admission attempts the same row.
                awaitLatch(release);
            });
        });

        try {
            assertTrue(
                    lockHeld.await(10, TimeUnit.SECONDS)
            );

            Future<Boolean> attemptedAdmission =
                    executor.submit(this::claim);

            // Admission must wait for the suspension
            // transaction to release its account lock.
            assertThrows(
                    TimeoutException.class,
                    () -> attemptedAdmission.get(
                            300,
                            TimeUnit.MILLISECONDS
                    )
            );

            release.countDown();

            // Suspension commits before admission proceeds.
            suspension.get(10, TimeUnit.SECONDS);

            ExecutionException denied = assertThrows(
                    ExecutionException.class,
                    () -> attemptedAdmission.get(
                            10,
                            TimeUnit.SECONDS
                    )
            );

            assertInstanceOf(
                    AccessDeniedException.class,
                    denied.getCause()
            );

            // No committed financial claim.
            assertEquals(0, claimCount());

        } finally {
            release.countDown();
        }
    }

    @Test
    void admissionFirstCommitsBeforeSuspension() throws Exception {

        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch suspensionStarted =
                new CountDownLatch(1);

        TransactionTemplate tx =
                new TransactionTemplate(transactionManager);

        Future<Boolean> firstAdmission =
                executor.submit(() -> tx.execute(status -> {

                    boolean claimed = claim();

                    // Admission has obtained the account
                    // lock and inserted its claim.
                    admitted.countDown();

                    // Hold the transaction open so that
                    // suspension must wait.
                    awaitLatch(release);

                    return claimed;
                }));

        try {
            assertTrue(
                    admitted.await(10, TimeUnit.SECONDS)
            );

            Future<?> suspension = executor.submit(() -> {

                suspensionStarted.countDown();
                accounts.suspendIfActive(senderId);
            });

            assertTrue(
                    suspensionStarted.await(
                            10,
                            TimeUnit.SECONDS
                    )
            );

            assertThrows(
                    TimeoutException.class,
                    () -> suspension.get(
                            300,
                            TimeUnit.MILLISECONDS
                    )
            );

            // Complete and commit admission first.
            release.countDown();

            assertTrue(
                    firstAdmission.get(
                            10,
                            TimeUnit.SECONDS
                    )
            );

            // Suspension can now acquire the account lock.
            suspension.get(10, TimeUnit.SECONDS);

            // The already-admitted claim is retained.
            assertEquals(1, claimCount());

            assertEquals(
                    AccountStatus.SUSPENDED,
                    users.findById(senderId)
                            .orElseThrow()
                            .getAccountStatus()
            );

        } finally {
            release.countDown();
        }
    }
}
