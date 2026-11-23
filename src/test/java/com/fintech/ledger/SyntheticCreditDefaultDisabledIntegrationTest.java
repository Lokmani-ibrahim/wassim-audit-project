
package com.fintech.ledger;

import com.fintech.ledger.api.SyntheticCreditController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SyntheticCreditDefaultDisabledIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void syntheticCreditIsDisabledWithoutExplicitEnablement()
            throws Exception {

        // No synthetic-credit test property override.
        // This verifies the resolved application default.
        assertThat(environment.getProperty(
                "neos.backoffice.synthetic-credit.enabled",
                Boolean.class
        )).isFalse();

        assertThat(context.getBeansOfType(
                SyntheticCreditService.class
        )).isEmpty();

        assertThat(context.getBeansOfType(
                SyntheticCreditController.class
        )).isEmpty();

        // Authenticate as an officer so an authorization
        // error cannot conceal an active controller.
        mockMvc.perform(
                post("/api/backoffice/synthetic-credits")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority(
                                        "ROLE_COMPLIANCE_OFFICER"
                                )
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "00000000-0000-0000-0000-000000000001",
                                  "currency": "EUR",
                                  "amountMinor": 10000,
                                  "operationId": "default_disabled_test"
                                }
                                """)
        ).andExpect(status().isNotFound());
    }
}
