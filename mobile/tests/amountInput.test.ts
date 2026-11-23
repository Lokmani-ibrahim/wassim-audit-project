import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  compareMinor,
  formatMoney,
  minorToSafeNumber,
  parseAmountInput,
  InvalidMoneyError,
} from '../src/money';

const ok = (text: string, scale: number) => {
  const result = parseAmountInput(text, scale);
  assert.ok(
    result.ok,
    `${JSON.stringify(text)} @${scale} should parse, got ${JSON.stringify(result)}`,
  );
  return result.minor;
};
const err = (text: string, scale: number) => {
  const result = parseAmountInput(text, scale);
  assert.ok(!result.ok, `${JSON.stringify(text)} @${scale} should be rejected`);
  return result.error;
};

test('amount input: scale 2 (EUR/USD/GBP/AED/MUR)', () => {
  assert.equal(ok('10', 2), '1000');
  assert.equal(ok('10.00', 2), '1000');
  assert.equal(ok('10.5', 2), '1050');
  assert.equal(ok('10,50', 2), '1050');
  assert.equal(ok('0.01', 2), '1');
  assert.equal(ok('.5', 2), '50');
  assert.equal(ok('1.', 2), '100');
  assert.equal(ok('  7.25 ', 2), '725');
  assert.equal(ok('0007.10', 2), '710');
  assert.equal(err('10.001', 2), 'tooManyDecimals');
});

test('amount input: TND scale 3', () => {
  assert.equal(ok('1.234', 3), '1234');
  assert.equal(ok('0.005', 3), '5');
  assert.equal(ok('12', 3), '12000');
  assert.equal(ok('12.5', 3), '12500');
  assert.equal(err('1.2345', 3), 'tooManyDecimals');
});

test('amount input: scale 0 accepts integers only', () => {
  assert.equal(ok('42', 0), '42');
  assert.equal(ok('42.', 0), '42');
  assert.equal(err('42.5', 0), 'tooManyDecimals');
});

test('amount input: "," is always a decimal separator, never grouping', () => {
  // "1,000" in TND is 1.000 TND (1000 millimes), NOT one thousand dinars.
  assert.equal(ok('1,000', 3), '1000');
  assert.equal(formatMoney(ok('1,000', 3), 'TND', 'en', { scale: 3 }), 'TND 1.000');
  // In a 2-decimal currency "1,000" cannot be a valid decimal: rejected, not 1000.00.
  assert.equal(err('1,000', 2), 'tooManyDecimals');
  // Grouped thousands are not accepted at all.
  assert.equal(err('1,000.00', 2), 'invalid');
  assert.equal(err('1.000,00', 2), 'invalid');
  assert.equal(err('1 000', 2), 'invalid');
});

test('amount input: invalid inputs', () => {
  assert.equal(err('', 2), 'empty');
  assert.equal(err('   ', 2), 'empty');
  assert.equal(err('-5', 2), 'negative');
  assert.equal(err('−5', 2), 'negative');
  assert.equal(err('0', 2), 'zero');
  assert.equal(err('0.00', 2), 'zero');
  assert.equal(err('0,0', 3), 'zero');
  assert.equal(err('.', 2), 'invalid');
  assert.equal(err(',', 2), 'invalid');
  assert.equal(err('abc', 2), 'invalid');
  assert.equal(err('1.2.3', 2), 'invalid');
  assert.equal(err('1,2,3', 2), 'invalid');
  assert.equal(err('1e3', 2), 'invalid');
  assert.equal(err('+5', 2), 'invalid');
  assert.equal(err('5€', 2), 'invalid');
  assert.equal(err('٥', 2), 'invalid');
  assert.equal(err('Infinity', 2), 'invalid');
});

test('amount input: too large for the JSON-number transfer wire', () => {
  assert.equal(ok('90071992547409.91', 2), '9007199254740991');
  assert.equal(err('90071992547409.92', 2), 'tooLarge');
  assert.equal(err('99999999999999999999', 2), 'tooLarge');
});

test('amount input: rejects an invalid scale', () => {
  assert.throws(() => parseAmountInput('1', -1), InvalidMoneyError);
  assert.throws(() => parseAmountInput('1', 1.5), InvalidMoneyError);
});

test('compareMinor: exact, string-based', () => {
  assert.equal(compareMinor('1000', '999'), 1);
  assert.equal(compareMinor('999', '1000'), -1);
  assert.equal(compareMinor('0500', '500'), 0);
  assert.equal(compareMinor(7500, '7500'), 0);
  assert.equal(compareMinor('-5', '3'), -1);
  assert.equal(compareMinor('-5', '-3'), -1);
  assert.equal(compareMinor('-3', '-5'), 1);
  assert.equal(compareMinor('-0', '0'), 0);
  assert.equal(compareMinor('123456789012345678901', '123456789012345678900'), 1);
});

test('minorToSafeNumber: exact integers only', () => {
  assert.equal(minorToSafeNumber('1050'), 1050);
  assert.equal(minorToSafeNumber('9007199254740991'), Number.MAX_SAFE_INTEGER);
  assert.throws(() => minorToSafeNumber('9007199254740992'), InvalidMoneyError);
  assert.throws(() => minorToSafeNumber('1.5'), InvalidMoneyError);
});
