import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { CURRENCIES } from '../src/api/contract';
import { notImplementedActions, sandboxOnlyActions } from '../src/mocks/registry';
import {
  SANDBOX_ACCOUNT_HOLDER,
  SANDBOX_ACCOUNT_NUMBER,
  SANDBOX_BANK_NAME,
  depositReference,
  sandboxDepositInstructions,
  topUpPreviewAvailableFor,
} from '../src/topup/sandboxInstructions';

const USER_ID = '3f2a9c1e-7b4d-4e8f-9a01-23456789abcd';

test('reference: "NEOS-" + first 8 characters of the user ID, upper case', () => {
  assert.equal(depositReference(USER_ID), 'NEOS-3F2A9C1E');
  assert.equal(depositReference('  abcdef12xyz  '), 'NEOS-ABCDEF12');
});

test('reference: no usable user ID gives no reference (never a made-up one)', () => {
  assert.equal(depositReference(null), null);
  assert.equal(depositReference(undefined), null);
  assert.equal(depositReference(''), null);
  assert.equal(depositReference('abc'), null);
  assert.equal(sandboxDepositInstructions(null, 'EUR'), null);
});

test('instructions: fixed sandbox bank details for every currency, only the reference is per user', () => {
  for (const currency of CURRENCIES) {
    const details = sandboxDepositInstructions(USER_ID, currency);
    assert.deepEqual(details, {
      bankName: SANDBOX_BANK_NAME,
      accountHolder: SANDBOX_ACCOUNT_HOLDER,
      accountNumber: SANDBOX_ACCOUNT_NUMBER,
      currency,
      reference: 'NEOS-3F2A9C1E',
    });
  }
  assert.match(SANDBOX_BANK_NAME, /\(sandbox\)$/);
  assert.match(SANDBOX_ACCOUNT_HOLDER, /\(sandbox\)$/);
});

// ISO 13616 check: move the first 4 characters to the end, letters → 10..35, mod 97 must be 1.
function ibanChecksumValid(value: string): boolean {
  const compact = value.replace(/\s+/g, '').toUpperCase();
  if (!/^[A-Z]{2}\d{2}[A-Z0-9]+$/.test(compact)) return false;
  const digits = (compact.slice(4) + compact.slice(0, 4)).replace(/[A-Z]/g, (ch) =>
    String(ch.charCodeAt(0) - 55),
  );
  let remainder = 0;
  for (const digit of digits) remainder = (remainder * 10 + Number(digit)) % 97;
  return remainder === 1;
}

test('account number is obviously fake: no real country code and it fails the IBAN check', () => {
  assert.equal(SANDBOX_ACCOUNT_NUMBER, 'XX00 SAND BOX0 0000 0000 0000 00');
  assert.ok(SANDBOX_ACCOUNT_NUMBER.startsWith('XX'), 'XX is not an ISO 3166 country code');
  assert.ok(SANDBOX_ACCOUNT_NUMBER.includes('SAND BOX'));
  assert.equal(ibanChecksumValid(SANDBOX_ACCOUNT_NUMBER), false);
  // The checker itself works (a published example IBAN passes).
  assert.equal(ibanChecksumValid('GB82 WEST 1234 5698 7654 32'), true);
});

test('availability: same signal as Convert (sandbox build with a real session only)', () => {
  assert.equal(topUpPreviewAvailableFor({ sandbox: true, authMocked: false }), true);
  assert.equal(topUpPreviewAvailableFor({ sandbox: false, authMocked: false }), false);
  assert.equal(topUpPreviewAvailableFor({ sandbox: true, authMocked: true }), false);
  assert.equal(topUpPreviewAvailableFor({ sandbox: false, authMocked: true }), false);
});

test('the preview never calls a service, credits or creates a transaction', () => {
  const root = join(__dirname, '..');
  const sources = [
    'src/topup/sandboxInstructions.ts',
    'src/topup/index.ts',
    'src/screens/topup/AddMoney.tsx',
  ].map((file) => readFileSync(join(root, file), 'utf8'));
  for (const source of sources) {
    assert.doesNotMatch(source, /from '(\.\.\/)+services'/);
    assert.doesNotMatch(source, /services\./);
    assert.doesNotMatch(source, /\bfetch\(/);
    assert.doesNotMatch(source, /__NEOS_MOCK__/);
  }
});

test('release readiness: Add money is a sandbox-only preview, nothing left unimplemented', () => {
  assert.deepEqual([...notImplementedActions], []);
  assert.ok(sandboxOnlyActions.some((action) => action.startsWith('Add money (sandbox preview')));
  assert.ok(sandboxOnlyActions.some((action) => action.startsWith('Convert')));
});
