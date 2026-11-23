import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ApiError } from '../src/api/client';
import type { FxConversion, FxQuote } from '../src/api/contract';
import {
  ContractError,
  parseFxConversion,
  parseFxQuote,
  parseTransactionPage,
} from '../src/api/parse';
import { AppError } from '../src/services/errors';
import { SEND_MARGIN_MS, isExpired, quoteDeadline, secondsLeft } from '../src/fx/clock';
import { classifyConversionFailure, classifyQuoteFailure } from '../src/fx/outcome';
import {
  createFxEngine,
  fxPendingStorageKey,
  type FxEngineDeps,
  type PendingConversion,
} from '../src/fx/engine';

const USER = 'user-1';

const wireQuote = {
  quoteId: 'q-1',
  idempotencyKey: 'qk-1',
  sourceCurrency: 'EUR',
  destinationCurrency: 'TND',
  sourceMinor: '10000',
  destinationMinor: '335000',
  sourceScale: 2,
  destinationScale: 3,
  exchangeRate: '3.35',
  pricingMode: 'SANDBOX_INDICATIVE',
  executable: false,
  asOf: '2026-11-06T10:00:00.000000Z',
  expiresAt: '2026-11-06T10:00:30.000000Z',
};

const wireConversion = {
  operationId: 'op-1',
  quoteId: 'q-1',
  idempotencyKey: 'ck-1',
  status: 'SUCCEEDED',
  simulated: true,
  sourceCurrency: 'EUR',
  destinationCurrency: 'TND',
  sourceMinor: '10000',
  destinationMinor: '335000',
  exchangeRate: '3.35',
  reservationReference: 'fx_reserve:op1',
  reservationTransactionId: 141,
  settlementReference: 'fx_convert:op1',
  settlementTransactionId: 142,
  requiresReconciliation: false,
};

// ---------- parsing ----------

test('parseFxQuote keeps amounts as strings with their own scale (TND = 3)', () => {
  const quote = parseFxQuote(wireQuote);
  assert.equal(quote.destinationMinor, '335000');
  assert.equal(quote.destinationScale, 3);
  assert.equal(quote.exchangeRate, '3.35');
  assert.equal(quote.executable, false);
});

test('parseFxQuote rejects numbers for amounts, same currencies, bad dates and bad rates', () => {
  assert.throws(() => parseFxQuote({ ...wireQuote, sourceMinor: 10000 }), ContractError);
  assert.throws(() => parseFxQuote({ ...wireQuote, destinationMinor: '0' }), ContractError);
  assert.throws(() => parseFxQuote({ ...wireQuote, destinationCurrency: 'EUR' }), ContractError);
  assert.throws(() => parseFxQuote({ ...wireQuote, expiresAt: 'soon' }), ContractError);
  assert.throws(() => parseFxQuote({ ...wireQuote, exchangeRate: '-1' }), ContractError);
  assert.throws(() => parseFxQuote({ ...wireQuote, destinationScale: 9 }), ContractError);
});

test('parseFxConversion accepts unresolved and rejected states, null transaction ids', () => {
  const unknown = parseFxConversion({
    ...wireConversion,
    status: 'SETTLE_UNKNOWN',
    settlementTransactionId: null,
    requiresReconciliation: true,
  });
  assert.equal(unknown.status, 'SETTLE_UNKNOWN');
  assert.equal(unknown.settlementTransactionId, null);
  assert.equal(parseFxConversion({ ...wireConversion, status: 'REJECTED' }).status, 'REJECTED');
  assert.throws(() => parseFxConversion({ ...wireConversion, status: 'DONE' }), ContractError);
});

test('history accepts CONVERSION items (backend fix BUG-FX-1)', () => {
  const page = parseTransactionPage({
    items: [
      {
        transactionId: 141,
        type: 'CONVERSION',
        direction: 'DEBIT',
        currency: 'EUR',
        amountMinor: 10000,
        status: 'COMPLETED',
        reference: 'fx_reserve:op1',
        occurredAt: '2026-11-06T10:00:05Z',
      },
    ],
    hasMore: false,
    nextCursor: null,
  });
  assert.equal(page.items[0]!.type, 'CONVERSION');
});

// ---------- clock ----------

test('quote deadline counts the server lifetime from the send moment, minus a margin', () => {
  const quote = parseFxQuote(wireQuote);
  const sentAt = 1_000_000;
  assert.equal(quoteDeadline({ quote, sentAt }), sentAt + 30_000 - SEND_MARGIN_MS);
  assert.equal(secondsLeft({ quote, sentAt }, sentAt), 29);
  assert.equal(isExpired({ quote, sentAt }, sentAt + 28_499), false);
  assert.equal(isExpired({ quote, sentAt }, sentAt + 28_500), true);
  assert.equal(secondsLeft({ quote, sentAt }, sentAt + 60_000), 0);
});

test('a quote with an unusable lifetime is expired immediately', () => {
  const quote = parseFxQuote({ ...wireQuote, expiresAt: wireQuote.asOf });
  assert.equal(isExpired({ quote, sentAt: 5 }, 5), true);
});

// ---------- outcome classification ----------

test('quote failures: 503/404 unavailable, 422 out of range, network', () => {
  assert.equal(classifyQuoteFailure(new ApiError(503, 'q')), 'unavailable');
  assert.equal(classifyQuoteFailure(new ApiError(404, 'q')), 'unavailable');
  assert.equal(classifyQuoteFailure(new ApiError(422, 'q')), 'amountOutOfRange');
  assert.equal(classifyQuoteFailure(new ApiError(403, 'q')), 'notAllowed');
  assert.equal(classifyQuoteFailure(new TypeError('Network request failed')), 'network');
});

test('conversion failures: only provably-nothing-moved statuses are rejections', () => {
  assert.deepEqual(classifyConversionFailure(new ApiError(404, 'c')), {
    kind: 'rejected',
    reason: 'unavailable',
  });
  assert.deepEqual(classifyConversionFailure(new ApiError(422, 'c')), {
    kind: 'rejected',
    reason: 'insufficientFunds',
  });
  for (const status of [409, 500, 503]) {
    assert.deepEqual(classifyConversionFailure(new ApiError(status, 'c')), { kind: 'uncertain' });
  }
  assert.deepEqual(classifyConversionFailure(new Error('timeout')), { kind: 'uncertain' });
  assert.deepEqual(classifyConversionFailure(new ContractError('bad body')), { kind: 'uncertain' });
  assert.equal(classifyConversionFailure(new AppError('unexpectedError', true)).kind, 'rejected');
});

// ---------- engine ----------

type Call = { kind: 'quote' | 'convert' | 'find'; body: unknown };

function harness(options: {
  convert?: () => Promise<FxConversion>;
  find?: () => Promise<FxConversion>;
  quote?: () => Promise<FxQuote>;
  now?: () => number;
}) {
  const data = new Map<string, unknown>();
  const calls: Call[] = [];
  const writes: string[] = [];
  let uuid = 0;
  const deps: FxEngineDeps = {
    async quote(body) {
      calls.push({ kind: 'quote', body });
      return options.quote ? options.quote() : parseFxQuote(wireQuote);
    },
    async convert(body) {
      calls.push({ kind: 'convert', body });
      // The pending conversion must be on disk before any POST.
      assert.ok(data.has(fxPendingStorageKey(USER)), 'persisted before POST');
      return options.convert
        ? options.convert()
        : parseFxConversion({ ...wireConversion, idempotencyKey: body.idempotencyKey });
    },
    async findByKey(key) {
      calls.push({ kind: 'find', body: key });
      if (!options.find) throw new ApiError(404, 'find');
      return options.find();
    },
    store: {
      async read<T>(key: string) {
        return (data.get(key) as T) ?? null;
      },
      async write(key, value) {
        writes.push(key);
        data.set(key, JSON.parse(JSON.stringify(value)));
      },
      async remove(key) {
        data.delete(key);
      },
    },
    uuid: () => `uuid-${++uuid}`,
    now: options.now ?? (() => 1_000_000),
  };
  return { engine: createFxEngine(USER, deps), calls, data, writes };
}

const intent = { sourceCurrency: 'EUR', destinationCurrency: 'TND', sourceMinor: '10000' } as const;

test('requestQuote sends a new key every time and records the send moment', async () => {
  const h = harness({});
  const first = await h.engine.requestQuote(intent);
  const second = await h.engine.requestQuote(intent);
  assert.equal(first.kind, 'quote');
  const keys = h.calls.map((call) => (call.body as { idempotencyKey: string }).idempotencyKey);
  assert.notEqual(keys[0], keys[1]);
  assert.equal(first.kind === 'quote' && first.received.sentAt, 1_000_000);
  assert.equal(second.kind, 'quote');
});

test('requestQuote maps a disabled backend (503) to unavailable', async () => {
  const h = harness({
    quote: async () => {
      throw new ApiError(503, 'q');
    },
  });
  assert.deepEqual(await h.engine.requestQuote(intent), { kind: 'error', reason: 'unavailable' });
});

test('submit: persisted before the POST, success clears it', async () => {
  const h = harness({});
  const quoted = await h.engine.requestQuote(intent);
  assert.equal(quoted.kind, 'quote');
  if (quoted.kind !== 'quote') return;
  const outcome = await h.engine.submit(quoted.received);
  assert.equal(outcome.kind, 'succeeded');
  assert.equal(h.data.has(fxPendingStorageKey(USER)), false);
  const convert = h.calls.find((call) => call.kind === 'convert')!.body as {
    quoteId: string;
    idempotencyKey: string;
  };
  assert.equal(convert.quoteId, 'q-1');
});

test('submit never sends an expired quote', async () => {
  let now = 1_000_000;
  const h = harness({ now: () => now });
  const quoted = await h.engine.requestQuote(intent);
  if (quoted.kind !== 'quote') throw new Error('no quote');
  now += 29_000;
  assert.deepEqual(await h.engine.submit(quoted.received), { kind: 'expired' });
  assert.equal(h.calls.filter((call) => call.kind === 'convert').length, 0);
  assert.equal(h.data.has(fxPendingStorageKey(USER)), false);
});

test('uncertain outcomes (409, 503, timeout) keep the pending conversion and send nothing more', async () => {
  for (const failure of [new ApiError(409, 'c'), new ApiError(503, 'c'), new Error('timeout')]) {
    const h = harness({
      convert: async () => {
        throw failure;
      },
    });
    const quoted = await h.engine.requestQuote(intent);
    if (quoted.kind !== 'quote') throw new Error('no quote');
    const outcome = await h.engine.submit(quoted.received);
    assert.equal(outcome.kind, 'uncertain');
    assert.equal(outcome.kind === 'uncertain' && outcome.check, 'notChecked');
    assert.equal(
      h.calls.filter((call) => call.kind === 'convert').length,
      1,
      'no automatic resend',
    );
    assert.equal(h.calls.filter((call) => call.kind === 'find').length, 0, 'no automatic check');
    assert.ok(h.data.has(fxPendingStorageKey(USER)));
  }
});

test('retry resends the SAME key and quote; a replayed success resolves it', async () => {
  let fail = true;
  const h = harness({
    convert: async () => {
      if (fail) throw new ApiError(503, 'c');
      return parseFxConversion({ ...wireConversion, idempotencyKey: 'uuid-2' });
    },
  });
  const quoted = await h.engine.requestQuote(intent);
  if (quoted.kind !== 'quote') throw new Error('no quote');
  const first = await h.engine.submit(quoted.received);
  if (first.kind !== 'uncertain') throw new Error('expected uncertain');
  fail = false;
  const second = await h.engine.retry(first.pending);
  assert.equal(second.kind, 'succeeded');
  const bodies = h.calls
    .filter((call) => call.kind === 'convert')
    .map((call) => call.body as { quoteId: string; idempotencyKey: string });
  assert.equal(bodies.length, 2);
  assert.deepEqual(bodies[0], bodies[1]);
});

test('definitive rejections (404 off, 422 funds) clear the pending conversion', async () => {
  for (const [status, reason] of [
    [404, 'unavailable'],
    [422, 'insufficientFunds'],
  ] as const) {
    const h = harness({
      convert: async () => {
        throw new ApiError(status, 'c');
      },
    });
    const quoted = await h.engine.requestQuote(intent);
    if (quoted.kind !== 'quote') throw new Error('no quote');
    const outcome = await h.engine.submit(quoted.received);
    assert.equal(outcome.kind, 'rejected');
    assert.equal(outcome.kind === 'rejected' && outcome.reason, reason);
    assert.equal(h.data.has(fxPendingStorageKey(USER)), false);
  }
});

async function uncertainPending(find?: () => Promise<FxConversion>) {
  const h = harness({
    convert: async () => {
      throw new ApiError(503, 'c');
    },
    find,
  });
  const quoted = await h.engine.requestQuote(intent);
  if (quoted.kind !== 'quote') throw new Error('no quote');
  const outcome = await h.engine.submit(quoted.received);
  if (outcome.kind !== 'uncertain') throw new Error('expected uncertain');
  return { h, pending: outcome.pending };
}

test('check: SUCCEEDED and REJECTED are final, unfinished stays uncertain', async () => {
  const done = await uncertainPending(async () =>
    parseFxConversion({ ...wireConversion, idempotencyKey: 'uuid-2' }),
  );
  assert.equal((await done.h.engine.check(done.pending)).kind, 'succeeded');
  assert.equal(done.h.data.has(fxPendingStorageKey(USER)), false);

  const rejected = await uncertainPending(async () =>
    parseFxConversion({ ...wireConversion, idempotencyKey: 'uuid-2', status: 'REJECTED' }),
  );
  const outcome = await rejected.h.engine.check(rejected.pending);
  assert.equal(outcome.kind === 'rejected' && outcome.reason, 'insufficientFunds');

  const open = await uncertainPending(async () =>
    parseFxConversion({
      ...wireConversion,
      idempotencyKey: 'uuid-2',
      status: 'SETTLE_UNKNOWN',
      requiresReconciliation: true,
    }),
  );
  const still = await open.h.engine.check(open.pending);
  assert.equal(still.kind === 'uncertain' && still.check, 'inProgress');
  assert.equal(still.kind === 'uncertain' && still.pending.manualChecks, 1);
});

test('check: 404 is not proof, a missing endpoint is "check unavailable"', async () => {
  const missing = await uncertainPending();
  const outcome = await missing.h.engine.check(missing.pending);
  assert.equal(outcome.kind === 'uncertain' && outcome.check, 'notFound');
  assert.ok(missing.h.data.has(fxPendingStorageKey(USER)));

  const old = await uncertainPending(async () => {
    throw new ApiError(405, 'find');
  });
  const result = await old.h.engine.check(old.pending);
  assert.equal(result.kind === 'uncertain' && result.check, 'checkUnavailable');
  assert.equal(
    old.h.calls.filter((call) => call.kind === 'convert').length,
    1,
    'check never POSTs',
  );
});

test('stop tracking only after a manual check; a second conversion waits for the first', async () => {
  const { h, pending } = await uncertainPending();
  await assert.rejects(() => h.engine.stopTracking(pending));
  const quoted = await h.engine.requestQuote(intent);
  if (quoted.kind !== 'quote') throw new Error('no quote');
  await assert.rejects(() => h.engine.submit(quoted.received));
  const checked = await h.engine.check(pending);
  if (checked.kind !== 'uncertain') throw new Error('expected uncertain');
  await h.engine.stopTracking(checked.pending);
  assert.equal(h.data.has(fxPendingStorageKey(USER)), false);
  const typed: PendingConversion | null = await h.engine.resume();
  assert.equal(typed, null);
});
