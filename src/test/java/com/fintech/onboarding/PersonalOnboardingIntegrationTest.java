package com.fintech.onboarding;

import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.DeviceRepository;
import com.fintech.identity.repository.UserRepository;
import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.repository.KycStatusRepository;
import com.fintech.onboarding.repository.TermsAcceptanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class PersonalOnboardingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private KycStatusRepository kycStatusRepository;

    @Autowired
    private TermsAcceptanceRepository termsAcceptanceRepository;

    @Test
    void customerCanCompleteInitialPersonalOnboarding() throws Exception {

        String subject = "b1-test-customer";
        String email = "b1.customer@neos.local";

        mockMvc.perform(
                        post("/api/onboarding/personal")
                                .with(jwt()
                                        .jwt(jwt -> jwt
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
                                .content("""
                                        {
                                          "phoneNumber": "+21620123456",
                                          "installationId": "android-b1-test-001",
                                          "platform": "ANDROID",
                                          "termsVersion": "1.0"
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").exists())
                .andExpect(jsonPath("$.deviceId").exists())
                .andExpect(jsonPath("$.kycStatus")
                        .value("NOT_STARTED"))
                .andExpect(jsonPath("$.termsVersion")
                        .value("1.0"));

        UserEntity user = userRepository
                .findByKeycloakSubject(subject)
                .orElseThrow();

        assertThat(user.getEmail())
                .isEqualTo(email);

        assertThat(
                deviceRepository.findAllByUser_Id(
                        user.getId()
                )
        ).hasSize(1);

        assertThat(
                kycStatusRepository
                        .findByUserId(user.getId())
        )
                .isPresent()
                .get()
                .extracting(kyc -> kyc.getStatus())
                .isEqualTo(KycStatus.NOT_STARTED);

        assertThat(
                termsAcceptanceRepository
                        .findByUserIdAndTermsVersion(
                                user.getId(),
                                "1.0"
                        )
        ).isPresent();
    }
    @Test
    void retryingSameOnboardingDoesNotCreateDuplicates()
            throws Exception {

        String subject = "b1-retry-customer";
        String email = "b1.retry@neos.local";

        String requestBody = """
            {
              "phoneNumber": "+21620123457",
              "installationId": "android-b1-retry-001",
              "platform": "ANDROID",
              "termsVersion": "1.0"
            }
            """;

        var authentication = jwt()
                .jwt(jwt -> jwt
                        .subject(subject)
                        .claim("email", email)
                )
                .authorities(
                        new SimpleGrantedAuthority(
                                "ROLE_CUSTOMER"
                        )
                );

        mockMvc.perform(
                        post("/api/onboarding/personal")
                                .with(authentication)
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(requestBody)
                )
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/onboarding/personal")
                                .with(authentication)
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(requestBody)
                )
                .andExpect(status().isOk());

        UserEntity user = userRepository
                .findByKeycloakSubject(subject)
                .orElseThrow();

        assertThat(
                deviceRepository
                        .findAllByUser_Id(user.getId())
        ).hasSize(1);

        assertThat(
                kycStatusRepository
                        .findByUserId(user.getId())
        ).isPresent();

        assertThat(
                termsAcceptanceRepository
                        .findAllByUserIdOrderByAcceptedAtDesc(
                                user.getId()
                        )
        ).hasSize(1);
    }
    @Test
    void nonCustomerCannotPerformPersonalOnboarding()
            throws Exception {

        mockMvc.perform(
                        post("/api/onboarding/personal")
                                .with(jwt()
                                        .jwt(jwt -> jwt
                                                .subject(
                                                        "compliance-test"
                                                )
                                                .claim(
                                                        "email",
                                                        "compliance@neos.local"
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
                                .content("""
                                    {
                                      "phoneNumber": "+21620123458",
                                      "installationId": "compliance-device",
                                      "platform": "ANDROID",
                                      "termsVersion": "1.0"
                                    }
                                    """)
                )
                .andExpect(status().isForbidden());
    }
    @Test
    void invalidOnboardingRequestReturnsBadRequest()
            throws Exception {

        String subject = "b1-invalid-request";

        mockMvc.perform(
                        post("/api/onboarding/personal")
                                .with(jwt()
                                        .jwt(jwt -> jwt
                                                .subject(subject)
                                                .claim(
                                                        "email",
                                                        "invalid@neos.local"
                                                )
                                        )
                                        .authorities(
                                                new SimpleGrantedAuthority(
                                                        "ROLE_CUSTOMER"
                                                )
                                        )
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {
                                      "phoneNumber": "+21620123456",
                                      "installationId": "",
                                      "platform": "ANDROID",
                                      "termsVersion": "1.0"
                                    }
                                    """)
                )
                .andExpect(status().isBadRequest());

        assertThat(
                userRepository.existsByKeycloakSubject(
                        subject
                )
        ).isFalse();
    }
    @Test
    void unsupportedTermsVersionReturnsBadRequest()
            throws Exception {

        String subject = "b1-old-terms";

        mockMvc.perform(
                        post("/api/onboarding/personal")
                                .with(jwt()
                                        .jwt(jwt -> jwt
                                                .subject(subject)
                                                .claim(
                                                        "email",
                                                        "oldterms@neos.local"
                                                )
                                        )
                                        .authorities(
                                                new SimpleGrantedAuthority(
                                                        "ROLE_CUSTOMER"
                                                )
                                        )
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {
                                      "phoneNumber": "+21620123456",
                                      "installationId": "android-old-terms",
                                      "platform": "ANDROID",
                                      "termsVersion": "0.9"
                                    }
                                    """)
                )
                .andExpect(status().isBadRequest());

        assertThat(
                userRepository.existsByKeycloakSubject(
                        subject
                )
        ).isFalse();
    }
}