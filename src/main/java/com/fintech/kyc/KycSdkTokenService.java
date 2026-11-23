
package com.fintech.kyc;

import com.fintech.identity.CustomerAccountLookup;
import com.fintech.identity.CustomerAccountSummary;
import com.fintech.identity.IdentityAccountService;
import com.fintech.identity.KycSdkAccess;

import com.fintech.kyc.entity.KycStatus;
import com.fintech.kyc.entity.KycStatusEntity;
import com.fintech.kyc.port.KycProvider;
import com.fintech.kyc.repository.KycStatusRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.UUID;

@Service
public class KycSdkTokenService {

    private final CustomerAccountLookup customerAccounts;
    private final IdentityAccountService identityAccounts;
    private final KycStatusRepository kycStatuses;
    private final KycProvider kycProvider;

    public KycSdkTokenService(
            CustomerAccountLookup customerAccounts,
            IdentityAccountService identityAccounts,
            KycStatusRepository kycStatuses,
            KycProvider kycProvider
    ) {
        this.customerAccounts =
                Objects.requireNonNull(customerAccounts);

        this.identityAccounts =
                Objects.requireNonNull(identityAccounts);

        this.kycStatuses =
                Objects.requireNonNull(kycStatuses);

        this.kycProvider =
                Objects.requireNonNull(kycProvider);
    }

    public KycProvider.VerificationSession createSession(
            String keycloakSubject
    ) {

        if (keycloakSubject == null
                || keycloakSubject.isBlank()) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authenticated customer required"
            );
        }

        /*
         * STEP 1 — Resolve authenticated customer.
         *
         * Customer identity must come from the JWT.
         * Never accept a client-supplied userId.
         */
        CustomerAccountSummary customer =
                customerAccounts
                        .findByKeycloakSubject(
                                keycloakSubject
                        )
                        .orElseThrow(
                                KycOnboardingRequiredException::new
                        );

        UUID userId = customer.userId();

        /*
         * STEP 2 — Account security restrictions.
         *
         * Identity owns account eligibility,
         * including suspension reasons.
         */
        KycSdkAccess access =
                identityAccounts.kycSdkAccess(
                        userId,
                        keycloakSubject
                );

        if (access == KycSdkAccess.RESTRICTED) {
            throw new KycApiException(
                    KycApiErrorCode.ACCOUNT_RESTRICTED
            );
        }

        /*
         * STEP 3 — KYC must have been started.
         *
         * A missing KYC record and NOT_STARTED
         * both mean the SDK session is unavailable
         * until the start flow is completed.
         */
        KycStatusEntity kyc = kycStatuses
                .findByUserId(userId)
                .orElseThrow(
                        () -> new KycApiException(
                                KycApiErrorCode.KYC_NOT_STARTED
                        )
                );

        KycStatus status = kyc.getStatus();

        if (status == KycStatus.NOT_STARTED) {
            throw new KycApiException(
                    KycApiErrorCode.KYC_NOT_STARTED
            );
        }

        /*
         * Distinguish already-decided states.
         */
        if (status == KycStatus.APPROVED) {
            throw new KycApiException(
                    KycApiErrorCode.KYC_ALREADY_APPROVED
            );
        }

        if (status == KycStatus.REJECTED) {
            throw new KycApiException(
                    KycApiErrorCode.KYC_FINAL_REJECTED
            );
        }

        /*
         * SDK sessions are supported only while
         * verification is ongoing or resubmission
         * is required.
         */
        if (status != KycStatus.PENDING
                && status != KycStatus.IN_REVIEW
                && status != KycStatus.RESUBMISSION_REQUIRED) {

            throw new KycApiException(
                    KycApiErrorCode.KYC_STATE_NOT_ELIGIBLE
            );
        }

        /*
         * A suspended account is eligible only
         * when its suspension is specifically for
         * KYC reverification AND the KYC status
         * is RESUBMISSION_REQUIRED.
         *
         * Financial operations remain blocked.
         */
        if (access == KycSdkAccess.REVERIFICATION
                && status != KycStatus.RESUBMISSION_REQUIRED) {

            throw new KycApiException(
                    KycApiErrorCode.ACCOUNT_RESTRICTED
            );
        }

        /*
         * STEP 4 — Validate persisted applicant.
         */
        if (!"SUMSUB".equals(kyc.getProvider())
                || kyc.getProviderApplicantId() == null
                || kyc.getProviderApplicantId().isBlank()) {

            throw new KycApiException(
                    KycApiErrorCode.KYC_APPLICANT_INVALID
            );
        }

        /*
         * Administrative review cannot be bypassed.
         */
        if (kyc.isInitialFinalRejectionLocked()
                || kyc.isManualReviewRequired()) {

            throw new KycApiException(
                    KycApiErrorCode.KYC_MANUAL_REVIEW_REQUIRED
            );
        }

        /*
         * STEP 5 — Request Sumsub SDK session.
         *
         * No database transaction is held open
         * during the external provider request.
         */
        KycProvider.VerificationSession session =
                kycProvider.createVerificationSession(
                        new KycProvider
                                .CreateVerificationSessionCommand(
                                userId
                        )
                );

        /*
         * Invalid provider responses are mapped
         * to the stable upstream error code.
         *
         * Transport errors and timeouts are handled
         * separately in Fix #10.
         */
        if (session == null
                || session.token() == null
                || session.token().isBlank()
                || session.expiresInSeconds() <= 0) {

            throw new KycApiException(
                    KycApiErrorCode.SUMSUB_UPSTREAM_ERROR
            );
        }

        return session;
    }
}
