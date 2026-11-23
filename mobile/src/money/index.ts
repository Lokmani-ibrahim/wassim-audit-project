// Money display (FIN-01). Amounts are integer minor units, as the backend sends them:
// strings in GET /api/wallets, safe-integer numbers in history/transfers.
//
// No floating-point arithmetic and no BigInt: the amount is handled as a digit string, split
// at the currency scale, and grouped with the locale's own symbols. Intl is used only to learn
// the locale's separators, currency position and sign position (formatToParts on a sample
// value), never to format the amount itself, so values above 2^53 stay exact.
import { CURRENCIES, type Currency } from '../api/contract';

export class InvalidMoneyError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'InvalidMoneyError';
  }
}

// Fallback scale table (ledger/internal/taxonomy/LedgerCurrency.java). The API's own `scale`
// wins whenever it is sent (GET /api/wallets sends it).
export const CURRENCY_SCALE: Record<Currency, number> = {
  EUR: 2,
  USD: 2,
  GBP: 2,
  AED: 2,
  MUR: 2,
  TND: 3,
};

export function isCurrency(value: string): value is Currency {
  return (CURRENCIES as readonly string[]).includes(value);
}

export function scaleFor(currency: string, apiScale?: number | null): number {
  if (apiScale !== undefined && apiScale !== null) {
    if (!Number.isInteger(apiScale) || apiScale < 0 || apiScale > 18)
      throw new InvalidMoneyError(`Invalid scale ${String(apiScale)} for ${currency}`);
    return apiScale;
  }
  if (isCurrency(currency)) return CURRENCY_SCALE[currency];
  throw new InvalidMoneyError(`Unknown currency ${currency} and no scale given`);
}

export interface MinorUnits {
  negative: boolean;
  // No sign, no leading zeros ("0" for zero).
  digits: string;
}

const MINOR_PATTERN = /^-?\d+$/;

export function parseMinor(minor: string | number): MinorUnits {
  let text: string;
  if (typeof minor === 'number') {
    if (!Number.isSafeInteger(minor))
      throw new InvalidMoneyError(`Amount ${String(minor)} is not a safe integer of minor units`);
    text = String(minor);
  } else if (typeof minor === 'string') {
    if (!MINOR_PATTERN.test(minor))
      throw new InvalidMoneyError(`Amount ${JSON.stringify(minor)} is not integer minor units`);
    text = minor;
  } else {
    throw new InvalidMoneyError(`Amount of type ${typeof minor} is not supported`);
  }
  const negative = text.startsWith('-');
  const digits = (negative ? text.slice(1) : text).replace(/^0+/, '') || '0';
  return { negative: negative && digits !== '0', digits };
}

export function isZeroMinor(minor: string | number): boolean {
  return parseMinor(minor).digits === '0';
}

function split(digits: string, scale: number) {
  const padded = digits.padStart(scale + 1, '0');
  const cut = padded.length - scale;
  return { integer: padded.slice(0, cut), fraction: scale > 0 ? padded.slice(cut) : '' };
}

// Exact decimal string, e.g. ("-123456", 2) -> "-1234.56". For accessibility/debug, not display.
export function toDecimalString(minor: string | number, scale: number): string {
  const { negative, digits } = parseMinor(minor);
  const { integer, fraction } = split(digits, scale);
  return `${negative ? '-' : ''}${integer}${fraction ? `.${fraction}` : ''}`;
}

interface Grouping {
  separator: string;
  primary: number;
  secondary: number;
  // Some locales do not group a 4-digit integer part (minimumGroupingDigits = 2).
  minimumDigits: number;
}

function group(integer: string, grouping: Grouping | null): string {
  if (!grouping || integer.length < grouping.minimumDigits) return integer;
  const { primary, secondary, separator } = grouping;
  if (integer.length <= primary) return integer;
  const groups = [integer.slice(-primary)];
  let rest = integer.slice(0, -primary);
  while (rest.length > secondary) {
    groups.unshift(rest.slice(-secondary));
    rest = rest.slice(0, -secondary);
  }
  if (rest) groups.unshift(rest);
  return groups.join(separator);
}

type Part = { type: string; value: string };
interface Template {
  positive: Part[];
  negative: Part[];
  decimal: string;
  grouping: Grouping | null;
}

const templates = new Map<string, Template>();
// Sample values only teach structure; their digits are never shown.
const SAMPLE = 1234567890;

function learnTemplate(locale: string, currency: string, scale: number): Template | null {
  const key = `${locale}|${currency}|${scale}`;
  const cached = templates.get(key);
  if (cached) return cached;
  try {
    const format = new Intl.NumberFormat(locale, {
      style: 'currency',
      currency,
      minimumFractionDigits: scale,
      maximumFractionDigits: scale,
    });
    if (typeof format.formatToParts !== 'function') return null;
    const positive = format.formatToParts(SAMPLE) as Part[];
    const negative = format.formatToParts(-SAMPLE) as Part[];
    const integers = positive.filter((part) => part.type === 'integer');
    if (integers.length === 0) return null;
    const separator = positive.find((part) => part.type === 'group')?.value;
    let grouping: Grouping | null = null;
    if (separator !== undefined && integers.length > 1) {
      const lengths = integers.map((part) => part.value.length);
      const primary = lengths[lengths.length - 1]!;
      const secondary = lengths.length > 2 ? lengths[lengths.length - 2]! : primary;
      const fourDigits = format.formatToParts(1234) as Part[];
      const groupsFour = fourDigits.some((part) => part.type === 'group');
      grouping = { separator, primary, secondary, minimumDigits: groupsFour ? 0 : primary + 2 };
    }
    const decimal = positive.find((part) => part.type === 'decimal')?.value ?? '.';
    const template: Template = { positive, negative, decimal, grouping };
    templates.set(key, template);
    return template;
  } catch {
    return null;
  }
}

// Used when Intl.NumberFormat / formatToParts is unavailable: "EUR 1,234.56", "-TND 0.005".
export function formatMoneyPlain(minor: string | number, currency: string, scale: number): string {
  const { negative, digits } = parseMinor(minor);
  const { integer, fraction } = split(digits, scale);
  const grouped = group(integer, { separator: ',', primary: 3, secondary: 3, minimumDigits: 0 });
  return `${negative ? '-' : ''}${currency} ${grouped}${fraction ? `.${fraction}` : ''}`;
}

export interface FormatMoneyOptions {
  // The API's scale for this amount (preferred over the fallback table).
  scale?: number | null;
}

export function formatMoney(
  minor: string | number,
  currency: string,
  locale: string,
  options: FormatMoneyOptions = {},
): string {
  const scale = scaleFor(currency, options.scale);
  const { negative, digits } = parseMinor(minor);
  const template = learnTemplate(locale, currency, scale);
  if (!template) return formatMoneyPlain(minor, currency, scale);
  const { integer, fraction } = split(digits, scale);
  let out = '';
  let integerWritten = false;
  for (const part of negative ? template.negative : template.positive) {
    switch (part.type) {
      case 'integer':
        if (!integerWritten) out += group(integer, template.grouping);
        integerWritten = true;
        break;
      case 'group':
        break;
      case 'decimal':
        if (scale > 0) out += template.decimal;
        break;
      case 'fraction':
        out += fraction;
        break;
      default:
        // currency, literal (spaces), minusSign, plusSign...
        out += part.value;
    }
  }
  return out;
}

// Exact comparison of two integer minor-unit amounts (strings or safe-integer numbers), with no
// float and no BigInt: sign first, then digit count, then lexicographic order. -1 / 0 / 1.
export function compareMinor(a: string | number, b: string | number): -1 | 0 | 1 {
  const left = parseMinor(a);
  const right = parseMinor(b);
  if (left.negative !== right.negative) return left.negative ? -1 : 1;
  let magnitude: -1 | 0 | 1;
  if (left.digits.length !== right.digits.length)
    magnitude = left.digits.length < right.digits.length ? -1 : 1;
  else if (left.digits === right.digits) magnitude = 0;
  else magnitude = left.digits < right.digits ? -1 : 1;
  if (!left.negative || magnitude === 0) return magnitude;
  return magnitude === 1 ? -1 : 1;
}

const MAX_SAFE_MINOR = String(Number.MAX_SAFE_INTEGER);

// The transfer API takes amountMinor as a JSON number (Java long). Only exact safe integers.
export function minorToSafeNumber(minor: string): number {
  const { negative, digits } = parseMinor(minor);
  if (compareMinor(digits, MAX_SAFE_MINOR) > 0)
    throw new InvalidMoneyError(`Amount ${minor} exceeds the safe integer range`);
  // Integer digit string within 2^53-1: Number() is exact here (no fractional part involved).
  const value = Number(digits);
  return negative ? -value : value;
}

export type AmountInputError =
  'empty' | 'invalid' | 'negative' | 'zero' | 'tooManyDecimals' | 'tooLarge';
export type AmountInput = { ok: true; minor: string } | { ok: false; error: AmountInputError };

// Parses what the user typed into integer minor units at the currency scale. String-based: the
// integer and fraction digits are concatenated, never multiplied. Accepts ONE decimal separator,
// "." or ",", and no grouping separators. A "," is therefore always the decimal separator:
// "1,000" in TND (scale 3) is 1.000 TND, and in EUR (scale 2) it is rejected (too many
// decimals). The UI shows the parsed amount formatted live to remove any doubt.
export function parseAmountInput(text: string, scale: number): AmountInput {
  if (!Number.isInteger(scale) || scale < 0 || scale > 18)
    throw new InvalidMoneyError(`Invalid scale ${String(scale)}`);
  const value = text.trim();
  if (value === '') return { ok: false, error: 'empty' };
  if (/^[-\u2212]/.test(value)) return { ok: false, error: 'negative' };
  const match = /^(\d*)(?:[.,](\d*))?$/.exec(value);
  if (!match) return { ok: false, error: 'invalid' };
  const integer = match[1] ?? '';
  const fraction = match[2] ?? '';
  if (integer === '' && fraction === '') return { ok: false, error: 'invalid' };
  if (fraction.length > scale) return { ok: false, error: 'tooManyDecimals' };
  const digits = `${integer}${fraction.padEnd(scale, '0')}`.replace(/^0+/, '') || '0';
  if (digits === '0') return { ok: false, error: 'zero' };
  if (compareMinor(digits, MAX_SAFE_MINOR) > 0) return { ok: false, error: 'tooLarge' };
  return { ok: true, minor: digits };
}
