# Package C isolated verification — 2026-11-09

## Isolation and commands

The original `C:\Users\LENOVO\Documents\Personal\Projects\NEOS` folder was clean on Package B before the fetch. `git fetch origin` advanced `origin/integration` to `1857119`; `git switch --no-track -c feat/backoffice-package-c origin/integration` created this branch in the same folder. Nothing was staged, committed, pushed or opened as a PR.

Disposable PostgreSQL 18, PostgreSQL 17 for Formance, Formance Ledger v2.4.14, Caddy and Keycloak 26.7.2 ran in new `--rm` containers on a new Docker network. They had no application volumes. The Keycloak container imported a sanitized realm definition with zero users, then six invented role-matrix accounts were created only there. Random passwords were held in temporary local files and passed to the isolated services. The local browser imported an existing temporary Playwright installation; no dependency was added. The app database and Formance ledger held only synthetic customers and postings.

Representative sanitized commands (all secrets are placeholders, never literal values):

```powershell
git status --porcelain=v1 --untracked-files=normal
git fetch origin
git switch --no-track -c feat/backoffice-package-c origin/integration

docker network create neos-pack-c-net
docker run -d --rm --name neos-pack-c-pg --network neos-pack-c-net -p 127.0.0.1:55433:5432 --env-file <temporary-app-db-env> postgres:18
docker run -d --rm --name neos-pack-c-formance-pg --network neos-pack-c-net --env-file <temporary-ledger-db-env> postgres:17-alpine
docker run -d --rm --name neos-pack-c-formance --network neos-pack-c-net --env-file <temporary-ledger-env> ghcr.io/formancehq/ledger:v2.4.14 serve
docker run -d --rm --name neos-pack-c-gateway --network neos-pack-c-net -p 127.0.0.1:18080:80 -v <temporary-caddyfile>:/etc/caddy/Caddyfile:ro caddy:2.8.4-alpine
docker run -d --rm --name neos-pack-c-keycloak --network neos-pack-c-net -p 127.0.0.1:8081:8080 --env-file <temporary-keycloak-env> -v <sanitized-realm-folder>:/opt/keycloak/data/import:ro quay.io/keycloak/keycloak:26.7.2 start-dev --import-realm

$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55433/neos'
$env:DB_USERNAME = 'neos'
$env:DB_PASSWORD = '<temporary-random-password>'
$env:KEYCLOAK_ISSUER_URI = 'http://localhost:8081/realms/neos'
$env:FORMANCE_BASE_URL = 'http://127.0.0.1:18080'
$env:NEOS_SYNTHETIC_CREDIT_ENABLED = 'false'
$env:NEOS_CUSTOMER_TRANSFER_API_ENABLED = 'false'
$env:MICROSOFT_GRAPH_ENABLED = 'false'
$env:NOTIFICATION_DISPATCHER_ENABLED = 'false'
$env:SUMSUB_WEBHOOK_ENABLED = 'false'
.\mvnw.cmd -q clean test

# Only against a freshly created disposable ledger:
$env:PACK_C_DISPOSABLE_LEDGER_URL = 'http://127.0.0.1:18080'
node backoffice/scripts/setup-package-c-ledger.mjs
$env:RUN_FORMANCE_IT = 'true'
.\mvnw.cmd -q '-Dtest=CustomerFinancialFormanceLiveIntegrationTest' test

Set-Location backoffice
npm test
npm run build
npm run build:demo
npm audit --omit=dev --json
# With the isolated backend on 8082, Vite on 5173, and a disposable password supplied by environment:
node scripts/verify-package-c-browser.mjs
```

The backend browser run explicitly set `SERVER_PORT=8082`, staff issuer `http://localhost:8081/realms/neos`, audience `neos-api` and client `neos-backoffice`. Vite used the production entry and proxied `/api` to that backend. The browser script retained bearer tokens only in memory, printed role labels and status codes, and saved no credentials, tokens or real customer data.

## Results

| Check | Observed result |
| --- | --- |
| Full backend suite, final clean isolated database | 129 suites, **645 total**, **584 executed**, **61 skipped**, 0 failures, 0 errors. The mobile API regression tests and `ModularityTest` passed. |
| Live Package C Formance test, separately enabled | **1 passed** against the disposable ledger and database. Six wallets were provisioned; five TND incoming payments, a customer transfer and a settled payout were posted as synthetic ledger data. The exact TND scale, available/pending balances, CREDIT/DEBIT direction, completed status, settlement transaction ID and timestamps were checked. |
| Fixed-data paging | Seven customer-visible items traversed at `pageSize=2` with no duplicate or missing transaction ID. The other customer saw its own transfer credit. Customer, currency and page-size cursor reuse returned 400. |
| Empty/missing/provider states | Before provisioning, an existing customer had empty wallets and history; a missing customer returned 404. When the disposable Formance container was stopped, both staff endpoints returned sanitized 503; the browser displayed the error and Retry. |
| Authorization | Direct GET on both new endpoints returned 200 for dedicated compliance and 403 for ADMIN, CUSTOMER, mixed customer/staff, combined ADMIN/compliance and OPERATIONS. ADMIN kept shell access and no financial UI requests were sent from its direct route. |
| Provider-call efficiency | The ledger wallet service uses bounded account and volume query streams. The history path uses one account page plus a bounded `$or` batch for payout holds, rather than one request per payout. A unit test checked two payouts in one batch; the live settled-payout test exercised the Formance batch query. |
| Database query plan | On the small 34-row synthetic table, PostgreSQL chose a sequential scan (0.050 ms). In a rolled-back 10,000-row synthetic plan, the customer lookup used `users_pkey` (one index search, 0.018 ms). No index or migration was added. |
| Frontend | **31/31 tests** across five files passed, including response-shape rejection, exact large/negative/TND formatting, stale currency responses, customer switching and authorization clearing. Production TypeScript/Vite build and production demo isolation check passed; demo build passed. Production dependency audit: **0 findings**. |
| Diff | `git diff --check` passed; changes remain unstaged and uncommitted. |

The 61 full-suite skips are **not passes**. Forty-six are gated on `RUN_FORMANCE_IT` (including the new Package C live test, which was run separately and passed), four on `RUN_FORMANCE_RECON_IT`, four on `RUN_KYC_REVERIFICATION_CONTAINER_IT`, and one each on `RUN_WALLET_RECOVERY_CONTAINER_IT`, `RUN_PAYOUT_EVIDENCE_IT`, `RUN_KYC_MIGRATION_CONTAINER_IT`, `RUN_SUMSUB_IT`, `RUN_SUMSUB_CREATE_IT`, `RUN_SUMSUB_SDK_IT`, or an external Microsoft Graph email check. These unrelated provider/container scenarios were not enabled in the full suite.

An initial full backend run found a Package B test that asserted five ACTIVE rows even though it seeded four; unrelated tests can persist other ACTIVE rows. The test now verifies its own four rows and preserves the two-query assertion. The final full suite passed on a fresh disposable database. An initial browser login exposed CRLF handling in a temporary shell-sourced password file; the disposable accounts were corrected without changing the shared realm or repository credentials. An initial browser provider 503 identified the new ledger had not yet been created; the repository schema was installed on the disposable ledger before the successful live run.

After the full suite, the live integration scenario was strengthened with a real transfer and settled payout and passed as a focused run. The existing wallet unit test was strengthened to assert a large and a negative minor-unit value and passed as a focused run. No production source changed after the full-suite pass.

## Browser evidence

Headless Chromium completed Authorization Code with PKCE S256 through the disposable Keycloak form and opened the direct customer-detail route. With Formance live, dedicated compliance saw the synthetic TND wallet and transaction history; its direct wallet/history requests both returned 200. The other five role cases had no financial page requests from the direct frontend route, and direct requests to both endpoints returned 403. With Formance stopped, compliance saw distinct ledger-unavailable sections and a working Retry control; both direct endpoint requests returned 503. The same five unauthorized cases still returned 403 during the outage. These observations are local to the isolated services, not a production latency or availability claim.

Formance ordering is provider-defined, and these requests do not pin a provider snapshot. The complete paging result applies to the fixed synthetic dataset only. Cursor signatures use an ephemeral application key, so a restart or routing to a different backend instance invalidates outstanding cursors; this local verification used one backend instance.

## Cleanup state

The five Package C `--rm` containers and `neos-pack-c-net` were stopped and removed. Automatic approval review rejected both recursive deletion and explicit file-by-file deletion of `C:\Users\LENOVO\AppData\Local\Temp\neos-pack-c-isolated-verification` with the stated reason “blocked by policy.” That directory remains and includes temporary synthetic service passwords; no credential value is recorded here or in the repository. A manual cleanup of that exact directory is required. No pre-existing container, shared realm, application volume or prior verification directory was removed.

Manual cleanup command (not executed):

```powershell
Remove-Item -LiteralPath 'C:\Users\LENOVO\AppData\Local\Temp\neos-pack-c-isolated-verification' -Recurse -Force
```
