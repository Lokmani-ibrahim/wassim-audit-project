
package com.fintech.ledger;

import com.fintech.kyc.KycApprovedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;

import java.time.Duration;
import java.util.Objects;

/**
 * Periodically retries FAILED KycApprovedEvent publications.
 *
 * Important:
 * - Never creates a new KYC approval event.
 * - Never updates customer or KYC status directly.
 * - Never calls Formance directly.
 * - Replays obsolete approval events so they can complete.
 * - The listener performs the authoritative eligibility check.
 *
 * Enable only on one designated backend instance until
 * multi-instance recovery coordination is validated.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "neos.ledger.wallet-provisioning.recovery",
        name = "enabled",
        havingValue = "true"
)
public class WalletProvisioningRecoveryScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(
                    WalletProvisioningRecoveryScheduler.class
            );

    private final FailedEventPublications failedPublications;
    private final int batchSize;
    private final int maxInFlight;
    private final Duration minAge;

    public WalletProvisioningRecoveryScheduler(
            FailedEventPublications failedPublications,

            @Value("${neos.ledger.wallet-provisioning.recovery.batch-size:50}")
            int batchSize,

            @Value("${neos.ledger.wallet-provisioning.recovery.max-in-flight:2}")
            int maxInFlight,

            @Value("${neos.ledger.wallet-provisioning.recovery.min-age:PT2M}")
            Duration minAge
    ) {
        this.failedPublications =
                Objects.requireNonNull(failedPublications);

        this.minAge = Objects.requireNonNull(minAge);

        if (batchSize < 1
                || maxInFlight < 1
                || minAge.isNegative()) {
            throw new IllegalArgumentException(
                    "Invalid wallet recovery configuration"
            );
        }

        this.batchSize = batchSize;
        this.maxInFlight = maxInFlight;
    }

    @Scheduled(
            fixedDelayString =
                    "${neos.ledger.wallet-provisioning.recovery.fixed-delay-ms:60000}",
            initialDelayString =
                    "${neos.ledger.wallet-provisioning.recovery.initial-delay-ms:60000}"
    )
    public void recoverFailedWalletProvisioning() {

        ResubmissionOptions options =
                ResubmissionOptions.defaults()
                        .withBatchSize(batchSize)
                        .withMaxInFlight(maxInFlight)
                        .withMinAge(minAge)
                        .withFilter(publication ->
                                publication.getEvent()
                                        instanceof KycApprovedEvent
                        );

        try {
            failedPublications.resubmit(options);
        } catch (RuntimeException exception) {
            log.error(
                    "Failed to initiate wallet provisioning recovery",
                    exception
            );
        }
    }
}
