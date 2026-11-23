package com.fintech.ledger.internal.taxonomy;

public enum LedgerAccountKind {

    CUSTOMER_AVAILABLE(
            LedgerBalancePolicy.NON_NEGATIVE
    ),

    CUSTOMER_HOLD(
            LedgerBalancePolicy.NON_NEGATIVE
    ),

    /**
     * Positive representation of cash actually held
     * at the sponsor bank.
     */
    BANK_MASTER(
            LedgerBalancePolicy.NON_NEGATIVE
    ),

    /**
     * Internal balancing account for funds currently
     * represented inside the safeguarded-money system.
     *
     * This account is expected to be zero or negative.
     * It is NOT a real bank overdraft.
     */
    SAFEGUARDED_CONTROL(
            LedgerBalancePolicy.NON_POSITIVE
    ),

    INCOMING_SUSPENSE(
            LedgerBalancePolicy.NON_NEGATIVE
    ),

    PLATFORM_FEES(
            LedgerBalancePolicy.NON_NEGATIVE
    );

    private final LedgerBalancePolicy balancePolicy;

    LedgerAccountKind(
            LedgerBalancePolicy balancePolicy
    ) {
        this.balancePolicy = balancePolicy;
    }

    public LedgerBalancePolicy balancePolicy() {
        return balancePolicy;
    }
}