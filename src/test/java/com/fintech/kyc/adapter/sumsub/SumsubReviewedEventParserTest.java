package com.fintech.kyc.adapter.sumsub;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SumsubReviewedEventParserTest {

    private final SumsubReviewedEventParser parser =
            new SumsubReviewedEventParser(
                    JsonMapper.builder().build(),
                    "id-and-liveness",
                    true
            );

    private String payload(
            String answer,
            String rejectType
    ) {
        String reviewResult =
                "\"reviewAnswer\":\"" + answer + "\"";

        if (rejectType != null) {
            reviewResult +=
                    ",\"reviewRejectType\":\""
                            + rejectType + "\"";
        }

        return """
                {
                  "type": "applicantReviewed",
                  "applicantType": "individual",
                  "applicantId": "sandbox-applicant-123",
                  "externalUserId":
                    "00000000-0000-0000-0000-000000000001",
                  "levelName": "id-and-liveness",
                  "sandboxMode": true,
                  "reviewStatus": "completed",
                  "createdAtMs": "2026-10-26 20:00:00.000",
                  "reviewResult": {%s}
                }
                """.formatted(reviewResult);
    }

    private SumsubReviewedEvent parse(String json) {
        return parser.parse(
                json.getBytes(StandardCharsets.UTF_8)
        );
    }

    @Test
    void parsesApproval() {

        var event = parse(payload("GREEN", null));

        assertEquals(
                SumsubReviewedEvent.Decision.APPROVED,
                event.decision()
        );

        assertEquals(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000001"
                ),
                event.userId()
        );

        assertEquals(
                "sandbox-applicant-123",
                event.applicantId()
        );
    }

    @Test
    void parsesFinalRejection() {

        var event = parse(payload("RED", "FINAL"));

        assertEquals(
                SumsubReviewedEvent.Decision.FINAL_REJECTION,
                event.decision()
        );
    }

    @Test
    void parsesResubmissionRequest() {

        var event = parse(payload("RED", "RETRY"));

        assertEquals(
                SumsubReviewedEvent.Decision.RESUBMISSION_REQUIRED,
                event.decision()
        );
    }

    @Test
    void rejectsWrongVerificationLevel() {

        String json = payload("GREEN", null)
                .replace(
                        "id-and-liveness",
                        "different-level"
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> parse(json)
        );
    }

    @Test
    void rejectsWrongEnvironment() {

        String json = payload("GREEN", null)
                .replace(
                        "\"sandboxMode\": true",
                        "\"sandboxMode\": false"
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> parse(json)
        );
    }

    @Test
    void rejectsManualTestWebhook() {

        String json = payload("GREEN", null)
                .replace(
                        "\"sandboxMode\": true,",
                        "\"sandboxMode\": true,"
                                + "\"testMode\": true,"
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> parse(json)
        );
    }

    @Test
    void rejectsUnsupportedEventType() {

        String json = payload("GREEN", null)
                .replace(
                        "applicantReviewed",
                        "applicantCreated"
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> parse(json)
        );
    }

    @Test
    void rejectsRedWithoutRejectionType() {

        assertThrows(
                IllegalArgumentException.class,
                () -> parse(payload("RED", null))
        );
    }

    @Test
    void rejectsInvalidUserId() {

        String json = payload("GREEN", null)
                .replace(
                        "00000000-0000-0000-0000-000000000001",
                        "invalid-user-id"
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> parse(json)
        );
    }
}