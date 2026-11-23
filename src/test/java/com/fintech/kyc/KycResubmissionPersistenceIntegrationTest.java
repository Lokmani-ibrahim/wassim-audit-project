package com.fintech.kyc;

import com.fintech.identity.entity.AccountStatus;
import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.repository.KycStatusRepository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@Rollback
class KycResubmissionPersistenceIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KycStatusRepository kycStatusRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsResubmissionWithoutApprovingCustomer() {

        UUID unique = UUID.randomUUID();

        UserEntity user = userRepository.saveAndFlush(
                new UserEntity(
                        "resubmission-" + unique,
                        "resubmission-" + unique + "@example.com",
                        "+21620123456"
                )
        );

        KycStatusEntity kyc =
                new KycStatusEntity(user.getId());

        kyc.startVerification(
                "SUMSUB",
                "sandbox-" + unique
        );

        kyc.markResubmissionRequired();

        Instant eventTime =
                Instant.parse("2026-10-26T20:00:00Z");

        kyc.recordProviderEventAt(eventTime);

        kycStatusRepository.saveAndFlush(kyc);

        entityManager.clear();

        KycStatusEntity restored = kycStatusRepository
                .findByUserId(user.getId())
                .orElseThrow();

        assertEquals(
                KycStatus.RESUBMISSION_REQUIRED,
                restored.getStatus()
        );

        assertEquals(
                "SUMSUB",
                restored.getProvider()
        );

        assertEquals(
                eventTime,
                restored.getLastProviderEventAt()
        );

        assertNull(restored.getDecidedAt());

        UserEntity restoredUser = userRepository
                .findById(user.getId())
                .orElseThrow();

        assertEquals(
                AccountStatus.PENDING_KYC,
                restoredUser.getAccountStatus()
        );
    }
}