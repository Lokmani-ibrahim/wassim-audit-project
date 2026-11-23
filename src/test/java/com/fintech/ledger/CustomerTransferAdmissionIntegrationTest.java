
package com.fintech.ledger;

import com.fintech.identity.UserAccountStatusLookup;
import com.fintech.kyc.CustomerDebitAuthorization;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;


import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.transfers.customer-api-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
class CustomerTransferAdmissionIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CustomerTransferAdmissionService admission;

    // These tests exercise REAL PostgreSQL transaction
    // behavior, while simulating authorization decisions.
    @MockitoBean(enforceOverride = true)
    private UserAccountStatusLookup accounts;

    @MockitoBean(enforceOverride = true)
    private CustomerDebitAuthorization debitAuthorization;

    private final UUID senderId = UUID.randomUUID();
    private final UUID recipientId = UUID.randomUUID();
    private final UUID requestKey = UUID.randomUUID();


    @BeforeEach
    void allowApprovedKycAdmission() {

        doReturn(true)
                .when(debitAuthorization)
                .lockAndCheckApprovedForAdmission(
                        any(UUID.class)
                );
    }



    private String operationId() {
        return "ct_test_" + requestKey.toString().replace("-", "");
    }

    private boolean claim() {
        return admission.claimNew(
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

    @AfterEach
    void cleanup() {
        jdbc.update(
                """
                DELETE FROM customer_transfer_idempotency
                WHERE sender_user_id = ?
                  AND request_key = ?
                """,
                senderId,
                requestKey
        );
    }

    @Test
    void eligibleTransferCreatesCommittedClaim() {

        assertTrue(claim());
        assertEquals(1, claimCount());

        verify(accounts)
                .requireActiveForTransferAdmission(senderId);

        verify(debitAuthorization)
                .requireEligible(senderId);
    }

    @Test
    void suspensionDenialRollsBackClaim() {

        doThrow(new AccessDeniedException(
                "Customer is suspended"
        )).when(accounts)
                .requireActiveForTransferAdmission(senderId);

        assertThrows(
                AccessDeniedException.class,
                this::claim
        );

        // KYC was checked first.
        verify(debitAuthorization)
                .lockAndCheckApprovedForAdmission(senderId);

        // Account authorization rejected admission.
        verify(accounts)
                .requireActiveForTransferAdmission(senderId);

        // Further eligibility checks were not reached.
        verify(debitAuthorization, never())
                .requireEligible(senderId);

        // PostgreSQL transaction rolled back.
        assertEquals(0, claimCount());
    }


    @Test
    void kycDenialRollsBackClaim() {

        doThrow(new AccessDeniedException(
                "KYC not approved"
        )).when(debitAuthorization)
                .requireEligible(senderId);

        assertThrows(
                AccessDeniedException.class,
                this::claim
        );

        assertEquals(0, claimCount());
    }

    @Test
    void duplicateClaimDoesNotReauthorize() {

        assertTrue(claim());

        // A replay must not initiate a second claim.
        assertFalse(claim());
        assertEquals(1, claimCount());

        verify(accounts, times(1))
                .requireActiveForTransferAdmission(senderId);
        verify(debitAuthorization, times(1))
                .lockAndCheckApprovedForAdmission(senderId);
        verify(debitAuthorization, times(1))
                .requireEligible(senderId);
    }

    @Test
    void revokedKycPreventsTransferAdmission() {

        when(debitAuthorization
                .lockAndCheckApprovedForAdmission(senderId))
                .thenReturn(false);

        assertThrows(
                AccessDeniedException.class,
                this::claim
        );

        // No transfer claim must be persisted.
        assertEquals(0, claimCount());

        // Since KYC failed, the users row does not
        // need to be locked.
        verifyNoInteractions(accounts);

        // No subsequent eligibility check is necessary.
        verify(debitAuthorization, never())
                .requireEligible(senderId);
    }

}
