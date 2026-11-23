
package com.fintech.ledger;

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
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.transfers.customer-api-enabled=false",
        "neos.backoffice.synthetic-credit.enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class PayoutRequestAdmissionIntegrationTest {

    @Autowired
    private PayoutRequestAdmissionService admission;

    @Autowired
    private UserRepository users;

    @Autowired
    private KycStatusRepository kycStatuses;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID userId;
    private UUID requestKey;

    @BeforeEach
    void setup() {

        String unique = UUID.randomUUID().toString();

        UserEntity user = new UserEntity(
                "payout-admission-" + unique,
                "payout-admission-" + unique + "@example.com",
                null
        );

        user.activate();

        userId = users.saveAndFlush(user).getId();
        requestKey = UUID.randomUUID();

        KycStatusEntity kyc =
                new KycStatusEntity(userId);

        kyc.startVerification(
                "SUMSUB",
                "sandbox-" + unique
        );

        kyc.approve();

        kycStatuses.saveAndFlush(kyc);
    }

    private PayoutRequestAdmissionService.AdmissionResult admit() {

        return admission.admit(
                userId,
                requestKey,
                LedgerCurrency.EUR,
                5_000L,
                "sponsor"
        );
    }

    private int countClaims() {

        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM payout_requests
                WHERE user_id = ?
                  AND request_key = ?
                """,
                Integer.class,
                userId,
                requestKey
        );

        return count == null ? 0 : count;
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
    void eligibleCustomerCreatesRequestedPayout() {

        var result = admit();

        assertTrue(result.created());
        assertNotNull(result.payoutId());
        assertTrue(result.operationId().startsWith("po_"));

        assertEquals(1, countClaims());

        String status = jdbc.queryForObject(
                """
                SELECT status
                FROM payout_requests
                WHERE payout_id = ?
                """,
                String.class,
                result.payoutId()
        );

        assertEquals("REQUESTED", status);

        // No Formance posting occurs during admission.
    }

    @Test
    void exactRetryReturnsSamePayoutWithoutNewClaim() {

        var first = admit();
        var second = admit();

        assertTrue(first.created());
        assertFalse(second.created());

        assertEquals(first.payoutId(), second.payoutId());
        assertEquals(
                first.operationId(),
                second.operationId()
        );

        assertEquals(1, countClaims());
    }

    @Test
    void changedAmountWithSameKeyIsRejected() {

        admit();

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> admission.admit(
                        userId,
                        requestKey,
                        LedgerCurrency.EUR,
                        6_000L,
                        "sponsor"
                )
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(1, countClaims());
    }

    @Test
    void changedSettlementBankWithSameKeyIsRejected() {

        admit();

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> admission.admit(
                        userId,
                        requestKey,
                        LedgerCurrency.EUR,
                        5_000L,
                        "other_bank"
                )
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(1, countClaims());
    }

    @Test
    void rejectedKycCannotCreateNewPayout() {

        KycStatusEntity kyc = kycStatuses
                .findByUserId(userId)
                .orElseThrow();

        kyc.reject();
        kycStatuses.saveAndFlush(kyc);

        assertThrows(
                AccessDeniedException.class,
                this::admit
        );

        assertEquals(0, countClaims());
    }

    @Test
    void suspendedAccountCannotCreateNewPayout() {

        UserEntity user = users
                .findById(userId)
                .orElseThrow();

        user.suspend();
        users.saveAndFlush(user);

        assertThrows(
                AccessDeniedException.class,
                this::admit
        );

        assertEquals(0, countClaims());
    }

    @Test
    void concurrentExactRequestsCreateOnlyOnePayout()
            throws Exception {

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch start = new CountDownLatch(1);

        try {

            Callable<PayoutRequestAdmissionService.AdmissionResult>
                    task = () -> {

                if (!start.await(
                        10,
                        TimeUnit.SECONDS
                )) {
                    throw new IllegalStateException(
                            "Start coordination timed out"
                    );
                }

                return admit();
            };

            Future<PayoutRequestAdmissionService.AdmissionResult>
                    first = executor.submit(task);

            Future<PayoutRequestAdmissionService.AdmissionResult>
                    second = executor.submit(task);

            start.countDown();

            var resultA = first.get(
                    15,
                    TimeUnit.SECONDS
            );

            var resultB = second.get(
                    15,
                    TimeUnit.SECONDS
            );

            assertEquals(
                    resultA.payoutId(),
                    resultB.payoutId()
            );

            assertEquals(
                    resultA.operationId(),
                    resultB.operationId()
            );

            assertNotEquals(
                    resultA.created(),
                    resultB.created()
            );

            assertEquals(1, countClaims());

        } finally {

            start.countDown();

            executor.shutdownNow();

            assertTrue(executor.awaitTermination(
                    10,
                    TimeUnit.SECONDS
            ));
        }
    }
}
