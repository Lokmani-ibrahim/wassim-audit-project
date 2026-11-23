# NEOS staff backoffice — Packages A and B

Package A provides staff sign-in, `GET /api/backoffice/me`, a connected shell and logout. It has no customer or financial reads. The fixture demo is a separate entry.

Package B adds a connected, read-only `/customers` directory and detail view for dedicated compliance staff. The API returns only user ID, email, account status, current KYC status (or null), and creation time. The exact request/response contract, ordering and bounded cursor behavior are in `docs/api/BACKOFFICE_CUSTOMER_DIRECTORY.md`; isolated results are in `docs/testing/PACK_B_ISOLATED_VERIFICATION.md`.

## Local connected run

Use an isolated Keycloak realm and backend. The checked-in realm export adds a **local-development** `neos-backoffice` public client only when imported into a fresh realm. An existing running realm is not changed by editing the export.

1. Copy `.env.example` to `.env.local`, then edit the host/port values to match the isolated setup. The Vite development proxy sends same-origin `/api` calls to `http://localhost:8082`.
2. Configure the isolated backend with `NEOS_BACKOFFICE_ENABLED=true`, `NEOS_BACKOFFICE_AUDIENCE=neos-api`, `NEOS_BACKOFFICE_CLIENT_ID=neos-backoffice`, `NEOS_BACKOFFICE_ISSUER=http://localhost:8081/realms/neos`, and the existing `KEYCLOAK_ISSUER_URI` at that same issuer. Do not enable the staff feature against a shared realm.
3. Start the isolated services, then run `npm ci` and `npm run dev` in this directory. The connected shell requires a dedicated `ADMIN` or `COMPLIANCE_OFFICER` identity, but denies accounts with both staff roles or any `CUSTOMER` role. No account or role is provisioned by this code.

Missing or invalid frontend configuration displays a configuration error. Backend or identity failures never load fixtures. The browser client uses Keycloak Authorization Code + PKCE S256 through pinned, unmodified `keycloak-js` 26.2.5. Upstream stores temporary callback state, including the PKCE verifier and nonce, in `localStorage` with a cookie fallback. This callback state is JavaScript-accessible and its persistence has a security cost; it is distinct from bearer tokens. Access and refresh tokens remain in adapter memory and are not written to browser persistent storage by application code. A same-origin intended route is kept in `sessionStorage`. Reload uses `check-sso` and obtains new tokens from an existing Keycloak SSO session if available. The adapter uses POST logout so its ID-token hint is sent in a form body, not a URL.

The frontend accepts only a same-origin `/api` base URL. In production, serve the static frontend and proxy `/api` to the backend at the same origin. Serve `/auth/callback` with the SPA index fallback. The Keycloak URL, realm, exact callback URL, exact post-logout URL and environment label must be supplied for each deployment at build time. Build-time variables are public and must contain no secret. An explicit label such as `Sandbox (connected)` is suitable for a connected test environment; fixture demo mode is separate.

## Separate fixture demo

Run `npm run dev:demo` and open `/demo.html`, or run `npm run build:demo`. The demo retains its original invented data and simulated actions. Normal development mode returns 404 for `/demo.html`. The normal `npm run build` uses only `index.html` and the production entry and automatically checks the production import graph and output for demo code. `npm run check:production` checks an existing production build. Run production and demo builds separately because Vite replaces `dist` on each build.

## Checks

Run `npm run test`, `npm run build`, and `npm run build:demo`. The unit tests use adapter and fetch doubles; they verify application state handling, not a real OIDC round trip. Backend tests must use Java 21 or newer. The signed-JWT web test uses a real Nimbus decoder for signature, issuer and expiry validation. See `docs/testing/PACK_A_ISOLATED_VERIFICATION.md` for the disposable PostgreSQL/Keycloak/browser run and its remaining rollout gates.

## Browser and deployment security

Use exact HTTPS redirect and web-origin values for each production environment. Do not use wildcard redirects. The deployment's static host or reverse proxy owns `Content-Security-Policy` (at least restrictive `default-src`, `script-src`, `connect-src` and `form-action` for the exact Keycloak origin, and `frame-ancestors`), HSTS, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, and no-store for the HTML entry. These headers are instructions, not verified deployment state. The frontend must be reviewed for XSS because JavaScript can access its in-memory tokens. Same-origin `/api` with bearer headers and `credentials: omit` needs no browser cookie CSRF protection. If a future deployment separates frontend and API origins, backend CORS must explicitly allow only that origin, `GET`, `Authorization`, and preflight `OPTIONS` before authentication, without credentialed cookies; that topology is outside this Package A implementation.

## Operator and backend-owner gates

The fresh-realm client definition lives in `infra/keycloak/realm/neos-realm.json`. For an **existing** realm, an operator must create or update that client in the running realm: public client; standard code flow only; mandatory S256; disabled implicit, direct access, service account, device and CIBA grants; no optional `offline_access`; `fullScopeAllowed=false` with `ADMIN`, `COMPLIANCE_OFFICER` and `CUSTOMER` realm-role visibility; a hardcoded `neos-api` access-token audience mapper; the `basic` scope for a `sub` claim; exact callback, post-logout URI and web origin. Include `CUSTOMER` in the staff token so the backend can reject a mixed-role identity. Provision dedicated staff identities without the realm's default `CUSTOMER` entitlement; the isolated test removed `default-roles-neos` from invented staff accounts while preserving it for the test customer. A role claim alone cannot prove an identity was never used as a customer account. Inspect actual access-token claims in an isolated realm before rollout. Never place a client secret in the frontend. Do not edit or assign staff users automatically.

The existing realm export has 30-minute SSO idle, 10-hour SSO maximum and optional OTP. Mandatory staff MFA and proposed **15-minute staff-client idle / eight-hour staff-client maximum** are rollout decisions for the operator and backend owner. A pre-existing SSO cookie may satisfy a normal browser flow without a new MFA challenge; a client-specific authentication flow or ACR/step-up requirement must be designed and tested in an isolated realm. Automatic refresh can extend idle timers, so the 15-minute value alone does not prove 15 minutes of human inactivity. Client limits are children of realm SSO limits; changing realm-wide flows or timers could change mobile login behavior and is not done here. Do not claim the staff MFA or inactivity policy is enforced until its isolated browser test passes.

Later work must add ADMIN visibility across supported backoffice features and staff-account lifecycle management, with server-side separation of compliance decisions. Before staff management is built, decide who may assign compliance authority, whether administrators may alter their own roles, how combined roles are governed, and how changes are audited and active sessions revoked. ADMIN shell entry in Package A grants no compliance decision permission or customer-data capability.

The backend's dev profile configures `spring.security.oauth2.resourceserver.jwt.issuer-uri`; its prod profile does not. Production operators must explicitly set `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` to the exact realm issuer, as well as the staff-specific issuer property. The staff policy additionally compares `iss`, `aud`, `azp` and effective realm/client roles only on `GET /api/backoffice/me`; it does not change customer, synthetic-credit or reconciliation endpoint authorization. The feature defaults disabled, and enabling it without all staff properties fails startup. Confirm production decoder wiring and existing mobile authentication in an isolated deployment before enabling it.

## Package B module boundary

The new backoffice module composes identity-owned bounded projections and a KYC-owned bulk status lookup. Neither module reads the other's table. No cross-module SQL exception was taken.
