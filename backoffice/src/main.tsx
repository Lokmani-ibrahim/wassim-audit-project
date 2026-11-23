import React from 'react';
import ReactDOM from 'react-dom/client';
import Keycloak from 'keycloak-js';
import App from './App';
import { StaffSession } from './auth/StaffSession';
import { parseConfig } from './config';
import './production.css';
import './customers/customers.css';

const root = ReactDOM.createRoot(document.getElementById('root')!);

try {
  const config = parseConfig(import.meta.env, window.location.origin);
  const session = new StaffSession(new Keycloak({
    url: config.keycloakUrl, realm: config.realm, clientId: config.clientId,
  }), config);
  root.render(<div className="login-page"><div className="login-card" role="status">Checking staff access…</div></div>);
  // keycloak-js processes the callback before normal routes are mounted.
  void session.start().then(() => {
    root.render(<React.StrictMode><App session={session} environment={config.environmentLabel} /></React.StrictMode>);
  });
} catch {
  root.render(<div className="login-page"><div className="login-card" role="alert"><div className="brand dark">NEOS<span>BACKOFFICE</span></div><h1>Configuration error</h1><p>Staff access is unavailable. Contact the deployment operator.</p></div></div>);
}
