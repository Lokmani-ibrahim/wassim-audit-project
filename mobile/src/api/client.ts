// Pure HTTP client (no Expo/React Native imports, so it is unit-testable under node).
// Behaviour:
//   - Bearer token on every call.
//   - 401 => invalidate the token, refresh, retry ONCE (any method: a 401 is rejected before
//     the request is processed); a second 401, or no way to refresh => session expiry.
//   - Per-request timeout via AbortController.
//   - Nothing else is ever retried automatically (in particular no POST is retried on a
//     network error, timeout or 5xx: the outcome may be unknown).
//   - Non-2xx => typed ApiError. Callers map statuses to user messages per endpoint.
import { AppError } from '../services/errors';

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    // "METHOD /path" without the query string.
    public readonly endpoint: string,
    public readonly bodyText?: string,
  ) {
    super(`HTTP ${status} on ${endpoint}`);
    this.name = 'ApiError';
  }
}

export type QueryValue = string | number | boolean | undefined | null;
export interface RequestOptions {
  query?: Record<string, QueryValue>;
}

// The subset of fetch the client uses. Satisfied by the global fetch and by the timer-free
// XMLHttpRequest transport (api/xhr.ts) used while the Sumsub SDK is in front.
export interface HttpResponse {
  readonly status: number;
  readonly ok: boolean;
  text(): Promise<string>;
  json(): Promise<unknown>;
}
export type HttpFetch = (
  url: string,
  init: { method: string; headers: Record<string, string>; body?: string; signal: AbortSignal },
) => Promise<HttpResponse>;

export interface ApiClientDeps {
  baseUrl: () => string;
  // Resolves a valid access token (refreshing if near expiry) or throws (session expiry).
  getToken: () => Promise<string>;
  // Marks the current token as unusable. Returns false if no refresh is possible.
  invalidateToken: () => boolean;
  // Clears the session and throws AppError('sessionError', true).
  expire: (expectedGeneration?: number) => Promise<never>;
  // Prevent responses from an account that signed out while a request was in flight.
  generation?: () => number;
  fetch?: HttpFetch;
  timeoutMs?: number;
}

export interface ApiClient {
  get<T>(path: string, options?: RequestOptions): Promise<T>;
  post<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
}

export function buildQuery(query?: Record<string, QueryValue>): string {
  if (!query) return '';
  const pairs = Object.entries(query)
    .filter(([, value]) => value !== undefined && value !== null)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`);
  return pairs.length ? `?${pairs.join('&')}` : '';
}

const MAX_BODY_TEXT = 500;

export function createApiClient(deps: ApiClientDeps): ApiClient {
  const doFetch: HttpFetch = deps.fetch ?? ((url, init) => fetch(url, init));
  const timeoutMs = deps.timeoutMs ?? 15000;

  async function request<T>(
    method: 'GET' | 'POST',
    path: string,
    body: unknown,
    options: RequestOptions | undefined,
    retried: boolean,
  ): Promise<T> {
    const endpoint = `${method} ${path}`;
    const generation = deps.generation?.();
    const current = () => generation === deps.generation?.();
    const token = await deps.getToken();
    if (!current()) throw new AppError('sessionError');
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), timeoutMs);
    let response: HttpResponse;
    try {
      const headers: Record<string, string> = {
        Authorization: `Bearer ${token}`,
        Accept: 'application/json',
      };
      if (method === 'POST') headers['Content-Type'] = 'application/json';
      response = await doFetch(`${deps.baseUrl()}${path}${buildQuery(options?.query)}`, {
        method,
        headers,
        // POST always sends a JSON body ({} when none is given), as the backend expects.
        body: method === 'POST' ? JSON.stringify(body ?? {}) : undefined,
        signal: abort.signal,
      });
    } catch {
      // Network failure or timeout. Never retried here.
      throw new AppError('networkError');
    } finally {
      clearTimeout(timer);
    }
    if (!current()) throw new AppError('sessionError');
    if (response.status === 401) {
      if (!retried && deps.invalidateToken()) return request<T>(method, path, body, options, true);
      return deps.expire(generation);
    }
    if (!response.ok) {
      let bodyText: string | undefined;
      try {
        bodyText = (await response.text()).slice(0, MAX_BODY_TEXT) || undefined;
      } catch {
        bodyText = undefined;
      }
      throw new ApiError(response.status, endpoint, bodyText);
    }
    try {
      const json = await response.json();
      if (!current()) throw new AppError('sessionError');
      return json as T;
    } catch (error) {
      if (error instanceof AppError) throw error;
      throw new AppError('unexpectedError');
    }
  }

  return {
    get: (path, options) => request('GET', path, undefined, options, false),
    post: (path, body, options) => request('POST', path, body, options, false),
  };
}

export function isApiError(error: unknown, status?: number): error is ApiError {
  return error instanceof ApiError && (status === undefined || error.status === status);
}
