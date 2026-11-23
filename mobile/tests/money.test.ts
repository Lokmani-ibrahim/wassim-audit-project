import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  CURRENCY_SCALE,
  InvalidMoneyError,
  formatMoney,
  formatMoneyPlain,
  isZeroMinor,
  parseMinor,
  scaleFor,
  toDecimalString,
} from '../src/money';
import { CURRENCIES } from '../src/api/contract';

// Reference for safe values only: Intl on an exact JS number (minor / 10^scale is exact enough
// for the small fixtures used here; the formatter itself never does this).
function intl(minor: number, currency: string, locale: string, scale: number) {
  return new Intl.NumberFormat(locale, {
    style: 'currency',
    currency,
    minimumFractionDigits: scale,
    maximumFractionDigits: scale,
  }).format(minor / 10 ** scale);
}

test('scale table covers the 6 backend currencies, TND = 3, the API scale wins', () => {
  assert.deepEqual(Object.keys(CURRENCY_SCALE).sort(), [...CURRENCIES].sort());
  for (const currency of ['EUR', 'USD', 'GBP', 'AED', 'MUR']) assert.equal(scaleFor(currency), 2);
  assert.equal(scaleFor('TND'), 3);
  assert.equal(scaleFor('EUR', 3), 3);
  assert.equal(scaleFor('XYZ', 0), 0);
  assert.throws(() => scaleFor('XYZ'), InvalidMoneyError);
  assert.throws(() => scaleFor('EUR', 1.5), InvalidMoneyError);
  assert.throws(() => scaleFor('EUR', -1), InvalidMoneyError);
});

test('every currency and scale matches Intl exactly in en and fr (safe values)', () => {
  const samples = [0, 1, 5, 99, 100, 7500, 123456, 100000000, -1, -123456, 987654321];
  for (const locale of ['en', 'fr']) {
    for (const currency of CURRENCIES) {
      const scale = CURRENCY_SCALE[currency];
      for (const minor of samples) {
        assert.equal(
          formatMoney(minor, currency, locale),
          intl(minor, currency, locale, scale),
          `${locale} ${currency} ${minor}`,
        );
        // string and number input give the same output
        assert.equal(
          formatMoney(String(minor), currency, locale),
          formatMoney(minor, currency, locale),
        );
      }
    }
  }
});

test('explicit expectations: en and fr separators, TND 3 decimals, sign', () => {
  assert.equal(formatMoney('7500', 'EUR', 'en'), '€75.00');
  assert.equal(formatMoney('123456', 'EUR', 'en'), '€1,234.56');
  assert.equal(formatMoney('123456', 'EUR', 'fr'), '1 234,56 €');
  assert.equal(formatMoney('1234567', 'TND', 'en'), 'TND 1,234.567');
  assert.equal(formatMoney('5', 'TND', 'en'), 'TND 0.005');
  assert.equal(formatMoney('-5', 'TND', 'en'), '-TND 0.005');
  assert.equal(formatMoney('100000', 'MUR', 'en'), 'MUR 1,000.00');
  assert.equal(formatMoney('-0', 'EUR', 'en'), '€0.00');
  assert.equal(formatMoney('000123', 'USD', 'en'), '$1.23');
});

test('values above 2^53 stay exact (string path, no Number conversion)', () => {
  const big = '12345678901234567890123';
  assert.equal(formatMoney(big, 'MUR', 'en'), 'MUR 123,456,789,012,345,678,901.23');
  assert.equal(formatMoney(big, 'TND', 'fr'), '12 345 678 901 234 567 890,123 TND');
  // 2^63 + 1 (above Java long, as the backend can return from Formance)
  assert.equal(formatMoney('9223372036854775809', 'EUR', 'en'), '€92,233,720,368,547,758.09');
  assert.equal(toDecimalString('9007199254740993', 2), '90071992547409.93');
  assert.equal(formatMoney('-9007199254740993', 'USD', 'en'), '-$90,071,992,547,409.93');
});

test('the scale sent by the API overrides the table', () => {
  assert.equal(formatMoney('1234', 'EUR', 'en', { scale: 3 }), '€1.234');
  assert.equal(formatMoney('1234', 'TND', 'en', { scale: 2 }), 'TND 12.34');
});

test('invalid amounts are rejected explicitly, never rendered', () => {
  for (const bad of ['', ' 1', '1 ', '1.5', '1,5', '+1', '--1', '1e3', 'abc', '0x10', '١٢']) {
    assert.throws(() => formatMoney(bad, 'EUR', 'en'), InvalidMoneyError, JSON.stringify(bad));
  }
  for (const bad of [1.5, NaN, Infinity, -Infinity, 1e21, Number.MAX_SAFE_INTEGER + 1]) {
    assert.throws(() => formatMoney(bad, 'EUR', 'en'), InvalidMoneyError, String(bad));
  }
  assert.throws(() => formatMoney(null as unknown as string, 'EUR', 'en'), InvalidMoneyError);
  assert.throws(() => formatMoney('1', 'XYZ', 'en'), InvalidMoneyError);
  assert.equal(formatMoney(Number.MAX_SAFE_INTEGER, 'EUR', 'en'), '€90,071,992,547,409.91');
});

test('helpers: parseMinor, isZeroMinor, toDecimalString, plain fallback', () => {
  assert.deepEqual(parseMinor('-000'), { negative: false, digits: '0' });
  assert.deepEqual(parseMinor('-0042'), { negative: true, digits: '42' });
  assert.equal(isZeroMinor('0'), true);
  assert.equal(isZeroMinor('000'), true);
  assert.equal(isZeroMinor('1'), false);
  assert.equal(toDecimalString('5', 3), '0.005');
  assert.equal(toDecimalString('-123456', 2), '-1234.56');
  assert.equal(toDecimalString('42', 0), '42');
  // Used when Intl/formatToParts is unavailable on the device.
  assert.equal(formatMoneyPlain('123456789', 'EUR', 2), 'EUR 1,234,567.89');
  assert.equal(formatMoneyPlain('-5', 'TND', 3), '-TND 0.005');
});
