
package com.fintech.identity;

import com.fintech.identity.entity.AccountStatus;
import com.fintech.identity.entity.AccountSuspensionReason;
import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdentityKycSdkAccessTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String SUBJECT = "customer-keycloak-id";

    @Mock
    private UserRepository users;

    @Mock
    private UserEntity user;

    private void givenUser(
            AccountStatus status,
            AccountSuspensionReason reason
    ) {
        when(users.findById(USER_ID))
                .thenReturn(Optional.of(user));

        when(user.getKeycloakSubject())
                .thenReturn(SUBJECT);

        when(user.getAccountStatus())
                .thenReturn(status);

        if (status == AccountStatus.SUSPENDED) {
            when(user.getSuspensionReason())
                    .thenReturn(reason);
        }
    }

    private KycSdkAccess access() {
        return new IdentityAccountService(users)
                .kycSdkAccess(USER_ID, SUBJECT);
    }

    @Test
    void pendingKycIsAllowed() {
        givenUser(AccountStatus.PENDING_KYC, null);

        assertEquals(KycSdkAccess.STANDARD, access());
    }

    @Test
    void activeAccountIsAllowed() {
        givenUser(AccountStatus.ACTIVE, null);

        assertEquals(KycSdkAccess.STANDARD, access());
    }

    @Test
    void kycReverificationIsAllowed() {
        givenUser(
                AccountStatus.SUSPENDED,
                AccountSuspensionReason.KYC_REVERIFICATION
        );

        assertEquals(KycSdkAccess.REVERIFICATION, access());
    }

    @Test
    void administrativeSuspensionIsDenied() {
        givenUser(
                AccountStatus.SUSPENDED,
                AccountSuspensionReason.ADMINISTRATIVE
        );

        assertEquals(KycSdkAccess.RESTRICTED, access());
    }

    @Test
    void finalRejectionSuspensionIsDenied() {
        givenUser(
                AccountStatus.SUSPENDED,
                AccountSuspensionReason.KYC_FINAL_REJECTION
        );

        assertEquals(KycSdkAccess.RESTRICTED, access());
    }

    @Test
    void legacySuspensionIsDenied() {
        givenUser(
                AccountStatus.SUSPENDED,
                AccountSuspensionReason.UNSPECIFIED
        );

        assertEquals(KycSdkAccess.RESTRICTED, access());
    }

    @Test
    void closedAccountIsDenied() {
        givenUser(AccountStatus.CLOSED, null);

        assertEquals(KycSdkAccess.RESTRICTED, access());
    }

    @Test
    void incorrectKeycloakSubjectIsDenied() {

        when(users.findById(USER_ID))
                .thenReturn(Optional.of(user));

        when(user.getKeycloakSubject())
                .thenReturn("another-customer");

        assertEquals(KycSdkAccess.RESTRICTED, access());
    }

    @Test
    void missingCustomerIsDenied() {

        when(users.findById(USER_ID))
                .thenReturn(Optional.empty());

        assertEquals(KycSdkAccess.RESTRICTED, access());
    }

    @Test
    void blankSubjectIsDenied() {

        KycSdkAccess result =
                new IdentityAccountService(users)
                        .kycSdkAccess(USER_ID, "");

        assertEquals(KycSdkAccess.RESTRICTED, result);

        verifyNoInteractions(users);
    }
}
