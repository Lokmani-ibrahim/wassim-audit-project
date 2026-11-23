package com.fintech.identity.repository;

import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.entity.AccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    interface DirectoryProjection {
        UUID getUserId();
        String getEmail();
        AccountStatus getAccountStatus();
        java.time.Instant getCreatedAt();
    }

    Optional<UserEntity> findByKeycloakSubject(String keycloakSubject);

    Optional<UserEntity> findByEmail(String email);

    List<UserEntity> findAllByEmailIgnoreCase(String email);

    boolean existsByKeycloakSubject(String keycloakSubject);

    boolean existsByEmail(String email);

    @Query("""
       SELECT u.id AS userId, u.email AS email,
           u.accountStatus AS accountStatus, u.createdAt AS createdAt
       FROM UserEntity u
       WHERE (:after IS NULL OR u.id > :after)
         AND (:accountStatus IS NULL OR u.accountStatus = :accountStatus)
         AND (:email IS NULL OR lower(u.email) = :email)
       ORDER BY u.id ASC
       """)
    List<DirectoryProjection> scanDirectory(
            @Param("after") UUID after,
            @Param("accountStatus") AccountStatus accountStatus,
            @Param("email") String email,
            Pageable page
    );

    @Query("""
       SELECT u.id AS userId, u.email AS email,
           u.accountStatus AS accountStatus, u.createdAt AS createdAt
       FROM UserEntity u WHERE u.id = :userId
       """)
    Optional<DirectoryProjection> findDirectoryRow(@Param("userId") UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
       SELECT u FROM UserEntity u
       WHERE u.id = :userId
       """)
    Optional<UserEntity> findByIdForUpdate(
            @Param("userId") UUID userId
    );
}
