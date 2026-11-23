package com.fintech.notifications;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(
        proxyBeanMethods = false
)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "neos.notifications.dispatcher",
        name = "enabled",
        havingValue = "true"
)
public class NotificationSchedulingConfiguration {

    @Bean(
            name = "notificationTaskScheduler"
    )
    ThreadPoolTaskScheduler notificationTaskScheduler() {

        ThreadPoolTaskScheduler scheduler =
                new ThreadPoolTaskScheduler();

        /*
         * One thread is sufficient because:
         *
         * - the worker processes bounded batches;
         * - PostgreSQL claiming already uses SKIP LOCKED;
         * - we do not want overlapping local dispatcher executions.
         *
         * Multiple application instances can still safely run
         * their own scheduler later because SKIP LOCKED prevents
         * them from claiming the same rows concurrently.
         */
        scheduler.setPoolSize(
                1
        );

        scheduler.setThreadNamePrefix(
                "notification-dispatcher-"
        );

        scheduler.setWaitForTasksToCompleteOnShutdown(
                true
        );

        scheduler.setAwaitTerminationSeconds(
                10
        );

        return scheduler;
    }
}