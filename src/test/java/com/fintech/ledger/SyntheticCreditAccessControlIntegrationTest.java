
package com.fintech.ledger;

import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerOperationResult;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "neos.backoffice.synthetic-credit.enabled=true"
})
@AutoConfigureMockMvc
class SyntheticCreditAccessControlIntegrationTest {

    private static final String ENDPOINT =
            "/api/backoffice/synthetic-credits";

    private static final String OPERATION_ID =
            "security_http_test";

    @Autowired
    private MockMvc mockMvc;

    // Replaces the actual financial service.
    // No Formance ledger operation can be submitted
    // through these controller requests.
    @MockitoBean(enforceOverride = true)
    private SyntheticCreditService syntheticCreditService;

    private String requestBody(UUID userId) {

        return """
                {
                  "userId": "%s",
                  "currency": "EUR",
                  "amountMinor": 10000,
                  "operationId": "%s"
                }
                """.formatted(userId, OPERATION_ID);
    }

    @Test
    void unauthenticatedRequestIsRejectedWithoutPosting()
            throws Exception {

        mockMvc.perform(
                post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(UUID.randomUUID()))
        ).andExpect(status().isUnauthorized());

        verifyNoInteractions(syntheticCreditService);
    }

    @Test
    void customerCannotCreateSyntheticCredit()
            throws Exception {

        mockMvc.perform(
                post(ENDPOINT)
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority(
                                        "ROLE_CUSTOMER"
                                )
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(UUID.randomUUID()))
        ).andExpect(status().isForbidden());

        verifyNoInteractions(syntheticCreditService);
    }

    @Test
    void complianceOfficerCanAccessEnabledEndpoint()
            throws Exception {

        UUID userId = UUID.randomUUID();

        // Mock the ledger result as well.
        // This test checks authorization and dispatch,
        // not the Formance transaction format.
        LedgerOperationResult result =
                mock(LedgerOperationResult.class);

        when(syntheticCreditService.credit(
                userId,
                LedgerCurrency.EUR,
                10_000L,
                OPERATION_ID
        )).thenReturn(result);

        mockMvc.perform(
                post(ENDPOINT)
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority(
                                        "ROLE_COMPLIANCE_OFFICER"
                                )
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(userId))
        ).andExpect(status().isOk());

        verify(syntheticCreditService, times(1))
                .credit(
                        userId,
                        LedgerCurrency.EUR,
                        10_000L,
                        OPERATION_ID
                );

        verifyNoMoreInteractions(syntheticCreditService);
    }
}
