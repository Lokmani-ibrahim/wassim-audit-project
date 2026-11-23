package com.fintech.identity;

import com.fintech.backoffice.CustomerDirectoryService;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "neos.backoffice.enabled=true",
        "neos.backoffice.audience=neos-api",
        "neos.backoffice.client-id=neos-backoffice",
        "neos.backoffice.issuer=https://issuer.example/realms/neos",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
@AutoConfigureMockMvc
class CustomerDirectoryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired CustomerDirectoryService directory;
    @Autowired EntityManagerFactory entityManagerFactory;

    private final List<UUID> ids = new ArrayList<>();
    private static final String[] KYC = {null, "NOT_STARTED", "PENDING", "IN_REVIEW",
            "APPROVED", "REJECTED", "RESUBMISSION_REQUIRED", null};
    private static final String[] ACCOUNT = {"ACTIVE", "ACTIVE", "PENDING_KYC", "SUSPENDED",
            "CLOSED", "ACTIVE", "ACTIVE", "SUSPENDED"};

    @BeforeEach
    void seed() {
        for (int i = 0; i < KYC.length; i++) {
            UUID id = UUID.fromString(String.format("00000000-0000-0000-0000-%012x", 0xabc000 + i));
            ids.add(id);
            jdbc.update("INSERT INTO users(id,keycloak_subject,email,account_status,suspension_reason,created_at,updated_at) " +
                            "VALUES (?,?,?,?,?,?,?)", id, "pack-b-sub-" + i,
                    "pack-b-" + i + "@review.invalid", ACCOUNT[i],
                    ACCOUNT[i].equals("SUSPENDED") ? "ADMINISTRATIVE" : null,
                    Timestamp.from(Instant.parse("2026-11-09T10:00:00Z")),
                    Timestamp.from(Instant.parse("2026-11-09T10:00:00Z")));
            if (KYC[i] != null) jdbc.update("INSERT INTO kyc_status(id,user_id,status,created_at,updated_at) " +
                            "VALUES (?,?,?,?,?)", UUID.randomUUID(), id, KYC[i],
                    Timestamp.from(Instant.parse("2026-11-09T10:00:00Z")),
                    Timestamp.from(Instant.parse("2026-11-09T10:00:00Z")));
        }
    }

    @AfterEach
    void clean() {
        for (UUID id : ids) {
            jdbc.update("DELETE FROM kyc_status WHERE user_id=?", id);
            jdbc.update("DELETE FROM users WHERE id=?", id);
        }
        ids.clear();
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor staff() {
        return jwt().jwt(token -> token.issuer("https://issuer.example/realms/neos")
                .subject("pack-b-staff")
                .audience(List.of("neos-api"))
                .claim("azp", "neos-backoffice")
                .claim("realm_access", Map.of("roles", List.of("COMPLIANCE_OFFICER"))));
    }

    private JsonNode page(String query) throws Exception {
        MvcResult result = mvc.perform(get("/api/backoffice/customers" + query).with(staff()))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void detailHasOnlyAllowedFieldsAndMissingKycIsNull() throws Exception {
        mvc.perform(get("/api/backoffice/customers/" + ids.getFirst()).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(ids.getFirst().toString()))
                .andExpect(jsonPath("$.kycStatus").value(org.hamcrest.Matchers.nullValue()));
        JsonNode body = json.readTree(mvc.perform(get("/api/backoffice/customers/" + ids.getFirst())
                .with(staff())).andReturn().getResponse().getContentAsString());
        assertThat(body.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder(
                "userId", "email", "accountStatus", "kycStatus", "createdAt");
        mvc.perform(get("/api/backoffice/customers/00000000-0000-0000-0000-000000ffffff").with(staff()))
                .andExpect(status().isNotFound());
    }

    @Test
    void exactSearchAndEveryStatusFilter() throws Exception {
        assertThat(page("?search=PACK-B-1@REVIEW.INVALID").path("items").size()).isEqualTo(1);
        assertThat(page("?search=" + ids.get(2)).path("items").size()).isEqualTo(1);
        assertThat(page("?search=pack-b-1@other.invalid").path("items").size()).isZero();
        for (String status : List.of("PENDING_KYC", "ACTIVE", "SUSPENDED", "CLOSED")) {
            JsonNode items = page("?accountStatus=" + status).path("items");
            assertThat(items.size()).isGreaterThan(0);
            items.forEach(item -> assertThat(item.path("accountStatus").asString()).isEqualTo(status));
        }
        for (String status : List.of("MISSING", "NOT_STARTED", "PENDING", "IN_REVIEW",
                "APPROVED", "REJECTED", "RESUBMISSION_REQUIRED")) {
            JsonNode items = page("?kycStatus=" + status).path("items");
            assertThat(items.size()).isGreaterThan(0);
            items.forEach(item -> assertThat(status.equals("MISSING") ? item.path("kycStatus").isNull()
                    : item.path("kycStatus").asString().equals(status)).isTrue());
        }
    }

    @Test
    void keysetPagesHaveNoDuplicateOrMissingRowsAndCursorBindsFilters() throws Exception {
        Set<String> expected = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM users", UUID.class).stream().map(UUID::toString).toList());
        Set<String> seen = new HashSet<>();
        String cursor = null;
        do {
            JsonNode page = page("?pageSize=3" + (cursor == null ? "" : "&cursor=" + cursor));
            page.path("items").forEach(item -> assertThat(seen.add(item.path("userId").asString())).isTrue());
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asString();
        } while (cursor != null);
        assertThat(seen).isEqualTo(expected);

        String firstCursor = page("?pageSize=1").path("nextCursor").asString();
        mvc.perform(get("/api/backoffice/customers?pageSize=1&accountStatus=ACTIVE&cursor=" +
                firstCursor).with(staff())).andExpect(status().isBadRequest());
        for (String query : List.of("?pageSize=0", "?pageSize=101", "?pageSize=-1",
                "?accountStatus=UNKNOWN", "?kycStatus=UNKNOWN", "?search=partial",
                "?cursor=not-a-cursor", "?extra=1", "?pageSize=1&pageSize=2")) {
            mvc.perform(get("/api/backoffice/customers" + query).with(staff()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void oneIdentityQueryAndOneBulkKycQueryServePage() {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        sessionFactory.getStatistics().clear();
        CustomerDirectoryService.CustomerPage result = directory.list(
                new CustomerDirectoryService.Filter("", "ACTIVE", null, 100), null);
        assertThat(result.items()).filteredOn(row -> ids.contains(row.userId())).hasSize(4);
        assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isEqualTo(2);
    }
}
