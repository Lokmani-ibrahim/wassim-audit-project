package com.fintech.notifications;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationFeedCursorTest {

    @Test
    void roundTripsCreatedAtAndId() {

        NotificationFeedCursor cursor =
                new NotificationFeedCursor(
                        Instant.parse("2026-11-05T03:17:35.467123Z"),
                        UUID.fromString("c541db50-ffe1-42a0-ad90-0e7bfff56a47")
                );

        String encoded =
                cursor.encode();

        assertThat(encoded)
                .doesNotContain("=")
                .doesNotContain("+")
                .doesNotContain("/");

        assertThat(NotificationFeedCursor.decode(encoded))
                .isEqualTo(cursor);
    }

    @Test
    void carriesNoUserId() {

        UUID id =
                UUID.randomUUID();

        String raw =
                new String(
                        Base64.getUrlDecoder().decode(
                                new NotificationFeedCursor(
                                        Instant.parse("2026-11-05T00:00:00Z"),
                                        id
                                ).encode()
                        ),
                        StandardCharsets.UTF_8
                );

        assertThat(raw)
                .isEqualTo("v1|2026-11-05T00:00:00Z|" + id);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "not base64 !",
            "djE",
            // v2|2026-11-05T00:00:00Z|c541db50-ffe1-42a0-ad90-0e7bfff56a47
            "djJ8MjAyNi0xMS0wNVQwMDowMDowMFp8YzU0MWRiNTAtZmZlMS00MmEwLWFkOTAtMGU3YmZmZjU2YTQ3",
            // v1|yesterday|c541db50-ffe1-42a0-ad90-0e7bfff56a47
            "djF8eWVzdGVyZGF5fGM1NDFkYjUwLWZmZTEtNDJhMC1hZDkwLTBlN2JmZmY1NmE0Nw",
            // v1|2026-11-05T00:00:00Z|not-a-uuid
            "djF8MjAyNi0xMS0wNVQwMDowMDowMFp8bm90LWEtdXVpZA",
            // v1|2026-11-05T00:00:00Z
            "djF8MjAyNi0xMS0wNVQwMDowMDowMFo"
    })
    void rejectsValuesItDidNotIssue(String value) {

        assertThatThrownBy(() -> NotificationFeedCursor.decode(value))
                .isInstanceOf(InvalidNotificationFeedRequestException.class);
    }

    @Test
    void rejectsNullAndOversizedValues() {

        assertThatThrownBy(() -> NotificationFeedCursor.decode(null))
                .isInstanceOf(InvalidNotificationFeedRequestException.class);

        assertThatThrownBy(() -> NotificationFeedCursor.decode("a".repeat(257)))
                .isInstanceOf(InvalidNotificationFeedRequestException.class);
    }
}
