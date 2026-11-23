// What a failed POST /api/kyc/start or /api/kyc/sdk-token means for the app. Pure.
//
// errorCode (PRs #61, #64) wins when present. Without one (Spring Security, older backends) the
// HTTP status decides, and 409/403 are resolved by re-reading GET /api/profile (see useJourney).
// Unknown codes: on a 5xx "temporarily unavailable" + Try again; on anything else a generic
// message (never a guess at routing).
import { KYC_ERROR_CODES, type KycErrorCode } from '../api/contract';
import { errorCodeFrom } from '../api/parse';
import type { MessageKey } from '../locales';
import type { Profile } from '../types/domain';

export type KycFailure =
  | 'onboardingRequired' // profile missing: route via GET /api/profile (onboarding)
  | 'notStarted' // POST /api/kyc/start, then ask for a token once more
  | 'decided' // already APPROVED or REJECTED: route via GET /api/profile
  | 'accountRestricted' // restricted screen (no SDK)
  | 'manualReview' // stay, tell the user we will review it, hide "Continue"
  | 'applicantInvalid' // backend data problem: support message
  | 'unavailable' // 5xx / network / timeout: "temporarily unavailable" + Try again
  | 'conflict' // 409 without a code: disambiguate via GET /api/profile
  | 'forbidden' // 403 without a code: disambiguate via GET /api/profile
  | 'unknown'; // anything else, including unknown codes on a non-5xx: generic message

const byCode: Record<KycErrorCode, KycFailure> = {
  ONBOARDING_REQUIRED: 'onboardingRequired',
  KYC_NOT_STARTED: 'notStarted',
  KYC_ALREADY_APPROVED: 'decided',
  KYC_FINAL_REJECTED: 'decided',
  KYC_STATE_NOT_ELIGIBLE: 'decided',
  KYC_APPLICANT_INVALID: 'applicantInvalid',
  ACCOUNT_RESTRICTED: 'accountRestricted',
  KYC_MANUAL_REVIEW_REQUIRED: 'manualReview',
  SUMSUB_UPSTREAM_ERROR: 'unavailable',
  SUMSUB_UNAVAILABLE: 'unavailable',
};

function isKycErrorCode(code: string): code is KycErrorCode {
  return (KYC_ERROR_CODES as readonly string[]).includes(code);
}

export function classifyKycFailure(status: number, bodyText?: string): KycFailure {
  const code = errorCodeFrom(bodyText);
  if (code !== null && isKycErrorCode(code)) return byCode[code];
  if (status >= 500) return 'unavailable'; // with or without an unknown code
  if (code !== null) return 'unknown';
  if (status === 409) return 'conflict';
  if (status === 403) return 'forbidden';
  return 'unknown';
}

// Raised by the KYC services for a failed start/token request, so the journey can route.
export class KycFlowError extends Error {
  constructor(public readonly failure: KycFailure) {
    super(`KYC request failed: ${failure}`);
    this.name = 'KycFlowError';
  }
}

// What the journey does about a failure. Pure: `profile` is the fresh GET /api/profile result,
// read first when needsFreshProfile(failure) is true. `retried`: KYC start was already retried.
export type KycFailureStep =
  | { kind: 'startAndRetry' }
  | { kind: 'route'; restricted: boolean }
  | { kind: 'message'; key: MessageKey; reviewLocked: boolean; route: boolean };

export function needsFreshProfile(failure: KycFailure): boolean {
  return ['onboardingRequired', 'decided', 'accountRestricted', 'conflict', 'forbidden'].includes(
    failure,
  );
}

export function resolveKycFailure(
  failure: KycFailure,
  profile: Pick<Profile, 'account' | 'kyc' | 'onboarded'> | null,
  retried: boolean,
): KycFailureStep {
  const message = (key: MessageKey, reviewLocked = false, route = false): KycFailureStep => ({
    kind: 'message',
    key,
    reviewLocked,
    route,
  });
  switch (failure) {
    case 'notStarted':
      return retried ? message('kycError') : { kind: 'startAndRetry' };
    case 'onboardingRequired':
    case 'decided':
      return { kind: 'route', restricted: false };
    case 'accountRestricted':
      return { kind: 'route', restricted: true };
    case 'manualReview':
      return message('kycManualReview', true);
    case 'applicantInvalid':
      return message('kycSupport');
    case 'unavailable':
      return message('kycUnavailable');
    case 'conflict':
      if (profile?.onboarded && profile.kyc === 'NOT_STARTED' && !retried)
        return { kind: 'startAndRetry' };
      return { kind: 'route', restricted: false };
    case 'forbidden':
      if (profile && (profile.account === 'SUSPENDED' || profile.account === 'CLOSED'))
        return { kind: 'route', restricted: true };
      return message('kycManualReview', true, true);
    case 'unknown':
      return message('kycError');
  }
}
