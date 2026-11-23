package com.fintech.ledger.internal.taxonomy;

/**
 * Expected sign convention for a Neos ledger account family.
 *
 * This is a Neos domain rule. Actual enforcement will later be done
 * through controlled ledger operations / Numscript.
 */
public enum LedgerBalancePolicy {

    /**
     * Account should normally have a balance >= 0.
     */
    NON_NEGATIVE,

    /**
     * Account should normally have a balance <= 0.
     */
    NON_POSITIVE
}