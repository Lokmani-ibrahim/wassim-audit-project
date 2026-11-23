
package com.fintech.ledger;

import com.fintech.ledger.api.CustomerTransferNotificationRepairAdminController;
import com.fintech.ledger.CustomerTransferNotificationRepairService.Outcome;
import com.fintech.ledger.CustomerTransferNotificationRepairService.RepairBatch;
import com.fintech.ledger.CustomerTransferNotificationRepairService.RepairResult;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "neos.transfers.reconciliation.admin-api-enabled=true",
        "neos.transfers.reconciliation.notification-repair-enabled=true",
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
@AutoConfigureMockMvc
class CustomerTransferNotificationRepairAdminControllerIntegrationTest {

    private static final String BASE_PATH =
            "/api/admin/transfers/reconciliation";

    private final UUID senderId = UUID.randomUUID();
    private final UUID requestKey = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(enforceOverride = true)
    private CustomerTransferNotificationRepairService repairService;

    private String repairPath() {
        return BASE_PATH + "/"
                + senderId
                + "/notifications/repair";
    }

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

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {

        mockMvc.perform(
                post(repairPath())
        ).andExpect(status().isUnauthorized());

        verifyNoInteractions(repairService);
    }

    @Test
    void ordinaryCustomerCannotRepairNotifications()
            throws Exception {

        mockMvc.perform(
                post(repairPath())
                        .with(customerJwt())
        ).andExpect(status().isForbidden());

        verifyNoInteractions(repairService);
    }

    @Test
    void adminCanRepairWithDefaultBatchSize()
            throws Exception {

        when(repairService.repairForSender(
                senderId,
                null,
                25
        )).thenReturn(
                new RepairBatch(
                        List.of(
                                new RepairResult(
                                        requestKey,
                                        Outcome.OUTBOX_PRESENT,
                                        Outcome.STILL_MISSING
                                )
                        ),
                        requestKey
                )
        );

        mockMvc.perform(
                        post(repairPath())
                                .with(adminJwt())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.results[0].requestKey")
                                .value(requestKey.toString())
                )
                .andExpect(
                        jsonPath("$.results[0].senderOutcome")
                                .value("OUTBOX_PRESENT")
                )
                .andExpect(
                        jsonPath("$.results[0].recipientOutcome")
                                .value("STILL_MISSING")
                )
                .andExpect(
                        jsonPath("$.nextCursor")
                                .value(requestKey.toString())
                );

        verify(repairService, times(1))
                .repairForSender(senderId, null, 25);
    }

    @Test
    void adminCanProvidePaginationCursor()
            throws Exception {

        UUID cursor = UUID.randomUUID();

        when(repairService.repairForSender(
                senderId,
                cursor,
                50
        )).thenReturn(
                new RepairBatch(List.of(), null)
        );

        mockMvc.perform(
                        post(repairPath())
                                .with(adminJwt())
                                .param(
                                        "afterRequestKey",
                                        cursor.toString()
                                )
                                .param("batchSize", "50")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.results").isEmpty())
                .andExpect(jsonPath("$.nextCursor").isEmpty());

        verify(repairService).repairForSender(
                senderId,
                cursor,
                50
        );
    }

    @Test
    void zeroBatchSizeIsRejected() throws Exception {

        mockMvc.perform(
                post(repairPath())
                        .with(adminJwt())
                        .param("batchSize", "0")
        ).andExpect(status().isBadRequest());

        verifyNoInteractions(repairService);
    }

    @Test
    void excessiveBatchSizeIsRejected() throws Exception {

        mockMvc.perform(
                post(repairPath())
                        .with(adminJwt())
                        .param("batchSize", "101")
        ).andExpect(status().isBadRequest());

        verifyNoInteractions(repairService);
    }

    @Test
    void invalidCursorIsRejected() throws Exception {

        mockMvc.perform(
                post(repairPath())
                        .with(adminJwt())
                        .param(
                                "afterRequestKey",
                                "not-a-uuid"
                        )
        ).andExpect(status().isBadRequest());

        verifyNoInteractions(repairService);
    }

    @Test
    void repairFailureReturnsServiceUnavailable()
            throws Exception {

        when(repairService.repairForSender(
                senderId,
                null,
                25
        )).thenThrow(
                new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Notification repair unavailable"
                )
        );

        mockMvc.perform(
                post(repairPath())
                        .with(adminJwt())
        ).andExpect(status().isServiceUnavailable());

        verify(repairService, times(1))
                .repairForSender(senderId, null, 25);
    }

    @Test
    void controllerRequiresBothFeatureFlags() {

        assertControllerAbsent("false", "true");
        assertControllerAbsent("true", "false");
        assertControllerAbsent("false", "false");
    }

    private void assertControllerAbsent(
            String adminEnabled,
            String repairEnabled
    ) {

        new ApplicationContextRunner()
                .withPropertyValues(
                        "neos.transfers.reconciliation.admin-api-enabled="
                                + adminEnabled,
                        "neos.transfers.reconciliation.notification-repair-enabled="
                                + repairEnabled
                )
                .withBean(
                        CustomerTransferNotificationRepairService.class,
                        () -> mock(
                                CustomerTransferNotificationRepairService.class
                        )
                )
                .withUserConfiguration(
                        CustomerTransferNotificationRepairAdminController.class
                )
                .run(context -> assertThat(context)
                        .doesNotHaveBean(
                                CustomerTransferNotificationRepairAdminController.class
                        ));
    }
}
