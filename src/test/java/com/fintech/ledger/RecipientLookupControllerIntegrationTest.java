package com.fintech.ledger;

import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "neos.transfers.customer-api-enabled=true",
        "neos.recipients.lookup.max-requests-per-minute=5",
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
@AutoConfigureMockMvc
class RecipientLookupControllerIntegrationTest {

    private static final String PATH = "/api/recipients/lookup";

    private static final int LIMIT = 5;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void eligibleRecipientIsFoundWithMaskedEmail() throws Exception {

        Customer caller = eligibleCustomer("sender");
        Customer recipient = eligibleCustomer("receiver");

        mockMvc.perform(lookup(caller, recipient.email()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.recipientUserId")
                        .value(recipient.userId().toString()))
                .andExpect(jsonPath("$.maskedEmail")
                        .value("r***@neos.local"))
                .andExpect(jsonPath("$.displayName").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void emailMatchingIgnoresCaseAndSurroundingWhitespace()
            throws Exception {

        Customer caller = eligibleCustomer("sender");
        Customer recipient = eligibleCustomer("receiver");

        mockMvc.perform(
                        lookup(
                                caller,
                                "  " + recipient.email().toUpperCase() + "  "
                        )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientUserId")
                        .value(recipient.userId().toString()));
    }

    @Test
    void unknownIneligibleAndSelfRecipientsAreIndistinguishable()
            throws Exception {

        Customer caller = eligibleCustomer("sender");

        Customer pendingKyc = onboardedCustomer("pending");

        Customer suspended = eligibleCustomer("suspended");
        setStatuses(suspended.userId(), "SUSPENDED", "APPROVED");

        Customer rejected = eligibleCustomer("rejected");
        setStatuses(rejected.userId(), "ACTIVE", "REJECTED");

        List<String> emails = List.of(
                "nobody." + suffix() + "@neos.local",
                pendingKyc.email(),
                suspended.email(),
                rejected.email(),
                caller.email()
        );

        List<MvcResult> results = new ArrayList<>();

        for (String email : emails) {
            results.add(
                    mockMvc.perform(lookup(caller, email))
                            .andExpect(status().isNotFound())
                            .andReturn()
            );
        }

        for (MvcResult result : results) {

            assertEquals(
                    "Recipient not available",
                    result.getResponse().getErrorMessage()
            );

            assertEquals(
                    results.getFirst().getResponse().getContentAsString(),
                    result.getResponse().getContentAsString()
            );
        }
    }

    @Test
    void ineligibleCallerIsForbidden() throws Exception {

        Customer caller = onboardedCustomer("pending-sender");
        Customer recipient = eligibleCustomer("receiver");

        mockMvc.perform(lookup(caller, recipient.email()))
                .andExpect(status().isForbidden());
    }

    @Test
    void notOnboardedCallerIsForbidden() throws Exception {

        Customer recipient = eligibleCustomer("receiver");

        mockMvc.perform(
                        post(PATH)
                                .with(customer("not-onboarded-" + UUID.randomUUID()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(recipient.email()))
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void blankEmailIsBadRequest() throws Exception {

        Customer caller = eligibleCustomer("sender");

        mockMvc.perform(lookup(caller, "   "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lookupsAboveTheLimitAreRejected() throws Exception {

        Customer caller = eligibleCustomer("sender");
        String email = "nobody." + suffix() + "@neos.local";

        // 2 * LIMIT + 1 attempts exceed the limit even if
        // a minute boundary falls inside the loop.
        boolean limited = false;

        for (int attempt = 0; attempt < 2 * LIMIT + 1; attempt++) {

            int status = mockMvc.perform(lookup(caller, email))
                    .andReturn()
                    .getResponse()
                    .getStatus();

            if (status == 429) {
                limited = true;
                break;
            }

            assertEquals(404, status);
        }

        assertTrue(limited);
    }

    @Test
    void complianceOfficerCannotLookUpRecipients() throws Exception {

        mockMvc.perform(
                        post(PATH)
                                .with(
                                        jwt()
                                                .jwt(token -> token.subject("lookup-compliance"))
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "ROLE_COMPLIANCE_OFFICER"
                                                        )
                                                )
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("someone@neos.local"))
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedCallerCannotLookUpRecipients() throws Exception {

        mockMvc.perform(
                        post(PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("someone@neos.local"))
                )
                .andExpect(status().isUnauthorized());
    }

    private record Customer(
            String subject,
            String email,
            UUID userId
    ) {
    }

    private Customer eligibleCustomer(String prefix) throws Exception {

        Customer customer = onboardedCustomer(prefix);
        setStatuses(customer.userId(), "ACTIVE", "APPROVED");
        return customer;
    }

    private Customer onboardedCustomer(String prefix) throws Exception {

        String suffix = suffix();
        String subject = "lookup-" + prefix + "-" + suffix;
        String email = prefix + "." + suffix + "@neos.local";

        mockMvc.perform(
                        post("/api/onboarding/personal")
                                .with(
                                        jwt()
                                                .jwt(token -> token
                                                        .subject(subject)
                                                        .claim("email", email)
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
                                          "installationId": "android-lookup-%s",
                                          "platform": "ANDROID",
                                          "termsVersion": "1.0"
                                        }
                                        """.formatted(suffix)
                                )
                )
                .andExpect(status().isOk());

        UUID userId = userRepository
                .findByKeycloakSubject(subject)
                .map(UserEntity::getId)
                .orElseThrow();

        return new Customer(subject, email, userId);
    }


    private void setStatuses(
            UUID userId,
            String accountStatus,
            String kycStatus
    ) {

        jdbcTemplate.update(
                """
                UPDATE users
                SET account_status = ?,
                    suspension_reason = CASE
                        WHEN ? = 'SUSPENDED'
                            THEN 'ADMINISTRATIVE'
                        ELSE NULL
                    END
                WHERE id = ?
                """,
                accountStatus,
                accountStatus,
                userId
        );

        jdbcTemplate.update(
                """
                UPDATE kyc_status
                SET status = ?
                WHERE user_id = ?
                """,
                kycStatus,
                userId
        );
    }


    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder lookup(
            Customer caller,
            String email
    ) {
        return post(PATH)
                .with(customer(caller.subject()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(email));
    }

    private static String body(String email) {
        return """
                { "email": "%s" }
                """.formatted(email);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static RequestPostProcessor customer(String subject) {
        return jwt()
                .jwt(token -> token.subject(subject))
                .authorities(
                        new SimpleGrantedAuthority("ROLE_CUSTOMER")
                );
    }
}
