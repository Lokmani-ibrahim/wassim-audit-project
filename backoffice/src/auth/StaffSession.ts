import type Keycloak from 'keycloak-js';
import type { BackofficeConfig } from '../config';

export type Me = { staffSubject: string; capabilities: string[] };
export type SessionState =
  | { kind: 'loading' }
  | { kind: 'signedOut' }
  | { kind: 'signedIn'; me: Me }
  | { kind: 'forbidden' }
  | { kind: 'unavailable' }
  | { kind: 'logoutFailed' }
  | { kind: 'expired' };

export type Adapter = Pick<Keycloak, 'init' | 'login' | 'logout' | 'updateToken' | 'clearToken'> & {
  token?: string;
  tokenParsed?: { sub?: string };
  onTokenExpired?: () => void;
  onAuthLogout?: () => void;
};

export class ApiError extends Error {
  constructor(public readonly status: number) { super(`API request failed: ${status}`); }
}
export class StaleSessionError extends Error {}
export class ExpiredSessionError extends Error {}

const routeKey = 'neos.backoffice.intendedRoute';

export function safeRoute(candidate: string, origin: string): string {
  if (!candidate.startsWith('/') || candidate.startsWith('//') || candidate.includes('\\')) return '/';
  try {
    const url = new URL(candidate, origin);
    if (url.origin !== origin || url.pathname === '/auth/callback') return '/';
    return url.pathname + url.search + url.hash;
  } catch { return '/'; }
}

export function parseMe(value: unknown): Me {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('Invalid /me response');
  const data = value as Record<string, unknown>;
  if (typeof data.staffSubject !== 'string' || !data.staffSubject ||
      !Array.isArray(data.capabilities) || data.capabilities.some(v => typeof v !== 'string') ||
      data.capabilities.some(v => v !== 'CUSTOMER_READ') ||
      new Set(data.capabilities).size !== data.capabilities.length) throw new Error('Invalid /me response');
  return { staffSubject: data.staffSubject, capabilities: data.capabilities as string[] };
}

export class StaffSession {
  private stateValue: SessionState = { kind: 'loading' };
  private listeners = new Set<() => void>();
  private generation = 0;
  private initialized = false;
  private refreshPending?: { generation: number; promise: Promise<void> };
  private startPending?: Promise<void>;

  constructor(
    private readonly adapter: Adapter,
    private readonly config: BackofficeConfig,
    private readonly request: typeof fetch = (...args) => fetch(...args),
  ) {}

  get state(): SessionState { return this.stateValue; }
  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }
  private publish(state: SessionState): void {
    this.stateValue = state;
    this.listeners.forEach(listener => listener());
  }
  private invalidate(state: SessionState): void {
    this.generation++;
    this.publish(state);
    this.adapter.clearToken();
  }
  private subject(): string | undefined { return this.adapter.tokenParsed?.sub; }
  private assertCurrent(generation: number, subject: string | undefined): void {
    if (generation !== this.generation) throw new StaleSessionError();
    if (subject !== this.subject()) {
      this.invalidate({ kind: 'expired' });
      throw new StaleSessionError();
    }
  }

  start(): Promise<void> {
    if (this.startPending) return this.startPending;
    this.startPending = this.initialize();
    return this.startPending;
  }

  private async initialize(): Promise<void> {
    const generation = this.generation;
    this.adapter.onTokenExpired = () => {
      if (this.initialized && this.stateValue.kind !== 'signedOut' &&
          this.stateValue.kind !== 'logoutFailed' && this.stateValue.kind !== 'expired') {
        void this.refresh().catch(() => undefined);
      }
    };
    this.adapter.onAuthLogout = () => {
      if (this.initialized && this.stateValue.kind !== 'signedOut' &&
          this.stateValue.kind !== 'logoutFailed' &&
          this.stateValue.kind !== 'expired') {
        this.invalidate({ kind: 'expired' });
      }
    };
    try {
      if (window.location.pathname !== '/auth/callback' && !sessionStorage.getItem(routeKey)) {
        sessionStorage.setItem(routeKey,
          safeRoute(window.location.pathname + window.location.search + window.location.hash, window.location.origin));
      }
      // The adapter consumes and validates an authorization callback before React routes mount.
      const authenticated = await this.adapter.init({
        onLoad: 'check-sso', pkceMethod: 'S256', checkLoginIframe: false,
        logoutMethod: 'POST',
        redirectUri: this.config.redirectUri,
      });
      if (generation !== this.generation) {
        this.adapter.clearToken();
        return;
      }
      this.initialized = true;
      const intended = sessionStorage.getItem(routeKey);
      sessionStorage.removeItem(routeKey);
      if (window.location.pathname === '/auth/callback') {
        window.history.replaceState(null, '', safeRoute(intended ?? '/', window.location.origin));
      }
      if (!authenticated) { this.publish({ kind: 'signedOut' }); return; }
      await this.loadMe();
    } catch {
      if (generation === this.generation) {
        this.adapter.clearToken();
        if (window.location.pathname === '/auth/callback') {
          window.history.replaceState(null, '', '/');
          sessionStorage.removeItem(routeKey);
        }
        this.publish({ kind: 'unavailable' });
      }
    }
  }

  async login(): Promise<void> {
    sessionStorage.setItem(routeKey,
      safeRoute(window.location.pathname + window.location.search + window.location.hash, window.location.origin));
    try {
      await this.adapter.login({ redirectUri: this.config.redirectUri, scope: 'openid profile email' });
    } catch {
      sessionStorage.removeItem(routeKey);
      this.publish({ kind: 'unavailable' });
    }
  }

  async logout(): Promise<void> {
    sessionStorage.removeItem(routeKey);
    this.generation++;
    this.publish({ kind: 'signedOut' });
    try {
      // Keep the adapter's ID token available while it constructs the OIDC logout URL.
      await this.adapter.logout({ redirectUri: this.config.postLogoutUri, logoutMethod: 'POST' });
    } catch {
      // Local credentials are still discarded, but the identity-provider logout failed.
      this.publish({ kind: 'logoutFailed' });
    } finally {
      this.adapter.clearToken();
    }
  }

  private async refresh(force = false): Promise<void> {
    if (this.refreshPending?.generation === this.generation) return this.refreshPending.promise;
    const generation = this.generation;
    const subject = this.subject();
    const pending = { generation, promise: undefined as unknown as Promise<void> };
    pending.promise = (async () => {
      try {
        await this.adapter.updateToken(force ? -1 : 30);
        if (generation !== this.generation || this.subject() !== subject || !this.adapter.token) {
          throw new StaleSessionError();
        }
      } catch (error) {
        if (generation === this.generation) this.invalidate({ kind: 'expired' });
        else if (this.stateValue.kind === 'signedOut' || this.stateValue.kind === 'logoutFailed') {
          this.adapter.clearToken();
        }
        throw error;
      } finally {
        if (this.refreshPending === pending) this.refreshPending = undefined;
      }
    })();
    this.refreshPending = pending;
    return pending.promise;
  }

  async get(path: string): Promise<Response> {
    if (!path.startsWith('/') || path.startsWith('//')) throw new Error('Invalid API path');
    const generation = this.generation;
    const subject = this.subject();
    await this.refresh();
    this.assertCurrent(generation, subject);
    const send = () => this.request(this.config.apiBaseUrl + path, {
      method: 'GET', headers: { Authorization: `Bearer ${this.adapter.token}` },
      credentials: 'omit', cache: 'no-store', redirect: 'error',
    });
    let response = await send();
    this.assertCurrent(generation, subject);
    if (response.status === 401) {
      await this.refresh(true);
      this.assertCurrent(generation, subject);
      response = await send();
      this.assertCurrent(generation, subject);
      if (response.status === 401) this.invalidate({ kind: 'expired' });
    }
    if (!response.ok) throw new ApiError(response.status);
    return response;
  }

  async loadMe(): Promise<void> {
    const generation = this.generation;
    const subject = this.subject();
    this.publish({ kind: 'loading' });
    try {
      const response = await this.get('/backoffice/me');
      const me = parseMe(await response.json());
      this.assertCurrent(generation, subject);
      if (me.staffSubject !== subject) throw new Error('Subject mismatch');
      this.publish({ kind: 'signedIn', me });
    } catch (error) {
      if (generation !== this.generation) return;
      if (error instanceof ApiError && error.status === 403) this.publish({ kind: 'forbidden' });
      else if (error instanceof StaleSessionError || error instanceof ExpiredSessionError) return;
      else if (this.stateValue.kind !== 'expired') this.publish({ kind: 'unavailable' });
    }
  }
}
