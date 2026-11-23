// Runtime shape checks for responses the app routes or renders money from. A response that
// does not match the documented contract is rejected (ContractError) rather than rendered.
import { AppError } from '../services/errors';
import {
  ACCOUNT_STATUSES,
  BACKEND_KYC_STATUSES,
  CURRENCIES,
  FX_CONVERSION_STATUSES,
  type AccountStatus,
  type AuthMeResponse,
  type BackendKycStatus,
  type Currency,
  type CustomerProfileResponse,
  type FxConversion,
  type FxQuote,
  type InternalTransferResponse,
  type KycSdkTokenResponse,
  type NotificationItem,
  type NotificationPage,
  type RecipientLookupResponse,
  type TransactionDirection,
  type TransactionItem,
  type TransactionPage,
  type TransactionStatus,
  type TransactionType,
  type WalletDto,
  type WalletsResponse,
  type WalletsStatus,
} from './contract';

export class ContractError extends AppError {
  constructor(public readonly detail: string) {
    super('unexpectedError');
    this.name = 'ContractError';
  }
}

type Json = Record<string, unknown>;
const MINOR_UNITS = /^-?\d+$/;
const WALLETS_STATUSES: readonly WalletsStatus[] = ['READY', 'PROVISIONING', 'NOT_ELIGIBLE'];

function object(value: unknown, what: string): Json {
  if (typeof value !== 'object' || value === null || Array.isArray(value))
    throw new ContractError(`${what} is not an object`);
  return value as Json;
}
function string(source: Json, key: string, what: string): string {
  const value = source[key];
  if (typeof value !== 'string') throw new ContractError(`${what}.${key} is not a string`);
  return value;
}
function nullableString(source: Json, key: string, what: string): string | null {
  const value = source[key];
  if (value === null || value === undefined) return null;
  if (typeof value !== 'string') throw new ContractError(`${what}.${key} is not a string`);
  return value;
}
function oneOf<T extends string>(value: unknown, allowed: readonly T[], what: string): T {
  if (typeof value !== 'string' || !(allowed as readonly string[]).includes(value))
    throw new ContractError(`${what} has unexpected value ${JSON.stringify(value)}`);
  return value as T;
}
function minorUnits(source: Json, key: string, what: string): string | null {
  const value = nullableString(source, key, what);
  if (value !== null && !MINOR_UNITS.test(value))
    throw new ContractError(`${what}.${key} is not an integer string`);
  return value;
}

export function parseAuthMe(value: unknown): AuthMeResponse {
  const body = object(value, 'auth/me');
  return {
    subject: string(body, 'subject', 'auth/me'),
    username: string(body, 'username', 'auth/me'),
    email: string(body, 'email', 'auth/me'),
    onboardingEligibility: oneOf(
      body.onboardingEligibility,
      ['READY', 'EXISTING', 'CONFLICT'] as const,
      'auth/me.onboardingEligibility',
    ),
  };
}

export function parseProfile(value: unknown): CustomerProfileResponse {
  const body = object(value, 'profile');
  return {
    userId: string(body, 'userId', 'profile'),
    email: string(body, 'email', 'profile'),
    phoneNumber: nullableString(body, 'phoneNumber', 'profile'),
    accountStatus: oneOf<AccountStatus>(
      body.accountStatus,
      ACCOUNT_STATUSES,
      'profile.accountStatus',
    ),
    kycStatus: oneOf<BackendKycStatus>(body.kycStatus, BACKEND_KYC_STATUSES, 'profile.kycStatus'),
    onboardedAt: nullableString(body, 'onboardedAt', 'profile'),
    createdAt: string(body, 'createdAt', 'profile'),
  };
}

function parseWallet(value: unknown, index: number): WalletDto {
  const what = `wallets[${index}]`;
  const body = object(value, what);
  const scale = body.scale;
  if (typeof scale !== 'number' || !Number.isInteger(scale) || scale < 0 || scale > 18)
    throw new ContractError(`${what}.scale is not a valid scale`);
  const status = string(body, 'status', what);
  const availableMinor = minorUnits(body, 'availableMinor', what);
  const pendingMinor = minorUnits(body, 'pendingMinor', what);
  if (status !== 'NOT_PROVISIONED' && availableMinor === null)
    throw new ContractError(`${what} is provisioned but has no availableMinor`);
  return {
    currency: oneOf<Currency>(body.currency, CURRENCIES, `${what}.currency`),
    scale,
    asset: string(body, 'asset', what),
    status,
    availableMinor,
    pendingMinor,
  };
}

export function parseWallets(value: unknown): WalletsResponse {
  const body = object(value, 'wallets response');
  if (!Array.isArray(body.wallets)) throw new ContractError('wallets is not an array');
  return {
    walletsStatus: oneOf(body.walletsStatus, WALLETS_STATUSES, 'walletsStatus'),
    asOf: string(body, 'asOf', 'wallets response'),
    wallets: body.wallets.map(parseWallet),
  };
}

// §12 — POST /api/recipients/lookup.
export function parseRecipientLookup(value: unknown): RecipientLookupResponse {
  const body = object(value, 'recipient lookup');
  const recipientUserId = string(body, 'recipientUserId', 'recipient lookup');
  if (!recipientUserId) throw new ContractError('recipient lookup.recipientUserId is empty');
  return { recipientUserId, maskedEmail: string(body, 'maskedEmail', 'recipient lookup') };
}

function safeInteger(source: Json, key: string, what: string, positive: boolean): number {
  const value = source[key];
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || (positive && value <= 0))
    throw new ContractError(`${what}.${key} is not a ${positive ? 'positive ' : ''}safe integer`);
  return value;
}

// §5 — POST /api/transfers/internal, 200. A 200 means posted; a malformed 200 body is treated
// by the caller as an UNCERTAIN outcome (never as a failure), because the money may have moved.
export function parseTransfer(value: unknown): InternalTransferResponse {
  const body = object(value, 'transfer');
  const reference = string(body, 'reference', 'transfer');
  if (!reference) throw new ContractError('transfer.reference is empty');
  return { transactionId: safeInteger(body, 'transactionId', 'transfer', false), reference };
}

const TRANSACTION_TYPES: readonly TransactionType[] = [
  'INCOMING_PAYMENT',
  'TRANSFER',
  'FEE',
  'PAYOUT',
  'CONVERSION',
];
const DIRECTIONS: readonly TransactionDirection[] = ['CREDIT', 'DEBIT'];
const TRANSACTION_STATUSES: readonly TransactionStatus[] = ['COMPLETED', 'REVERSED'];

function parseTransaction(value: unknown, index: number): TransactionItem {
  const what = `items[${index}]`;
  const body = object(value, what);
  return {
    transactionId: safeInteger(body, 'transactionId', what, false),
    type: oneOf(body.type, TRANSACTION_TYPES, `${what}.type`),
    direction: oneOf(body.direction, DIRECTIONS, `${what}.direction`),
    currency: oneOf<Currency>(body.currency, CURRENCIES, `${what}.currency`),
    // Always positive on the wire (§6); the sign comes from direction.
    amountMinor: safeInteger(body, 'amountMinor', what, true),
    status: oneOf(body.status, TRANSACTION_STATUSES, `${what}.status`),
    reference: string(body, 'reference', what),
    occurredAt: string(body, 'occurredAt', what),
  };
}

// §6 — GET /api/transactions. hasMore drives paging (pages can be empty with hasMore true).
export function parseTransactionPage(value: unknown): TransactionPage {
  const body = object(value, 'transactions page');
  if (!Array.isArray(body.items))
    throw new ContractError('transactions page.items is not an array');
  if (typeof body.hasMore !== 'boolean')
    throw new ContractError('transactions page.hasMore is not a boolean');
  return {
    items: body.items.map(parseTransaction),
    hasMore: body.hasMore,
    nextCursor: nullableString(body, 'nextCursor', 'transactions page'),
  };
}

// §13 — GET /api/notifications. An item with a type or currency this app version does not know is
// dropped (the feed may grow before the app does); any other malformed field rejects the page.
function parseNotification(value: unknown, index: number): NotificationItem | null {
  const what = `notifications[${index}]`;
  const body = object(value, what);
  if (
    !(TRANSACTION_TYPES as readonly unknown[]).includes(body.type) ||
    !(CURRENCIES as readonly unknown[]).includes(body.currency)
  )
    return null;
  const id = string(body, 'id', what);
  if (id.trim() === '') throw new ContractError(`${what}.id is empty`);
  const createdAt = string(body, 'createdAt', what);
  if (Number.isNaN(Date.parse(createdAt)))
    throw new ContractError(`${what}.createdAt is not a timestamp`);
  if (typeof body.testCredit !== 'boolean')
    throw new ContractError(`${what}.testCredit is not a boolean`);
  return {
    id,
    type: body.type as TransactionType,
    direction: oneOf(body.direction, DIRECTIONS, `${what}.direction`),
    currency: body.currency as Currency,
    amountMinor: safeInteger(body, 'amountMinor', what, true),
    transactionId: safeInteger(body, 'transactionId', what, false),
    reference: string(body, 'reference', what),
    testCredit: body.testCredit,
    createdAt,
  };
}

export function parseNotificationPage(value: unknown): NotificationPage {
  const body = object(value, 'notifications page');
  if (!Array.isArray(body.items))
    throw new ContractError('notifications page.items is not an array');
  if (typeof body.hasMore !== 'boolean')
    throw new ContractError('notifications page.hasMore is not a boolean');
  const nextCursor = nullableString(body, 'nextCursor', 'notifications page');
  // hasMore without a cursor cannot be followed: treat it as the end.
  const hasMore = body.hasMore && !!nextCursor;
  return {
    items: body.items
      .map(parseNotification)
      .filter((item): item is NotificationItem => item !== null),
    hasMore,
    nextCursor: hasMore ? nextCursor : null,
  };
}

// POST /api/kyc/sdk-token, 200. The token is a credential: error details name the field only and
// never include its value (no JSON.stringify of the body or the token here).
export function parseKycSdkToken(value: unknown): KycSdkTokenResponse {
  const body = object(value, 'sdk token');
  const token = body.token;
  if (typeof token !== 'string' || token.trim() === '')
    throw new ContractError('sdk token.token is missing or not a string');
  return { token, expiresInSeconds: safeInteger(body, 'expiresInSeconds', 'sdk token', true) };
}

// §14 — FX. Amounts are integer strings, never numbers; the rate stays a decimal string.
const DECIMAL = /^\d+(\.\d+)?$/;
function digits(source: Json, key: string, what: string, positive: boolean): string {
  const value = string(source, key, what);
  if (!/^\d+$/.test(value) || (positive && /^0+$/.test(value)))
    throw new ContractError(`${what}.${key} is not a ${positive ? 'positive ' : ''}digit string`);
  return value;
}
function scaleOf(source: Json, key: string, what: string): number {
  const value = source[key];
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 0 || value > 6)
    throw new ContractError(`${what}.${key} is not a valid scale`);
  return value;
}
function booleanOf(source: Json, key: string, what: string): boolean {
  const value = source[key];
  if (typeof value !== 'boolean') throw new ContractError(`${what}.${key} is not a boolean`);
  return value;
}
function rateOf(source: Json, key: string, what: string): string {
  const value = string(source, key, what);
  if (!DECIMAL.test(value) || !/[1-9]/.test(value))
    throw new ContractError(`${what}.${key} is not a positive decimal`);
  return value;
}
function nonEmpty(source: Json, key: string, what: string): string {
  const value = string(source, key, what);
  if (value.trim() === '') throw new ContractError(`${what}.${key} is empty`);
  return value;
}
function nullableId(source: Json, key: string, what: string): number | null {
  const value = source[key];
  if (value === null || value === undefined) return null;
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0)
    throw new ContractError(`${what}.${key} is not a transaction id`);
  return value;
}

export function parseFxQuote(value: unknown): FxQuote {
  const what = 'fx quote';
  const body = object(value, what);
  const quote: FxQuote = {
    quoteId: nonEmpty(body, 'quoteId', what),
    idempotencyKey: nonEmpty(body, 'idempotencyKey', what),
    sourceCurrency: oneOf<Currency>(body.sourceCurrency, CURRENCIES, `${what}.sourceCurrency`),
    destinationCurrency: oneOf<Currency>(
      body.destinationCurrency,
      CURRENCIES,
      `${what}.destinationCurrency`,
    ),
    sourceMinor: digits(body, 'sourceMinor', what, true),
    destinationMinor: digits(body, 'destinationMinor', what, true),
    sourceScale: scaleOf(body, 'sourceScale', what),
    destinationScale: scaleOf(body, 'destinationScale', what),
    exchangeRate: rateOf(body, 'exchangeRate', what),
    pricingMode: nonEmpty(body, 'pricingMode', what),
    executable: booleanOf(body, 'executable', what),
    asOf: nonEmpty(body, 'asOf', what),
    expiresAt: nonEmpty(body, 'expiresAt', what),
  };
  if (quote.sourceCurrency === quote.destinationCurrency)
    throw new ContractError(`${what} has the same currency on both sides`);
  if (Number.isNaN(Date.parse(quote.asOf)) || Number.isNaN(Date.parse(quote.expiresAt)))
    throw new ContractError(`${what} has an unreadable asOf/expiresAt`);
  return quote;
}

export function parseFxConversion(value: unknown): FxConversion {
  const what = 'fx conversion';
  const body = object(value, what);
  return {
    operationId: nonEmpty(body, 'operationId', what),
    quoteId: nonEmpty(body, 'quoteId', what),
    idempotencyKey: nonEmpty(body, 'idempotencyKey', what),
    status: oneOf(body.status, FX_CONVERSION_STATUSES, `${what}.status`),
    simulated: booleanOf(body, 'simulated', what),
    sourceCurrency: oneOf<Currency>(body.sourceCurrency, CURRENCIES, `${what}.sourceCurrency`),
    destinationCurrency: oneOf<Currency>(
      body.destinationCurrency,
      CURRENCIES,
      `${what}.destinationCurrency`,
    ),
    sourceMinor: digits(body, 'sourceMinor', what, true),
    destinationMinor: digits(body, 'destinationMinor', what, true),
    exchangeRate: rateOf(body, 'exchangeRate', what),
    reservationReference: nonEmpty(body, 'reservationReference', what),
    reservationTransactionId: nullableId(body, 'reservationTransactionId', what),
    settlementReference: nonEmpty(body, 'settlementReference', what),
    settlementTransactionId: nullableId(body, 'settlementTransactionId', what),
    requiresReconciliation: booleanOf(body, 'requiresReconciliation', what),
  };
}

// Future-proofing (§0.3 today: Spring default error body, no code). If an error body is JSON
// with a string `errorCode` (Problem Details extension or a flat body), return it.
export function errorCodeFrom(bodyText: string | undefined): string | null {
  if (!bodyText) return null;
  try {
    const body: unknown = JSON.parse(bodyText);
    if (typeof body !== 'object' || body === null || Array.isArray(body)) return null;
    const code = (body as Json).errorCode;
    return typeof code === 'string' && code.trim() !== '' ? code.trim() : null;
  } catch {
    return null;
  }
}
