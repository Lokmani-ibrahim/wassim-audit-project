package com.fintech.kyc;

import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.repository.KycStatusRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class KycInitializationService {

    private final KycStatusRepository kycStatusRepository;

    public KycInitializationService(
            KycStatusRepository kycStatusRepository
    ) {
        this.kycStatusRepository = kycStatusRepository;
    }

    @Transactional
    public void ensureInitialized(UUID userId) {

        Objects.requireNonNull(
                userId,
                "userId must not be null"
        );

        if (kycStatusRepository.existsByUserId(userId)) {
            return;
        }

        KycStatusEntity kycStatus =
                new KycStatusEntity(userId);

        kycStatusRepository.saveAndFlush(kycStatus);
    }
}