
package com.fintech.kyc.adapter.sumsub;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

@Component
@Profile("prod")
public final class SumsubProductionConfigurationValidator
        implements SmartInitializingSingleton {

    private final SumsubProperties properties;
    private final boolean webhookEnabled;
    private final String webhookSecret;
    private final boolean sandboxMode;

    public SumsubProductionConfigurationValidator(
            SumsubProperties properties,

            @Value("${neos.kyc.sumsub.webhook-enabled:false}")
            boolean webhookEnabled,

            @Value("${neos.kyc.sumsub.webhook-secret:}")
            String webhookSecret,

            @Value("${neos.kyc.sumsub.sandbox-mode:true}")
            boolean sandboxMode
    ) {
        this.properties = Objects.requireNonNull(properties);
        this.webhookEnabled = webhookEnabled;
        this.webhookSecret = webhookSecret;
        this.sandboxMode = sandboxMode;
    }

    @Override
    public void afterSingletonsInstantiated() {

        /*
         * This validator is loaded only with
         * the Spring "prod" profile.
         *
         * Production startup must fail when
         * Sumsub is not fully configured.
         */

        requireCredential(
                properties.appToken(),
                "SUMSUB_APP_TOKEN"
        );

        requireCredential(
                properties.secretKey(),
                "SUMSUB_SECRET_KEY"
        );

        requireCredential(
                properties.levelName(),
                "SUMSUB_LEVEL_NAME"
        );

        validateBaseUrl(properties.baseUrl());

        if (properties.sdkTokenTtlSeconds() <= 0) {
            throw new IllegalStateException(
                    "Production KYC requires a positive " +
                            "Sumsub SDK token TTL"
            );
        }

        /*
         * Production must not accept sandbox
         * verification decisions.
         */
        if (sandboxMode) {
            throw new IllegalStateException(
                    "Production KYC must have " +
                            "SUMSUB_SANDBOX_MODE=false"
            );
        }

        /*
         * Without the webhook endpoint, Sumsub
         * approval decisions cannot reach NEOS.
         */
        if (!webhookEnabled) {
            throw new IllegalStateException(
                    "Production KYC requires " +
                            "SUMSUB_WEBHOOK_ENABLED=true"
            );
        }

        requireCredential(
                webhookSecret,
                "SUMSUB_WEBHOOK_SECRET"
        );
    }

    private static void requireCredential(
            String value,
            String setting
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Production KYC requires " + setting
            );
        }

        /*
         * Reject known development placeholders.
         * Never include actual secret values
         * in exception messages.
         */
        String normalized =
                value.trim().toLowerCase(Locale.ROOT);

        boolean placeholder = switch (normalized) {
            case "test",
                 "test-secret",
                 "test-app-token",
                 "test-webhook-secret",
                 "changeme",
                 "change-me",
                 "placeholder",
                 "your-token",
                 "your-secret" -> true;
            default -> false;
        };

        if (placeholder) {
            throw new IllegalStateException(
                    "Production KYC setting " +
                            setting +
                            " contains a placeholder value"
            );
        }
    }

    private static void validateBaseUrl(
            String baseUrl
    ) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "Production KYC requires SUMSUB_BASE_URL"
            );
        }

        final URI uri;

        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "SUMSUB_BASE_URL is not a valid URI",
                    exception
            );
        }

        String host = uri.getHost();

        /*
         * Require HTTPS and a Sumsub-controlled
         * hostname to avoid sending signed requests
         * and credentials to an arbitrary server.
         *
         * Regional API subdomains are supported.
         */
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || !host.toLowerCase(Locale.ROOT)
                .endsWith(".sumsub.com")) {

            throw new IllegalStateException(
                    "Production SUMSUB_BASE_URL must use " +
                            "HTTPS on a Sumsub API domain"
            );
        }

        if (uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {

            throw new IllegalStateException(
                    "Production SUMSUB_BASE_URL must not " +
                            "contain credentials, queries or fragments"
            );
        }

        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalStateException(
                    "Production SUMSUB_BASE_URL must use " +
                            "the standard HTTPS port"
            );
        }
    }
}
