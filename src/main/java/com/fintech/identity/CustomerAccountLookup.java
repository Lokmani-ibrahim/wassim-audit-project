package com.fintech.identity;

import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class CustomerAccountLookup {

    private final UserRepository userRepository;

    public CustomerAccountLookup(
            UserRepository userRepository
    ) {
        this.userRepository =
                Objects.requireNonNull(
                        userRepository,
                        "userRepository must not be null"
                );
    }

    @Transactional(readOnly = true)
    public Optional<CustomerAccountSummary> findByKeycloakSubject(
            String keycloakSubject
    ) {

        if (keycloakSubject == null || keycloakSubject.isBlank()) {
            return Optional.empty();
        }

        return userRepository
                .findByKeycloakSubject(keycloakSubject)
                .map(CustomerAccountLookup::summary);
    }

    /**
     * Finds a customer by email, ignoring surrounding
     * whitespace and case.
     *
     * Emails are stored as received in the Keycloak
     * "email" claim (Keycloak keeps them lowercase), and
     * the unique constraint on users.email is
     * case-sensitive. If more than one row matches
     * case-insensitively the result is ambiguous and
     * nothing is returned.
     */
    @Transactional(readOnly = true)
    public Optional<CustomerAccountSummary> findByEmail(
            String email
    ) {

        if (email == null || email.isBlank()) {
            return Optional.empty();
        }

        List<UserEntity> matches =
                userRepository.findAllByEmailIgnoreCase(
                        email.trim()
                );

        if (matches.size() != 1) {
            return Optional.empty();
        }

        return Optional.of(
                summary(matches.getFirst())
        );
    }

    private static CustomerAccountSummary summary(
            UserEntity user
    ) {
        return new CustomerAccountSummary(
                user.getId(),
                user.getEmail(),
                user.getPhoneNumber(),
                user.getAccountStatus().name(),
                user.getCreatedAt()
        );
    }
}
