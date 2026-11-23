
package com.fintech.onboarding;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "neos.notifications.microsoft-graph.enabled=false",
        "neos.notifications.dispatcher.enabled=false"
})
@AutoConfigureMockMvc
class CustomerProfileControllerIntegrationTest {

    private static final String PATH = "/api/profile";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void onboardedCustomerReadsOwnProfile() throws Exception {

        String suffix = suffix();
        String subject = onboard(suffix, "+21620123456");

        UUID userId = userId(subject);

        mockMvc.perform(get(PATH).with(customer(subject)))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Cache-Control",
                        containsString("no-store")
                ))
                .andExpect(jsonPath("$.userId")
                        .value(userId.toString()))
                .andExpect(jsonPath("$.email")
                        .value("profile." + suffix + "@neos.local"))
                .andExpect(jsonPath("$.phoneNumber")
                        .value("+21620123456"))
                .andExpect(jsonPath("$.accountStatus")
                        .value("PENDING_KYC"))
                .andExpect(jsonPath("$.kycStatus")
                        .value("NOT_STARTED"))
                .andExpect(jsonPath("$.onboardedAt").exists())
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void phoneNumberIsNullWhenNotProvided() throws Exception {

        String subject = onboard(suffix(), null);

        mockMvc.perform(get(PATH).with(customer(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber")
                        .value(nullValue()));
    }

    @Test
    void suspendedAndApprovedAreReportedAsStored()
            throws Exception {

        String subject = onboard(suffix(), "+21620123456");
        UUID userId = userId(subject);

        setStatuses(userId, "SUSPENDED", "APPROVED");

        mockMvc.perform(get(PATH).with(customer(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountStatus")
                        .value("SUSPENDED"))
                .andExpect(jsonPath("$.kycStatus")
                        .value("APPROVED"));
    }

    @Test
    void resubmissionRequiredIsReported() throws Exception {

        String subject = onboard(suffix(), "+21620123456");
        UUID userId = userId(subject);

        setStatuses(
                userId,
                "PENDING_KYC",
                "RESUBMISSION_REQUIRED"
        );

        mockMvc.perform(get(PATH).with(customer(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus")
                        .value("RESUBMISSION_REQUIRED"));
    }

    @Test
    void readingTheProfileHasNoSideEffects() throws Exception {

        String subject = onboard(suffix(), "+21620123456");
        UUID userId = userId(subject);

        mockMvc.perform(get(PATH).with(customer(subject)))
                .andExpect(status().isOk());

        mockMvc.perform(get(PATH).with(customer(subject)))
                .andExpect(jsonPath("$.accountStatus")
                        .value("PENDING_KYC"))
                .andExpect(jsonPath("$.kycStatus")
                        .value("NOT_STARTED"));

        assertEquals(
                "NOT_STARTED",
                jdbcTemplate.queryForObject(
                        """
                        SELECT status
                        FROM kyc_status
                        WHERE user_id = ?
                        """,
                        String.class,
                        userId
                )
        );
    }

    @Test
    void customerWithoutProfileGetsNotFound() throws Exception {

        mockMvc.perform(
                        get(PATH).with(
                                customer(
                                        "not-onboarded-"
                                                + UUID.randomUUID()
                                )
                        )
                )
                .andExpect(status().isNotFound());
    }

    @Test
    void complianceOfficerCannotReadCustomerProfile()
            throws Exception {

        mockMvc.perform(
                        get(PATH).with(
                                jwt()
                                        .jwt(token -> token.subject(
                                                "profile-compliance"
                                        ))
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
    void unauthenticatedCallerCannotReadProfile()
            throws Exception {

        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized());
    }

    private String onboard(
            String suffix,
            String phoneNumber
    ) throws Exception {

        String subject = "profile-" + suffix;

        String phone =
                phoneNumber == null
                        ? "null"
                        : "\"" + phoneNumber + "\"";

        mockMvc.perform(
                        post("/api/onboarding/personal")
                                .with(
                                        jwt()
                                                .jwt(token -> token
                                                        .subject(subject)
                                                        .claim(
                                                                "email",
                                                                "profile."
                                                                        + suffix
                                                                        + "@neos.local"
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
                                          "phoneNumber": %s,
                                          "installationId": "android-profile-%s",
                                          "platform": "ANDROID",
                                          "termsVersion": "1.0"
                                        }
                                        """.formatted(phone, suffix)
                                )
                )
                .andExpect(status().isOk());

        return subject;
    }

    private UUID userId(String subject) {

        return userRepository
                .findByKeycloakSubject(subject)
                .map(UserEntity::getId)
                .orElseThrow();
    }

    /**
     * Test-only status fixture.
     *
     * V13 requires every suspended user to have a
     * non-null suspension reason.
     *
     * Direct SQL updates must maintain this constraint.
     *
     * These test scenarios use ADMINISTRATIVE when
     * simulating a generic suspended account.
     */
    private void setStatuses(
            UUID userId,
            String accountStatus,
            String kycStatus
    ) {

        // Update account state with a consistent suspension reason.
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

        // Update the KYC state in its own table.
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

    private static String suffix() {

        return UUID.randomUUID()
                .toString()
                .replace("-", "");
    }

    private static RequestPostProcessor customer(
            String subject
    ) {

        return jwt()
                .jwt(token -> token.subject(subject))
                .authorities(
                        new SimpleGrantedAuthority(
                                "ROLE_CUSTOMER"
                        )
                );
    }
}
