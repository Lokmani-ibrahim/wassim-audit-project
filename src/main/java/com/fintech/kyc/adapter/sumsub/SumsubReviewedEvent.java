package com.fintech.kyc.adapter.sumsub;

import java.time.Instant;
import java.util.UUID;

public record SumsubReviewedEvent(
        UUID userId,
        String applicantId,
        String levelName,
        Decision decision,
        Instant eventTime
) {
    public enum Decision {
        APPROVED,
        FINAL_REJECTION,
        RESUBMISSION_REQUIRED
    }
}