
package com.fintech.kyc;

import com.fintech.identity.entity.AccountStatus;
import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.repository.KycStatusRepository;
import com.fintech.ledger.WalletProvisioningService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.webhook-enabled=true",
        "neos.kyc.sumsub.webhook-secret=test-webhook-secret",
        "neos.kyc.sumsub.sandbox-mode=true",
        "neos.kyc.sumsub.app-token=test-app-token",
        "neos.kyc.sumsub.secret-key=test-secret",
        "neos.kyc.sumsub.level-name=id-and-liveness",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
@AutoConfigureMockMvc
class SumsubWebhookEndToEndIntegrationTest {

    private static final String PATH = "/api/webhooks/sumsub";
    private static final String SECRET = "test-webhook-secret";

    private static final Instant BASE_TIME =
            Instant.parse("2026-10-27T12:00:00Z");

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                    .withZone(ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KycStatusRepository kycRepository;

    // Real decision service and PostgreSQL transactions.
    // Only the outbound Formance provisioning is mocked.
    @MockitoBean(enforceOverride = true)
    private WalletProvisioningService walletProvisioningService;

    private UUID userId;
    private String applicantId;

    @BeforeEach
    void setup() {
        String unique = UUID.randomUUID().toString();

        UserEntity user = userRepository.saveAndFlush(
                new UserEntity(
                        "sumsub-e2e-" + unique,
                        "sumsub-e2e-" + unique + "@example.com",
                        null
                )
        );

        userId = user.getId();
        applicantId = "sandbox-applicant-" + unique;

        KycStatusEntity kyc = new KycStatusEntity(userId);
        kyc.startVerification("SUMSUB", applicantId);

        kycRepository.saveAndFlush(kyc);
    }

    @AfterEach
    void cleanup() {
        if (userId != null) {
            kycRepository.findByUserId(userId)
                    .ifPresent(kycRepository::delete);

            userRepository.findById(userId)
                    .ifPresent(userRepository::delete);
        }
    }

    private String payload(
            String targetApplicantId,
            String reviewResult,
            Instant eventTime
    ) {
        return """
                {
                  "type": "applicantReviewed",
                  "applicantType": "individual",
                  "applicantId": "%s",
                  "externalUserId": "%s",
                  "levelName": "id-and-liveness",
                  "sandboxMode": true,
                  "reviewStatus": "completed",
                  "createdAtMs": "%s",
                  "reviewResult": %s
                }
                """.formatted(
                targetApplicantId,
                userId,
                TIMESTAMP_FORMAT.format(eventTime),
                reviewResult
        );
    }

    private String approval(Instant time) {
        return payload(
                applicantId,
                "{\"reviewAnswer\":\"GREEN\"}",
                time
        );
    }

    private String finalRejection(Instant time) {
        return payload(
                applicantId,
                "{\"reviewAnswer\":\"RED\","
                        + "\"reviewRejectType\":\"FINAL\"}",
                time
        );
    }

    private String resubmission(Instant time) {
        return payload(
                applicantId,
                "{\"reviewAnswer\":\"RED\","
                        + "\"reviewRejectType\":\"RETRY\"}",
                time
        );
    }

    private String digest(byte[] rawBody) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                SECRET.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        ));

        return HexFormat.of().formatHex(mac.doFinal(rawBody));
    }

    private ResultActions signedPost(String body)
            throws Exception {

        byte[] rawBody = body.getBytes(StandardCharsets.UTF_8);

        return mockMvc.perform(
                post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody)
                        .header("X-Payload-Digest", digest(rawBody))
                        .header("X-Payload-Digest-Alg", "HMAC_SHA256_HEX")
        );
    }

    private KycStatusEntity persistedKyc() {
        return kycRepository.findByUserId(userId).orElseThrow();
    }

    private AccountStatus persistedAccountStatus() {
        return userRepository.findById(userId)
                .orElseThrow()
                .getAccountStatus();
    }

    @Test
    void signedApprovalActivatesAndProvisionsWallets()
            throws Exception {

        signedPost(approval(BASE_TIME))
                .andExpect(status().isOk());

        assertEquals(KycStatus.APPROVED, persistedKyc().getStatus());
        assertEquals(AccountStatus.ACTIVE, persistedAccountStatus());

        assertEquals(
                BASE_TIME,
                persistedKyc().getLastProviderEventAt()
        );

        verify(walletProvisioningService, times(1))
                .provisionCustomerWallets(userId);
    }

    @Test
    void duplicateApprovalDoesNotProvisionTwice()
            throws Exception {

        String body = approval(BASE_TIME);

        signedPost(body).andExpect(status().isOk());
        signedPost(body).andExpect(status().isOk());

        assertEquals(KycStatus.APPROVED, persistedKyc().getStatus());

        verify(walletProvisioningService, times(1))
                .provisionCustomerWallets(userId);
    }

    @Test
    void resubmissionDoesNotActivateCustomer()
            throws Exception {

        signedPost(resubmission(BASE_TIME))
                .andExpect(status().isOk());

        assertEquals(
                KycStatus.RESUBMISSION_REQUIRED,
                persistedKyc().getStatus()
        );

        assertEquals(
                AccountStatus.PENDING_KYC,
                persistedAccountStatus()
        );

        assertNull(persistedKyc().getDecidedAt());

        verifyNoInteractions(walletProvisioningService);
    }

    @Test
    void finalRejectionDoesNotActivateCustomer()
            throws Exception {

        signedPost(finalRejection(BASE_TIME))
                .andExpect(status().isOk());

        assertEquals(KycStatus.REJECTED, persistedKyc().getStatus());
        assertEquals(
                AccountStatus.PENDING_KYC,
                persistedAccountStatus()
        );

        verifyNoInteractions(walletProvisioningService);
    }

    @Test
    void newerRejectionSuspendsApprovedCustomer()
            throws Exception {

        signedPost(approval(BASE_TIME))
                .andExpect(status().isOk());

        signedPost(finalRejection(BASE_TIME.plusSeconds(60)))
                .andExpect(status().isOk());

        assertEquals(KycStatus.REJECTED, persistedKyc().getStatus());
        assertEquals(AccountStatus.SUSPENDED, persistedAccountStatus());

        verify(walletProvisioningService, times(1))
                .provisionCustomerWallets(userId);
    }

    @Test
    void olderApprovalCannotUndoNewerRejection()
            throws Exception {

        signedPost(finalRejection(BASE_TIME.plusSeconds(60)))
                .andExpect(status().isOk());

        signedPost(approval(BASE_TIME))
                .andExpect(status().isOk());

        assertEquals(KycStatus.REJECTED, persistedKyc().getStatus());
        assertEquals(
                AccountStatus.PENDING_KYC,
                persistedAccountStatus()
        );

        verifyNoInteractions(walletProvisioningService);
    }

    @Test
    void invalidSignatureCannotChangeDatabase()
            throws Exception {

        mockMvc.perform(
                post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approval(BASE_TIME))
                        .header("X-Payload-Digest", "00".repeat(32))
                        .header("X-Payload-Digest-Alg", "HMAC_SHA256_HEX")
        ).andExpect(status().isUnauthorized());

        assertEquals(KycStatus.PENDING, persistedKyc().getStatus());
        assertEquals(
                AccountStatus.PENDING_KYC,
                persistedAccountStatus()
        );

        verifyNoInteractions(walletProvisioningService);
    }

    @Test
    void signedWebhookForWrongApplicantCannotApprove()
            throws Exception {

        String wrongApplicant = payload(
                "wrong-applicant-id",
                "{\"reviewAnswer\":\"GREEN\"}",
                BASE_TIME
        );

        // Current controller returns 503 on a decision-service
        // ownership error. We'll refine permanent-error
        // classification before enabling live webhook delivery.
        signedPost(wrongApplicant)
                .andExpect(status().isServiceUnavailable());

        assertEquals(KycStatus.PENDING, persistedKyc().getStatus());
        assertEquals(
                AccountStatus.PENDING_KYC,
                persistedAccountStatus()
        );

        verifyNoInteractions(walletProvisioningService);
    }

    @Test
    void resubmissionThenApprovalActivatesCustomer()
            throws Exception {

        // First Sumsub review requests better documents.
        signedPost(resubmission(BASE_TIME))
                .andExpect(status().isOk());

        assertEquals(
                KycStatus.RESUBMISSION_REQUIRED,
                persistedKyc().getStatus()
        );

        assertEquals(
                AccountStatus.PENDING_KYC,
                persistedAccountStatus()
        );

        verifyNoInteractions(walletProvisioningService);

        // Customer resubmits, and Sumsub approves.
        signedPost(approval(BASE_TIME.plusSeconds(60)))
                .andExpect(status().isOk());

        assertEquals(
                KycStatus.APPROVED,
                persistedKyc().getStatus()
        );

        assertEquals(
                AccountStatus.ACTIVE,
                persistedAccountStatus()
        );

        assertEquals(
                BASE_TIME.plusSeconds(60),
                persistedKyc().getLastProviderEventAt()
        );

        // The first rejection never provisioned wallets.
        // Only the later approval does.
        verify(walletProvisioningService, times(1))
                .provisionCustomerWallets(userId);
    }

    @Test
    void newerApprovalDoesNotReactivateSuspendedAccount()
            throws Exception {

        // Initial approval.
        signedPost(approval(BASE_TIME))
                .andExpect(status().isOk());

        assertEquals(
                AccountStatus.ACTIVE,
                persistedAccountStatus()
        );

        // A newer final rejection revokes access.
        signedPost(finalRejection(BASE_TIME.plusSeconds(60)))
                .andExpect(status().isOk());

        assertEquals(
                KycStatus.REJECTED,
                persistedKyc().getStatus()
        );

        assertEquals(
                AccountStatus.SUSPENDED,
                persistedAccountStatus()
        );

        // Another, newer GREEN decision arrives.
        signedPost(approval(BASE_TIME.plusSeconds(120)))
                .andExpect(status().isOk());

        // Current service records the latest
        // identity-verification decision.
        assertEquals(
                KycStatus.APPROVED,
                persistedKyc().getStatus()
        );

        // But the suspended account must NOT
        // automatically become active again.
        assertEquals(
                AccountStatus.SUSPENDED,
                persistedAccountStatus()
        );

        // The first approval was the only time
        // wallet provisioning was attempted.
        verify(walletProvisioningService, times(1))
                .provisionCustomerWallets(userId);
    }

    @Test
    void initialFinalRejectionCannotBeAutomaticallyOverturned()
            throws Exception {

        // STEP 1: The customer's first review is RED/FINAL.
        signedPost(finalRejection(BASE_TIME))
                .andExpect(status().isOk());

        // The initial final rejection must create a persistent lock.
        assertTrue(
                persistedKyc().isInitialFinalRejectionLocked()
        );

        assertFalse(
                persistedKyc().hasEverBeenApproved()
        );

        assertEquals(
                KycStatus.REJECTED,
                persistedKyc().getStatus()
        );

        assertEquals(
                AccountStatus.PENDING_KYC,
                persistedAccountStatus()
        );

        verifyNoInteractions(walletProvisioningService);

        // STEP 2: A newer GREEN decision arrives for the same applicant.
        // It must not automatically overturn the initial FINAL rejection.
        signedPost(approval(BASE_TIME.plusSeconds(60)))
                .andExpect(status().isOk());

        // STEP 3: The KYC status must remain REJECTED.
        assertEquals(
                KycStatus.REJECTED,
                persistedKyc().getStatus(),
                "Initial FINAL rejection must not be auto-overturned"
        );

        // The customer must remain inactive.
        assertEquals(
                AccountStatus.PENDING_KYC,
                persistedAccountStatus(),
                "Customer must not be activated automatically"
        );

        // STEP 4: The newer GREEN decision must trigger manual review.
        assertTrue(
                persistedKyc().isManualReviewRequired(),
                "Unexpected approval must be flagged for manual review"
        );

        assertEquals(
                "APPROVED",
                persistedKyc().getManualReviewTrigger()
        );

        assertNotNull(
                persistedKyc().getManualReviewTriggerAt()
        );

        // STEP 5: The newer webhook timestamp must be persisted
        // to prevent older events from being processed later.
        assertEquals(
                BASE_TIME.plusSeconds(60),
                persistedKyc().getLastProviderEventAt()
        );

        // STEP 6: The initial final-rejection lock must remain intact.
        assertTrue(
                persistedKyc().isInitialFinalRejectionLocked()
        );

        assertFalse(
                persistedKyc().hasEverBeenApproved()
        );

        // CRITICAL: No Formance wallet provisioning must occur,
        // even though the newer webhook was correctly signed.
        verifyNoInteractions(walletProvisioningService);
    }
    @Test
    void initialFinalRejectionCannotBecomeResubmission()
            throws Exception {

        // First review: permanent rejection.
        signedPost(finalRejection(BASE_TIME))
                .andExpect(status().isOk());

        assertEquals(
                KycStatus.REJECTED,
                persistedKyc().getStatus()
        );

        assertTrue(
                persistedKyc().isInitialFinalRejectionLocked()
        );

        // A newer RETRY decision must not reopen
        // an initially final-rejected applicant.
        signedPost(
                resubmission(BASE_TIME.plusSeconds(60))
        ).andExpect(status().isOk());

        assertEquals(
                KycStatus.REJECTED,
                persistedKyc().getStatus()
        );

        assertTrue(
                persistedKyc().isManualReviewRequired()
        );

        assertEquals(
                "RESUBMISSION_REQUIRED",
                persistedKyc().getManualReviewTrigger()
        );

        assertEquals(
                AccountStatus.PENDING_KYC,
                persistedAccountStatus()
        );

        verifyNoInteractions(walletProvisioningService);
    }
}
