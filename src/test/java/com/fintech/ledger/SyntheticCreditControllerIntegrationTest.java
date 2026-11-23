package com.fintech.ledger;

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
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigInteger;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        properties = {
                "neos.backoffice.synthetic-credit.enabled=true"
        }
)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(
        named = "RUN_FORMANCE_IT",
        matches = "true"
)
class SyntheticCreditControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WalletProvisioningService walletProvisioningService;

    @Autowired
    private FormanceClient formanceClient;

    @DynamicPropertySource
    static void properties(
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
    void complianceOfficerCanCreateSyntheticCreditAndRetryIsIdempotent()
            throws Exception {

        UUID userId =
                UUID.randomUUID();

        String operationId =
                "c3_http_"
                        + UUID.randomUUID()
                        .toString()
                        .replace(
                                "-",
                                ""
                        );

        /*
         * Simulate C2:
         *
         * user has already passed KYC and therefore
         * owns provisioned wallets.
         */
        walletProvisioningService
                .provisionCustomerWallets(
                        userId
                );

        LedgerAccountAddress available =
                LedgerAccountAddressFactory
                        .customerAvailable(
                                userId,
                                LedgerCurrency.EUR
                        );

        LedgerAccountAddress bank =
                LedgerAccountAddressFactory
                        .bankMaster(
                                "sponsor",
                                LedgerCurrency.EUR
                        );

        LedgerAccountAddress control =
                LedgerAccountAddressFactory
                        .safeguardedControl(
                                LedgerCurrency.EUR
                        );

        BigInteger availableBefore =
                balance(
                        available
                );

        BigInteger bankBefore =
                balance(
                        bank
                );

        BigInteger controlBefore =
                balance(
                        control
                );

        String requestBody =
                """
                {
                  "userId": "%s",
                  "currency": "EUR",
                  "amountMinor": 10000,
                  "operationId": "%s"
                }
                """
                        .formatted(
                                userId,
                                operationId
                        );

        /*
         * First request.
         */
        mockMvc.perform(
                        post(
                                "/api/backoffice/synthetic-credits"
                        )
                                .with(
                                        jwt()
                                                .jwt(
                                                        token ->
                                                                token.subject(
                                                                        "backoffice-c3"
                                                                )
                                                )
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "ROLE_COMPLIANCE_OFFICER"
                                                        )
                                                )
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        requestBody
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath(
                                "$.userId"
                        ).value(
                                userId.toString()
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.currency"
                        ).value(
                                "EUR"
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.amountMinor"
                        ).value(
                                10_000
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.transactionId"
                        ).isNumber()
                )
                .andExpect(
                        jsonPath(
                                "$.reference"
                        ).value(
                                "incoming_payment:"
                                        + operationId
                        )
                );

        BigInteger availableAfter =
                balance(
                        available
                );

        BigInteger bankAfter =
                balance(
                        bank
                );

        BigInteger controlAfter =
                balance(
                        control
                );

        /*
         * Customer +100 EUR.
         */
        assertThat(
                availableAfter.subtract(
                        availableBefore
                )
        ).isEqualTo(
                BigInteger.valueOf(
                        10_000
                )
        );

        /*
         * Bank +100 EUR.
         */
        assertThat(
                bankAfter.subtract(
                        bankBefore
                )
        ).isEqualTo(
                BigInteger.valueOf(
                        10_000
                )
        );

        /*
         * Control -100 EUR.
         */
        assertThat(
                controlAfter.subtract(
                        controlBefore
                )
        ).isEqualTo(
                BigInteger.valueOf(
                        -10_000
                )
        );

        /*
         * Retry the exact same HTTP operation.
         */
        mockMvc.perform(
                        post(
                                "/api/backoffice/synthetic-credits"
                        )
                                .with(
                                        jwt()
                                                .jwt(
                                                        token ->
                                                                token.subject(
                                                                        "backoffice-c3"
                                                                )
                                                )
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "ROLE_COMPLIANCE_OFFICER"
                                                        )
                                                )
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        requestBody
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath(
                                "$.reference"
                        ).value(
                                "incoming_payment:"
                                        + operationId
                        )
                );

        /*
         * Idempotent retry:
         *
         * no balance changes.
         */
        assertThat(
                balance(
                        available
                )
        ).isEqualTo(
                availableAfter
        );

        assertThat(
                balance(
                        bank
                )
        ).isEqualTo(
                bankAfter
        );

        assertThat(
                balance(
                        control
                )
        ).isEqualTo(
                controlAfter
        );
    }

    @Test
    void customerCannotCreateSyntheticCredit()
            throws Exception {

        UUID userId =
                UUID.randomUUID();

        mockMvc.perform(
                        post(
                                "/api/backoffice/synthetic-credits"
                        )
                                .with(
                                        jwt()
                                                .jwt(
                                                        token ->
                                                                token.subject(
                                                                        "customer-c3"
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
                                          "userId": "%s",
                                          "currency": "EUR",
                                          "amountMinor": 10000,
                                          "operationId": "c3_customer_forbidden"
                                        }
                                        """
                                                .formatted(
                                                        userId
                                                )
                                )
                )
                .andExpect(
                        status().isForbidden()
                );
    }

    @Test
    void unauthenticatedCallerCannotCreateSyntheticCredit()
            throws Exception {

        mockMvc.perform(
                        post(
                                "/api/backoffice/synthetic-credits"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "userId": "%s",
                                          "currency": "EUR",
                                          "amountMinor": 10000,
                                          "operationId": "c3_unauthenticated"
                                        }
                                        """
                                                .formatted(
                                                        UUID.randomUUID()
                                                )
                                )
                )
                .andExpect(
                        status().isUnauthorized()
                );
    }

    @Test
    void rejectsInvalidAmount()
            throws Exception {

        mockMvc.perform(
                        post(
                                "/api/backoffice/synthetic-credits"
                        )
                                .with(
                                        jwt()
                                                .jwt(
                                                        token ->
                                                                token.subject(
                                                                        "backoffice-invalid-c3"
                                                                )
                                                )
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "ROLE_COMPLIANCE_OFFICER"
                                                        )
                                                )
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "userId": "%s",
                                          "currency": "EUR",
                                          "amountMinor": 0,
                                          "operationId": "c3_invalid_amount"
                                        }
                                        """
                                                .formatted(
                                                        UUID.randomUUID()
                                                )
                                )
                )
                .andExpect(
                        status().isBadRequest()
                );
    }

    private BigInteger balance(
            LedgerAccountAddress account
    ) {

        return formanceClient
                .getAccountBalance(
                        account.value(),
                        account.formanceAsset()
                );
    }
}