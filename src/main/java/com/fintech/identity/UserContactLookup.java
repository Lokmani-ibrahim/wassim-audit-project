package com.fintech.identity;

import com.fintech.identity.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserContactLookup {

    private final UserRepository userRepository;

    public UserContactLookup(
            UserRepository userRepository
    ) {
        this.userRepository =
                Objects.requireNonNull(
                        userRepository,
                        "userRepository must not be null"
                );
    }

    public Optional<String> findEmail(
            UUID userId
    ) {

        Objects.requireNonNull(
                userId,
                "userId must not be null"
        );

        return userRepository
                .findById(userId)
                .map(user -> user.getEmail())
                .filter(email ->
                        email != null
                                && !email.isBlank()
                );
    }
}