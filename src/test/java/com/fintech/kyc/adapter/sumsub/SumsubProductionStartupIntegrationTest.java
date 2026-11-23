
package com.fintech.kyc.adapter.sumsub;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SumsubProductionStartupIntegrationTest {

    private static SumsubProperties validProperties() {
        return new SumsubProperties(
                "https://api.sumsub.com",
                "live-app-token",
                "live-secret-key",
                "id-and-liveness",
                600
        );
    }

    private static AnnotationConfigApplicationContext createContext(
            String profile,
            SumsubProperties properties,
            boolean webhookEnabled,
            String webhookSecret,
            boolean sandboxMode
    ) {

        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext();

        context.getEnvironment().setActiveProfiles(profile);

        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "sumsub-production-test",
                                Map.of(
                                        "neos.kyc.sumsub.webhook-enabled",
                                        Boolean.toString(webhookEnabled),

                                        "neos.kyc.sumsub.webhook-secret",
                                        webhookSecret,

                                        "neos.kyc.sumsub.sandbox-mode",
                                        Boolean.toString(sandboxMode)
                                )
                        )
                );

        context.registerBean(
                SumsubProperties.class,
                () -> properties
        );

        context.register(
                SumsubProductionConfigurationValidator.class
        );

        return context;
    }

    private static boolean containsMessage(
            Throwable throwable,
            String expected
    ) {
        Throwable current = throwable;

        while (current != null) {

            String message = current.getMessage();

            if (message != null && message.contains(expected)) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    private static void assertProductionStartupRejected(
            SumsubProperties properties,
            boolean webhookEnabled,
            String webhookSecret,
            boolean sandboxMode,
            String expectedMessage
    ) {

        try (AnnotationConfigApplicationContext context =
                     createContext(
                             "prod",
                             properties,
                             webhookEnabled,
                             webhookSecret,
                             sandboxMode
                     )) {

            RuntimeException exception = assertThrows(
                    RuntimeException.class,
                    context::refresh
            );

            assertTrue(
                    containsMessage(exception, expectedMessage),
                    "Expected startup rejection containing: "
                            + expectedMessage
                            + ". Actual exception: "
                            + exception
            );
        }
    }

    @Test
    void validProductionConfigurationStarts() {

        try (AnnotationConfigApplicationContext context =
                     createContext(
                             "prod",
                             validProperties(),
                             true,
                             "live-webhook-secret",
                             false
                     )) {

            assertDoesNotThrow(context::refresh);

            assertNotNull(
                    context.getBean(
                            SumsubProductionConfigurationValidator.class
                    )
            );
        }
    }

    @Test
    void missingAppTokenPreventsProductionStartup() {

        SumsubProperties invalid =
                new SumsubProperties(
                        "https://api.sumsub.com",
                        "",
                        "live-secret-key",
                        "id-and-liveness",
                        600
                );

        assertProductionStartupRejected(
                invalid,
                true,
                "live-webhook-secret",
                false,
                "SUMSUB_APP_TOKEN"
        );
    }

    @Test
    void disabledWebhookPreventsProductionStartup() {

        assertProductionStartupRejected(
                validProperties(),
                false,
                "live-webhook-secret",
                false,
                "SUMSUB_WEBHOOK_ENABLED"
        );
    }

    @Test
    void missingWebhookSecretPreventsProductionStartup() {

        assertProductionStartupRejected(
                validProperties(),
                true,
                "",
                false,
                "SUMSUB_WEBHOOK_SECRET"
        );
    }

    @Test
    void sandboxModePreventsProductionStartup() {

        assertProductionStartupRejected(
                validProperties(),
                true,
                "live-webhook-secret",
                true,
                "SUMSUB_SANDBOX_MODE"
        );
    }

    @Test
    void developmentDoesNotRegisterProductionValidator() {

        try (AnnotationConfigApplicationContext context =
                     createContext(
                             "dev",
                             validProperties(),
                             false,
                             "",
                             true
                     )) {

            assertDoesNotThrow(context::refresh);

            assertTrue(
                    context.getBeansOfType(
                            SumsubProductionConfigurationValidator.class
                    ).isEmpty()
            );
        }
    }
}
