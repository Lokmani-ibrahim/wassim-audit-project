import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  ACCOUNT_STATUSES,
  BACKEND_KYC_STATUSES,
  type AccountStatus,
  type BackendKycStatus,
  type CustomerProfileResponse,
} from '../src/api/contract';
import { mapKycStatus, profileFromBackend } from '../src/api/profileMapping';
import { ContractError, parseAuthMe, parseProfile } from '../src/api/parse';

import { routeFor } from '../src/navigation/state';
import type { Route } from '../src/types/domain';

const contract = (pattern: RegExp) => (error: unknown) =>
  error instanceof ContractError && error.key === 'unexpectedError' && pattern.test(error.detail);

const identity = { subject: 'kc-1', username: 'neos-sender', email: 'neos-sender@neos.local' };
function backend(
  kycStatus: BackendKycStatus,
  accountStatus: AccountStatus,
): CustomerProfileResponse {
  return {
    userId: '7bf8f8ad-1133-419d-9a13-9c3883690de0',
    email: 'neos-sender@neos.local',
    phoneNumber: null,
    accountStatus,
    kycStatus,
    onboardedAt: '2026-11-02T00:28:01.654557Z',
    createdAt: '2026-11-02T00:28:01.529145Z',
  };
}
// Expected gate for every KYC x account x "SDK refused for account reasons" combination
// (acknowledged session, so APPROVED+ACTIVE goes straight to the authenticated area).
// SUSPENDED + RESUBMISSION_REQUIRED may resubmit (KYC-reverification suspension; the reason is
// not exposed) until POST /api/kyc/sdk-token refuses it (ACCOUNT_RESTRICTED => kycRestricted).
function expected(kyc: BackendKycStatus, account: AccountStatus, kycRestricted = false): Route {
  if (account === 'CLOSED') return 'restricted';
  if (account === 'SUSPENDED')
    return kyc === 'RESUBMISSION_REQUIRED' && !kycRestricted ? 'moreInfo' : 'restricted';
  switch (kyc) {
    case 'NOT_STARTED':
      return 'kycIntro';
    case 'PENDING':
    case 'IN_REVIEW':
      return 'pending';
    case 'REJECTED':
      return 'rejected';
    case 'RESUBMISSION_REQUIRED':
      return 'moreInfo';
    case 'APPROVED':
      return account === 'ACTIVE' ? 'home' : 'pending';
  }
}

test('every backend KYC status maps; RESUBMISSION_REQUIRED -> more info, IN_REVIEW = PENDING', () => {
  assert.equal(mapKycStatus('RESUBMISSION_REQUIRED'), 'MORE_INFO_REQUIRED');
  assert.equal(mapKycStatus('IN_REVIEW'), 'PENDING');
  assert.equal(mapKycStatus('PENDING'), 'PENDING');
  assert.equal(mapKycStatus('REJECTED'), 'REJECTED');
  assert.equal(mapKycStatus('APPROVED'), 'APPROVED');
  assert.equal(mapKycStatus('NOT_STARTED'), 'NOT_STARTED');
});

test('routing for all KYC x account combinations uses the real account status', () => {
  for (const kyc of BACKEND_KYC_STATUSES)
    for (const account of ACCOUNT_STATUSES) {
      const profile = profileFromBackend(identity, backend(kyc, account));
      assert.equal(profile.account, account, 'account status is never derived from KYC');
      assert.equal(routeFor(profile, true), expected(kyc, account), `${kyc} + ${account}`);
      assert.equal(
        routeFor(profile, true, { kycRestricted: true }),
        expected(kyc, account, true),
        `${kyc} + ${account} + kycRestricted`,
      );
    }
});

test('SUSPENDED + APPROVED is restricted (valid backend combination), CLOSED too', () => {
  assert.equal(
    routeFor(profileFromBackend(identity, backend('APPROVED', 'SUSPENDED')), true),
    'restricted',
  );
  assert.equal(
    routeFor(profileFromBackend(identity, backend('APPROVED', 'CLOSED')), true),
    'restricted',
  );
  // Approved but not yet activated: no access to the authenticated area.
  assert.equal(
    routeFor(profileFromBackend(identity, backend('APPROVED', 'PENDING_KYC')), true),
    'pending',
  );
  // Not acknowledged yet: the approval screen comes first.
  assert.equal(routeFor(profileFromBackend(identity, backend('APPROVED', 'ACTIVE'))), 'approved');
});

test('404 (not onboarded) => onboarding intro; customer details carry the own userId', () => {
  const fresh = profileFromBackend(identity, null);
  assert.equal(fresh.onboarded, false);
  assert.equal(fresh.customer, undefined);
  assert.equal(routeFor(fresh, true), 'intro');
  const onboarded = profileFromBackend(identity, backend('APPROVED', 'ACTIVE'));
  assert.equal(onboarded.customer?.userId, '7bf8f8ad-1133-419d-9a13-9c3883690de0');
  assert.equal(onboarded.customer?.phoneNumber, null);
});

test('a conflict is gated before onboarding and a bare customer row does not prove completion', () => {
  assert.equal(
    routeFor(profileFromBackend({ ...identity, onboardingEligibility: 'CONFLICT' }, null)),
    'onboardingConflict',
  );
  const partial = profileFromBackend(identity, {
    ...backend('NOT_STARTED', 'PENDING_KYC'),
    onboardedAt: null,
  });
  assert.equal(partial.onboarded, false);
  assert.equal(routeFor(partial), 'intro');
  assert.equal(routeFor(profileFromBackend(identity, backend('APPROVED', 'ACTIVE')), true), 'home');
});

test('bootstrap requires a recognized subject-scoped eligibility value', () => {
  const response = { ...identity, onboardingEligibility: 'CONFLICT' };
  assert.deepEqual(parseAuthMe(response), response);
  assert.throws(() => parseAuthMe(identity), contract(/onboardingEligibility/));
  assert.throws(
    () => parseAuthMe({ ...response, onboardingEligibility: 'UNKNOWN' }),
    contract(/onboardingEligibility/),
  );
});

test('profile parser accepts the documented response and rejects unknown statuses/shapes', () => {
  const sample = {
    userId: '7bf8f8ad-1133-419d-9a13-9c3883690de0',
    email: 'neos-sender@neos.local',
    phoneNumber: '+21620123456',
    accountStatus: 'ACTIVE',
    kycStatus: 'APPROVED',
    onboardedAt: '2026-11-02T00:28:01.654557Z',
    createdAt: '2026-11-02T00:28:01.529145Z',
  };
  assert.deepEqual(parseProfile(sample), sample);
  assert.equal(parseProfile({ ...sample, phoneNumber: null }).phoneNumber, null);
  assert.throws(
    () => parseProfile({ ...sample, kycStatus: 'MORE_INFO_REQUIRED' }),
    contract(/kycStatus/),
  );
  assert.throws(
    () => parseProfile({ ...sample, accountStatus: 'FROZEN' }),
    contract(/accountStatus/),
  );
  assert.throws(() => parseProfile({ ...sample, userId: 42 }), contract(/userId/));
  assert.throws(() => parseProfile(null), contract(/not an object/));
});
