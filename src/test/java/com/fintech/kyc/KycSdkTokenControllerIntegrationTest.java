
package com.fintech.kyc;

import com.fintech.kyc.port.KycProvider;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
@AutoConfigureMockMvc
class KycSdkTokenControllerIntegrationTest {

    private static final String ENDPOINT =
            "/api/kyc/sdk-token";

    private static final String SUBJECT =
            "sdk-test-customer";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(enforceOverride = true)
    private KycSdkTokenService sdkTokenService;

    private org.springframework.test.web.servlet.request.RequestPostProcessor
    customerJwt() {
        return jwt()
                .jwt(token -> token.subject(SUBJECT))
                .authorities(
                        new SimpleGrantedAuthority(
                                "ROLE_CUSTOMER"
                        )
                );
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor
    adminJwt() {
        return jwt()
                .jwt(token -> token.subject("admin"))
                .authorities(
                        new SimpleGrantedAuthority(
                                "ROLE_RECONCILIATION_ADMIN"
                        )
                );
    }

    @Test
    void unauthenticatedRequestIsRejected()
            throws Exception {

        mockMvc.perform(
                post(ENDPOINT)
        ).andExpect(status().isUnauthorized());

        verifyNoInteractions(sdkTokenService);
    }

    @Test
    void userWithoutCustomerRoleIsRejected()
            throws Exception {

        mockMvc.perform(
                post(ENDPOINT).with(adminJwt())
        ).andExpect(status().isForbidden());

        verifyNoInteractions(sdkTokenService);
    }

    @Test
    void authenticatedCustomerReceivesSdkToken()
            throws Exception {

        when(sdkTokenService.createSession(SUBJECT))
                .thenReturn(
                        new KycProvider.VerificationSession(
                                "mobile-sdk-token",
                                600
                        )
                );

        mockMvc.perform(
                        post(ENDPOINT).with(customerJwt())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.token")
                                .value("mobile-sdk-token")
                )
                .andExpect(
                        jsonPath("$.expiresInSeconds")
                                .value(600)
                )
                .andExpect(
                        header().string(
                                HttpHeaders.CACHE_CONTROL,
                                containsString("no-store")
                        )
                )
                .andExpect(
                        header().string(
                                HttpHeaders.PRAGMA,
                                "no-cache"
                        )
                );

        verify(sdkTokenService, times(1))
                .createSession(SUBJECT);
    }

    @Test
    void ineligibleKycReturnsConflict()
            throws Exception {

        when(sdkTokenService.createSession(SUBJECT))
                .thenThrow(
                        new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "KYC is not eligible"
                        )
                );

        mockMvc.perform(
                post(ENDPOINT).with(customerJwt())
        ).andExpect(status().isConflict());
    }

    @Test
    void blockedCustomerReturnsForbidden()
            throws Exception {

        when(sdkTokenService.createSession(SUBJECT))
                .thenThrow(
                        new ResponseStatusException(
                                HttpStatus.FORBIDDEN,
                                "Customer account not eligible"
                        )
                );

        mockMvc.perform(
                post(ENDPOINT).with(customerJwt())
        ).andExpect(status().isForbidden());
    }
}
