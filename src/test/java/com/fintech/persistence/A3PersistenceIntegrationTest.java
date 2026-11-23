package com.fintech.persistence;

import com.fintech.identity.entity.DeviceEntity;
import com.fintech.identity.entity.DevicePlatform;
import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.DeviceRepository;
import com.fintech.identity.repository.UserRepository;
import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.repository.KycStatusRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class A3PersistenceIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KycStatusRepository kycStatusRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Test
    void shouldPersistAndRetrieveUserKycAndDevice() {

        // =====================================================
        // 1. Create Neos user
        // =====================================================

        String uniqueId = UUID.randomUUID().toString();

        UserEntity user = new UserEntity(
                "keycloak-" + uniqueId,
                "user-" + uniqueId + "@example.com",
                "+21620000000"
        );

        UserEntity savedUser = userRepository.saveAndFlush(user);

        assertNotNull(savedUser.getId());

        // =====================================================
        // 2. Retrieve user by Keycloak subject
        // =====================================================

        UserEntity retrievedUser = userRepository
                .findByKeycloakSubject(savedUser.getKeycloakSubject())
                .orElseThrow();

        assertEquals(
                savedUser.getId(),
                retrievedUser.getId()
        );

        assertEquals(
                savedUser.getEmail(),
                retrievedUser.getEmail()
        );

        // =====================================================
        // 3. Create KYC state
        // =====================================================

        KycStatusEntity kycStatus =
                new KycStatusEntity(savedUser.getId());

        kycStatus.start(
                "sumsub-applicant-" + uniqueId
        );

        KycStatusEntity savedKyc =
                kycStatusRepository.saveAndFlush(kycStatus);

        assertNotNull(savedKyc.getId());

        assertEquals(
                KycStatus.PENDING,
                savedKyc.getStatus()
        );

        // =====================================================
        // 4. Retrieve KYC by Neos user ID
        // =====================================================

        KycStatusEntity retrievedKyc =
                kycStatusRepository
                        .findByUserId(savedUser.getId())
                        .orElseThrow();

        assertEquals(
                savedUser.getId(),
                retrievedKyc.getUserId()
        );

        assertEquals(
                "SUMSUB",
                retrievedKyc.getProvider()
        );

        assertEquals(
                KycStatus.PENDING,
                retrievedKyc.getStatus()
        );

        // =====================================================
        // 5. Create device
        // =====================================================

        DeviceEntity device = new DeviceEntity(
                savedUser,
                "installation-" + uniqueId,
                DevicePlatform.ANDROID
        );

        DeviceEntity savedDevice =
                deviceRepository.saveAndFlush(device);

        assertNotNull(savedDevice.getId());

        assertFalse(savedDevice.isTrusted());
        assertFalse(savedDevice.isRevoked());

        // =====================================================
        // 6. Retrieve devices for user
        // =====================================================

        List<DeviceEntity> devices =
                deviceRepository.findAllByUser_Id(
                        savedUser.getId()
                );

        assertEquals(1, devices.size());

        DeviceEntity retrievedDevice = devices.getFirst();

        assertEquals(
                savedDevice.getId(),
                retrievedDevice.getId()
        );

        assertEquals(
                DevicePlatform.ANDROID,
                retrievedDevice.getPlatform()
        );

        assertEquals(
                savedDevice.getInstallationId(),
                retrievedDevice.getInstallationId()
        );
    }
    @Test
    void shouldRejectDuplicateKeycloakSubject() {

        String keycloakSubject = "keycloak-" + UUID.randomUUID();

        UserEntity firstUser = new UserEntity(
                keycloakSubject,
                "first-" + UUID.randomUUID() + "@example.com",
                "+21620000001"
        );

        userRepository.saveAndFlush(firstUser);

        UserEntity secondUser = new UserEntity(
                keycloakSubject,
                "second-" + UUID.randomUUID() + "@example.com",
                "+21620000002"
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> userRepository.saveAndFlush(secondUser)
        );
    }
    @Test
    void shouldRejectSecondKycStatusForSameUser() {

        String uniqueId = UUID.randomUUID().toString();

        UserEntity user = new UserEntity(
                "keycloak-" + uniqueId,
                "user-" + uniqueId + "@example.com",
                "+21620000003"
        );

        UserEntity savedUser = userRepository.saveAndFlush(user);

        KycStatusEntity firstKyc =
                new KycStatusEntity(savedUser.getId());

        firstKyc.start(
                "sumsub-first-" + uniqueId
        );

        kycStatusRepository.saveAndFlush(firstKyc);

        KycStatusEntity secondKyc =
                new KycStatusEntity(savedUser.getId());

        secondKyc.start(
                "sumsub-second-" + uniqueId
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> kycStatusRepository.saveAndFlush(secondKyc)
        );
    }
    @Test
    void shouldRejectDuplicateInstallationForSameUser() {

        String uniqueId = UUID.randomUUID().toString();

        UserEntity user = new UserEntity(
                "keycloak-" + uniqueId,
                "user-" + uniqueId + "@example.com",
                "+21620000004"
        );

        UserEntity savedUser = userRepository.saveAndFlush(user);

        String installationId =
                "installation-" + uniqueId;

        DeviceEntity firstDevice = new DeviceEntity(
                savedUser,
                installationId,
                DevicePlatform.ANDROID
        );

        deviceRepository.saveAndFlush(firstDevice);

        DeviceEntity duplicateDevice = new DeviceEntity(
                savedUser,
                installationId,
                DevicePlatform.ANDROID
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> deviceRepository.saveAndFlush(duplicateDevice)
        );
    }
}