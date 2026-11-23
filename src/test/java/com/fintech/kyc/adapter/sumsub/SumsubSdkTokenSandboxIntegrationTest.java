package com.fintech.kyc.adapter.sumsub;

import com.fintech.kyc.port.KycProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIfSystemProperty(
        named = "RUN_SUMSUB_SDK_IT",
        matches = "true"
)
class SumsubSdkTokenSandboxIntegrationTest {

    @Autowired
    private KycProvider provider;

    @Autowired
    private SumsubClient client;

    @Autowired
    private SumsubProperties properties;

    @Test
    void generatesRealSdkTokenForExistingApplicant() {

        UUID userId = UUID.nameUUIDFromBytes(
                "neos-sumsub-sandbox-smoke-v1"
                        .getBytes(StandardCharsets.UTF_8)
        );

        // Confirm applicant already exists in Sumsub.
        var applicantId = client.findApplicantId(userId);

        assertTrue(
                applicantId.isPresent(),
                "Sandbox applicant must already exist"
        );

        // Generate a real, short-lived SDK token.
        var session = provider.createVerificationSession(
                new KycProvider.CreateVerificationSessionCommand(
                        userId
                )
        );

        assertNotNull(session);
        assertNotNull(session.token());
        assertFalse(session.token().isBlank());

        assertEquals(
                properties.sdkTokenTtlSeconds(),
                session.expiresInSeconds()
        );

        // Never print the SDK token.
    }
}