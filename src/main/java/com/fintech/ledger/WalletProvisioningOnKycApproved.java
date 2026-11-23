package com.fintech.ledger;

import com.fintech.kyc.KycApprovedEvent;
import com.fintech.kyc.KycWalletProvisioningEligibility;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Objects;

@Component
public class WalletProvisioningOnKycApproved {

    private final WalletProvisioningService walletProvisioningService;
    private final KycWalletProvisioningEligibility eligibility;

    public WalletProvisioningOnKycApproved(
            WalletProvisioningService walletProvisioningService,
            KycWalletProvisioningEligibility eligibility
    ) {
        this.walletProvisioningService =
                Objects.requireNonNull(walletProvisioningService);

        this.eligibility =
                Objects.requireNonNull(eligibility);
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true
    )
    public void onKycApproved(KycApprovedEvent event) {

        Objects.requireNonNull(event, "event");

        // Always check current account and KYC status.
        // The original event may be older than a later
        // rejection, suspension, or closure.
        if (!eligibility.isEligible(event.userId())) {
            return;
        }

        // Failures must propagate so Spring Modulith
        // can retain the publication for recovery.
        walletProvisioningService.provisionCustomerWallets(
                event.userId()
        );
    }
}