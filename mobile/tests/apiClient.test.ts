import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, buildQuery, createApiClient, type ApiClientDeps } from '../src/api/client';
import { AppError } from '../src/services/errors';

interface Call {
  url: string;
  init: RequestInit;
}
function json(status: number, body?: unknown): Response {
  return new Response(body === undefined ? '' : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
function setup(responses: (Response | Error)[], overrides: Partial<ApiClientDeps> = {}) {
  const calls: Call[] = [];
  let token = 'token-1';
  let canRefresh = true;
  let expired = 0;
  const deps: ApiClientDeps = {
    baseUrl: () => 'http://api.test',
    getToken: async () => token,
    invalidateToken: () => {
      if (!canRefresh) return false;
      token = 'token-2';
      return true;
    },
    expire: async () => {
      expired += 1;
      throw new AppError('sessionError', true);
    },
    fetch: (async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      const next = responses.shift();
      if (!next) throw new Error('unexpected extra request');
      if (next instanceof Error) throw next;
      return next;
    }) as typeof fetch,
    ...overrides,
  };
  return {
    client: createApiClient(deps),
    calls,
    expiredCount: () => expired,
    noRefresh: () => {
      canRefresh = false;
    },
  };
}
const authHeader = (call: Call) => (call.init.headers as Record<string, string>).Authorization;

test('query params are encoded; undefined and null are skipped', () => {
  assert.equal(buildQuery(undefined), '');
  assert.equal(buildQuery({}), '');
  assert.equal(buildQuery({ a: undefined, b: null }), '');
  assert.equal(
    buildQuery({ currency: 'MUR', pageSize: 20, cursor: 'eyJ+/=&?x y', flag: true }),
    '?currency=MUR&pageSize=20&cursor=eyJ%2B%2F%3D%26%3Fx%20y&flag=true',
  );
});

test('GET sends bearer token and query, no body; POST sends JSON body ({} by default)', async () => {
  const { client, calls } = setup([json(200, { ok: 1 }), json(200, { ok: 2 }), json(200, {})]);
  assert.deepEqual(await client.get('/api/transactions', { query: { currency: 'EUR' } }), {
    ok: 1,
  });
  assert.equal(calls[0]!.url, 'http://api.test/api/transactions?currency=EUR');
  assert.equal(calls[0]!.init.method, 'GET');
  assert.equal(calls[0]!.init.body, undefined);
  assert.equal(authHeader(calls[0]!), 'Bearer token-1');
  await client.post('/api/recipients/lookup', { email: 'a@b.c' });
  assert.equal(calls[1]!.init.method, 'POST');
  assert.equal(calls[1]!.init.body, '{"email":"a@b.c"}');
  assert.equal(
    (calls[1]!.init.headers as Record<string, string>)['Content-Type'],
    'application/json',
  );
  await client.post('/api/kyc/start');
  assert.equal(calls[2]!.init.body, '{}');
});

test('401 => one refresh + retry with the new token, then success', async () => {
  const { client, calls, expiredCount } = setup([json(401), json(200, { done: true })]);
  assert.deepEqual(await client.get('/api/profile'), { done: true });
  assert.equal(calls.length, 2);
  assert.equal(authHeader(calls[0]!), 'Bearer token-1');
  assert.equal(authHeader(calls[1]!), 'Bearer token-2');
  assert.equal(expiredCount(), 0);
});

test('401 twice => session expiry after exactly one retry (also for POST)', async () => {
  const { client, calls, expiredCount } = setup([json(401), json(401)]);
  await assert.rejects(client.post('/api/kyc/start', {}), (error: unknown) => {
    assert.ok(error instanceof AppError && error.expired && error.key === 'sessionError');
    return true;
  });
  assert.equal(calls.length, 2);
  assert.equal(expiredCount(), 1);
});

test('401 with no refresh possible => immediate expiry, no retry', async () => {
  const ctx = setup([json(401)]);
  ctx.noRefresh();
  await assert.rejects(ctx.client.get('/api/wallets'), /sessionError/);
  assert.equal(ctx.calls.length, 1);
  assert.equal(ctx.expiredCount(), 1);
});

test('non-2xx => typed ApiError with status, endpoint (no query) and body text; never retried', async () => {
  const body = { status: 503, error: 'Service Unavailable', path: '/api/wallets' };
  const { client, calls } = setup([json(503, body), json(409)]);
  await assert.rejects(client.get('/api/wallets', { query: { x: 1 } }), (error: unknown) => {
    assert.ok(error instanceof ApiError);
    assert.equal(error.status, 503);
    assert.equal(error.endpoint, 'GET /api/wallets');
    assert.equal(error.bodyText, JSON.stringify(body));
    return true;
  });
  await assert.rejects(client.post('/api/transfers/internal', {}), (error: unknown) => {
    assert.ok(error instanceof ApiError && error.status === 409);
    assert.equal(error.endpoint, 'POST /api/transfers/internal');
    assert.equal(error.bodyText, undefined);
    return true;
  });
  assert.equal(calls.length, 2);
});

test('network failure => networkError, and a POST is not retried', async () => {
  const { client, calls } = setup([new TypeError('Network request failed')]);
  await assert.rejects(client.post('/api/transfers/internal', { a: 1 }), (error: unknown) => {
    assert.ok(error instanceof AppError && error.key === 'networkError' && !error.expired);
    return true;
  });
  assert.equal(calls.length, 1);
});

test('an old-account response cannot complete after sign-out and cannot expire the new account', async () => {
  let generation = 1;
  let resolve!: (response: Response) => void;
  let expired = 0;
  const client = createApiClient({
    baseUrl: () => 'http://api.test',
    getToken: async () => 'old-token',
    invalidateToken: () => true,
    expire: async () => {
      expired++;
      throw new AppError('sessionError', true);
    },
    generation: () => generation,
    fetch: (() =>
      new Promise<Response>((done) => {
        resolve = done;
      })) as typeof fetch,
  });
  const pending = client.get('/api/profile');
  await new Promise((done) => setImmediate(done));
  generation = 2;
  resolve(json(401));
  await assert.rejects(pending, /sessionError/);
  assert.equal(expired, 0);
});

test('logout during token refresh prevents the old account from sending a request', async () => {
  let generation = 1;
  let resolveToken!: (token: string) => void;
  let requests = 0;
  const client = createApiClient({
    baseUrl: () => 'http://api.test',
    getToken: () =>
      new Promise<string>((done) => {
        resolveToken = done;
      }),
    invalidateToken: () => true,
    expire: async () => {
      throw new AppError('sessionError', true);
    },
    generation: () => generation,
    fetch: (async () => {
      requests++;
      return json(200, {});
    }) as typeof fetch,
  });
  const pending = client.post('/api/onboarding/personal', {});
  generation = 2;
  resolveToken('old-refreshed-token');
  await assert.rejects(pending, /sessionError/);
  assert.equal(requests, 0);
});

test('timeout aborts the request via AbortController => networkError', async () => {
  let aborted = false;
  const client = createApiClient({
    baseUrl: () => 'http://api.test',
    getToken: async () => 't',
    invalidateToken: () => false,
    expire: async () => {
      throw new AppError('sessionError', true);
    },
    timeoutMs: 20,
    fetch: ((_url: string, init: RequestInit) =>
      new Promise<Response>((_, reject) => {
        init.signal?.addEventListener('abort', () => {
          aborted = true;
          reject(new DOMException('Aborted', 'AbortError'));
        });
      })) as typeof fetch,
  });
  await assert.rejects(client.get('/api/wallets'), /networkError/);
  assert.equal(aborted, true);
});

test('an invalid JSON success body => unexpectedError', async () => {
  const { client } = setup([new Response('not json', { status: 200 })]);
  await assert.rejects(client.get('/api/profile'), /unexpectedError/);
});
