import { useEffect, useState } from 'react';
import type { MouseEvent } from 'react';
import { ApiError } from '../auth/StaffSession';
import type { StaffSession } from '../auth/StaffSession';
import { getCustomer, listCustomers } from './api';
import type { Customer, CustomerPage, Filters } from './api';
import { currencies, formatMinor, getTransactions, getWallets } from './financial';
import type { TransactionPage, WalletPage } from './financial';

export type DirectoryView = {
  searchInput: string;
  filters: Filters;
  cursor: string | null;
  previous: (string | null)[];
};
export const initialDirectoryView: DirectoryView = {
  searchInput: '', filters: { search: '', accountStatus: '', kycStatus: '' },
  cursor: null, previous: [],
};
type Navigate = (path: string) => void;

export function followLink(event: MouseEvent<HTMLAnchorElement>, navigate: Navigate, path: string) {
  if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
  event.preventDefault();
  navigate(path);
}

type Load<T> = { kind: 'loading' } | { kind: 'ready'; value: T } |
  { kind: 'forbidden' | 'missing' | 'unavailable' };

function failure(error: unknown): Load<never> {
  if (error instanceof ApiError && error.status === 403) return { kind: 'forbidden' };
  if (error instanceof ApiError && error.status === 404) return { kind: 'missing' };
  return { kind: 'unavailable' };
}

function State({ kind, retry, navigate, detail = false }: { kind: string; retry: () => void; navigate?: Navigate; detail?: boolean }) {
  const label = kind === 'forbidden' ? 'Access denied' : kind === 'missing' ? 'Customer not found' :
    kind === 'loading' ? (detail ? 'Loading customer…' : 'Loading customers…') :
      (detail ? 'Customer detail unavailable' : 'Customer directory unavailable');
  return <section className="panel" role="status"><h2>{label}</h2>
    {kind === 'unavailable' && <button className="button secondary" onClick={retry}>Retry</button>}
    {kind === 'missing' && <a href="/customers" onClick={event => navigate && followLink(event, navigate, '/customers')}>Return to customers</a>}
  </section>;
}

function CustomerFields({ customer }: { customer: Customer }) {
  return <dl className="customer-fields">
    <dt>User ID</dt><dd>{customer.userId}</dd>
    <dt>Email</dt><dd>{customer.email}</dd>
    <dt>Account status</dt><dd>{customer.accountStatus}</dd>
    <dt>KYC status</dt><dd>{customer.kycStatus ?? 'No KYC record'}</dd>
    <dt>Created</dt><dd>{new Date(customer.createdAt).toLocaleString()}</dd>
  </dl>;
}

function FinancialState({ kind, retry, resource }: { kind: string; retry: () => void; resource: string }) {
  const label = kind === 'loading' ? `Loading ${resource}…` : kind === 'forbidden' ? 'Access denied' :
    kind === 'missing' ? 'Customer not found' : 'Ledger service unavailable';
  return <div role="status"><p>{label}</p>{kind === 'unavailable' &&
    <button className="button secondary" onClick={retry}>Retry</button>}</div>;
}

function CustomerFinancials({ session, userId }: { session: StaffSession; userId: string }) {
  const [wallets, setWallets] = useState<Load<WalletPage>>({ kind: 'loading' });
  const [walletRetry, setWalletRetry] = useState(0);
  const [currency, setCurrency] = useState<string>('EUR');
  const [cursor, setCursor] = useState<string | null>(null);
  const [previous, setPrevious] = useState<(string | null)[]>([]);
  const [history, setHistory] = useState<Load<TransactionPage>>({ kind: 'loading' });
  const [historyRetry, setHistoryRetry] = useState(0);

  useEffect(() => {
    let current = true;
    setWallets({ kind: 'loading' });
    void getWallets(session, userId).then(value => {
      if (current) setWallets({ kind: 'ready', value });
    }).catch(error => { if (current) setWallets(failure(error)); });
    return () => { current = false; };
  }, [session, userId, walletRetry]);

  useEffect(() => {
    let current = true;
    setHistory({ kind: 'loading' });
    void getTransactions(session, userId, currency, cursor).then(value => {
      if (current) setHistory({ kind: 'ready', value });
    }).catch(error => { if (current) setHistory(failure(error)); });
    return () => { current = false; };
  }, [session, userId, currency, cursor, historyRetry]);

  return <>
    <section className="panel"><h2>Wallets and balances</h2>
      {wallets.kind !== 'ready' ? <FinancialState kind={wallets.kind} resource="wallets" retry={() => setWalletRetry(n => n + 1)} /> : <>
        <p>Retrieved {new Date(wallets.value.retrievedAt).toLocaleString()} · Retrieval time, not a ledger snapshot</p>
        {wallets.value.wallets.length === 0 ? <p>No provisioned wallets.</p> :
          <div className="customer-table-wrap" tabIndex={0} role="region" aria-label="Wallet balances table"><table className="customer-table"><thead><tr>
            <th>Currency</th><th>Status</th><th>Available</th><th>Pending</th>
          </tr></thead><tbody>{wallets.value.wallets.map(wallet => <tr key={wallet.currency}>
            <td>{wallet.currency}</td><td>{wallet.status}</td>
            <td className="customer-amount">{wallet.availableMinor === null ? 'Unavailable' : `${formatMinor(wallet.availableMinor, wallet.scale)} ${wallet.currency}`}</td>
            <td className="customer-amount">{wallet.pendingMinor === null ? 'Unavailable' : `${formatMinor(wallet.pendingMinor, wallet.scale)} ${wallet.currency}`}</td>
          </tr>)}</tbody></table></div>}
      </>}
    </section>
    <section className="panel"><h2>Transaction history</h2>
      <label>Transaction currency <select value={currency} onChange={event => {
        setCurrency(event.target.value); setCursor(null); setPrevious([]); setHistory({ kind: 'loading' });
      }}>{currencies.map(value => <option key={value} value={value}>{value}</option>)}</select></label>
      {history.kind !== 'ready' ? <FinancialState kind={history.kind} resource="transactions" retry={() => setHistoryRetry(n => n + 1)} /> : <>
        {history.value.items.length === 0 ? <p>{history.value.hasMore ? 'No visible transactions on this page. Continue to search.' : 'No transactions.'}</p> :
          <div className="customer-table-wrap" tabIndex={0} role="region" aria-label="Transaction history table"><table className="customer-table"><thead><tr>
            <th>Occurred</th><th>Type</th><th>Direction</th><th>Amount</th><th>Status</th><th>Transaction ID</th>
          </tr></thead><tbody>{history.value.items.map(item => <tr key={item.transactionId}>
            <td>{new Date(item.occurredAt).toLocaleString()}</td><td>{item.type}</td><td>{item.direction}</td>
            <td className="customer-amount">{formatMinor(item.amountMinor, item.scale)} {item.currency}</td><td>{item.status}</td><td>{item.transactionId}</td>
          </tr>)}</tbody></table></div>}
        <div className="customer-pagination">
          {previous.length > 0 && <button className="button secondary" onClick={() => {
            setCursor(previous.at(-1) ?? null); setPrevious(value => value.slice(0, -1));
          }}>Previous</button>}
          {history.value.nextCursor && <button className="button secondary" onClick={() => {
            setPrevious(value => [...value, cursor]); setCursor(history.value.nextCursor);
          }}>Next page</button>}
        </div>
      </>}
    </section>
  </>;
}

export function CustomerDetail({ session, userId, navigate }: { session: StaffSession; userId: string; navigate: Navigate }) {
  const [load, setLoad] = useState<Load<Customer>>({ kind: 'loading' });
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let current = true;
    setLoad({ kind: 'loading' });
    void getCustomer(session, userId).then(value => {
      if (current) setLoad({ kind: 'ready', value });
    }).catch(error => { if (current) setLoad(failure(error)); });
    return () => { current = false; };
  }, [session, userId, retry]);
  return <><div className="page-head"><div><a href="/customers" onClick={event => followLink(event, navigate, '/customers')}>← Customers</a><h1>Customer detail</h1></div></div>
    {load.kind === 'ready' ? <><section className="panel"><CustomerFields customer={load.value} /></section>
      <CustomerFinancials key={userId} session={session} userId={userId} /></> :
      <State kind={load.kind} retry={() => setRetry(value => value + 1)} navigate={navigate} detail />}</>;
}

export function CustomerDirectory({ session, view, onViewChange, navigate }: {
  session: StaffSession; view: DirectoryView; onViewChange: (view: DirectoryView) => void; navigate: Navigate;
}) {
  const { searchInput, filters, cursor, previous } = view;
  const [load, setLoad] = useState<Load<CustomerPage>>({ kind: 'loading' });
  const [retry, setRetry] = useState(0);

  useEffect(() => {
    let current = true;
    setLoad({ kind: 'loading' });
    void listCustomers(session, filters, cursor).then(value => {
      if (current) setLoad({ kind: 'ready', value });
    }).catch(error => { if (current) setLoad(failure(error)); });
    return () => { current = false; };
  }, [session, filters, cursor, retry]);

  const change = (next: Filters) => {
    onViewChange({ searchInput, filters: next, cursor: null, previous: [] });
    setLoad({ kind: 'loading' });
  };
  return <><div className="page-head"><div><h1>Customers</h1><p>Connected read-only directory</p></div></div>
    <form className="customer-filters" onSubmit={event => { event.preventDefault(); change({ ...filters, search: searchInput }); }}>
      <label>Exact email or user ID<input value={searchInput}
        onChange={event => onViewChange({ ...view, searchInput: event.target.value })} /></label>
      <button className="button" type="submit">Search</button>
      <label>Account status<select value={filters.accountStatus}
        onChange={event => change({ ...filters, accountStatus: event.target.value })}>
        <option value="">All</option>{['PENDING_KYC', 'ACTIVE', 'SUSPENDED', 'CLOSED'].map(value =>
          <option key={value} value={value}>{value}</option>)}</select></label>
      <label>KYC status<select value={filters.kycStatus}
        onChange={event => change({ ...filters, kycStatus: event.target.value })}>
        <option value="">All</option><option value="MISSING">No KYC record</option>
        {['NOT_STARTED', 'PENDING', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'RESUBMISSION_REQUIRED'].map(value =>
          <option key={value} value={value}>{value}</option>)}</select></label>
    </form>
    {load.kind !== 'ready' ? <State kind={load.kind} retry={() => setRetry(value => value + 1)} /> : <section className="panel">
      {load.value.items.length === 0 ? <p>{load.value.nextCursor ? 'No matches in this scan window. Continue to search.' : 'No customers match these filters.'}</p> :
        <div className="customer-table-wrap" tabIndex={0} role="region" aria-label="Customers table"><table className="customer-table"><thead><tr>
          <th>Email</th><th>User ID</th><th>Account status</th><th>KYC status</th><th>Created</th>
        </tr></thead><tbody>{load.value.items.map(customer => <tr key={customer.userId}>
          <td><a href={`/customers/${customer.userId}`} onClick={event => followLink(event, navigate, `/customers/${customer.userId}`)}>{customer.email}</a></td>
          <td>{customer.userId}</td><td>{customer.accountStatus}</td>
          <td>{customer.kycStatus ?? 'No KYC record'}</td>
          <td>{new Date(customer.createdAt).toLocaleDateString()}</td>
        </tr>)}</tbody></table></div>}
      <div className="customer-pagination">
        {previous.length > 0 && <button className="button secondary" onClick={() => {
          onViewChange({ ...view, cursor: previous.at(-1) ?? null, previous: previous.slice(0, -1) });
        }}>Previous</button>}
        {load.value.nextCursor && <button className="button secondary" onClick={() => {
          onViewChange({ ...view, previous: [...previous, cursor], cursor: load.value.nextCursor });
        }}>Next page</button>}
      </div>
    </section>}</>;
}
