package com.fintech.identity.repository;

import com.fintech.identity.entity.DeviceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<DeviceEntity, UUID> {

    List<DeviceEntity> findAllByUser_Id(UUID userId);

    Optional<DeviceEntity> findByUser_IdAndInstallationId(
            UUID userId,
            String installationId
    );

    boolean existsByUser_IdAndInstallationId(
            UUID userId,
            String installationId
    );
}