package com.fintech.kyc.adapter.sumsub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIfSystemProperty(
        named = "RUN_SUMSUB_IT",
        matches = "true"
)
class SumsubSandboxSmokeTest {

    @Autowired
    private RestClient.Builder builder;

    @Autowired
    private SumsubProperties properties;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void connectsToSumsubSandbox() {

        assertNotNull(properties.appToken());
        assertFalse(properties.appToken().isBlank());

        assertNotNull(properties.secretKey());
        assertFalse(properties.secretKey().isBlank());

        SumsubClient client = new SumsubClient(
                builder,
                properties,
                jsonMapper
        );

        UUID unknownUserId = UUID.randomUUID();

        boolean exists = client.applicantExists(
                unknownUserId
        );

        assertFalse(
                exists,
                "A newly generated test applicant should not exist"
        );
    }
}