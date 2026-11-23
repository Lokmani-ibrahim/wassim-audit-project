
package com.fintech.identity;

import com.fintech.identity.entity.AccountStatus;
import com.fintech.identity.entity.AccountSuspensionReason;
import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class IdentityAccountService {

    private final UserRepository userRepository;

    public IdentityAccountService(
            UserRepository userRepository
    ) {
        this.userRepository =
                Objects.requireNonNull(userRepository);
    }

    /**
     * Public identity-module API for KYC SDK access.
     *
     * KYC must not read UserEntity or UserRepository
     * directly across the module boundary.
     *
     * STANDARD:
     *   PENDING_KYC or ACTIVE.
     *
     * REVERIFICATION:
     *   Suspended specifically for KYC reverification.
     *
     * RESTRICTED:
     *   Administrative suspension, final rejection,
     *   legacy suspension, closed or missing account.
     */
    @Transactional(readOnly = true)
    public KycSdkAccess kycSdkAccess(
            UUID userId,
            String keycloakSubject
    ) {
        if (userId == null
                || keycloakSubject == null
                || keycloakSubject.isBlank()) {
            return KycSdkAccess.RESTRICTED;
        }

        UserEntity user = userRepository.findById(userId)
                .orElse(null);

        if (user == null
                || !keycloakSubject.equals(
                user.getKeycloakSubject())) {
            return KycSdkAccess.RESTRICTED;
        }

        return switch (user.getAccountStatus()) {

            case PENDING_KYC, ACTIVE ->
                    KycSdkAccess.STANDARD;

            case SUSPENDED ->
                    user.getSuspensionReason()
                            == AccountSuspensionReason.KYC_REVERIFICATION
                            ? KycSdkAccess.REVERIFICATION
                            : KycSdkAccess.RESTRICTED;

            case CLOSED ->
                    KycSdkAccess.RESTRICTED;
        };
    }

    @Transactional
    public boolean activateIfPendingKyc(UUID userId) {

        UserEntity user = lockUser(userId);

        if (user.getAccountStatus()
                != AccountStatus.PENDING_KYC) {
            return false;
        }

        user.activate();
        userRepository.saveAndFlush(user);

        return true;
    }

    /**
     * Initial approval or eligible KYC reverification.
     *
     * Never overrides administrative restrictions.
     */
    @Transactional
    public boolean activateAfterVerifiedKyc(
            UUID userId
    ) {
        UserEntity user = lockUser(userId);

        if (user.getAccountStatus()
                == AccountStatus.PENDING_KYC) {

            user.activate();

        } else if (
                user.getAccountStatus()
                        == AccountStatus.SUSPENDED
                        && user.getSuspensionReason()
                        == AccountSuspensionReason.KYC_REVERIFICATION
        ) {

            user.reactivateAfterKycReverification();

        } else {
            return false;
        }

        userRepository.saveAndFlush(user);
        return true;
    }

    @Transactional
    public void suspendIfActive(UUID userId) {

        UserEntity user = lockUser(userId);

        if (user.getAccountStatus()
                == AccountStatus.ACTIVE) {

            user.suspend();
            userRepository.saveAndFlush(user);
        }
    }

    @Transactional
    public void suspendForKycReverificationIfActive(
            UUID userId
    ) {
        UserEntity user = lockUser(userId);

        if (user.getAccountStatus()
                == AccountStatus.ACTIVE) {

            user.suspendForKycReverification();
            userRepository.saveAndFlush(user);
        }
    }

    @Transactional
    public void suspendForKycFinalRejection(
            UUID userId
    ) {
        UserEntity user = lockUser(userId);

        boolean canApply =
                user.getAccountStatus()
                        == AccountStatus.ACTIVE
                        || (
                        user.getAccountStatus()
                                == AccountStatus.SUSPENDED
                                && user.getSuspensionReason()
                                == AccountSuspensionReason.KYC_REVERIFICATION
                );

        if (!canApply) {
            return;
        }

        user.suspendForKycFinalRejection();
        userRepository.saveAndFlush(user);
    }

    /**
     * Restricted to trusted administrative callers.
     * Do not expose directly through customer endpoints.
     */
    @Transactional
    public void suspendAdministratively(UUID userId) {

        UserEntity user = lockUser(userId);

        if (user.getAccountStatus()
                == AccountStatus.CLOSED) {
            return;
        }

        user.suspend();
        userRepository.saveAndFlush(user);
    }

    private UserEntity lockUser(UUID userId) {

        Objects.requireNonNull(userId, "userId");

        return userRepository
                .findByIdForUpdate(userId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "User does not exist"
                        )
                );
    }
}
