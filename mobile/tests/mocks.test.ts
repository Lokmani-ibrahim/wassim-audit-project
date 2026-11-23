import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { MOCK_FEATURES, type MockFeature } from '../src/mockFeatures';
import { composeServices } from '../src/services/compose';
import { unavailableMoneyServices } from '../src/services/unavailable';
import { FeatureUnavailableError } from '../src/services/errors';
import { createSession } from '../src/services/session';
import { createDemoServices, type DemoStorage } from '../src/mocks/demo';
import { createMockKycCapture } from '../src/mocks/kycCapture';
import { createMockNotifications } from '../src/mocks/notifications';
import { createMockWallets } from '../src/mocks/wallets';
import { mockRegistry } from '../src/mocks/registry';
import { parseNotificationPage, parseWallets } from '../src/api/parse';
import type { Profile, Services } from '../src/types/domain';

const root = join(__dirname, '..');
const MARKER = '__NEOS_MOCK__:';

function tagged(): Services {
  const stub = () => ({}) as never;
  return {
    auth: { signIn: stub, restore: stub, hasStoredSession: stub, signOut: stub },
    account: { get: stub },
    onboarding: { submit: stub },
    kyc: { submit: stub, refresh: stub },
    kycCapture: { verify: stub },
    wallets: { list: stub },
    notifications: { list: stub },
    transfers: { lookup: stub, send: stub },
    transactions: { list: stub },
    fx: { quote: stub, convert: stub, findByKey: stub },
  };
}
// Which Services slots each feature controls.
const slots: Record<MockFeature, (keyof Services)[]> = {
  auth: ['auth', 'account'],
  onboarding: ['onboarding'],
  kyc: ['kyc'],
  kycCapture: ['kycCapture'],
  wallets: ['wallets'],
  notifications: ['notifications'],
};

test('composition: each feature is mocked or real strictly according to the mock set', () => {
  const real = tagged();
  const mock = tagged();
  for (const feature of MOCK_FEATURES) {
    const composed = composeServices(new Set([feature]), real, mock);
    for (const other of MOCK_FEATURES) {
      for (const slot of slots[other]) {
        assert.equal(
          composed[slot],
          other === feature ? mock[slot] : real[slot],
          `${feature} set: slot ${slot}`,
        );
      }
    }
  }
});

test('composition: empty set is all real, full set is all mock, no mock bundle returns real', () => {
  const real = tagged();
  const mock = tagged();
  const none = composeServices(new Set(), real, mock);
  const all = composeServices(new Set(MOCK_FEATURES), real, mock);
  for (const slot of Object.keys(real) as (keyof Services)[]) {
    assert.equal(none[slot], real[slot]);
    if (slot === 'transfers' || slot === 'transactions' || slot === 'fx') continue;
    assert.equal(all[slot], mock[slot]);
  }
  assert.equal(composeServices(new Set(MOCK_FEATURES), real, null), real);
});

test('composition: transfers and history are never mocked; with mocked auth they are unavailable', async () => {
  const real = tagged();
  const mock = tagged();
  for (const feature of MOCK_FEATURES) {
    const composed = composeServices(new Set([feature]), real, mock);
    const expectedTransfers =
      feature === 'auth' ? unavailableMoneyServices.transfers : real.transfers;
    const expectedTransactions =
      feature === 'auth' ? unavailableMoneyServices.transactions : real.transactions;
    assert.equal(composed.transfers, expectedTransfers, `${feature}: transfers`);
    assert.equal(composed.transactions, expectedTransactions, `${feature}: transactions`);
    // FX moves money: never mocked either (contract §14).
    const expectedFx = feature === 'auth' ? unavailableMoneyServices.fx : real.fx;
    assert.equal(composed.fx, expectedFx, `${feature}: fx`);
  }
  for (const call of [
    () => unavailableMoneyServices.transfers.lookup('a@b.c'),
    () =>
      unavailableMoneyServices.transfers.send({
        destinationUserId: 'x',
        currency: 'EUR',
        amountMinor: 1,
        idempotencyKey: 'k',
      }),
    () => unavailableMoneyServices.transactions.list('EUR', null, 20),
  ])
    await assert.rejects(call(), (error: unknown) => error instanceof FeatureUnavailableError);
});

test('mock wallets match the GET /api/wallets contract; notifications match GET /api/notifications; capture keeps its demo values', async () => {
  const response = await createMockWallets().list();
  // Same validator the real service applies: the mock must be a valid wire response.
  assert.deepEqual(parseWallets(response), response);
  assert.equal(response.walletsStatus, 'READY');
  assert.deepEqual(
    response.wallets.map((wallet) => wallet.currency),
    ['EUR', 'USD', 'GBP', 'AED', 'MUR', 'TND'],
  );
  for (const wallet of response.wallets) {
    assert.equal(wallet.availableMinor, '0');
    assert.equal(wallet.pendingMinor, '0');
    assert.equal(wallet.status, 'ACTIVE');
    assert.equal(wallet.scale, wallet.currency === 'TND' ? 3 : 2);
  }
  const feed = await createMockNotifications().list(null, 20);
  // Same validator the real service applies: the mock must be a valid wire response.
  assert.deepEqual(parseNotificationPage(feed), feed);
  assert.equal(feed.items.length, 3);
  assert.equal(feed.items.filter((item) => item.testCredit).length, 1);
  assert.deepEqual(await createMockKycCapture(0).verify(), { kind: 'submitted' });
  assert.deepEqual(await createMockKycCapture(0, () => ({ kind: 'incomplete' })).verify(), {
    kind: 'incomplete',
  });
});

test('real auth + mocked kyc: kyc mock works on the shared session and never persists demo state', async () => {
  const data = new Map<string, unknown>();
  const store: DemoStorage = {
    async read<T>(key: string) {
      return (data.get(key) as T) || null;
    },
    async write(key, value) {
      data.set(key, value);
    },
    async remove(key) {
      data.delete(key);
    },
  };
  const session = createSession();
  const { services, controls } = createDemoServices(store, 0, { session, persist: false });
  await assert.rejects(services.kyc.submit(), /sessionError/);
  const realProfile: Profile = {
    identity: { subject: 'kc-1', username: 'Real', email: 'real@example.com' },
    onboarded: true,
    kyc: 'NOT_STARTED',
    account: 'PENDING_KYC',
  };
  session.profile = realProfile; // as the real auth service would set it
  controls.setOutcome('PENDING');
  assert.equal((await services.kyc.submit()).kyc, 'PENDING');
  controls.setOutcome('APPROVED');
  const approved = await services.kyc.refresh();
  assert.equal(approved.account, 'ACTIVE');
  assert.equal(session.profile?.identity.subject, 'kc-1');
  assert.equal(data.size, 0, 'nothing written to demo storage when auth is real');
  await assert.rejects(controls.expire(), /only be simulated when auth is mocked/);
});

test('registry: exactly one entry per mockable feature, each with a unique marker in its module', () => {
  assert.deepEqual(
    mockRegistry.map((entry) => entry.feature),
    [...MOCK_FEATURES],
  );
  assert.equal(new Set(mockRegistry.map((entry) => entry.marker)).size, MOCK_FEATURES.length);
  for (const entry of mockRegistry) {
    assert.equal(entry.marker, `${MARKER}${entry.feature}`);
    assert.equal(typeof entry.realAvailable, 'boolean');
    assert.ok(entry.fakes.length > 0 && entry.replacedBy.length > 0);
    const source = readFileSync(join(root, entry.module), 'utf8');
    assert.ok(source.includes(`'${entry.marker}'`), `${entry.module} must contain ${entry.marker}`);
  }
});

test('registry: no unregistered marker exists anywhere in src/mocks', () => {
  const registered = new Set(mockRegistry.map((entry) => entry.marker));
  const found = new Set<string>();
  for (const file of readdirSync(join(root, 'src', 'mocks'))) {
    const source = readFileSync(join(root, 'src', 'mocks', file), 'utf8');
    for (const match of source.matchAll(/__NEOS_MOCK__:\w+/g)) found.add(match[0]);
  }
  // registry.ts builds expected markers from constants and only references them by name.
  assert.deepEqual([...found].sort(), [...registered].sort());
});
