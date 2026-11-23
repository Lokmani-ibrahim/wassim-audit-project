
package com.fintech.kyc;

import com.fintech.identity.entity.AccountStatus;
import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.port.KycProvider;
import com.fintech.kyc.repository.KycStatusRepository;
import com.fintech.ledger.adapter.formance.FormanceAccountData;
import com.fintech.ledger.adapter.formance.FormanceClient;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddress;
import com.fintech.ledger.internal.taxonomy.LedgerAccountAddressFactory;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "neos.kyc.sumsub.webhook-enabled=true",
        "neos.kyc.sumsub.webhook-secret=test-webhook-secret",
        "neos.kyc.sumsub.sandbox-mode=true",
        "neos.kyc.sumsub.level-name=id-and-liveness",
        "neos.kyc.sumsub.app-token=test-app-token",
        "neos.kyc.sumsub.secret-key=test-secret",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(
        named = "RUN_FORMANCE_IT",
        matches = "true"
)
class KycApprovalWalletProvisioningIntegrationTest {

    private static final String WEBHOOK_SECRET =
            "test-webhook-secret";

    private static final Instant APPROVAL_TIME =
            Instant.parse("2026-11-03T12:00:00Z");

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern(
                    "yyyy-MM-dd HH:mm:ss.SSS"
            ).withZone(ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KycStatusRepository kycStatusRepository;

    @Autowired
    private FormanceClient formanceClient;

    /*
     * Simulate only outbound Sumsub applicant creation.
     *
     * KYC persistence, signed webhook processing,
     * account activation, event publication and Formance
     * provisioning remain real.
     */
    @MockitoBean(enforceOverride = true)
    private KycProvider kycProvider;

    @DynamicPropertySource
    static void formanceProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "neos.ledger.formance.base-url",
                () -> System.getenv().getOrDefault(
                        "FORMANCE_BASE_URL",
                        "http://localhost:8080"
                )
        );

        registry.add(
                "neos.ledger.formance.ledger-name",
                () -> System.getenv().getOrDefault(
                        "FORMANCE_LEDGER_NAME",
                        "neos"
                )
        );

        registry.add(
                "neos.ledger.formance.schema-version",
                () -> System.getenv().getOrDefault(
                        "FORMANCE_SCHEMA_VERSION",
                        "v1.1.0"
                )
        );

        registry.add(
                "neos.ledger.formance.connect-timeout",
                () -> "2s"
        );

        registry.add(
                "neos.ledger.formance.read-timeout",
                () -> "5s"
        );
    }

    @Test
    void signedSumsubApprovalProvisionsSixWallets()
            throws Exception {

        String suffix = UUID.randomUUID()
                .toString()
                .replace("-", "");

        String subject = "c2-sumsub-" + suffix;
        String email = "c2." + suffix + "@neos.local";
        String installationId = "android-c2-" + suffix;
        String applicantId = "sandbox-applicant-" + suffix;

        when(kycProvider.createApplicant(any()))
                .thenReturn(
                        new KycProvider.Applicant(applicantId)
                );

        /*
         * STEP 1: Real customer onboarding.
         */
        mockMvc.perform(
                        post("/api/onboarding/personal")
                                .with(customerJwt(subject, email))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {
                                  "phoneNumber": "+21620123456",
                                  "installationId": "%s",
                                  "platform": "ANDROID",
                                  "termsVersion": "1.0"
                                }
                                """.formatted(installationId))
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.kycStatus")
                                .value("NOT_STARTED")
                );

        UserEntity user = userRepository
                .findByKeycloakSubject(subject)
                .orElseThrow();

        UUID userId = user.getId();

        assertThat(
                kycStatusRepository.findByUserId(userId)
                        .orElseThrow()
                        .getStatus()
        ).isEqualTo(KycStatus.NOT_STARTED);

        /*
         * STEP 2: Start Sumsub verification.
         *
         * Starting verification must not approve
         * or activate the customer.
         */
        mockMvc.perform(
                        post("/api/kyc/start")
                                .with(customerJwt(subject, email))
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.status")
                                .value("PENDING")
                )
                .andExpect(
                        jsonPath("$.provider")
                                .value("SUMSUB")
                );

        assertThat(
                kycStatusRepository.findByUserId(userId)
                        .orElseThrow()
                        .getStatus()
        ).isEqualTo(KycStatus.PENDING);

        assertThat(
                userRepository.findById(userId)
                        .orElseThrow()
                        .getAccountStatus()
        ).isEqualTo(AccountStatus.PENDING_KYC);

        /*
         * STEP 3: Send a signed simulated Sumsub
         * approval webhook.
         *
         * The real signature verifier, controller,
         * decision service and database are used.
         */
        String approvalPayload = """
                {
                  "type": "applicantReviewed",
                  "applicantType": "individual",
                  "applicantId": "%s",
                  "externalUserId": "%s",
                  "levelName": "id-and-liveness",
                  "sandboxMode": true,
                  "reviewStatus": "completed",
                  "createdAtMs": "%s",
                  "reviewResult": {
                    "reviewAnswer": "GREEN"
                  }
                }
                """.formatted(
                applicantId,
                userId,
                TIMESTAMP_FORMAT.format(APPROVAL_TIME)
        );

        sendSignedApproval(approvalPayload);

        /*
         * STEP 4: Verify actual KYC approval
         * and customer activation.
         */
        assertThat(
                kycStatusRepository.findByUserId(userId)
                        .orElseThrow()
                        .getStatus()
        ).isEqualTo(KycStatus.APPROVED);

        assertThat(
                userRepository.findById(userId)
                        .orElseThrow()
                        .getAccountStatus()
        ).isEqualTo(AccountStatus.ACTIVE);

        /*
         * STEP 5: Verify real Formance wallets.
         */
        assertThat(LedgerCurrency.values())
                .hasSize(6);

        verifySixZeroBalanceWallets(userId);

        /*
         * STEP 6: Retry KYC start.
         *
         * Must not create another applicant,
         * publish another approval or create money.
         */
        mockMvc.perform(
                        post("/api/kyc/start")
                                .with(customerJwt(subject, email))
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.status")
                                .value("APPROVED")
                );

        /*
         * STEP 7: Repeat the same signed webhook.
         *
         * Duplicate approval must remain safe.
         */
        sendSignedApproval(approvalPayload);

        assertThat(
                userRepository.findById(userId)
                        .orElseThrow()
                        .getAccountStatus()
        ).isEqualTo(AccountStatus.ACTIVE);

        verifySixZeroBalanceWallets(userId);

        verify(kycProvider, times(1))
                .createApplicant(any());
    }

    private void sendSignedApproval(String payload)
            throws Exception {

        byte[] rawBody =
                payload.getBytes(StandardCharsets.UTF_8);

        Mac mac = Mac.getInstance("HmacSHA256");

        mac.init(
                new SecretKeySpec(
                        WEBHOOK_SECRET.getBytes(
                                StandardCharsets.UTF_8
                        ),
                        "HmacSHA256"
                )
        );

        String digest = HexFormat.of()
                .formatHex(mac.doFinal(rawBody));

        mockMvc.perform(
                post("/api/webhooks/sumsub")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawBody)
                        .header("X-Payload-Digest", digest)
                        .header(
                                "X-Payload-Digest-Alg",
                                "HMAC_SHA256_HEX"
                        )
        ).andExpect(status().isOk());
    }

    private void verifySixZeroBalanceWallets(
            UUID userId
    ) {

        for (LedgerCurrency currency
                : LedgerCurrency.values()) {

            LedgerAccountAddress account =
                    LedgerAccountAddressFactory
                            .customerAvailable(
                                    userId,
                                    currency
                            );

            FormanceAccountData actual =
                    formanceClient.getAccount(
                            account.value()
                    );

            assertThat(actual.address())
                    .isEqualTo(account.value());

            assertThat(actual.metadata())
                    .containsEntry(
                            "neos.account_kind",
                            "customer_available"
                    )
                    .containsEntry(
                            "neos.user_id",
                            userId.toString()
                    )
                    .containsEntry(
                            "neos.currency",
                            currency.currencyCode()
                    )
                    .containsEntry(
                            "neos.wallet_status",
                            "ACTIVE"
                    );

            /*
             * Wallet provisioning must not
             * create financial value.
             */
            assertThat(
                    formanceClient.getAccountBalance(
                            account.value(),
                            currency.formanceAsset()
                    )
            ).isEqualTo(BigInteger.ZERO);
        }
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor
    customerJwt(String subject, String email) {

        return jwt()
                .jwt(token -> token
                        .subject(subject)
                        .claim("email", email)
                )
                .authorities(
                        new SimpleGrantedAuthority(
                                "ROLE_CUSTOMER"
                        )
                );
    }
}
