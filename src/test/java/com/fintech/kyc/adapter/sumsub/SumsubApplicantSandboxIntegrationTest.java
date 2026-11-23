package com.fintech.kyc.adapter.sumsub;

import com.fintech.kyc.port.KycProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIfSystemProperty(
        named = "RUN_SUMSUB_CREATE_IT",
        matches = "true"
)
class SumsubApplicantSandboxIntegrationTest {

    @Autowired
    private KycProvider provider;

    @Autowired
    private SumsubClient client;

    @Test
    void createsAndRecoversSandboxApplicant() {

        assertInstanceOf(
                SumsubKycProvider.class,
                provider
        );

        // Deterministic test ID: reruns reuse
        // the same Sandbox applicant.
        UUID userId = UUID.nameUUIDFromBytes(
                "neos-sumsub-sandbox-smoke-v1"
                        .getBytes(StandardCharsets.UTF_8)
        );

        var command =
                new KycProvider.CreateApplicantCommand(
                        userId,
                        null,
                        null
                );

        // First call: create or recover applicant.
        var first = provider.createApplicant(command);

        assertNotNull(first.providerApplicantId());
        assertFalse(first.providerApplicantId().isBlank());

        // Verify the returned ID against Sumsub.
        Optional<String> recovered =
                client.findApplicantId(userId);

        assertEquals(
                Optional.of(first.providerApplicantId()),
                recovered
        );

        // Second call must reuse the same applicant.
        var second = provider.createApplicant(command);

        assertEquals(
                first.providerApplicantId(),
                second.providerApplicantId()
        );
    }
}