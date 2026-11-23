package com.fintech.kyc;

import com.fintech.identity.UserAccountStatusLookup;
import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.repository.KycStatusRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class KycWalletProvisioningEligibility {

    private final KycStatusRepository kycRepository;
    private final UserAccountStatusLookup accountStatuses;

    public KycWalletProvisioningEligibility(
            KycStatusRepository kycRepository,
            UserAccountStatusLookup accountStatuses
    ) {
        this.kycRepository = Objects.requireNonNull(kycRepository);
        this.accountStatuses = Objects.requireNonNull(accountStatuses);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = true
    )
    public boolean isEligible(UUID userId) {

        Objects.requireNonNull(userId, "userId");

        boolean approved = kycRepository.findByUserId(userId)
                .map(kyc ->
                        kyc.getStatus() == KycStatus.APPROVED
                )
                .orElse(false);

        if (!approved) {
            return false;
        }

        return accountStatuses.isActive(userId);
    }
}