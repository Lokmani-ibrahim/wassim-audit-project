// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import App from '../App';
import { ApiError } from '../auth/StaffSession';
import type { StaffSession, SessionState } from '../auth/StaffSession';
import { parseCustomer, parsePage } from './api';

afterEach(() => { cleanup(); window.history.replaceState(null, '', '/'); });

const first = { userId: '00000000-0000-0000-0000-000000000001', email: 'first@review.invalid',
  accountStatus: 'ACTIVE', kycStatus: null, createdAt: '2026-11-01T10:00:00Z' };
const second = { ...first, userId: '00000000-0000-0000-0000-000000000002', email: 'second@review.invalid' };

function shell(state: SessionState, get: (path: string) => Promise<Response> = vi.fn(async () => Response.json({ items: [first], nextCursor: null }))) {
  const listeners = new Set<() => void>();
  const session = { state, get, logout: vi.fn(async () => undefined),
    subscribe: (listener: () => void) => { listeners.add(listener); return () => listeners.delete(listener); } };
  render(<App session={session as unknown as StaffSession} environment="Connected test" />);
  return { session, publish(next: SessionState) { session.state = next; listeners.forEach(listener => listener()); } };
}

describe('customer response validation', () => {
  it('accepts only the five allowed row fields and a bounded page', () => {
    expect(parseCustomer(first).kycStatus).toBeNull();
    expect(() => parseCustomer({ ...first, phoneNumber: 'private' })).toThrow();
    expect(() => parseCustomer({ ...first, kycStatus: 'UNKNOWN' })).toThrow();
    expect(() => parseCustomer({ ...first, createdAt: '11/01/2026' })).toThrow();
    expect(() => parsePage({ items: [first, first], nextCursor: null })).toThrow();
    expect(() => parsePage({ items: [first], nextCursor: 4 })).toThrow();
  });
});

describe('connected customer routes', () => {
  it('denies an ADMIN direct route without requesting data', () => {
    window.history.replaceState(null, '', '/customers');
    const get = vi.fn();
    shell({ kind: 'signedIn', me: { staffSubject: 'admin', capabilities: [] } }, get);
    expect(screen.getByText('Access denied')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Customers' })).toBeNull();
    expect(get).not.toHaveBeenCalled();
  });

  it('shows only the latest search result and clears data on logout', async () => {
    window.history.replaceState(null, '', '/customers');
    let resolveOld: (value: Response) => void = () => undefined;
    const old = new Promise<Response>(resolve => { resolveOld = resolve; });
    const get = vi.fn().mockReturnValueOnce(old)
      .mockResolvedValueOnce(Response.json({ items: [second], nextCursor: null }));
    const view = shell({ kind: 'signedIn', me: { staffSubject: 'compliance', capabilities: ['CUSTOMER_READ'] } }, get);
    fireEvent.change(screen.getByLabelText('Exact email or user ID'), { target: { value: second.email } });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(screen.getByText(second.email)).toBeTruthy());
    await act(async () => { resolveOld(Response.json({ items: [first], nextCursor: null })); await old; });
    expect(screen.queryByText(first.email)).toBeNull();
    act(() => view.publish({ kind: 'signedOut' }));
    expect(screen.queryByText(second.email)).toBeNull();
  });

  it('shows a missing customer on a direct detail route', async () => {
    window.history.replaceState(null, '', `/customers/${first.userId}`);
    const get = vi.fn().mockRejectedValue(new ApiError(404));
    shell({ kind: 'signedIn', me: { staffSubject: 'compliance', capabilities: ['CUSTOMER_READ'] } }, get);
    await waitFor(() => expect(screen.getByText('Customer not found')).toBeTruthy());
  });

  it('shows a retry state for service failure and clears a row on authorization loss', async () => {
    window.history.replaceState(null, '', '/customers');
    const get = vi.fn().mockRejectedValueOnce(new ApiError(503))
      .mockResolvedValueOnce(Response.json({ items: [first], nextCursor: null }))
      .mockRejectedValueOnce(new ApiError(403));
    const view = shell({ kind: 'signedIn', me: { staffSubject: 'compliance', capabilities: ['CUSTOMER_READ'] } }, get);
    await waitFor(() => expect(screen.getByText('Customer directory unavailable')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(screen.getByText(first.email)).toBeTruthy());
    act(() => view.publish({ kind: 'signedIn', me: { staffSubject: 'compliance', capabilities: [] } }));
    expect(screen.queryByText(first.email)).toBeNull();
    expect(screen.getByText('Access denied')).toBeTruthy();
  });

  it('returns from detail to the same search, filters and page', async () => {
    window.history.replaceState(null, '', '/customers');
    const get = vi.fn(async (path: string) => {
      if (path.endsWith('/wallets')) return Response.json({ retrievedAt: first.createdAt, wallets: [] });
      if (path.includes('/transactions?')) return Response.json({ items: [], hasMore: false, nextCursor: null });
      if (path === `/backoffice/customers/${second.userId}`) return Response.json(second);
      if (path.includes('cursor=page-two')) return Response.json({ items: [second], nextCursor: null });
      return Response.json({ items: [first], nextCursor: 'page-two' });
    });
    shell({ kind: 'signedIn', me: { staffSubject: 'compliance', capabilities: ['CUSTOMER_READ'] } }, get);
    fireEvent.change(screen.getByLabelText('Exact email or user ID'), { target: { value: 'second@review.invalid' } });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    fireEvent.change(screen.getByLabelText('Account status'), { target: { value: 'ACTIVE' } });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Next page' })).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => expect(screen.getByRole('link', { name: second.email })).toBeTruthy());
    fireEvent.click(screen.getByRole('link', { name: second.email }));
    await waitFor(() => expect(screen.getByText('Wallets and balances')).toBeTruthy());
    fireEvent.click(screen.getByRole('link', { name: '← Customers' }));
    await waitFor(() => expect(screen.getByRole('link', { name: second.email })).toBeTruthy());
    expect(screen.getByLabelText<HTMLInputElement>('Exact email or user ID').value).toBe(second.email);
    expect(screen.getByLabelText<HTMLSelectElement>('Account status').value).toBe('ACTIVE');
    expect(screen.getByRole('button', { name: 'Previous' })).toBeTruthy();
    expect(get.mock.calls.some(([path]) => path.includes('cursor=page-two'))).toBe(true);
  });

  it('clears directory state on logout and account change', async () => {
    window.history.replaceState(null, '', '/customers');
    const view = shell({ kind: 'signedIn', me: { staffSubject: 'first-staff', capabilities: ['CUSTOMER_READ'] } });
    fireEvent.change(screen.getByLabelText('Exact email or user ID'), { target: { value: first.email } });
    act(() => view.publish({ kind: 'signedIn', me: { staffSubject: 'second-staff', capabilities: ['CUSTOMER_READ'] } }));
    expect(screen.getByLabelText<HTMLInputElement>('Exact email or user ID').value).toBe('');
    fireEvent.change(screen.getByLabelText('Exact email or user ID'), { target: { value: first.email } });
    act(() => view.publish({ kind: 'signedOut' }));
    act(() => view.publish({ kind: 'signedIn', me: { staffSubject: 'second-staff', capabilities: ['CUSTOMER_READ'] } }));
    expect(screen.getByLabelText<HTMLInputElement>('Exact email or user ID').value).toBe('');
  });
});
