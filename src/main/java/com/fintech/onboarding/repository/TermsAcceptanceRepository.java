package com.fintech.onboarding.repository;

import com.fintech.onboarding.entity.TermsAcceptanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TermsAcceptanceRepository
        extends JpaRepository<TermsAcceptanceEntity, UUID> {

    boolean existsByUserIdAndTermsVersion(
            UUID userId,
            String termsVersion
    );

    Optional<TermsAcceptanceEntity> findByUserIdAndTermsVersion(
            UUID userId,
            String termsVersion
    );

    List<TermsAcceptanceEntity> findAllByUserIdOrderByAcceptedAtDesc(
            UUID userId
    );
}