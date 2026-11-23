import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  ACKNOWLEDGED_ON_SESSION_START,
  acknowledgedAfterUpdate,
  isHomeEligible,
  routeFor,
} from '../src/navigation/state';
import { profileFromBackend } from '../src/api/profileMapping';
import type { AccountStatus, BackendKycStatus } from '../src/api/contract';
import type { Profile } from '../src/types/domain';

const identity = { subject: 'kc-1', username: 'neos-sender', email: 'neos-sender@neos.local' };
function backend(kycStatus: BackendKycStatus, accountStatus: AccountStatus): Profile {
  return profileFromBackend(identity, {
    userId: '7bf8f8ad-1133-419d-9a13-9c3883690de0',
    email: identity.email,
    phoneNumber: null,
    accountStatus,
    kycStatus,
    onboardedAt: '2026-11-02T00:28:01Z',
    createdAt: '2026-11-02T00:28:01Z',
  });
}
const notOnboarded = profileFromBackend(identity, null);

// Mirrors how useJourney drives the gate: a session starts (sign-in/restore), the status is
// updated by KYC submit/refresh or a profile re-read, and "Go to home" acknowledges.
function session(start: Profile | null) {
  let acknowledged = ACKNOWLEDGED_ON_SESSION_START;
  let profile = start;
  return {
    route: () => routeFor(profile, acknowledged),
    update(next: Profile | null) {
      acknowledged = acknowledgedAfterUpdate(profile, next, acknowledged);
      profile = next;
    },
    goHome() {
      acknowledged = true;
    },
  };
}

test('returning approved user goes straight to Home (no verified screens)', () => {
  const s = session(backend('APPROVED', 'ACTIVE'));
  assert.equal(s.route(), 'home');
  // Re-reading the same status (Profile focus, foreground, "check for an update") changes nothing.
  s.update(backend('APPROVED', 'ACTIVE'));
  assert.equal(s.route(), 'home');
});

test('KYC turning APPROVED during the session shows the verified screens exactly once', () => {
  const s = session(notOnboarded);
  assert.equal(s.route(), 'intro');
  s.update(backend('NOT_STARTED', 'PENDING_KYC')); // onboarding submitted
  assert.equal(s.route(), 'kycIntro');
  s.update(backend('APPROVED', 'ACTIVE')); // KYC submitted, transition observed
  assert.equal(s.route(), 'approved');
  s.goHome(); // "Continue" -> ready -> "Go to home"
  assert.equal(s.route(), 'home');
  s.update(backend('APPROVED', 'ACTIVE'));
  assert.equal(s.route(), 'home', 'never shown a second time for the same approval');
});

test('pending at session start, approved later in the session: verified screens once', () => {
  const s = session(backend('PENDING', 'PENDING_KYC'));
  assert.equal(s.route(), 'pending');
  s.update(backend('PENDING', 'PENDING_KYC')); // "Check for an update", still pending
  assert.equal(s.route(), 'pending');
  s.update(backend('APPROVED', 'ACTIVE'));
  assert.equal(s.route(), 'approved');
});

test('APPROVED but account not yet ACTIVE, then activated in session: transition is the activation', () => {
  const s = session(backend('APPROVED', 'PENDING_KYC'));
  assert.equal(s.route(), 'pending');
  s.update(backend('APPROVED', 'ACTIVE'));
  assert.equal(s.route(), 'approved');
});

test('suspension in the authenticated area still wins over acknowledgement', () => {
  const s = session(backend('APPROVED', 'ACTIVE'));
  s.update(backend('APPROVED', 'SUSPENDED'));
  assert.equal(s.route(), 'restricted');
});

test('rule details: only an eligible-after / not-eligible-before change resets acknowledgement', () => {
  const approved = backend('APPROVED', 'ACTIVE');
  const pending = backend('PENDING', 'PENDING_KYC');
  assert.equal(isHomeEligible(approved), true);
  assert.equal(isHomeEligible(pending), false);
  assert.equal(isHomeEligible(null), false);
  assert.equal(acknowledgedAfterUpdate(pending, approved, true), false);
  assert.equal(acknowledgedAfterUpdate(null, approved, true), false);
  assert.equal(acknowledgedAfterUpdate(approved, approved, true), true);
  assert.equal(acknowledgedAfterUpdate(approved, approved, false), false);
  assert.equal(acknowledgedAfterUpdate(pending, pending, true), true);
  assert.equal(acknowledgedAfterUpdate(approved, null, true), true);
});
