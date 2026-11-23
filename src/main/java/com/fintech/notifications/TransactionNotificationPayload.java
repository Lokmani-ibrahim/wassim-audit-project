package com.fintech.notifications;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TransactionNotificationPayload(

        UUID userId,
        long transactionId,
        TransactionNotificationType type,
        TransactionNotificationDirection direction,
        String currency,
        String asset,
        long amountMinor,
        String reference,
        Instant occurredAt

) {

    public TransactionNotificationPayload {

        Objects.requireNonNull(
                userId,
                "userId must not be null"
        );

        Objects.requireNonNull(
                type,
                "type must not be null"
        );

        Objects.requireNonNull(
                direction,
                "direction must not be null"
        );

        Objects.requireNonNull(
                currency,
                "currency must not be null"
        );

        Objects.requireNonNull(
                asset,
                "asset must not be null"
        );

        Objects.requireNonNull(
                reference,
                "reference must not be null"
        );

        Objects.requireNonNull(
                occurredAt,
                "occurredAt must not be null"
        );

        if (transactionId < 0) {
            throw new IllegalArgumentException(
                    "transactionId must be nonnegative"
            );

        }

        if (currency.isBlank()) {
            throw new IllegalArgumentException(
                    "currency must not be blank"
            );
        }

        if (asset.isBlank()) {
            throw new IllegalArgumentException(
                    "asset must not be blank"
            );
        }

        if (amountMinor <= 0) {
            throw new IllegalArgumentException(
                    "amountMinor must be positive"
            );
        }

        if (reference.isBlank()) {
            throw new IllegalArgumentException(
                    "reference must not be blank"
            );
        }

        currency =
                currency.trim();

        asset =
                asset.trim();

        reference =
                reference.trim();
    }
}