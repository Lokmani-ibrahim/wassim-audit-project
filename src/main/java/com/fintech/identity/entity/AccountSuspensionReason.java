
package com.fintech.identity.entity;

/**
 * Reason an account is suspended.
 *
 * Only KYC_REVERIFICATION permits automatic restoration
 * after a new verified Sumsub approval.
 */
public enum AccountSuspensionReason {

    KYC_REVERIFICATION,

    KYC_FINAL_REJECTION,

    ADMINISTRATIVE,

    /**
     * Existing suspended accounts whose original
     * suspension reason was not persisted.
     *
     * These accounts must not reactivate automatically.
     */
    UNSPECIFIED
}
