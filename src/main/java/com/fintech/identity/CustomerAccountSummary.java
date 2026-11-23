package com.fintech.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only snapshot of a customer account
 * for other modules.
 *
 * accountStatus is the AccountStatus enum name
 * (PENDING_KYC, ACTIVE, SUSPENDED, CLOSED).
 */
public record CustomerAccountSummary(
        UUID userId,
        String email,
        String phoneNumber,
        String accountStatus,
        Instant createdAt
) {
}
