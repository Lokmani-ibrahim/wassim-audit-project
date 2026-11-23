import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createDemoServices, type DemoStorage } from '../src/mocks/demo';
import { routeFor, validPhone, previousRoute } from '../src/navigation/state';
import type { Profile } from '../src/types/domain';
import { withTimeout } from '../src/services/timeout';
import { authErrors } from '../src/utils/authValidation';
import { DEMO_FALLBACK_EMAIL } from '../src/mocks/demo';

function setup() {
  const data = new Map<string, unknown>();
  const storage: DemoStorage = {
    async read<T>(key: string) {
      return (data.get(key) as T) || null;
    },
    async write(key, value) {
      data.set(key, structuredClone(value));
    },
    async remove(key) {
      data.delete(key);
    },
  };
  return { ...createDemoServices(storage, 0), storage };
}
test('complete lifecycle persists and can be reset for another meeting', async () => {
  const { services, controls, storage } = setup();
  assert.equal(routeFor(await services.auth.restore()), 'welcome');
  assert.equal(routeFor(await services.auth.signIn()), 'intro');
  assert.equal(routeFor(await services.onboarding.submit('')), 'kycIntro');
  const verified = await services.kyc.submit();
  assert.equal(verified.account, 'ACTIVE');
  assert.equal(routeFor(verified), 'approved');
  assert.equal(routeFor(verified, true), 'home');
  const restarted = createDemoServices(storage, 0);
  assert.equal(routeFor(await restarted.services.auth.restore(), true), 'home');
  await controls.reset();
  assert.equal(await services.auth.restore(), null);
});
test('pending, rejected and more-info states never activate the account', async () => {
  for (const outcome of ['PENDING', 'REJECTED', 'MORE_INFO_REQUIRED'] as const) {
    const { services, controls } = setup();
    await services.auth.signIn();
    await services.onboarding.submit('');
    controls.setOutcome(outcome);
    const profile = await services.kyc.submit();
    assert.equal(profile.account, 'PENDING_KYC');
    assert.equal(
      routeFor(profile),
      { PENDING: 'pending', REJECTED: 'rejected', MORE_INFO_REQUIRED: 'moreInfo' }[outcome],
    );
    controls.setOutcome('APPROVED');
    assert.equal(routeFor(await services.kyc.refresh()), 'approved');
  }
});
test('network failure preserves progress and retry succeeds', async () => {
  const { services, controls } = setup();
  await services.auth.signIn();
  controls.failNext();
  await assert.rejects(services.onboarding.submit(''), /networkError/);
  assert.equal((await services.account.get()).onboarded, false);
  assert.equal((await services.onboarding.submit('')).onboarded, true);
});
test('expired sessions cannot perform authenticated operations', async () => {
  const { services, controls } = setup();
  await services.auth.signIn();
  await assert.rejects(controls.expire(), /sessionError/);
  await assert.rejects(services.kyc.submit(), /sessionError/);
  assert.equal(await services.auth.restore(), null);
});
test('backend restrictions take priority over approved KYC and local acknowledgements', () => {
  const profile: Profile = {
    identity: { subject: 'test', username: 'Test', email: 'test@example.com' },
    onboarded: true,
    kyc: 'APPROVED',
    account: 'SUSPENDED',
  };
  assert.equal(routeFor(profile, true), 'restricted');
  assert.equal(routeFor({ ...profile, account: 'CLOSED' }, true), 'restricted');
  assert.equal(routeFor({ ...profile, account: 'PENDING_KYC' }), 'pending');
  assert.equal(routeFor({ ...profile, account: 'PENDING_KYC', kyc: 'IN_REVIEW' }), 'pending');
});
test('optional phone and back routes follow the actual onboarding contract', () => {
  assert.equal(validPhone(''), true);
  assert.equal(validPhone('+230 5 123 4567'), true);
  assert.equal(validPhone('abc'), false);
  assert.equal(validPhone('-------'), false);
  assert.equal(validPhone('1'.repeat(33)), false);
  assert.equal(previousRoute.review, 'details');
  // The simulated document/selfie screens are gone: the Sumsub SDK has its own navigation.
  assert.equal(previousRoute.kycIntro, undefined);
  assert.equal(previousRoute.home, undefined);
});

test('verification cannot bypass onboarding', async () => {
  const { services } = setup();
  await services.auth.signIn();
  await assert.rejects(services.kyc.submit(), /kycError/);
  assert.equal((await services.account.get()).account, 'PENDING_KYC');
});

test('a stalled identity-provider request returns a retryable network error', async () => {
  await assert.rejects(withTimeout(new Promise(() => {}), 5), /networkError/);
  assert.equal(await withTimeout(Promise.resolve('ready'), 20), 'ready');
});

test('signup stores entered email across onboarding, verification and restart, never passwords', async () => {
  const { services, storage } = setup();
  const created = await services.auth.signIn({
    intent: 'create',
    email: '  abir@example.com ',
    password: 'sample-pass',
    confirmPassword: 'sample-pass',
  });
  assert.equal(created.identity.email, 'abir@example.com');
  assert.equal(created.onboarded, false);
  assert.equal((await services.onboarding.submit('')).identity.email, 'abir@example.com');
  assert.equal((await services.kyc.submit()).identity.email, 'abir@example.com');
  const restarted = createDemoServices(storage, 0);
  assert.equal((await restarted.services.auth.restore())?.identity.email, 'abir@example.com');
  const saved = JSON.stringify(await storage.read('neos.demo.v1'));
  assert.equal(saved.includes('sample-pass'), false);
  assert.equal(saved.includes('password'), false);
});

test('signout retains account lifecycle, sign-in resumes it, and another email never inherits it', async () => {
  const { services, storage } = setup();
  const input = {
    intent: 'signIn',
    email: 'returning@example.com',
    password: 'sample-pass',
  } as const;
  await services.auth.signIn(input);
  await services.onboarding.submit('');
  await services.kyc.submit();
  await services.auth.signOut();
  assert.equal(await createDemoServices(storage, 0).services.auth.restore(), null);
  assert.equal(routeFor(await services.auth.signIn(input), true), 'home');
  assert.equal(
    routeFor(await services.auth.signIn({ ...input, email: 'different@example.com' })),
    'intro',
  );
});

test('reset removes custom email and restores exact fallback only for internal seed authentication', async () => {
  const { services, controls, storage } = setup();
  await services.auth.signIn({
    intent: 'create',
    email: 'custom@example.com',
    password: 'sample',
    confirmPassword: 'sample',
  });
  await controls.reset();
  assert.equal(await services.auth.restore(), null);
  assert.equal(await storage.read('neos.demo.v1'), null);
  assert.equal(DEMO_FALLBACK_EMAIL, 'khalil@gmail.com');
  assert.equal((await services.auth.signIn()).identity.email, 'khalil@gmail.com');
});

test('legacy identity migrates to fallback; restricted and review states survive sign-in', async () => {
  const { services, storage } = setup();
  const legacy: Profile = {
    identity: { subject: 'demo-alex', email: 'old-seed@example.com', username: 'Legacy' },
    onboarded: true,
    kyc: 'APPROVED',
    account: 'SUSPENDED',
  };
  await storage.write('neos.demo.v1', legacy);
  assert.equal((await services.auth.restore())?.identity.email, 'khalil@gmail.com');
  const input = { intent: 'signIn', email: 'khalil@gmail.com', password: 'sample' } as const;
  assert.equal(routeFor(await services.auth.signIn(input), true), 'restricted');
  for (const kyc of ['PENDING', 'IN_REVIEW', 'REJECTED', 'MORE_INFO_REQUIRED'] as const) {
    await storage.write('neos.demo.v1', {
      ...legacy,
      identity: { subject: 'demo:test', username: 'Khalil', email: input.email },
      kyc,
      account: 'PENDING_KYC',
    });
    assert.equal((await services.auth.signIn(input)).kyc, kyc);
  }
});

test('both auth forms validate before persisting; signup requires matching passwords', async () => {
  const { services, storage } = setup();
  const input = {
    intent: 'create',
    email: 'valid@example.com',
    password: 'sample',
    confirmPassword: 'sample',
  } as const;
  assert.deepEqual(authErrors(input), {});
  assert.equal(authErrors({ ...input, password: ' ' }).password, 'passwordError');
  assert.equal(
    authErrors({ ...input, confirmPassword: 'different' }).confirmPassword,
    'passwordMismatch',
  );
  await assert.rejects(services.auth.signIn({ ...input, email: 'invalid' }), /emailError/);
  await assert.rejects(services.auth.signIn({ ...input, confirmPassword: '' }), /passwordMismatch/);
  await assert.rejects(
    services.auth.signIn({ ...input, intent: 'signIn', password: '' }),
    /passwordError/,
  );
  assert.equal(await storage.read('neos.demo.v1'), null);
  assert.equal(previousRoute.createAccount, 'welcome');
  assert.equal(previousRoute.signIn, 'welcome');
});
