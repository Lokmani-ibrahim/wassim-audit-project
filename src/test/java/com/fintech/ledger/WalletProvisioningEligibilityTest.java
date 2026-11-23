package com.fintech.ledger;

import com.fintech.kyc.KycApprovedEvent;
import com.fintech.kyc.KycWalletProvisioningEligibility;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WalletProvisioningEligibilityTest {

    private final UUID userId = UUID.randomUUID();

    private WalletProvisioningService wallets;
    private KycWalletProvisioningEligibility eligibility;
    private WalletProvisioningOnKycApproved listener;

    @BeforeEach
    void setup() {

        wallets = mock(WalletProvisioningService.class);

        eligibility = mock(
                KycWalletProvisioningEligibility.class
        );

        listener = new WalletProvisioningOnKycApproved(
                wallets,
                eligibility
        );
    }

    @Test
    void eligibleCustomerGetsWallets() {

        when(eligibility.isEligible(userId))
                .thenReturn(true);

        listener.onKycApproved(
                new KycApprovedEvent(userId)
        );

        verify(wallets, times(1))
                .provisionCustomerWallets(userId);
    }

    @Test
    void ineligibleCustomerCannotGetWallets() {

        when(eligibility.isEligible(userId))
                .thenReturn(false);

        listener.onKycApproved(
                new KycApprovedEvent(userId)
        );

        verifyNoInteractions(wallets);
    }

    @Test
    void eligibilityLookupFailureMustPropagate() {

        when(eligibility.isEligible(userId))
                .thenThrow(
                        new IllegalStateException(
                                "Database temporarily unavailable"
                        )
                );

        assertThrows(
                IllegalStateException.class,
                () -> listener.onKycApproved(
                        new KycApprovedEvent(userId)
                )
        );

        verifyNoInteractions(wallets);
    }

    @Test
    void walletProvisioningFailureMustPropagate() {

        when(eligibility.isEligible(userId))
                .thenReturn(true);

        doThrow(new IllegalStateException(
                "Formance unavailable"
        ))
                .when(wallets)
                .provisionCustomerWallets(userId);

        assertThrows(
                IllegalStateException.class,
                () -> listener.onKycApproved(
                        new KycApprovedEvent(userId)
                )
        );

        verify(wallets, times(1))
                .provisionCustomerWallets(userId);
    }
}