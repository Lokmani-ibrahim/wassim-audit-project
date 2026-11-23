package com.fintech.ledger;

import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(
        named = "RUN_FORMANCE_IT",
        matches = "true"
)
class CustomerWalletFormanceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletProvisioningService walletProvisioningService;

    @Autowired
    private LedgerProvider ledgerProvider;

    @DynamicPropertySource
    static void formanceProperties(
            DynamicPropertyRegistry registry
    ) {

        registry.add(
                "neos.ledger.formance.base-url",
                () -> System.getenv()
                        .getOrDefault(
                                "FORMANCE_BASE_URL",
                                "http://localhost:8080"
                        )
        );

        registry.add(
                "neos.ledger.formance.ledger-name",
                () -> System.getenv()
                        .getOrDefault(
                                "FORMANCE_LEDGER_NAME",
                                "neos"
                        )
        );

        registry.add(
                "neos.ledger.formance.schema-version",
                () -> System.getenv()
                        .getOrDefault(
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
    void provisionedAndCreditedWalletShowsRealFormanceBalance()
            throws Exception {

        String suffix = suffix();
        String subject = onboard(suffix);

        UUID userId = userRepository
                .findByKeycloakSubject(subject)
                .map(UserEntity::getId)
                .orElseThrow();

        /*
         * Same state as after KYC approval (C2),
         * then a real incoming payment (synthetic credit path).
         */
        walletProvisioningService
                .provisionCustomerWallets(userId);

        ledgerProvider.recordIncomingPayment(
                userId,
                "wallets_it_bank",
                LedgerCurrency.EUR,
                10_000,
                "wallets_it_" + suffix
        );

        mockMvc.perform(
                        get("/api/wallets")
                                .with(customer(subject))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletsStatus").value("READY"))
                .andExpect(jsonPath("$.wallets.length()").value(6))
                .andExpect(jsonPath("$.wallets[0].currency").value("EUR"))
                .andExpect(jsonPath("$.wallets[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.wallets[0].availableMinor").value("10000"))
                .andExpect(jsonPath("$.wallets[0].pendingMinor").value("0"))
                .andExpect(jsonPath("$.wallets[5].currency").value("TND"))
                .andExpect(jsonPath("$.wallets[5].availableMinor").value("0"));
    }

    @Test
    void walletsWithoutProvisioningAreNotProvisionedNotZero()
            throws Exception {

        String subject = onboard(suffix());

        // Onboarded, KYC NOT_STARTED, nothing in Formance.
        mockMvc.perform(
                        get("/api/wallets")
                                .with(customer(subject))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletsStatus").value("NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.wallets[0].status").value("NOT_PROVISIONED"))
                .andExpect(jsonPath("$.wallets[0].availableMinor").value(nullValue()))
                .andExpect(jsonPath("$.wallets[0].pendingMinor").value(nullValue()));
    }

    private String onboard(String suffix) throws Exception {

        String subject = "wallets-it-" + suffix;

        mockMvc.perform(
                        post("/api/onboarding/personal")
                                .with(
                                        jwt()
                                                .jwt(token -> token
                                                        .subject(subject)
                                                        .claim(
                                                                "email",
                                                                "wallets.it." + suffix + "@neos.local"
                                                        )
                                                )
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "ROLE_CUSTOMER"
                                                        )
                                                )
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "phoneNumber": "+21620123456",
                                          "installationId": "android-wallets-it-%s",
                                          "platform": "ANDROID",
                                          "termsVersion": "1.0"
                                        }
                                        """.formatted(suffix)
                                )
                )
                .andExpect(status().isOk());

        return subject;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor customer(
            String subject
    ) {
        return jwt()
                .jwt(token -> token.subject(subject))
                .authorities(
                        new SimpleGrantedAuthority("ROLE_CUSTOMER")
                );
    }
}
