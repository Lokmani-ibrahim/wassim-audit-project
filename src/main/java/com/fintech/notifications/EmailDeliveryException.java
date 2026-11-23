package com.fintech.notifications;

import java.time.Duration;

public class EmailDeliveryException
        extends RuntimeException {

    private final boolean retryable;
    private final Duration retryAfter;

    public EmailDeliveryException(
            String message,
            boolean retryable,
            Duration retryAfter
    ) {

        super(
                message
        );

        this.retryable =
                retryable;

        this.retryAfter =
                retryAfter;
    }

    public EmailDeliveryException(
            String message,
            boolean retryable,
            Duration retryAfter,
            Throwable cause
    ) {

        super(
                message,
                cause
        );

        this.retryable =
                retryable;

        this.retryAfter =
                retryAfter;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}