package com.fintech.onboarding;

import com.fintech.identity.IdentityRegistrationService;
import com.fintech.identity.OnboardingIdentityConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_ONBOARDING_CONTAINER_IT", matches = "true")
@ActiveProfiles("dev")
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "neos.ledger.wallet-provisioning.recovery.enabled=false",
        "neos.kyc.sumsub.webhook-enabled=false",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false",
        "neos.ledger.formance.base-url=http://127.0.0.1:1"
})
@AutoConfigureMockMvc
class OnboardingIdentityContainerIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("onboarding_identity_it")
            .withUsername("onboarding_test")
            .withPassword("local_test_only");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired PersonalOnboardingService onboarding;
    @Autowired IdentityRegistrationService identities;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private PersonalOnboardingResult submit(String subject, String email, String installation) {
        return onboarding.onboard(subject, email, null, installation, "ANDROID", "1.0");
    }

    @Test
    void bootstrapDistinguishesOwnIdentityFromAnotherOwnersEmailAndRejectsMissingClaims()
            throws Exception {
        String email = "bootstrap-" + UUID.randomUUID() + "@example.test";
        String owner = "owner-" + UUID.randomUUID();
        submit(owner, email, "owner-device");
        String other = "other-" + UUID.randomUUID();
        mvc.perform(get("/api/auth/me").with(jwt().jwt(token -> token.subject(other)
                        .claim("email", email).claim("preferred_username", email))
                        .authorities(createAuthorityList("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(other))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.username").value(email))
                .andExpect(jsonPath("$.onboardingEligibility").value("CONFLICT"));
        mvc.perform(get("/api/auth/me").with(jwt().jwt(token -> token.subject(owner)
                        .claim("email", "changed-" + email))
                        .authorities(createAuthorityList("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingEligibility").value("EXISTING"));
        mvc.perform(get("/api/auth/me").with(jwt().jwt(token -> token.subject(other)
                        .claim("preferred_username", "missing"))
                        .authorities(createAuthorityList("ROLE_CUSTOMER"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("IDENTITY_CLAIMS_UNUSABLE"));
        mvc.perform(post("/api/onboarding/personal").with(jwt().jwt(token -> token.subject(other))
                        .authorities(createAuthorityList("ROLE_CUSTOMER")))
                        .contentType("application/json")
                        .content("{\"installationId\":\"missing-email-device\",\"platform\":\"ANDROID\",\"termsVersion\":\"1.0\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("IDENTITY_CLAIMS_UNUSABLE"));
        mvc.perform(get("/api/auth/me").with(jwt().jwt(token -> token.subject(owner))
                        .authorities(createAuthorityList("ROLE_CUSTOMER"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void preflightRaceIsCaughtAtFinalWriteWithoutLinking() throws Exception {
        String email = "race-" + UUID.randomUUID() + "@example.test";
        String contender = "contender-" + UUID.randomUUID();
        assertEquals(IdentityRegistrationService.OnboardingEligibility.READY,
                identities.eligibility(contender, email));
        submit("winner-" + UUID.randomUUID(), email, "winner-device");
        mvc.perform(post("/api/onboarding/personal").with(jwt().jwt(token -> token
                        .subject(contender).claim("email", email))
                        .authorities(createAuthorityList("ROLE_CUSTOMER")))
                        .contentType("application/json")
                        .content("{\"installationId\":\"contender-device\",\"platform\":\"ANDROID\",\"termsVersion\":\"1.0\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ONBOARDING_IDENTITY_CONFLICT"));
        assertEquals(1L, count("users", "email", email));
    }

    @Test
    void exactEmailPolicyMatchesExistingDatabaseKeyAndRejectsUnusableClaims() {
        String email = "Case-" + UUID.randomUUID() + "@example.test";
        submit("case-owner-" + UUID.randomUUID(), email, "case-device");
        assertEquals(IdentityRegistrationService.OnboardingEligibility.CONFLICT,
                identities.eligibility("other-" + UUID.randomUUID(), email));
        // PostgreSQL's existing users.email key is case-sensitive. Changing this policy
        // requires a reviewed migration; this test records the current semantics.
        assertEquals(IdentityRegistrationService.OnboardingEligibility.READY,
                identities.eligibility("variant-" + UUID.randomUUID(), email.toLowerCase()));
        assertThrows(com.fintech.identity.UnusableIdentityClaimsException.class,
                () -> identities.eligibility("subject", " bad@example.test "));
    }

    @Test
    void sameSubjectConcurrentRetryPreservesOneCompleteCustomer() throws Exception {
        String subject = "same-" + UUID.randomUUID();
        String email = subject + "@example.test";
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<PersonalOnboardingResult> first = pool.submit(() -> {
                start.await(); return submit(subject, email, "same-device");
            });
            Future<PersonalOnboardingResult> second = pool.submit(() -> {
                start.await(); return submit(subject, email, "same-device");
            });
            start.countDown();
            assertEquals(first.get().userId(), second.get().userId());
        }
        assertEquals(1L, count("users", "keycloak_subject", subject));
        assertEquals(1L, jdbc.queryForObject("select count(*) from devices where installation_id = 'same-device' and user_id = (select id from users where keycloak_subject = ?)", Long.class, subject));
        assertEquals(1L, jdbc.queryForObject("select count(*) from terms_acceptances where user_id = (select id from users where keycloak_subject = ?)", Long.class, subject));
        assertEquals(1L, jdbc.queryForObject("select count(*) from kyc_status where user_id = (select id from users where keycloak_subject = ?)", Long.class, subject));
        assertEquals(IdentityRegistrationService.OnboardingEligibility.EXISTING,
                identities.eligibility(subject, email));
        // A lost 200 can be retried safely, and an existing KYC state is never reset.
        jdbc.update("update kyc_status set status = 'PENDING' where user_id = (select id from users where keycloak_subject = ?)", subject);
        submit(subject, email, "same-device");
        assertEquals("PENDING", jdbc.queryForObject("select status from kyc_status where user_id = (select id from users where keycloak_subject = ?)", String.class, subject));
    }

    @Test
    void sameSubjectWithDifferentEmailClaimsSerializesAndDoesNotOverwriteOwnership() throws Exception {
        String subject = "changed-" + UUID.randomUUID();
        String firstEmail = "first-" + UUID.randomUUID() + "@example.test";
        String secondEmail = "second-" + UUID.randomUUID() + "@example.test";
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<PersonalOnboardingResult> first = pool.submit(() -> {
                start.await(); return submit(subject, firstEmail, "changed-device");
            });
            Future<PersonalOnboardingResult> second = pool.submit(() -> {
                start.await(); return submit(subject, secondEmail, "changed-device");
            });
            start.countDown();
            assertEquals(first.get().userId(), second.get().userId());
        }
        assertEquals(1L, count("users", "keycloak_subject", subject));
        String savedEmail = jdbc.queryForObject(
                "select email from users where keycloak_subject = ?", String.class, subject);
        assertTrue(savedEmail.equals(firstEmail) || savedEmail.equals(secondEmail));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from devices where user_id = (select id from users where keycloak_subject = ?)",
                Long.class, subject));
    }

    @Test
    void differentSubjectsCompetingForExactEmailNeverLink() throws Exception {
        String email = "compete-" + UUID.randomUUID() + "@example.test";
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> first = pool.submit(() -> compete(start, "first-" + UUID.randomUUID(), email));
            Future<Boolean> second = pool.submit(() -> compete(start, "second-" + UUID.randomUUID(), email));
            start.countDown();
            assertNotEquals(first.get(), second.get());
        }
        assertEquals(1L, count("users", "email", email));
    }

    private boolean compete(CountDownLatch start, String subject, String email) throws Exception {
        start.await();
        try { submit(subject, email, subject); return true; }
        catch (OnboardingIdentityConflictException conflict) { return false; }
    }

    private long count(String table, String column, String value) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?",
                Long.class, value);
    }
}
