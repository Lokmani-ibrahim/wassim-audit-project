package com.fintech.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@ConditionalOnProperty(
        prefix = "neos.notifications.dispatcher",
        name = "enabled",
        havingValue = "true"
)
public class NotificationOutboxScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(
                    NotificationOutboxScheduler.class
            );

    private final NotificationDeliveryWorker worker;

    private final int batchSize;

    public NotificationOutboxScheduler(
            NotificationDeliveryWorker worker,

            @Value(
                    "${neos.notifications.dispatcher.batch-size:25}"
            )
            int batchSize
    ) {

        this.worker =
                Objects.requireNonNull(
                        worker,
                        "worker must not be null"
                );

        if (batchSize < 1
                || batchSize > 100) {

            throw new IllegalArgumentException(
                    "notification dispatcher batchSize must be between 1 and 100"
            );
        }

        this.batchSize =
                batchSize;
    }

    /*
     * Fixed delay is deliberate.
     *
     * If one batch takes 2 seconds and fixed-delay is 5 seconds,
     * the next run starts approximately 5 seconds AFTER that
     * batch finishes.
     *
     * This avoids overlapping execution inside one application
     * instance.
     */
    @Scheduled(
            fixedDelayString =
                    "${neos.notifications.dispatcher.fixed-delay-ms:5000}",

            initialDelayString =
                    "${neos.notifications.dispatcher.initial-delay-ms:5000}",

            scheduler =
                    "notificationTaskScheduler"
    )
    public void dispatch() {

        try {

            int processed =
                    worker.runOnce(
                            batchSize
                    );

            if (processed > 0) {

                log.info(
                        "Notification dispatcher processed {} item(s)",
                        processed
                );
            }

        } catch (RuntimeException exception) {

            /*
             * Never let one unexpected scheduler failure stop
             * subsequent polling cycles.
             *
             * Individual notification delivery failures are
             * already handled inside NotificationDeliveryWorker.
             *
             * This catch protects the scheduler itself from
             * unexpected infrastructure/programming failures.
             */
            log.error(
                    "Unexpected notification dispatcher failure",
                    exception
            );
        }
    }
}