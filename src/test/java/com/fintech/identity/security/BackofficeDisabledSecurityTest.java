package com.fintech.identity.security;

import com.fintech.identity.api.BackofficeMeController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BackofficeMeController.class, properties = "neos.backoffice.enabled=false")
@Import({SecurityConfig.class, StaffAccessPolicy.class, BackofficeDisabledSecurityTest.DecoderConfig.class})
class BackofficeDisabledSecurityTest {
    @Autowired MockMvc mvc;

    @Test
    void disabledStaffRouteDeniesEvenComplianceTokenWithoutStaffConfiguration() throws Exception {
        mvc.perform(get("/api/backoffice/me").with(jwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_COMPLIANCE_OFFICER"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/backoffice/me")).andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class DecoderConfig {
        @Bean JwtDecoder jwtDecoder() {
            return token -> { throw new IllegalArgumentException("Not used in this disabled-route test"); };
        }
    }
}
