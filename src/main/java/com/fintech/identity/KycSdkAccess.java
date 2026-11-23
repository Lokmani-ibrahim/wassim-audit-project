
package com.fintech.identity;

/**
 * Public identity-module API for Sumsub SDK access.
 *
 * No identity entities, repositories or internal
 * account statuses are exposed to other modules.
 */
public enum KycSdkAccess {

    STANDARD,

    REVERIFICATION,

    RESTRICTED
}
