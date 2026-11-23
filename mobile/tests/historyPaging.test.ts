import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { Currency, TransactionItem, TransactionPage } from '../src/api/contract';
import {
  ContractError,
  errorCodeFrom,
  parseTransactionPage,
  parseTransfer,
  parseRecipientLookup,
} from '../src/api/parse';
import { findByReference, loadPage, recentItems } from '../src/history/paging';
import {
  signedAmount,
  transactionTitle,
  transactionView,
} from '../src/screens/activity/transactionView';

const item = (id: number, overrides: Partial<TransactionItem> = {}): TransactionItem => ({
  transactionId: id,
  type: 'TRANSFER',
  direction: 'DEBIT',
  currency: 'EUR',
  amountMinor: 1000,
  status: 'COMPLETED',
  reference: `ref-${id}`,
  occurredAt: '2026-11-02T10:00:00Z',
  ...overrides,
});

function lister(pages: TransactionPage[]) {
  const calls: { currency: Currency; cursor: string | null; pageSize: number }[] = [];
  const queue = [...pages];
  const list = async (currency: Currency, cursor: string | null, pageSize: number) => {
    calls.push({ currency, cursor, pageSize });
    const next = queue.shift();
    if (!next) throw new Error('unexpected extra page request');
    return next;
  };
  return { list, calls };
}
const p = (items: TransactionItem[], nextCursor: string | null): TransactionPage => ({
  items,
  hasMore: nextCursor !== null,
  nextCursor,
});

test('loadPage: keeps fetching through empty pages while hasMore is true', async () => {
  const { list, calls } = lister([p([], 'a'), p([], 'b'), p([item(1), item(2)], 'c')]);
  const page = await loadPage(list, 'EUR', null);
  assert.deepEqual(
    page.items.map((entry) => entry.transactionId),
    [1, 2],
  );
  assert.equal(page.hasMore, true);
  assert.equal(page.nextCursor, 'c');
  assert.deepEqual(
    calls.map((call) => call.cursor),
    [null, 'a', 'b'],
  );
  assert.equal(calls[0]!.pageSize, 20);
});

test('loadPage: bounded number of empty hops, then hands back with hasMore still true', async () => {
  const { list, calls } = lister(Array.from({ length: 5 }, (_, index) => p([], `c${index}`)));
  const page = await loadPage(list, 'TND', 'start');
  assert.deepEqual(page.items, []);
  assert.equal(page.hasMore, true);
  assert.equal(page.nextCursor, 'c4');
  assert.equal(calls.length, 5);
  assert.equal(calls[0]!.cursor, 'start');
});

test('loadPage: empty history; hasMore without a cursor is treated as the end', async () => {
  assert.deepEqual(await loadPage(lister([p([], null)]).list, 'EUR', null), {
    items: [],
    hasMore: false,
    nextCursor: null,
  });
  const broken = await loadPage(
    lister([{ items: [], hasMore: true, nextCursor: null }]).list,
    'EUR',
    null,
  );
  assert.equal(broken.hasMore, false);
});

test('loadPage: keeps server order (no re-sorting)', async () => {
  const { list } = lister([p([item(3), item(9), item(1)], null)]);
  const page = await loadPage(list, 'EUR', null);
  assert.deepEqual(
    page.items.map((entry) => entry.transactionId),
    [3, 9, 1],
  );
});

test('recentItems: first 3 across pages, including empty ones; exhausted only at the real end', async () => {
  const recent = await recentItems(
    lister([p([item(1)], 'a'), p([], 'b'), p([item(2), item(3), item(4)], 'c')]).list,
    'EUR',
    3,
  );
  assert.deepEqual(
    recent.items.map((entry) => entry.transactionId),
    [1, 2, 3],
  );
  assert.equal(recent.exhausted, false);

  const empty = await recentItems(lister([p([], null)]).list, 'EUR', 3);
  assert.deepEqual(empty, { items: [], exhausted: true });

  const budget = await recentItems(
    lister(Array.from({ length: 5 }, (_, index) => p([], `c${index}`))).list,
    'EUR',
    3,
  );
  assert.deepEqual(budget, { items: [], exhausted: false }, 'not "no activity"');
});

test('findByReference: pages at 100, stops when found, reports exhausted vs budget', async () => {
  const hit = lister([p([item(1)], 'a'), p([], 'b'), p([item(2, { reference: 'target' })], 'c')]);
  const found = await findByReference(hit.list, 'EUR', 'target');
  assert.ok(found.found);
  assert.equal(found.item.transactionId, 2);
  assert.ok(hit.calls.every((call) => call.pageSize === 100));
  assert.equal(hit.calls.length, 3);

  assert.deepEqual(await findByReference(lister([p([item(1)], null)]).list, 'EUR', 'target'), {
    found: false,
    exhausted: true,
  });
  const many = lister(Array.from({ length: 3 }, (_, index) => p([item(index)], `c${index}`)));
  assert.deepEqual(await findByReference(many.list, 'EUR', 'target', { maxPages: 3 }), {
    found: false,
    exhausted: false,
  });
});

test('parseTransactionPage: shape checks', () => {
  const ok = parseTransactionPage({
    items: [
      {
        transactionId: 4711,
        type: 'TRANSFER',
        direction: 'DEBIT',
        currency: 'MUR',
        amountMinor: 150000,
        status: 'COMPLETED',
        reference: 'internal_transfer:ct_a_b',
        occurredAt: '2026-10-30T14:03:11Z',
      },
    ],
    hasMore: true,
    nextCursor: 'eyJ',
  });
  assert.equal(ok.items[0]!.amountMinor, 150000);
  assert.equal(ok.nextCursor, 'eyJ');
  assert.equal(
    parseTransactionPage({ items: [], hasMore: false, nextCursor: null }).nextCursor,
    null,
  );
  const bad = (overrides: Record<string, unknown>) => () =>
    parseTransactionPage({
      items: [
        {
          transactionId: 1,
          type: 'TRANSFER',
          direction: 'DEBIT',
          currency: 'EUR',
          amountMinor: 1,
          status: 'COMPLETED',
          reference: 'r',
          occurredAt: 'x',
          ...overrides,
        },
      ],
      hasMore: false,
      nextCursor: null,
    });
  for (const overrides of [
    { type: 'GIFT' },
    { direction: 'SIDEWAYS' },
    { currency: 'XYZ' },
    { amountMinor: -5 },
    { amountMinor: 0 },
    { amountMinor: 1.5 },
    { amountMinor: '100' },
    { status: 'PENDING' },
    { transactionId: 'abc' },
  ])
    assert.throws(bad(overrides), ContractError, JSON.stringify(overrides));
  assert.throws(() => parseTransactionPage({ items: [], hasMore: 'yes' }), ContractError);
});

test('parseTransfer / parseRecipientLookup / errorCodeFrom', () => {
  assert.deepEqual(parseTransfer({ transactionId: 4711, reference: 'r' }), {
    transactionId: 4711,
    reference: 'r',
  });
  assert.throws(() => parseTransfer({ transactionId: 4711 }), ContractError);
  assert.throws(() => parseTransfer({ transactionId: '4711', reference: 'r' }), ContractError);
  assert.deepEqual(parseRecipientLookup({ recipientUserId: 'u', maskedEmail: 'n***@x' }), {
    recipientUserId: 'u',
    maskedEmail: 'n***@x',
  });
  assert.throws(
    () => parseRecipientLookup({ recipientUserId: '', maskedEmail: 'm' }),
    ContractError,
  );
  assert.equal(errorCodeFrom('{"errorCode":"INSUFFICIENT_FUNDS"}'), 'INSUFFICIENT_FUNDS');
  assert.equal(errorCodeFrom('{"status":503,"error":"Service Unavailable"}'), null);
  assert.equal(errorCodeFrom('{"errorCode":42}'), null);
  assert.equal(errorCodeFrom('not json'), null);
  assert.equal(errorCodeFrom(undefined), null);
});

test('transaction view: titles, sign from direction, REVERSED', () => {
  assert.equal(transactionTitle({ type: 'TRANSFER', direction: 'DEBIT' }), 'txTRANSFER_DEBIT');
  assert.equal(transactionTitle({ type: 'TRANSFER', direction: 'CREDIT' }), 'txTRANSFER_CREDIT');
  assert.equal(
    transactionTitle({ type: 'INCOMING_PAYMENT', direction: 'CREDIT' }),
    'txINCOMING_PAYMENT',
  );
  assert.equal(transactionTitle({ type: 'FEE', direction: 'DEBIT' }), 'txFEE');
  assert.equal(transactionTitle({ type: 'PAYOUT', direction: 'DEBIT' }), 'txPAYOUT');
  assert.equal(signedAmount(item(1), 'en'), '−€10.00');
  assert.equal(signedAmount(item(1, { direction: 'CREDIT' }), 'en'), '+€10.00');
  assert.equal(signedAmount(item(1, { currency: 'TND', amountMinor: 5 }), 'en'), '−TND 0.005');
  const reversed = transactionView(item(1, { status: 'REVERSED' }), 'en');
  assert.equal(reversed.reversed, true);
  assert.equal(reversed.status, 'txREVERSED');
  assert.equal(transactionView(item(1), 'en').status, 'txCOMPLETED');
});
