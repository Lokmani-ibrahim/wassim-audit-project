package com.fintech.kyc;

import com.fintech.kyc.repository.KycStatusRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only view of a customer's current KYC status
 * for other modules.
 *
 * The status is exposed as its enum name
 * (for example RESUBMISSION_REQUIRED) because the
 * KycStatus enum is internal to the kyc module.
 */
@Service
public class KycStatusLookup {

    private final KycStatusRepository kycRepository;

    public KycStatusLookup(
            KycStatusRepository kycRepository
    ) {
        this.kycRepository =
                Objects.requireNonNull(
                        kycRepository,
                        "kycRepository must not be null"
                );
    }

    @Transactional(readOnly = true)
    public Optional<String> findStatus(UUID userId) {

        Objects.requireNonNull(userId, "userId");

        return kycRepository
                .findByUserId(userId)
                .map(kyc -> kyc.getStatus().name());
    }

    /** One bounded KYC-owned read for a bounded identity page. */
    @Transactional(readOnly = true)
    public Map<UUID, String> findStatuses(List<UUID> userIds) {
        if (userIds.isEmpty()) return Map.of();
        if (userIds.size() > 1000) throw new IllegalArgumentException("Too many user IDs");
        return kycRepository.findDirectoryStatuses(userIds).stream()
                .collect(Collectors.toMap(
                        KycStatusRepository.DirectoryStatus::getUserId,
                        row -> row.getStatus().name()));
    }
}
