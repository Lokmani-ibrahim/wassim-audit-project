package com.fintech.identity;

import com.fintech.identity.entity.AccountStatus;
import com.fintech.identity.repository.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;


@Service
public class UserAccountStatusLookup {

    private final UserRepository repository;

    public UserAccountStatusLookup(
            UserRepository repository
    ) {
        this.repository = Objects.requireNonNull(repository);
    }

    public boolean isActive(UUID userId) {

        Objects.requireNonNull(userId, "userId");

        return repository.findById(userId)
                .map(user ->
                        user.getAccountStatus() == AccountStatus.ACTIVE
                )
                .orElse(false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireActiveForTransferAdmission(UUID userId) {

        Objects.requireNonNull(userId, "userId");

        // The caller must already have opened a transaction.
        // This locks the same users row that suspension locks.
        var user = repository
                .findByIdForUpdate(userId)
                .orElseThrow(() -> new AccessDeniedException(
                        "Customer account is unavailable"
                ));

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new AccessDeniedException(
                    "Customer account is not ACTIVE"
            );
        }
    }

}