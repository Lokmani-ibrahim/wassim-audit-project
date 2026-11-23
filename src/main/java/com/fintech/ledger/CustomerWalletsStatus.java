package com.fintech.ledger;

public enum CustomerWalletsStatus {

    /**
     * Every supported currency wallet is provisioned.
     */
    READY,

    /**
     * At least one wallet is not provisioned yet
     * (provisioning in flight or failed) while the
     * customer is, or has been, eligible for wallets.
     */
    PROVISIONING,

    /**
     * KYC is not APPROVED and no wallet exists.
     */
    NOT_ELIGIBLE
}
