package com.fintech.notifications.adapter.graph;

import com.fintech.notifications.EmailDeliveryException;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public class MicrosoftGraphAccessTokenProvider {

    private static final Duration TOKEN_SAFETY_MARGIN =
            Duration.ofSeconds(
                    60
            );

    private final RestClient identityClient;
    private final MicrosoftGraphProperties properties;

    private volatile CachedToken cachedToken;

    public MicrosoftGraphAccessTokenProvider(
            RestClient identityClient,
            MicrosoftGraphProperties properties
    ) {

        this.identityClient =
                Objects.requireNonNull(
                        identityClient,
                        "identityClient must not be null"
                );

        this.properties =
                Objects.requireNonNull(
                        properties,
                        "properties must not be null"
                );
    }

    public String accessToken() {

        CachedToken current =
                cachedToken;

        if (current != null
                && current.isUsable()) {

            return current.value();
        }

        synchronized (this) {

            current =
                    cachedToken;

            if (current != null
                    && current.isUsable()) {

                return current.value();
            }

            cachedToken =
                    requestToken();

            return cachedToken.value();
        }
    }

    public void invalidate() {

        cachedToken =
                null;
    }

    private CachedToken requestToken() {

        validateConfiguration();

        MultiValueMap<String, String> form =
                new LinkedMultiValueMap<>();

        form.add(
                "client_id",
                properties.clientId()
        );

        form.add(
                "client_secret",
                properties.clientSecret()
        );

        form.add(
                "scope",
                "https://graph.microsoft.com/.default"
        );

        form.add(
                "grant_type",
                "client_credentials"
        );

        try {

            MicrosoftTokenResponse response =
                    identityClient
                            .post()
                            .uri(
                                    "/{tenantId}/oauth2/v2.0/token",
                                    properties.tenantId()
                            )
                            .contentType(
                                    MediaType.APPLICATION_FORM_URLENCODED
                            )
                            .body(
                                    form
                            )
                            .retrieve()
                            .body(
                                    MicrosoftTokenResponse.class
                            );

            if (response == null
                    || response.accessToken() == null
                    || response.accessToken().isBlank()) {

                throw new EmailDeliveryException(
                        "Microsoft identity platform returned no access token",
                        true,
                        Duration.ofSeconds(30)
                );
            }

            long expiresIn =
                    Math.max(
                            response.expiresIn(),
                            120
                    );

            return new CachedToken(
                    response.accessToken(),
                    Instant.now()
                            .plusSeconds(
                                    expiresIn
                            )
            );

        } catch (RestClientResponseException exception) {

            throw tokenHttpFailure(
                    exception
            );

        } catch (RestClientException exception) {

            throw new EmailDeliveryException(
                    "Unable to acquire Microsoft Graph access token",
                    true,
                    Duration.ofSeconds(30),
                    exception
            );
        }
    }

    private EmailDeliveryException tokenHttpFailure(
            RestClientResponseException exception
    ) {

        int status =
                exception
                        .getStatusCode()
                        .value();

        boolean retryable =
                status == 429
                        || status >= 500;

        return new EmailDeliveryException(
                "Microsoft identity token request failed with HTTP "
                        + status,
                retryable,
                retryable
                        ? Duration.ofSeconds(30)
                        : Duration.ZERO,
                exception
        );
    }

    private void validateConfiguration() {

        validateRequired(
                properties.tenantId(),
                "tenantId"
        );

        validateRequired(
                properties.clientId(),
                "clientId"
        );

        validateRequired(
                properties.clientSecret(),
                "clientSecret"
        );
    }

    private static void validateRequired(
            String value,
            String field
    ) {

        if (value == null
                || value.isBlank()) {

            throw new EmailDeliveryException(
                    "Microsoft Graph "
                            + field
                            + " is not configured",
                    false,
                    Duration.ZERO
            );
        }
    }



    private record CachedToken(
            String value,
            Instant expiresAt
    ) {

        private boolean isUsable() {

            return Instant.now()
                    .plus(
                            TOKEN_SAFETY_MARGIN
                    )
                    .isBefore(
                            expiresAt
                    );
        }
    }
}