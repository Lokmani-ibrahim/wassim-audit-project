import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { NotificationItem, TransactionItem, TransactionPage } from '../src/api/contract';
import { ContractError, parseNotificationPage } from '../src/api/parse';
import { findByTransactionId } from '../src/history/paging';
import { createMockNotifications, MOCK_NOTIFICATION_ITEMS } from '../src/mocks/notifications';
import {
  MAX_READ_IDS,
  compareInstants,
  initialReadState,
  isUnread,
  markAllRead,
  markRead,
  parseReadState,
  unreadCount,
  type ReadState,
} from '../src/notifications/readState';
import { createReadStateStore, type ReadStatePersistence } from '../src/notifications/store';
import { badgeText, notificationTitle, notificationView } from '../src/notifications/view';
import { withSignOutCleanup } from '../src/services/compose';
import type { Services } from '../src/types/domain';

const USER_A = '7bf8f8ad-1133-419d-9a13-9c3883690de0';
const USER_B = 'f61b6653-aded-40c8-97bc-f118a0ffeb9f';

const note = (id: string, createdAt: string, overrides: Partial<NotificationItem> = {}) =>
  ({
    id,
    type: 'TRANSFER',
    direction: 'CREDIT',
    currency: 'EUR',
    amountMinor: 1000,
    transactionId: 1,
    reference: `ref-${id}`,
    testCredit: false,
    createdAt,
    ...overrides,
  }) satisfies NotificationItem;

function memoryPersistence() {
  const files = new Map<string, string>();
  const persistence: ReadStatePersistence & { files: Map<string, string>; failing: boolean } = {
    files,
    failing: false,
    load(userId) {
      if (persistence.failing) throw new Error('disk');
      const raw = files.get(userId);
      return raw ? JSON.parse(raw) : null;
    },
    save(userId, state) {
      if (persistence.failing) throw new Error('disk');
      files.set(userId, JSON.stringify(state));
    },
    clearAll() {
      files.clear();
    },
  };
  return persistence;
}

// --- Read state (pure) ------------------------------------------------------------------------

test('compareInstants orders exactly, including microseconds Date.parse would drop', () => {
  assert.equal(compareInstants('2026-11-05T03:17:35.467123Z', '2026-11-05T03:17:35.467124Z'), -1);
  assert.equal(compareInstants('2026-11-05T03:17:35.4Z', '2026-11-05T03:17:35.400000Z'), 0);
  assert.equal(compareInstants('2026-11-05T03:17:36Z', '2026-11-05T03:17:35.999999Z'), 1);
  assert.equal(compareInstants('garbage', '2026-11-05T03:17:35Z'), -1);
});

test('first use: everything already in the feed counts as read (no badge burst)', () => {
  const items = [note('b', '2026-11-05T10:00:00Z'), note('a', '2026-11-05T09:00:00Z')];
  const state = initialReadState(items);
  assert.equal(state.watermark, '2026-11-05T10:00:00Z');
  assert.equal(unreadCount(state, items), 0);
  // A notification created later is unread.
  assert.equal(isUnread(state, note('c', '2026-11-05T10:00:00.000001Z')), true);
  // An empty first page: anything that arrives afterwards is new.
  assert.equal(isUnread(initialReadState([]), note('d', '2020-01-01T00:00:00Z')), true);
  // Not initialised: nothing is shown as unread.
  assert.equal(isUnread(null, note('e', '2030-01-01T00:00:00Z')), false);
});

test('markRead marks one item, is idempotent, and keeps at most 500 ids, newest first', () => {
  let state: ReadState = initialReadState([]);
  const item = note('x', '2026-11-05T10:00:00Z');
  state = markRead(state, item);
  assert.equal(isUnread(state, item), false);
  assert.equal(markRead(state, item), state);
  for (let index = 0; index < MAX_READ_IDS + 20; index += 1)
    state = markRead(state, note(`id-${index}`, '2026-11-05T11:00:00Z'));
  assert.equal(state.readIds.length, MAX_READ_IDS);
  assert.equal(state.readIds[0], `id-${MAX_READ_IDS + 19}`);
  assert.equal(state.readIds.includes('x'), false);
});

test('markAllRead moves the watermark forward only and drops ids it now covers', () => {
  const fresh = note('new', '2026-11-05T12:00:00Z');
  const items = [fresh, note('old', '2026-11-05T08:00:00Z')];
  let state = initialReadState([note('seed', '2026-11-05T09:00:00Z')]);
  state = markRead(state, fresh);
  state = markAllRead(state, items);
  assert.equal(state.watermark, '2026-11-05T12:00:00Z');
  assert.deepEqual(state.readIds, []);
  assert.equal(unreadCount(state, items), 0);
  // An older page never moves the watermark back.
  assert.equal(
    markAllRead(state, [note('older', '2026-11-01T00:00:00Z')]).watermark,
    '2026-11-05T12:00:00Z',
  );
});

test('parseReadState rejects anything unexpected (caller re-initialises)', () => {
  assert.deepEqual(parseReadState({ version: 1, watermark: null, readIds: ['a'] }), {
    version: 1,
    watermark: null,
    readIds: ['a'],
  });
  for (const bad of [
    null,
    [],
    'x',
    { version: 2, watermark: null, readIds: [] },
    { version: 1, watermark: 'yesterday', readIds: [] },
    { version: 1, watermark: null, readIds: [1] },
    { version: 1, watermark: null },
  ])
    assert.equal(parseReadState(bad), null);
});

// --- Store (shared, per user, persisted) ------------------------------------------------------

test('store: per-user state survives a restart (new store, same persistence)', () => {
  const persistence = memoryPersistence();
  const first = createReadStateStore(persistence);
  const items = [note('a', '2026-11-05T09:00:00Z')];
  first.observe(USER_A, items);
  first.markRead(USER_A, note('b', '2026-11-05T10:00:00Z'));

  const restarted = createReadStateStore(persistence);
  const state = restarted.get(USER_A);
  assert.equal(state?.watermark, '2026-11-05T09:00:00Z');
  assert.deepEqual(state?.readIds, ['b']);
  // observe() initialises only once: later pages do not reset the state.
  restarted.observe(USER_A, [note('c', '2026-11-05T11:00:00Z')]);
  assert.equal(isUnread(restarted.get(USER_A), note('c', '2026-11-05T11:00:00Z')), true);
});

test('store: users never share read state', () => {
  const store = createReadStateStore(memoryPersistence());
  store.observe(USER_A, []);
  store.observe(USER_B, []);
  const item = note('shared-id', '2026-11-05T10:00:00Z');
  store.markRead(USER_A, item);
  assert.equal(isUnread(store.get(USER_A), item), false);
  assert.equal(isUnread(store.get(USER_B), item), true);
});

test('store: clear() (sign-out) forgets every user in memory and on disk, and notifies', () => {
  const persistence = memoryPersistence();
  const store = createReadStateStore(persistence);
  let notified = 0;
  const unsubscribe = store.subscribe(() => (notified += 1));
  store.observe(USER_A, []);
  store.observe(USER_B, []);
  const before = store.version();
  store.clear();
  assert.equal(persistence.files.size, 0);
  assert.equal(store.get(USER_A), null);
  assert.equal(store.get(USER_B), null);
  assert.ok(store.version() > before);
  assert.equal(notified, 3);
  unsubscribe();
  store.observe(USER_A, []);
  assert.equal(notified, 3);
});

test('store: storage failures never break the feed (state kept in memory)', () => {
  const persistence = memoryPersistence();
  persistence.failing = true;
  const store = createReadStateStore(persistence);
  store.observe(USER_A, [note('a', '2026-11-05T09:00:00Z')]);
  const item = note('b', '2026-11-05T10:00:00Z');
  store.markRead(USER_A, item);
  assert.equal(isUnread(store.get(USER_A), item), false);
});

test('store: a corrupt saved file re-initialises from the next page', () => {
  const persistence = memoryPersistence();
  persistence.files.set(USER_A, JSON.stringify({ version: 9 }));
  const store = createReadStateStore(persistence);
  assert.equal(store.get(USER_A), null);
  store.observe(USER_A, [note('a', '2026-11-05T09:00:00Z')]);
  assert.equal(store.get(USER_A)?.watermark, '2026-11-05T09:00:00Z');
});

// --- Wire parsing (contract §13) --------------------------------------------------------------

test('parseNotificationPage keeps valid items, drops unknown types and currencies', () => {
  const page = parseNotificationPage({
    items: [
      note('a', '2026-11-05T10:00:00.123456Z'),
      { ...note('b', '2026-11-05T09:00:00Z'), type: 'SOMETHING_NEW' },
      { ...note('c', '2026-11-05T08:00:00Z'), currency: 'JPY' },
    ],
    hasMore: true,
    nextCursor: 'djE6',
  });
  assert.deepEqual(
    page.items.map((item) => item.id),
    ['a'],
  );
  assert.equal(page.hasMore, true);
  assert.equal(page.nextCursor, 'djE6');
});

test('parseNotificationPage rejects malformed items and pages', () => {
  const good = note('a', '2026-11-05T10:00:00Z');
  for (const bad of [
    null,
    { items: 'x', hasMore: false, nextCursor: null },
    { items: [], hasMore: 'no', nextCursor: null },
    { items: [{ ...good, id: '' }], hasMore: false, nextCursor: null },
    { items: [{ ...good, amountMinor: 0 }], hasMore: false, nextCursor: null },
    { items: [{ ...good, amountMinor: '1000' }], hasMore: false, nextCursor: null },
    { items: [{ ...good, direction: 'SIDEWAYS' }], hasMore: false, nextCursor: null },
    { items: [{ ...good, testCredit: 'true' }], hasMore: false, nextCursor: null },
    { items: [{ ...good, createdAt: 'yesterday' }], hasMore: false, nextCursor: null },
  ])
    assert.throws(() => parseNotificationPage(bad), ContractError);
});

test('parseNotificationPage treats hasMore without a cursor as the end', () => {
  const page = parseNotificationPage({ items: [], hasMore: true, nextCursor: null });
  assert.equal(page.hasMore, false);
  assert.equal(page.nextCursor, null);
});

// --- Presentation -----------------------------------------------------------------------------

test('titles: a test credit never reads like a real payment (EMAIL-2)', () => {
  assert.equal(
    notificationTitle({ type: 'INCOMING_PAYMENT', direction: 'CREDIT', testCredit: true }),
    'notifTestCredit',
  );
  assert.equal(
    notificationTitle({ type: 'INCOMING_PAYMENT', direction: 'CREDIT', testCredit: false }),
    'notifMoneyReceived',
  );
  assert.equal(
    notificationTitle({ type: 'TRANSFER', direction: 'CREDIT', testCredit: false }),
    'notifTransferReceived',
  );
  assert.equal(
    notificationTitle({ type: 'TRANSFER', direction: 'DEBIT', testCredit: false }),
    'notifTransferSent',
  );
  assert.equal(
    notificationTitle({ type: 'FEE', direction: 'DEBIT', testCredit: false }),
    'notifFee',
  );
  assert.equal(
    notificationTitle({ type: 'PAYOUT', direction: 'DEBIT', testCredit: false }),
    'notifPayout',
  );
  const view = notificationView(
    note('t', '2026-11-05T10:00:00Z', {
      type: 'INCOMING_PAYMENT',
      testCredit: true,
      amountMinor: 2500,
    }),
    'en',
  );
  assert.equal(view.note, 'notifTestCreditNote');
  assert.equal(view.amountText, '+€25.00');
});

test('amounts use the currency scale and the direction sign', () => {
  const view = notificationView(
    note('t', '2026-11-05T10:00:00Z', { currency: 'TND', direction: 'DEBIT', amountMinor: 1500 }),
    'en',
  );
  assert.equal(view.credit, false);
  assert.match(view.amountText, /^−/);
  assert.match(view.amountText, /1\.500/);
});

test('badge text: hidden at zero, "9+" above nine', () => {
  assert.equal(badgeText(0), null);
  assert.equal(badgeText(-1), null);
  assert.equal(badgeText(3), '3');
  assert.equal(badgeText(9), '9');
  assert.equal(badgeText(10), '9+');
});

// --- Opening a notification -------------------------------------------------------------------

const tx = (id: number): TransactionItem => ({
  transactionId: id,
  type: 'TRANSFER',
  direction: 'CREDIT',
  currency: 'EUR',
  amountMinor: 1000,
  status: 'COMPLETED',
  reference: `ref-${id}`,
  occurredAt: '2026-11-05T10:00:00Z',
});

test('findByTransactionId follows pages (including empty ones) and is bounded', async () => {
  const pages = new Map<string | null, TransactionPage>([
    [null, { items: [tx(1)], hasMore: true, nextCursor: 'p2' }],
    ['p2', { items: [], hasMore: true, nextCursor: 'p3' }],
    ['p3', { items: [tx(7)], hasMore: false, nextCursor: null }],
  ]);
  let calls = 0;
  const list = async (_currency: string, cursor: string | null): Promise<TransactionPage> => {
    calls += 1;
    const page = pages.get(cursor);
    if (!page) throw new Error(`unexpected cursor ${cursor}`);
    return page;
  };
  assert.deepEqual(await findByTransactionId(list, 'EUR', 7), { found: true, item: tx(7) });
  assert.equal(calls, 3);
  assert.deepEqual(await findByTransactionId(list, 'EUR', 99), { found: false, exhausted: true });

  const endless = async () => ({ items: [tx(1)], hasMore: true, nextCursor: 'again' });
  assert.deepEqual(await findByTransactionId(endless, 'EUR', 99, { maxPages: 4 }), {
    found: false,
    exhausted: false,
  });
});

// --- Sign-out cleanup and the dev mock --------------------------------------------------------

test('sign-out runs the cleanup first, and a failing cleanup never blocks it', async () => {
  const order: string[] = [];
  const base = {
    auth: {
      async signOut() {
        order.push('signOut');
      },
    },
  } as unknown as Services;
  await withSignOutCleanup(base, () => order.push('cleanup')).auth.signOut();
  assert.deepEqual(order, ['cleanup', 'signOut']);
  await withSignOutCleanup(base, () => {
    throw new Error('disk');
  }).auth.signOut();
  assert.deepEqual(order, ['cleanup', 'signOut', 'signOut']);
});

test('mock feed pages newest first with an opaque cursor', async () => {
  const feed = createMockNotifications();
  const first = await feed.list(null, 2);
  assert.deepEqual(
    first.items.map((item) => item.id),
    MOCK_NOTIFICATION_ITEMS.slice(0, 2).map((item) => item.id),
  );
  assert.equal(first.hasMore, true);
  const second = await feed.list(first.nextCursor, 2);
  assert.equal(second.items.length, 1);
  assert.equal(second.hasMore, false);
  assert.equal(second.nextCursor, null);
  const dates = MOCK_NOTIFICATION_ITEMS.map((item) => item.createdAt);
  assert.deepEqual(
    [...dates].sort((a, b) => compareInstants(b, a)),
    dates,
  );
});
