package com.fintech.onboarding;

import com.fintech.identity.CustomerAccountLookup;
import com.fintech.identity.CustomerAccountSummary;
import com.fintech.kyc.KycStatusLookup;
import com.fintech.onboarding.entity.TermsAcceptanceEntity;
import com.fintech.onboarding.repository.TermsAcceptanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Read-only customer profile.
 *
 * Unlike POST /api/kyc/start, reading the profile never
 * starts verification or changes any account state.
 */
@Service
public class CustomerProfileService {

    private final CustomerAccountLookup customerAccountLookup;
    private final KycStatusLookup kycStatusLookup;
    private final TermsAcceptanceRepository termsAcceptanceRepository;

    public CustomerProfileService(
            CustomerAccountLookup customerAccountLookup,
            KycStatusLookup kycStatusLookup,
            TermsAcceptanceRepository termsAcceptanceRepository
    ) {
        this.customerAccountLookup =
                Objects.requireNonNull(customerAccountLookup);

        this.kycStatusLookup =
                Objects.requireNonNull(kycStatusLookup);

        this.termsAcceptanceRepository =
                Objects.requireNonNull(termsAcceptanceRepository);
    }

    @Transactional(readOnly = true)
    public CustomerProfile getProfile(String keycloakSubject) {

        CustomerAccountSummary account =
                customerAccountLookup
                        .findByKeycloakSubject(keycloakSubject)
                        .orElseThrow(
                                CustomerProfileNotFoundException::new
                        );

        // The KYC row is created during onboarding,
        // in the same transaction as the user.
        String kycStatus =
                kycStatusLookup
                        .findStatus(account.userId())
                        .orElse("NOT_STARTED");

        return new CustomerProfile(
                account.userId(),
                account.email(),
                account.phoneNumber(),
                account.accountStatus(),
                kycStatus,
                onboardedAt(account),
                account.createdAt()
        );
    }

    // First accepted terms version.
    private Instant onboardedAt(CustomerAccountSummary account) {

        List<TermsAcceptanceEntity> acceptances =
                termsAcceptanceRepository
                        .findAllByUserIdOrderByAcceptedAtDesc(
                                account.userId()
                        );

        if (acceptances.isEmpty()) {
            return null;
        }

        return acceptances.getLast().getAcceptedAt();
    }
}
