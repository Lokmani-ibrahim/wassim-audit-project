package com.fintech.notifications;

import com.fintech.notifications.entity.NotificationOutboxEntity;
import com.fintech.notifications.repository.NotificationOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;
import java.util.UUID;
@Service
public class NotificationOutboxService {

    private static final String TRANSACTION_COMPLETED =
            "TRANSACTION_COMPLETED";

    private final NotificationOutboxRepository repository;
    private final JsonMapper jsonMapper;

    public NotificationOutboxService(
            NotificationOutboxRepository repository,
            JsonMapper jsonMapper
    ) {

        this.repository =
                Objects.requireNonNull(
                        repository,
                        "repository must not be null"
                );

        this.jsonMapper =
                Objects.requireNonNull(
                        jsonMapper,
                        "jsonMapper must not be null"
                );
    }

    @Transactional
    public NotificationOutboxEntity enqueueTransactionEmail(
            String recipient,
            TransactionNotificationPayload payload
    ) {

        Objects.requireNonNull(
                payload,
                "payload must not be null"
        );

        String requiredRecipient =
                requireText(
                        recipient,
                        "recipient"
                );

        String notificationKey =
                TRANSACTION_COMPLETED
                        + ":"
                        + payload.transactionId()
                        + ":"
                        + payload.userId()
                        + ":EMAIL";

        return repository
                .findByNotificationKey(
                        notificationKey
                )
                .orElseGet(
                        () -> create(
                                notificationKey,
                                requiredRecipient,
                                payload
                        )
                );
    }

    private NotificationOutboxEntity create(
            String notificationKey,
            String recipient,
            TransactionNotificationPayload payload
    ) {

        String payloadJson;

        try {

            payloadJson =
                    jsonMapper.writeValueAsString(
                            payload
                    );

        } catch (JacksonException exception) {

            throw new IllegalStateException(
                    "Unable to serialize transaction notification",
                    exception
            );
        }

        NotificationOutboxEntity entity =
                new NotificationOutboxEntity(
                        notificationKey,
                        TRANSACTION_COMPLETED,
                        NotificationChannel.EMAIL,
                        payload.userId(),
                        recipient,
                        payloadJson
                );

        return repository.saveAndFlush(
                entity
        );
    }

    private static String requireText(
            String value,
            String field
    ) {

        Objects.requireNonNull(
                value,
                field + " must not be null"
        );

        String trimmed =
                value.trim();

        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }

        return trimmed;
    }

    /**
     * Public notifications-module API.
     *
     * Allows other modules to check whether an email
     * notification has already been durably recorded,
     * without depending on the internal repository.
     *
     * Includes notifications in every outbox status.
     */
    public boolean transactionEmailExists(
            UUID userId,
            long transactionId
    ) {
        Objects.requireNonNull(userId, "userId");

        if (transactionId < 0) {
            throw new IllegalArgumentException(
                    "transactionId must be nonnegative"
            );
        }

        String notificationKey =
                "TRANSACTION_COMPLETED:"
                        + transactionId
                        + ":"
                        + userId
                        + ":EMAIL";

        return repository.countByNotificationKey(
                notificationKey
        ) > 0;
    }

}