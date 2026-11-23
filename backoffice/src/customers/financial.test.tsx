// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import App from '../App';
import type { StaffSession, SessionState } from '../auth/StaffSession';
import { formatMinor, parseTransactionPage, parseWalletPage } from './financial';

const userId = '00000000-0000-0000-0000-000000000001';
const customer = { userId, email: 'synthetic@review.invalid', accountStatus: 'ACTIVE',
  kycStatus: null, createdAt: '2026-11-01T10:00:00Z' };
const wallets = { retrievedAt: '2026-11-09T12:00:00Z', wallets: [
  { currency: 'TND', scale: 3, status: 'ACTIVE', availableMinor: '9007199254740993123', pendingMinor: '0' }] };
const empty = { items: [], hasMore: false, nextCursor: null };
const item = { transactionId: '123', type: 'TRANSFER', direction: 'DEBIT', currency: 'EUR',
  scale: 2, amountMinor: '12345', status: 'COMPLETED', occurredAt: '2026-11-09T12:00:00Z' };

afterEach(() => { cleanup(); window.history.replaceState(null, '', '/'); });

function shell(get: (path: string) => Promise<Response>) {
  let state: SessionState = { kind: 'signedIn', me: { staffSubject: 'compliance', capabilities: ['CUSTOMER_READ'] } };
  const listeners = new Set<() => void>();
  const session = { get: vi.fn(get), logout: vi.fn(), get state() { return state; },
    subscribe: (listener: () => void) => { listeners.add(listener); return () => listeners.delete(listener); } };
  window.history.replaceState(null, '', `/customers/${userId}`);
  const view = render(<App session={session as unknown as StaffSession} environment="Test" />);
  return { session, publish(next: SessionState) { state = next; listeners.forEach(listener => listener()); },
    rerender() { view.rerender(<App session={session as unknown as StaffSession} environment="Test" />); } };
}

describe('financial contract', () => {
  it('formats exact minor-unit strings without Number conversion', () => {
    expect(formatMinor('0', 2)).toBe('0.00');
    expect(formatMinor('9007199254740993123', 3)).toBe('9007199254740993.123');
    expect(formatMinor('-123', 3)).toBe('-0.123');
    expect(() => formatMinor('1.5', 2)).toThrow();
  });

  it('rejects extra fields, wrong scales and malformed history', () => {
    expect(parseWalletPage(wallets).wallets[0].availableMinor).toBe('9007199254740993123');
    expect(parseWalletPage({ ...wallets, wallets: [{ ...wallets.wallets[0], availableMinor: null }] })
      .wallets[0].availableMinor).toBeNull();
    expect(() => parseWalletPage({ ...wallets, wallets: [{ ...wallets.wallets[0], scale: 2 }] })).toThrow();
    expect(() => parseWalletPage({ ...wallets, accountNumber: 'private' })).toThrow();
    expect(() => parseTransactionPage({ items: [item], hasMore: false, nextCursor: null }, 'EUR')).not.toThrow();
    expect(() => parseTransactionPage({ items: [item], hasMore: true, nextCursor: null }, 'EUR')).toThrow();
    expect(() => parseTransactionPage({ ...empty, items: [{ ...item, currency: 'TND', scale: 3 }] }, 'EUR')).toThrow();
  });
});

describe('financial detail', () => {
  it('renders precise balances and history and clears them on authorization loss', async () => {
    const view = shell(async path => Response.json(path.endsWith('/wallets') ? wallets :
      path.includes('/transactions') ? { items: [item], hasMore: false, nextCursor: null } : customer));
    await waitFor(() => expect(screen.getByText('9007199254740993.123 TND')).toBeTruthy());
    expect(screen.getByText('123.45 EUR')).toBeTruthy();
    act(() => view.publish({ kind: 'signedIn', me: { staffSubject: 'compliance', capabilities: [] } }));
    expect(screen.queryByText('9007199254740993.123 TND')).toBeNull();
    expect(screen.getByText('Access denied')).toBeTruthy();
  });

  it('ignores stale history after a currency switch', async () => {
    let resolveOld: (value: Response) => void = () => undefined;
    const old = new Promise<Response>(resolve => { resolveOld = resolve; });
    shell(path => path.endsWith('/wallets') ? Promise.resolve(Response.json(wallets)) :
      path.includes('currency=EUR') ? old : path.includes('/transactions') ?
        Promise.resolve(Response.json(empty)) : Promise.resolve(Response.json(customer)));
    await waitFor(() => expect(screen.getByLabelText('Transaction currency')).toBeTruthy());
    fireEvent.change(screen.getByLabelText('Transaction currency'), { target: { value: 'TND' } });
    await waitFor(() => expect(screen.getByText('No transactions.')).toBeTruthy());
    await act(async () => { resolveOld(Response.json({ items: [item], hasMore: false, nextCursor: null })); await old; });
    expect(screen.queryByText('123.45 EUR')).toBeNull();
  });

  it('never displays the previous customer wallet after route switching', async () => {
    const otherId = '00000000-0000-0000-0000-000000000002';
    const other = { ...customer, userId: otherId, email: 'other@review.invalid' };
    let resolveOld: (value: Response) => void = () => undefined;
    const old = new Promise<Response>(resolve => { resolveOld = resolve; });
    const view = shell(path => path.endsWith('/wallets') ?
      (path.includes(userId) ? old : Promise.resolve(Response.json({ ...wallets, wallets: [
        { currency: 'TND', scale: 3, status: 'ACTIVE', availableMinor: '2000', pendingMinor: '0' }] }))) :
      path.includes('/transactions') ? Promise.resolve(Response.json(empty)) :
        Promise.resolve(Response.json(path.includes(otherId) ? other : customer)));
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Wallets and balances' })).toBeTruthy());
    window.history.replaceState(null, '', `/customers/${otherId}`);
    view.rerender();
    await waitFor(() => expect(screen.getByText('2.000 TND')).toBeTruthy());
    await act(async () => { resolveOld(Response.json(wallets)); await old; });
    expect(screen.queryByText('9007199254740993.123 TND')).toBeNull();
  });
});
