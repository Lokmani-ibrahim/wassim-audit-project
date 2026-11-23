package com.fintech.identity;

import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

@Service
public class IdentityRegistrationService {

    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    public IdentityRegistrationService(
            UserRepository userRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public enum OnboardingEligibility { READY, EXISTING, CONFLICT }

    /** The email comparison intentionally matches the existing case-sensitive users.email key. */
    @Transactional(readOnly = true)
    public OnboardingEligibility eligibility(String subject, String email) {
        requireIdentity(subject, email);
        if (userRepository.existsByKeycloakSubject(subject)) return OnboardingEligibility.EXISTING;
        return userRepository.existsByEmail(email)
                ? OnboardingEligibility.CONFLICT : OnboardingEligibility.READY;
    }

    @Transactional
    public UUID resolveOrCreatePersonalUser(
            String keycloakSubject,
            String email,
            String phoneNumber
    ) {

        requireIdentity(keycloakSubject, email);
        // Both keys are locked in a stable order within the caller's transaction. This
        // serializes same-subject retries and different subjects using the same email before
        // any existence check or write. Database unique keys remain authoritative.
        long[] keys = { lockKey("subject:", keycloakSubject), lockKey("email:", email) };
        Arrays.sort(keys);
        for (long key : keys) lock(key);

        return userRepository
                .findByKeycloakSubject(keycloakSubject)
                .map(UserEntity::getId)
                .orElseGet(() -> {
                    if (userRepository.existsByEmail(email))
                        throw new OnboardingIdentityConflictException();
                    return createUser(keycloakSubject, email, phoneNumber);
                });
    }

    public static void requireIdentity(String subject, String email) {
        if (subject == null || subject.isBlank() || subject.length() > 255
                || email == null || email.isBlank() || email.length() > 320
                || email.indexOf('@') <= 0 || email.indexOf('@') == email.length() - 1
                || !email.equals(email.trim()) || email.chars().anyMatch(Character::isWhitespace))
            throw new UnusableIdentityClaimsException();
    }

    private static long lockKey(String domain, String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((domain + value).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void lock(long key) {
        jdbcTemplate.execute((java.sql.Connection connection) -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, key);
                statement.execute();
            }
            return null;
        });
    }

    private UUID createUser(
            String keycloakSubject,
            String email,
            String phoneNumber
    ) {

        UserEntity user = new UserEntity(
                keycloakSubject,
                email,
                phoneNumber
        );

        UserEntity savedUser =
                userRepository.saveAndFlush(user);

        return savedUser.getId();
    }
}
