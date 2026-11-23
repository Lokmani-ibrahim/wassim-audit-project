package com.fintech.kyc.adapter.sumsub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

public final class SumsubReviewedEventParser {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern(
                    "yyyy-MM-dd HH:mm:ss.SSS"
            );

    private final JsonMapper mapper;
    private final String expectedLevel;
    private final boolean expectedSandbox;

    public SumsubReviewedEventParser(
            JsonMapper mapper,
            String expectedLevel,
            boolean expectedSandbox
    ) {
        this.mapper = Objects.requireNonNull(mapper);
        this.expectedLevel =
                Objects.requireNonNull(expectedLevel);
        this.expectedSandbox = expectedSandbox;
    }

    public SumsubReviewedEvent parse(byte[] rawBody) {

        if (rawBody == null || rawBody.length == 0) {
            throw new IllegalArgumentException(
                    "Empty Sumsub webhook"
            );
        }

        JsonNode root = mapper.readTree(
                new String(rawBody, StandardCharsets.UTF_8)
        );

        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException(
                    "Invalid webhook JSON"
            );
        }

        if (!"applicantReviewed".equals(
                requiredText(root, "type"))) {
            throw new IllegalArgumentException(
                    "Unsupported webhook type"
            );
        }

        if (!"completed".equals(
                requiredText(root, "reviewStatus"))) {
            throw new IllegalArgumentException(
                    "Review is not completed"
            );
        }

        if (!"individual".equals(
                requiredText(root, "applicantType"))) {
            throw new IllegalArgumentException(
                    "Unsupported applicant type"
            );
        }

        JsonNode sandbox = root.path("sandboxMode");

        if (!sandbox.isBoolean()
                || sandbox.booleanValue() != expectedSandbox) {
            throw new IllegalArgumentException(
                    "Sumsub environment mismatch"
            );
        }

        JsonNode testMode = root.path("testMode");

        if (!testMode.isMissingNode()
                && (!testMode.isBoolean()
                || testMode.booleanValue())) {
            throw new IllegalArgumentException(
                    "Manual test webhook cannot modify KYC"
            );
        }

        String level = requiredText(root, "levelName");

        if (!expectedLevel.equals(level)) {
            throw new IllegalArgumentException(
                    "Unexpected verification level"
            );
        }

        UUID userId = UUID.fromString(
                requiredText(root, "externalUserId")
        );

        String applicantId =
                requiredText(root, "applicantId");

        JsonNode review = root.path("reviewResult");

        String answer =
                requiredText(review, "reviewAnswer");

        SumsubReviewedEvent.Decision decision;

        switch (answer) {
            case "GREEN" -> decision =
                    SumsubReviewedEvent.Decision.APPROVED;

            case "RED" -> {
                String rejectType =
                        requiredText(
                                review,
                                "reviewRejectType"
                        );

                decision = switch (rejectType) {
                    case "FINAL" ->
                            SumsubReviewedEvent.Decision
                                    .FINAL_REJECTION;

                    case "RETRY" ->
                            SumsubReviewedEvent.Decision
                                    .RESUBMISSION_REQUIRED;

                    default -> throw new IllegalArgumentException(
                            "Unsupported rejection type"
                    );
                };
            }

            default -> throw new IllegalArgumentException(
                    "Unsupported review answer"
            );
        }

        Instant eventTime = LocalDateTime.parse(
                requiredText(root, "createdAtMs"),
                TIMESTAMP_FORMAT
        ).toInstant(ZoneOffset.UTC);

        return new SumsubReviewedEvent(
                userId,
                applicantId,
                level,
                decision,
                eventTime
        );
    }

    private static String requiredText(
            JsonNode node,
            String field
    ) {
        JsonNode value = node.path(field);

        if (!value.isTextual()
                || value.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "Missing or invalid field: " + field
            );
        }

        return value.asText();
    }
}