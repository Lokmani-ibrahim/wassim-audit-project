import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ContractError, parseWallets } from '../src/api/parse';
import type { WalletDto, WalletsResponse } from '../src/api/contract';
import { availableText, pendingText, walletsViewModel } from '../src/screens/home/walletsViewModel';

const ASSETS: Record<string, string> = {
  EUR: 'EUR/2',
  USD: 'USD/2',
  GBP: 'GBP/2',
  AED: 'AED/2',
  MUR: 'MUR/2',
  TND: 'TND/3',
};
function wallet(currency: WalletDto['currency'], overrides: Partial<WalletDto> = {}): WalletDto {
  return {
    currency,
    scale: currency === 'TND' ? 3 : 2,
    asset: ASSETS[currency]!,
    status: 'ACTIVE',
    availableMinor: '0',
    pendingMinor: '0',
    ...overrides,
  };
}
const notProvisioned = { status: 'NOT_PROVISIONED', availableMinor: null, pendingMinor: null };
// The smoke-test response from the contract (§10).
const ready: WalletsResponse = {
  walletsStatus: 'READY',
  asOf: '2026-11-02T00:28:19.310691300Z',
  wallets: [
    wallet('EUR', { availableMinor: '7500' }),
    wallet('USD'),
    wallet('GBP'),
    wallet('AED'),
    wallet('MUR', { availableMinor: '100000', pendingMinor: '2500' }),
    wallet('TND', { availableMinor: '1234567' }),
  ],
};

test('READY: one item per wallet in API order, exact amounts, pending only when non-zero', () => {
  const view = walletsViewModel(parseWallets(JSON.parse(JSON.stringify(ready))));
  assert.equal(view.banner, null);
  assert.equal(view.asOf, ready.asOf);
  assert.deepEqual(
    view.items.map((item) => item.currency),
    ['EUR', 'USD', 'GBP', 'AED', 'MUR', 'TND'],
  );
  const [eur, usd, , , mur, tnd] = view.items;
  assert.equal(availableText(eur!, 'en'), '€75.00');
  assert.equal(availableText(usd!, 'en'), '$0.00');
  assert.equal(pendingText(eur!, 'en'), null);
  assert.equal(availableText(mur!, 'en'), 'MUR 1,000.00');
  assert.equal(pendingText(mur!, 'en'), 'MUR 25.00 pending');
  assert.equal(availableText(tnd!, 'en'), 'TND 1,234.567');
});

test('PROVISIONING: banner, and NOT_PROVISIONED wallets are "Setting up…", never 0', () => {
  const response: WalletsResponse = {
    ...ready,
    walletsStatus: 'PROVISIONING',
    wallets: [
      wallet('EUR', { availableMinor: '7500' }),
      ...ready.wallets.slice(1).map((w) => ({ ...w, ...notProvisioned })),
    ],
  };
  const view = walletsViewModel(parseWallets(response));
  assert.equal(view.banner, 'provisioning');
  assert.equal(view.items[0]!.available.kind, 'amount');
  for (const item of view.items.slice(1)) {
    assert.deepEqual(item.available, { kind: 'settingUp' });
    assert.equal(item.pendingMinor, null);
    const text = availableText(item, 'en');
    assert.equal(text, 'Setting up…');
    assert.doesNotMatch(text, /\d/);
  }
});

test('NOT_ELIGIBLE: explanation banner, all 6 wallets shown as not provisioned, no amounts', () => {
  const response: WalletsResponse = {
    walletsStatus: 'NOT_ELIGIBLE',
    asOf: ready.asOf,
    wallets: ready.wallets.map((w) => ({ ...w, ...notProvisioned })),
  };
  const view = walletsViewModel(parseWallets(response));
  assert.equal(view.banner, 'notEligible');
  assert.equal(view.items.length, 6);
  assert.ok(view.items.every((item) => item.available.kind === 'settingUp'));
});

test('a wallet with an unknown status but a balance is shown as its balance (as stored)', () => {
  const response: WalletsResponse = {
    ...ready,
    wallets: [wallet('EUR', { status: 'FROZEN', availableMinor: '100' })],
  };
  const [item] = walletsViewModel(parseWallets(response)).items;
  assert.equal(availableText(item!, 'en'), '€1.00');
});

test('no total across currencies is computed anywhere in the view-model', () => {
  const view = walletsViewModel(ready);
  assert.deepEqual(Object.keys(view).sort(), ['asOf', 'banner', 'items']);
});

test('parser rejects malformed wallet responses instead of rendering them', () => {
  const clone = () =>
    JSON.parse(JSON.stringify(ready)) as Record<string, unknown> & {
      wallets: Record<string, unknown>[];
    };
  const cases: [string, (body: ReturnType<typeof clone>) => void][] = [
    ['number amount', (b) => (b.wallets[0]!.availableMinor = 7500)],
    ['decimal amount', (b) => (b.wallets[0]!.availableMinor = '75.00')],
    ['bad pending', (b) => (b.wallets[0]!.pendingMinor = 'x')],
    ['unknown currency', (b) => (b.wallets[0]!.currency = 'BTC')],
    ['bad scale', (b) => (b.wallets[0]!.scale = '2')],
    ['provisioned null amount', (b) => (b.wallets[0]!.availableMinor = null)],
    ['unknown walletsStatus', (b) => (b.walletsStatus = 'DONE')],
    ['wallets not array', (b) => (b.wallets = {} as never)],
    ['missing asOf', (b) => delete b.asOf],
  ];
  for (const [name, mutate] of cases) {
    const body = clone();
    mutate(body);
    assert.throws(() => parseWallets(body), ContractError, name);
  }
  // Values above 2^63 are valid and kept as exact strings.
  const big = clone();
  big.wallets[0]!.availableMinor = '9223372036854775809';
  assert.equal(parseWallets(big).wallets[0]!.availableMinor, '9223372036854775809');
});
