package com.fintech.identity;

import com.fintech.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class IdentityLookupService {

    private final UserRepository userRepository;

    public IdentityLookupService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findUserIdByKeycloakSubject(
            String keycloakSubject
    ) {
        return findProfileByKeycloakSubject(keycloakSubject)
                .map(IdentityProfile::userId);
    }

    @Transactional(readOnly = true)
    public Optional<IdentityProfile> findProfileByKeycloakSubject(
            String keycloakSubject
    ) {

        if (keycloakSubject == null || keycloakSubject.isBlank()) {
            return Optional.empty();
        }

        return userRepository
                .findByKeycloakSubject(keycloakSubject)
                .map(user -> new IdentityProfile(
                        user.getId(),
                        user.getEmail(),
                        user.getPhoneNumber()
                ));
    }

    public record IdentityProfile(
            UUID userId,
            String email,
            String phoneNumber
    ) {
    }
}