// Sumsub KYC phase 2: error mapping, failure resolution, status polling, timer-free renewal
// transport, SDK result mapping and the mock registry.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApiClient } from '../src/api/client';
import { KYC_ERROR_CODES } from '../src/api/contract';
import { createXhrFetch, XhrNetworkError, type XhrLike } from '../src/api/xhr';
import {
  classifyKycFailure,
  needsFreshProfile,
  resolveKycFailure,
  type KycFailure,
} from '../src/kyc/errors';
import { createPoller, pollDelay, POLL_DELAYS_MS, POLL_MAX_MS } from '../src/kyc/polling';
import { outcomeFromSdkResult } from '../src/kyc/sdkOutcome';
import { mockRegistry } from '../src/mocks/registry';
import { AppError } from '../src/services/errors';
import type { AccountStatus, KycStatus } from '../src/types/domain';

const body = (errorCode: string, status: number) =>
  JSON.stringify({ errorCode, message: 'ignored by the app', status });

// --- Error mapping ------------------------------------------------------------------------------

test('every PR #61 / #64 errorCode maps to a specific failure', () => {
  const expected: Record<(typeof KYC_ERROR_CODES)[number], [number, KycFailure]> = {
    ONBOARDING_REQUIRED: [409, 'onboardingRequired'],
    KYC_NOT_STARTED: [409, 'notStarted'],
    KYC_ALREADY_APPROVED: [409, 'decided'],
    KYC_FINAL_REJECTED: [409, 'decided'],
    KYC_STATE_NOT_ELIGIBLE: [409, 'decided'],
    KYC_APPLICANT_INVALID: [409, 'applicantInvalid'],
    ACCOUNT_RESTRICTED: [403, 'accountRestricted'],
    KYC_MANUAL_REVIEW_REQUIRED: [403, 'manualReview'],
    SUMSUB_UPSTREAM_ERROR: [502, 'unavailable'],
    SUMSUB_UNAVAILABLE: [503, 'unavailable'],
  };
  for (const code of KYC_ERROR_CODES) {
    const [status, failure] = expected[code];
    assert.equal(classifyKycFailure(status, body(code, status)), failure, code);
  }
});

test('503 SUMSUB_UNAVAILABLE shows "temporarily unavailable" + Try again, no routing', () => {
  const failure = classifyKycFailure(503, body('SUMSUB_UNAVAILABLE', 503));
  assert.equal(needsFreshProfile(failure), false);
  assert.deepEqual(resolveKycFailure(failure, null, false), {
    kind: 'message',
    key: 'kycUnavailable',
    reviewLocked: false,
    route: false,
  });
});

test('unknown errorCode: 5xx => unavailable, 4xx => generic', () => {
  for (const status of [500, 502, 503, 504])
    assert.equal(
      classifyKycFailure(status, body('SOMETHING_NEW', status)),
      'unavailable',
      `${status}`,
    );
  for (const status of [400, 403, 404, 409, 422])
    assert.equal(classifyKycFailure(status, body('SOMETHING_NEW', status)), 'unknown', `${status}`);
  assert.equal(
    (
      resolveKycFailure(classifyKycFailure(409, body('SOMETHING_NEW', 409)), null, false) as {
        key: string;
      }
    ).key,
    'kycError',
  );
});

test('without errorCode the status decides', () => {
  // Spring default error body / Spring Security empty body / any other uncoded 5xx.
  assert.equal(classifyKycFailure(409, '{"status":409,"error":"Conflict"}'), 'conflict');
  assert.equal(classifyKycFailure(403, undefined), 'forbidden');
  for (const status of [500, 502, 503, 504])
    assert.equal(classifyKycFailure(status, undefined), 'unavailable');
  assert.equal(classifyKycFailure(400, 'not json'), 'unknown');
  assert.equal(classifyKycFailure(409, body('', 409)), 'conflict');
});

// --- Failure resolution ------------------------------------------------------------------------

const profile = (account: AccountStatus, kyc: KycStatus, onboarded = true) => ({
  account,
  kyc,
  onboarded,
});

test('coded failures resolve without guessing', () => {
  assert.deepEqual(resolveKycFailure('notStarted', null, false), { kind: 'startAndRetry' });
  assert.equal(resolveKycFailure('notStarted', null, true).kind, 'message');
  for (const failure of ['onboardingRequired', 'decided'] as const)
    assert.deepEqual(resolveKycFailure(failure, null, false), { kind: 'route', restricted: false });
  assert.deepEqual(resolveKycFailure('accountRestricted', null, false), {
    kind: 'route',
    restricted: true,
  });
  assert.deepEqual(resolveKycFailure('manualReview', null, false), {
    kind: 'message',
    key: 'kycManualReview',
    reviewLocked: true,
    route: false,
  });
  assert.deepEqual(resolveKycFailure('unavailable', null, false), {
    kind: 'message',
    key: 'kycUnavailable',
    reviewLocked: false,
    route: false,
  });
  assert.equal(
    (resolveKycFailure('applicantInvalid', null, false) as { key: string }).key,
    'kycSupport',
  );
  assert.equal((resolveKycFailure('unknown', null, false) as { key: string }).key, 'kycError');
});

test('uncoded 409/403 are disambiguated with GET /api/profile', () => {
  // 409: NOT_STARTED => start then retry once; anything else => route on the fresh status.
  assert.deepEqual(resolveKycFailure('conflict', profile('PENDING_KYC', 'NOT_STARTED'), false), {
    kind: 'startAndRetry',
  });
  assert.deepEqual(resolveKycFailure('conflict', profile('PENDING_KYC', 'NOT_STARTED'), true), {
    kind: 'route',
    restricted: false,
  });
  for (const kyc of ['APPROVED', 'REJECTED', 'PENDING'] as const)
    assert.deepEqual(resolveKycFailure('conflict', profile('ACTIVE', kyc), false), {
      kind: 'route',
      restricted: false,
    });
  // 404 profile (not onboarded) => route (onboarding).
  assert.deepEqual(
    resolveKycFailure('conflict', profile('PENDING_KYC', 'NOT_STARTED', false), false),
    { kind: 'route', restricted: false },
  );
  // 403: suspended/closed => restricted; otherwise a review lock.
  for (const account of ['SUSPENDED', 'CLOSED'] as const)
    assert.deepEqual(
      resolveKycFailure('forbidden', profile(account, 'MORE_INFO_REQUIRED'), false),
      {
        kind: 'route',
        restricted: true,
      },
    );
  assert.deepEqual(resolveKycFailure('forbidden', profile('PENDING_KYC', 'PENDING'), false), {
    kind: 'message',
    key: 'kycManualReview',
    reviewLocked: true,
    route: true,
  });
});

test('only failures that need the status trigger a GET /api/profile', () => {
  const needs: KycFailure[] = [
    'onboardingRequired',
    'decided',
    'accountRestricted',
    'conflict',
    'forbidden',
  ];
  const all: KycFailure[] = [
    ...needs,
    'notStarted',
    'manualReview',
    'applicantInvalid',
    'unavailable',
    'unknown',
  ];
  for (const failure of all) assert.equal(needsFreshProfile(failure), needs.includes(failure));
});

// --- Status polling ----------------------------------------------------------------------------

function fakeClock() {
  let now = 0;
  const pending: { at: number; callback: () => void; id: number }[] = [];
  let nextId = 1;
  return {
    now: () => now,
    schedule: (callback: () => void, ms: number) => {
      const id = nextId++;
      pending.push({ at: now + ms, callback, id });
      return id;
    },
    cancel: (handle: unknown) => {
      const index = pending.findIndex((item) => item.id === handle);
      if (index >= 0) pending.splice(index, 1);
    },
    pendingCount: () => pending.length,
    nextDelay: () => (pending[0] ? pending[0].at - now : null),
    async fire() {
      const item = pending.shift();
      if (!item) return false;
      now = item.at;
      item.callback();
      for (let i = 0; i < 5; i++) await Promise.resolve();
      return true;
    },
  };
}

test('polling backs off 5 s → 10 s → 20 s → 30 s → 60 s and stops after 15 minutes', async () => {
  assert.deepEqual([0, 1, 2, 3, 4, 5, 9].map(pollDelay), [5e3, 10e3, 20e3, 30e3, 60e3, 60e3, 60e3]);
  const clock = fakeClock();
  let ticks = 0;
  const poller = createPoller({ ...clock, tick: async () => void ticks++ });
  poller.start();
  const delays: number[] = [];
  while (clock.pendingCount() > 0) {
    delays.push(clock.nextDelay()!);
    await clock.fire();
  }
  assert.deepEqual(delays.slice(0, 5), [...POLL_DELAYS_MS]);
  assert.equal(poller.running, false);
  assert.ok(clock.now() <= POLL_MAX_MS, 'never polls past the bound');
  assert.equal(ticks, delays.length);
  assert.ok(ticks >= 15, 'keeps polling every minute until the bound');
});

test('polling is stoppable, restartable, and survives failing ticks', async () => {
  const clock = fakeClock();
  let ticks = 0;
  const poller = createPoller({
    ...clock,
    tick: async () => {
      ticks++;
      throw new Error('network');
    },
  });
  poller.start();
  await clock.fire();
  await clock.fire();
  assert.equal(ticks, 2);
  assert.equal(clock.nextDelay(), 20_000, 'failure does not break the schedule');
  poller.reset();
  assert.equal(clock.pendingCount(), 1);
  assert.equal(clock.nextDelay(), 5_000, 'a reset restarts the backoff');
  poller.stop();
  assert.equal(clock.pendingCount(), 0);
  assert.equal(poller.running, false);
});

// --- Timer-free transport ------------------------------------------------------------------------

type Reply = { status: number; body?: string } | 'error' | 'timeout';
function fakeXhrFactory(
  replies: Reply[],
  seen: { url: string; headers: Record<string, string> }[],
) {
  return (): XhrLike => {
    const xhr: XhrLike & {
      url: string;
      headers: Record<string, string>;
      status: number;
      responseText: string;
    } = {
      url: '',
      headers: {},
      timeout: 0,
      status: 0,
      responseText: '',
      onload: null,
      onerror: null,
      ontimeout: null,
      onabort: null,
      open(_method: string, url: string) {
        this.url = url;
      },
      setRequestHeader(name: string, value: string) {
        this.headers[name] = value;
      },
      send() {
        seen.push({ url: this.url, headers: { ...this.headers } });
        const reply = replies.shift() ?? { status: 500 };
        // Delivered synchronously from "native": no JS timer involved.
        if (reply === 'error') this.onerror?.();
        else if (reply === 'timeout') this.ontimeout?.();
        else {
          this.status = reply.status;
          this.responseText = reply.body ?? '';
          this.onload?.();
        }
      },
      abort() {
        this.onabort?.();
      },
    };
    return xhr;
  };
}

test('xhr transport resolves without timers and reports network/timeout/abort', async () => {
  const seen: { url: string; headers: Record<string, string> }[] = [];
  const xhrFetch = createXhrFetch({
    timeoutMs: 1234,
    create: fakeXhrFactory([{ status: 200, body: '{"a":1}' }, 'error', 'timeout'], seen),
  });
  const ok = await xhrFetch('https://x/ok', { method: 'POST', headers: { A: 'b' }, body: '{}' });
  assert.equal(ok.ok, true);
  assert.deepEqual(await ok.json(), { a: 1 });
  assert.equal(seen[0]?.headers.A, 'b');
  await assert.rejects(
    xhrFetch('https://x/e'),
    (e: unknown) => e instanceof XhrNetworkError && e.reason === 'network',
  );
  await assert.rejects(
    xhrFetch('https://x/t'),
    (e: unknown) => e instanceof XhrNetworkError && e.reason === 'timeout',
  );
  const aborted = new AbortController();
  aborted.abort();
  await assert.rejects(
    xhrFetch('https://x/a', { signal: aborted.signal }),
    (e: unknown) => e instanceof XhrNetworkError && e.reason === 'aborted',
  );
});

test('renewal client over xhr keeps 401 => refresh => retry once; second 401 => expiry', async () => {
  const seen: { url: string; headers: Record<string, string> }[] = [];
  const token = '_act-sbx-renewed-token-value';
  let current = 'access-1';
  let refreshes = 0;
  const client = (replies: Reply[]) =>
    createApiClient({
      baseUrl: () => 'http://api',
      getToken: async () => current,
      invalidateToken: () => {
        refreshes++;
        current = `access-${refreshes + 1}`;
        return true;
      },
      expire: async () => {
        throw new AppError('sessionError', true);
      },
      fetch: createXhrFetch({ create: fakeXhrFactory(replies, seen) }),
    });
  const ok = await client([
    { status: 401 },
    { status: 200, body: JSON.stringify({ token, expiresInSeconds: 600 }) },
  ]).post<{ token: string }>('/api/kyc/sdk-token', {});
  assert.equal(ok.token, token);
  assert.equal(refreshes, 1);
  assert.deepEqual(
    seen.map((request) => request.headers.Authorization),
    ['Bearer access-1', 'Bearer access-2'],
  );
  await assert.rejects(
    client([{ status: 401 }, { status: 401 }]).post('/api/kyc/sdk-token', {}),
    (e: unknown) => e instanceof AppError && e.expired,
  );
  await assert.rejects(
    client(['error']).post('/api/kyc/sdk-token', {}),
    (e: unknown) => e instanceof AppError && e.key === 'networkError',
  );
});

// --- SDK result mapping ------------------------------------------------------------------------

test('SDK results map to submitted/incomplete/failed and never read errorMsg', () => {
  for (const status of [
    'Pending',
    'Approved',
    'TemporarilyDeclined',
    'FinallyRejected',
    'ActionCompleted',
  ])
    assert.deepEqual(outcomeFromSdkResult({ success: true, status }), { kind: 'submitted' });
  for (const status of ['Initial', 'Incomplete', 'Ready', 'SomethingNew'])
    assert.deepEqual(outcomeFromSdkResult({ success: true, status }), { kind: 'incomplete' });
  const secret = 'Unauthorized access with accessToken=_act-sbx-should-never-leak';
  const failed = outcomeFromSdkResult({
    success: false,
    status: 'Failed',
    errorType: 'Unauthorized',
    errorMsg: secret,
  } as never);
  assert.deepEqual(failed, { kind: 'failed', errorType: 'Unauthorized' });
  assert.ok(!JSON.stringify(failed).includes('_act-'), 'errorMsg is dropped');
  assert.deepEqual(outcomeFromSdkResult(null), { kind: 'failed', errorType: null });
});

// --- Registry ----------------------------------------------------------------------------------

test('registry: kycCapture is real (Sumsub SDK); its mock exists only for development', () => {
  const entry = mockRegistry.find((item) => item.feature === 'kycCapture');
  assert.ok(entry);
  assert.equal(entry.realAvailable, true);
  assert.match(entry.replacedBy, /Sumsub MobileSDK/);
  assert.match(entry.replacedBy, /\/api\/kyc\/sdk-token/);
  assert.match(entry.marker, /^__NEOS_MOCK__:kycCapture$/);
});
