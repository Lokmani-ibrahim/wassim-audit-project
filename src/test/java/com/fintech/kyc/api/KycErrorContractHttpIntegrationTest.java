
package com.fintech.kyc.api;

import com.fintech.kyc.KycApiErrorCode;
import com.fintech.kyc.KycApiException;
import com.fintech.kyc.KycOnboardingRequiredException;
import com.fintech.kyc.KycSdkTokenService;
import com.fintech.kyc.KycStartResult;
import com.fintech.kyc.KycStartService;
import com.fintech.kyc.port.KycProvider;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false",
        "neos.ledger.wallet-provisioning.recovery.enabled=false"
})
@AutoConfigureMockMvc
class KycErrorContractHttpIntegrationTest {

    private static final String SUBJECT =
            "kyc-error-contract-customer";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(enforceOverride = true)
    private KycSdkTokenService sdkTokenService;

    @MockitoBean(enforceOverride = true)
    private KycStartService kycStartService;



    @Test
    void sdkTokenReturnsStableErrorCodes() throws Exception {

        List<KycApiErrorCode> codes = List.of(
                KycApiErrorCode.KYC_NOT_STARTED,
                KycApiErrorCode.KYC_ALREADY_APPROVED,
                KycApiErrorCode.KYC_FINAL_REJECTED,
                KycApiErrorCode.ACCOUNT_RESTRICTED,
                KycApiErrorCode.KYC_MANUAL_REVIEW_REQUIRED,
                KycApiErrorCode.KYC_APPLICANT_INVALID,
                KycApiErrorCode.KYC_STATE_NOT_ELIGIBLE,
                KycApiErrorCode.SUMSUB_UPSTREAM_ERROR
        );

        for (KycApiErrorCode code : codes) {

            /*
             * Use doThrow instead of when().thenThrow().
             *
             * This prevents Mockito from invoking the
             * previously configured exception stub.
             */
            doThrow(new KycApiException(code))
                    .when(sdkTokenService)
                    .createSession(SUBJECT);

            /*
             * Verify the actual HTTP response produced
             * by Spring MVC and KycApiErrorHandler.
             */
            mockMvc.perform(
                            post("/api/kyc/sdk-token")
                                    .with(customer())
                    )
                    .andExpect(
                            status().is(code.status().value())
                    )
                    .andExpect(
                            content().contentTypeCompatibleWith(
                                    MediaType.APPLICATION_JSON
                            )
                    )
                    .andExpect(
                            jsonPath("$.errorCode")
                                    .value(code.name())
                    )
                    .andExpect(
                            jsonPath("$.message")
                                    .value(code.message())
                    )
                    .andExpect(
                            jsonPath("$.status")
                                    .value(code.status().value())
                    )
                    .andExpect(
                            header().string(
                                    "Cache-Control",
                                    containsString("no-store")
                            )
                    )
                    .andExpect(
                            header().string(
                                    "Pragma",
                                    "no-cache"
                            )
                    );
        }

        /*
         * Every code must have been exercised exactly
         * once through the HTTP endpoint.
         */
        verify(
                sdkTokenService,
                times(codes.size())
        ).createSession(SUBJECT);
    }


    @Test
    void sdkTokenReturnsOriginalSuccessContract()
            throws Exception {

        reset(sdkTokenService);

        when(sdkTokenService.createSession(SUBJECT))
                .thenReturn(
                        new KycProvider.VerificationSession(
                                "test-sdk-token",
                                600
                        )
                );

        mockMvc.perform(
                        post("/api/kyc/sdk-token")
                                .with(customer())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.token")
                                .value("test-sdk-token")
                )
                .andExpect(
                        jsonPath("$.expiresInSeconds")
                                .value(600)
                )
                .andExpect(
                        header().string(
                                "Cache-Control",
                                containsString("no-store")
                        )
                );
    }

    @Test
    void sdkTokenWithoutAuthenticationIsUnauthorized()
            throws Exception {

        mockMvc.perform(
                        post("/api/kyc/sdk-token")
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(sdkTokenService);
    }

    @Test
    void startReturnsOnboardingRequiredCode()
            throws Exception {

        when(kycStartService.start(SUBJECT))
                .thenThrow(
                        new KycOnboardingRequiredException()
                );

        mockMvc.perform(
                        post("/api/kyc/start")
                                .with(customer())
                )
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("ONBOARDING_REQUIRED")
                )
                .andExpect(
                        jsonPath("$.status")
                                .value(409)
                )
                .andExpect(
                        header().string(
                                "Cache-Control",
                                containsString("no-store")
                        )
                );
    }

    @Test
    void startPreservesSuccessContract()
            throws Exception {

        UUID userId = UUID.randomUUID();

        when(kycStartService.start(SUBJECT))
                .thenReturn(
                        new KycStartResult(
                                userId,
                                "PENDING",
                                "SUMSUB",
                                "NON_PROD"
                        )
                );

        mockMvc.perform(
                        post("/api/kyc/start")
                                .with(customer())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.userId")
                                .value(userId.toString())
                )
                .andExpect(
                        jsonPath("$.status")
                                .value("PENDING")
                )
                .andExpect(
                        jsonPath("$.provider")
                                .value("SUMSUB")
                )
                .andExpect(
                        jsonPath("$.verificationMode")
                                .value("NON_PROD")
                );
    }



    @Test
    void startReturnsServiceUnavailableWhenSumsubFails()
            throws Exception {

        when(kycStartService.start(SUBJECT))
                .thenThrow(
                        new KycApiException(
                                KycApiErrorCode.SUMSUB_UNAVAILABLE
                        )
                );

        mockMvc.perform(
                        post("/api/kyc/start")
                                .with(customer())
                )
                .andExpect(status().isServiceUnavailable())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value("SUMSUB_UNAVAILABLE")
                )
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        KycApiErrorCode
                                                .SUMSUB_UNAVAILABLE
                                                .message()
                                )
                )
                .andExpect(
                        jsonPath("$.status")
                                .value(503)
                )
                .andExpect(
                        header().string(
                                "Cache-Control",
                                containsString("no-store")
                        )
                );

        verify(kycStartService, times(1))
                .start(SUBJECT);
    }
    private static RequestPostProcessor customer() {

        return jwt()
                .jwt(token -> token.subject(SUBJECT))
                .authorities(
                        new SimpleGrantedAuthority(
                                "ROLE_CUSTOMER"
                        )
                );
    }

}
