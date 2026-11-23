// Pure mapping from GET /api/profile (§11) to the app's Profile, which routeFor() gates on.
// Both statuses are taken exactly as stored: account status is never derived from KYC.
import type { BackendKycStatus, CustomerProfileResponse } from './contract';
import type { Identity, KycStatus, Profile } from '../types/domain';

const kycByBackend: Record<BackendKycStatus, KycStatus> = {
  NOT_STARTED: 'NOT_STARTED',
  // IN_REVIEW is never produced today (§4) and means the same thing as PENDING.
  PENDING: 'PENDING',
  IN_REVIEW: 'PENDING',
  APPROVED: 'APPROVED',
  // Final: the app offers no retry.
  REJECTED: 'REJECTED',
  // Existing "more info" route.
  RESUBMISSION_REQUIRED: 'MORE_INFO_REQUIRED',
};

export function mapKycStatus(status: BackendKycStatus): KycStatus {
  return kycByBackend[status];
}

// customer === null means GET /api/profile answered 404: signed in but not onboarded yet.
export function profileFromBackend(
  identity: Identity,
  customer: CustomerProfileResponse | null,
): Profile {
  if (!customer) return { identity, onboarded: false, kyc: 'NOT_STARTED', account: 'PENDING_KYC' };
  return {
    identity,
    // A customer row alone does not prove terms were accepted. The server sets this
    // timestamp from the accepted-terms row; KYC must stay gated until it exists.
    onboarded: customer.onboardedAt !== null,
    kyc: mapKycStatus(customer.kycStatus),
    account: customer.accountStatus,
    customer: {
      userId: customer.userId,
      email: customer.email,
      phoneNumber: customer.phoneNumber,
      onboardedAt: customer.onboardedAt,
    },
  };
}
