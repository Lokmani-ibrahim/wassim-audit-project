
package com.fintech.kyc;

import com.fintech.identity.IdentityLookupService;
import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.port.KycProvider;
import com.fintech.kyc.repository.KycStatusRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class KycStartService {

    private static final String PROVIDER = "SUMSUB";

    private final IdentityLookupService identityLookupService;
    private final KycStatusRepository kycStatusRepository;
    private final KycProvider kycProvider;

    public KycStartService(
            IdentityLookupService identityLookupService,
            KycStatusRepository kycStatusRepository,
            KycProvider kycProvider
    ) {
        this.identityLookupService = identityLookupService;
        this.kycStatusRepository = kycStatusRepository;
        this.kycProvider = kycProvider;
    }

    @Transactional
    public KycStartResult start(String keycloakSubject) {

        IdentityLookupService.IdentityProfile profile =
                identityLookupService
                        .findProfileByKeycloakSubject(
                                keycloakSubject
                        )
                        .orElseThrow(
                                KycOnboardingRequiredException::new
                        );

        UUID userId = profile.userId();

        KycStatusEntity kyc =
                kycStatusRepository
                        .findByUserId(userId)
                        .orElseThrow(
                                KycOnboardingRequiredException::new
                        );

        /*
         * Idempotency:
         *
         * Do not create another Sumsub applicant
         * if verification has already started.
         *
         * SECURITY:
         * Never reactivate suspended or closed
         * accounts during a KYC retry.
         */
        if (kyc.getStatus() != KycStatus.NOT_STARTED) {
            return result(userId, kyc);
        }

        /*
         * Sumsub is the only supported KYC provider.
         *
         * A provider error must propagate instead of
         * silently approving the customer.
         */
        KycProvider.Applicant applicant =
                kycProvider.createApplicant(
                        new KycProvider.CreateApplicantCommand(
                                userId,
                                profile.email(),
                                profile.phoneNumber()
                        )
                );

        if (applicant == null
                || applicant.providerApplicantId() == null
                || applicant.providerApplicantId().isBlank()) {

            throw new KycApiException(
                    KycApiErrorCode.SUMSUB_UPSTREAM_ERROR
            );
        }

        /*
         * Creating an applicant does not mean
         * that identity verification succeeded.
         *
         * Approval is handled only by the
         * authenticated Sumsub decision workflow.
         */
        kyc.startVerification(
                PROVIDER,
                applicant.providerApplicantId()
        );

        KycStatusEntity savedKyc =
                kycStatusRepository.saveAndFlush(kyc);

        return result(userId, savedKyc);
    }

    private KycStartResult result(
            UUID userId,
            KycStatusEntity kyc
    ) {
        return new KycStartResult(
                userId,
                kyc.getStatus().name(),
                kyc.getProvider(),
                "NON_PROD"
        );
    }
}
