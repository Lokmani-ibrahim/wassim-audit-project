import type { StaffSession } from '../auth/StaffSession';

export type Wallet = { currency: string; scale: number; status: string;
  availableMinor: string | null; pendingMinor: string | null };
export type WalletPage = { retrievedAt: string; wallets: Wallet[] };
export type Transaction = { transactionId: string; type: string; direction: 'CREDIT' | 'DEBIT';
  currency: string; scale: number; amountMinor: string; status: string; occurredAt: string };
export type TransactionPage = { items: Transaction[]; hasMore: boolean; nextCursor: string | null };

export const currencies = ['EUR', 'USD', 'GBP', 'AED', 'MUR', 'TND'] as const;
const scaleFor = (currency: string) => currency === 'TND' ? 3 : 2;
const object = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value);
const keys = (value: Record<string, unknown>, expected: string[]) =>
  Object.keys(value).sort().join(',') === expected.sort().join(',');
const minor = (value: unknown): value is string => typeof value === 'string' && /^-?(0|[1-9]\d*)$/.test(value);
const timestamp = (value: unknown): value is string => typeof value === 'string' &&
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value) && !Number.isNaN(Date.parse(value));
const currencyScale = (value: Record<string, unknown>) =>
  typeof value.currency === 'string' && currencies.includes(value.currency as typeof currencies[number]) &&
  value.scale === scaleFor(value.currency);

export function parseWalletPage(value: unknown): WalletPage {
  if (!object(value) || !keys(value, ['retrievedAt', 'wallets']) || !timestamp(value.retrievedAt) ||
      !Array.isArray(value.wallets) || value.wallets.length > currencies.length) throw new Error('Invalid wallets response');
  const wallets = value.wallets.map(item => {
    if (!object(item) || !keys(item, ['currency', 'scale', 'status', 'availableMinor', 'pendingMinor']) ||
        !currencyScale(item) || typeof item.status !== 'string' || !item.status || item.status.length > 64 ||
        (item.availableMinor !== null && !minor(item.availableMinor)) ||
        (item.pendingMinor !== null && !minor(item.pendingMinor))) throw new Error('Invalid wallet');
    return item as Wallet;
  });
  if (new Set(wallets.map(item => item.currency)).size !== wallets.length) throw new Error('Duplicate wallet');
  return { retrievedAt: value.retrievedAt, wallets };
}

export function parseTransactionPage(value: unknown, currency: string): TransactionPage {
  if (!object(value) || !keys(value, ['items', 'hasMore', 'nextCursor']) ||
      !Array.isArray(value.items) || value.items.length > 100 || typeof value.hasMore !== 'boolean' ||
      (value.hasMore ? typeof value.nextCursor !== 'string' || !value.nextCursor : value.nextCursor !== null))
    throw new Error('Invalid transactions response');
  const items = value.items.map(item => {
    if (!object(item) || !keys(item, ['transactionId', 'type', 'direction', 'currency', 'scale',
        'amountMinor', 'status', 'occurredAt']) || !currencyScale(item) || item.currency !== currency ||
        typeof item.transactionId !== 'string' || !/^\d+$/.test(item.transactionId) ||
        typeof item.type !== 'string' || !['INCOMING_PAYMENT', 'TRANSFER', 'FEE', 'PAYOUT', 'CONVERSION'].includes(item.type) ||
        !['CREDIT', 'DEBIT'].includes(item.direction as string) || !minor(item.amountMinor) ||
        typeof item.status !== 'string' || !['COMPLETED', 'REVERSED'].includes(item.status) ||
        !timestamp(item.occurredAt)) throw new Error('Invalid transaction');
    return item as Transaction;
  });
  if (new Set(items.map(item => item.transactionId)).size !== items.length) throw new Error('Duplicate transaction');
  return { items, hasMore: value.hasMore, nextCursor: value.nextCursor as string | null };
}

export function formatMinor(value: string, scale: number): string {
  if (!minor(value) || !Number.isInteger(scale) || scale < 0 || scale > 3) throw new Error('Invalid amount');
  const negative = value.startsWith('-');
  const digits = negative ? value.slice(1) : value;
  const padded = digits.padStart(scale + 1, '0');
  const amount = scale ? `${padded.slice(0, -scale)}.${padded.slice(-scale)}` : padded;
  return `${negative ? '-' : ''}${amount}`;
}

export async function getWallets(session: StaffSession, userId: string): Promise<WalletPage> {
  return parseWalletPage(await (await session.get(`/backoffice/customers/${encodeURIComponent(userId)}/wallets`)).json());
}

export async function getTransactions(session: StaffSession, userId: string, currency: string,
    cursor: string | null): Promise<TransactionPage> {
  const query = new URLSearchParams({ currency, pageSize: '20' });
  if (cursor) query.set('cursor', cursor);
  return parseTransactionPage(await (await session.get(
    `/backoffice/customers/${encodeURIComponent(userId)}/transactions?${query}`)).json(), currency);
}
