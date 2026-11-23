# NEOS Mobile & API Integration — Engineering Log

Updated Nov 9, 2026

## 1. Purpose and context

This log records the mobile and API integration work on NEOS between 25 October and 5 November 2026: what was found, what was built, what was decided, what was rejected, and how each result was tested. It is written to support a later report and to show that every claim about the app rests on evidence.

**The product.** NEOS (also called Tanit and Nova Pay in the specification documents) is a multi-currency fintech app for individuals and businesses. It runs on a sponsor-bank model: a Mauritian partner bank holds the real funds in a master account, while NEOS keeps the internal ledger of wallets and transactions (Formance) and reconciles it against the bank.

**Phase 1 scope (contract).** An Android app plus a web backoffice: account creation, KYC/KYB, multi-currency wallets, top-up by bank transfer with a unique reference, internal and external transfers, FX conversion, history, receipts, notifications and support. Cards, crypto, pockets and advanced business features are out of scope.

**Team and ownership.**

| Area | Owner | Notes |
| --- | --- | --- |
| Backend core (Spring Boot, Formance, Sumsub, payouts, transfers) | Backend developer | Owns the transfer and KYC logic and the shared configuration |
| Mobile app (React Native + Expo) | Frontend developer | Owns `mobile/` and the integration docs |
| Customer read APIs (wallets, profile, recipient lookup) | Frontend developer | Contributed to the backend through a reviewed PR, following the backend developer's conventions |

**Stack.** Backend: Java 21, Spring Boot 4.1, Spring Modulith, PostgreSQL + Flyway, Keycloak (OIDC/JWT), Formance ledger, Sumsub (KYC), Microsoft Graph (email). Mobile: React Native 0.83, Expo SDK 55, TypeScript strict. Implementation was carried out with Claude Code sessions under the review process described in section 2.

## 2. Working method

Every change followed the same discipline: audit before acting, plan before coding, verify before claiming.

1. **Audit first, read-only.** Before any change, a read-only audit mapped the real state of the code. Audits never modified files and were written to `docs/audits/` and `docs/api/`.
2. **Evidence rules.** Every finding cites `file:line`. Claims are labelled VERIFIED (seen in code or by a command run), RISK (inferred) or RECOMMENDATION. Anything not checked is marked UNVERIFIED. Nothing is reported as tested unless it was run.
3. **Plan, approve, implement.** Each implementation session first presented a plan and stopped. The plan was reviewed, decisions were answered explicitly, and only then was code written. No session committed on its own.
4. **Independent checks after each session.** TypeScript (`tsc --noEmit`), lint, the full test suite, a release-bundle leak check and a release-readiness report were re-run, then the result was exercised live on an emulator against the real backend whenever possible.
5. **Ownership boundaries.** Backend code owned by the backend developer (transfer idempotency, KYC start, Sumsub decisions, shared error handling, realm and compose files) was never modified. Findings about it were sent as questions, and needed changes were documented as requests (R0 to R5).
6. **Branch and PR discipline.** One branch per work package, a pull request into `integration` for review, never a direct push to the shared branch, separate commits for unrelated changes (for example a personal file removed on its own).
7. **Secrets.** Real credentials (the backend developer's `.env`) are never committed, pasted into chats or prompts. Local test accounts exist only in the local Keycloak container.

## 3. Work record

Twenty-nine work packages took the app from a demo with three real endpoints to an end-to-end app with real balances, transfers, history, biometric unlock, real Sumsub KYC, receipts, notifications, sandbox currency conversion and read-only staff customer financial views.

| # | Dates | Work package | Outcome |
| --- | --- | --- | --- |
| WP1 | 25 Oct | Specification and architecture analysis | Shared understanding, 9 gaps and contradictions listed |
| WP2 | 25 Oct | Integration audit of the first frontend | 2 of 15 Phase 1 journeys really wired (about 13%) |
| WP3 | 25 Oct | Branch mapping | Stacked PRs understood; latest backend branch identified |
| WP4 | 25 Oct | Frontend quality audit | Score 6.7/10; 13 findings incl. one critical (SEC-01) |
| WP5 | 25 Oct | SEC-01 fix: fail-closed demo mode | Mock impossible in release builds; 22 tests |
| WP6 | 25–26 Oct | Android toolchain setup | App built and running on the emulator |
| WP7 | 26 Oct | Mock centralization | Per-feature mocks, registry, guardrails; 32 tests |
| WP8 | 26 Oct | Home render crash fix | Root cause found and a lint guard added |
| WP9 | 26 Oct | Backend C1–C3 merge, delta audit, first real run | First real end-to-end flow; wallet-loss risk confirmed |
| WP10 | 1 Nov | Delta audit 2 + mobile API contract | 5 customer endpoints mapped; 3 backend security/contract bugs found |
| WP11 | 1 Nov | Acceptance criteria + gap analysis + balance API proposal | Backlog from GitHub issues #17, #22–#29 |
| WP12 | 2 Nov | Backend customer read APIs | 3 endpoints, 407 backend tests, PR #51 merged |
| WP13 | 2 Nov | Mobile foundations + real dashboard and profile | Real balances on screen; PRs #52/#53 |
| WP14 | 2 Nov | Keycloak sign-up and session fixes | Registration flow, verified screens once, silent stale token |
| WP15 | 2 Nov | Send, Activity, Receive | Real transfers with idempotent outcomes; 112 tests |
| WP16 | 3 Nov | Biometric unlock, recents privacy, login theme | Fingerprint lock verified live; 144 tests |
| WP17 | 3 Nov | Repository hygiene | Personal file removed; docs PR #50 |
| WP18 | 3–4 Nov | Delta audit 3 + integration test run (PR #55) | 54 passed, 2 failed, 9 not run, 25 blocked; prod startup validation 6/6 |
| WP19 | 3–4 Nov | npm audit cleanup | 45 → 38 advisories, critical removed, no version changes |
| WP20 | 4 Nov | Sumsub SDK feasibility and native spike | Native build proven; 2 security issues found early |
| WP21 | 4–5 Nov | Real Sumsub KYC flow | SDK flow with token renewal and hidden screens; PR #65; 165 tests |
| WP22 | 5 Nov | Email notifications test | Real delivery in under a minute; 6 backend findings |
| WP23 | 5 Nov | PDF receipts | Receipts from Activity and Send; 177 tests |
| WP24 | 5 Nov | In-app notifications (Option A) | `GET /api/notifications` + real feed, bell and device read state; 195 tests |
| WP25 | 5–6 Nov | Mobile design refresh (in progress) | Home card wallet switcher, Details sheet, Activity by day, Profile cleanup (D35–D39); other screens pending |
| WP26 | 6 Nov | FX conversion: audit, mobile Convert flow, backend fixes | Convert live on the emulator (quote, expiry, re-quote, convert, uncertain path); 3 backend bugs fixed on `fix/fx-conversion-bugs`; 218 tests |
| WP27 | 9 Nov | Backoffice Package B customer directory | Read-only connected list/detail; 640 backend tests with 60 skips, 26 frontend tests, six invented-account browser flows |
| WP28 | 9 Nov | Backoffice Package C financial reads | Customer wallets and history; live Formance transfer and payout, 645 backend tests with 61 skips, 31 frontend tests |
| WP29 | 9 Nov | Backoffice usability pass | Session-bound directory position, customer navigation, focus and responsive tables; 33 frontend tests |

### WP1. Specification and architecture analysis

The two specification documents and the deployment diagram were analysed. Contradictions recorded: three product names (Nova Pay, Tanit, Neos); the diagram marked the KYC provider and sponsor bank as optional while both are core to Phase 1; SMS, push, FX source, WAF and reconciliation missing from the diagram; Android-only scope versus an iOS/web diagram; "two" versus three validations; different IP-transfer terms between the two documents; and no stated source of truth between PostgreSQL wallets and the Formance ledger.

### WP2. Integration audit of the first frontend

Read-only audit of branch `feat/frontend` (commit `7e4e2a5`). Findings: the backend exposed only `GET /api/auth/me`, `POST /api/onboarding/personal` and `POST /api/kyc/start`; demo mode was ON by default; wallets, transfers, FX, history, notifications and backoffice were UI mocks or absent; KYC auto-approved through a mock provider. Result: about 13% of Phase 1 journeys genuinely end to end.

### WP3. Branch mapping

The backend PRs were stacked (C1 into `dummy`, C2 into C1's branch, C3 into C2's branch), so the most complete code sat on `feat/c2-wallet-provisioning`, not on `dummy` or `main`. This avoided integrating the frontend against an incomplete backend.

### WP4. Frontend quality audit

Twelve criteria scored with evidence; weighted score 6.7/10. Strengths: strict TypeScript with no `any`, a clean services seam, a centralized async/lock wrapper, typed statuses, accessibility. Key findings: SEC-01 (demo mode default-on, critical), FIN-01 (no money module), FIN-02 (no step-up confirmation), ARC-01/02 (flat routes, monolithic hook), TEST-01 (real service path untested), UX-01 (no FR/AR, inert RTL). Later checks: `tsc` and lint clean; `npm audit` reported 12 vulnerabilities, all in build tooling.

### WP5. SEC-01 fix: fail-closed demo mode

Demo mode now requires a development build AND an exact opt-in value; release builds can never use mocks, and the mock module is stripped from release bundles. Configuration moved into a pure, tested `resolveConfig`, with startup validation in release (HTTPS only, required values, no `REPLACE_ME`). Three EAS build profiles were added (development, staging, production). Commit `0865799`.

### WP6. Android toolchain setup

Resolved in sequence: `ANDROID_HOME` and `JAVA_HOME` (JDK 17 for Android, JDK 21 for the backend), the project's `NEOS_ANDROID_JAVA_HOME`, DNS failures reaching Gradle and Google repositories, missing NDK 27 and CMake, an intermittent "access denied" in native compilation (fixed by building one ABI with `--max-workers=1`), and Metro listening only on IPv6. Recurring low-memory stops showed the PC cannot run Docker, backend, emulator and Gradle at once.

### WP7. Mock centralization

Every fake behaviour moved behind one switch, `EXPO_PUBLIC_MOCK_FEATURES` (all, none or a list), with a registry of each mock, a unique marker per mock module, a lint rule forbidding mock imports outside the services seam, an on-screen dev banner, a release-bundle check that fails if any marker ships, and a release-readiness report. Fake data was removed from screens. Commit `eb45caa`.

### WP8. Home render crash fix

The error "Text strings must be rendered within a Text component" came from `{' '}` left after `</Sheet>` inside a ScrollView, introduced by the mock refactor. Fixed, plus `!!description` on a string condition, plus an ESLint rule catching whitespace children of non-Text components. Lesson recorded: an earlier claim that the bug pre-existed was wrong, because Metro was running in CI mode without reloading. Commit `d86d15f`.

### WP9. Backend C1–C3 merge, delta audit and first real run

The frontend was merged with the C1–C3 backend (Formance ledger taxonomy, wallet provisioning after KYC, synthetic credits) without conflicts (merge `051cfec`). A delta audit found no wallet read endpoint, synthetic credits enabled in every profile including prod, no local Formance setup, a port clash (backend default 8082 = Metro) and provisioning without retry. The full stack was then run locally for the first time: real Keycloak login, onboarding and KYC on the emulator, and 6 Formance wallets created. A deliberate failure test (Formance stopped during KYC) confirmed the risk: `/api/kyc/start` returned 200 APPROVED and the wallets were never created, even after Formance came back.

### WP10. Delta audit 2 and mobile API contract

After the backend developer's PR #49 (Sumsub, transfers, payouts, reconciliation, notifications), a second read-only audit verified his report against the code instead of trusting it. It produced `docs/api/MOBILE_API_CONTRACT.md`, the reference for all later wiring. Customer-callable endpoints: auth/me, onboarding, KYC start, transaction history and internal transfers (behind a flag). Findings sent to the backend developer: a suspended account could reactivate itself through `POST /api/kyc/start`; `KYC_MODE` defaulted to MOCK in prod; a Formance rejection (e.g. insufficient funds) on a transfer was recorded as UNKNOWN with a 503, blocking the idempotency key forever; no balance, profile, SDK-token or error-code contract.

### WP11. Acceptance criteria, gap analysis and balance API proposal

GitHub issues #17 (B5) and #22–#29 (D1–D8) were extracted read-only into `docs/product/MOBILE_ACCEPTANCE_CRITERIA.md`, with a criterion-by-criterion gap analysis. A balance API contract was proposed (`docs/api/PROPOSED_BALANCE_API.md`): per-currency available and pending amounts as strings, the currency scale, and NOT\_PROVISIONED instead of a misleading zero. Docs were merged through PR #50.

### WP12. Backend customer read APIs (PR #51)

To gain time, the frontend developer implemented three endpoints in the backend, following the backend developer's module boundaries and error style, without touching his transfer or KYC services. `GET /api/wallets` reads Formance in two queries (account metadata + volumes with an `$or` filter, verified live first). `GET /api/profile` is a read-only status source with no side effects. `POST /api/recipients/lookup` finds an eligible recipient by email with a uniform 404 and a per-user rate limit. Transfers were enabled in the dev profile only. Result: 407 backend tests, 0 failures, and a live smoke test: credit, balance, lookup, transfer, both balances updated (7500 / 2500).

### WP13. Mobile foundations and real dashboard and profile

Added React Navigation (bottom tabs plus a stack), a pure API client with typed errors, runtime response validation, and a money module that formats amounts from digit strings (no floats, TND with 3 decimals, values above 2^53 exact). The dashboard shows the 6 real wallets, refreshes on focus and pull, never shows NOT\_PROVISIONED as zero, and keeps the last balances with a clear message when Formance is down. The profile screen reads `GET /api/profile`, which became the only status source, removing the POST-polling side effect. A permanent "Sandbox · test funds" badge appears outside production. Merged through PRs #52 and #53.

### WP14. Keycloak sign-up and session fixes

"Let's begin" now opens the Keycloak registration form (`prompt=create`) and "Sign in" the login form. Registration, email as username and the CUSTOMER default role were enabled in the local realm only and documented as request R1. The "You're verified" screens now appear only when approval happens during the session, and a stale stored token after a reinstall clears silently instead of showing "session expired".

### WP15. Send, Activity and Receive

Send: recipient by email, amount parsed from a string with a live formatted preview, an insufficient-balance guard from a fresh balance read, a review screen with "No fee" (checked in backend code), device authentication before every transfer, and a tested state machine for outcomes. The idempotency key is reused on every retry; uncertain outcomes are resolved by searching history for the deterministic reference; a pending transfer survives an app restart; "Stop tracking" is offered only after a check, with a double-payment warning. Activity shows real history per currency with cursor paging; Receive shows the user's email and ID. 112 mobile tests.

### WP16. Biometric unlock, recents privacy and login theme

Opt-in fingerprint or device-credential unlock: the app locks after 60 seconds in the background or on cold start, 3 failures or a system lockout force a password login, and enrolling a new fingerprint disables unlock (detected through an invalidated Keystore key). A local config plugin hides balances in the Android recent-apps view (Android 13+). A real Security screen replaced the placeholder. The Keycloak login page got a CSS-only NEOS theme (new files under `infra/keycloak/themes/`, adoption steps in R3). 144 mobile tests; every key scenario verified live on the emulator.

### WP17. Repository hygiene

A personal document found in `mobile/assets/brand/` was removed in its own commit. The branch's upstream was detached from `integration` twice to prevent accidental pushes to the shared branch.

### WP18. Delta audit 3 and integration test run (PR #55)

After the backend developer's PR #55 (KYC security, transfer safety, notification recovery), a read-only audit and a full test run were done on a sandbox branch combining `integration` and the npm-audit fix. Result: 54 passed, 2 failed, 9 not run, 25 blocked. Confirmed fixed: self-reactivation through `kyc/start`, and MOCK KYC as a prod default (MOCK KYC is now removed everywhere). The production startup validation passed 6/6: prod refuses to start without the Sumsub keys or webhook, or in sandbox mode. Failures: insufficient funds still returns 503 with the claim UNKNOWN (ISSUE-1, high) and `pageSize=0` returns 500 (ISSUE-2). Most blocked cases came from missing endpoints. Report: `docs/testing/INTEGRATION_TEST_REPORT_PR55.md`.

### WP19. npm audit cleanup

`npm audit fix` without `--force`: 45 → 38 advisories, the critical one removed, no Expo, React or React Native version change. A duplicate nested React Native added by the fix was removed, and `expo-auth-session` aligned to the SDK version. The remaining advisories are in build tooling only and need breaking upgrades.

### WP20. Sumsub SDK feasibility and native spike

The backend developer's `sdk-token` endpoint (PR #58) was read from code to confirm which states get a token. The official React Native module was chosen over the Web SDK and proven to build on the stack through a local config plugin. A patch stops the module writing tokens to logcat, and permissions are limited to CAMERA. The spike found two problems before release: token renewal hung while the SDK was open (paused JS timers block `fetch`), and ID documents were visible in the Android recent-apps view.

### WP21. Real Sumsub KYC flow (PR #65)

The simulated capture was replaced by the Sumsub SDK: `kyc/start` → `sdk-token` → SDK, then the status is read from `GET /api/profile` with polling; the SDK result is never treated as a decision. Token renewal moved to XMLHttpRequest and was verified while the SDK stayed open, including after the Keycloak token expired. Sumsub screens get FLAG\_SECURE (black in recents and screenshots). The app uses the backend's KYC error codes (PRs #61, #64), and treats unknown codes on a 5xx as "temporarily unavailable". Routing covers resubmission, reverification suspensions (PR #60), final rejection and restricted accounts; ISSUE-6 was fixed. A physical-device test guide was written for the backend developer. Capture was deliberately not run with the laptop webcam; the device test covers it.

### WP22. Email notifications end-to-end test

With Microsoft Graph enabled for one real recipient only, a synthetic credit produced one email, delivered to the inbox in under a minute; replaying the credit sent nothing more. Findings for the backend: no recipient allowlist outside production (emails had earlier gone to fake test addresses), a test-credit email identical to a real payment, a bare template (internal reference, no date, no thousands separator) and at-least-once delivery. Report: `docs/testing/EMAIL_NOTIFICATIONS_TEST.md`.

### WP23. PDF receipts

Receipts are generated on the device from Activity and from a Send success: escaped HTML with a restrictive CSP, the user's own email as account holder, a sandbox mark outside production, the file deleted after sharing, and no receipt for uncertain or rejected transfers. They are documented as not authoritative; a server-generated receipt is out of scope. 177 mobile tests. One unreproducible test failure was investigated (46 clean reruns) and documented.

### WP24. In-app notifications (Option A)

The bell showed a mock, and notifications existed only as email outbox rows. A new read-only endpoint, `GET /api/notifications`, returns the customer's own outbox rows: newest first, keyset cursor, only fields already exposed by history, and `testCredit` for synthetic credits. It is built from new files in the notifications module only, with no migration and no change to the sending logic, and has 26 new tests: security, per-user isolation, paging, the cursor, and an ArchUnit guard for the test-credit rule. The app now has a real feed, a bell badge, tap → transaction detail, and read state kept in a per-user file that is deleted on sign-out. Verified live: isolation, paging, test-credit wording and read state across restarts, with nothing emailed. Branches `feat/notifications-feed-api` and `feat/mobile-notifications` (not committed yet).

### WP26. FX conversion (sandbox demo)

The backend's FX demo (PRs #73, #74) was audited from the code (`docs/api/FX_CONTRACT_AUDIT.md`): synchronous conversions with no failed state, no error codes, and conversions missing from history. The mobile Convert flow is built on a pure engine (`mobile/src/fx/`): an amount, then an indicative quote with a countdown, device confirmation, and one POST with a key persisted beforehand. Uncertain outcomes are never resent automatically; the user has manual "Check status", "Try again" (same key and quote) and "Stop tracking". It is reachable only in sandbox builds. Three backend bugs were fixed on `fix/fx-conversion-bugs`, one commit each with tests (history, insufficient funds as a final rejection, lookup by idempotency key); two more are reported (R10–R12). Live on the emulator, against `integration` and then the fix branch, the flow worked end to end: €72.90 → €61.90 and $1.00 → $12.88 after two conversions, and with the fix both appear in Activity.

### WP27. Backoffice Package B customer directory

The merged Package A shell had no customer reads, and a direct identity-to-KYC call would create a Modulith cycle. A new backoffice module now composes bounded identity and bulk KYC read interfaces without a cross-module SQL join; its API returns only five customer fields and grants `CUSTOMER_READ` only to dedicated compliance staff. The connected page supports exact search, status filters and cursor pages, while ADMIN remains in the shell without directory access. Isolated PostgreSQL, Keycloak and Chromium checks passed, as did 26 frontend tests and the clean backend suite (640 tests, 60 skips, no failures); the work is uncommitted on `feat/backoffice-package-b` for review.

### WP28. Backoffice Package C customer financial reads

Package B was merged, and the mobile ledger services already owned wallet balances and transaction mapping; the payout history path still made one provider read per reservation. Package C adds staff-only customer-scoped wallet and transaction endpoints with signed customer-bound cursors, batched payout-hold reads, exact minor-unit string DTOs and connected detail sections. A disposable Formance ledger, PostgreSQL, Keycloak and Chromium run verified synthetic TND balances, a transfer, a settled payout, authorization and a provider outage; 31 frontend tests and the full backend suite passed with 61 explicit skips. The work is unstaged and uncommitted on `feat/backoffice-package-c` for manual review; automatic approval review blocked deletion of the local temporary credential files after disposable service cleanup.

### WP29. Backoffice usability pass

Package C is merged into `integration`; customer links previously reloaded the page and lost the directory position. In-app navigation now keeps exact search, filters and page history only in the authenticated shell, which unmounts on sign-out or staff identity change. Clearer loading labels, keyboard-focus outlines, scrollable tables and narrow-screen filters were added without changing API or permissions. The backoffice suite passed 33 tests, and production and demo builds passed; the live browser smoke was unavailable because the existing isolated services were stopped. Changes are unstaged and uncommitted on `feat/backoffice-usability` for manual review.

## 4. Architecture and API surface

The mobile app logs in through Keycloak and reads and writes all data through ten backend endpoints, four of which were contributed by the frontend developer.

&#91;embedded content: system architecture · state on 5 Nov 2026\]

The backend validates every token against Keycloak's keys; balances come only from the Formance ledger.

| Endpoint | Built by | Used in the app by | Notes |
| --- | --- | --- | --- |
| `GET /api/auth/me` | Backend developer | Sign-in, greeting fallback | 500 if the token has no email claim |
| `POST /api/onboarding/personal` | Backend developer | Onboarding | Always returns `NOT_STARTED` |
| `POST /api/kyc/start` | Backend developer | Starting KYC | Creates the Sumsub applicant, returns PENDING; stable error codes |
| `POST /api/kyc/sdk-token` | Backend developer | Opening and renewing the Sumsub SDK | Token + `expiresInSeconds`; no-store |
| `GET /api/profile` | Frontend developer (WP12) | Status after every step, KYC polling, Profile screen | Read-only, no side effects |
| `GET /api/wallets` | Frontend developer (WP12) | Dashboard, Send balance guard | String amounts, scale, NOT\_PROVISIONED |
| `POST /api/recipients/lookup` | Frontend developer (WP12) | Send recipient step | Email only, uniform 404, 10 per minute |
| `POST /api/transfers/internal` | Backend developer | Send | Idempotency key in body; insufficient funds still 503 |
| `GET /api/transactions` | Backend developer | Activity, Home, uncertain-transfer check, receipts | Per currency, cursor paging |
| `GET /api/notifications` | Frontend developer (WP24) | Notifications screen, bell badge | Read-only view of the email outbox, newest first, cursor paging |
| `POST /api/backoffice/synthetic-credits` | Backend developer | Test funding only (compliance role) | Off by default |

**Mobile modules added:** `api/` (contract types, client, XHR transport, response parsers), `money/` (parse and format), `transfers/` (outcome engine, reference, device auth), `history/` (paging), `kyc/` (error mapping, routing), `lock/` (app-lock state machine), `receipts/` (model, HTML, share), `notifications/` (read state, file store, wording), `session/`, `navigation/`, and screens for Profile, Send, Activity, Transaction detail, Receive, Unlock, Security and Notifications.

## 5. Decisions log

Every decision below was taken explicitly during a plan review, with the reason recorded at the time.

| # | Decision | Reason | WP |
| --- | --- | --- | --- |
| D1 | Mocks fail closed: active only in a dev build with an exact opt-in | A forgotten setting must never ship fake login or fake balances | WP5 |
| D2 | Release builds validate configuration at startup (HTTPS, required values, no placeholders) | A misconfigured build fails visibly instead of talking to the wrong server | WP5 |
| D3 | Mocks are per feature, registered, marked and checked in the release bundle | The backend arrives feature by feature; nothing fake may be forgotten | WP7 |
| D4 | The backend is the source of truth; the app never computes balances or totals across currencies | A total across currencies needs FX rates; client-side money arithmetic drifts | WP13 |
| D5 | Amounts handled as integer minor units in strings, with a per-currency scale (TND = 3) | Formance uses big integers; JavaScript numbers lose precision above 2^53 | WP11, WP13 |
| D6 | Not-provisioned wallets are shown as "Setting up", never as 0 | A zero would hide a provisioning failure | WP11, WP13 |
| D7 | `GET /api/profile` is the only status source; `POST /api/kyc/start` only starts KYC | Avoids polling through a POST with side effects | WP13 |
| D8 | Recipient lookup by email only, uniform 404, rate limited, gated by the transfer flag | Phone numbers are not verified in the backend; prevents account enumeration | WP12 |
| D9 | One idempotency key per transfer intent, reused on every retry; never a new key on retry | Prevents double debit; the backend deduplicates by key and Formance by reference | WP15 |
| D10 | Uncertain outcomes are resolved by searching history for the deterministic reference | The backend has no transfer-status endpoint | WP15 |
| D11 | A pending transfer is saved just before sending, not at review | An abandoned review must not show a "being processed" transfer | WP15 |
| D12 | "Stop tracking" only after a manual check, with a double-payment warning | Stopping and resending could pay twice | WP15 |
| D13 | "," accepted as decimal separator, with a live formatted preview | "1,000" in TND means one dinar, not one thousand | WP15 |
| D14 | Device authentication before every transfer, even right after unlocking | Unlocking the app is not consent to move money | WP15, WP16 |
| D15 | App lock after 60 s in the background; 3 failures or a lockout force a password login | Benchmark: PSD2 strong-authentication rules cap inactivity at 5 minutes | WP16 |
| D16 | Fingerprint-change detection through a Keystore marker; tokens not bound to the biometric key | Binding tokens would prompt every 5 minutes on token refresh | WP16 |
| D17 | Login stays in the system browser (Custom Tab) with `prompt=login`, branded by a Keycloak theme | OAuth for native apps standard (RFC 8252); the app never sees the password | WP14, WP16 |
| D18 | The backend developer fixes transfer and KYC bugs; the frontend developer builds only new, separate endpoints | He owns that logic; a wrong fix could release a key for a transfer that was posted | WP12 |
| D19 | All shared-file changes (realm, compose, error format) go through documented requests R0–R5 | Respect ownership of shared configuration | WP12–WP21 |
| D20 | React Navigation (native stack + bottom tabs), with the existing state gate kept for onboarding and KYC | Proven navigation with parameters and history; no rewrite of working screens | WP13 |
| D21 | Integration tests run on a sandbox branch; results published through one PR per topic | Test combinations without touching `integration` | WP18 |
| D22 | Sumsub official React Native module, Web SDK kept as fallback | Native camera and liveness; uses the backend token directly | WP20 |
| D23 | Patch the SDK so tokens never reach logcat, including debug builds | The module logged renewed tokens in plain text | WP20 |
| D24 | CAMERA is the only sensitive permission; location, NFC, microphone and settings blocked | The `id-and-liveness` level needs only the camera | WP20 |
| D25 | The SDK result is never a decision; the status comes from `GET /api/profile` polling | Only the backend's verified webhook decides | WP21 |
| D26 | Token renewal over XMLHttpRequest | `fetch` hangs while the SDK is in front | WP21 |
| D27 | FLAG\_SECURE on every Sumsub screen | ID documents must not appear in recents or screenshots | WP21 |
| D28 | Unknown error code on a 5xx → "temporarily unavailable"; on a 4xx → generic message | New backend codes must not break the app | WP21 |
| D29 | Neutral wording for a suspended account asked to resubmit documents | The suspension reason is not exposed to the app | WP21 |
| D30 | Email tests with Graph enabled for one real recipient only, backlog neutralized first | Real emails leave the system; test addresses must never receive them | WP22 |
| D31 | Receipts generated on device, own email only, none for unconfirmed transfers | Data already in the app; unverified names and uncertain outcomes excluded | WP23 |
| D32 | In-app notifications, Option A: `GET /api/notifications` reads the existing outbox rows; read state stays on the device (per user, survives restarts, cleared on sign-out) | No migration and no change to the backend developer's tables or sending logic; agreed with him | WP24 |
| D33 | Read state in a per-user JSON file (`expo-file-system`), 500 read IDs max; existing notifications start as read | SecureStore values above ~2 KB are unreliable and this data is not secret; no badge burst on first use | WP24 |
| D34 | `testCredit` derived from `INCOMING_PAYMENT` for now, guarded by an ArchUnit test; unknown or corrupt rows skipped with a WARN log of the row ID only | The synthetic credit is the only producer today; a payload marker needs the backend developer (R6) | WP24 |
| D35 | The navy balance card stays and becomes the wallet switcher: swipe between the 6 wallets (page dots), or tap the currency code for a picker sheet; the full wallet list leaves Home | The card is the brand's signature element; the list took most of the screen. "Setting up" still never shows as 0 (D6) | WP25 |
| D36 | Home quick actions are Add money, Send, Convert and Details; the History action is removed | The Activity tab and Recent activity (which follows the selected wallet) cover history; "Details" restores the "receive" function of GitHub criterion D1 (#22) | WP25 |
| D37 | Receiving details (email, user ID shortened to first 8 … last 6, Copy, Share) live in one "Account details" sheet, opened from Home "Details" and from a Profile row; the Receive route is removed; a marked block is reserved for bank details once top-up exists | One source for the details instead of a screen plus a Profile copy; no fake bank data | WP25 |
| D38 | Activity grouped by local day ("Today", "Yesterday", then the full date) with sticky headers; server order kept, a day split across pages is merged. No balance computed (D4): the header has a hidden slot for an end-of-day balance once the API sends `balanceAfterMinor` per item (R8) | A running balance in the app would be client-side money arithmetic | WP25 |
| D40 | Convert (FX demo) is reachable only in a sandbox build with a real session; a production build keeps the placeholder and a 404 means "not available, nothing converted" | The demo endpoint simulates the bank and must never be offered as a real service | WP26 |
| D41 | One idempotency key per confirmed quote, persisted with the quote before the POST; 409, 503, timeouts and malformed 200s are uncertain; no automatic resend or status check, only manual "Check status", "Try again" (same key and quote) and "Stop tracking" after a manual action | CLAUDE.md rule for unknown money outcomes; 409 and 503 are ambiguous on this endpoint | WP26 |
| D42 | The quote countdown runs from the moment the request was sent (server lifetime `expiresAt − asOf`, minus 1.5 s), and the engine refuses to send an expired quote | Never trust the device clock against the server's; the user always confirms the price shown | WP26 |
| D43 | Backend FX fixes on one separate branch from `origin/integration`, one commit per bug with tests; BUG-FX-4/5 and the demo script only reported | Backend ownership; small, reviewable changes | WP26 |
| D44 | Add money is a sandbox preview: example deposit instructions (fake sponsor bank and holder, an account number that cannot pass an IBAN check, the user's real reference `NEOS-` + first 8 characters of the user ID), a permanent "do not send real money" banner, no service call, credit, balance change or transaction; same gate as Convert (D40), placeholder in production; listed as sandbox-only in the registry, not as a mock | Shows the future flow to the bank without fake money; a mock would be stripped from sandbox release builds, and a realistic IBAN could receive real money | WP27 |
| D45 | Haptics (`expo-haptics`): a light tick when the Home wallet changes and a success haptic when a confirmed transfer or conversion succeeds (not on a resumed one); nothing on errors; Android through `performHapticFeedback`, which follows the system touch-feedback setting | Feedback where the user acted; vibration on errors adds stress; no extra setting to maintain | WP25 |
| D39 | Restrained finish: large light balance with smaller minor units (tabular figures), gold only as the card hairline and the active-tab indicator, skeletons instead of spinners (Home, Activity, Notifications), short motion (amount change, sheet slide) disabled by reduce-motion; initials avatars only when a counterparty name exists (history has none yet) | Premium feel without gradients, illustrations or extra colour; gold stays a brand mark | WP25 |

## 6. Rejected and deferred ideas

These options were considered and set aside; each reason was stated before the choice was made.

| Idea | Status | Why |
| --- | --- | --- |
| Embed the Keycloak login page in a WebView for an "in-app" feel | Rejected | Forbidden by RFC 8252: the app could read the password and inject code; trains phishing; a bank's security review would flag it. Replaced by a branded Custom Tab, a NEOS login theme and fingerprint unlock. |
| Bind the tokens cryptographically to the fingerprint (`requireAuthentication`) | Rejected | Would prompt on every token refresh (about every 5 minutes) and accepts strong biometrics only |
| Resend an uncertain FX conversion automatically (same key, with backoff) | Rejected | The frontend developer's rule: unknown money outcomes are only retried by the user (D41) |
| Request a 30-day `offline_access` token for longer unlock | Deferred | A 30-day token on the phone is a security-policy decision; shorter session idle (5–15 min) proposed instead (R4) |
| The frontend developer fixes transfer bug #3 (rejection recorded as UNKNOWN) | Rejected | No code overlap with the frontend work; some Formance 4xx mean "already posted", so a wrong fix could allow a double debit; the owner fixes it |
| Add a global `@ControllerAdvice` / Problem Details format | Deferred | Would change every endpoint of the backend developer; KYC got its own codes (PRs #61, #64) |
| Recipient lookup by phone number | Deferred | Phone numbers are optional and never verified in the backend; risk of paying the wrong person |
| Return an always-null `displayName` in lookup | Rejected | The backend stores no names; a permanently null field is confusing; add later as a non-breaking change |
| Show only shells (preview screens) for the bank presentation | Rejected | The goal is a working app; real features were wired wherever an endpoint existed |
| Demo from a development build with mocks | Rejected | Would present developer tooling to the bank; a permanent sandbox badge on a real build was chosen instead |
| Compute a total balance across currencies in the app | Rejected | Needs FX rates (planned for V3); never computed client-side |
| Extend the custom router instead of React Navigation | Rejected | Would be maintained in-house and grow complex with transfer flows |
| Wire Send while the transfer error contract is broken, without safeguards | Rejected | Added an insufficient-balance guard and history-based resolution so the app is correct before and after the backend fix |
| Run `npm audit fix --force` | Rejected | Would downgrade Expo from 55 to 44 and break the app |
| Patch `expo-web-browser` for the toolbar colour and share button | Deferred | A new dependency to maintain for a cosmetic library bug; documented instead |
| Real Sumsub pipeline (SDK token, capture, webhook) | Done in WP20–WP21 | Built once the backend added the token endpoint (PR #58); webhook stays with the backend developer |
| Sumsub Web SDK | Fallback only | The native module built fine and has better camera and liveness support |
| Strip the SDK's token logs with an R8 rule | Rejected | Would require enabling minification for the whole app; a source patch is narrower |
| Forge Sumsub webhooks locally to approve test users | Rejected | Amounts to faking a provider decision; approvals come from the Sumsub sandbox |
| Run the SDK capture with the laptop webcam | Rejected | Would upload a personal image to Sumsub without proving anything; covered by the device test |
| First name on PDF receipts | Rejected | The token's `given_name` is self-declared; receipts show the email only |
| Push notifications | Out of scope this phase | Needs a Firebase project and backend sending; email notifications cover this phase |
| CI checks on pull requests | Deferred | Not prioritised in this phase |
| Build Android for two architectures in development | Rejected | Doubled build time and triggered native compile failures; x86\_64 only for the emulator |
| Merge directly into `integration` | Rejected | Every change goes through a PR so the backend developer can review it |
| In-app notifications, Option B: dedicated table, `POST /api/notifications/{id}/read`, unread counts across devices, independent of email | Deferred | Needs a Flyway migration in the backend developer's schema and his agreement (R7) |
| Notification read state in SecureStore | Rejected | Values above ~2 KB are unreliable and the data is not secret; a per-user file is used |

## 7. Test evidence

The mobile suite grew to 195 passing tests and every money path was exercised live against the real backend; the integration run after PR #55 passed 54 cases with 2 known backend failures.

&#91;embedded content: npm test results reported after each work package\]

Each feature arrived with its tests: the largest jumps came with the transfer engine (WP15), the app lock (WP16) and the Sumsub flow (WP21).

### 7.1 Checks run after every work package

| Check | What it proves | Last result |
| --- | --- | --- |
| `npx tsc --noEmit` | Strict TypeScript compiles | 0 errors |
| `npm run lint` | Lint rules, incl. mock-import ban and raw-text guard | 0 problems |
| `npm test` | Unit and logic tests | 218 / 218 pass (WP26) |
| `npm run check:release-bundle` | No mock or dev-only code in a production bundle | 0 markers (the control run detects them) |
| `npm run check:release-readiness` | Features still without a real implementation | none (Add money remains a placeholder; Convert is sandbox-only) |
| `./mvnw test` (backend) | Backend unit, integration, Modulith and ArchUnit tests | 407 / 0 failures after WP12; 536 / 0 reported by the backend developer after PR #64; WP24: 561 run, 60 skipped, 1 failure (`SyntheticCreditDefaultDisabledIntegrationTest`: the local `.env` enables synthetic credit; environment, not code) |
| Package B isolated checks | Backoffice contract, module boundaries, authorization and frontend isolation | Clean backend suite: 640 total, 580 executed, 60 skipped, 0 failures/errors; backoffice: 26 / 26 tests, production and demo builds pass; production dependency audit: 0 findings |
| Package C isolated checks | Staff financial reads, live ledger mapping, browser isolation and provider outage | Clean backend suite: 645 total, 584 executed, 61 skipped, 0 failures/errors; backoffice: 31 / 31 tests, production and demo builds pass; production dependency audit: 0 findings |
| Backoffice usability pass | Customer navigation and session state | Backoffice: 33 / 33 tests; production and demo builds pass; live browser smoke not run (services stopped) |
| Production startup validation | Prod refuses an unsafe KYC configuration | 6 / 6 (WP18) |
| Gradle `assembleDebug` | Native Android build | Success (x86\_64, one worker) |

### 7.2 What the unit tests cover

- **Configuration:** fail-closed mocks, release validation (HTTPS, placeholders, required values), lock timeout bounds.
- **Money:** all six scales, TND 3 decimals, values above 2^53, invalid input rejected, "," never read as grouping, en/fr formatting checked against `Intl`.
- **API client:** query encoding, one refresh and retry on 401 then expiry, timeout, typed errors, no retry of POSTs; XHR transport with the same rules.
- **Statuses:** every KYC × account combination, `RESUBMISSION_REQUIRED`, SUSPENDED + APPROVED routed to restricted, verified screens once per session.
- **KYC flow:** routing for every KYC, account and suspension case; error codes with and without a code; unknown 4xx and 5xx rules; polling cadence.
- **Wallets:** READY / PROVISIONING / NOT\_ELIGIBLE, never zero for not-provisioned, no total computed.
- **Transfers:** every HTTP outcome, same key on retry, new key for a new intent, persistence before POST, resume after restart, uncertain → found / not found, stop-tracking rules.
- **History:** empty pages while `hasMore`, bounded paging, reference search, `CONVERSION` items accepted.
- **FX:** strict quote and conversion parsing (string amounts, TND scale 3), quote deadline from the send moment, every HTTP outcome classified, key persisted before the POST, no automatic resend or check, same key on retry, lookup results (final, in progress, 404, missing endpoint), stop-tracking rule.
- **App lock:** cold start, timeout, own prompts ignored, cancel, 3 failures, lockout, biometrics changed or removed, sign-out, other user, unlock never authorizes a transfer.
- **Receipts:** HTML escaping, per-currency formatting, REVERSED marking, sandbox mark, no receipt for uncertain or rejected transfers.
- **Notifications:** read/unread rules (first use, mark one, mark all, 500 cap, exact microsecond ordering), per-user store, restart and sign-out, storage failures, feed parsing, test-credit wording, bounded transaction search.

### 7.3 Live end-to-end tests (emulator + real Keycloak, PostgreSQL, Formance)

| Scenario | Result | WP |
| --- | --- | --- |
| First real login → onboarding → KYC (mock provider) → 6 Formance wallets created | Pass | WP9 |
| KYC approved while Formance is stopped | Confirmed bug: 200 APPROVED, wallets never created (since fixed by the backend, PR #59) | WP9 |
| Credit → balance → lookup by email → transfer → both balances | Pass: 7500 / 2500 minor units | WP12 |
| Credit appears after pull-to-refresh and on return to Home | Pass | WP13 |
| Formance gateway stopped while on Home | Last balances kept, "temporarily unavailable", recovered on retry | WP13 |
| Self-registration via Keycloak → onboarding → KYC → Home with 6 wallets | Pass | WP14 |
| Returning approved user; stale token after reinstall; session expiry in use | Straight to Home; silent welcome; "session expired" only in use | WP14 |
| Lookup: unknown email, mixed-case email, own email | Uniform "not found"; case-insensitive match; same 404 for self | WP12, WP15 |
| Amount guards: EUR "1,000" and TND "1,000" | EUR rejected (2 decimals max); TND shown "= TND 1.000", then insufficient balance | WP15 |
| Transfer with PIN cancelled | "Nothing was sent"; balance unchanged, checked via API | WP15 |
| Transfer €10 confirmed | Sent (tx 90); sender and receiver Home and Activity updated | WP15 |
| Formance stopped during a transfer | "Being processed"; check after restart shows not posted; retry with same key gets 409; pending transfer resumed after app kill | WP15 |
| Fingerprint unlock: right finger / wrong finger / cancel | Unlocks / rejected / stays locked with no balance visible | WP16 |
| Transfer after unlock: wrong finger, then right finger | Nothing sent (10000 / 0), then sent (tx 93, 9000 / 1000) | WP16 |
| Background 65 s vs 10 s | Locked vs not locked | WP16 |
| New fingerprint enrolled after enabling unlock | Unlock disabled, password login required | WP16 |
| System lockout after repeated wrong fingers | Password login required, session removed | WP16 |
| Recent-apps view | NEOS card blank (Android 13+) | WP16 |
| NEOS Keycloak theme on login and registration | Rendered; one header layout issue fixed during the run | WP16 |
| Integration run after PR #55 (auth, onboarding, KYC to PENDING, wallets, Send, Activity, restricted accounts) | 54 passed, 2 failed (ISSUE-1 insufficient funds, ISSUE-2 page size) | WP18 |
| Sumsub SDK with a real token | Opens; camera starts; status stays PENDING without a decision | WP20, WP21 |
| Token renewal while the SDK stays open (TTL 120 s) | 2 / 2 succeeded, incl. after the Keycloak token expired | WP21 |
| Recents and screenshots while the SDK is open | Black (FLAG\_SECURE) | WP21 |
| Logcat during SDK use | 0 tokens, 0 `errorMsg` | WP20, WP21 |
| Synthetic credit with email enabled | Delivered to the inbox in under a minute; no duplicate on replay | WP22 |
| PDF receipt from Activity and from Send | Correct content; file deleted after sharing; nothing logged | WP23 |
| Notifications API: synthetic credit (tx 119) + transfer (tx 120) between two approved users, Graph and dispatcher off | Each side sees only its own rows; newest first; `pageSize=1` walk complete and unique; a cursor from the other user leaks nothing; 400 on bad input; new rows stay PENDING (nothing emailed) | WP24 |
| FX demo on `integration` (demo profile, `neos-sender`) | Quote €10 → $10.80 at 1.08 with countdown; expiry → "Get a new rate" → new quote → converted (op `64694990…`); EUR €72.90 → €62.90, USD $1.00 → $11.80; no entry in Activity (BUG-FX-1) | WP26 |
| FX failure paths on `integration` | Unconfigured pair (USD → GBP) → "not available for this pair"; amount above balance → blocked in the app; Formance stopped during a conversion → "being processed", Check status → 405 (no lookup), Try again → 409, Home banner, Stop tracking; balance unchanged (nothing posted); demo profile off → 404 → "not available in this environment, nothing converted" | WP26 |
| FX on `fix/fx-conversion-bugs` (demo profile) | Conversion €1 → $1.08; both conversions listed as "Currency conversion" in EUR (debit) and USD (credit); Formance stopped → Check status finds the operation by key ("still being processed") | WP26 |
| Notifications on the emulator (receiver) | No badge on first use; new test credit (tx 121) → badge 1, kept after app kill; tap → detail with the test-funds badge; badge cleared and kept cleared after restart; sign-out deletes the read-state file | WP24 |

| Backoffice Package B in disposable Keycloak/PostgreSQL and headless Chromium | Compliance list/detail and missing KYC; ADMIN shell but direct directory 403; CUSTOMER, mixed, combined and OPERATIONS denied; six invented accounts | WP27 |
| Backoffice Package C in disposable PostgreSQL/Keycloak/Formance and headless Chromium | Synthetic TND wallets, incoming payments, transfer and settled payout; seven-item cursor walk without gaps or duplicates; compliance 200, five other role cases 403; provider outage 503 with Retry | WP28 |

### 7.4 Not verified live (covered by unit tests or pending)

Real Sumsub capture (ID, selfie, liveness) and decisions through the webhook, pending the backend developer's physical-device test; uncertain transfer resolving to success; definitive transfer rejections until the backend fix; the 3-failed-attempts counter (Android escalates to a system lockout first); screen-lock-only unlock; REVERSED and TND receipts on a device; iOS and Face ID; Android 12 and lower; a physical device.

WP29 live browser navigation, preserved list state, denied access and logout clearing remain unverified because the existing isolated backend, Keycloak and ledger services were not running; focused frontend tests cover state preservation and clearing.

## 8. Security posture

The app protects the session, every money movement and the build itself; the main remaining gaps are server-side and owned by the backend developer.

| Layer | Control in place | Evidence |
| --- | --- | --- |
| Authentication | Keycloak OIDC with PKCE in the system browser; the app never sees the password; tokens in secure storage; refresh + revoke on sign-out | WP2, WP14, D17 |
| Authorization (backend) | JWT validation and role checks (`CUSTOMER`, `COMPLIANCE_OFFICER`) on every endpoint; lookup requires an eligible caller | WP9 smoke tests (401 / 403), WP12 |
| Session | 60 s app lock with fingerprint or device credential; 3 failures or a lockout → password; new fingerprint → unlock disabled | WP16 live tests |
| Money movement | Device authentication before every transfer; idempotency key per intent; deterministic ledger reference; no client-side balance arithmetic | WP15, WP16, D9, D14 |
| Recipient safety | Email lookup with masked confirmation, uniform 404, rate limit; insufficient-balance guard | WP12, WP15 |
| Identity verification | Sumsub token issued by the backend only and held in memory; SDK token logging patched out; CAMERA only; SDK screens hidden with FLAG\_SECURE; KYC prod config validated at startup | WP18, WP20, WP21 |
| Privacy | Balances hidden in the recent-apps view; no balances behind the lock screen; personal data never logged | WP16 |
| Receipts | Escaped HTML with a restrictive CSP; generated locally; file deleted after sharing; none for unconfirmed transfers | WP23 |
| Build integrity | Mocks impossible in release; release config validated; release bundle scanned for mock and dev-only markers; no secrets in the bundle | WP5, WP7, WP20 |
| Secrets handling | Shared `.env` never committed or pasted; test accounts local only | Section 2 |

**Known weaknesses** (documented, not hidden):

- The app lock is enforced in app logic, not cryptographically; a rooted device or debug build could bypass it.
- Recipient confirmation shows only a masked email; a verified name (Confirmation of Payee) needs names stored in the backend.
- Insufficient funds on a transfer still returns 503 with the claim UNKNOWN (backend, ISSUE-1).
- No recipient allowlist for emails outside production, and test credits send a normal "Money received" email (backend, EMAIL-1, EMAIL-2).
- Keycloak session idle is 30 minutes; 5–15 minutes proposed (R4).
- Fixed since the first version of this log: self-reactivation through `kyc/start` and MOCK KYC by default (PR #55); wallet provisioning without retry (PR #59, recovery scheduler off by default).

## 9. Limitations, open items and requests

The customer flows work end to end; what remains is the end-to-end KYC validation on a physical device, the bank-side simulation, the backoffice, the missing customer endpoints and a few backend fixes.

### 9.1 Requests to the backend developer

| Ref | Request | Status |
| --- | --- | --- |
| Bug | Stop `POST /api/kyc/start` reactivating SUSPENDED or CLOSED accounts | Done (PR #55) |
| Bug | No silent MOCK KYC in prod | Done (PR #55, MOCK removed) |
| Bug | Re-drive failed wallet provisioning | Done (PR #59, scheduler off by default) |
| Bug | Record clear Formance rejections as rejected (e.g. 422 INSUFFICIENT\_FUNDS) | Open (ISSUE-1, high) |
| R0 | Machine-readable `errorCode` on error bodies | KYC done (PRs #61, #64); transfers, wallets and lookup open |
| R1 | Official realm: registration, email as username, CUSTOMER default role, `neos://auth` redirect | Done (PR #67) |
| R2 | Keycloak end-session logout on sign-out | Open |
| R3 | Mount the NEOS login theme and set `loginTheme` | Open |
| R4 | Reduce session idle from 30 to 5–15 minutes | Open |
| R5 | Expose the suspension reason (or `canResubmitKyc`) in `GET /api/profile` | Open |
| Email | Recipient allowlist outside prod; test-funds wording; clearer template | Open (EMAIL-1 to EMAIL-3) |
| R6 | Explicit synthetic marker in the notification payload, before any real top-up exists | Open |
| R7 | Option B: dedicated in-app notifications with server-side read state | Open (deferred) |
| Test | `NotificationDeliveryWorkerIntegrationTest` runs `worker.runOnce(100)` on the shared dev database with a fake email port, so it marks any queued real row `PROVIDER_ACCEPTED` without sending it (45 rows on 5 Nov, incl. the 40 `@neos.local` backlog rows) | Open (finding) |
| API | Recipient name in lookup (needs names stored in `users`) | Open |
| Infra | Formance compose in the repo, port defaults, complete `.env.example` | Open |
| Product | D1 (#22) quick actions "send / receive / convert / add / history": receive is the "Details" action (D36, D37), history is the Activity tab. Deviation resolved | Done |
| R8 | `balanceAfterMinor` per item in `GET /api/transactions` (from Formance `postCommitVolumes`), for the end-of-day balance in Activity day headers (D38) | Open |
| R9 | Review `fix/fx-conversion-bugs`: BUG-FX-1 (conversions in history), BUG-FX-2 (insufficient funds = final rejection, V17), BUG-FX-3 (lookup by idempotency key) | Open (PR drafted) |
| R10 | FX error codes and pair discovery (BUG-FX-4) | Open |
| R11 | FX hold release and reconciliation of unresolved operations (BUG-FX-5) | Open |
| R12 | `neos-fx-demo.ps1`: port 8082 default, `POST /api/kyc/start` used to print the status | Open |

### 9.2 Not built yet

- End-to-end Sumsub KYC on a physical device with the webhook (backend developer; guide ready).
- Bank simulation: top-up with a unique reference, SWIFT/SEPA payout, FX, safeguarding and reconciliation report.
- Backoffice KYC review decisions, blocking, reconciliation and staff management; KYC awaits backend endpoints. Read-only customer wallets and transaction history are implemented.
- Customer endpoints: Receive (deposit instructions), Hold, Convert, payouts, transfer status. In-app notifications exist as Option A; Option B (server-side read state) is deferred (R7).
- Beneficiaries, devices list, business accounts (KYB), support, French and Arabic with right-to-left layout.
- Push notifications: out of scope for this phase.

### 9.3 Known limitations

- In-memory lookup rate limit and unindexed case-insensitive email match: fine for one instance, to fix before scaling.
- Amounts are strings in `/api/wallets` but numbers in history and transfers.
- Custom Tab toolbar colour and share button not applied (library bug).
- Notifications `testCredit` is derived from `INCOMING_PAYMENT`: correct only while the synthetic credit is the sole producer. As soon as real incoming payments (bank top-up) exist, this rule becomes wrong and R6 is required.
- Option A notifications: no row without a stored email, read state not shared across devices, reversals visible only in the detail screen.
- The development PC cannot run Docker, the backend, the emulator and a Gradle build at the same time; a physical phone is preferred for real use.

## 10. Appendix: branches, PRs, commits and documents

Commit hashes and PR numbers below come from the reports and terminal output in this work; a fact-check against the repository is still to be done.

### 10.1 Branches and pull requests

| Branch | Content | PR / state |
| --- | --- | --- |
| `feat/frontend` | First AI-generated frontend, SEC-01, mocks, render fix, audits | Merged into `integration` (via `feat/frontend-integration`) |
| `feat/frontend-integration` | Frontend + backend C1–C3 merge | Included in `integration` |
| `feat/mobile-on-integration` | Audits, API contract, acceptance criteria, balance proposal | PR #50 (docs only), merged |
| `feat/customer-read-apis` | Backend: wallets, profile, recipient lookup | PR #51, merged |
| `feat/mobile-real-integration` | Mobile foundations, dashboard, profile, sign-up, Send / Activity / Receive | PRs #52 and #53, merged |
| `feat/mobile-biometric-unlock` | Biometric unlock, recents privacy, Security screen, login theme | Merged |
| `chore/npm-audit` | npm audit fix without `--force` | Merged |
| `docs/integration-test-pr55` | Delta audit 3, integration test plan and report | Merged |
| `feat/mobile-sumsub-sdk` | Real Sumsub KYC flow, error-code mapping, device test guide | PR #65, merged |
| `feat/mobile-pdf-receipts` | PDF receipts + email notifications test report | PR into `integration` |
| `feat/notifications-feed-api` | Backend: `GET /api/notifications` (Option A) | Not committed yet; PR for the backend developer's review |
| `feat/mobile-notifications` | Notifications screen, bell, device read state (from `feat/notifications-feed-api`) | Not committed yet |
| `feat/mobile-design-refresh` | Design refresh WIP (Home card switcher, Details sheet, Activity by day, Profile) | WIP commit `87907f1` |
| `fix/fx-conversion-bugs` | Backend: BUG-FX-1/2/3 with tests (`4586f52`, `8304c8c`, `dc4d2d8`) | Local; PR for the backend developer's review |
| `feat/mobile-fx-convert` | Mobile Convert flow (from `integration` + `feat/mobile-design-refresh`) | Not committed yet |

| `feat/backoffice-package-b` | Read-only connected customer directory (WP27) | Merged into `integration` before WP28 |
| `feat/backoffice-package-c` | Customer-scoped read-only wallets and transaction history (WP28) | Merged into `integration` through PR #80 |
| `feat/backoffice-usability` | Focused customer navigation and accessibility pass (WP29) | Unstaged and uncommitted; manual review pending |

Backend developer PRs integrated during this period: #49, #55, #58, #59, #60, #61, #64 and #67.

### 10.2 Key commits

| Commit | Description |
| --- | --- |
| `7e4e2a5` | Initial frontend |
| `0865799` | SEC-01: fail-closed demo mode, release config validation, EAS profiles |
| `691585d` | Lockfile synced with installed dependencies |
| `b2338e4` | Frontend quality and integration audits |
| `eb45caa` | Mocks centralized behind per-feature switch, registry and guardrails |
| `d86d15f` | Home render crash fix + lint guard |
| `051cfec` | Merge of backend C1–C3 into the frontend integration branch |
| `2e05d7a` | `integration` after PR #49 (audit 2 baseline) |
| `635e27d` | `integration` base of the biometric work |
| `185db52` | `integration` with wallet provisioning recovery (PR #59); base of the Sumsub work |
| `93b1ab9` | `integration` with Sumsub resilience (PR #64) |

### 10.3 Documents in the repository

| Document | Purpose |
| --- | --- |
| `docs/audits/INTEGRATION_AUDIT.md` | First frontend ↔ backend audit (WP2) |
| `docs/audits/FRONTEND_AUDIT.md` | 12-criteria quality audit (WP4) |
| `docs/audits/BACKEND_DELTA_AUDIT.md` | C1–C3 delta audit (WP9) |
| `docs/audits/BACKEND_DELTA_AUDIT_2.md` | PR #49 delta audit (WP10) |
| `docs/audits/BACKEND_DELTA_AUDIT_3.md` | PR #55 delta audit (WP18) |
| `docs/api/MOBILE_API_CONTRACT.md` | Customer API contract, incl. the endpoints added in WP12 and WP24 and the KYC token |
| `docs/api/BACKOFFICE_CUSTOMER_DIRECTORY.md` | Package B request/response and cursor contract (WP27) |
| `docs/testing/PACK_B_ISOLATED_VERIFICATION.md` | Sanitized Package B checks and browser evidence (WP27) |
| `docs/api/BACKOFFICE_CUSTOMER_FINANCIAL_READS.md` | Package C financial read contract (WP28) |
| `docs/testing/PACK_C_ISOLATED_VERIFICATION.md` | Sanitized Package C checks and browser evidence (WP28) |
| `docs/api/PROPOSED_BALANCE_API.md` | Balance API proposal (WP11) |
| `docs/api/INTEGRATION_STATUS.md` | Feature → endpoint status, error mappings and requests R0–R12 |
| `docs/api/FX_CONTRACT_AUDIT.md` | FX contract audit, findings BUG-FX-1…5 and the mobile plan (WP26) |
| `docs/api/SUMSUB_MOBILE_INTEGRATION_PLAN.md` | Sumsub plan and phase results (WP20–WP21) |
| `docs/product/MOBILE_ACCEPTANCE_CRITERIA.md` | GitHub issues #17, #22–#29, verbatim |
| `docs/product/MOBILE_GAP_ANALYSIS.md` | Criterion-by-criterion gap analysis |
| `docs/testing/INTEGRATION_TEST_PLAN.md` | Full integration test plan (WP18) |
| `docs/testing/INTEGRATION_TEST_REPORT_PR55.md` | Integration test results (WP18) |
| `docs/testing/ANDROID_DEVICE_TEST_GUIDE.md` | Physical-device test guide (WP21) |
| `docs/testing/EMAIL_NOTIFICATIONS_TEST.md` | Email notifications test (WP22) |
| `mobile/VALIDATION.md` | Live test runs, results and known limitations |

### 10.4 Test accounts policy

All test accounts (sender, receiver, compliance, fresh sign-ups and biometric test users) exist only in the local Keycloak container, with shared local passwords. None are in the repository, and none may be reused on staging or for a client presentation.
