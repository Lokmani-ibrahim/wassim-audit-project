package com.fintech.identity;

import java.time.Instant;
import java.util.UUID;

/** Fields selected by the identity module for staff directory reads. */
public record CustomerDirectoryIdentityRow(
        UUID userId, String email, String accountStatus, Instant createdAt
) {}
