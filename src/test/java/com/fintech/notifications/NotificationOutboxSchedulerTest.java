package com.fintech.notifications;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class NotificationOutboxSchedulerTest {

    @Test
    void dispatchRunsDeliveryWorkerWithConfiguredBatchSize() {

        NotificationDeliveryWorker worker =
                mock(
                        NotificationDeliveryWorker.class
                );

        when(
                worker.runOnce(
                        25
                )
        ).thenReturn(
                3
        );

        NotificationOutboxScheduler scheduler =
                new NotificationOutboxScheduler(
                        worker,
                        25
                );

        scheduler.dispatch();

        verify(
                worker,
                times(1)
        ).runOnce(
                25
        );
    }

    @Test
    void unexpectedWorkerFailureDoesNotEscapeScheduler() {

        NotificationDeliveryWorker worker =
                mock(
                        NotificationDeliveryWorker.class
                );

        when(
                worker.runOnce(
                        25
                )
        ).thenThrow(
                new RuntimeException(
                        "Database temporarily unavailable"
                )
        );

        NotificationOutboxScheduler scheduler =
                new NotificationOutboxScheduler(
                        worker,
                        25
                );

        assertThatCode(
                scheduler::dispatch
        ).doesNotThrowAnyException();

        verify(
                worker,
                times(1)
        ).runOnce(
                25
        );
    }

    @Test
    void rejectsInvalidBatchSize() {

        NotificationDeliveryWorker worker =
                mock(
                        NotificationDeliveryWorker.class
                );

        assertThatCode(
                () ->
                        new NotificationOutboxScheduler(
                                worker,
                                0
                        )
        ).isInstanceOf(
                IllegalArgumentException.class
        );
    }
}