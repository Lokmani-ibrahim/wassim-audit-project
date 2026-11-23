# Integration Test Report — PR #55 (checkpoint)

> **Status: CHECKPOINT. The run was stopped on request before it finished.** See §7 "Remaining" for what was not run and the state that still needs cleaning up.

- **Plan executed:** `docs/testing/INTEGRATION_TEST_PLAN.md` (the "TESTABLE NOW" cases). Background: `docs/audits/BACKEND_DELTA_AUDIT_3.md`.
- **Date:** 2026-11-04, roughly 03:50–05:35 local time (UTC+01:00). DB and API timestamps below are UTC.
- **Re-run:** 2026-11-04 14:17 local: `SumsubProductionStartupIntegrationTest` (TC-ADM-10) re-run on its own → **PASS** (§2.0, §7.1).
- **Redaction:**
  - Tokens are shown as `Bearer ***`. No password, token or `.env` value appears in this report.
  - Emails are shown masked or by Keycloak username.
  - Backend error bodies contain a full Java `trace` in dev; it is stripped to `<N chars>` here.

---

## 1. Summary

| Area | Passed | Failed | Not run | Blocked |
|---|---|---|---|---|
| Setup checks (Sumsub sandbox IT, prod startup IT) | 2 | 0 | 0 | 0 |
| Auth & session | 8 | 0 | 2 | 0 |
| Onboarding | 5 | 0 | 1 | 0 |
| Sumsub KYC | 5 | 0 | 2 | 7 |
| Wallets | 4 | 0 | 0 | 2 |
| Receive | 4 | 0 | 0 | 0 (bank deposit: blocked, no endpoint) |
| Hold | 0 | 0 | 0 | 2 |
| Convert | 1 (placeholder) | 0 | 0 | 3 |
| Send | 11 | **1** | 1 | 0 |
| Activity / notifications | 6 | **1** | 0 | 2 |
| Negative scenarios | 6 | 0 | 2 | 2 |
| Admin / flags | 2 | 0 | 1 | 7 |
| **Total** | **54** | **2** | **9** | **25** |

> The prod startup IT is counted twice, as before: in "Setup checks" and as TC-ADM-10 in "Admin / flags". The checkpoint's "Not run" total (13) did not match its rows, which add up to 11. The total above is the sum of the current rows (11 − 2 = 9).

**Failures** (§3):
- **ISSUE-1:** insufficient funds → 503 + `UNKNOWN`, and the key is blocked with 409.
- **ISSUE-2:** `pageSize=0` → 500.

**Additional findings** (§3):
- **ISSUE-3:** dev error bodies leak the stack trace.
- **ISSUE-4:** `.env` flags conflict with the test rules.
- **ISSUE-5:** the integration test suite writes into the dev DB.
- **ISSUE-6 (mobile):** the restricted screen does not re-check on foreground.

---

## 2. Results per test case

Screenshots are in `C:\Users\LENOVO\AppData\Local\Temp\neos-pr55-run\shots\` (outside the repo, not committed). Names are given as `shots/NN-*.png`.

### 2.0 Setup checks

| Check | Result | Evidence |
|---|---|---|
| Backend on 8080 runs the current code | **Done (restarted)** | The previous backend was PID 37940 (child of `mvn spring-boot:run` PID 43872), started 03:17 local, after the branch checkout at 02:54. It had loaded `.env` with `NOTIFICATION_DISPATCHER_ENABLED=true` and `NEOS_CUSTOMER_TRANSFER_API_ENABLED=false` (ISSUE-4). With your approval I stopped it and started the same command (`.env` loaded into the process) with **process-only overrides**: `NOTIFICATION_DISPATCHER_ENABLED=false`, `NEOS_CUSTOMER_TRANSFER_API_ENABLED=true`, `MICROSOFT_GRAPH_ENABLED=false`. New PID 34532, "Started FintechBackendApplication in 20.766 s" at 03:53:19 local, `/actuator/health` → `{"status":"UP"}`. No file was edited. |
| Graph / dispatcher flags checked by name before starting | **Done** | `.env`: `MICROSOFT_GRAPH_ENABLED` = false. `NOTIFICATION_DISPATCHER_ENABLED` = **true** (overridden to false for the run). Values were compared in a script, never printed. |
| `SumsubApplicantSandboxIntegrationTest` with `-DRUN_SUMSUB_CREATE_IT=true` | **PASS** | `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 23.70 s`. The applicant-lookup response shape flagged in Audit 3 §1.2 works against the real Sumsub sandbox for an **existing** applicant (the test uses a deterministic user, so reruns reuse it). The "unknown user" path was then exercised live by TC-KYC-02 and also worked. |
| `SumsubProductionStartupIntegrationTest` (TC-ADM-10) | **PASS (re-run)** | First attempt: exit code 4, then the run was stopped (§7.1). **Re-run 14:17 local**, on its own. Root `.env` loaded into the process the way `scripts/run-backend-local.ps1` does (23 entries, values never printed). JDK = Android Studio JBR. Command: `mvnw.cmd test -Dtest=SumsubProductionStartupIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`. Result: exit code 0, "Nothing to compile - all classes are up to date.", `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.680 s`, `BUILD SUCCESS`, total time 5.3 s. All 6 passed: `validProductionConfigurationStarts`, `missingAppTokenPreventsProductionStartup`, `disabledWebhookPreventsProductionStartup`, `missingWebhookSecretPreventsProductionStartup`, `sandboxModePreventsProductionStartup`, `developmentDoesNotRegisterProductionValidator`. The expected rejections were logged as `IllegalStateException: Production KYC requires SUMSUB_APP_TOKEN` / `… requires SUMSUB_WEBHOOK_ENABLED=true` / `… requires SUMSUB_WEBHOOK_SECRET` / `… must have SUMSUB_SANDBOX_MODE=false`. The test builds its own context with fixed dummy values, so `.env` does not affect the result. Report: `target/surefire-reports/com.fintech.kyc.adapter.sumsub.SumsubProductionStartupIntegrationTest.txt`. The cause of the first attempt's exit code 4 was not investigated. |
| Native rebuild (lockfile changed by `chore/npm-audit`) | **Done** | JDK 17 (`C:\Program Files\Java\jdk-17`), `gradlew app:assembleDebug -PreactNativeArchitectures=x86_64 --max-workers=1`, emulator off: **BUILD SUCCESSFUL in 12m 59s**, `app-debug.apk` 65 MB. Gradle and Kotlin daemons were stopped (`gradlew --stop`) before the emulator started. |
| Emulator, `adb reverse`, Metro | **Done** | Pixel_6 AVD (API 36), `-no-snapshot-save`. `adb install -r` → Success. `adb reverse` 8080/8081/8082. Metro: `npx expo start --dev-client --port 8082 --clear`, `NODE_OPTIONS=--dns-result-order=ipv4first`, `EXPO_PUBLIC_MOCK_FEATURES=kycCapture,notifications`, `CI` unset. "Android Bundled 24725ms index.ts (1083 modules)". The app showed the "MOCK: kycCapture, notifications" banner (`shots/00-launch.png`). |
| Disk / RAM | ok | C: 20.8 GB free at start, 12.3 GB before the emulator (≥ 10 GB). RAM: 8.7 GB free of 31.7 GB at start. Before the 14:17 re-run: C: 23.9 GB free, RAM 13.5 GB free. |
| Processes before the re-run (14:17) | checked | No listener on port 8080, so the backend (PID 34532) was **not running**. No Maven/surefire JVM was running. The only `java.exe` was the IDE Java language server. |

### 2.1 Auth & session

| ID | Result | Evidence |
|---|---|---|
| TC-AUTH-01 Login | **PASS** | The new user (`neos-pr55-1@…`) and `neos-sender` both logged in through the Keycloak login form → app. `GET /api/auth/me` 200 `{subject, username, email}`. `neos-sender` → Home "Good to see you, Smoke", €80.00, Sandbox badge (`shots/21-sender-home.png`). The new user → KYC pending screen (`shots/16-after-login.png`). |
| TC-AUTH-02 Registration | **PASS** | "Let's begin" → Keycloak **Register** form (`shots/01-register.png`) → registered `neos-pr55-1@neos.local` (first/last name "Pr55 Tester") → app onboarding "First, the essentials" with the email prefilled (`shots/07-after-register.png`). `/api/auth/me` 200 (default `CUSTOMER` role active on the running realm). `/api/profile` 404 (not onboarded), as expected. |
| TC-AUTH-03 Token refresh | **PASS** | Logged in at 04:20:33 and left idle until 04:26:44 (> 300 s access token). "Check for an update" → request succeeded, no "session expired" (`shots/17-refresh-after-6min.png`). |
| TC-AUTH-04 Logout | **PASS** | Sign out → welcome. "Sign in" → Keycloak **login form** shown again (no silent SSO) (`shots/14-after-signout.png`, `shots/15-login.png`). |
| TC-AUTH-05 Silent cancel | **PASS** | Closing the Keycloak tab during registration → welcome, **no error** (`shots/03-after-close.png`). |
| TC-AUTH-06 Revoked token in use | **PASS** | Keycloak admin API (local dev admin, not a NEOS endpoint): `POST /admin/realms/neos/users/{id}/logout` → 204, sessions 5 → 0 at 04:27:23. At 04:33:29 "Check for an update" → "Your session has expired. Please sign in again." → welcome (`shots/18-revoked-in-use.png`). |
| TC-AUTH-07 Stale token at cold start | **PASS** | Signed in, sessions revoked (04:34:32), force-stop, waited until 04:40:26, cold start → plain welcome, **no error** (`shots/20-coldstart-stale.png`). |
| TC-AUTH-08 `/me` without email claim | **NOT RUN** | No test user without an email exists, and creating one was out of scope. The known bug is unchanged in code (`AuthController.java:22-28`). |
| TC-AUTH-09 Missing `CUSTOMER` role | **NOT RUN** | All available users have `CUSTOMER`: `neos-compliance` `/api/auth/me` → 200, because the running realm's default roles include `CUSTOMER`. A user without it would have to be created. |
| TC-AUTH-10 Biometric unlock | **PASS** | PIN 1234 set and fingerprint 1 enrolled through Settings + `adb -e emu finger touch 1` ("Fingerprint added"). Profile → Account security → App unlock → "Turn on app unlock" prompt → finger 1 → "On · fingerprint" (`shots/73-unlock-on.png`). Cold start → "Unlock NEOS" prompt before content → finger 1 → Home €70.00 (`acceptCrypto` 2) (`shots/76-unlocked.png`). Background 66 s → "Unlock NEOS" again → finger 1 → Home (`shots/77-bg-65s.png`, `shots/78-bg-unlocked.png`). |

### 2.2 Onboarding (new user `neos-pr55-1`, userId `0727bfdf-…`)

| ID | Result | Evidence |
|---|---|---|
| TC-ONB-01 Profile creation | **PASS** | Details (phone `+21620000055`) → review "Looking good?" → terms → "Confirm and continue" → KYC intro (`shots/08-onb-review.png`, `shots/09-after-onboarding.png`). DB: `users` row `PENDING_KYC`, `kyc_status` `NOT_STARTED`, provider `SUMSUB`. |
| TC-ONB-02 Device registration | **PASS** | DB: 1 `devices` row for the user (platform ANDROID). |
| TC-ONB-03 Terms acceptance | **PASS** | DB: 1 `terms_acceptances` row, version `1.0`. Later `GET /api/profile` `onboardedAt` = `2026-11-04T03:16:40.764853Z`. |
| TC-ONB-04 Wrong terms version | **PASS** (API) | `POST /api/onboarding/personal` with `termsVersion:"0.9"` → **400**, message "Unsupported terms version". |
| TC-ONB-05 Retry / idempotency | **PASS** (API) | Re-POST with the same `installationId` → 200, same `userId` and `deviceId` (`9442fe9a-…`). DB counts afterwards: users 1, devices 1, terms 1. |
| TC-ONB-06 Network failure | **NOT RUN** | Airplane mode does not cut `adb reverse` traffic (it goes over the adb transport), and the shared backend must not be stopped. This needs another way to drop traffic. |

### 2.3 Sumsub KYC

| ID | Result | Evidence |
|---|---|---|
| TC-KYC-01 Startup without secret | **NOT RUN** | Needs a second backend instance (memory: one thing at a time). |
| TC-KYC-02 Applicant creation, PENDING | **PASS** | App: simulated capture (mock) → "Submit for verification" → **"We're taking a closer look." / "Review in progress"** (`shots/12-kyc-result.png`). DB: `kyc_status` `PENDING`, provider `SUMSUB`, `provider_applicant_id` set, account `PENDING_KYC`. **No auto-approval.** `POST /api/kyc/start` → 200 `{"status":"PENDING","provider":"SUMSUB","verificationMode":"NON_PROD"}`. A real Sumsub sandbox applicant now exists for `externalUserId=0727bfdf-…`. |
| TC-KYC-03 Start idempotency | **PASS** | Second `POST /api/kyc/start` → 200 `PENDING`. The md5 of `provider_applicant_id` is unchanged. In the app, "Check for an update" stays on pending (`shots/13-kyc-refresh.png`). |
| TC-KYC-04 Start before onboarding | **PASS** | **409** "Personal onboarding must be completed before starting KYC". |
| TC-KYC-05 Sumsub unreachable | **NOT RUN** | Needs a second instance. |
| TC-KYC-06 SDK initialization | **BLOCKED** | No SDK-token endpoint (backend), and no Sumsub SDK in the app (mobile). |
| TC-KYC-07 / 08 / 09 / 11 / 13 / 14 (webhook decisions, security, duplicates) | **BLOCKED** | `SUMSUB_WEBHOOK_ENABLED` / `SUMSUB_WEBHOOK_SECRET` are absent from `.env`, there is no public HTTPS tunnel, and the app cannot submit documents. Forging webhooks is excluded by the run rules. |
| TC-KYC-10 Wallets only after approval | **PASS** (API) | As the PENDING user: `GET /api/wallets` → 200 `walletsStatus:"NOT_ELIGIBLE"`, all 6 `NOT_PROVISIONED` with `availableMinor:null, pendingMinor:null`. `POST /api/recipients/lookup` → **403**. The app routes a PENDING user to the KYC status screen, so the Home `NOT_ELIGIBLE` banner is not reachable for them. |
| TC-KYC-12 No self-reactivation | **PASS** | `neos-sender` set to `SUSPENDED` in the local DB → `POST /api/kyc/start` → 200 `{"status":"APPROVED","provider":"MOCK",…}`, and the DB still says **`SUSPENDED`**. Same with `CLOSED`: stays `CLOSED`. Restored afterwards (§6). |

### 2.4 Wallets

| ID | Result | Evidence |
|---|---|---|
| TC-WAL-01 Balances across currencies | **PASS** | `neos-sender` Home: EUR €80.00, USD $1.00, GBP £0.00, AED 0.00, MUR 1,000.00, **TND 0.000** (3 decimals) (`shots/21-sender-home.png`, `shots/22-sender-wallets.png`). This matches `GET /api/wallets` (`READY`, strings `"8000"`, `"100"`, `"0"`, `"0"`, `"100000"`, `"0"`) and Formance directly (EUR/2 input 12500, output 4500, balance 8000). |
| TC-WAL-02 `pendingMinor` | **BLOCKED** | No hold or payout endpoint. `pendingMinor` was `"0"` everywhere. |
| TC-WAL-03 `NOT_ELIGIBLE` | **PASS** (API) | See TC-KYC-10. |
| TC-WAL-04 `PROVISIONING` after an outage | **BLOCKED** | Needs a webhook approval. |
| TC-WAL-05 Formance outage while reading | **PASS** | `docker stop neos-formance-gateway` (05:05:11 local) → `GET /api/wallets` **503** "Wallet balances are temporarily unavailable". App Home kept €70.00 with "Last updated Nov 4, 4:04 AM" + "Balances are temporarily unavailable… Showing the last balances" (`shots/53-home-down.png`). Gateway restarted (05:06:39) → `GET /api/wallets` 200. |
| TC-WAL-06 Not onboarded | **PASS** (API) | New user before onboarding: `GET /api/wallets` → **404** "Customer profile not found. Personal onboarding is required." |

### 2.5 Receive

| ID | Result | Evidence |
|---|---|---|
| TC-RCV-01 Deposit information | **PASS** (internal only) | Receive screen: "Other NEOS users can send you money using your email.", own email + Copy, NEOS user ID + Copy (`shots/79-receive.png`). Bank deposit details: **BLOCKED**, no endpoint. |
| TC-RCV-02 Incoming internal transfer | **PASS** (API) | Receiver EUR history: `(95, TRANSFER, CREDIT, 1000)` at the top. Receiver EUR 6000 → 7000. The receiver's app view was not opened (§7). |
| TC-RCV-03 Incoming synthetic credit | **PASS** | `neos-compliance` → `POST /api/backoffice/synthetic-credits {userId: receiver, EUR, 1500, operationId: "pr55-rcv-1"}` → 200 `transactionId 94`, reference `incoming_payment:pr55-rcv-1`. Receiver EUR 4500 → **6000**. History top item: `INCOMING_PAYMENT`, `CREDIT`, 1500, `COMPLETED`. |
| TC-RCV-04 Credit idempotency | **PASS** | Same body again → 200 with the **same** `transactionId 94`. The balance stayed 6000 (one credit). |

### 2.6 Hold, 2.7 Convert

| ID | Result | Evidence |
|---|---|---|
| TC-HLD-01/02 | **BLOCKED** | No hold/reservation endpoint |
| TC-FX-01/02/03 | **BLOCKED** | No FX endpoint |
| TC-FX-04 Placeholder | **PASS** | "Convert — Currency conversion isn't available in this version of the app yet. Not available yet." (`shots/80-convert.png`) |

### 2.8 Send (`neos-sender` → `neos-receiver`)

| ID | Result | Evidence |
|---|---|---|
| TC-SND-01 Lookup eligible | **PASS** | `NEOS-Receiver@neos.local` (mixed case) → "How much? To n***@neos.local" (`shots/27-lookup-ok.png`) |
| TC-SND-02 Lookup not available | **PASS** | Unknown email, **own email** and the **PENDING user's email** → all "No eligible NEOS account found for this email." (`shots/24`, `25`, `26-lookup-*.png`) |
| TC-SND-03 Rate limit | **PASS** (API) | `neos-bio-sender`, 11 lookups within a minute: calls 1–10 → 200, call 11 → **429** "Too many recipient lookups" (a 12th → 429) |
| TC-SND-04 Amount guards | **PASS** | EUR "1,000" → "EUR allows up to 2 decimals." TND "1,000" → "= TND 1.000", "Available: TND 0.000", **"Insufficient balance"**. EUR "10" → "= €10.00", "Available: €80.00" (`shots/28`, `29`, `30-amt-*.png`) |
| TC-SND-05 Biometric confirmation / cancel | **PASS** | Review → "Confirm your transfer" system prompt (`shots/41-bio-prompt.png`). Finger 2 (not enrolled) rejected (`reject` 1), Back → **"Confirmation cancelled. Nothing was sent."** (`shots/42-bio-cancel.png`). Sender EUR still 8000 via the API. |
| TC-SND-06 Success | **PASS** | Finger 1 → **"Money sent."** €10.00, transaction ID **95**, reference `internal_transfer:ct_7bf8f8ad…_b5dd51ba…` (`shots/43-sent.png`). Sender EUR 8000 → 7000, receiver 6000 → 7000. DB claim `b5dd51ba-…` `SUCCEEDED`, `ledger_transaction_id` 95. |
| TC-SND-07 Duplicate submission (same key) | **PASS** (API) | Same body + same `idempotencyKey` → 200 `{"transactionId":95, "reference":"…b5dd51ba…"}`. No new posting (balance unchanged at 7000). |
| TC-SND-08 Same key, different params | **PASS** (API) | Amount 1001 → **409** "Idempotency key already used with different transfer parameters" |
| TC-SND-09 Insufficient funds | **FAIL** (vs desired; matches the known current behaviour) → **ISSUE-1** | TND 1000 with TND balance 0: **503** "Transfer outcome not confirmed. Reconciliation is required.", claim `ee231e7f-…` → **`UNKNOWN`**. One deliberate, manual same-key check → **409** "Transfer is in progress or requires reconciliation". TND balance still 0; nothing posted. |
| TC-SND-10 Self-transfer | **PASS** (API) | **400** "Cannot transfer to the same customer" |
| TC-SND-11 Ineligible recipient | **PASS** (API) | Recipient = the PENDING user → **403** (empty body) |
| TC-SND-12 Flag off | **NOT RUN** | Needs a second instance |
| TC-SND-13 No fee | **PASS** | Review: "Fee: No fee", "The money moves instantly…" (`shots/40-review.png`). History shows no `FEE` item. |

### 2.9 Activity / notifications

| ID | Result | Evidence |
|---|---|---|
| TC-ACT-01 List per currency | **PASS** | EUR: "Transfer sent −€10.00, Nov 4, 4:00 AM" at the top (`shots/44-activity-eur.png`). GBP: "No transactions in GBP yet." before the credits (`shots/46-activity-gbp.png`). Newest first, as observed. |
| TC-ACT-02 Detail | **PASS** | Transfer sent, −€10.00, Completed, Money out, EUR, Transaction ID 95 + Copy, Reference + Copy (`shots/45-detail.png`) |
| TC-ACT-03 Paging | **PASS** | 21 synthetic GBP credits of 1 minor unit (`pr55-page-01..21`, tx 96–116). API page 1: 20 items, `hasMore:true`, cursor; page 2: 1 item (`pr55-page-01`), `hasMore:false`. App: "Load more" at the end of page 1, gone after loading (`shots/47-gbp-bottom.png`, `shots/48-gbp-more.png`). |
| TC-ACT-04 `REVERSED` | **BLOCKED** | No API to revert |
| TC-ACT-05 `pageSize` out of range | **FAIL** (known) → **ISSUE-2** | `GET /api/transactions?currency=EUR&pageSize=0` → **500** "pageSize must be between 1 and 100" |
| TC-ACT-06 History while Formance is down | **PASS** | Gateway stopped → API **500**. App: "Your activity is temporarily unavailable. Please try again shortly." + Try again (`shots/54-activity-down.png`). After the restart, Try again → list shown (`shots/55-activity-up.png`). |
| TC-NTF-01 Outbox rows | **PASS** (DB) | 24 new rows, all `TRANSACTION_COMPLETED`, `EMAIL`, **`PENDING`, `attempt_count 0`**: credit 94 (receiver), transfer 95 (sender **and** receiver), tx 96–116 (sender). Keys look like `TRANSACTION_COMPLETED:<tx>:<userId>:EMAIL`. The UNKNOWN claims created no rows. Outbox totals: `PENDING` 14 → 38. `PROCESSING` 2, `DEAD_LETTER` 4 and `PROVIDER_ACCEPTED` 27 are unchanged (all from before this run). **No email was sent.** |
| TC-NTF-02 Email delivery | **BLOCKED** (policy) | Graph off |
| TC-NTF-03 Notifications feed | **PASS** (placeholder) / feed **BLOCKED** | "In-app notifications aren't available yet. Account updates are sent to you by email." (`shots/81-bell.png`) |

### 2.10 Negative scenarios

| ID | Result | Evidence |
|---|---|---|
| TC-NEG-01 Suspended account | **PASS** | `neos-sender` set to `SUSPENDED` (local DB). API: profile 200 `accountStatus:"SUSPENDED", kycStatus:"APPROVED"`, wallets 200, lookup **403**, transfer **403** (no claim row created). App, back in the foreground: **"Your account is unavailable. Please contact your NEOS support contact…"** + Sign out (`shots/64-suspended.png`). |
| TC-NEG-02 Closed account | **PASS** | `CLOSED`: profile `"CLOSED"`, transfer **403**, `kyc/start` does not reactivate, app shows the same restricted screen (`shots/65-closed.png`). Restored to `ACTIVE` afterwards (§6). See ISSUE-6 for the app's behaviour after the restore. |
| TC-NEG-03 Expired token mid-transfer | **PASS** | Review €0.50 reached. Sessions revoked at 05:24:02. Confirm with finger 1 at 05:29:44 → **"Your session has expired. Please sign in again."** → welcome (`shots/83-expired-mid-transfer.png`). DB: **0** new claims, so nothing reached the transfer endpoint and no money moved. Not verified: whether the app kept a pending-transfer record. |
| TC-NEG-04 Duplicate submission | **PASS** (API) | See TC-SND-07/08. A UI double tap was not attempted: the biometric prompt gates every confirm. |
| TC-NEG-05 API failure during send (Formance stopped) | **PASS** | Review €1.00 → gateway stopped → confirm (finger 1) → **"Transfer is being processed."**, deterministic reference `…_c7e0dd66…`, Check activity / Check status / Try again / Done (`shots/51-uncertain.png`). DB: claim `c7e0dd66-…` **`UNKNOWN`**, EUR 100. **No automatic retry:** only one claim, and Formance has **0** transactions with that reference (a control query for tx 95 finds 1). Check status with the gateway down → "We couldn't check right now." (`shots/52-check-down.png`). Home banner "A transfer is being confirmed · €1.00 to n***@neos.local". After the restart: Check status → "Not confirmed yet: it doesn't appear in your activity." (`shots/57-check-up.png`). **"Try again" was deliberately not pressed.** |
| TC-NEG-06 Network timeout | **NOT RUN** | Same reason as TC-ONB-06 (`adb reverse` is not affected by airplane mode) |
| TC-NEG-07 / 08 Admin-resolved uncertain outcome | **BLOCKED** | Admin flags not set, and the `RECONCILIATION_ADMIN` role is absent from the realm |
| TC-NEG-09 Stop tracking + duplicate warning | **PASS** | Stop tracking → warning sheet ("We couldn't confirm this transfer. It may still appear in your Activity…") → confirmed → banner gone. A new Send of €1.00 to the same recipient → **"You may have sent this already"** sheet with Check activity / Continue anyway (`shots/63-dup-warning.png`). Closed without sending. |
| TC-NEG-10 Malformed 200 | **NOT RUN** | Unit tests only |

### 2.11 Admin / flags

| ID | Result | Evidence |
|---|---|---|
| TC-ADM-01 … 07 | **BLOCKED** | Reconciliation flags not set, `RECONCILIATION_ADMIN` role missing. TC-ADM-01 is also excluded for the shared instance. |
| TC-ADM-08 Synthetic credit role | **PASS** | The same request with a `CUSTOMER` token (`neos-sender`) → **403**. With `neos-compliance` → 200. |
| TC-ADM-09 Transfer flag off | **NOT RUN** | Needs a second instance |
| TC-ADM-10 Prod startup validation | **PASS (re-run)** | 6/6 tests passed at 14:17 local; see §2.0 |

---

## 3. Issues (backend developer's format)

### ISSUE-1 — [Send] Insufficient funds returns 503 + UNKNOWN and blocks the key forever (FAIL, TC-SND-09)

- **Endpoint:** `POST /api/transfers/internal`
- **HTTP status:** actual **503**, then **409** on a same-key check. Expected **4xx** (e.g. 422) with `errorCode: "INSUFFICIENT_FUNDS"`.
- **Request (redacted):**
  ```http
  POST /api/transfers/internal
  Authorization: Bearer ***
  Content-Type: application/json

  {"destinationUserId":"f61b6653-…","currency":"TND","amountMinor":1000,"idempotencyKey":"ee231e7f-7356-4339-96e9-968d783640e0"}
  ```
- **Response (redacted):**
  ```json
  {"timestamp":"2026-11-04T04:01:15.623Z","status":503,"error":"Service Unavailable","trace":"<19277 chars>","message":"Transfer outcome not confirmed. Reconciliation is required.","path":"/api/transfers/internal"}
  ```
  The same key a second time:
  ```json
  {"timestamp":"2026-11-04T04:01:19.170Z","status":409,"error":"Conflict","trace":"<16519 chars>","message":"Transfer is in progress or requires reconciliation","path":"/api/transfers/internal"}
  ```
- **Expected:**
  - a definitive rejection;
  - the claim released or marked failed;
  - an `errorCode` the app can show ("Insufficient balance").
- **Actual:**
  - the claim is `UNKNOWN` in `customer_transfer_idempotency`;
  - nothing was posted (TND balance 0);
  - the key returns 409 forever;
  - admin confirm cannot resolve it either, because there is no evidence to confirm (Audit 3 §1.3).
- **Reproduction:**
  1. Sign in as a sender with a TND balance of 0.
  2. Send the request above with a new UUID key.
  3. Send it again with the same key.
- **Code:** `ledger/adapter/formance/FormanceClient.java:833-846` (4xx → `LedgerTransactionRejectedException`), `ledger/CustomerTransferIdempotencyService.java:268-301` (catch-all → UNKNOWN + 503), `:386-390` (409).
- **Severity:** High (contract). The app's balance guard hides it in normal use (TC-SND-04).

### ISSUE-2 — [Activity] `pageSize` out of range returns 500 instead of 400 (FAIL, TC-ACT-05)

- **Endpoint:** `GET /api/transactions?currency=EUR&pageSize=0`
- **HTTP status:** actual **500**, expected **400**
- **Request:** `GET /api/transactions?currency=EUR&pageSize=0`, `Authorization: Bearer ***`
- **Response:** `{"status":500,"error":"Internal Server Error","trace":"<16328 chars>","message":"pageSize must be between 1 and 100","path":"/api/transactions"}`
- **Expected vs actual:** a client validation error must be a 4xx. Today an `IllegalArgumentException` becomes a 500, so the app's "temporarily unavailable" path is triggered by a client bug.
- **Reproduction:** any customer token, `pageSize=0` or `101`.
- **Code:** `ledger/CustomerTransactionHistoryService.java:61-68`
- **Severity:** Low

### ISSUE-3 — [All endpoints, dev] Error bodies include the full Java stack trace and exception message (finding)

- **Endpoint:** every error response in the dev profile (seen on 400, 403-from-service, 404, 409, 429, 500, 503)
- **HTTP status:** n/a (body content)
- **Example response (redacted):** `{"timestamp":"…","status":404,"error":"Not Found","trace":"com.fintech.onboarding.CustomerProfileNotFoundException: Customer profile not found… at …CustomerProfileService.getProfile(CustomerProfileService.java:49) …","message":"Customer profile not found. Personal onboarding is required.","path":"/api/profile"}`. The `trace` is 16–19 KB.
- **Expected:** no stack traces in API responses. Also, the contract (§0.3) assumes `message` is omitted.
- **Actual:**
  - The backend runs with Spring Boot **devtools**: the log thread is named `restartedMain`, and devtools sets `server.error.include-stacktrace=always` / `include-message=always` by default.
  - Class names, line numbers and internal messages are exposed to any client.
  - The app's error handling does not depend on this.
- **Reproduction:** `GET /api/profile` with a token of a user who is not onboarded.
- **Severity:** Medium if devtools or these defaults can reach any shared or deployed environment; Low for local-only. Recommend confirming devtools is `optional`/excluded from packaged builds, and setting `server.error.include-stacktrace=never` explicitly.

### ISSUE-4 — [Env] Root `.env` disables transfers and enables the dispatcher (finding, local config)

- **Item:** `.env` (names only): `NEOS_CUSTOMER_TRANSFER_API_ENABLED` = false, `NOTIFICATION_DISPATCHER_ENABLED` = true. `KYC_MODE` is still present but ignored.
- **Effect:**
  - `scripts/run-backend-local.ps1` (and the `spring.config.import` of `./.env`) turns **off** recipient lookup and transfers in dev. Every Send case would return 404.
  - The dispatcher is on. It is harmless only while Graph is off (`NotificationDeliveryWorker.runOnce` returns 0 without an email port), but it contradicts the "both false" rule.
- **Expected:** a dev `.env` matching the plan (transfers on, dispatcher off).
- **Severity:** Low (local). For this run the two flags were overridden per process only; nothing was edited.

### ISSUE-5 — [Tests/DB] The backend integration test suite writes into the dev database (finding)

- **Evidence:** `fintech_dev` has 140 `users` before this run. Most are test fixtures (`sender.<uuid>`, `pending.<uuid>`, `suspended.<uuid>`, `profile.<uuid>`, `wallets.it.<uuid>`, …) created on 2026-11-02, mixed with the manual test users. The `SumsubApplicantSandboxIntegrationTest` run also used this DB.
- **Expected:** tests use an isolated DB (Testcontainers or a separate schema).
- **Severity:** Low. It pollutes dev data, and test runs can interfere with manual testing.

### ISSUE-6 — [Mobile] The restricted screen does not re-check account status when the app returns to the foreground (observation, mobile)

- **Steps:**
  1. Account `SUSPENDED` → the app shows "Your account is unavailable".
  2. Restore to `ACTIVE` in the DB.
  3. Home → reopen the app: it **still** shows "unavailable" (`shots/66-restored.png`).
  4. Only a cold start goes to Home (`shots/67-restored-cold.png`).
- **Expected:** the foreground re-check (`docs/api/INTEGRATION_STATUS.md`, "KYC / account status … foreground re-check") should also leave the restricted state, or the screen should offer a "Check again" action.
- **Severity:** Low (mobile). No backend change is needed.

---

## 4. Blocked items and exact reasons

| Item | Reason |
|---|---|
| Sumsub SDK initialization (TC-KYC-06) | **Missing endpoint**: no SDK-token API (`SumsubKycProvider.createVerificationSession` has no caller). **Missing mobile feature**: no Sumsub SDK. |
| KYC approval / rejection / resubmission / webhook security (TC-KYC-07/08/09/11/13/14), wallets `PROVISIONING` (TC-WAL-04) | **Missing secret**: `SUMSUB_WEBHOOK_ENABLED`, `SUMSUB_WEBHOOK_SECRET` are not in `.env`. **Missing tunnel**: no public HTTPS URL for the Sumsub webhook. **No document upload path** from the app. Forging webhooks is excluded by the run rules. |
| Resubmission | **Missing endpoint** (`kyc/start` short-circuits; no SDK token) |
| Holds (TC-HLD-01/02), `pendingMinor` (TC-WAL-02) | **Missing endpoint** (reservations exist only inside payout services, with no HTTP entry) |
| Convert (TC-FX-01/02/03) | **Missing endpoint** |
| Bank deposit / top-up (part of TC-RCV-01) | **Missing endpoint** |
| `REVERSED` history item (TC-ACT-04) | No API to revert a transaction |
| Email delivery (TC-NTF-02) | **Policy**: Graph stays off |
| In-app notifications feed | **Missing endpoint** |
| Admin reconciliation and repair (TC-ADM-01…07, TC-NEG-07/08) | **Missing flags** (`neos.transfers.reconciliation.*` not set) and **missing realm role** `RECONCILIATION_ADMIN` |

## 5. Endpoints missing or incompatible with mobile

- **Missing:**
  - Sumsub SDK access token
  - KYC resubmission
  - top-up / deposit instructions
  - holds / reservations
  - FX quote / convert
  - payouts
  - notifications feed
  - customer transfer status by key
- **Incompatible / contract gaps seen live:**
  - ISSUE-1: no definitive transfer rejection and no `errorCode`. The app's `INSUFFICIENT_FUNDS` handling is never reached.
  - ISSUE-2: a 500 for a client error.
  - No `errorCode` anywhere. The 403 from lookup and transfer has an **empty body**, so the app cannot tell why.
  - ISSUE-3: the dev error-body shape differs from contract §0.3 (`message` and `trace` present). The app does not rely on it.
- **Compatible (verified live):**
  - `GET /api/profile`, `GET /api/wallets` (string amounts, `scale`, `NOT_PROVISIONED` with `null`, 503 behaviour)
  - `POST /api/recipients/lookup` (uniform 404, 429)
  - `POST /api/transfers/internal` (200, idempotent replay, 409, 400, 403, 503 → uncertain)
  - `GET /api/transactions` (cursor paging, newest first observed)
  - `POST /api/kyc/start` (`PENDING`, idempotent, no reactivation)
  - `POST /api/onboarding/personal`

---

## 6. Environment and state changes

### 6.1 Environment

| Component | Version / detail |
|---|---|
| Branch / commit | `test/integration-pr55` @ `a2f97a8` (backend code = PR #55 `a74c4e8`; mobile with `chore/npm-audit` `de1981d`, `expo-auth-session ~55.0.19`) |
| Backend | dev profile, port 8080, PID 34532, started 03:53:19 local. JDK 21.0.6 (Android Studio JBR, as in `scripts/run-backend-local.ps1`). `.env` loaded, with process-only overrides `NOTIFICATION_DISPATCHER_ENABLED=false`, `NEOS_CUSTOMER_TRANSFER_API_ENABLED=true`, `MICROSOFT_GRAPH_ENABLED=false`. |
| Keycloak | 26.7.2, container `neos-keycloak`, `:8081`, realm `neos` (R1 applied on the running realm) |
| Formance | gateway `neos-formance-gateway` `:8083` (v2.4.14 per earlier runs), ledger `neos` |
| Postgres (app) | `neos-backend-db` `:5434`, DB `fintech_dev` |
| Mobile build | JDK 17, `assembleDebug` x86_64, `--max-workers=1`, 12m59s |
| Emulator | AVD `Pixel_6`, Android API 36, `-no-snapshot-save` |
| Metro | `:8082`, `--clear`, `EXPO_PUBLIC_MOCK_FEATURES=kycCapture,notifications` |
| Test client | Keycloak `neos-cli-test` (password grant) for API tokens. Tokens were stored in temp files and never printed. |

### 6.2 Changes made during the run

| Change | Status |
|---|---|
| `neos-sender` `users.account_status` → `SUSPENDED`, then `CLOSED` (local DB) | **Restored** to `ACTIVE`, with the original `updated_at` `2026-11-02 00:28:02.211942+00` (verified by `RETURNING`) |
| New Keycloak user `neos-pr55-1@neos.local` (running realm), NEOS user `0727bfdf-cf6c-4c11-9f49-152232346e6f` (`PENDING_KYC`/`PENDING`), and a **Sumsub sandbox applicant** for it | Left in place (test data). Delete them if not wanted. |
| Synthetic credits | receiver EUR +1500 (tx 94); sender GBP +21 × 1 (tx 96–116) |
| Internal transfer | €10.00 sender → receiver (tx 95, claim `b5dd51ba-…` `SUCCEEDED`) |
| **New UNKNOWN claims** (cannot be resolved today) | `ee231e7f-7356-4339-96e9-968d783640e0` (TND 1000, insufficient funds) and `c7e0dd66-5f88-4b7d-87f6-96a2e9556703` (EUR 100, Formance down). Neither was posted. |
| Final balances | sender EUR 70.00, USD 1.00, GBP 0.21, MUR 1,000.00, TND 0.000; receiver EUR 70.00 |
| `notification_outbox` | +24 `PENDING` rows (never sent; Graph off, dispatcher off) |
| Keycloak sessions | All sessions of `neos-pr55-1` and of `neos-sender` (26, including the CLI test-client sessions) were logged out through the Keycloak admin API |
| Formance gateway | Stopped 05:05:11 and **started again** 05:06:39 (verified by a 200 from `GET /api/wallets` afterwards) |
| Emulator | PIN `1234` set and fingerprint 1 enrolled, then **cleared** (`locksettings clear --old 1234` → "Lock credential cleared", fingerprint count **0**). App unlock was turned on for `neos-sender`; that session then expired. The emulator was shut down (`adb emu kill`). The NEOS debug APK remains installed. |
| Metro | Stopped |
| Graph | **Off the whole time** (`MICROSOFT_GRAPH_ENABLED=false`). No email sent. |
| Handling incident | During registration, a screen dump printed a generated throwaway password into the session log. It was never used: no account was created with it. It was rotated, and the helper now redacts secret values. No user-supplied password or token was ever printed. |

---

## 7. Remaining

### 7.1 What was running when the run stopped

- ✅ **DONE (re-run 2026-11-04 14:17 local → PASS, 6/6; see §2.0).** Original note, kept for history: **TC-ADM-10:** `mvnw.cmd -q test -Dtest=SumsubProductionStartupIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` (JDK 21, emulator already off) returned **exit code 4**. The next command, which read `C:\Users\LENOVO\AppData\Local\Temp\neos-pr55-run\prod-startup-it.log` and `target/surefire-reports`, returned **exit code 107** with no output. The run was stopped before diagnosing it. **The result is unknown.**
  - Possible causes, none verified: Maven/surefire failure, a missing env for the test context, or a lack of memory.
  - Re-run it on its own and read `target/surefire-reports/*SumsubProductionStartup*.txt`.
- **Backend process:** the background task that started the backend (PID 34532) was reported **"stopped"** when the previous session ended. **Whether the backend on 8080 is still running is UNVERIFIED** (not checked, per the instruction not to run anything). *Update 14:17: nothing is listening on 8080, so the backend is down.* If it is down, restart it with `scripts/run-backend-local.ps1`. With `.env` as it is, transfers will then be **off** and the dispatcher **on** (ISSUE-4).

### 7.2 Cases still to run

| Case | What is needed |
|---|---|
| ~~TC-ADM-10 prod startup IT~~ | ✅ **Done**: re-run 14:17 local → PASS (6/6) |
| TC-AUTH-08 `/me` without email | A Keycloak user without an email |
| TC-AUTH-09 missing `CUSTOMER` role | A Keycloak user without `CUSTOMER` (the default roles include it) |
| TC-ONB-06, TC-NEG-06 network failure / timeout | A way to drop app → backend traffic other than airplane mode (e.g. temporarily remove `adb reverse tcp:8080`, or use a delaying proxy) |
| TC-KYC-01, TC-KYC-05, TC-SND-12, TC-ADM-09 | A second backend instance on another port (memory permitting) |
| TC-RCV-02 in the app (receiver's Home / Activity) | Sign in as `neos-receiver` (passed at the API level) |
| TC-NEG-03 sub-check | Sign back in as `neos-sender` and check whether a pending-transfer banner remains from the expired-session attempt |
| TC-NEG-04 UI double tap | Optional (the biometric prompt gates every confirm) |

### 7.3 State still to clean up

| Item | Action |
|---|---|
| **`C:\Users\LENOVO\AppData\Local\Temp\neos-pr55-run\creds.env`** (`TEST_PASSWORD`, `BIO_TEST_PASSWORD`) | **NOT deleted yet**: the run stopped before cleanup. Delete it. (It was moved here from the previous session's scratchpad, which the environment had marked unusable.) |
| `C:\Users\LENOVO\AppData\Local\Temp\neos-pr55-run\newuser.env` (generated password of `neos-pr55-1`) | Delete, or keep if you want to reuse that user |
| `C:\Users\LENOVO\AppData\Local\Temp\neos-pr55-run\tok_*` (expired access tokens), `resp.txt`, `ui.xml`, `cur.txt`, `k9`, `orig_sender.txt` | Delete |
| `C:\Users\LENOVO\AppData\Local\Temp\neos-pr55-run\shots\` (screenshots with masked test data) | Keep as evidence or delete |
| `C:\Users\LENOVO\AppData\Local\Temp\claude\b7735530c15d9cf7\198c2234-90f7-4f1b-b566-9818eed042ea\scratchpad\` (`backend.log`, `emulator.log`, `metro.log`, `login_time`) | Delete. `backend.log` may still be written to if the backend is alive. |
| DB: `neos-sender` status | Already restored. Nothing else to restore. |
| DB: the two new `UNKNOWN` claims, 24 `PENDING` outbox rows, test credits and transfer | Test data. They cannot be resolved through the API; leave them, or clean the local DB by hand. Do **not** turn on Graph + dispatcher with this backlog (Audit 3 §5.5). |
| Keycloak user `neos-pr55-1@neos.local` and its Sumsub sandbox applicant | Optional delete |
| Emulator fingerprint / PIN | Already cleared (count 0). The emulator is off. |
| Background processes | Metro: stopped. Emulator: stopped. Gradle/Kotlin daemons: stopped. Maven test JVM (exit 4): **gone** (checked 14:17; no Maven/surefire JVM running). Backend PID 34532: **not running** (checked 14:17; nothing listening on 8080). The IDE Java language server (PID 28400) was left untouched. |
