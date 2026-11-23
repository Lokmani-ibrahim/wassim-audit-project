package com.fintech.notifications;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Opaque keyset cursor for GET /api/notifications.
 *
 * Wire form: base64url (no padding) of
 *
 *     v1|{createdAt ISO-8601}|{outbox row id}
 *
 * It carries no user id. The feed query is always
 * restricted to the authenticated user, so a cursor that
 * was edited or copied from another user can only move
 * within the caller's own rows.
 */
public record NotificationFeedCursor(
        Instant createdAt,
        UUID id
) {

    private static final String VERSION =
            "v1";

    private static final int MAX_ENCODED_LENGTH =
            256;

    public NotificationFeedCursor {

        Objects.requireNonNull(
                createdAt,
                "createdAt must not be null"
        );

        Objects.requireNonNull(
                id,
                "id must not be null"
        );
    }

    public String encode() {

        String raw =
                VERSION
                        + "|"
                        + createdAt
                        + "|"
                        + id;

        return Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        raw.getBytes(
                                StandardCharsets.UTF_8
                        )
                );
    }

    /**
     * @throws InvalidNotificationFeedRequestException
     *         when the value is not a cursor issued by
     *         this endpoint.
     */
    public static NotificationFeedCursor decode(
            String encoded
    ) {

        if (encoded == null
                || encoded.isBlank()
                || encoded.length() > MAX_ENCODED_LENGTH) {

            throw invalid();
        }

        try {

            String raw =
                    new String(
                            Base64
                                    .getUrlDecoder()
                                    .decode(
                                            encoded.trim()
                                    ),
                            StandardCharsets.UTF_8
                    );

            String[] parts =
                    raw.split(
                            "\\|",
                            -1
                    );

            if (parts.length != 3
                    || !VERSION.equals(parts[0])) {

                throw invalid();
            }

            return new NotificationFeedCursor(
                    Instant.parse(
                            parts[1]
                    ),
                    UUID.fromString(
                            parts[2]
                    )
            );

        } catch (IllegalArgumentException
                 | DateTimeParseException exception) {

            throw invalid();
        }
    }

    private static InvalidNotificationFeedRequestException invalid() {

        return new InvalidNotificationFeedRequestException(
                "cursor is invalid"
        );
    }
}
