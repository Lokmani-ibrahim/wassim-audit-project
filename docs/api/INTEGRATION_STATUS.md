# Mobile ↔ Backend Integration Status

- **Mobile branch:** `feat/mobile-fx-convert` (design refresh + FX Convert + Add money preview, from `integration` after PR #75).
- **Backend source of truth:** `docs/api/MOBILE_API_CONTRACT.md`. The § numbers below refer to it.
- **Last updated:** 2026-11-06.

## Status legend

| Status | Meaning |
|---|---|
| **real** | The app calls the backend endpoint in every build. No mock is involved unless one is explicitly enabled in a dev build. |
| **mock** | Dev builds only (`EXPO_PUBLIC_MOCK_FEATURES`). Mocks are stripped from release bundles (`npm run check:release-bundle`). With no mock, the app shows "not available yet". |
| **placeholder** | An honest "Not available yet" screen. It makes no service call and shows no preview data. |
| **proposed** | The endpoint is designed but not implemented. |
| **blocked** | Needs a backend endpoint, a provider or a decision first. |

## Features

| Feature (app) | Endpoint | Status | Notes |
|---|---|---|---|
| Sign-up / sign-in / sign-out | Keycloak auth code + PKCE; `GET /api/auth/me` (§2) | **real** | Keycloak opens in the system browser (Custom Tab on Android, never a WebView) with the page title shown ("Sign in to NEOS"). A NEOS toolbar colour and no share button are requested too, but `expo-web-browser` 55.0.20 does not apply them on Android: `toolbarColor` is set as the secondary toolbar colour, and the share state is never set OFF. They need a library fix. The NEOS-branded pages come from the login theme (R3). "Let's begin" → Keycloak **registration** form (`prompt=create`; needs realm registration, see R1). "Sign in" → **login** form (`prompt=login`). Closing the Keycloak page without signing in returns silently to welcome (no error). Sign-out is local + refresh-token revocation; end-session logout is a follow-up (R2). A token from a previous install that is rejected on cold start is cleared silently (no "session expired"). |
| Greeting name (Home, Profile) | ID token `given_name` (decoded locally) | **real** | Display only: the app does not verify the ID token and never uses it for a decision. Falls back to the email local part, never the full email. |
| Personal onboarding | `POST /api/onboarding/personal` (§3), then `GET /api/profile` | **real** | The real status is re-read after submit, because the response's `kycStatus` is always `NOT_STARTED`. |
| Start KYC | `POST /api/kyc/start` (§4) | **real** | Called **only** to start verification (idempotent once started), then status is re-read with `GET /api/profile`. KYC `MOCK` mode no longer exists on the backend: a new customer stays `PENDING` until a signed Sumsub webhook arrives. Errors use the PR #61 `errorCode`s (see "KYC error mapping" below). |
| KYC / account status (gate, refresh, foreground re-check, polling) | `GET /api/profile` (§11) | **real** | Read-only. `RESUBMISSION_REQUIRED` → "Update your documents", `IN_REVIEW` = `PENDING`, `REJECTED` is final (no retry). `CLOSED`, and `SUSPENDED` with any KYC status except `RESUBMISSION_REQUIRED`, → restricted. `SUSPENDED` + `RESUBMISSION_REQUIRED` → "Update your documents" (neutral wording, the suspension reason is not exposed), switching to restricted when `sdk-token` answers `ACCOUNT_RESTRICTED`. Polled with backoff (5 s → 60 s, at most 15 min) on the waiting screens. The foreground re-check now also refreshes the status screens, including the account-unavailable screen (ISSUE-6 fixed). |
| Own user ID (Profile, copy) | `GET /api/profile` (§11) `userId` | **real** | Kept in the session (`Profile.customer.userId`). |
| Profile screen | `GET /api/profile` (§11) | **real** | Shows the masked email, phone if present, account status, KYC status, member since (`onboardedAt`) and the user ID with a copy button. Re-read on every focus and on pull-to-refresh. |
| Dashboard balances (6 wallets) | `GET /api/wallets` (§10) | **real** | String minor units, formatted exactly with the API `scale`. `NOT_PROVISIONED` → "Setting up…" (never 0). Banners for `PROVISIONING` and `NOT_ELIGIBLE`. 503 → "Balances temporarily unavailable". There is no total across currencies (that needs FX). Data is refetched on focus, pull-to-refresh and foreground. Mock available in dev (`wallets`). |
| Sandbox labelling | config `EXPO_PUBLIC_APP_ENV` | **real** | A permanent "Sandbox · test funds" badge appears unless `EXPO_PUBLIC_APP_ENV=production`. |
| Document / selfie / liveness capture | `POST /api/kyc/sdk-token` (§4.1) + Sumsub MobileSDK | **real** | `@sumsub/react-native-mobilesdk-module` 1.46.1 (`src/services/sumsub.ts`). Token in memory only; renewal on expiry over a timer-free XMLHttpRequest path (fetch cannot complete while the SDK Activity is in front). `withDebug(false)`, no `onLog`, `errorMsg` never read. App auto-lock suppressed while the SDK is open; `FLAG_SECURE` on `com.sumsub.*` activities (black in recents and screenshots). Verified on the emulator: launch, 2 renewals while open, recents/screenshot black, camera opened. Document/liveness capture: physical-device run (`docs/testing/ANDROID_DEVICE_TEST_GUIDE.md`). Dev mock: `kycCapture`. |
| Notifications (bell, feed, tap → detail) | `GET /api/notifications` (§13) | **real** | Backend branch `feat/notifications-feed-api` (Option A: read-only view of the email outbox). Newest first, cursor paging with "Load more", pull-to-refresh, refetch on focus. Bell badge = unread items on the newest page ("9+" above 9). A test credit reads "Test credit received · Sandbox test funds · not real money" in every build. Tap → marks it read, finds the transaction in `GET /api/transactions` by `transactionId` (bounded) and opens the detail screen with the real item (so `REVERSED` shows); not found → "Open Activity". See "Notifications (mobile)" below. Dev mock: `notifications`. Push notifications: out of scope. |
| Activity / history (tab, detail, Home "Recent activity") | `GET /api/transactions` (§6) | **real** | Currency selector (6), cursor paging with "Load more". Empty pages with `hasMore: true` are followed (at most 5 per load), so "No transactions in EUR yet" appears only when the history really is empty. Items are shown **in server order** (newest-first is UNVERIFIED server-side; nothing is re-sorted). `REVERSED` is badged and struck through. The detail screen shows every field, with copy for the reference and `transactionId`. Refetch on focus and on pull-to-refresh. Home shows the first 3 items of the selected wallet. |
| Send (internal transfer) | `POST /api/recipients/lookup` (§12) + `POST /api/transfers/internal` (§5) | **real** (dev flag) | Recipient by email → masked email. The amount is typed as a decimal string and parsed to minor units with the wallet `scale` (no floats). `.` or `,` is the decimal separator, never grouping, and the parsed amount is shown live (e.g. "= TND 1.000"). The available balance comes from a fresh `GET /api/wallets`; above it, Continue is disabled ("Insufficient balance": a UX guard only, the backend stays authoritative). Review shows the amount prominently, "No fee" and "moves instantly". "No fee" is confirmed in code: an internal transfer is a single posting and `FeeCollectionService` has no caller. Confirmation uses `expo-local-authentication` (biometrics or the device credential); on a device with no screen lock, an explicit "Send now" with a note. Outcome handling: see "Internal transfer outcomes" below. |
| Account details (receive) | `GET /api/profile` (§11) `email`, `userId` | **real** | "Details" quick action on Home and a Profile row open one sheet: email and user ID (shortened) with Copy, plus Share. The Receive route was removed (design refresh). |
| PDF transaction receipt (Activity detail, Send success) | – (device-generated from §5 / §6 data) | **real** (device) | "Share receipt" builds a PDF **on the device** from data the app already has (`expo-print`), opens the system share sheet (`expo-sharing`) and deletes the file afterwards (`expo-file-system`). **Not legally authoritative**: a server-generated receipt is out of scope for now. Never offered for an uncertain/pending or rejected transfer. See "PDF receipts" below. |
| Add money / top-up | – (no endpoint yet) | **sandbox preview** (sandbox builds only); real top-up **blocked** | Sandbox builds show **example** deposit instructions: "Sponsor bank (sandbox)", holder "NEOS Client Funds (sandbox)", account number `XX00 SAND BOX0 0000 0000 0000 00` (not a real country code, fails the IBAN check), a currency selector (6), and the user's reference `NEOS-` + first 8 characters of the NEOS user ID in upper case. Copy per field, Share (the sandbox warning is part of the shared text), a permanent "Example details · sandbox · do not send real money" banner and "money appears once the bank confirms the transfer". No service call, no credit, no balance change, no transaction. A production build keeps the placeholder. The real top-up depends on the backend developer's Top-Up simulation (R13) and the sponsor bank (V2 #33). |
| Convert (FX, sandbox demo) | `POST /api/fx/quotes`, `POST /api/fx/demo/conversions`, `GET /api/fx/demo/conversions?idempotencyKey=` (§14) | **real** (sandbox builds only) | Amount → indicative quote with countdown → device confirmation → one POST. One key per confirmed quote, persisted before the POST; uncertain outcomes (409, 503, timeout) are never resent automatically: manual "Check status" / "Try again" (same key and quote) / "Stop tracking". 404 → "not available in this environment". A production build (`EXPO_PUBLIC_APP_ENV=production`) keeps the placeholder. Live-tested on `integration` and on `fix/fx-conversion-bugs`. |
| App unlock (fingerprint / screen lock) | – (device only) | **real** (device) | Opt-in after the first interactive sign-in (once per user, only if the device has a fingerprint or screen lock), and in Profile → Security. With it on: unlock screen on cold start **before** the session is restored, and after `EXPO_PUBLIC_LOCK_TIMEOUT_SECONDS` (default **60**) in the background. 3 failed attempts or any system lockout → Keycloak login. Fingerprints changed or removed → unlock turned off + Keycloak login. Transfers still ask for device authentication every time. See "App unlock" below. |
| Security settings | – | **real** (unlock toggle) | Profile → Security: the app-unlock toggle, plus how sign-in and transfers are protected. Other security settings (devices, password change) remain V2 #35. |

## Error mapping in the app (status-based, per endpoint)

The backend sends no error codes (§0.3). The app switches on HTTP status only, mapped in each service method (`mobile/src/services/real.ts`) from a typed `ApiError { status, endpoint, bodyText? }`.

| Endpoint | Status → user message |
|---|---|
| any | 401 → one token refresh + retry, then "session expired" (back to welcome). Network error / timeout → "couldn't connect". Malformed body → "something went wrong". |
| `GET /api/auth/me` | non-2xx → sign-in error |
| `GET /api/profile` | 404 → not onboarded (normal state, routes to onboarding). Other → error with retry. |
| `POST /api/onboarding/personal` | non-2xx → "couldn't save your information" |
| `POST /api/kyc/start`, `POST /api/kyc/sdk-token` | `errorCode` first (PR #61), else status. See "KYC error mapping" below. |
| `GET /api/wallets` | 503 → "Balances temporarily unavailable". Other → generic. The last good balances stay visible with "Last updated". |
| `POST /api/recipients/lookup` | 404 → "No eligible NEOS account found for this email" (uniform: the app never says why). 429 → "Too many attempts. Please try again in a minute." 403 → "Your account can't send money right now." 400 → "Enter the recipient's email address." |
| `GET /api/transactions` | 5xx (Formance down is a 500 today) → "Your activity is temporarily unavailable". Other → generic. |
| `GET /api/notifications` | 5xx → "Notifications are temporarily unavailable" + Try again. Other → generic. The bell shows no badge on any error (never a guessed number). |
| `POST /api/transfers/internal` | See the next section. Errors are classified as **rejected** (no money moved) or **uncertain** (money may have moved); they are never just displayed. |

No request is retried automatically except the single 401 refresh-retry.

The app understands an optional JSON `errorCode` in a 4xx error body (flat, or as a Problem Details extension). Today the backend sends none, so the app switches on status only.

## KYC error mapping (mobile `src/kyc/errors.ts`)

`/api/kyc/start` and `/api/kyc/sdk-token` return `{"errorCode", "message", "status"}` (PRs #61, #64). The app maps the code; `message` is never shown.

| errorCode (HTTP) | App behaviour |
|---|---|
| `ONBOARDING_REQUIRED` (409) | Re-read `GET /api/profile` and route (onboarding). |
| `KYC_NOT_STARTED` (409) | `POST /api/kyc/start`, then one more token request. |
| `KYC_ALREADY_APPROVED`, `KYC_FINAL_REJECTED`, `KYC_STATE_NOT_ELIGIBLE` (409) | Re-read the profile and route (verified / rejected / …). |
| `KYC_APPLICANT_INVALID` (409) | "We couldn’t open your verification. Please contact NEOS support." |
| `ACCOUNT_RESTRICTED` (403) | Restricted screen ("Your account is unavailable"), no SDK, for the rest of the session. |
| `KYC_MANUAL_REVIEW_REQUIRED` (403) | Stay on the waiting screen, "needs a manual review", "Continue verification" hidden. |
| `SUMSUB_UPSTREAM_ERROR` (502) | "Verification is temporarily unavailable" + Try again. |
| `SUMSUB_UNAVAILABLE` (503, PR #64) | "Verification is temporarily unavailable" + Try again. Never retried automatically. |
| no code: 409 | Re-read the profile: `NOT_STARTED` → start + one retry; otherwise route on it. |
| no code: 403 | Re-read the profile: `SUSPENDED`/`CLOSED` → restricted; otherwise "manual review". |
| no code: 5xx, network error, timeout | "Verification is temporarily unavailable" + Try again. Never retried automatically. |
| unknown code on a 5xx | "Verification is temporarily unavailable" + Try again (a new upstream code is treated like any other 5xx). |
| unknown code on a 4xx | Generic "couldn’t complete this check" (the app never guesses a route from an unknown code). |

Rule for new KYC codes: an `errorCode` the app does not know is classified by HTTP status only, 5xx → temporarily unavailable, 4xx → generic. A new code that needs different behaviour (routing, a specific message) needs an app change; tell the mobile side before shipping it. The mobile request timeout stays 15 s; a slower `kyc/start` shows "temporarily unavailable", and Try again is safe because `kyc/start` is idempotent.

## Internal transfer outcomes (mobile)

Code: `mobile/src/transfers/` (`engine.ts` state machine, `outcome.ts` classification, `reference.ts`). Unit tests: `mobile/tests/transferEngine.test.ts`.

- **Idempotency key:** a UUID generated when the user reaches Review. It is reused while the intent (recipient, currency, amount) is unchanged; any change gives a new key. A retry always reuses the key of the attempt it retries. The app never retries with a new key on its own.
- **Persistence:** the pending transfer `{key, recipient, amount, currency, reference}` is written to secure storage (`neos.transfer.pending.<senderUserId>`) **just before** the POST, and removed only on a final outcome. A Review that is never confirmed stores nothing. After a restart, Send (and a Home banner) resume the transfer and re-check its status once.
- **Deterministic reference:** `internal_transfer:ct_<senderUserId without dashes>_<idempotencyKey without dashes>`, lowercase.

| Response | Classified as | App behaviour |
|---|---|---|
| 200 | success | Amount, recipient, `transactionId`, reference. Home refetches balances on focus. |
| 400 | rejected | "Something in this transfer isn't valid…" + "No money was sent". |
| 403 | rejected | "Transfer not allowed." |
| 404 (flag off) | rejected | "Transfers aren't available right now." |
| 429 | rejected | "Too many attempts…" |
| 422 or other 4xx, no code | rejected | "This transfer was declined." |
| 4xx with `errorCode` | rejected | `INSUFFICIENT_FUNDS` → "Insufficient balance for this transfer." `RECIPIENT_NOT_ELIGIBLE`/`RECIPIENT_NOT_AVAILABLE`, `SENDER_NOT_ELIGIBLE`/`TRANSFER_NOT_ALLOWED` and `VALIDATION_FAILED`/`INVALID_REQUEST`/`SELF_TRANSFER` get their own messages. Unknown code → generic rejection. |
| 409 `IDEMPOTENCY_KEY_REUSED` / `IDEMPOTENCY_KEY_CONFLICT` | rejected (app bug) | "Please start it again"; the next attempt gets a new key. |
| 409 with no code (or any other code) | **uncertain** | Today the client cannot tell "different params" from "in progress / UNKNOWN". The app reuses a key only for the identical intent, so it treats the 409 as in progress. |
| 5xx (any body), network error, timeout, malformed 200 | **uncertain** | |

- **Uncertain:** the app searches `GET /api/transactions?currency=` for the deterministic reference (pages of 100, at most 10 pages). Found → success. Not found → "Transfer is being processed", with **Check status** (search again), **Try again** (same key only: worst case 409, still processing; a replayed 200 is a success) and **Done**. A failed search shows "We couldn't check right now".
- **Stop tracking:** offered only after at least one manual "Check status" without a result, behind the warning "We couldn't confirm this transfer. It may still appear in your Activity. Check your Activity before sending again." The transfer is remembered for 7 days: a new transfer with the same recipient, currency and amount shows the same warning again before Review.
- **Today vs after the backend fix:** today a Formance rejection (e.g. insufficient funds) is a 503 with the claim marked UNKNOWN. The app therefore shows "being processed", a retry gets 409 forever, and only "Stop tracking" ends it (the balance guard prevents this in normal use). After the fix, a definitive 4xx such as 422, ideally with `errorCode: "INSUFFICIENT_FUNDS"`, shows "Transfer not sent" with no app change.

## App unlock (mobile)

Code: `mobile/src/lock/` (`machine.ts` pure state machine, `device.ts`, `settings.ts`, `useAppLock.ts`), `mobile/src/screens/Unlock.tsx`, `mobile/src/screens/Security.tsx`. Unit tests: `mobile/tests/appLock.test.ts`.

- **Methods.** Strong biometrics enrolled → `biometric`. When unlock is turned on, a random marker is written with `expo-secure-store` `requireAuthentication: true` (Android Keystore key, `setUserAuthenticationRequired(true)`). Reading it is the unlock prompt. When fingerprints change, the OS invalidates the key, the read returns `null`, and the app treats that as **biometrics changed**. Screen lock only → `deviceCredential` (`expo-local-authentication` with the PIN/pattern/password); removal is detected, a change is not.
- **Tokens are not bound to the biometric key.** Every token refresh (about every 5 minutes) rewrites them, and a Keystore key that requires authentication would show a prompt on each write. The lock is enforced in app logic; see the limitation in `mobile/VALIDATION.md`.
- **Session lifetime.** Unlock does not extend the Keycloak session. A refresh token dies 30 minutes after its last use (`ssoSessionIdleTimeout=1800`). After that, unlocking leads to the welcome screen and a password sign-in; on a background lock the app shows "Your session has expired". See R4.
- **Sign-out** clears the tokens, the unlock preference and the marker. Pending-transfer records (`neos.transfer.pending.<userId>`) are deliberately kept, per user, as before: an uncertain transfer is shown again only when the **same** user signs back in, including after a forced password sign-in from the unlock screen.
- **Android recents preview:** hidden on Android 13+ by a local config plugin (`mobile/plugins/withRecentsPrivacy.js`, `setRecentsScreenshotEnabled(false)`). Screenshots still work. No effect on Android 12 and lower.

## PDF receipts (mobile)

Code: `mobile/src/receipts/` (`model.ts` data rules, `html.ts` pure HTML builder, `share.ts` print/share/delete), `mobile/src/components/ShareReceiptButton.tsx`. Unit tests: `mobile/tests/receipts.test.ts`.

- **Scope.** The receipt is generated **on the device** from data the app already holds. There is no extra request and no server involvement. It is a convenience document, **not** a legally authoritative receipt or bank statement; the footer says so ("Generated by the NEOS app from ledger data. This is not a bank statement."). A server-generated, signed receipt is **out of scope for now** and would need a backend endpoint.
- **Where.** Activity → transaction detail, and the Send success screen. History has no pending status (§6), so every history item qualifies. On Send, a receipt exists only for a `succeeded` outcome (including one later shown as `REVERSED`). It is **never** offered for an uncertain/pending or rejected transfer (`receiptFromOutcome` returns `null`).
- **Content.** NEOS header, "Transaction receipt", the generation time (local + UTC), the signed amount formatted with the money module at the currency scale (TND has 3 decimals), the type, direction, currency, status (`REVERSED` in red with the amount struck through), the date and time (local + UTC), the transaction ID and the reference.
  - **Date on a Send receipt:** the 200 response has no timestamp (§5). If the transfer was resolved from history, the ledger `occurredAt` is shown. Otherwise the row reads "Confirmed at (device time)", with the time the app received the confirmation.
  - **Counterparty:** only the recipient's masked email on a Send receipt. History items carry no counterparty, so the row is omitted (never invented).
  - **Account holder:** the user's own email only. The ID token `given_name` is self-declared and unverified, so it is not printed. A verified name can be added once the backend stores it from KYC.
  - **Sandbox:** in every non-production build (`EXPO_PUBLIC_APP_ENV` ≠ `production`, the same signal as the Home badge), a "Sandbox · test funds" badge, a watermark and a footer note.
- **Security.** Every dynamic value (and every label) is HTML-escaped, and the document carries a CSP (`default-src 'none'; style-src 'unsafe-inline'`), so no script or network load can run. The PDF is written to the app cache (`<cache>/receipts/NEOS-receipt-<transactionId>.pdf`), shared through the system share sheet, and deleted when the share sheet returns. Leftover receipts (crash, app killed) are swept before the next one. Nothing about the receipt is logged.

## Notifications (mobile)

Code: `mobile/src/notifications/` (`readState.ts` pure rules, `store.ts` shared per-user state, `readStore.ts` file persistence, `view.ts` wording, `hooks.ts` bell), `mobile/src/screens/notifications/Notifications.tsx`. Unit tests: `mobile/tests/notifications.test.ts`.

- **Read state is on the device (Option A).** The backend has none. It is not shared across devices, and a reinstall resets it.
- **Storage:** one JSON file per user, `<document dir>/notifications/read-<userId>.json` (`expo-file-system`). Not SecureStore: the data is not secret and can exceed the ~2 KB SecureStore values are reliable for. The user ID must be a UUID before it becomes part of a path.
- **Model:** everything created at or before a watermark is read, plus up to **500** individually read IDs (newest kept). On first use the watermark is the newest item, so existing notifications start as read. "Mark all as read" moves the watermark to the newest item. `createdAt` is compared exactly (microseconds).
- **Sign-out** (Profile, or a forced password login from the unlock screen) deletes every user's file. Read state survives app restarts.
- **Storage errors** never break the feed: the state then lives in memory for that run.

### Known limitation: `testCredit` (Option A)

`testCredit` is derived on the server as `type == INCOMING_PAYMENT`. That is correct today only because the back-office `SyntheticCreditService` is the sole producer of `INCOMING_PAYMENT` notifications. A backend ArchUnit test (`NotificationFeedTestCreditArchitectureTest`) fails as soon as another class produces one. **As soon as real incoming payments exist (bank top-up), this rule becomes wrong and R6 is required.**

## Requests for the backend developer

### R0. Transfer error codes the app already understands

If error bodies get a code, please use these `errorCode` values on any 4xx (the app ignores codes on 5xx, which always stay "uncertain"): `INSUFFICIENT_FUNDS`, `RECIPIENT_NOT_ELIGIBLE`, `SENDER_NOT_ELIGIBLE`, `VALIDATION_FAILED`, `SELF_TRANSFER`, and `IDEMPOTENCY_KEY_REUSED` for the different-parameters 409. A 409 for an attempt that is in progress or UNKNOWN must **not** use that code; it may use e.g. `TRANSFER_IN_PROGRESS`, which the app treats as uncertain. Unknown codes on a 4xx are shown as a generic rejection.

### R5. KYC follow-ups after PRs #58, #60 and #61 (Sumsub mobile flow)

The app now runs the Sumsub SDK with `POST /api/kyc/sdk-token` and maps the PR #61 `errorCode`s (`ONBOARDING_REQUIRED`, `KYC_NOT_STARTED`, `KYC_ALREADY_APPROVED`, `KYC_FINAL_REJECTED`, `KYC_STATE_NOT_ELIGIBLE`, `KYC_APPLICANT_INVALID`, `ACCOUNT_RESTRICTED`, `KYC_MANUAL_REVIEW_REQUIRED`, `SUMSUB_UPSTREAM_ERROR`) plus PR #64's `SUMSUB_UNAVAILABLE`. Requests:

- **(a) Expose the suspension reason, or a derived flag, in `GET /api/profile`.** For example `canResubmitKyc: true` when the account is `SUSPENDED` with reason `KYC_REVERIFICATION` and KYC is `RESUBMISSION_REQUIRED` (or the raw `suspensionReason`). Today the app has to probe `POST /api/kyc/sdk-token` to find out whether a suspended customer may resubmit, and shows neutral wording until the answer arrives.
- **(b) `errorCode`s for the other customer endpoints:** transfers (`POST /api/transfers/internal`; see R0 for the codes the app already understands), wallets (`GET /api/wallets`), recipient lookup (`POST /api/recipients/lookup`), profile and transactions. Only `/api/kyc/start` and `/api/kyc/sdk-token` have codes now; everything else is still mapped by HTTP status only.
- **(c) Fix #10: Sumsub timeouts and transport errors → 502/503 with codes. ✅ Done by PR #64.** The Sumsub `RestClient` now has connect/read timeouts (3 s / 10 s). Network errors, timeouts and Sumsub 408/429/5xx return 503 `SUMSUB_UNAVAILABLE`; other Sumsub 4xx and malformed responses return 502 `SUMSUB_UPSTREAM_ERROR`. A failed start leaves KYC `NOT_STARTED`. The app maps `SUMSUB_UNAVAILABLE` explicitly to "Verification is temporarily unavailable" + Try again (never retried automatically). Correction: before this change an unknown code got the generic message even on a 5xx, so a new code did *not* fall back to "temporarily unavailable" by itself. The app now treats an unknown code on a 5xx as temporarily unavailable, and on a 4xx as generic (see "KYC error mapping").

### R6. Explicit synthetic marker in the notification payload (needed before real top-ups)

`GET /api/notifications` marks every `INCOMING_PAYMENT` as `testCredit: true` (see "Known limitation: `testCredit`" above). Before any real incoming-payment producer is added, please add an explicit marker set by `SyntheticCreditService`, for example `boolean synthetic` in `TransactionNotificationPayload` (default `false` when absent, so existing rows stay readable), or a separate `TransactionNotificationType.SYNTHETIC_CREDIT`. The feed would then read the marker instead of the type, and `NotificationFeedTestCreditArchitectureTest` can be removed. The same marker would fix EMAIL-2 for the email itself.

### R7. Option B: dedicated in-app notifications (deferred)

Option A reuses the email outbox. Option B is a dedicated in-app table, independent of email delivery, with server-side read state:

- a new table (e.g. `customer_notification`), written by the same events as the outbox (or by an outbox listener), so that no notification depends on the customer having an email;
- `POST /api/notifications/{id}/read` (and optionally `POST /api/notifications/read-all`), and an unread count in the feed response, so the badge is the same on every device;
- room for non-transaction events (KYC decision, account status);
- ideally the same IDs as the outbox rows for existing notifications, so the device read state carries over (otherwise it resets once).

This needs a Flyway migration in the backend schema and your agreement, which is why it is deferred. The app's read-state module is isolated (`mobile/src/notifications/`) so it can switch to the server state without changing the screens.

### R8. Balance after each history item (`balanceAfterMinor`)

The app now groups Activity by day and would like to show the wallet balance at the end of each day in the day header. It must not compute that balance itself (decision D4: no client-side money arithmetic, and history pages are partial). Request: add to each item of `GET /api/transactions` a field `balanceAfterMinor` (string of minor units, same scale and format as `availableMinor` in `GET /api/wallets`; `null` when unknown), taken from Formance's `postCommitVolumes` for the customer's wallet account and asset of that transaction (balance = input − output after the posting). The app would show, for each day, the `balanceAfterMinor` of that day's latest item, so it also needs the items ordered by posting time within a page (newest first) — please confirm the ordering. Until the field exists the day-header slot stays hidden (`DayHeader` in `mobile/src/components/money.tsx`). The field is additive and optional, so older app builds are unaffected.

### R9. FX conversion fixes — branch `fix/fx-conversion-bugs` (PR for your review)

Three commits from `origin/integration`, each with tests (audit: `docs/api/FX_CONTRACT_AUDIT.md` §4):

- **BUG-FX-1** (`4586f52`): `fx_reserve` / `fx_convert` mapped to a new `CONVERSION` history type (source wallet DEBIT, destination wallet CREDIT). Before: conversions changed both balances with no history entry.
- **BUG-FX-2** (`8304c8c`, migration **V17**): Formance `400 INSUFFICIENT_FUND` at reservation → final status `REJECTED`, **422**, quote usable again (partial unique index). Any other ledger 4xx keeps the cautious `RESERVE_UNKNOWN` path. Before: 503 "requires review" forever, quote consumed.
- **BUG-FX-3** (`dc4d2d8`): `GET /api/fx/demo/conversions?idempotencyKey=` so a client can resolve a 503 / 409 / timeout. Before: the `operationId` was only in the (unexposed) exception message.

Verified live on the emulator against this branch (demo profile): conversions appear in both wallets' Activity; "Check status" resolves an unresolved operation to "still being processed".

### R10. FX error codes (BUG-FX-4, not fixed)

FX errors have no `errorCode`, and Spring's default body has no reason. 409 covers four cases (expired quote, key reused with another quote, operation unfinished, unresolved state); 503 covers three (quotes disabled, pair not configured, outcome unknown). Proposal, in the R0 style: `FX_DISABLED`, `FX_PAIR_UNAVAILABLE`, `QUOTE_EXPIRED`, `QUOTE_ALREADY_USED`, `IDEMPOTENCY_KEY_REUSED`, `CONVERSION_IN_PROGRESS`, `CONVERSION_REQUIRES_REVIEW`, `INSUFFICIENT_FUNDS`. Also useful: `GET /api/fx/pairs` (no way to list configured pairs today).

### R11. FX hold release and reconciliation (BUG-FX-5, not fixed)

If settlement fails after a successful reservation, the source amount stays in `users:{id}:wallet:{SRC}:holds:fx_{op}` (shown as pending) with no path back. Unreachable-ledger failures at reservation also stay `RESERVE_UNKNOWN` forever (2 such rows in the local DB from the live test, no ledger posting). Proposal: a reconciliation step that looks up the reservation and settlement references in Formance and then settles, releases the hold, or closes the operation.

### R12. `neos-fx-demo.ps1` (not fixed)

- `ApiBase` defaults to `http://localhost:8082`, which is Metro locally (the backend runs on 8080 here).
- It calls `POST /api/kyc/start` (state-changing) only to print the KYC status; `GET /api/profile` is read-only.
- It uses the password-grant client `neos-dev-cli`: fine for local only, keep it out of any production realm.

### R13. Top-Up simulation (needed for a real "Add money")

The app shows example deposit instructions in sandbox builds only (decision D44). To replace them with a real flow, the app needs, from the backend developer's Top-Up simulation:

- **Deposit instructions from the server** (bank name, account holder, account number or IBAN per currency, the user's reference), e.g. `GET /api/topup/instructions?currency=EUR`, so nothing is hard-coded in the app.
- **Reference matching for incoming transfers**: an incoming bank transfer carrying the reference is credited to that user's wallet in the transfer's currency. The preview uses `NEOS-` + the first 8 characters of the user ID (32 bits for a UUID). Please confirm the format; if it stays this short, the matching should check uniqueness (or use a longer reference) so two users can never share one.
- **What happens without a match** (wrong or missing reference): held for review, never credited to a guessed user.
- **History and notifications**: a credited top-up appears in `GET /api/transactions` and `GET /api/notifications`, and is **not** marked `testCredit` (R6 must land first, since `testCredit` is derived from `INCOMING_PAYMENT` today).

### R1. Keycloak realm `neos`: customer self-registration (needed for the app's "Let's begin")

The app's sign-up button sends the OIDC `prompt=create` parameter. Keycloak (≥ 22; the local stack runs **26.7.2**) then opens the registration form instead of the login form. Sign-in keeps `prompt=login`. On 2026-11-02 these settings were applied **to the running local realm only**, through the admin API. `infra/keycloak/realm/neos-realm.json` was **not** changed. Please carry them into the realm export:

| Setting (realm representation) | Value | Why |
|---|---|---|
| `displayName` | `"NEOS"` | Title of the Keycloak pages ("Sign in to NEOS"). |
| `registrationAllowed` | `true` | Without it, `prompt=create` has no registration page. |
| `registrationEmailAsUsername` | `true` | The backend identifies customers by the JWT `email` claim (`PersonalOnboardingController` requires a non-blank email). `GET /api/auth/me` returns 500 when `preferred_username` or `email` is missing (contract §2). With email as username, the registration form always collects an email, and `preferred_username` = email. Existing users keep their usernames and can still sign in with username or email (`loginWithEmailAllowed` is already `true`). |
| Default roles: add realm role `CUSTOMER` to the composite `default-roles-neos` | – | Every customer endpoint requires `CUSTOMER`. Without it, a self-registered user gets 403 on `/api/auth/me` and is stuck. Staff accounts (`COMPLIANCE_OFFICER`, …) are still created by an admin and would also receive `CUSTOMER`. If that is not wanted, remove it per staff user or use a separate realm or client for staff. |

Exact JSON for `neos-realm.json`, top level:

```json
"displayName": "NEOS",
"registrationAllowed": true,
"registrationEmailAsUsername": true,
```

and in `roles.realm`, the `default-roles-neos` entry:

```json
"composites": { "realm": ["offline_access", "uma_authorization", "CUSTOMER"], "client": { "account": ["view-profile", "manage-account"] } }
```

Admin API calls that were used (running realm only):

```
PUT  /admin/realms/neos                                   {"displayName":"NEOS","registrationAllowed":true,"registrationEmailAsUsername":true}
POST /admin/realms/neos/roles/default-roles-neos/composites   [<CUSTOMER role representation>]
```

**Not enabled, and needed before any real (non-sandbox) environment:**
- `verifyEmail: true`, with an SMTP server configured. Locally there is no SMTP, so email ownership is **not** verified. That is acceptable for the sandbox demo only.
- A password policy (e.g. `length(12) and notUsername and notEmail`). The realm has none today.
- Brute-force detection (`bruteForceProtected: true`).
- Optional: a registration CAPTCHA, and Terms-and-Conditions as a Keycloak required action. The app records terms acceptance during onboarding.

### R2. Keycloak end-session logout (follow-up)

Sign-out in the app is local: tokens are removed and the refresh token is revoked. The Keycloak browser SSO session survives, which is why sign-in uses `prompt=login`. A proper logout needs the realm's end-session endpoint called with `id_token_hint` and a registered `post.logout.redirect.uris` for `neos-mobile`. Today that is `+`, which allows the client's own redirect URIs, so `neos://auth` would work. No backend change is needed; this is listed for awareness.

### R3. Keycloak login theme `neos` (branded sign-in and registration)

New files, no Keycloak build step: `infra/keycloak/themes/neos/login/theme.properties` (`parent=keycloak`, the v1 login theme) and `login/resources/css/neos.css` (CSS only: NEOS colours, type, mobile-first layout). No `.ftl` template is overridden. On 2026-11-03 the theme was tried on the **running** container only (`docker cp` into `/opt/keycloak/themes/neos`; `start-dev` picked it up without a restart) and `loginTheme=neos` was set on the **running** realm through the admin API. Neither `compose.yml` nor `neos-realm.json` was changed. To adopt it:

`infra/keycloak/compose.yml`, service `keycloak`, `volumes`:

```yaml
      - ./realm:/opt/keycloak/data/import
      # NEOS login theme (CSS only, see themes/neos/README.md)
      - ./themes/neos:/opt/keycloak/themes/neos:ro
```

`infra/keycloak/realm/neos-realm.json`, top level:

```json
"loginTheme": "neos",
```

Admin API equivalent (running realm): `PUT /admin/realms/neos {"loginTheme":"neos"}`. To revert on a running realm: `PUT /admin/realms/neos {"loginTheme":""}`.

In production (`start`, not `start-dev`), themes are cached. Mount the theme before start, or bake it into the image under `/opt/keycloak/themes/neos`.

### R4. Shorter Keycloak session idle timeout for `neos-mobile` (security / product decision)

Today the realm has `ssoSessionIdleTimeout=1800` (30 min) and `ssoSessionMaxLifespan=36000` (10 h), and `neos-mobile` has no client-level override. A mobile session therefore stays usable for 30 minutes of inactivity, protected on the device only by the app's own unlock.

**Proposal:** reduce the idle timeout for `neos-mobile` to **5–15 minutes**. A bank will most likely benchmark this against the **PSD2 RTS on strong customer authentication** (Commission Delegated Regulation (EU) 2018/389, Art. 4(3)(d)): at most **5 minutes** of inactivity once the payer has been authenticated to access their payment account online. A client-level override leaves staff clients and the admin console as they are:

`neos-realm.json`, client `neos-mobile`, `attributes` (seconds; a client value may only be shorter than the realm value):

```json
"client.session.idle.timeout": "600",
"client.session.max.lifespan": "36000"
```

(Admin console: Clients → `neos-mobile` → Advanced → Advanced settings → Client Session Idle / Client Session Max.) Pick 300 to follow the RTS literally, or up to 900 as the upper bound of the proposal.

What changes for the app (no app change needed):
- After the idle timeout, the refresh fails and the user signs in again with the password. On cold start this is a silent return to welcome; on return from the background it shows "Your session has expired".
- App unlock then only protects sessions younger than that idle timeout. The access-token lifespan is already 300 s.
- Follow-up on the app side if wanted: skip the unlock prompt when the stored refresh token is known to be expired (needs `refresh_expires_in` kept with the tokens).

**Alternative, not proposed:** requesting the optional `offline_access` scope (already allowed on `neos-mobile`) would give 30-day offline tokens (`offlineSessionIdleTimeout=2592000`) and make app unlock the main protection for a month-long session. This is the opposite trade-off. It needs an explicit security policy decision (risk acceptance, device binding, revocation), so the app does not request it.

