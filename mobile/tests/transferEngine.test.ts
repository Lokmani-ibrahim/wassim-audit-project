import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError } from '../src/api/client';
import { ContractError } from '../src/api/parse';
import { AppError } from '../src/services/errors';
import type {
  Currency,
  InternalTransferRequest,
  TransactionItem,
  TransactionPage,
} from '../src/api/contract';
import {
  createTransferEngine,
  pendingStorageKey,
  readPending,
  type KeyValueStore,
  type PendingTransfer,
  type TransferIntent,
} from '../src/transfers/engine';
import { classifyTransferFailure } from '../src/transfers/outcome';
import { transferReference } from '../src/transfers/reference';

const SENDER = '7bf8f8ad-1133-419d-9a13-9c3883690de0';
const RECIPIENT = 'f61b6653-aded-40c8-97bc-f118a0ffeb9f';
const ENDPOINT = 'POST /api/transfers/internal';

const intent = (overrides: Partial<TransferIntent> = {}): TransferIntent => ({
  recipientUserId: RECIPIENT,
  maskedEmail: 'n***@neos.local',
  currency: 'EUR',
  amountMinor: '1000',
  ...overrides,
});

function memoryStore(): KeyValueStore & { data: Map<string, string>; failWrites: boolean } {
  const data = new Map<string, string>();
  const store = {
    data,
    failWrites: false,
    async read<T>(key: string) {
      const value = data.get(key);
      return value === undefined ? null : (JSON.parse(value) as T);
    },
    async write(key: string, value: unknown) {
      if (store.failWrites) throw new Error('secure store unavailable');
      data.set(key, JSON.stringify(value));
    },
    async remove(key: string) {
      data.delete(key);
    },
  };
  return store;
}

type SendResult = { transactionId: number; reference: string } | Error;
type ListResult = TransactionPage | Error;

function setup(
  options: {
    send?: SendResult[];
    pages?: ListResult[];
    store?: ReturnType<typeof memoryStore>;
  } = {},
) {
  const sends: InternalTransferRequest[] = [];
  const lists: { currency: Currency; cursor: string | null; pageSize: number }[] = [];
  const sendQueue = [...(options.send ?? [])];
  const pageQueue = [...(options.pages ?? [])];
  const store = options.store ?? memoryStore();
  let uuidCounter = 0;
  let persistedAtSend: unknown = undefined;
  const engine = createTransferEngine(SENDER, {
    async send(request) {
      sends.push(request);
      persistedAtSend = store.data.get(pendingStorageKey(SENDER));
      const next = sendQueue.shift();
      if (!next) throw new Error('unexpected send');
      if (next instanceof Error) throw next;
      return next;
    },
    async list(currency, cursor, pageSize) {
      lists.push({ currency, cursor, pageSize });
      const next = pageQueue.shift();
      if (!next) throw new Error('unexpected list');
      if (next instanceof Error) throw next;
      return next;
    },
    store,
    uuid: () => {
      uuidCounter += 1;
      return `00000000-0000-4000-8000-${String(uuidCounter).padStart(12, '0')}`;
    },
    now: () => Date.parse('2026-11-02T10:00:00Z'),
  });
  return {
    engine,
    store,
    sends,
    lists,
    persistedAtSend: () => persistedAtSend,
    pending: () => readPending(store, SENDER),
  };
}

const page = (items: TransactionItem[], hasMore = false, nextCursor: string | null = null) => ({
  items,
  hasMore,
  nextCursor: hasMore ? (nextCursor ?? 'next') : null,
});
const debit = (reference: string, overrides: Partial<TransactionItem> = {}): TransactionItem => ({
  transactionId: 4711,
  type: 'TRANSFER',
  direction: 'DEBIT',
  currency: 'EUR',
  amountMinor: 1000,
  status: 'COMPLETED',
  reference,
  occurredAt: '2026-11-02T10:00:01Z',
  ...overrides,
});
const apiError = (status: number, body?: unknown) =>
  new ApiError(status, ENDPOINT, body === undefined ? undefined : JSON.stringify(body));

// ---------- reference ----------

test('reference: internal_transfer:ct_<sender no dashes>_<key no dashes>, lowercase', () => {
  assert.equal(
    transferReference(SENDER, '5B7E0A52-0C64-4A3F-9D1B-2F0D6C8E9A11'),
    'internal_transfer:ct_7bf8f8ad1133419d9a139c3883690de0_5b7e0a520c644a3f9d1b2f0d6c8e9a11',
  );
  assert.throws(() => transferReference('not-a-uuid', SENDER));
  assert.throws(() => transferReference(SENDER, ''));
});

// ---------- classification (contract §5 outcome table + future error codes) ----------

test('classify: each HTTP case', () => {
  const cases: [unknown, ReturnType<typeof classifyTransferFailure>][] = [
    [apiError(400), { kind: 'rejected', reason: 'invalidInput' }],
    [apiError(403), { kind: 'rejected', reason: 'notAllowed' }],
    [apiError(404), { kind: 'rejected', reason: 'transfersUnavailable' }],
    [apiError(422), { kind: 'rejected', reason: 'rejected' }],
    [apiError(429), { kind: 'rejected', reason: 'rateLimited' }],
    // Today: in-progress / UNKNOWN claim, or different params (indistinguishable without a code).
    [apiError(409), { kind: 'uncertain' }],
    [apiError(409, { status: 409, error: 'Conflict' }), { kind: 'uncertain' }],
    [apiError(500), { kind: 'uncertain' }],
    [apiError(502), { kind: 'uncertain' }],
    [apiError(503), { kind: 'uncertain' }],
    [new AppError('networkError'), { kind: 'uncertain' }],
    [new ContractError('transfer.reference is empty'), { kind: 'uncertain' }],
    [new Error('anything else'), { kind: 'uncertain' }],
    // 401 after refresh-retry: rejected by Spring Security before the controller.
    [new AppError('sessionError', true), { kind: 'rejected', reason: 'rejected' }],
  ];
  for (const [error, expected] of cases) assert.deepEqual(classifyTransferFailure(error), expected);
});

test('classify: future errorCode bodies', () => {
  assert.deepEqual(classifyTransferFailure(apiError(422, { errorCode: 'INSUFFICIENT_FUNDS' })), {
    kind: 'rejected',
    reason: 'insufficientFunds',
  });
  assert.deepEqual(
    classifyTransferFailure(
      apiError(400, { type: 'about:blank', errorCode: 'INSUFFICIENT_FUNDS' }),
    ),
    { kind: 'rejected', reason: 'insufficientFunds' },
  );
  assert.deepEqual(classifyTransferFailure(apiError(422, { errorCode: 'SOMETHING_NEW' })), {
    kind: 'rejected',
    reason: 'rejected',
  });
  assert.deepEqual(
    classifyTransferFailure(apiError(403, { errorCode: 'RECIPIENT_NOT_ELIGIBLE' })),
    {
      kind: 'rejected',
      reason: 'recipientUnavailable',
    },
  );
  // 409: only the explicit key-reuse code is definitive; any other code stays uncertain.
  assert.deepEqual(
    classifyTransferFailure(apiError(409, { errorCode: 'IDEMPOTENCY_KEY_REUSED' })),
    {
      kind: 'rejected',
      reason: 'keyConflict',
    },
  );
  assert.deepEqual(classifyTransferFailure(apiError(409, { errorCode: 'TRANSFER_IN_PROGRESS' })), {
    kind: 'uncertain',
  });
  // 5xx is never definitive, whatever the code says.
  assert.deepEqual(classifyTransferFailure(apiError(503, { errorCode: 'INSUFFICIENT_FUNDS' })), {
    kind: 'uncertain',
  });
  // Non-JSON / truncated body: status only.
  assert.deepEqual(classifyTransferFailure(new ApiError(422, ENDPOINT, '<html>{"errorCode"')), {
    kind: 'rejected',
    reason: 'rejected',
  });
});

// ---------- engine ----------

test('200: success, request on the wire, pending persisted before the POST and cleared after', async () => {
  const s = setup({ send: [{ transactionId: 4711, reference: 'ref-from-server' }] });
  const key = s.engine.prepare(intent());
  const outcome = await s.engine.submit();
  assert.equal(outcome.kind, 'succeeded');
  assert.ok(outcome.kind === 'succeeded');
  assert.equal(outcome.transactionId, 4711);
  assert.equal(outcome.reference, 'ref-from-server');
  assert.equal(outcome.via, 'response');
  assert.deepEqual(s.sends, [
    { destinationUserId: RECIPIENT, currency: 'EUR', amountMinor: 1000, idempotencyKey: key },
  ]);
  const persisted = JSON.parse(String(s.persistedAtSend())) as PendingTransfer;
  assert.equal(persisted.idempotencyKey, key);
  assert.equal(persisted.reference, transferReference(SENDER, key));
  assert.equal(persisted.amountMinor, '1000');
  assert.equal(await s.pending(), null);
});

test('prepare: same intent keeps the key; any changed field gives a new key', () => {
  const s = setup();
  const first = s.engine.prepare(intent());
  assert.equal(s.engine.prepare(intent()), first);
  assert.equal(s.engine.prepare(intent({ maskedEmail: 'other display' })), first);
  assert.equal(s.engine.prepare(intent({ amountMinor: '01000' })), first);
  const amount = s.engine.prepare(intent({ amountMinor: '1001' }));
  assert.notEqual(amount, first);
  const currency = s.engine.prepare(intent({ amountMinor: '1001', currency: 'USD' }));
  assert.notEqual(currency, amount);
  const recipient = s.engine.prepare(
    intent({ amountMinor: '1001', currency: 'USD', recipientUserId: SENDER }),
  );
  assert.notEqual(recipient, currency);
});

test('a Review that is never confirmed persists nothing', async () => {
  const s = setup();
  s.engine.prepare(intent());
  assert.equal(await s.pending(), null);
  assert.equal(s.store.data.size, 0);
});

test('storage failure before the POST: nothing is sent', async () => {
  const store = memoryStore();
  store.failWrites = true;
  const s = setup({ store });
  s.engine.prepare(intent());
  await assert.rejects(s.engine.submit());
  assert.equal(s.sends.length, 0);
});

for (const [status, reason] of [
  [400, 'invalidInput'],
  [403, 'notAllowed'],
  [422, 'rejected'],
] as const) {
  test(`${status}: definitive rejection, pending cleared, next attempt gets a new key`, async () => {
    const s = setup({ send: [apiError(status)] });
    const key = s.engine.prepare(intent());
    const outcome = await s.engine.submit();
    assert.deepEqual(outcome.kind === 'rejected' && outcome.reason, reason);
    assert.equal(s.lists.length, 0, 'no history search on a definitive rejection');
    assert.equal(await s.pending(), null);
    assert.notEqual(s.engine.prepare(intent()), key);
  });
}

test('422 INSUFFICIENT_FUNDS (after the backend fix): specific rejection', async () => {
  const s = setup({ send: [apiError(422, { errorCode: 'INSUFFICIENT_FUNDS' })] });
  s.engine.prepare(intent());
  const outcome = await s.engine.submit();
  assert.ok(outcome.kind === 'rejected');
  assert.equal(outcome.reason, 'insufficientFunds');
  assert.equal(await s.pending(), null);
});

test('409 key reuse with different params (coded): client bug, new key for the new intent', async () => {
  const s = setup({ send: [apiError(409, { errorCode: 'IDEMPOTENCY_KEY_REUSED' })] });
  const key = s.engine.prepare(intent());
  const outcome = await s.engine.submit();
  assert.ok(outcome.kind === 'rejected');
  assert.equal(outcome.reason, 'keyConflict');
  assert.notEqual(s.engine.prepare(intent()), key);
});

for (const [label, error] of [
  ['503 (today: Formance rejection / ledger error)', apiError(503)],
  ['409 in progress', apiError(409)],
  ['500', apiError(500)],
  ['network error', new AppError('networkError')],
  ['timeout', new AppError('networkError')],
  ['malformed 200', new ContractError('transfer is not an object')],
] as const) {
  test(`uncertain (${label}) → reference found in history → success`, async () => {
    // Keys are deterministic in tests: the first prepare() of a fresh engine gets uuid #1.
    const reference = transferReference(SENDER, '00000000-0000-4000-8000-000000000001');
    // First page: unrelated items, hasMore; second: an empty page with hasMore; third: the debit.
    const pages: ListResult[] = [
      page([debit('internal_transfer:ct_other')], true, 'c1'),
      page([], true, 'c2'),
      page([debit(reference, { transactionId: 99 })]),
    ];
    const t = setup({ send: [error], pages });
    t.engine.prepare(intent());
    const outcome = await t.engine.submit();
    assert.ok(outcome.kind === 'succeeded', JSON.stringify(outcome));
    assert.equal(outcome.via, 'history');
    assert.equal(outcome.transactionId, 99);
    assert.deepEqual(
      t.lists.map((call) => [call.currency, call.cursor, call.pageSize]),
      [
        ['EUR', null, 100],
        ['EUR', 'c1', 100],
        ['EUR', 'c2', 100],
      ],
    );
    assert.equal(await t.pending(), null);
  });
}

test('uncertain → not found → stays pending (persisted), no new key, no auto-retry', async () => {
  const s = setup({ send: [apiError(503)], pages: [page([])] });
  const key = s.engine.prepare(intent());
  const outcome = await s.engine.submit();
  assert.ok(outcome.kind === 'uncertain');
  assert.equal(outcome.check, 'notFound');
  assert.equal(outcome.transfer.idempotencyKey, key);
  assert.equal(outcome.transfer.manualChecks, 0, 'the automatic check is not a manual one');
  assert.equal(s.sends.length, 1, 'never auto-retried');
  const pending = await s.pending();
  assert.equal(pending?.idempotencyKey, key);
});

test('uncertain → search budget exhausted → notFoundYet; history error → checkFailed', async () => {
  const many = Array.from({ length: 10 }, (_, index) => page([], true, `c${index}`));
  const s = setup({ send: [apiError(503)], pages: many });
  s.engine.prepare(intent());
  const outcome = await s.engine.submit();
  assert.ok(outcome.kind === 'uncertain');
  assert.equal(outcome.check, 'notFoundYet');
  assert.equal(s.lists.length, 10);

  const f = setup({
    send: [new AppError('networkError')],
    pages: [new AppError('historyUnavailable')],
  });
  f.engine.prepare(intent());
  const failed = await f.engine.submit();
  assert.ok(failed.kind === 'uncertain');
  assert.equal(failed.check, 'checkFailed');
  assert.ok(await f.pending());
});

test('manual "Check status": counts, persists, and resolves once the debit appears', async () => {
  const s = setup({ send: [apiError(503)], pages: [page([]), page([]), page([])] });
  const key = s.engine.prepare(intent());
  const first = await s.engine.submit();
  assert.ok(first.kind === 'uncertain');
  const second = await s.engine.check(first.transfer, true);
  assert.ok(second.kind === 'uncertain');
  assert.equal(second.transfer.manualChecks, 1);
  assert.equal((await s.pending())?.manualChecks, 1);
  const third = await s.engine.check(second.transfer, true);
  assert.ok(third.kind === 'uncertain');
  assert.equal(third.transfer.manualChecks, 2);

  const found = setup({ pages: [page([debit(transferReference(SENDER, key))])] });
  const resolved = await found.engine.check(third.transfer, true);
  assert.ok(resolved.kind === 'succeeded');
});

test('"Try again" reuses the same key and params: 409 → still uncertain, replayed 200 → success', async () => {
  const s = setup({
    send: [apiError(503), apiError(409), { transactionId: 7, reference: 'r' }],
    pages: [page([]), page([])],
  });
  const key = s.engine.prepare(intent());
  const first = await s.engine.submit();
  assert.ok(first.kind === 'uncertain');
  const second = await s.engine.retry(first.transfer);
  assert.ok(second.kind === 'uncertain');
  const third = await s.engine.retry(second.transfer);
  assert.ok(third.kind === 'succeeded');
  assert.deepEqual(
    s.sends.map((request) => request.idempotencyKey),
    [key, key, key],
  );
  assert.ok(s.sends.every((request) => request.amountMinor === 1000));
  assert.equal(await s.pending(), null);
});

test('resume after restart: a new engine instance reads the persisted transfer and checks it', async () => {
  const store = memoryStore();
  const before = setup({
    send: [new AppError('networkError')],
    pages: [new Error('offline')],
    store,
  });
  const key = before.engine.prepare(intent());
  await before.engine.submit();

  const reference = transferReference(SENDER, key);
  const after = setup({ store, pages: [page([debit(reference)])] });
  const resumed = await after.engine.resume();
  assert.equal(resumed?.idempotencyKey, key);
  assert.equal(resumed?.reference, reference);
  const outcome = await after.engine.check(resumed!, false);
  assert.ok(outcome.kind === 'succeeded');
  assert.equal(await after.engine.resume(), null);
});

test('resume ignores another user’s or a malformed stored transfer', async () => {
  const store = memoryStore();
  store.data.set(
    pendingStorageKey(SENDER),
    JSON.stringify({ version: 1, senderUserId: 'someone' }),
  );
  assert.equal(await readPending(store, SENDER), null);
  store.data.set(pendingStorageKey(SENDER), JSON.stringify({ hello: 'world' }));
  assert.equal(await readPending(store, SENDER), null);
});

test('stop tracking: only after a manual check; then the same recipient + amount warns again', async () => {
  const s = setup({ send: [apiError(503)], pages: [page([]), page([])] });
  s.engine.prepare(intent());
  const first = await s.engine.submit();
  assert.ok(first.kind === 'uncertain');
  await assert.rejects(s.engine.stopTracking(first.transfer));
  assert.ok(await s.pending(), 'still tracked');

  const checked = await s.engine.check(first.transfer, true);
  assert.ok(checked.kind === 'uncertain');
  await s.engine.stopTracking(checked.transfer);
  assert.equal(await s.pending(), null);

  assert.equal(await s.engine.matchesUnconfirmed(intent()), true);
  assert.equal(await s.engine.matchesUnconfirmed(intent({ amountMinor: '01000' })), true);
  assert.equal(await s.engine.matchesUnconfirmed(intent({ amountMinor: '1001' })), false);
  assert.equal(await s.engine.matchesUnconfirmed(intent({ recipientUserId: SENDER })), false);
  assert.equal(await s.engine.matchesUnconfirmed(intent({ currency: 'USD' })), false);
  // The new transfer gets a new key (it is a new intent after a stop).
  const key = s.engine.prepare(intent());
  assert.notEqual(key, first.transfer.idempotencyKey);
});
