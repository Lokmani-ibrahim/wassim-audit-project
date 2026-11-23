# Package B isolated verification — 2026-11-09

## Scope and isolation

- Worktree: `C:\Users\LENOVO\Documents\Personal\Projects\NEOS`, branch `feat/backoffice-package-b`, created from freshly fetched `origin/integration`. No staging, commit, push or PR was performed.
- Disposable PostgreSQL 18 ran in a new `--rm` container on loopback port 55432, with no volume. Disposable Keycloak 26.7.2 ran in a new `--rm` container on loopback port 8081 with its own ephemeral store. It imported an existing sanitized realm definition with zero users, then six invented `review.invalid` accounts were provisioned only there. Two invented directory rows and one KYC row were inserted only into the disposable PostgreSQL instance. Neither shared realms nor existing application volumes were changed.
- Random database and browser passwords were held in temporary local files and removed during cleanup. No password, bearer token, authorization code, PKCE verifier, real customer data, browser trace or screenshot is retained in this report.

## Reproduction commands (sanitized)

The following commands show the checks and relevant settings; `<temporary-random-password>` represents an ephemeral value, never a repository value. Use fresh disposable containers and loopback ports. The browser script reads its password from `PACK_B_BROWSER_PASSWORD` and an installed Playwright module from `PACK_B_PLAYWRIGHT_MODULE`; it does not log either.

```powershell
git fetch origin
git switch -c feat/backoffice-package-b origin/integration
git branch --unset-upstream

# Disposable PostgreSQL and Keycloak are started with docker run --rm,
# random passwords, loopback ports 55432 and 8081, and no application volume.
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' # Java 21
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55432/neos'
$env:DB_USERNAME = 'neos'
$env:DB_PASSWORD = '<temporary-random-password>'
$env:KEYCLOAK_ISSUER_URI = 'http://localhost:8081/realms/neos'
$env:NEOS_SYNTHETIC_CREDIT_ENABLED = 'false'
$env:NEOS_CUSTOMER_TRANSFER_API_ENABLED = 'false'
$env:SUMSUB_BASE_URL = 'http://127.0.0.1:18081'
$env:FORMANCE_BASE_URL = 'http://127.0.0.1:18080'
$env:MICROSOFT_GRAPH_ENABLED = 'false'
$env:NOTIFICATION_DISPATCHER_ENABLED = 'false'
$env:SUMSUB_WEBHOOK_ENABLED = 'false'
.\mvnw.cmd clean test

Set-Location backoffice
npm ci
npm run test
npm run build
npm run build:demo
npm audit --omit=dev --json

# With the isolated backend on 8082, Vite on 5173, and invented realm users:
node scripts/verify-package-b-browser.mjs
```

The browser run used `VITE_KEYCLOAK_URL=http://localhost:8081`, realm `neos`, client `neos-backoffice`, same-origin `/api`, exact `http://localhost:5173/auth/callback` and post-logout root, and a disposable environment label. The Vite development proxy forwarded `/api` to the isolated backend at 8082. The backend was enabled only against that isolated issuer, audience `neos-api` and client `neos-backoffice`.

## Results

| Check | Observed result |
| --- | --- |
| Focused database and security tests | `CustomerDirectoryIntegrationTest`, `BackofficeMeSecurityTest` and `ModularityTest` passed after the module boundary correction. Direct list/detail calls covered dedicated compliance, ADMIN, CUSTOMER, mixed CUSTOMER/staff, combined ADMIN/compliance and OPERATIONS. |
| Directory contract | Exact case-insensitive email and exact UUID search, every account and KYC filter including `MISSING`, page sizes 1–100, invalid/unknown/repeated parameters, changed-filter cursor rejection, missing customer 404 and missing KYC `null` passed. A fixed dataset was paged at size 3 without duplicate or missing IDs. A simulated `DataAccessResourceFailureException` returned sanitized 503 without the internal message. |
| Query behavior and module boundary | Hibernate statistics observed two prepared statements for a page: one identity projection and one bulk KYC status query. `ModularityTest` passed. No cross-module table join was added. |
| Query plans | In rolled-back synthetic 10,000-row plans, a selective `SUSPENDED` keyset query used `idx_users_account_status_id` (100 rows, 0.111 ms); exact lower-case email used `idx_users_email_lower` (1 row, 0.029 ms). A less-selective `ACTIVE` query used the users primary key, which is appropriate for its ordered limit. These are local planner observations, not production latency claims. |
| Full backend suite | `mvnw clean test`: 126 suites, 640 tests, 0 failures, 0 errors, 60 skipped, 580 executed. Java 21 and disposable PostgreSQL/Keycloak were used. |
| Frontend tests | `npm run test`: 4 files, 26 tests passed. Includes strict customer/page validation, direct ADMIN route denial, stale response suppression, logout/authorization clearing, missing detail and retry states. |
| Production build | `npm run build`: TypeScript and Vite passed; automatic production import and artifact checks found no demo entry or fixture markers (3 assets checked). |
| Demo build | `npm run build:demo`: passed, separate demo entry. |
| Production dependency audit | `npm audit --omit=dev --json`: 0 findings. |
| Diff check | `git diff --check`: passed. |

The 60 skips are not passes: 45 require `RUN_FORMANCE_IT`, four `RUN_FORMANCE_RECON_IT`, four `RUN_KYC_REVERIFICATION_CONTAINER_IT`, and one each requires `RUN_WALLET_RECOVERY_CONTAINER_IT`, `RUN_PAYOUT_EVIDENCE_IT`, `RUN_KYC_MIGRATION_CONTAINER_IT`, `RUN_SUMSUB_IT`, `RUN_SUMSUB_CREATE_IT`, `RUN_SUMSUB_SDK_IT`, or an external Microsoft Graph email test. They require separate provider/container scenarios and were not enabled for this Package B run.

An initial full run exposed an identity-to-KYC Modulith cycle, a stale compiled test helper, and a local `.env` flag that enabled synthetic credits. The implementation was moved into a separate backoffice module. The final full run cleaned generated Maven output and explicitly disabled the unrelated feature; it passed as recorded above. A focused seed-data check also corrected an invented suspended row to satisfy the existing database constraint. Passing checks were not repeated without a code change or that concrete failure.

## Real browser evidence

Headless Chromium used Authorization Code with PKCE S256 through the disposable Keycloak login form. It restored the `/customers` deep link after callback. The script retained tokens only in memory for direct HTTP status checks and printed only role-case labels and statuses.

| Invented account role case | `/me` | Direct list | Direct detail | Browser result |
| --- | ---: | ---: | ---: | --- |
| Dedicated `COMPLIANCE_OFFICER` | 200 | 200 | 200 | Connected list, exact search, detail with `IN_REVIEW`, and detail with no KYC record. |
| `ADMIN` only | 200 | 403 | 403 | Shell usable; Customers navigation absent; direct route showed Access denied and made no directory request. |
| `CUSTOMER` only | 403 | 403 | 403 | Access denied. |
| `CUSTOMER` + compliance | 403 | 403 | 403 | Access denied. |
| `ADMIN` + compliance | 403 | 403 | 403 | Access denied. |
| `OPERATIONS` only | 403 | 403 | 403 | Access denied. |

This verifies the isolated local code path. Production rollout still requires the Package A operator controls for the staff client, exact HTTPS origins, static-host security headers and staff MFA/session policy. Package B adds no customer write or financial capability.
