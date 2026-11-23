
package com.fintech.ledger;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Verifies automatic wallet provisioning recovery scheduling.
 *
 * Uses an isolated Spring context:
 * - No PostgreSQL connection.
 * - No real Formance calls.
 * - No actual event-publication replay.
 * - No replacement of Spring's applicationEventMulticaster.
 *
 * Spring must invoke the recovery scheduler automatically.
 */
@SpringJUnitConfig(classes = {
        WalletProvisioningRecoveryScheduler.class,
        WalletProvisioningRecoverySchedulingIntegrationTest
                .TestBeans.class
})
@TestPropertySource(properties = {
        "neos.ledger.wallet-provisioning.recovery.enabled=true",
        "neos.ledger.wallet-provisioning.recovery.initial-delay-ms=100",
        "neos.ledger.wallet-provisioning.recovery.fixed-delay-ms=60000",
        "neos.ledger.wallet-provisioning.recovery.batch-size=50",
        "neos.ledger.wallet-provisioning.recovery.max-in-flight=2",
        "neos.ledger.wallet-provisioning.recovery.min-age=PT2M"
})
class WalletProvisioningRecoverySchedulingIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private FailedEventPublications failedPublications;

    @Configuration(proxyBeanMethods = false)
    static class TestBeans {

        /**
         * Register Spring Boot's conversion service.
         *
         * Required to convert:
         *
         * "PT2M" -> java.time.Duration
         *
         * A lightweight SpringJUnitConfig context does not
         * automatically configure Boot's conversion service.
         */
        @Bean(name = "conversionService")
        public static ConversionService conversionService() {
            return ApplicationConversionService
                    .getSharedInstance();
        }

        /**
         * Isolated mock publication registry.
         *
         * It does not replace the applicationEventMulticaster
         * because the full Modulith application is not loaded.
         */
        @Bean
        FailedEventPublications failedPublications() {
            return mock(FailedEventPublications.class);
        }
    }

    @Test
    void enabledRecoveryWorkerIsInvokedAutomatically() {

        /*
         * Verify the scheduler was registered.
         */
        assertNotNull(
                applicationContext.getBean(
                        WalletProvisioningRecoveryScheduler.class
                )
        );

        /*
         * Do not invoke the scheduler manually.
         *
         * Spring's @Scheduled infrastructure must call
         * the method automatically.
         */
        verify(
                failedPublications,
                timeout(10_000).atLeastOnce()
        ).resubmit(
                any(ResubmissionOptions.class)
        );
    }
}
