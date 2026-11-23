
package com.fintech.kyc.api;

import com.fintech.kyc.KycApiErrorCode;
import com.fintech.kyc.KycApiException;
import com.fintech.kyc.KycOnboardingRequiredException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KycApiErrorHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ErrorProbeController())
                .setControllerAdvice(new KycApiErrorHandler())
                .build();
    }

    @Test
    void everyKnownErrorHasStableJsonContract()
            throws Exception {

        for (KycApiErrorCode code :
                KycApiErrorCode.values()) {

            mockMvc.perform(
                            get("/__test/kyc/error/{code}",
                                    code.name())
                    )
                    .andExpect(status().is(code.status().value()))
                    .andExpect(content().contentTypeCompatibleWith(
                            MediaType.APPLICATION_JSON
                    ))
                    .andExpect(jsonPath("$.errorCode")
                            .value(code.name()))
                    .andExpect(jsonPath("$.message")
                            .value(code.message()))
                    .andExpect(jsonPath("$.status")
                            .value(code.status().value()))
                    .andExpect(header().string(
                            "Cache-Control",
                            containsString("no-store")
                    ))
                    .andExpect(header().string(
                            "Pragma",
                            "no-cache"
                    ));
        }
    }

    @Test
    void existingOnboardingExceptionReturnsStableCode()
            throws Exception {

        mockMvc.perform(
                        get("/__test/kyc/onboarding")
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode")
                        .value("ONBOARDING_REQUIRED"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(header().string(
                        "Cache-Control",
                        containsString("no-store")
                ));
    }

    @RestController
    static class ErrorProbeController {

        @GetMapping("/__test/kyc/error/{code}")
        public void throwKycError(
                @PathVariable String code
        ) {
            throw new KycApiException(
                    KycApiErrorCode.valueOf(code)
            );
        }

        @GetMapping("/__test/kyc/onboarding")
        public void throwOnboardingError() {
            throw new KycOnboardingRequiredException();
        }
    }
}
