
package com.fintech.kyc.adapter.sumsub;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class SumsubClient {

    private static final String APP_TOKEN_HEADER =
            "X-App-Token";

    private static final String TIMESTAMP_HEADER =
            "X-App-Access-Ts";

    private static final String SIGNATURE_HEADER =
            "X-App-Access-Sig";

    private final RestClient restClient;
    private final SumsubProperties properties;
    private final SumsubRequestSigner signer;
    private final JsonMapper jsonMapper;

    /**
     * Production constructor.
     *
     * Uses the dedicated RestClient configured
     * with Sumsub-specific HTTP timeouts.
     */
    @Autowired
    public SumsubClient(
            @Qualifier("sumsubRestClient")
            RestClient restClient,
            SumsubProperties properties,
            JsonMapper jsonMapper
    ) {
        this.restClient =
                Objects.requireNonNull(restClient);

        this.properties =
                Objects.requireNonNull(properties);

        this.jsonMapper =
                Objects.requireNonNull(jsonMapper);

        this.signer =
                new SumsubRequestSigner(
                        properties.secretKey()
                );
    }

    /**
     * Test-compatible constructor.
     *
     * Retains compatibility with existing tests
     * using MockRestServiceServer.bindTo(builder).
     */
    public SumsubClient(
            RestClient.Builder builder,
            SumsubProperties properties,
            JsonMapper jsonMapper
    ) {
        this(
                Objects.requireNonNull(builder)
                        .baseUrl(
                                Objects.requireNonNull(properties)
                                        .baseUrl()
                        )
                        .build(),
                properties,
                jsonMapper
        );
    }

    /**
     * Checks whether a customer has an existing
     * Sumsub applicant.
     */
    public boolean applicantExists(UUID userId) {
        return findApplicantId(userId).isPresent();
    }

    /**
     * Finds an existing applicant using the NEOS
     * customer's UUID as externalUserId.
     *
     * Never creates a new applicant.
     */
    public Optional<String> findApplicantId(
            UUID userId
    ) {
        Objects.requireNonNull(userId, "userId");

        String path =
                "/resources/applicants/-/byExternalUserId/"
                        + userId;

        long timestamp =
                Instant.now().getEpochSecond();

        String signature = signer.sign(
                timestamp,
                "GET",
                path,
                null
        );

        ApplicantExistenceResponse response =
                restClient
                        .get()
                        .uri(path)
                        .header(
                                APP_TOKEN_HEADER,
                                properties.appToken()
                        )
                        .header(
                                TIMESTAMP_HEADER,
                                Long.toString(timestamp)
                        )
                        .header(
                                SIGNATURE_HEADER,
                                signature
                        )
                        .retrieve()
                        .body(ApplicantExistenceResponse.class);

        if (response == null) {
            throw new IllegalStateException(
                    "Sumsub returned an empty applicant lookup response"
            );
        }

        if (!response.exists()) {
            return Optional.empty();
        }

        ApplicantDetails applicant =
                response.applicant();

        if (applicant == null
                || applicant.id() == null
                || applicant.id().isBlank()) {

            throw new IllegalStateException(
                    "Sumsub applicant exists but its ID is missing"
            );
        }

        /**
         * Prevent associating a different Sumsub
         * applicant with the NEOS customer.
         */
        if (!userId.toString().equals(
                applicant.externalUserId()
        )) {
            throw new IllegalStateException(
                    "Sumsub applicant external user ID mismatch"
            );
        }

        return Optional.of(applicant.id());
    }

    public record ApplicantExistenceResponse(
            boolean exists,
            ApplicantDetails applicant
    ) {
    }

    public record ApplicantDetails(
            String id,
            String externalUserId
    ) {
    }

    /**
     * Generates a short-lived Sumsub SDK token.
     *
     * The signed request bytes must match the
     * exact JSON bytes sent over HTTP.
     */
    public SdkTokenResponse generateSdkToken(
            UUID userId
    ) {
        Objects.requireNonNull(userId, "userId");

        String path =
                "/resources/accessTokens/sdk";

        SdkTokenRequest payload =
                new SdkTokenRequest(
                        properties.sdkTokenTtlSeconds(),
                        userId.toString(),
                        properties.levelName()
                );

        byte[] bodyBytes;

        try {
            bodyBytes =
                    jsonMapper.writeValueAsBytes(payload);

        } catch (JacksonException exception) {

            throw new IllegalStateException(
                    "Unable to serialize Sumsub SDK token request",
                    exception
            );
        }

        long timestamp =
                Instant.now().getEpochSecond();

        String signature = signer.sign(
                timestamp,
                "POST",
                path,
                bodyBytes
        );

        SdkTokenResponse response =
                restClient
                        .post()
                        .uri(path)
                        .header(
                                APP_TOKEN_HEADER,
                                properties.appToken()
                        )
                        .header(
                                TIMESTAMP_HEADER,
                                Long.toString(timestamp)
                        )
                        .header(
                                SIGNATURE_HEADER,
                                signature
                        )
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .body(bodyBytes)
                        .retrieve()
                        .body(SdkTokenResponse.class);

        /**
         * Never return an invalid token to mobile.
         */
        if (response == null
                || response.token() == null
                || response.token().isBlank()) {

            throw new IllegalStateException(
                    "Sumsub returned an invalid SDK token response"
            );
        }

        /**
         * Verify the returned token belongs to
         * the customer requesting verification.
         */
        if (!userId.toString().equals(
                response.userId()
        )) {

            throw new IllegalStateException(
                    "Sumsub SDK token response has an unexpected user ID"
            );
        }

        return response;
    }

    public record SdkTokenRequest(
            long ttlInSecs,
            String userId,
            String levelName
    ) {
    }

    public record SdkTokenResponse(
            String token,
            String userId
    ) {
    }

    /**
     * Creates a Sumsub applicant.
     *
     * IMPORTANT:
     *
     * This method sends exactly one POST request.
     *
     * It must never automatically retry after a
     * connection or read timeout, because Sumsub
     * may already have created the applicant.
     *
     * SumsubKycProvider handles lookup and
     * duplicate-applicant recovery.
     */
    public String createApplicant(
            UUID userId,
            String email,
            String phoneNumber
    ) {
        Objects.requireNonNull(userId, "userId");

        URI uri = UriComponentsBuilder
                .fromUriString(properties.baseUrl())
                .path("/resources/applicants")
                .queryParam(
                        "levelName",
                        properties.levelName()
                )
                .build()
                .encode()
                .toUri();

        String signedPath =
                uri.getRawPath()
                        + "?"
                        + uri.getRawQuery();

        Map<String, String> payload =
                new LinkedHashMap<>();

        payload.put(
                "externalUserId",
                userId.toString()
        );

        if (email != null && !email.isBlank()) {
            payload.put("email", email);
        }

        if (phoneNumber != null
                && !phoneNumber.isBlank()) {

            payload.put(
                    "phone",
                    phoneNumber
            );
        }

        /**
         * Serialize once so the request body
         * and signature use identical bytes.
         */
        byte[] bodyBytes =
                jsonMapper.writeValueAsBytes(payload);

        long timestamp =
                Instant.now().getEpochSecond();

        String signature = signer.sign(
                timestamp,
                "POST",
                signedPath,
                bodyBytes
        );

        String response =
                restClient
                        .post()
                        .uri(uri)
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .header(
                                APP_TOKEN_HEADER,
                                properties.appToken()
                        )
                        .header(
                                TIMESTAMP_HEADER,
                                Long.toString(timestamp)
                        )
                        .header(
                                SIGNATURE_HEADER,
                                signature
                        )
                        .body(bodyBytes)
                        .retrieve()
                        .body(String.class);

        if (response == null || response.isBlank()) {
            throw new IllegalStateException(
                    "Sumsub returned an empty applicant response"
            );
        }

        JsonNode json =
                jsonMapper.readTree(response);

        String applicantId =
                json.path("id").asText();

        if (applicantId.isBlank()) {
            throw new IllegalStateException(
                    "Sumsub response is missing applicant ID"
            );
        }

        return applicantId;
    }
}
