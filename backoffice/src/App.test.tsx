// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import App from './App';
import type { StaffSession, SessionState } from './auth/StaffSession';

afterEach(cleanup);

function shell(state: SessionState, route = '/') {
  window.history.replaceState(null, '', route);
  const session = {
    state,
    subscribe: () => () => undefined,
    logout: vi.fn(async () => undefined),
  } as unknown as StaffSession;
  render(<App session={session} environment="Connected test" />);
  return session;
}

describe('connected shell', () => {
  it('shows no customer or financial navigation before Package B', () => {
    shell({ kind: 'signedIn', me: { staffSubject: 'staff-1', capabilities: [] } });
    expect(screen.getByText('Staff access active')).toBeTruthy();
    expect(screen.queryByText('Customers')).toBeNull();
    expect(screen.queryByText('Transactions')).toBeNull();
  });

  it('does not display demo fixtures at a direct unsupported route', () => {
    shell({ kind: 'signedIn', me: { staffSubject: 'staff-1', capabilities: [] } }, '/users');
    expect(screen.getByText('Page unavailable')).toBeTruthy();
    expect(screen.queryByText('Sandbox')).toBeNull();
  });

  it('shows forbidden state without starting another login', () => {
    shell({ kind: 'forbidden' });
    expect(screen.getByText('Access denied')).toBeTruthy();
    expect(screen.getByText('Sign out')).toBeTruthy();
    expect(screen.queryByText('Sign in')).toBeNull();
  });
});
