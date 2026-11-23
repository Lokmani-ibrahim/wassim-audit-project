package com.fintech.kyc;

import com.fintech.kyc.adapter.sumsub.SumsubReviewedEvent;
import com.fintech.kyc.api.SumsubWebhookController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.webhook-enabled=true",
        "neos.kyc.sumsub.webhook-secret=test-webhook-secret",
        "neos.kyc.sumsub.sandbox-mode=true",
        "neos.kyc.sumsub.app-token=test-app-token",
        "neos.kyc.sumsub.secret-key=test-secret",
        "neos.kyc.sumsub.level-name=id-and-liveness"
})
@AutoConfigureMockMvc
class SumsubWebhookControllerIntegrationTest {

    private static final String SECRET =
            "test-webhook-secret";

    private static final String PATH =
            "/api/webhooks/sumsub";

    private static final String BODY = """
            {
              "type": "applicantReviewed",
              "applicantType": "individual",
              "applicantId": "sandbox-applicant-123",
              "externalUserId":
                "00000000-0000-0000-0000-000000000001",
              "levelName": "id-and-liveness",
              "sandboxMode": true,
              "reviewStatus": "completed",
              "createdAtMs": "2026-10-27 14:00:00.000",
              "reviewResult": {
                "reviewAnswer": "GREEN"
              }
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private SumsubDecisionService decisionService;

    private String digest(String body) throws Exception {

        Mac mac = Mac.getInstance("HmacSHA256");

        mac.init(new SecretKeySpec(
                SECRET.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        ));

        return HexFormat.of().formatHex(
                mac.doFinal(
                        body.getBytes(StandardCharsets.UTF_8)
                )
        );
    }

    private org.springframework.test.web.servlet.ResultActions
    signedPost(String body) throws Exception {

        return mockMvc.perform(
                post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8))
                        .header(
                                "X-Payload-Digest",
                                digest(body)
                        )
                        .header(
                                "X-Payload-Digest-Alg",
                                "HMAC_SHA256_HEX"
                        )
        );
    }

    @Test
    void validApprovalIsProcessedAndAcknowledged()
            throws Exception {

        when(decisionService.applyVerifiedReview(
                any(SumsubReviewedEvent.class)
        )).thenReturn(
                SumsubDecisionService.Outcome.APPLIED
        );

        signedPost(BODY)
                .andExpect(status().isOk());

        verify(decisionService).applyVerifiedReview(
                argThat(event ->
                        event.decision()
                                == SumsubReviewedEvent.Decision.APPROVED
                                && event.applicantId()
                                .equals("sandbox-applicant-123")
                )
        );
    }

    @Test
    void missingSignatureReturns401() throws Exception {

        mockMvc.perform(
                post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
        ).andExpect(status().isUnauthorized());

        verifyNoInteractions(decisionService);
    }

    @Test
    void incorrectSignatureReturns401() throws Exception {

        mockMvc.perform(
                post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                        .header(
                                "X-Payload-Digest",
                                "00".repeat(32)
                        )
                        .header(
                                "X-Payload-Digest-Alg",
                                "HMAC_SHA256_HEX"
                        )
        ).andExpect(status().isUnauthorized());

        verifyNoInteractions(decisionService);
    }

    @Test
    void invalidVerificationLevelReturns400()
            throws Exception {

        String invalid = BODY.replace(
                "id-and-liveness",
                "unexpected-level"
        );

        signedPost(invalid)
                .andExpect(status().isBadRequest());

        verifyNoInteractions(decisionService);
    }

    @Test
    void malformedSignedJsonReturns400() throws Exception {

        signedPost("{invalid-json")
                .andExpect(status().isBadRequest());

        verifyNoInteractions(decisionService);
    }

    @Test
    void duplicateWebhookIsAcknowledged()
            throws Exception {

        when(decisionService.applyVerifiedReview(
                any(SumsubReviewedEvent.class)
        )).thenReturn(
                SumsubDecisionService.Outcome.DUPLICATE
        );

        signedPost(BODY)
                .andExpect(status().isOk());
    }

    @Test
    void staleWebhookIsAcknowledged()
            throws Exception {

        when(decisionService.applyVerifiedReview(
                any(SumsubReviewedEvent.class)
        )).thenReturn(
                SumsubDecisionService.Outcome.STALE
        );

        signedPost(BODY)
                .andExpect(status().isOk());
    }

    @Test
    void decisionProcessingFailureReturns503()
            throws Exception {

        when(decisionService.applyVerifiedReview(
                any(SumsubReviewedEvent.class)
        )).thenThrow(
                new IllegalStateException(
                        "Simulated database failure"
                )
        );

        signedPost(BODY)
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void ordinaryKycEndpointStillRequiresJwt()
            throws Exception {

        mockMvc.perform(
                post("/api/kyc/start")
        ).andExpect(status().isUnauthorized());
    }

    @Test
    void webhookControllerIsRegistered() {

        assertEquals(
                1,
                context.getBeansOfType(
                        SumsubWebhookController.class
                ).size()
        );
    }
}