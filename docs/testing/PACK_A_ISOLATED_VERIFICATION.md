# Package A isolated verification — 2026-11-09

This report records a local code-review verification run. It is not deployment approval. No shared realm, customer/provider service, original worktree, or original index was changed. No credential, bearer token, authorization code, PKCE verifier, browser trace, or screenshot is retained here.

## Baseline and infrastructure

- Implementation worktree: `C:\Users\LENOVO\Documents\Personal\Projects\NEOS-package-a`; branch `feat/backoffice-package-a`; HEAD `74dcd382e39d08f698e50c2d6f0d1f1ef04fb70a`; changes remain uncommitted.
- Original worktree: `C:\Users\LENOVO\Documents\Personal\Projects\NEOS`; branch `feat/backoffice-demo` at the same SHA. Its staged mobile, documentation and demo changes were left untouched.
- Preserved snapshot: `C:\Users\LENOVO\Documents\Personal\Projects\NEOS-package-a-baseline`; 13 files matched `SHA256SUMS.txt`, with `backoffice-staged.patch` retained.
- Docker Desktop 4.47.0, Linux Engine 28.4.0, Compose v2.39.4 responded to `docker version`, `docker info --format '{{.OSType}}'`, and `docker compose version`.
- Disposable Compose project `neospacc8a544ed8` used PostgreSQL 18 for the backend and Keycloak, Keycloak 26.7.2, separate named volumes, and loopback ports 5433 and 8081. The Spring backend listened on 8082. Vite production preview listened on 5173 and proxied `/api` to the backend. Java 23 was used. The realm was sanitized from the checked-in export before import; only invented `review.invalid` accounts were provisioned there. The project and its volumes were removed after verification.
- Verification scripts were prepared outside tracked implementation files at `$env:TEMP\neos-package-a-isolated-verification`. `prepare.ps1` creates a unique Compose project and random local credentials after checking ports; `start.ps1` starts its containers and provisions invented accounts; `test-backend.ps1` sets the isolated datasource and issuer; `run-backend.ps1` and `run-frontend.ps1` start the app; `stop.ps1` targets only the recorded project. The local `.env` is removed after teardown. To repeat on this machine, start Docker Desktop with the Linux engine, run `prepare.ps1`, then `start.ps1`, `run-backend.ps1` and `run-frontend.ps1` in separate PowerShell terminals. Run `test-backend.ps1 -Full` and the three `browser-*.mjs` scripts from that temporary directory, then `stop.ps1`. The extra ADMIN+CUSTOMER browser case requires running `add-admin-customer.sh` in the disposable Keycloak container first. No password or token should be printed or captured.

## Executed checks

| Command/check | Result |
| --- | --- |
| `& "$env:TEMP\neos-package-a-isolated-verification\test-backend.ps1"` | Focused staff policy, `/me`, disabled feature, customer security, synthetic-credit access control, reconciliation access control, and Spring Modulith: 32 tests, 0 failures, 0 errors, 0 skips. |
| `& "$env:TEMP\neos-package-a-isolated-verification\test-backend.ps1" -Full` | Maven `test` BUILD SUCCESS; 125 Surefire reports, 634 tests, 0 failures, 0 errors, 60 skipped, 574 executed. Disposable PostgreSQL was used. |
| `npm run test` in `backoffice` after the default-fetch correction | 3 files, 21 tests passed, 0 failed. Includes logout and refresh generation races, bounded retry, 403 behavior, callback routes, configuration, and default browser-fetch receiver regression. |
| `npm run build` in `backoffice` | TypeScript and Vite passed; automatic production import-graph and artifact checks passed (3 assets). Repeated with isolated, nonsecret build settings for production-preview browser testing; passed again. |
| `npm run build:demo` in `backoffice` | Separate demo build passed before the later production-only fetch regression test and documentation edits; no demo source or dependency changed afterward. |
| Clean `npm ci` in an isolated copy | Passed in the prior verification pass. Installed `keycloak-js` 26.2.5 matched the unmodified upstream package; no adapter patch or `patch-package` hook remains. |
| `npm audit --omit=dev --json` | 0 production dependency findings. |
| `npm audit --json` | 2 moderate advisories for `react-router` / `react-router-dom` 6.30.6. This dependency is used only by the explicit fixture demo and is blocked from the production import graph. The [open-redirect advisory](https://github.com/advisories/GHSA-wrjc-x8rr-h8h6) concerns attacker-controlled navigation; the [SSR hydration advisory](https://github.com/advisories/GHSA-337j-9hxr-rhxg) does not apply to this declarative client-only demo. No production Package A fix or speculative major upgrade was made. |
| `git diff --check` | Passed. |

The 60 skips are pre-existing gates for external/provider or specialized container scenarios: 45 require `RUN_FORMANCE_IT`, 4 `RUN_FORMANCE_RECON_IT`, 4 `RUN_KYC_REVERIFICATION_CONTAINER_IT`, and one each requires `RUN_KYC_MIGRATION_CONTAINER_IT`, `RUN_PAYOUT_EVIDENCE_IT`, `RUN_WALLET_RECOVERY_CONTAINER_IT`, `RUN_SUMSUB_CREATE_IT`, `RUN_SUMSUB_IT`, `RUN_SUMSUB_SDK_IT`, or a real Microsoft Graph email test. They were **not** counted as passes. No Package A staff-policy test was skipped; the focused synthetic-credit and reconciliation security regression tests executed. Those skipped provider tests require separate disposable infrastructure or external sandbox authorization and are outside this local Pack A run.

## Real browser: built production preview and disposable Keycloak

Playwright Chromium `1.56.1` automated a real Authorization Code flow. Each account used the browser login form; direct/password grants were not enabled. The authorization request included `response_type=code` and `code_challenge_method=S256`; the callback was observed, and `/users` was restored as a safe deep link without callback parameters. The test read only sanitized role names and HTTP statuses from token-bearing requests. No trace, screenshot, full token, code, or verifier was stored.

| Disposable effective account roles | `/api/backoffice/me` | Browser result |
| --- | ---: | --- |
| `COMPLIANCE_OFFICER` only | 200 | Signed-in shell; reload restored SSO. |
| `ADMIN` only | 200 | Signed-in shell; reload restored SSO. |
| `CUSTOMER` only | 403 | Access denied with sign-out; no login loop. |
| `COMPLIANCE_OFFICER` + default `CUSTOMER` | 403 | Access denied with sign-out. |
| `ADMIN` + default `CUSTOMER` | 403 | Access denied with sign-out. |
| `ADMIN` + `COMPLIANCE_OFFICER` | 403 | Access denied with sign-out. |
| `OPERATIONS` only | 403 | Access denied with sign-out. The restricted staff client did not emit OPERATIONS in the access token. |

Additional real-browser outcomes:

- A second tab restored existing SSO. Logout used POST to Keycloak; the second tab was signed out after reload. The exact configured post-logout URI returned to the application. An account switch from compliance to ADMIN changed the backend subject and did not restore the previous shell session.
- A forced `/me` 401 caused one successful refresh-token request and one bounded retry. A rejected refresh caused the expired screen after one refresh attempt and one `/me` attempt. No refresh or redirect loop was observed.
- A forced `/me` 503 displayed the service-unavailable screen without a demo fallback. `/users` displayed the unavailable-route shell for authorized accounts, never fixtures. The production build contained no demo entry or fixture import.
- With browser `localStorage` unavailable, upstream `keycloak-js` completed the callback using its cookie fallback. Application access/refresh tokens were not found in local/session storage in the observed flows. Upstream temporary callback state is separate from bearer tokens and remains JavaScript-accessible; this result is not an XSS defense claim.
- Unit tests, rather than this browser run, exercised logout while a request or adapter initialization was pending and confirmed that a late result did not restore protected state. A same-tab UI logout during initial `/me` loading is unavailable because the shell has not mounted yet. Immediate cross-tab invalidation without reload was not implemented or claimed.

## Backend security coverage and limits

Signed JWTs were passed through a real Nimbus decoder in the web tests. Missing, malformed, expired, wrongly signed and wrong-issuer tokens returned 401. Valid wrong-audience and wrong-`azp` tokens returned 403. The role matrix above, customer role in another supported client claim, minimal server-derived `/me` (`staffSubject`, empty `capabilities`), disabled/missing staff configuration, existing mobile `/api/auth/me`, synthetic-credit and reconciliation routes, and ADMIN denial of the compliance-only synthetic-credit action were checked. The denied synthetic-credit request had no service interaction.

The staff feature defaults disabled. Its issuer/audience/client policy applies only to `GET /api/backoffice/me`; customer, synthetic-credit and reconciliation endpoint policies were not changed. No customer data feature, staff-management endpoint, KYC decision action, or Package B query exists in this worktree.

## Acceptance and rollout boundaries

This isolated run supports **Package A code review**, not production readiness. Deployment still requires operator-supplied exact HTTPS hosts and Keycloak client settings, a production JWT decoder issuer, CORS or same-origin proxy review, and static-host headers. Those headers were not applied or verified in this local Vite preview. Staff MFA and the proposed 15-minute idle/eight-hour maximum need backend-owner/operator decisions and isolated tests, including the effect of pre-existing realm SSO and refresh on inactivity. Do not change realm-wide flows without reviewing mobile login impact.

Dedicated staff provisioning must exclude inherited `CUSTOMER`, preserve normal customer defaults, and inventory existing mixed identities. Client role-claim visibility alone cannot establish that an identity was never a customer. Later ADMIN visibility and staff lifecycle management require separate authorization and audit design; ADMIN shell entry grants no compliance decision authority. Package B's cross-module SQL exception remains unapproved.
