import { useEffect, useState, useSyncExternalStore } from 'react';
import type { ReactNode } from 'react';
import type { StaffSession } from './auth/StaffSession';
import { CustomerDetail, CustomerDirectory, followLink, initialDirectoryView } from './customers/Customers';
import type { DirectoryView } from './customers/Customers';

type Props = { session: StaffSession; environment: string };

function Status({ title, message, action }: { title: string; message: string; action?: ReactNode }) {
  return <div className="login-page"><div className="login-card"><div className="brand dark">NEOS<span>BACKOFFICE</span></div><div className="eyebrow">STAFF ACCESS</div><h1>{title}</h1><p>{message}</p>{action}<div className="login-foot">NEOS · {title}</div></div></div>;
}

export default function App({ session, environment }: Props) {
  const state = useSyncExternalStore(session.subscribe.bind(session), () => session.state);
  if (state.kind === 'loading') return <Status title="Checking staff access" message="Verifying your session…" />;
  if (state.kind === 'signedOut') return <Status title="Sign in to backoffice" message="Use your approved staff account." action={<button className="button full" onClick={() => { void session.login(); }}>Sign in</button>} />;
  if (state.kind === 'expired') return <Status title="Session expired" message="Your staff session has ended." action={<button className="button full" onClick={() => { void session.login(); }}>Sign in again</button>} />;
  if (state.kind === 'forbidden') return <Status title="Access denied" message="This account does not have access to the staff backoffice." action={<button className="button full" onClick={() => { void session.logout(); }}>Sign out</button>} />;
  if (state.kind === 'unavailable') return <Status title="Service unavailable" message="Staff access could not be verified. Please retry." action={<div className="dialog-actions"><button className="button secondary" onClick={() => { window.location.reload(); }}>Retry</button><button className="button" onClick={() => { void session.logout(); }}>Sign out</button></div>} />;
  if (state.kind === 'logoutFailed') return <Status title="Sign out incomplete" message="Your local session was cleared, but the identity provider could not confirm sign out. Close this browser window or try again." action={<button className="button full" onClick={() => { void session.logout(); }}>Try sign out again</button>} />;

  return <AuthenticatedShell key={`${state.me.staffSubject}:${state.me.capabilities.join(',')}`} session={session} environment={environment} me={state.me} />;
}

function AuthenticatedShell({ session, environment, me }: Props & { me: { staffSubject: string; capabilities: string[] } }) {
  const [, setRouteVersion] = useState(0);
  const [directoryView, setDirectoryView] = useState<DirectoryView>(initialDirectoryView);
  useEffect(() => {
    const onPopState = () => setRouteVersion(value => value + 1);
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);
  const path = window.location.pathname;
  const navigate = (next: string) => {
    if (next === path) return;
    window.history.pushState(null, '', next);
    setRouteVersion(value => value + 1);
  };
  const canReadCustomers = me.capabilities.includes('CUSTOMER_READ');
  const customerRoute = path === '/customers' || path.startsWith('/customers/');
  const detailId = /^\/customers\/([^/]+)$/.exec(path)?.[1];
  return <div className="shell">
    <aside className="sidebar"><div className="brand">NEOS<span>BACKOFFICE</span></div><div className="nav-label">WORKSPACE</div><nav><a href="/" aria-current={path === '/' ? 'page' : undefined} onClick={event => followLink(event, navigate, '/')}>Home</a>{canReadCustomers && <a href="/customers" aria-current={customerRoute ? 'page' : undefined} onClick={event => followLink(event, navigate, '/customers')}>Customers</a>}</nav><div className="sidebar-bottom"><div className="env-dot" />{environment}</div></aside>
    <div className="main"><header className="topbar"><div className="topbar-context">Staff workspace</div><div className="staff"><div className="avatar">ST</div><div><strong>Staff session</strong><small>{environment}</small></div><button className="text-button" onClick={() => { void session.logout(); }}>Sign out</button></div></header>
      <main className="content">{path === '/' ? <><div className="page-head"><div><h1>Backoffice</h1><p>Signed in to the connected staff workspace.</p></div></div><section className="panel"><h2>Staff access active</h2><p>Your session is connected to the configured NEOS environment.</p></section></> : customerRoute && !canReadCustomers ? <section className="panel"><h1>Access denied</h1><p>This staff account cannot read customers.</p></section> : path === '/customers' ? <CustomerDirectory session={session} view={directoryView} onViewChange={setDirectoryView} navigate={navigate} /> : detailId ? <CustomerDetail key={detailId} session={session} userId={detailId} navigate={navigate} /> : <section className="panel"><h1>Page unavailable</h1><p>This route is not part of the connected backoffice.</p><a href="/">Return home</a></section>}</main>
    </div>
  </div>;
}
