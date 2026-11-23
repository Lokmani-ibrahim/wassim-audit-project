
package com.fintech.kyc.adapter.sumsub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SumsubProductionConfigurationValidatorTest {

    private SumsubProperties validProperties() {
        return new SumsubProperties(
                "https://api.sumsub.com",
                "live-app-token",
                "live-secret-key",
                "id-and-liveness",
                600
        );
    }

    private void validate(
            SumsubProperties properties,
            boolean webhookEnabled,
            String webhookSecret,
            boolean sandboxMode
    ) {
        new SumsubProductionConfigurationValidator(
                properties,
                webhookEnabled,
                webhookSecret,
                sandboxMode
        ).afterSingletonsInstantiated();
    }

    @Test
    void validProductionConfigurationIsAccepted() {

        assertDoesNotThrow(() -> validate(
                validProperties(),
                true,
                "live-webhook-secret",
                false
        ));
    }

    @Test
    void missingAppTokenIsRejected() {

        SumsubProperties properties =
                new SumsubProperties(
                        "https://api.sumsub.com",
                        "",
                        "live-secret-key",
                        "id-and-liveness",
                        600
                );

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> validate(
                                properties,
                                true,
                                "live-webhook-secret",
                                false
                        )
                );

        assertTrue(
                exception.getMessage()
                        .contains("SUMSUB_APP_TOKEN")
        );
    }

    @Test
    void missingSecretKeyIsRejected() {

        SumsubProperties properties =
                new SumsubProperties(
                        "https://api.sumsub.com",
                        "live-app-token",
                        "",
                        "id-and-liveness",
                        600
                );

        assertThrows(
                IllegalStateException.class,
                () -> validate(
                        properties,
                        true,
                        "live-webhook-secret",
                        false
                )
        );
    }

    @Test
    void disabledWebhookIsRejected() {

        assertThrows(
                IllegalStateException.class,
                () -> validate(
                        validProperties(),
                        false,
                        "live-webhook-secret",
                        false
                )
        );
    }

    @Test
    void missingWebhookSecretIsRejected() {

        assertThrows(
                IllegalStateException.class,
                () -> validate(
                        validProperties(),
                        true,
                        "",
                        false
                )
        );
    }

    @Test
    void sandboxModeIsRejected() {

        assertThrows(
                IllegalStateException.class,
                () -> validate(
                        validProperties(),
                        true,
                        "live-webhook-secret",
                        true
                )
        );
    }

    @Test
    void insecureHttpUrlIsRejected() {

        SumsubProperties properties =
                new SumsubProperties(
                        "http://api.sumsub.com",
                        "live-app-token",
                        "live-secret-key",
                        "id-and-liveness",
                        600
                );

        assertThrows(
                IllegalStateException.class,
                () -> validate(
                        properties,
                        true,
                        "live-webhook-secret",
                        false
                )
        );
    }

    @Test
    void unrelatedHttpsHostIsRejected() {

        SumsubProperties properties =
                new SumsubProperties(
                        "https://example.com",
                        "live-app-token",
                        "live-secret-key",
                        "id-and-liveness",
                        600
                );

        assertThrows(
                IllegalStateException.class,
                () -> validate(
                        properties,
                        true,
                        "live-webhook-secret",
                        false
                )
        );
    }

    @Test
    void invalidSdkTokenTtlIsRejected() {

        SumsubProperties properties =
                new SumsubProperties(
                        "https://api.sumsub.com",
                        "live-app-token",
                        "live-secret-key",
                        "id-and-liveness",
                        0
                );

        assertThrows(
                IllegalStateException.class,
                () -> validate(
                        properties,
                        true,
                        "live-webhook-secret",
                        false
                )
        );
    }

    @Test
    void placeholderCredentialsAreRejected() {

        SumsubProperties properties =
                new SumsubProperties(
                        "https://api.sumsub.com",
                        "test-app-token",
                        "test-secret",
                        "id-and-liveness",
                        600
                );

        assertThrows(
                IllegalStateException.class,
                () -> validate(
                        properties,
                        true,
                        "live-webhook-secret",
                        false
                )
        );
    }
}
