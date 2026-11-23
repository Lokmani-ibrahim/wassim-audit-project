package com.fintech.notifications.adapter.graph;

import com.fintech.notifications.EmailDeliveryException;
import com.fintech.notifications.port.EmailNotificationPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MicrosoftGraphEmailAdapter
        implements EmailNotificationPort {

    private final RestClient graphClient;
    private final MicrosoftGraphAccessTokenProvider tokenProvider;
    private final MicrosoftGraphProperties properties;

    public MicrosoftGraphEmailAdapter(
            RestClient graphClient,
            MicrosoftGraphAccessTokenProvider tokenProvider,
            MicrosoftGraphProperties properties
    ) {

        this.graphClient =
                Objects.requireNonNull(
                        graphClient,
                        "graphClient must not be null"
                );

        this.tokenProvider =
                Objects.requireNonNull(
                        tokenProvider,
                        "tokenProvider must not be null"
                );

        this.properties =
                Objects.requireNonNull(
                        properties,
                        "properties must not be null"
                );
    }

    @Override
    public void sendHtml(
            String recipient,
            String subject,
            String htmlBody
    ) {

        String requiredRecipient =
                requireText(
                        recipient,
                        "recipient"
                );

        String requiredSubject =
                requireText(
                        subject,
                        "subject"
                );

        String requiredHtml =
                requireText(
                        htmlBody,
                        "htmlBody"
                );

        try {

            sendOnce(
                    tokenProvider.accessToken(),
                    requiredRecipient,
                    requiredSubject,
                    requiredHtml
            );

        } catch (RestClientResponseException exception) {

            /*
             * A 401 may simply mean the cached access token
             * became invalid earlier than expected.
             *
             * Refresh ONCE and retry.
             */
            if (exception
                    .getStatusCode()
                    .value() == 401) {

                tokenProvider.invalidate();

                try {

                    sendOnce(
                            tokenProvider.accessToken(),
                            requiredRecipient,
                            requiredSubject,
                            requiredHtml
                    );

                    return;

                } catch (RestClientResponseException retryException) {

                    throw graphHttpFailure(
                            retryException
                    );
                }
            }

            throw graphHttpFailure(
                    exception
            );

        } catch (RestClientException exception) {

            throw new EmailDeliveryException(
                    "Unable to call Microsoft Graph sendMail",
                    true,
                    Duration.ofSeconds(30),
                    exception
            );
        }
    }

    private void sendOnce(
            String accessToken,
            String recipient,
            String subject,
            String htmlBody
    ) {

        Map<String, Object> request =
                Map.of(
                        "message",
                        Map.of(
                                "subject",
                                subject,

                                "body",
                                Map.of(
                                        "contentType",
                                        "HTML",
                                        "content",
                                        htmlBody
                                ),

                                "toRecipients",
                                List.of(
                                        Map.of(
                                                "emailAddress",
                                                Map.of(
                                                        "address",
                                                        recipient
                                                )
                                        )
                                )
                        ),

                        "saveToSentItems",
                        true
                );

        ResponseEntity<Void> response =
                graphClient
                        .post()
                        .uri(
                                "/users/{sender}/sendMail",
                                properties.sender()
                        )
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer "
                                        + accessToken
                        )
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .body(
                                request
                        )
                        .retrieve()
                        .toBodilessEntity();

        /*
         * Graph's documented successful response for sendMail
         * is 202 Accepted.
         */
        if (response.getStatusCode()
                != HttpStatus.ACCEPTED) {

            throw new EmailDeliveryException(
                    "Microsoft Graph sendMail returned unexpected HTTP "
                            + response.getStatusCode().value(),
                    true,
                    Duration.ofSeconds(30)
            );
        }
    }

    private EmailDeliveryException graphHttpFailure(
            RestClientResponseException exception
    ) {

        int status =
                exception
                        .getStatusCode()
                        .value();

        if (status == 429) {

            return new EmailDeliveryException(
                    "Microsoft Graph throttled sendMail",
                    true,
                    retryAfter(
                            exception,
                            Duration.ofSeconds(60)
                    ),
                    exception
            );
        }

        if (status >= 500) {

            return new EmailDeliveryException(
                    "Microsoft Graph temporarily failed with HTTP "
                            + status,
                    true,
                    retryAfter(
                            exception,
                            Duration.ofSeconds(30)
                    ),
                    exception
            );
        }

        /*
         * 400 = bad request/template/config
         * 401 = failed even after token refresh
         * 403 = permission problem
         * 404 = sender mailbox/config problem
         *
         * Retrying forever will not repair these.
         */
        return new EmailDeliveryException(
                "Microsoft Graph sendMail failed with HTTP "
                        + status,
                false,
                Duration.ZERO,
                exception
        );
    }

    private Duration retryAfter(
            RestClientResponseException exception,
            Duration fallback
    ) {

        if (exception.getResponseHeaders() == null) {
            return fallback;
        }

        String value =
                exception
                        .getResponseHeaders()
                        .getFirst(
                                HttpHeaders.RETRY_AFTER
                        );

        if (value == null
                || value.isBlank()) {

            return fallback;
        }

        try {

            long seconds =
                    Long.parseLong(
                            value.trim()
                    );

            return Duration.ofSeconds(
                    Math.max(
                            seconds,
                            1
                    )
            );

        } catch (NumberFormatException ignored) {

            return fallback;
        }
    }

    private static String requireText(
            String value,
            String field
    ) {

        if (value == null
                || value.isBlank()) {

            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }

        return value.trim();
    }
}