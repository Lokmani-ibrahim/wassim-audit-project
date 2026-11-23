
package com.fintech.ledger;

import com.fintech.ledger.CustomerTransferReconciliationInspectionService.Finding;
import com.fintech.ledger.CustomerTransferReconciliationInspectionService.Inspection;
import com.fintech.ledger.CustomerTransferReconciliationQueryService.UnresolvedStatus;
import com.fintech.ledger.port.LedgerOperationResult;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "neos.transfers.reconciliation.admin-api-enabled=true",
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
@AutoConfigureMockMvc
class CustomerTransferReconciliationAdminControllerIntegrationTest {

    private static final String PATH =
            "/api/admin/transfers/reconciliation";

    private final UUID senderId = UUID.randomUUID();
    private final UUID requestKey = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(enforceOverride = true)
    private CustomerTransferReconciliationInspectionService
            inspectionService;

    @MockitoBean(enforceOverride = true)
    private CustomerTransferManualReconciliationService
            reconciliationService;

    private org.springframework.test.web.servlet.request.RequestPostProcessor
    adminJwt() {

        return jwt()
                .jwt(token -> token.subject("reconciliation-admin"))
                .authorities(
                        new SimpleGrantedAuthority(
                                "ROLE_RECONCILIATION_ADMIN"
                        )
                );
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor
    customerJwt() {

        return jwt()
                .jwt(token -> token.subject("customer"))
                .authorities(
                        new SimpleGrantedAuthority(
                                "ROLE_CUSTOMER"
                        )
                );
    }

    private String confirmationPath() {
        return PATH + "/"
                + senderId
                + "/"
                + requestKey
                + "/confirm";
    }

    @Test
    void unauthenticatedInspectionIsRejected()
            throws Exception {

        mockMvc.perform(
                get(PATH)
                        .param("senderId", senderId.toString())
        ).andExpect(status().isUnauthorized());

        verifyNoInteractions(
                inspectionService,
                reconciliationService
        );
    }

    @Test
    void customerCannotInspectTransfers()
            throws Exception {

        mockMvc.perform(
                get(PATH)
                        .with(customerJwt())
                        .param("senderId", senderId.toString())
        ).andExpect(status().isForbidden());

        verifyNoInteractions(
                inspectionService,
                reconciliationService
        );
    }

    @Test
    void adminCanInspectUnresolvedTransfers()
            throws Exception {

        Inspection inspection = new Inspection(
                senderId,
                requestKey,
                "ct_test_operation",
                UnresolvedStatus.UNKNOWN,
                Finding.NOT_FOUND_UNCONFIRMED,
                null
        );

        when(
                inspectionService.inspectForSender(
                        eq(senderId),
                        any(Instant.class),
                        eq(20)
                )
        ).thenReturn(List.of(inspection));

        mockMvc.perform(
                        get(PATH)
                                .with(adminJwt())
                                .param("senderId", senderId.toString())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].senderUserId")
                        .value(senderId.toString()))
                .andExpect(jsonPath("$[0].requestKey")
                        .value(requestKey.toString()))
                .andExpect(jsonPath("$[0].storedStatus")
                        .value("UNKNOWN"))
                .andExpect(jsonPath("$[0].finding")
                        .value("NOT_FOUND_UNCONFIRMED"))
                .andExpect(jsonPath("$[0].formanceTransactionId")
                        .isEmpty());

        verify(inspectionService).inspectForSender(
                eq(senderId),
                any(Instant.class),
                eq(20)
        );

        // Inspection never initiates confirmation.
        verifyNoInteractions(reconciliationService);
    }

    @Test
    void invalidInspectionLimitIsRejected()
            throws Exception {

        mockMvc.perform(
                get(PATH)
                        .with(adminJwt())
                        .param("senderId", senderId.toString())
                        .param("limit", "101")
        ).andExpect(status().isBadRequest());

        mockMvc.perform(
                get(PATH)
                        .with(adminJwt())
                        .param("senderId", senderId.toString())
                        .param("limit", "0")
        ).andExpect(status().isBadRequest());

        verifyNoInteractions(
                inspectionService,
                reconciliationService
        );
    }

    @Test
    void unauthenticatedConfirmationIsRejected()
            throws Exception {

        mockMvc.perform(
                post(confirmationPath())
        ).andExpect(status().isUnauthorized());

        verifyNoInteractions(reconciliationService);
    }

    @Test
    void customerCannotConfirmTransfer()
            throws Exception {

        mockMvc.perform(
                post(confirmationPath())
                        .with(customerJwt())
        ).andExpect(status().isForbidden());

        verifyNoInteractions(reconciliationService);
    }

    @Test
    void adminCanConfirmVerifiedPostedTransfer()
            throws Exception {

        when(
                reconciliationService.confirmPostedTransfer(
                        senderId,
                        requestKey
                )
        ).thenReturn(
                new LedgerOperationResult(
                        12345L,
                        "internal_transfer:ct_test_operation"
                )
        );

        mockMvc.perform(
                        post(confirmationPath())
                                .with(adminJwt())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.transactionId")
                                .value(12345)
                )
                .andExpect(
                        jsonPath("$.reference")
                                .value(
                                        "internal_transfer:ct_test_operation"
                                )
                )
                .andExpect(
                        jsonPath("$.status")
                                .value("SUCCEEDED")
                );

        verify(reconciliationService, times(1))
                .confirmPostedTransfer(
                        senderId,
                        requestKey
                );
    }

    @Test
    void unconfirmedTransferCannotBeConfirmed()
            throws Exception {

        when(
                reconciliationService.confirmPostedTransfer(
                        senderId,
                        requestKey
                )
        ).thenThrow(
                new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Insufficient Formance evidence"
                )
        );

        mockMvc.perform(
                post(confirmationPath())
                        .with(adminJwt())
        ).andExpect(status().isConflict());

        verify(reconciliationService, times(1))
                .confirmPostedTransfer(
                        senderId,
                        requestKey
                );
    }

    @Test
    void missingTransferReturnsNotFound()
            throws Exception {

        when(
                reconciliationService.confirmPostedTransfer(
                        senderId,
                        requestKey
                )
        ).thenThrow(
                new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Transfer record not found"
                )
        );

        mockMvc.perform(
                post(confirmationPath())
                        .with(adminJwt())
        ).andExpect(status().isNotFound());
    }

    @Test
    void formanceFailureDoesNotConfirmTransfer()
            throws Exception {

        when(
                reconciliationService.confirmPostedTransfer(
                        senderId,
                        requestKey
                )
        ).thenThrow(
                new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Reconciliation unavailable"
                )
        );

        mockMvc.perform(
                post(confirmationPath())
                        .with(adminJwt())
        ).andExpect(status().isServiceUnavailable());

        verify(reconciliationService, times(1))
                .confirmPostedTransfer(
                        senderId,
                        requestKey
                );
    }
}
