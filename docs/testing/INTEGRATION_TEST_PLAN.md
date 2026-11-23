# Mobile ↔ Backend Integration Test Plan (after PR #55)

- **Backend:** `test/integration-pr55` at `a2f97a8` (`origin/integration` through PR #55, plus `chore/npm-audit`).
- **Evidence and line references:** `docs/audits/BACKEND_DELTA_AUDIT_3.md` (called "Audit 3" below). Wire contract: `docs/api/MOBILE_API_CONTRACT.md` ("§n").
- **Status values:**
  - **TESTABLE NOW** means it runs with the current code and the current local environment.
  - **BLOCKED** means something is missing first; the reason is given each time: a missing endpoint, a missing mobile feature, a missing secret or tunnel, or a missing realm role or flag.
- **Never auto-retry a transfer whose outcome is unknown.** Every retry in this plan reuses the **same** idempotency key and is triggered by hand.

---

## 0. Environment and test data

### 0.1 Services (as used in `mobile/VALIDATION.md`)

| Component | Address | Notes |
|---|---|---|
| Backend | `:8080` (`SERVER_PORT` in `.env`), dev profile, started by `scripts/run-backend-local.ps1` | Must be the PR #55 build. Check that `POST /api/kyc/start` for a new user returns `PENDING` (TC-KYC-02). |
| Keycloak 26.7.2 | `:8081`, realm `neos` | R1 (registration, `CUSTOMER` default role) is applied **on the running realm only**. A re-import loses it (Audit 3 §3 #7b). |
| Formance gateway v2.4.14 | `:8083` (`FORMANCE_BASE_URL`) | container `neos-formance-gateway`, which can be stopped to simulate an outage |
| Postgres (app) | `:5434` | |
| Metro / emulator | `:8082`, Pixel 6 API 36 | `EXPO_PUBLIC_MOCK_FEATURES=kycCapture,notifications` |

### 0.2 Backend flags for this plan

| Flag | Value for this plan | Why |
|---|---|---|
| `NEOS_CUSTOMER_TRANSFER_API_ENABLED` | `true` (dev default) | Send and lookup |
| `NEOS_SYNTHETIC_CREDIT_ENABLED` | `true` | Funding test wallets ("Receive") |
| `MICROSOFT_GRAPH_ENABLED`, `NOTIFICATION_DISPATCHER_ENABLED` | **`false`** | Prevents real emails and a backlog flush (Audit 3 §5.5) |
| `SUMSUB_WEBHOOK_ENABLED`, `SUMSUB_WEBHOOK_SECRET` | not set today | KYC decisions are BLOCKED until they are set |
| `NEOS_TRANSFERS_RECONCILIATION_ADMIN_API_ENABLED`, `…_NOTIFICATION_REPAIR_ENABLED` | not set today | Admin tests are BLOCKED |
| `KYC_MODE` | ignored | Remove it from `.env` to avoid confusion |

### 0.3 Test users

| User | State | Use |
|---|---|---|
| `neos-sender@neos.local` | APPROVED + ACTIVE (MOCK-approved before PR #55), EUR funded | sender |
| `neos-receiver@neos.local` | APPROVED + ACTIVE | recipient |
| `neos-bio-sender`, `neos-bio-receiver` | APPROVED + ACTIVE | spare pair |
| `neos-compliance`, `neos-bio-compliance` | `COMPLIANCE_OFFICER` | synthetic credits |
| `neos-recon-admin` (to create) | realm role `RECONCILIATION_ADMIN`, which **must be created first** | admin tests |
| new self-registered users (`neos-pr55-*@neos.local`) | fresh | onboarding and KYC |

Users approved under MOCK keep `kyc_status.provider='MOCK'`. No Sumsub webhook can change them (Audit 3 §1.2). Use **new** users for every webhook test.

### 0.4 How to obtain a token for API steps

Use the app's own session, or a password grant **only if** a test client allows it (none is configured: `neos-mobile` is PKCE-only). Otherwise, run the API steps through the app and read the result in the backend log or the DB. **Never paste tokens into tickets.**

---

## 1. Auth and session

| ID | Preconditions | Steps | Expected | Status |
|---|---|---|---|---|
| TC-AUTH-01 Login | `neos-sender` exists | App → "Sign in" → Keycloak login (`prompt=login`) → credentials | Custom Tab "Sign in to NEOS". The app gets the tokens. `GET /api/auth/me` 200 `{subject, username, email}`. Then Home. | TESTABLE NOW |
| TC-AUTH-02 Registration | R1 applied to the running realm | "Let's begin" → Register form → email/password/name | Account created, `/api/auth/me` 200 (the `CUSTOMER` default role), then the onboarding intro | TESTABLE NOW (only while R1 is applied by hand; BLOCKED after a realm re-import) |
| TC-AUTH-03 Token refresh | Signed in | Stay idle more than 5 min (access token 300 s), then pull to refresh Home | One silent refresh. The request succeeds. No "session expired". | TESTABLE NOW |
| TC-AUTH-04 Logout | Signed in | Profile → Sign out → Sign in again | Tokens and unlock preference cleared, refresh token revoked. Login form shown again (no silent SSO). | TESTABLE NOW (end-session logout = R2, not implemented) |
| TC-AUTH-05 Silent login / cancel | Signed out | "Sign in" → close the Custom Tab (Back or X) | Back to welcome, **no error** | TESTABLE NOW |
| TC-AUTH-06 Expired / revoked token | Signed in | Revoke the session in the Keycloak admin console → wait for access-token expiry → any action | One refresh attempt fails → "Your session has expired" → welcome | TESTABLE NOW |
| TC-AUTH-07 Stale token at cold start | Token from an old session stored | Kill the app, revoke the session, cold start | Plain welcome, no error | TESTABLE NOW |
| TC-AUTH-08 `/me` without an email claim | A user with no email (admin-created, no email) | Sign in | Backend **500** (`AuthController.java:22-28`, known). The app shows a sign-in error, not a crash. | TESTABLE NOW (expected to show the known bug) |
| TC-AUTH-09 Missing `CUSTOMER` role | A user without `CUSTOMER` | Sign in | `/api/auth/me` 403 → sign-in error | TESTABLE NOW |
| TC-AUTH-10 App unlock | Fingerprint enrolled | Cold start with unlock on; background more than 60 s | Unlock prompt before any content (see `mobile/VALIDATION.md` 3 Nov) | TESTABLE NOW (regression) |

## 2. Onboarding

| ID | Preconditions | Steps | Expected | Status |
|---|---|---|---|---|
| TC-ONB-01 Profile creation | New user signed in | `GET /api/profile` 404 → details → review → terms → submit | `POST /api/onboarding/personal` 200 `{userId, deviceId, kycStatus:"NOT_STARTED", termsVersion:"1.0"}`. The app then re-reads `GET /api/profile` → `accountStatus: PENDING_KYC`, `kycStatus: NOT_STARTED`. | TESTABLE NOW |
| TC-ONB-02 Device registration | TC-ONB-01 | DB: `devices` row for `installationId` + `ANDROID` | One device row per installation | TESTABLE NOW (DB check) |
| TC-ONB-03 Terms acceptance | TC-ONB-01 | DB: `terms_acceptances`. `GET /api/profile` `onboardedAt` | Version `1.0` stored. `onboardedAt` = first acceptance. | TESTABLE NOW |
| TC-ONB-04 Wrong terms version | Build with `EXPO_PUBLIC_TERMS_VERSION=0.9` | Submit onboarding | 400 → "couldn't save your information" | TESTABLE NOW |
| TC-ONB-05 Retry / idempotency | TC-ONB-01 done | Submit onboarding again (re-open the app mid-flow, or double tap) | 200 again, same `userId`, no duplicate user, KYC row unchanged | TESTABLE NOW |
| TC-ONB-06 Network failure | Backend stopped (or airplane mode) | Submit | "couldn't connect" + retry. Nothing is half-created on retry. | TESTABLE NOW (do **not** stop the shared backend; use airplane mode) |

## 3. Sumsub KYC

| ID | Preconditions | Steps | Expected | Status |
|---|---|---|---|---|
| TC-KYC-01 Startup without a Sumsub secret | Separate local backend instance only | Start with `SUMSUB_SECRET_KEY` blank | Startup fails: "Sumsub secret key must not be blank" | TESTABLE NOW (second instance on another port; never on the shared one) |
| TC-KYC-02 Applicant creation, pending status | New onboarded user | KYC intro → simulated capture (mock) → submit → `POST /api/kyc/start` | 200 `{status:"PENDING", provider:"SUMSUB"}`. Then `GET /api/profile` `kycStatus: PENDING`, `accountStatus: PENDING_KYC`. App: "Verification in progress". **No auto-approval.** A Sumsub sandbox applicant exists with `externalUserId = userId`. | TESTABLE NOW (needs valid Sumsub sandbox credentials in `.env`; run `SumsubApplicantSandboxIntegrationTest` first, Audit 3 §1.2) |
| TC-KYC-03 Start idempotency | TC-KYC-02 | Call `POST /api/kyc/start` again (from the app: re-enter KYC) | Same status, no second applicant, no account change | TESTABLE NOW |
| TC-KYC-04 Start before onboarding | Signed in, not onboarded | `POST /api/kyc/start` | 409 → "couldn't complete this check" | TESTABLE NOW (API) |
| TC-KYC-05 Sumsub unreachable | Separate instance with `SUMSUB_BASE_URL=https://invalid.example` | Start KYC | 500 → `kycError` with retry. Status stays `NOT_STARTED`. | TESTABLE NOW (separate instance) |
| TC-KYC-06 SDK initialization | – | App launches the Sumsub SDK with an access token | SDK opens for the applicant | **BLOCKED**: no SDK-token endpoint (backend) and no SDK in the app (mobile) |
| TC-KYC-07 Approval via webhook (GREEN) | TC-KYC-02 user. `SUMSUB_WEBHOOK_ENABLED=true` + `SUMSUB_WEBHOOK_SECRET`. A tunnel to `:8080` registered in the Sumsub sandbox. Applicant reviewed GREEN in Sumsub. | Wait for the webhook → app pull to refresh | Webhook 200. `kycStatus: APPROVED`, `accountStatus: ACTIVE`. `KycApprovedEvent` → 6 wallets provisioned (`GET /api/wallets` `READY`). App → "You're verified" → Home. | **BLOCKED**: missing `SUMSUB_WEBHOOK_*` in `.env`, no tunnel, no way to submit documents. Alternative: a locally signed webhook (Audit 3 §5.4 option 2), still BLOCKED until the webhook secret is set. |
| TC-KYC-08 Final rejection (RED/FINAL) | As TC-KYC-07 | Decision RED + `FINAL` | `kycStatus: REJECTED`. The account stays `PENDING_KYC` and the rejection is locked. App: rejected, **no retry button**. | **BLOCKED** (same as TC-KYC-07) |
| TC-KYC-09 Resubmission (RED/RETRY) | As TC-KYC-07 | Decision RED + `RETRY` | `kycStatus: RESUBMISSION_REQUIRED` → app "more info". Resubmitting is impossible (no SDK token; `kyc/start` short-circuits). | **BLOCKED** (webhook). Resubmission itself is BLOCKED by the missing endpoint. |
| TC-KYC-10 Wallets only after approval | TC-KYC-02 user (PENDING) | `GET /api/wallets` | `walletsStatus: NOT_ELIGIBLE`, 6 × `NOT_PROVISIONED`, `null` amounts. App banner. Lookup and transfer as this user → 403. | TESTABLE NOW |
| TC-KYC-11 Approved → RETRY → GREEN keeps the account suspended | As TC-KYC-07 | GREEN, then RED/RETRY, then GREEN | After RETRY: `SUSPENDED` → app restricted. After the second GREEN: `APPROVED` + **`SUSPENDED`** (no reactivation, no new wallet event). App stays restricted. | **BLOCKED** (webhook) |
| TC-KYC-12 No self-reactivation | A user who is `APPROVED` + `SUSPENDED` (set by TC-KYC-11, or by an ops DB update on a throwaway user) | `POST /api/kyc/start` | 200 `status: APPROVED`. The account **stays `SUSPENDED`**. | TESTABLE NOW, if a suspended test user is set up in the DB (a local-only `users.account_status` update is acceptable for this negative test) |
| TC-KYC-13 Webhook security | Webhook enabled | POST without a signature, with a bad signature, with `testMode:true`, with the wrong `levelName`, with `sandboxMode:false` | 401, 401, 400, 400, 400. No state change. | **BLOCKED** until the webhook is enabled (local only, no tunnel needed) |
| TC-KYC-14 Duplicate / stale webhook | Webhook enabled, a GREEN already applied | Replay the same payload; send one with an older `createdAtMs` | 200 both (`DUPLICATE` / `STALE`), no state change | **BLOCKED** (as TC-KYC-13) |

## 4. Wallets

| ID | Preconditions | Steps | Expected | Status |
|---|---|---|---|---|
| TC-WAL-01 Balances across currencies | `neos-sender` | Home | 6 wallets in API order. Amounts are strings, formatted with `scale`: TND has 3 decimals, the others 2. Sandbox badge shown. | TESTABLE NOW |
| TC-WAL-02 `pendingMinor` | – | – | Holds are shown only when a payout is reserved. There is no HTTP way to create one. | **BLOCKED** (no payout/hold endpoint) |
| TC-WAL-03 `NOT_ELIGIBLE` | PENDING user | Home | Banner. "Setting up…" is never shown as 0. | TESTABLE NOW |
| TC-WAL-04 `PROVISIONING` | Approval while Formance is down | Stop the Formance gateway → approve (webhook) → `GET /api/wallets` after the restart | `PROVISIONING` (some wallets missing). **No automatic retry**: it stays `PROVISIONING` until ops resubmits the event publication. | **BLOCKED** (needs webhook approval; no MOCK). It is also expected to show the known "no automatic retry" gap. |
| TC-WAL-05 Formance outage while reading | `neos-sender` | `docker stop neos-formance-gateway` → pull to refresh → restart → Try again | 503 → "Balances temporarily unavailable" with the last good balances, then recovery | TESTABLE NOW (stopping the Formance container is acceptable; the backend itself is not touched) |
| TC-WAL-06 Not onboarded | Signed-in user with no profile | `GET /api/wallets` | 404 | TESTABLE NOW (API) |

## 5. Receive

| ID | Preconditions | Steps | Expected | Status |
|---|---|---|---|---|
| TC-RCV-01 Deposit information | `neos-receiver` | Receive screen | Own email + NEOS `userId`, Copy, Share. **No IBAN or top-up instructions.** | TESTABLE NOW (internal receive only). Bank deposit: **BLOCKED**, no endpoint (Audit 3 §2.3). |
| TC-RCV-02 Incoming internal transfer | Sender funded | Sender sends €10 → receiver Home/Activity | Receiver EUR +1000 minor units. Activity "Transfer received" `TRANSFER`/`CREDIT`, `COMPLETED` | TESTABLE NOW |
| TC-RCV-03 Incoming synthetic credit | `neos-compliance` token | `POST /api/backoffice/synthetic-credits {userId, currency:"MUR", amountMinor:100000, operationId}` | 200. Receiver MUR +1,000.00 after the focus refetch. Activity `INCOMING_PAYMENT` `CREDIT`. | TESTABLE NOW |
| TC-RCV-04 Synthetic credit idempotency | TC-RCV-03 | Same `operationId` again | No second credit (check the balance) | TESTABLE NOW |

## 6. Hold

| ID | Preconditions | Steps | Expected | Status |
|---|---|---|---|---|
| TC-HLD-01 Fund reservation | – | Reserve funds from the app | – | **BLOCKED**: no hold/reservation endpoint. Reservations exist only inside the payout services, which have no HTTP entry. |
| TC-HLD-02 Available balance update | – | – | `availableMinor` decreases, `pendingMinor` increases | **BLOCKED** (same) |

## 7. Convert

| ID | Preconditions | Steps | Expected | Status |
|---|---|---|---|---|
| TC-FX-01 Rate display | – | – | – | **BLOCKED**: no FX endpoint (V3 #37). The app shows a placeholder. |
| TC-FX-02 Execution | – | – | – | **BLOCKED** |
| TC-FX-03 Resulting balances | – | – | – | **BLOCKED** |
| TC-FX-04 Placeholder | – | Tap Convert | "Not available yet". No service call. | TESTABLE NOW |

## 8. Send

| ID | Preconditions | Steps | Expected | Status |
|---|---|---|---|---|
| TC-SND-01 Recipient lookup | `neos-sender` | Enter `NEOS-Receiver@neos.local` (mixed case, spaces) | `POST /api/recipients/lookup` 200 `{recipientUserId, maskedEmail:"n***@neos.local"}` | TESTABLE NOW |
| TC-SND-02 Lookup not available (uniform) | – | Unknown email / own email / PENDING user email / suspended user email | 404 for all → "No eligible NEOS account found for this email" | TESTABLE NOW |
| TC-SND-03 Lookup rate limit | – | 11 lookups within 1 minute | 11th → 429 → "Too many attempts…" | TESTABLE NOW (the counter is in memory and per instance) |
| TC-SND-04 Amount parsing | – | EUR "1,000" / TND "1,000" / EUR "10" | Error "up to 2 decimals" / "= TND 1.000" / "= €10.00" | TESTABLE NOW |
| TC-SND-05 Biometric confirmation | Fingerprint enrolled | Review → Confirm → finger 2 (rejected) → Back; then finger 1 | Cancel → "Nothing was sent" (balance unchanged via the API). Finger 1 → 200 `{transactionId, reference}` | TESTABLE NOW |
| TC-SND-06 Internal transfer success | Sender ≥ €10 | Send €10 | Sender −1000, receiver +1000. Reference `internal_transfer:ct_<sender>_<key>` (no dashes). Activity shows it. | TESTABLE NOW |
| TC-SND-07 Idempotent replay | TC-SND-06 key | Replay the **same** request body with the same key (API) | 200 with the **same** `transactionId`. No second posting. | TESTABLE NOW (API) |
| TC-SND-08 Key reused, different params | TC-SND-06 key | Same key, amount +1 (API) | 409 (no code; the app treats it as uncertain) | TESTABLE NOW (API) |
| TC-SND-09 Insufficient funds | TND balance 0 | Bypass the UI guard: API `POST /api/transfers/internal` TND 1000 | **Today:** 503 "Transfer outcome not confirmed", claim `UNKNOWN`, same-key retry → 409 forever. **Desired:** 4xx + `INSUFFICIENT_FUNDS`. In the app: the UI guard blocks it ("Insufficient balance"). | TESTABLE NOW (expected to **fail** against the desired behaviour; report it with the template) |
| TC-SND-10 Self-transfer | – | API with `destinationUserId` = own `userId` | 400 | TESTABLE NOW (API) |
| TC-SND-11 Ineligible recipient | Recipient PENDING (TC-KYC-02 user) | API transfer to their `userId` | 403 → "Transfer not allowed" | TESTABLE NOW |
| TC-SND-12 Flag off | Separate instance with `NEOS_CUSTOMER_TRANSFER_API_ENABLED=false` | Lookup + transfer | 404 → "Transfers aren't available right now" | TESTABLE NOW (separate instance) |
| TC-SND-13 No fee | – | Review screen; history after the send | "No fee". No `FEE` item. | TESTABLE NOW |

## 9. Activity / history and notifications

| ID | Preconditions | Steps | Expected | Status |
|---|---|---|---|---|
| TC-ACT-01 List per currency | Sender with items | Activity → EUR / GBP | Items in server order (newest first, observed, not guaranteed). "No transactions in GBP yet". | TESTABLE NOW |
| TC-ACT-02 Detail | – | Open an item | All fields; Copy for the reference and `transactionId` | TESTABLE NOW |
| TC-ACT-03 Paging | More than 20 items in one currency | Load more | Follows `nextCursor`, tolerates empty pages | TESTABLE NOW (needs about 21 synthetic credits) |
| TC-ACT-04 `REVERSED` | – | A reverted Formance tx | Badge + strikethrough | **BLOCKED**: no API to revert (only the Formance admin; not recommended) |
| TC-ACT-05 `pageSize` out of range | – | `GET /api/transactions?currency=EUR&pageSize=0` (API) | Known **500** (should be 400) | TESTABLE NOW (known bug) |
| TC-ACT-06 History while Formance is down | – | Stop the gateway → Activity | 500 → "Your activity is temporarily unavailable" + Try again → recovers | TESTABLE NOW |
| TC-NTF-01 Email outbox rows | Graph and dispatcher **off** | After TC-SND-06, query `notification_outbox` for both users | One `PENDING` row each for sender (debit) and recipient (credit), unique `notification_key` | TESTABLE NOW (DB) |
| TC-NTF-02 Email delivery | A controlled mailbox user, backlog cleaned, Graph + dispatcher on | Transfer | Email received, row `PROVIDER_ACCEPTED` | **BLOCKED** by policy (do not enable Graph on the shared local DB; Audit 3 §5.5) |
| TC-NTF-03 In-app notifications feed | – | Bell | Placeholder | **BLOCKED**: no endpoint (TESTABLE NOW for the placeholder only) |

## 10. Negative scenarios

| ID | Preconditions | Steps | Expected | Status |
|---|---|---|---|---|
| TC-NEG-01 Suspended account | A user who is `APPROVED` + `SUSPENDED` (local DB) | Sign in → Home → Send | `GET /api/profile` `accountStatus: SUSPENDED` → restricted screen. Lookup / transfer → 403. Wallets still readable. | TESTABLE NOW (local DB setup) |
| TC-NEG-02 Closed account | Same, with `CLOSED` | Same | Restricted. `kyc/start` does not reactivate (TC-KYC-12). | TESTABLE NOW (local DB setup) |
| TC-NEG-03 Expired token mid-transfer | Signed in, session revoked | Confirm a transfer | 401 → refresh fails → "session expired". The transfer is classified as **rejected** (401 happens before the controller), and the pending record is kept. | TESTABLE NOW |
| TC-NEG-04 Duplicate submission | Review reached | Double-tap Confirm / re-confirm after a restart | One posting at most (same key). The second call returns the 200 replay or 409. | TESTABLE NOW |
| TC-NEG-05 API failure (5xx) | – | Stop the Formance gateway **after** Review → Confirm | 503 "Transfer outcome not confirmed" → app "Transfer is being processed". **No automatic retry.** Check status → not found. Try again (same key) → 409. | TESTABLE NOW |
| TC-NEG-06 Network timeout | – | Airplane mode right after Confirm, or a proxy delay > 15 s (`mobile/src/api/client.ts:58`) | Uncertain → history check → "being processed". After a restart, the Home banner resumes it. Never sent again with a new key. | TESTABLE NOW |
| TC-NEG-07 Uncertain → resolved by admin confirm | UNKNOWN claim **that did post** (needs a response loss after the posting) | Admin A1 inspect → `MATCHED` → A2 confirm → app "Try again" (same key) | A2 200 `SUCCEEDED`. App same-key retry → **200 replay** → "Money sent". Notifications enqueued. | **BLOCKED**: admin flag + `RECONCILIATION_ADMIN` role missing. Forcing a posted-but-lost response on demand is not possible in this setup. |
| TC-NEG-08 Uncertain that never posted | TC-NEG-05 claim | Admin A1 inspect | `finding: NOT_FOUND_UNCONFIRMED`. A2 → 409. **The claim cannot be resolved.** App: "Stop tracking" path only. | **BLOCKED** (admin flag/role). The app part is TESTABLE NOW. |
| TC-NEG-09 Stop tracking warning | After TC-NEG-05 | Check status once → Stop tracking → new send with the same recipient/currency/amount | Warning sheet; "You may have sent this already" before Review | TESTABLE NOW |
| TC-NEG-10 Malformed 200 | – | – | Uncertain | Unit tests only (`mobile/tests/transferEngine.test.ts`) |

## 11. Admin permissions and feature flags

These run against a **separate local backend instance** (another port) with the flags set. Do not run them against the shared `:8080` instance.

| ID | Preconditions | Steps | Expected | Status |
|---|---|---|---|---|
| TC-ADM-01 Flags off → routes absent | Default flags | `GET /api/admin/transfers/reconciliation?senderId=…` | 404 (no controller) | BLOCKED by instruction for this audit; TESTABLE on a separate instance |
| TC-ADM-02 Customer token rejected | Flag on | A1 with a `CUSTOMER` token | 403 | **BLOCKED**: flag not in `.env` |
| TC-ADM-03 Unauthenticated | Flag on | A1 without a token | 401 | **BLOCKED** (flag) |
| TC-ADM-04 Admin inspect | Flag on + realm role `RECONCILIATION_ADMIN` created + user | A1 `limit=20` / `limit=0` / `limit=101` | 200 list / 400 / 400 | **BLOCKED**: role absent from the realm export, flag not set |
| TC-ADM-05 Admin confirm | TC-NEG-07 | A2 | §TC-NEG-07 | **BLOCKED** |
| TC-ADM-06 Repair needs both flags | Only `admin-api-enabled` | A3 | 404 | **BLOCKED** (flags) |
| TC-ADM-07 Repair | Both flags, a reconciled transfer | A3 `batchSize=25`; then again | Results `OUTBOX_PRESENT`. The second call is idempotent (no new rows). `batchSize=0` → 400. A bad `afterRequestKey` → 400. | **BLOCKED** (flags/role, and needs a reconciled transfer) |
| TC-ADM-08 Synthetic credit role | `NEOS_SYNTHETIC_CREDIT_ENABLED=true` | S2 with a `CUSTOMER` token / a `COMPLIANCE_OFFICER` token | 403 / 200 | TESTABLE NOW |
| TC-ADM-09 Transfer flag | See TC-SND-12 | – | – | TESTABLE NOW (separate instance) |
| TC-ADM-10 Prod startup validation | Separate instance, `SPRING_PROFILES_ACTIVE=prod`, sandbox mode `true` | Start | Fails "Production KYC must have SUMSUB_SANDBOX_MODE=false" (and the other validator messages) | TESTABLE NOW via `mvnw test -Dtest=SumsubProductionStartupIntegrationTest` (no real secrets needed) |

---

## 12. Summary: testable now vs blocked

| Area | Testable now | Blocked (reason) |
|---|---|---|
| Auth & session | all, except end-session logout | registration after a realm re-import (R1 not in the export) |
| Onboarding | all | – |
| Sumsub KYC | applicant creation, PENDING, idempotency, no self-reactivation, wallets gated on approval | SDK (missing endpoint + mobile SDK); approval/rejection/resubmission (no `SUMSUB_WEBHOOK_*`, no tunnel, no document upload); resubmission (missing endpoint) |
| Wallets | balances, `NOT_ELIGIBLE`, outage on read | `PROVISIONING` after an outage (needs webhook approval); holds (no endpoint) |
| Receive | internal receive, synthetic credits | bank top-up / deposit instructions (no endpoint) |
| Hold | – | everything (no endpoint) |
| Convert | placeholder | everything (no endpoint) |
| Send | lookup, biometric, success, replay, 409, 403, self-transfer, insufficient funds (current 503 behaviour) | definitive insufficient-funds rejection (backend not fixed) |
| Activity / notifications | list, detail, paging, outage, outbox rows | `REVERSED` (no API), email delivery (policy), in-app feed (no endpoint) |
| Negative | suspended/closed (DB setup), expired token, duplicates, 5xx, timeout, stop tracking | admin-resolved uncertain outcomes (flag + role) |
| Admin / flags | synthetic-credit role, transfer flag, prod validation test | reconciliation and repair APIs (flags not set, `RECONCILIATION_ADMIN` role absent from the realm) |

---

## 13. Issue report template

Copy one block per issue. **Redact** the `Authorization` header, all tokens, Sumsub/Graph credentials and webhook secrets. Replace emails and UUIDs with placeholders where they are not needed to reproduce.

````markdown
### [AREA] Short title (e.g. "[Send] Insufficient funds returns 503 instead of 4xx")

- **Test case:** TC-SND-09
- **Build:** backend `<commit>` (profile `dev`), mobile `<commit>` / build type, device/emulator + Android API
- **Endpoint:** `POST /api/transfers/internal`
- **HTTP status:** actual `503`, expected `422` (or `4xx` with `errorCode`)
- **Request (redacted):**
  ```http
  POST /api/transfers/internal
  Authorization: Bearer <REDACTED>
  Content-Type: application/json

  {"destinationUserId":"<recipient-uuid>","currency":"TND","amountMinor":1000,"idempotencyKey":"<uuid>"}
  ```
- **Response (redacted):**
  ```json
  {"timestamp":"…","status":503,"error":"Service Unavailable","path":"/api/transfers/internal"}
  ```
- **Expected:** definitive rejection, no `UNKNOWN` claim, `errorCode: "INSUFFICIENT_FUNDS"`. The same key can never become "in progress".
- **Actual:** `503`. The claim stays `UNKNOWN`. A same-key retry returns `409` forever. The app shows "Transfer is being processed".
- **Reproduction steps:**
  1. Sign in as `<sender>` with a TND balance of 0.
  2. Send the request above.
  3. Send the same request again (same `idempotencyKey`).
- **Evidence:** backend log line (no secrets), DB row `customer_transfer_idempotency(sender_user_id, request_key)` → `status`, app screenshot.
- **Code reference (if known):** `src/main/java/com/fintech/ledger/CustomerTransferIdempotencyService.java:268-301`
- **Severity / impact:** High (contract), user-visible uncertainty, key blocked forever
````
