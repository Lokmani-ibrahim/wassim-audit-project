package com.fintech.kyc.adapter.sumsub;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.mock.http.client.MockClientHttpRequest;
import tools.jackson.databind.json.JsonMapper;

class SumsubClientTest {

    @Test
    void checksApplicantExistenceWithValidSignature() {

        RestClient.Builder builder = RestClient.builder();

        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

        SumsubProperties properties =
                new SumsubProperties(
                        "https://api.sumsub.com",
                        "test-app-token",
                        "test-secret",
                        "id-and-liveness",
                        600
                );

        SumsubClient client =
                new SumsubClient(
                        builder,
                        properties,
                        JsonMapper.builder().build()
                );

        UUID userId = UUID.fromString(
                "00000000-0000-0000-0000-000000000001"
        );

        String path =
                "/resources/applicants/-/byExternalUserId/"
                        + userId;

        server.expect(
                        requestTo(
                                "https://api.sumsub.com" + path
                        )
                )
                .andExpect(method(HttpMethod.GET))
                .andExpect(
                        header(
                                "X-App-Token",
                                "test-app-token"
                        )
                )
                .andExpect(request -> {

                    String timestamp = request
                            .getHeaders()
                            .getFirst("X-App-Access-Ts");

                    assertNotNull(timestamp);

                    String expectedSignature =
                            new SumsubRequestSigner("test-secret")
                                    .sign(
                                            Long.parseLong(timestamp),
                                            "GET",
                                            path,
                                            null
                                    );

                    assertEquals(
                            expectedSignature,
                            request.getHeaders()
                                    .getFirst("X-App-Access-Sig")
                    );
                })
                .andRespond(
                        withSuccess(
                                "{\"exists\":false}",
                                MediaType.APPLICATION_JSON
                        )
                );

        boolean exists = client.applicantExists(userId);

        assertFalse(exists);

        server.verify();
    }
    @Test
    void generatesSdkTokenWithValidSignature() {

        RestClient.Builder builder = RestClient.builder();

        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

        SumsubProperties properties =
                new SumsubProperties(
                        "https://api.sumsub.com",
                        "test-app-token",
                        "test-secret",
                        "id-and-liveness",
                        600
                );

        SumsubClient client = new SumsubClient(
                builder,
                properties,
                JsonMapper.builder().build()
        );

        UUID userId = UUID.fromString(
                "00000000-0000-0000-0000-000000000001"
        );

        String path = "/resources/accessTokens/sdk";

        server.expect(
                        requestTo("https://api.sumsub.com" + path)
                )
                .andExpect(method(HttpMethod.POST))
                .andExpect(
                        header("X-App-Token", "test-app-token")
                )
                .andExpect(
                        content().json("""
                            {
                              "ttlInSecs": 600,
                              "userId": "00000000-0000-0000-0000-000000000001",
                              "levelName": "id-and-liveness"
                            }
                            """)
                )
                .andExpect(request -> {

                    String timestamp = request
                            .getHeaders()
                            .getFirst("X-App-Access-Ts");

                    assertNotNull(timestamp);

                    byte[] actualBody =
                            ((MockClientHttpRequest) request)
                                    .getBodyAsBytes();

                    String expectedSignature =
                            new SumsubRequestSigner("test-secret")
                                    .sign(
                                            Long.parseLong(timestamp),
                                            "POST",
                                            path,
                                            actualBody
                                    );

                    assertEquals(
                            expectedSignature,
                            request.getHeaders()
                                    .getFirst("X-App-Access-Sig")
                    );
                })
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "token": "sandbox-sdk-token",
                                  "userId": "00000000-0000-0000-0000-000000000001"
                                }
                                """,
                                MediaType.APPLICATION_JSON
                        )
                );

        SumsubClient.SdkTokenResponse response =
                client.generateSdkToken(userId);

        assertEquals(
                "sandbox-sdk-token",
                response.token()
        );

        assertEquals(
                userId.toString(),
                response.userId()
        );

        server.verify();
    }
    @Test
    void createsApplicantWithCorrectSignature() {

        RestClient.Builder builder = RestClient.builder();

        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

        SumsubProperties properties = new SumsubProperties(
                "https://api.sumsub.com",
                "test-app-token",
                "test-secret",
                "id-and-liveness",
                600
        );

        SumsubClient client = new SumsubClient(
                builder,
                properties,
                JsonMapper.builder().build()
        );

        UUID userId = UUID.fromString(
                "00000000-0000-0000-0000-000000000001"
        );

        String path =
                "/resources/applicants?levelName=id-and-liveness";

        server.expect(
                        requestTo("https://api.sumsub.com" + path)
                )
                .andExpect(method(HttpMethod.POST))
                .andExpect(
                        header("X-App-Token", "test-app-token")
                )
                .andExpect(content().json("""
                    {
                      "externalUserId":
                        "00000000-0000-0000-0000-000000000001",
                      "email": "test@example.com",
                      "phone": "+21612345678"
                    }
                    """))
                .andExpect(request -> {

                    String timestamp = request.getHeaders()
                            .getFirst("X-App-Access-Ts");

                    assertNotNull(timestamp);

                    byte[] sentBody =
                            ((org.springframework.mock.http.client
                                    .MockClientHttpRequest) request)
                                    .getBodyAsBytes();

                    String expectedSignature =
                            new SumsubRequestSigner("test-secret")
                                    .sign(
                                            Long.parseLong(timestamp),
                                            "POST",
                                            path,
                                            sentBody
                                    );

                    assertEquals(
                            expectedSignature,
                            request.getHeaders()
                                    .getFirst("X-App-Access-Sig")
                    );
                })
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "id": "sandbox-applicant-123",
                                  "externalUserId":
                                    "00000000-0000-0000-0000-000000000001"
                                }
                                """,
                                MediaType.APPLICATION_JSON
                        )
                );

        String applicantId = client.createApplicant(
                userId,
                "test@example.com",
                "+21612345678"
        );

        assertEquals(
                "sandbox-applicant-123",
                applicantId
        );

        server.verify();
    }
    @Test
    void findsExistingApplicantByExternalUserId() {

        RestClient.Builder builder = RestClient.builder();

        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

        SumsubProperties properties = new SumsubProperties(
                "https://api.sumsub.com",
                "test-app-token",
                "test-secret",
                "id-and-liveness",
                600
        );

        SumsubClient client = new SumsubClient(
                builder,
                properties,
                JsonMapper.builder().build()
        );

        UUID userId = UUID.fromString(
                "00000000-0000-0000-0000-000000000001"
        );

        String path =
                "/resources/applicants/-/byExternalUserId/"
                        + userId;

        server.expect(
                        requestTo("https://api.sumsub.com" + path)
                )
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "exists": true,
                                  "applicant": {
                                    "id": "sandbox-applicant-123",
                                    "externalUserId":
                                      "00000000-0000-0000-0000-000000000001"
                                  }
                                }
                                """,
                                MediaType.APPLICATION_JSON
                        )
                );

        assertEquals(
                Optional.of("sandbox-applicant-123"),
                client.findApplicantId(userId)
        );

        server.verify();
    }
}