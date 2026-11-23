
package com.fintech.ledger;

import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.kyc.SumsubDecisionService;
import com.fintech.kyc.adapter.sumsub.SumsubReviewedEvent;
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

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.transfers.customer-api-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class CustomerTransferKycRevocationConcurrencyIntegrationTest {

    @Autowired
    private UserRepository users;

    @Autowired
    private KycStatusRepository kycStatuses;

    @Autowired
    private CustomerTransferAdmissionService admission;

    @Autowired
    private SumsubDecisionService decisions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID senderId;
    private UUID recipientId;
    private UUID requestKey;
    private String applicantId;

    private ExecutorService executor;

    @BeforeEach
    void setup() {

        String unique = UUID.randomUUID().toString();

        UserEntity user = new UserEntity(
                "kyc-race-" + unique,
                "kyc-race-" + unique + "@example.com",
                null
        );

        user.activate();

        senderId = users.saveAndFlush(user).getId();

        recipientId = UUID.randomUUID();
        requestKey = UUID.randomUUID();
        applicantId = "sandbox-" + unique;

        KycStatusEntity kyc =
                new KycStatusEntity(senderId);

        kyc.startVerification(
                "SUMSUB",
                applicantId
        );

        kyc.approve();

        kycStatuses.saveAndFlush(kyc);

        executor = Executors.newFixedThreadPool(2);
    }

    private boolean claimTransfer() {

        return admission.claimNew(
                senderId,
                recipientId,
                LedgerCurrency.EUR,
                5_000L,
                requestKey,
                "kyc_race_" + requestKey
        );
    }

    private SumsubReviewedEvent rejectionEvent() {

        return new SumsubReviewedEvent(
                senderId,
                applicantId,
                "id-and-liveness",
                SumsubReviewedEvent.Decision.FINAL_REJECTION,
                Instant.now()
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

    private String accountStatus() {

        return jdbc.queryForObject(
                """
                SELECT account_status
                FROM users
                WHERE id = ?
                """,
                String.class,
                senderId
        );
    }

    private String kycStatus() {

        return jdbc.queryForObject(
                """
                SELECT status
                FROM kyc_status
                WHERE user_id = ?
                """,
                String.class,
                senderId
        );
    }

    private static void awaitLatch(CountDownLatch latch) {

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Concurrency coordination timed out"
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

            assertTrue(executor.awaitTermination(
                    10,
                    TimeUnit.SECONDS
            ));
        }

        if (senderId != null) {

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
    }

    @Test
    void kycRejectionFirstBlocksNewAdmission()
            throws Exception {

        CountDownLatch rejectionApplied =
                new CountDownLatch(1);

        CountDownLatch releaseRejection =
                new CountDownLatch(1);

        TransactionTemplate tx =
                new TransactionTemplate(transactionManager);

        Future<SumsubDecisionService.Outcome> revocation =
                executor.submit(() -> tx.execute(status -> {

                    // Real KYC + user locks are acquired
                    // by SumsubDecisionService.
                    var outcome = decisions.applyVerifiedReview(
                            rejectionEvent()
                    );

                    rejectionApplied.countDown();

                    // Keep changes uncommitted to test
                    // whether admission waits.
                    awaitLatch(releaseRejection);

                    return outcome;
                }));

        try {
            assertTrue(
                    rejectionApplied.await(
                            10,
                            TimeUnit.SECONDS
                    )
            );

            Future<Boolean> attemptedAdmission =
                    executor.submit(this::claimTransfer);

            // Admission should not finish while
            // the KYC revocation transaction holds
            // its PostgreSQL locks.
            assertThrows(
                    TimeoutException.class,
                    () -> attemptedAdmission.get(
                            300,
                            TimeUnit.MILLISECONDS
                    )
            );

            releaseRejection.countDown();

            assertEquals(
                    SumsubDecisionService.Outcome.APPLIED,
                    revocation.get(10, TimeUnit.SECONDS)
            );

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> attemptedAdmission.get(
                            10,
                            TimeUnit.SECONDS
                    )
            );

            assertInstanceOf(
                    AccessDeniedException.class,
                    failure.getCause()
            );

            assertEquals("REJECTED", kycStatus());
            assertEquals("SUSPENDED", accountStatus());

            // No new financial operation was admitted.
            assertEquals(0, claimCount());

        } finally {
            releaseRejection.countDown();
        }
    }

    @Test
    void admissionFirstCommitsBeforeKycRejection()
            throws Exception {

        CountDownLatch admissionCreated =
                new CountDownLatch(1);

        CountDownLatch releaseAdmission =
                new CountDownLatch(1);

        CountDownLatch rejectionStarted =
                new CountDownLatch(1);

        TransactionTemplate tx =
                new TransactionTemplate(transactionManager);

        Future<Boolean> firstAdmission =
                executor.submit(() -> tx.execute(status -> {

                    boolean claimed = claimTransfer();

                    admissionCreated.countDown();

                    // The admission transaction is not
                    // yet committed. Both KYC and users
                    // row locks remain held.
                    awaitLatch(releaseAdmission);

                    return claimed;
                }));

        try {
            assertTrue(
                    admissionCreated.await(
                            10,
                            TimeUnit.SECONDS
                    )
            );

            Future<SumsubDecisionService.Outcome> revocation =
                    executor.submit(() -> {

                        rejectionStarted.countDown();

                        // Must wait for the KYC row lock.
                        return decisions.applyVerifiedReview(
                                rejectionEvent()
                        );
                    });

            assertTrue(
                    rejectionStarted.await(
                            10,
                            TimeUnit.SECONDS
                    )
            );

            assertThrows(
                    TimeoutException.class,
                    () -> revocation.get(
                            300,
                            TimeUnit.MILLISECONDS
                    )
            );

            // Commit admission before allowing
            // KYC revocation to acquire the locks.
            releaseAdmission.countDown();

            assertTrue(
                    firstAdmission.get(
                            10,
                            TimeUnit.SECONDS
                    )
            );

            assertEquals(
                    SumsubDecisionService.Outcome.APPLIED,
                    revocation.get(10, TimeUnit.SECONDS)
            );

            // The prior admission remains durable.
            assertEquals(1, claimCount());

            String transferStatus = jdbc.queryForObject(
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

            assertEquals("IN_PROGRESS", transferStatus);

            // KYC revocation still completes normally.
            assertEquals("REJECTED", kycStatus());
            assertEquals("SUSPENDED", accountStatus());

        } finally {
            releaseAdmission.countDown();
        }
    }
}
