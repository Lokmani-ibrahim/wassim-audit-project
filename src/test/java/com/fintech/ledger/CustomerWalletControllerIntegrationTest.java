package com.fintech.ledger;

import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.ledger.adapter.formance.FormanceAccountData;
import com.fintech.ledger.adapter.formance.FormanceAccountsCursor;
import com.fintech.ledger.adapter.formance.FormanceAccountsResponse;
import com.fintech.ledger.adapter.formance.FormanceClient;
import com.fintech.ledger.adapter.formance.FormanceVolumeBalance;
import com.fintech.ledger.adapter.formance.FormanceVolumesCursor;
import com.fintech.ledger.adapter.formance.FormanceVolumesResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
@AutoConfigureMockMvc
class CustomerWalletControllerIntegrationTest {

    private static final String PATH = "/api/wallets";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean(enforceOverride = true)
    private FormanceClient formanceClient;

    @Test
    void onboardedCustomerReadsWalletsWithStringAmounts()
            throws Exception {

        String subject = onboardCustomer();

        UUID userId = userRepository
                .findByKeycloakSubject(subject)
                .map(UserEntity::getId)
                .orElseThrow();

        when(formanceClient.getAccounts(any(), anyInt(), any()))
                .thenReturn(
                        new FormanceAccountsResponse(
                                new FormanceAccountsCursor(
                                        List.of(
                                                new FormanceAccountData(
                                                        "users:" + userId
                                                                + ":wallet:EUR:available",
                                                        Map.of(
                                                                "neos.wallet_status",
                                                                "ACTIVE"
                                                        )
                                                )
                                        ),
                                        false,
                                        null,
                                        100,
                                        null
                                )
                        )
                );

        when(formanceClient.getVolumes(any(), anyInt(), any()))
                .thenReturn(
                        new FormanceVolumesResponse(
                                new FormanceVolumesCursor(
                                        List.of(
                                                new FormanceVolumeBalance(
                                                        "users:" + userId
                                                                + ":wallet:EUR:available",
                                                        "EUR/2",
                                                        new BigInteger("10000"),
                                                        new BigInteger("10000"),
                                                        BigInteger.ZERO
                                                )
                                        ),
                                        false,
                                        null,
                                        100,
                                        null
                                ),
                                null
                        )
                );

        mockMvc.perform(
                        get(PATH)
                                .with(customer(subject))
                )
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                // KYC is NOT_STARTED and only EUR is provisioned.
                .andExpect(jsonPath("$.walletsStatus").value("PROVISIONING"))
                .andExpect(jsonPath("$.asOf").exists())
                .andExpect(jsonPath("$.wallets.length()").value(6))
                .andExpect(jsonPath("$.wallets[0].currency").value("EUR"))
                .andExpect(jsonPath("$.wallets[0].scale").value(2))
                .andExpect(jsonPath("$.wallets[0].asset").value("EUR/2"))
                .andExpect(jsonPath("$.wallets[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.wallets[0].availableMinor").value("10000"))
                .andExpect(jsonPath("$.wallets[0].pendingMinor").value("0"))
                .andExpect(jsonPath("$.wallets[5].currency").value("TND"))
                .andExpect(jsonPath("$.wallets[5].scale").value(3))
                .andExpect(jsonPath("$.wallets[5].status").value("NOT_PROVISIONED"))
                .andExpect(jsonPath("$.wallets[5].availableMinor").value(nullValue()))
                .andExpect(jsonPath("$.wallets[5].pendingMinor").value(nullValue()))
                // Null amounts are serialized, not omitted.
                .andExpect(content().string(containsString(
                        "\"availableMinor\":null"
                )));
    }

    @Test
    void ledgerOutageReturnsServiceUnavailable()
            throws Exception {

        String subject = onboardCustomer();

        when(formanceClient.getAccounts(any(), anyInt(), any()))
                .thenThrow(new LedgerProviderUnavailableException(
                        "Formance down"
                ));

        mockMvc.perform(
                        get(PATH)
                                .with(customer(subject))
                )
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void customerWithoutProfileGetsNotFound()
            throws Exception {

        mockMvc.perform(
                        get(PATH)
                                .with(customer("not-onboarded-" + UUID.randomUUID()))
                )
                .andExpect(status().isNotFound());

        verifyNoInteractions(formanceClient);
    }

    @Test
    void complianceOfficerCannotReadCustomerWallets()
            throws Exception {

        mockMvc.perform(
                        get(PATH)
                                .with(
                                        jwt()
                                                .jwt(token -> token.subject("wallet-compliance"))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "ROLE_COMPLIANCE_OFFICER"
                                                        )
                                                )
                                )
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedCallerCannotReadWallets()
            throws Exception {

        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized());
    }

    private String onboardCustomer() throws Exception {

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String subject = "wallets-" + suffix;

        mockMvc.perform(
                        post("/api/onboarding/personal")
                                .with(
                                        jwt()
                                                .jwt(token -> token
                                                        .subject(subject)
                                                        .claim(
                                                                "email",
                                                                "wallets." + suffix + "@neos.local"
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
                                          "installationId": "android-wallets-%s",
                                          "platform": "ANDROID",
                                          "termsVersion": "1.0"
                                        }
                                        """.formatted(suffix)
                                )
                )
                .andExpect(status().isOk());

        return subject;
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
