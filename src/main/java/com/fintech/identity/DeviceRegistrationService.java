package com.fintech.identity;

import com.fintech.identity.entity.DeviceEntity;
import com.fintech.identity.entity.DevicePlatform;
import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.DeviceRepository;
import com.fintech.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class DeviceRegistrationService {

    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;

    public DeviceRegistrationService(
            UserRepository userRepository,
            DeviceRepository deviceRepository
    ) {
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
    }

    @Transactional
    public UUID ensureRegistered(
            UUID userId,
            String installationId,
            String platform
    ) {

        Objects.requireNonNull(
                userId,
                "userId must not be null"
        );

        Objects.requireNonNull(
                installationId,
                "installationId must not be null"
        );

        Objects.requireNonNull(
                platform,
                "platform must not be null"
        );

        if (installationId.isBlank()) {
            throw new IllegalArgumentException(
                    "installationId must not be blank"
            );
        }

        DevicePlatform devicePlatform =
                parsePlatform(platform);

        return deviceRepository
                .findByUser_IdAndInstallationId(
                        userId,
                        installationId
                )
                .map(existingDevice -> {
                    existingDevice.markSeen();

                    DeviceEntity savedDevice =
                            deviceRepository.saveAndFlush(
                                    existingDevice
                            );

                    return savedDevice.getId();
                })
                .orElseGet(() ->
                        createDevice(
                                userId,
                                installationId,
                                devicePlatform
                        )
                );
    }

    private UUID createDevice(
            UUID userId,
            String installationId,
            DevicePlatform platform
    ) {

        UserEntity user = userRepository
                .findById(userId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "User not found: " + userId
                        )
                );

        DeviceEntity device =
                new DeviceEntity(
                        user,
                        installationId,
                        platform
                );

        DeviceEntity savedDevice =
                deviceRepository.saveAndFlush(device);

        return savedDevice.getId();
    }

    private DevicePlatform parsePlatform(
            String platform
    ) {

        try {
            return DevicePlatform.valueOf(
                    platform
                            .trim()
                            .toUpperCase(Locale.ROOT)
            );
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unsupported device platform: "
                            + platform
            );
        }
    }
}