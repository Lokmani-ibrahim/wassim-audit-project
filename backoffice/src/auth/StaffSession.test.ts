// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { BackofficeConfig } from '../config';
import { ApiError, StaffSession, safeRoute, type Adapter } from './StaffSession';

const config: BackofficeConfig = {
  keycloakUrl: 'http://localhost:8081', realm: 'neos', clientId: 'neos-backoffice',
  apiBaseUrl: 'http://localhost:3000/api', redirectUri: 'http://localhost:3000/auth/callback',
  postLogoutUri: 'http://localhost:3000/', environmentLabel: 'Local',
};

function setup(response: () => Promise<Response> = async () => Response.json({ staffSubject: 'staff-1', capabilities: [] })) {
  const adapter = {
    token: 'test-token', tokenParsed: { sub: 'staff-1' },
    init: vi.fn(async () => true), login: vi.fn(async () => undefined),
    logout: vi.fn(async () => undefined), updateToken: vi.fn(async () => false),
    clearToken: vi.fn(function (this: { token?: string }) { this.token = undefined; }),
  };
  const request = vi.fn(response);
  const session = new StaffSession(adapter as unknown as Adapter, config, request as unknown as typeof fetch);
  return { adapter, request, session };
}

beforeEach(() => {
  sessionStorage.clear();
  window.history.replaceState(null, '', '/');
});

describe('staff session', () => {
  it('initializes the adapter with check-sso and S256 before loading /me', async () => {
    const { adapter, session, request } = setup();
    await session.start();
    expect(adapter.init).toHaveBeenCalledWith(expect.objectContaining({ onLoad: 'check-sso', pkceMethod: 'S256', checkLoginIframe: false, logoutMethod: 'POST' }));
    expect(request).toHaveBeenCalledWith(config.apiBaseUrl + '/backoffice/me', expect.objectContaining({ credentials: 'omit' }));
    expect(session.state.kind).toBe('signedIn');
  });

  it('calls the default browser fetch without a StaffSession receiver', async () => {
    const { adapter } = setup();
    const originalFetch = globalThis.fetch;
    const browserLikeFetch = vi.fn(function (this: unknown) {
      if (this !== undefined) throw new TypeError('Invalid fetch receiver');
      return Promise.resolve(Response.json({ staffSubject: 'staff-1', capabilities: [] }));
    });
    globalThis.fetch = browserLikeFetch as typeof fetch;
    try {
      const session = new StaffSession(adapter as unknown as Adapter, config);
      await session.start();
      expect(session.state.kind).toBe('signedIn');
      expect(browserLikeFetch).toHaveBeenCalledTimes(1);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('restores only same-origin routes after callback', async () => {
    window.history.replaceState(null, '', '/auth/callback');
    sessionStorage.setItem('neos.backoffice.intendedRoute', '/users');
    const { session } = setup();
    await session.start();
    expect(window.location.pathname).toBe('/users');
    expect(safeRoute('//evil.example/', window.location.origin)).toBe('/');
    expect(safeRoute('/auth/callback', window.location.origin)).toBe('/');
  });

  it('clears a failed callback URL and remains unavailable', async () => {
    window.history.replaceState(null, '', '/auth/callback?code=one-time-code&state=state-value');
    const { session, adapter } = setup();
    adapter.init.mockRejectedValue(new Error('invalid callback'));
    await session.start();
    expect(window.location.pathname + window.location.search).toBe('/');
    expect(session.state.kind).toBe('unavailable');
  });

  it('shows login and identity-provider logout failures without retaining local credentials', async () => {
    const login = setup();
    login.adapter.init.mockResolvedValue(false);
    await login.session.start();
    login.adapter.login.mockRejectedValue(new Error('identity provider unavailable'));
    await login.session.login();
    expect(login.session.state.kind).toBe('unavailable');
    expect(sessionStorage.getItem('neos.backoffice.intendedRoute')).toBeNull();

    const logout = setup();
    await logout.session.start();
    logout.adapter.logout.mockRejectedValue(new Error('identity provider unavailable'));
    await logout.session.logout();
    expect(logout.session.state.kind).toBe('logoutFailed');
    expect(logout.adapter.token).toBeUndefined();
  });

  it('allows retry once after 401 and never retries 403', async () => {
    let calls = 0;
    const first = setup(async () => {
      calls++;
      return calls === 1 ? new Response(null, { status: 401 }) : Response.json({ staffSubject: 'staff-1', capabilities: [] });
    });
    await first.session.start();
    expect(calls).toBe(2);
    expect(first.adapter.updateToken).toHaveBeenCalledWith(-1);

    const denied = setup(async () => new Response(null, { status: 403 }));
    await denied.session.start();
    expect(denied.session.state.kind).toBe('forbidden');
    expect(denied.request).toHaveBeenCalledTimes(1);
    expect(denied.adapter.updateToken).not.toHaveBeenCalledWith(-1);

    const expired = setup(async () => new Response(null, { status: 401 }));
    await expired.session.start();
    expect(expired.request).toHaveBeenCalledTimes(2);
    expect(expired.session.state.kind).toBe('expired');
  });

  it('uses one refresh for concurrent safe requests', async () => {
    const { session, adapter } = setup();
    await session.start();
    let release!: () => void;
    adapter.updateToken.mockImplementation(() => new Promise<boolean>(r => { release = () => r(false); }));
    const first = session.get('/backoffice/me');
    const second = session.get('/backoffice/me');
    expect(adapter.updateToken).toHaveBeenCalledTimes(2); // initial /me plus one shared refresh
    release();
    await Promise.all([first, second]);
    expect(adapter.updateToken).toHaveBeenCalledTimes(2);
  });

  it('does not restore a session when refresh finishes after logout', async () => {
    const { session, adapter, request } = setup();
    await session.start();
    let finish!: () => void;
    adapter.updateToken.mockImplementation(() => new Promise<boolean>(resolve => {
      finish = () => { adapter.token = 'late-token'; resolve(false); };
    }));
    const stale = session.get('/backoffice/me');
    await session.logout();
    finish();
    await expect(stale).rejects.toBeDefined();
    expect(session.state.kind).toBe('signedOut');
    expect(adapter.token).toBeUndefined();
    expect(request).toHaveBeenCalledTimes(1);
  });

  it('does not clear a replacement session when an old refresh fails', async () => {
    let responseSubject = 'staff-1';
    const { session, adapter } = setup(async () => Response.json({ staffSubject: responseSubject, capabilities: [] }));
    await session.start();
    let rejectOld!: (error: Error) => void;
    adapter.updateToken
      .mockImplementationOnce(() => new Promise<boolean>((_, reject) => { rejectOld = reject; }))
      .mockResolvedValueOnce(false);
    const stale = session.get('/backoffice/me');
    await session.logout();
    adapter.token = 'replacement-token';
    adapter.tokenParsed.sub = 'staff-2';
    responseSubject = 'staff-2';
    await session.loadMe();
    expect(session.state.kind).toBe('signedIn');
    rejectOld(new Error('old refresh rejected'));
    await expect(stale).rejects.toBeDefined();
    expect(adapter.token).toBe('replacement-token');
    expect(session.state.kind).toBe('signedIn');
  });

  it('keeps a newer refresh pending when an old refresh completes', async () => {
    let responseSubject = 'staff-1';
    const { session, adapter } = setup(async () => Response.json({ staffSubject: responseSubject, capabilities: [] }));
    await session.start();
    let finishOld!: (value: boolean) => void;
    let finishNew!: (value: boolean) => void;
    adapter.updateToken
      .mockImplementationOnce(() => new Promise<boolean>(resolve => { finishOld = resolve; }))
      .mockImplementationOnce(() => new Promise<boolean>(resolve => { finishNew = resolve; }));
    const stale = session.get('/backoffice/me');
    await session.logout();
    adapter.token = 'replacement-token';
    adapter.tokenParsed.sub = 'staff-2';
    responseSubject = 'staff-2';
    const replacement = session.loadMe();
    expect(adapter.updateToken).toHaveBeenCalledTimes(3); // initial, old, replacement
    finishOld(false);
    await expect(stale).rejects.toBeDefined();
    const concurrent = session.get('/backoffice/me');
    expect(adapter.updateToken).toHaveBeenCalledTimes(3); // still sharing replacement refresh
    finishNew(false);
    await Promise.all([replacement, concurrent]);
    expect(session.state.kind).toBe('signedIn');
  });

  it('expires after rejected refresh and clears the token', async () => {
    const { adapter, session } = setup();
    adapter.updateToken.mockRejectedValue(new Error('refresh rejected'));
    await session.start();
    expect(session.state.kind).toBe('expired');
    expect(adapter.clearToken).toHaveBeenCalled();
  });

  it('does not allow a pending response to restore state after logout', async () => {
    let resolve!: (response: Response) => void;
    const pending = new Promise<Response>(r => { resolve = r; });
    const { session, adapter } = setup(() => pending);
    const start = session.start();
    await vi.waitFor(() => expect(session.state.kind).toBe('loading'));
    await session.logout();
    resolve(Response.json({ staffSubject: 'staff-1', capabilities: [] }));
    await start;
    expect(session.state.kind).toBe('signedOut');
    expect(adapter.logout).toHaveBeenCalledWith({ redirectUri: config.postLogoutUri, logoutMethod: 'POST' });
  });

  it('clears a token from an authentication callback that finishes after logout', async () => {
    let finish!: (authenticated: boolean) => void;
    const { session, adapter } = setup();
    adapter.init.mockImplementation(() => new Promise<boolean>(r => { finish = r; }));
    const start = session.start();
    await session.logout();
    adapter.token = 'late-token';
    finish(true);
    await start;
    expect(adapter.token).toBeUndefined();
    expect(session.state.kind).toBe('signedOut');
  });

  it('clears protected state when the principal changes during a request', async () => {
    let resolve!: (response: Response) => void;
    const pending = new Promise<Response>(r => { resolve = r; });
    const { session, adapter } = setup(() => pending);
    const start = session.start();
    await vi.waitFor(() => expect(adapter.updateToken).toHaveBeenCalled());
    adapter.tokenParsed.sub = 'different-staff';
    resolve(Response.json({ staffSubject: 'staff-1', capabilities: [] }));
    await start;
    expect(session.state.kind).toBe('expired');
  });

  it('rejects malformed /me data and keeps the service unavailable', async () => {
    const { session } = setup(async () => Response.json({ staffSubject: 'staff-1', capabilities: ['CUSTOMERS_READ'] }));
    await session.start();
    expect(session.state.kind).toBe('unavailable');
  });

  it('surfaces API errors with status', () => {
    expect(new ApiError(503).status).toBe(503);
  });
});
