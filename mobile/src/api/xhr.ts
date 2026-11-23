// A minimal fetch-compatible transport over XMLHttpRequest that never depends on JS timers.
//
// Why: React Native pauses JS timers while MainActivity is paused (JavaTimerManager.onHostPause),
// and RN's fetch (whatwg-fetch) resolves through setTimeout(..., 0). While the Sumsub SDK Activity
// is in front, a fetch therefore cannot complete until the SDK closes, so an SDK token renewal
// would hang (phase 1 finding, docs/api/SUMSUB_MOBILE_INTEGRATION_PLAN.md §10.3). XHR callbacks
// are delivered by the native networking module without timers (verified: 51 ms while paused).
//
// - The timeout is the native `xhr.timeout`, not setTimeout.
// - An AbortSignal is honoured (its abort event needs no timer either).
// - Only what createApiClient and the Keycloak refresh use: status, ok, text(), json().
// Pure: no React Native import (XMLHttpRequest is injected for unit tests).

export interface XhrLike {
  open(method: string, url: string): void;
  setRequestHeader(name: string, value: string): void;
  send(body?: string | null): void;
  abort(): void;
  timeout: number;
  readonly status: number;
  readonly responseText: string;
  onload: (() => void) | null;
  onerror: (() => void) | null;
  ontimeout: (() => void) | null;
  onabort: (() => void) | null;
}

export interface XhrResponse {
  readonly status: number;
  readonly ok: boolean;
  text(): Promise<string>;
  json(): Promise<unknown>;
}

export interface XhrRequestInit {
  method?: string;
  headers?: Record<string, string>;
  body?: string;
  signal?: AbortSignal;
}

export type XhrFetch = (url: string, init?: XhrRequestInit) => Promise<XhrResponse>;

export class XhrNetworkError extends Error {
  constructor(public readonly reason: 'network' | 'timeout' | 'aborted') {
    super(`XHR ${reason}`);
    this.name = 'XhrNetworkError';
  }
}

export function createXhrFetch(
  options: { timeoutMs?: number; create?: () => XhrLike } = {},
): XhrFetch {
  const create = options.create ?? (() => new XMLHttpRequest() as unknown as XhrLike);
  const timeoutMs = options.timeoutMs ?? 15000;
  return (url, init = {}) =>
    new Promise<XhrResponse>((resolve, reject) => {
      const xhr = create();
      let settled = false;
      const fail = (reason: XhrNetworkError['reason']) => {
        if (settled) return;
        settled = true;
        reject(new XhrNetworkError(reason));
      };
      if (init.signal?.aborted) {
        fail('aborted');
        return;
      }
      init.signal?.addEventListener('abort', () => {
        xhr.abort();
        fail('aborted');
      });
      xhr.open(init.method ?? 'GET', url);
      xhr.timeout = timeoutMs;
      for (const [name, value] of Object.entries(init.headers ?? {}))
        xhr.setRequestHeader(name, value);
      xhr.onload = () => {
        if (settled) return;
        settled = true;
        const status = xhr.status;
        const body = xhr.responseText ?? '';
        resolve({
          status,
          ok: status >= 200 && status < 300,
          text: async () => body,
          json: async () => JSON.parse(body) as unknown,
        });
      };
      xhr.onerror = () => fail('network');
      xhr.ontimeout = () => fail('timeout');
      xhr.onabort = () => fail('aborted');
      xhr.send(init.body ?? null);
    });
}
