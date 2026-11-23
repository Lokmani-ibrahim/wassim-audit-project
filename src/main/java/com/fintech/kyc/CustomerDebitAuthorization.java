package com.fintech.kyc;

import com.fintech.identity.UserAccountStatusLookup;
import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.repository.KycStatusRepository;
import org.springframework.transaction.annotation.Propagation;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class CustomerDebitAuthorization {

    private final KycStatusRepository kycRepository;
    private final UserAccountStatusLookup accounts;

    public CustomerDebitAuthorization(
            KycStatusRepository kycRepository,
            UserAccountStatusLookup accounts
    ) {
        this.kycRepository =
                Objects.requireNonNull(kycRepository);

        this.accounts =
                Objects.requireNonNull(accounts);
    }

    @Transactional(readOnly = true)
    public void requireEligible(UUID userId) {

        Objects.requireNonNull(userId, "userId");

        boolean approved = kycRepository
                .findByUserId(userId)
                .map(kyc ->
                        kyc.getStatus() == KycStatus.APPROVED
                )
                .orElse(false);

        boolean active = accounts.isActive(userId);

        if (!approved || !active) {
            throw new AccessDeniedException(
                    "Customer is not permitted to initiate "
                            + "financial operations"
            );
        }
    }

    /**
     * Locks the KYC row during transfer admission.
     *
     * Must run inside the admission transaction.
     * The lock remains held until that transaction commits.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockAndCheckApprovedForAdmission(UUID userId) {

        Objects.requireNonNull(userId, "userId");

        return kycRepository
                .findByUserIdForUpdate(userId)
                .map(kyc ->
                        kyc.getStatus() == KycStatus.APPROVED
                )
                .orElse(false);
    }

}