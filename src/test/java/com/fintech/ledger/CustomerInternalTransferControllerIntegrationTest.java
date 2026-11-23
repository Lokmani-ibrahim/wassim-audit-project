
package com.fintech.ledger;

import com.fintech.identity.IdentityLookupService;
import com.fintech.kyc.CustomerDebitAuthorization;
import com.fintech.ledger.api.CustomerInternalTransferController;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;
import com.fintech.identity.UserAccountStatusLookup;
import com.fintech.kyc.CustomerDebitAuthorization;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.AfterEach;
import org.springframework.jdbc.core.JdbcTemplate;


import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@SpringBootTest(properties = {
        "neos.transfers.customer-api-enabled=true",
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
@AutoConfigureMockMvc
class CustomerInternalTransferControllerIntegrationTest {

    private static final String PATH =
            "/api/transfers/internal";

    private static final String SUBJECT =
            "keycloak-test-customer";


    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(enforceOverride = true)
    private IdentityLookupService identityLookupService;

    @MockitoBean(enforceOverride = true)
    private CustomerDebitAuthorization authorization;

    @MockitoBean(enforceOverride = true)
    private InternalTransferService transferService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoBean(enforceOverride = true)
    private UserAccountStatusLookup accounts;

    @MockitoBean(enforceOverride = true)
    private CustomerDebitAuthorization debitAuthorization;
    private final UUID senderId = UUID.randomUUID();
    private final UUID recipientId = UUID.randomUUID();
    private final UUID requestKey = UUID.randomUUID();

    private String validRequest() {
        return """
                {
                  "destinationUserId": "%s",
                  "currency": "EUR",
                  "amountMinor": 5000,
                  "idempotencyKey": "%s"
                }
                """.formatted(recipientId, requestKey);
    }

    private String expectedOperationId() {
        return "ct_"
                + senderId.toString().replace("-", "")
                + "_"
                + requestKey.toString().replace("-", "");
    }

    @BeforeEach
    void allowApprovedKycAdmission() {

        lenient()
                .doReturn(true)
                .when(debitAuthorization)
                .lockAndCheckApprovedForAdmission(
                        any(UUID.class)
                );
    }

    @AfterEach
    void cleanupIdempotencyRecords() {

        jdbcTemplate.update(
                """
                DELETE FROM customer_transfer_idempotency
                WHERE sender_user_id = ?
                """,
                senderId
        );
    }

    @Test
    void authenticatedCustomerUsesServerResolvedSender()
            throws Exception {

        when(identityLookupService
                .findUserIdByKeycloakSubject(SUBJECT))
                .thenReturn(Optional.of(senderId));

        when(transferService.transfer(
                senderId,
                recipientId,
                LedgerCurrency.EUR,
                5000L,
                expectedOperationId()
        )).thenReturn(
                new LedgerOperationResult(
                        123L,
                        "internal_transfer:test"
                )
        );

        mockMvc.perform(
                        post(PATH)
                                .with(jwt()
                                        .jwt(j -> j.subject(SUBJECT))
                                        .authorities(
                                                new SimpleGrantedAuthority(
                                                        "ROLE_CUSTOMER"
                                                )
                                        ))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(123))
                .andExpect(jsonPath("$.reference")
                        .value("internal_transfer:test"));

        verify(identityLookupService)
                .findUserIdByKeycloakSubject(SUBJECT);

        verify(authorization)
                .requireEligible(recipientId);

        verify(transferService).transfer(
                senderId,
                recipientId,
                LedgerCurrency.EUR,
                5000L,
                expectedOperationId()
        );
    }

    @Test
    void unauthenticatedRequestIsRejected()
            throws Exception {

        mockMvc.perform(
                post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest())
        ).andExpect(status().isUnauthorized());

        verifyNoInteractions(transferService);
    }

    @Test
    void nonCustomerRoleIsRejected()
            throws Exception {

        mockMvc.perform(
                post(PATH)
                        .with(jwt()
                                .jwt(j -> j.subject(SUBJECT))
                                .authorities(
                                        new SimpleGrantedAuthority(
                                                "ROLE_USER"
                                        )
                                ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest())
        ).andExpect(status().isForbidden());

        verifyNoInteractions(transferService);
    }

    @Test
    void missingLocalCustomerProfileIsRejected()
            throws Exception {

        when(identityLookupService
                .findUserIdByKeycloakSubject(SUBJECT))
                .thenReturn(Optional.empty());

        mockMvc.perform(
                post(PATH)
                        .with(jwt()
                                .jwt(j -> j.subject(SUBJECT))
                                .authorities(
                                        new SimpleGrantedAuthority(
                                                "ROLE_CUSTOMER"
                                        )
                                ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest())
        ).andExpect(status().isForbidden());

        verifyNoInteractions(transferService);
    }

    @Test
    void ineligibleRecipientCannotReceiveInternalTransfer()
            throws Exception {

        when(identityLookupService
                .findUserIdByKeycloakSubject(SUBJECT))
                .thenReturn(Optional.of(senderId));

        doThrow(new AccessDeniedException(
                "Recipient not eligible"
        ))
                .when(authorization)
                .requireEligible(recipientId);

        mockMvc.perform(
                post(PATH)
                        .with(jwt()
                                .jwt(j -> j.subject(SUBJECT))
                                .authorities(
                                        new SimpleGrantedAuthority(
                                                "ROLE_CUSTOMER"
                                        )
                                ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest())
        ).andExpect(status().isForbidden());

        verifyNoInteractions(transferService);
    }

    @Test
    void invalidTransferAmountReturns400()
            throws Exception {

        String invalidRequest =
                validRequest().replace(
                        "\"amountMinor\": 5000",
                        "\"amountMinor\": 0"
                );

        mockMvc.perform(
                post(PATH)
                        .with(jwt()
                                .jwt(j -> j.subject(SUBJECT))
                                .authorities(
                                        new SimpleGrantedAuthority(
                                                "ROLE_CUSTOMER"
                                        )
                                ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequest)
        ).andExpect(status().isBadRequest());

        verifyNoInteractions(transferService);
    }

    @Test
    void selfTransferIsRejected()
            throws Exception {

        when(identityLookupService
                .findUserIdByKeycloakSubject(SUBJECT))
                .thenReturn(Optional.of(recipientId));

        mockMvc.perform(
                post(PATH)
                        .with(jwt()
                                .jwt(j -> j.subject(SUBJECT))
                                .authorities(
                                        new SimpleGrantedAuthority(
                                                "ROLE_CUSTOMER"
                                        )
                                ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest())
        ).andExpect(status().isBadRequest());

        verifyNoInteractions(transferService);
    }
    @Test
    void sameIdempotencyKeyCannotBeUsedWithDifferentAmount()
            throws Exception {

        when(identityLookupService
                .findUserIdByKeycloakSubject(SUBJECT))
                .thenReturn(Optional.of(senderId));

        when(transferService.transfer(
                eq(senderId),
                eq(recipientId),
                eq(LedgerCurrency.EUR),
                anyLong(),
                eq(expectedOperationId())
        )).thenReturn(
                new LedgerOperationResult(
                        123L,
                        "internal_transfer:idempotency-test"
                )
        );

        // STEP 1: First transfer is accepted.
        mockMvc.perform(
                post(PATH)
                        .with(jwt()
                                .jwt(j -> j.subject(SUBJECT))
                                .authorities(
                                        new SimpleGrantedAuthority(
                                                "ROLE_CUSTOMER"
                                        )
                                ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest())
        ).andExpect(status().isOk());

        // STEP 2: Reuse the same key with a different amount.
        String conflictingRequest = validRequest().replace(
                "\"amountMinor\": 5000",
                "\"amountMinor\": 6000"
        );

        // STEP 3: NEOS must reject the conflicting request.
        mockMvc.perform(
                post(PATH)
                        .with(jwt()
                                .jwt(j -> j.subject(SUBJECT))
                                .authorities(
                                        new SimpleGrantedAuthority(
                                                "ROLE_CUSTOMER"
                                        )
                                ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflictingRequest)
        ).andExpect(status().isConflict());

        // Only the original transfer may execute.
        verify(transferService, times(1))
                .transfer(
                        senderId,
                        recipientId,
                        LedgerCurrency.EUR,
                        5000L,
                        expectedOperationId()
                );
    }
    @Test
    void exactRetryReturnsOriginalResultWithoutExecutingAgain()
            throws Exception {

        when(identityLookupService
                .findUserIdByKeycloakSubject(SUBJECT))
                .thenReturn(Optional.of(senderId));

        when(transferService.transfer(
                senderId,
                recipientId,
                LedgerCurrency.EUR,
                5000L,
                expectedOperationId()
        )).thenReturn(
                new LedgerOperationResult(
                        456L,
                        "internal_transfer:original"
                )
        );

        // STEP 1: Original request.
        mockMvc.perform(
                        post(PATH)
                                .with(jwt()
                                        .jwt(j -> j.subject(SUBJECT))
                                        .authorities(
                                                new SimpleGrantedAuthority(
                                                        "ROLE_CUSTOMER"
                                                )
                                        ))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(456));

        // STEP 2: Exact same request sent again.
        mockMvc.perform(
                        post(PATH)
                                .with(jwt()
                                        .jwt(j -> j.subject(SUBJECT))
                                        .authorities(
                                                new SimpleGrantedAuthority(
                                                        "ROLE_CUSTOMER"
                                                )
                                        ))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(456))
                .andExpect(jsonPath("$.reference")
                        .value("internal_transfer:original"));

        // CRITICAL:
        // Returning the original result must not execute
        // another ledger operation.
        verify(transferService, times(1))
                .transfer(
                        senderId,
                        recipientId,
                        LedgerCurrency.EUR,
                        5000L,
                        expectedOperationId()
                );
    }

}
