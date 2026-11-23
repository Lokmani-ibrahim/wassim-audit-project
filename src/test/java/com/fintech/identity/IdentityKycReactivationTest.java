
package com.fintech.identity;

import com.fintech.identity.entity.AccountStatus;
import com.fintech.identity.entity.AccountSuspensionReason;
import com.fintech.identity.entity.UserEntity;
import com.fintech.identity.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdentityKycReactivationTest {

    @Mock
    private UserRepository users;

    private IdentityAccountService accounts;
    private UserEntity user;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        accounts = new IdentityAccountService(users);

        user = new UserEntity(
                "keycloak-" + userId,
                "customer-" + userId + "@example.com",
                null
        );

        when(users.findByIdForUpdate(userId))
                .thenReturn(Optional.of(user));
    }

    @Test
    void initialApprovalActivatesPendingAccount() {

        assertTrue(
                accounts.activateAfterVerifiedKyc(userId)
        );

        assertEquals(
                AccountStatus.ACTIVE,
                user.getAccountStatus()
        );

        assertNull(user.getSuspensionReason());
    }

    @Test
    void successfulReverificationRestoresAccount() {

        assertTrue(
                accounts.activateAfterVerifiedKyc(userId)
        );

        accounts.suspendForKycReverificationIfActive(userId);

        assertEquals(
                AccountStatus.SUSPENDED,
                user.getAccountStatus()
        );

        assertEquals(
                AccountSuspensionReason.KYC_REVERIFICATION,
                user.getSuspensionReason()
        );

        assertTrue(
                accounts.activateAfterVerifiedKyc(userId)
        );

        assertEquals(
                AccountStatus.ACTIVE,
                user.getAccountStatus()
        );

        assertNull(user.getSuspensionReason());
    }

    @Test
    void administrativeOverridePreventsReactivation() {

        accounts.activateAfterVerifiedKyc(userId);

        accounts.suspendForKycReverificationIfActive(userId);

        accounts.suspendAdministratively(userId);

        assertEquals(
                AccountSuspensionReason.ADMINISTRATIVE,
                user.getSuspensionReason()
        );

        assertFalse(
                accounts.activateAfterVerifiedKyc(userId)
        );

        assertEquals(
                AccountStatus.SUSPENDED,
                user.getAccountStatus()
        );

        assertEquals(
                AccountSuspensionReason.ADMINISTRATIVE,
                user.getSuspensionReason()
        );
    }

    @Test
    void finalRejectionPreventsAutomaticReactivation() {

        accounts.activateAfterVerifiedKyc(userId);

        accounts.suspendForKycReverificationIfActive(userId);

        accounts.suspendForKycFinalRejection(userId);

        assertEquals(
                AccountStatus.SUSPENDED,
                user.getAccountStatus()
        );

        assertEquals(
                AccountSuspensionReason.KYC_FINAL_REJECTION,
                user.getSuspensionReason()
        );

        assertFalse(
                accounts.activateAfterVerifiedKyc(userId)
        );
    }

    @Test
    void administrativeSuspensionIsNeverReclassifiedByKyc() {

        accounts.activateAfterVerifiedKyc(userId);

        accounts.suspendAdministratively(userId);

        accounts.suspendForKycReverificationIfActive(userId);

        accounts.suspendForKycFinalRejection(userId);

        assertEquals(
                AccountSuspensionReason.ADMINISTRATIVE,
                user.getSuspensionReason()
        );

        assertFalse(
                accounts.activateAfterVerifiedKyc(userId)
        );
    }

    @Test
    void closedAccountCannotBeReactivated() {

        accounts.activateAfterVerifiedKyc(userId);

        accounts.suspendForKycReverificationIfActive(userId);

        user.close();

        assertFalse(
                accounts.activateAfterVerifiedKyc(userId)
        );

        assertEquals(
                AccountStatus.CLOSED,
                user.getAccountStatus()
        );

        assertNull(user.getSuspensionReason());
    }

    @Test
    void repeatedApprovalCannotActivateTwice() {

        assertTrue(
                accounts.activateAfterVerifiedKyc(userId)
        );

        assertFalse(
                accounts.activateAfterVerifiedKyc(userId)
        );

        assertEquals(
                AccountStatus.ACTIVE,
                user.getAccountStatus()
        );

        verify(users, times(1)).saveAndFlush(user);
    }

    @Test
    void existingGenericSuspensionIsAdministrative() {

        accounts.activateAfterVerifiedKyc(userId);

        accounts.suspendIfActive(userId);

        assertEquals(
                AccountStatus.SUSPENDED,
                user.getAccountStatus()
        );

        assertEquals(
                AccountSuspensionReason.ADMINISTRATIVE,
                user.getSuspensionReason()
        );

        assertFalse(
                accounts.activateAfterVerifiedKyc(userId)
        );
    }
}
