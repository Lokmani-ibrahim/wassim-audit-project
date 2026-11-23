package com.fintech.backoffice;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.fintech.ledger.WalletProvisioningService;
import com.fintech.ledger.internal.taxonomy.LedgerCurrency;
import com.fintech.ledger.port.LedgerProvider;
import com.fintech.ledger.port.LedgerOperationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Run only against newly created, isolated Formance and PostgreSQL services. */
@SpringBootTest(properties = {
        "neos.backoffice.enabled=true",
        "neos.backoffice.audience=neos-api",
        "neos.backoffice.client-id=neos-backoffice",
        "neos.backoffice.issuer=https://issuer.example/realms/neos",
        "neos.backoffice.synthetic-credit.enabled=false",
        "neos.transfers.customer-api-enabled=false"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RUN_FORMANCE_IT", matches = "true")
class CustomerFinancialFormanceLiveIntegrationTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000b00001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000b00002");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired WalletProvisioningService provisioning;
    @Autowired LedgerProvider ledger;

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor staff() {
        return jwt().jwt(token -> token.issuer("https://issuer.example/realms/neos")
                .subject("package-c-staff").audience(List.of("neos-api"))
                .claim("azp", "neos-backoffice")
                .claim("realm_access", Map.of("roles", List.of("COMPLIANCE_OFFICER"))));
    }

    @Test
    void realWalletBalancesHistoryAndCustomerBoundCursors() throws Exception {
        for (UUID id : List.of(A, B)) {
            jdbc.update("INSERT INTO users(id,keycloak_subject,email,account_status,created_at,updated_at) " +
                            "VALUES (?,?,?,?,now(),now()) ON CONFLICT (id) DO NOTHING", id,
                    "package-c-" + id, "package-c-" + id + "@review.invalid", "ACTIVE");
        }
        provisioning.provisionCustomerWallets(A);
        for (int i = 0; i < 5; i++)
            ledger.recordIncomingPayment(A, "package_c_bank", LedgerCurrency.TND, 1250 + i,
                    "package_c_" + UUID.randomUUID().toString().replace("-", ""));

        String root = "/api/backoffice/customers/";
        mvc.perform(get(root + B + "/wallets").with(staff()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.wallets").isEmpty());
        mvc.perform(get(root + B + "/transactions?currency=TND").with(staff()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        provisioning.provisionCustomerWallets(B);
        ledger.transferBetweenUsers(A, B, LedgerCurrency.TND, 1000,
                "package_c_transfer_" + UUID.randomUUID().toString().replace("-", ""));
        String payoutId = "package_c_payout_" + UUID.randomUUID().toString().replace("-", "");
        ledger.reservePayout(A, LedgerCurrency.TND, 500, payoutId);
        LedgerOperationResult settlement = ledger.settlePayout(
                A, "package_c_bank", LedgerCurrency.TND, 500, payoutId);

        mvc.perform(get(root + A + "/wallets").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wallets.length()").value(6))
                .andExpect(jsonPath("$.wallets[5].currency").value("TND"))
                .andExpect(jsonPath("$.wallets[5].scale").value(3))
                .andExpect(jsonPath("$.wallets[5].availableMinor").value("4760"))
                .andExpect(jsonPath("$.wallets[5].pendingMinor").value("0"));
        mvc.perform(get(root + B + "/wallets").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wallets[5].availableMinor").value("1000"));
        mvc.perform(get(root + "00000000-0000-0000-0000-000000ffffff/wallets").with(staff()))
                .andExpect(status().isNotFound());

        Set<String> ids = new HashSet<>();
        String cursor = null;
        String firstCursor = null;
        int pages = 0;
        boolean debitTransfer = false;
        boolean settledPayout = false;
        do {
            String query = "?currency=TND&pageSize=2" + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = json.readTree(mvc.perform(get(root + A + "/transactions" + query)
                    .with(staff())).andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString());
            for (JsonNode item : page.path("items")) {
                assertTrue(ids.add(item.path("transactionId").asString()));
                assertEquals("TND", item.path("currency").asString());
                assertEquals(3, item.path("scale").asInt());
                assertEquals("COMPLETED", item.path("status").asString());
                assertTrue(item.path("occurredAt").asText().contains("T"));
                switch (item.path("type").asString()) {
                    case "INCOMING_PAYMENT" -> {
                        assertEquals("CREDIT", item.path("direction").asString());
                        assertTrue(item.path("amountMinor").asText().matches("125[0-4]"));
                    }
                    case "TRANSFER" -> {
                        assertEquals("DEBIT", item.path("direction").asString());
                        assertEquals("1000", item.path("amountMinor").asText());
                        debitTransfer = true;
                    }
                    case "PAYOUT" -> {
                        assertEquals("DEBIT", item.path("direction").asString());
                        assertEquals("500", item.path("amountMinor").asText());
                        assertEquals(Long.toString(settlement.transactionId()),
                                item.path("transactionId").asText());
                        settledPayout = true;
                    }
                    default -> fail("Unexpected ledger mapping");
                }
            }
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
            if (firstCursor == null) firstCursor = cursor;
            pages++;
            assertTrue(pages <= 5);
        } while (cursor != null);
        assertEquals(7, ids.size());
        assertTrue(debitTransfer);
        assertTrue(settledPayout);
        assertNotNull(firstCursor);
        for (String path : List.of(root + B + "/transactions?currency=TND&pageSize=2&cursor=" + firstCursor,
                root + A + "/transactions?currency=EUR&pageSize=2&cursor=" + firstCursor,
                root + A + "/transactions?currency=TND&pageSize=3&cursor=" + firstCursor))
            mvc.perform(get(path).with(staff())).andExpect(status().isBadRequest());
        mvc.perform(get(root + B + "/transactions?currency=TND").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("TRANSFER"))
                .andExpect(jsonPath("$.items[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.items[0].amountMinor").value("1000"));
    }
}
