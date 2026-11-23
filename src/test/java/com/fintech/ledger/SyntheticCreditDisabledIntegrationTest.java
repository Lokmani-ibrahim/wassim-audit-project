package com.fintech.ledger;

import com.fintech.ledger.api.SyntheticCreditController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        properties = {
                "neos.backoffice.synthetic-credit.enabled=false"
        }
)
@AutoConfigureMockMvc
class SyntheticCreditDisabledIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void syntheticCreditFeatureIsNotCreatedWhenDisabled()
            throws Exception {

        /*
         * Neither the service nor its HTTP controller
         * may exist when the feature flag is disabled.
         */
        assertThat(
                applicationContext.getBeansOfType(
                        SyntheticCreditService.class
                )
        ).isEmpty();

        assertThat(
                applicationContext.getBeansOfType(
                        SyntheticCreditController.class
                )
        ).isEmpty();

        /*
         * Use an authenticated backoffice identity so a 401/403
         * cannot hide the fact that the endpoint itself is absent.
         */
        mockMvc.perform(
                        post(
                                "/api/backoffice/synthetic-credits"
                        )
                                .with(
                                        jwt()
                                                .jwt(
                                                        token ->
                                                                token.subject(
                                                                        "disabled-feature-test"
                                                                )
                                                )
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "ROLE_COMPLIANCE_OFFICER"
                                                        )
                                                )
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "userId": "%s",
                                          "currency": "EUR",
                                          "amountMinor": 10000,
                                          "operationId": "c3_disabled_test"
                                        }
                                        """
                                                .formatted(
                                                        UUID.randomUUID()
                                                )
                                )
                )
                .andExpect(
                        status().isNotFound()
                );
    }
}