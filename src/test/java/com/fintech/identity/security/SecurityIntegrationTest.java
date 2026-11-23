package com.fintech.identity.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRejectRequestWithoutAuthentication() throws Exception {

        mockMvc.perform(
                        get("/api/auth/me")
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAllowCustomerToAccessCustomerEndpoint() throws Exception {

        mockMvc.perform(
                        get("/api/auth/me")
                                .with(
                                        jwt()
                                                .jwt(jwt -> jwt
                                                        .subject("keycloak-user-123")
                                                        .claim(
                                                                "preferred_username",
                                                                "test.customer"
                                                        )
                                                        .claim(
                                                                "email",
                                                                "test.customer@neos.local"
                                                        )
                                                )
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "ROLE_CUSTOMER"
                                                        )
                                                )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.subject")
                                .value("keycloak-user-123")
                )
                .andExpect(
                        jsonPath("$.username")
                                .value("test.customer")
                )
                .andExpect(
                        jsonPath("$.email")
                                .value("test.customer@neos.local")
                );
    }

    @Test
    void shouldRejectComplianceOfficerFromCustomerEndpoint() throws Exception {

        mockMvc.perform(
                        get("/api/auth/me")
                                .with(
                                        jwt()
                                                .jwt(jwt -> jwt
                                                        .subject("compliance-user-123")
                                                        .claim(
                                                                "preferred_username",
                                                                "test.compliance"
                                                        )
                                                        .claim(
                                                                "email",
                                                                "test.compliance@neos.local"
                                                        )
                                                )
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "ROLE_COMPLIANCE_OFFICER"
                                                        )
                                                )
                                )
                )
                .andExpect(status().isForbidden());
    }
}