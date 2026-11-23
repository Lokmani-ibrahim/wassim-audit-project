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
import static org.hamcrest.Matchers.hasItem;

import java.util.UUID;

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
class TransactionHistoryControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

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
    void customerSeesOwnIncomingPaymentAsFriendlyCredit()
            throws Exception {

        String suffix =
                UUID.randomUUID()
                        .toString()
                        .replace("-", "");

        String subject =
                "c4-history-" + suffix;

        String email =
                "c4." + suffix + "@neos.local";

        String installationId =
                "android-c4-" + suffix;

        /*
         * Create a real onboarded customer.
         */
        mockMvc.perform(
                        post(
                                "/api/onboarding/personal"
                        )
                                .with(
                                        jwt()
                                                .jwt(
                                                        token ->
                                                                token
                                                                        .subject(
                                                                                subject
                                                                        )
                                                                        .claim(
                                                                                "email",
                                                                                email
                                                                        )
                                                )
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "ROLE_CUSTOMER"
                                                        )
                                                )
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "phoneNumber": "+21620123456",
                                          "installationId": "%s",
                                          "platform": "ANDROID",
                                          "termsVersion": "1.0"
                                        }
                                        """
                                                .formatted(
                                                        installationId
                                                )
                                )
                )
                .andExpect(
                        status().isOk()
                );

        UserEntity user =
                userRepository
                        .findByKeycloakSubject(
                                subject
                        )
                        .orElseThrow();

        String operationId =
                "c4_history_api_"
                        + suffix;

        /*
         * Real Formance incoming payment.
         */
        ledgerProvider.recordIncomingPayment(
                user.getId(),
                "c4_history_bank",
                LedgerCurrency.EUR,
                10_000,
                operationId
        );

        /*
         * Customer reads their EUR history.
         */
        mockMvc.perform(
                        get(
                                "/api/transactions"
                        )
                                .param(
                                        "currency",
                                        "EUR"
                                )
                                .param(
                                        "pageSize",
                                        "20"
                                )
                                .with(
                                        jwt()
                                                .jwt(
                                                        token ->
                                                                token.subject(
                                                                        subject
                                                                )
                                                )
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "ROLE_CUSTOMER"
                                                        )
                                                )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath(
                                "$.items.length()"
                        ).value(
                                1
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.items[0].type"
                        ).value(
                                "INCOMING_PAYMENT"
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.items[0].direction"
                        ).value(
                                "CREDIT"
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.items[0].currency"
                        ).value(
                                "EUR"
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.items[0].amountMinor"
                        ).value(
                                10_000
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.items[0].status"
                        ).value(
                                "COMPLETED"
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.items[0].reference"
                        ).value(
                                "incoming_payment:"
                                        + operationId
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.items[0].occurredAt"
                        ).exists()
                );
    }

    @Test
    void complianceOfficerCannotReadCustomerHistory()
            throws Exception {

        mockMvc.perform(
                        get(
                                "/api/transactions"
                        )
                                .param(
                                        "currency",
                                        "EUR"
                                )
                                .with(
                                        jwt()
                                                .jwt(
                                                        token ->
                                                                token.subject(
                                                                        "c4-compliance"
                                                                )
                                                )
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "ROLE_COMPLIANCE_OFFICER"
                                                        )
                                                )
                                )
                )
                .andExpect(
                        status().isForbidden()
                );
    }

    @Test
    void unauthenticatedCallerCannotReadCustomerHistory()
            throws Exception {

        mockMvc.perform(
                        get(
                                "/api/transactions"
                        )
                                .param(
                                        "currency",
                                        "EUR"
                                )
                )
                .andExpect(
                        status().isUnauthorized()
                );
    }
    @Test
    void internalTransferAppearsAsDebitForSenderAndCreditForRecipient()
            throws Exception {

        String suffix =
                UUID.randomUUID()
                        .toString()
                        .replace("-", "");

        String senderSubject =
                "c4-sender-" + suffix;

        String recipientSubject =
                "c4-recipient-" + suffix;

        /*
         * Create sender.
         */
        mockMvc.perform(
                        post("/api/onboarding/personal")
                                .with(
                                        jwt()
                                                .jwt(token -> token
                                                        .subject(senderSubject)
                                                        .claim(
                                                                "email",
                                                                "sender."
                                                                        + suffix
                                                                        + "@neos.local"
                                                        ))
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
                                          "installationId": "sender-%s",
                                          "platform": "ANDROID",
                                          "termsVersion": "1.0"
                                        }
                                        """.formatted(suffix)
                                )
                )
                .andExpect(status().isOk());

        /*
         * Create recipient.
         */
        mockMvc.perform(
                        post("/api/onboarding/personal")
                                .with(
                                        jwt()
                                                .jwt(token -> token
                                                        .subject(recipientSubject)
                                                        .claim(
                                                                "email",
                                                                "recipient."
                                                                        + suffix
                                                                        + "@neos.local"
                                                        ))
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
                                          "phoneNumber": "+21620123457",
                                          "installationId": "recipient-%s",
                                          "platform": "ANDROID",
                                          "termsVersion": "1.0"
                                        }
                                        """.formatted(suffix)
                                )
                )
                .andExpect(status().isOk());

        UserEntity sender =
                userRepository
                        .findByKeycloakSubject(senderSubject)
                        .orElseThrow();

        UserEntity recipient =
                userRepository
                        .findByKeycloakSubject(recipientSubject)
                        .orElseThrow();

        /*
         * Give sender €100.
         */
        ledgerProvider.recordIncomingPayment(
                sender.getId(),
                "c4_transfer_bank",
                LedgerCurrency.EUR,
                10_000,
                "c4_transfer_funding_" + suffix
        );

        /*
         * Sender transfers €25 to recipient.
         */
        String operationId =
                "c4_transfer_" + suffix;

        ledgerProvider.transferBetweenUsers(
                sender.getId(),
                recipient.getId(),
                LedgerCurrency.EUR,
                2_500,
                operationId
        );

        String reference =
                "internal_transfer:" + operationId;

        /*
         * Sender sees DEBIT.
         */
        mockMvc.perform(
                        get("/api/transactions")
                                .param("currency", "EUR")
                                .param("pageSize", "20")
                                .with(
                                        jwt()
                                                .jwt(token ->
                                                        token.subject(
                                                                senderSubject
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
                        jsonPath(
                                "$.items[?(@.reference == '%s')].type"
                                        .formatted(reference)
                        ).value(
                                hasItem("TRANSFER")
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.items[?(@.reference == '%s')].direction"
                                        .formatted(reference)
                        ).value(
                                hasItem("DEBIT")
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.items[?(@.reference == '%s')].amountMinor"
                                        .formatted(reference)
                        ).value(
                                hasItem(2500)
                        )
                );

        /*
         * Recipient sees the SAME transaction as CREDIT.
         */
        mockMvc.perform(
                        get("/api/transactions")
                                .param("currency", "EUR")
                                .param("pageSize", "20")
                                .with(
                                        jwt()
                                                .jwt(token ->
                                                        token.subject(
                                                                recipientSubject
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
                        jsonPath(
                                "$.items[?(@.reference == '%s')].type"
                                        .formatted(reference)
                        ).value(
                                hasItem("TRANSFER")
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.items[?(@.reference == '%s')].direction"
                                        .formatted(reference)
                        ).value(
                                hasItem("CREDIT")
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.items[?(@.reference == '%s')].amountMinor"
                                        .formatted(reference)
                        ).value(
                                hasItem(2500)
                        )
                );
    }
    @Test
    void customerSeesFeeAsDebit()
            throws Exception {

        String suffix =
                UUID.randomUUID()
                        .toString()
                        .replace("-", "");

        String subject =
                "c4-fee-" + suffix;

        mockMvc.perform(
                        post("/api/onboarding/personal")
                                .with(
                                        jwt()
                                                .jwt(token -> token
                                                        .subject(subject)
                                                        .claim(
                                                                "email",
                                                                "fee."
                                                                        + suffix
                                                                        + "@neos.local"
                                                        ))
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
                                          "phoneNumber": "+21620123458",
                                          "installationId": "fee-%s",
                                          "platform": "ANDROID",
                                          "termsVersion": "1.0"
                                        }
                                        """.formatted(suffix)
                                )
                )
                .andExpect(status().isOk());

        UserEntity user =
                userRepository
                        .findByKeycloakSubject(subject)
                        .orElseThrow();

        /*
         * Fund €100 first.
         */
        ledgerProvider.recordIncomingPayment(
                user.getId(),
                "c4_fee_bank",
                LedgerCurrency.EUR,
                10_000,
                "c4_fee_funding_" + suffix
        );

        /*
         * Collect €2.50 fee.
         */
        String operationId =
                "c4_fee_" + suffix;

        ledgerProvider.collectFee(
                user.getId(),
                LedgerCurrency.EUR,
                250,
                operationId
        );

        String reference =
                "fee_collection:" + operationId;

        mockMvc.perform(
                        get("/api/transactions")
                                .param("currency", "EUR")
                                .param("pageSize", "20")
                                .with(
                                        jwt()
                                                .jwt(token ->
                                                        token.subject(subject)
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
                        jsonPath(
                                "$.items[?(@.reference == '%s')].type"
                                        .formatted(reference)
                        ).value(
                                hasItem("FEE")
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.items[?(@.reference == '%s')].direction"
                                        .formatted(reference)
                        ).value(
                                hasItem("DEBIT")
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.items[?(@.reference == '%s')].amountMinor"
                                        .formatted(reference)
                        ).value(
                                hasItem(250)
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.items[?(@.reference == '%s')].currency"
                                        .formatted(reference)
                        ).value(
                                hasItem("EUR")
                        )
                );
    }
    @Test
    void settledPayoutAppearsAsCompletedDebit()
            throws Exception {

        String suffix =
                UUID.randomUUID()
                        .toString()
                        .replace("-", "");

        String subject =
                "c4-payout-" + suffix;

        String email =
                "payout."
                        + suffix
                        + "@neos.local";

        mockMvc.perform(
                        post("/api/onboarding/personal")
                                .with(
                                        jwt()
                                                .jwt(token -> token
                                                        .subject(subject)
                                                        .claim(
                                                                "email",
                                                                email
                                                        ))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "ROLE_CUSTOMER"
                                                        )
                                                )
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "phoneNumber": "+21620123459",
                                          "installationId": "payout-%s",
                                          "platform": "ANDROID",
                                          "termsVersion": "1.0"
                                        }
                                        """.formatted(suffix)
                                )
                )
                .andExpect(
                        status().isOk()
                );

        UserEntity user =
                userRepository
                        .findByKeycloakSubject(subject)
                        .orElseThrow();

        /*
         * Fund both:
         *
         * customer available +€100
         * bank master        +€100
         *
         * so payout settlement has enough bank liquidity.
         */
        ledgerProvider.recordIncomingPayment(
                user.getId(),
                "c4_payout_bank",
                LedgerCurrency.EUR,
                10_000,
                "c4_payout_funding_" + suffix
        );

        String operationId =
                "c4_payout_" + suffix;

        /*
         * Stage €30.
         */
        ledgerProvider.reservePayout(
                user.getId(),
                LedgerCurrency.EUR,
                3_000,
                operationId
        );

        /*
         * Actually complete the payout.
         */
        ledgerProvider.settlePayout(
                user.getId(),
                "c4_payout_bank",
                LedgerCurrency.EUR,
                3_000,
                operationId
        );

        String reference =
                "payout_settle:"
                        + operationId;

        mockMvc.perform(
                        get("/api/transactions")
                                .param(
                                        "currency",
                                        "EUR"
                                )
                                .param(
                                        "pageSize",
                                        "20"
                                )
                                .with(
                                        jwt()
                                                .jwt(token ->
                                                        token.subject(
                                                                subject
                                                        )
                                                )
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "ROLE_CUSTOMER"
                                                        )
                                                )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath(
                                "$.items[?(@.reference == '%s')].type"
                                        .formatted(reference)
                        ).value(
                                hasItem("PAYOUT")
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.items[?(@.reference == '%s')].direction"
                                        .formatted(reference)
                        ).value(
                                hasItem("DEBIT")
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.items[?(@.reference == '%s')].amountMinor"
                                        .formatted(reference)
                        ).value(
                                hasItem(3000)
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.items[?(@.reference == '%s')].status"
                                        .formatted(reference)
                        ).value(
                                hasItem("COMPLETED")
                        )
                );
    }
    @Test
    void releasedPayoutDoesNotAppearAsCompletedPayout()
            throws Exception {

        String suffix =
                UUID.randomUUID()
                        .toString()
                        .replace("-", "");

        String subject =
                "c4-release-" + suffix;

        mockMvc.perform(
                        post("/api/onboarding/personal")
                                .with(
                                        jwt()
                                                .jwt(token -> token
                                                        .subject(subject)
                                                        .claim(
                                                                "email",
                                                                "release."
                                                                        + suffix
                                                                        + "@neos.local"
                                                        ))
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
                                          "phoneNumber": "+21620123460",
                                          "installationId": "release-%s",
                                          "platform": "ANDROID",
                                          "termsVersion": "1.0"
                                        }
                                        """.formatted(suffix)
                                )
                )
                .andExpect(status().isOk());

        UserEntity user =
                userRepository
                        .findByKeycloakSubject(subject)
                        .orElseThrow();

        ledgerProvider.recordIncomingPayment(
                user.getId(),
                "c4_release_bank",
                LedgerCurrency.EUR,
                10_000,
                "c4_release_funding_" + suffix
        );

        String operationId =
                "c4_release_" + suffix;

        ledgerProvider.reservePayout(
                user.getId(),
                LedgerCurrency.EUR,
                3_000,
                operationId
        );

        ledgerProvider.releasePayout(
                user.getId(),
                LedgerCurrency.EUR,
                3_000,
                operationId
        );

        mockMvc.perform(
                        get("/api/transactions")
                                .param("currency", "EUR")
                                .param("pageSize", "20")
                                .with(
                                        jwt()
                                                .jwt(token ->
                                                        token.subject(subject)
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
                        jsonPath(
                                "$.items[?(@.type == 'PAYOUT')]"
                        ).isEmpty()
                );
    }
}