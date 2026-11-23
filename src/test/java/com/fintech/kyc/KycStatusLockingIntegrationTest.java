package com.fintech.kyc;

import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.repository.KycStatusRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class KycStatusLockingIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KycStatusRepository kycRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void locksKycRowForProviderDecision() {

        UUID unique = UUID.randomUUID();

        UserEntity user = userRepository.saveAndFlush(
                new UserEntity(
                        "sumsub-lock-" + unique,
                        "sumsub-lock-" + unique + "@example.com",
                        "+21620123456"
                )
        );

        KycStatusEntity kyc =
                new KycStatusEntity(user.getId());

        kyc.startVerification(
                "SUMSUB",
                "sandbox-lock-" + unique
        );

        kycRepository.saveAndFlush(kyc);

        entityManager.clear();

        KycStatusEntity locked =
                kycRepository
                        .findByUserIdForUpdate(user.getId())
                        .orElseThrow();

        assertEquals(
                user.getId(),
                locked.getUserId()
        );

        assertEquals(
                LockModeType.PESSIMISTIC_WRITE,
                entityManager.getLockMode(locked)
        );
    }
}