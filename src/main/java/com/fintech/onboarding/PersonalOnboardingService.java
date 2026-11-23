package com.fintech.onboarding;

import com.fintech.identity.DeviceRegistrationService;
import com.fintech.identity.IdentityRegistrationService;
import com.fintech.kyc.KycInitializationService;
import com.fintech.onboarding.entity.TermsAcceptanceEntity;
import com.fintech.onboarding.repository.TermsAcceptanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class PersonalOnboardingService {

    private final TermsPolicy termsPolicy;
    private final IdentityRegistrationService identityRegistrationService;
    private final DeviceRegistrationService deviceRegistrationService;
    private final KycInitializationService kycInitializationService;
    private final TermsAcceptanceRepository termsAcceptanceRepository;

    public PersonalOnboardingService(
            TermsPolicy termsPolicy,
            IdentityRegistrationService identityRegistrationService,
            DeviceRegistrationService deviceRegistrationService,
            KycInitializationService kycInitializationService,
            TermsAcceptanceRepository termsAcceptanceRepository
    ) {
        this.termsPolicy = termsPolicy;
        this.identityRegistrationService = identityRegistrationService;
        this.deviceRegistrationService = deviceRegistrationService;
        this.kycInitializationService = kycInitializationService;
        this.termsAcceptanceRepository = termsAcceptanceRepository;
    }

    @Transactional
    public PersonalOnboardingResult onboard(
            String keycloakSubject,
            String email,
            String phoneNumber,
            String installationId,
            String platform,
            String termsVersion
    ) {

        IdentityRegistrationService.requireIdentity(keycloakSubject, email);

        Objects.requireNonNull(
                installationId,
                "installationId must not be null"
        );

        Objects.requireNonNull(
                platform,
                "platform must not be null"
        );

        Objects.requireNonNull(
                termsVersion,
                "termsVersion must not be null"
        );

        if (termsVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "termsVersion must not be blank"
            );
        }
        String validatedTermsVersion =
                termsPolicy.requireCurrentVersion(
                        termsVersion
                );

        UUID userId =
                identityRegistrationService
                        .resolveOrCreatePersonalUser(
                                keycloakSubject,
                                email,
                                phoneNumber
                        );

        UUID deviceId =
                deviceRegistrationService
                        .ensureRegistered(
                                userId,
                                installationId,
                                platform
                        );

        kycInitializationService
                .ensureInitialized(userId);

        ensureTermsAccepted(
                userId,
                validatedTermsVersion
        );

        return new PersonalOnboardingResult(
                userId,
                deviceId,
                "NOT_STARTED",
                validatedTermsVersion
        );
    }

    private void ensureTermsAccepted(
            UUID userId,
            String termsVersion
    ) {

        boolean alreadyAccepted =
                termsAcceptanceRepository
                        .existsByUserIdAndTermsVersion(
                                userId,
                                termsVersion
                        );

        if (alreadyAccepted) {
            return;
        }

        TermsAcceptanceEntity acceptance =
                new TermsAcceptanceEntity(
                        userId,
                        termsVersion
                );

        termsAcceptanceRepository
                .saveAndFlush(acceptance);
    }
}
