package com.fintech.kyc;

import com.fintech.identity.UserAccountStatusLookup;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.repository.KycStatusRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerDebitAuthorizationTest {

    private final UUID userId = UUID.randomUUID();

    private KycStatusRepository repository;
    private UserAccountStatusLookup accounts;
    private CustomerDebitAuthorization authorization;
    private KycStatusEntity kyc;

    @BeforeEach
    void setup() {

        repository = mock(KycStatusRepository.class);
        accounts = mock(UserAccountStatusLookup.class);

        authorization = new CustomerDebitAuthorization(
                repository,
                accounts
        );

        kyc = new KycStatusEntity(userId);
        kyc.startVerification(
                "SUMSUB",
                "sandbox-applicant-123"
        );

        when(repository.findByUserId(userId))
                .thenReturn(Optional.of(kyc));
    }

    @Test
    void approvedAndActiveCustomerIsAllowed() {

        kyc.approve();

        when(accounts.isActive(userId))
                .thenReturn(true);

        assertDoesNotThrow(
                () -> authorization.requireEligible(userId)
        );
    }

    @Test
    void suspendedCustomerIsDenied() {

        kyc.approve();

        when(accounts.isActive(userId))
                .thenReturn(false);

        assertThrows(
                AccessDeniedException.class,
                () -> authorization.requireEligible(userId)
        );
    }

    @Test
    void pendingKycCustomerIsDenied() {

        when(accounts.isActive(userId))
                .thenReturn(true);

        assertThrows(
                AccessDeniedException.class,
                () -> authorization.requireEligible(userId)
        );
    }

    @Test
    void missingKycRecordIsDenied() {

        when(repository.findByUserId(userId))
                .thenReturn(Optional.empty());

        when(accounts.isActive(userId))
                .thenReturn(true);

        assertThrows(
                AccessDeniedException.class,
                () -> authorization.requireEligible(userId)
        );
    }
}