package com.fintech.kyc.repository;

import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.entity.KycStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycStatusRepository
        extends JpaRepository<KycStatusEntity, UUID> {

    interface DirectoryStatus {
        UUID getUserId();
        KycStatus getStatus();
    }

    @Query("SELECT k.userId AS userId, k.status AS status FROM KycStatusEntity k WHERE k.userId IN :userIds")
    List<DirectoryStatus> findDirectoryStatuses(@Param("userIds") List<UUID> userIds);

    Optional<KycStatusEntity> findByUserId(UUID userId);

    Optional<KycStatusEntity> findByProviderAndProviderApplicantId(
            String provider,
            String providerApplicantId
    );

    List<KycStatusEntity> findAllByStatus(KycStatus status);

    boolean existsByUserId(UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
       SELECT k
       FROM KycStatusEntity k
       WHERE k.userId = :userId
       """)
    Optional<KycStatusEntity> findByUserIdForUpdate(
            @Param("userId") UUID userId
    );
}
