# Validation evidence

Environment: Windows, Node 24 / npm 11, Pixel 8 emulator (Android API 35), JDK 17 for Android. Backend Maven remains JDK 21.

## PDF transaction receipts (device-generated) — 5 November 2026

Branch `feat/mobile-pdf-receipts` (from `integration` at `ffa61b7`). New dependencies: `expo-print` 55.0.19, `expo-sharing` 55.0.25 and `expo-file-system` 55.0.26 (`npx expo install`). The `expo-sharing` config plugin is **not** added: it is only for receiving shares ("share into"), and outbound sharing uses the library's own `SharingFileProvider`. `expo prebuild -p android`, then `gradlew app:assembleDebug -PreactNativeArchitectures=x86_64 --max-workers=1` (JDK 17, emulator off): **BUILD SUCCESSFUL** (4 min 53 s). Gradle stopped afterwards; disk 31 GB free.

- `tsc`, ESLint, Prettier: **PASS**. `npm test`: **177/177 PASS** (new `tests/receipts.test.ts`, 12 tests). `check:release-bundle`: 0 markers. `check:release-readiness`: only notifications unavailable.
- **Unexplained one-off test failure.** The first full `npm test` after the source edits reported `# tests 169`, `# pass 168`, `# fail 1`. Only the last 10 output lines were kept, so the failing test is **not identified**. It did not reproduce in 46 later full runs: 5 ad hoc, 20 back to back, 15 with a fresh tsx cache each (an empty `TEMP` per run), 5 through PowerShell 5.1 exactly like the original call (`npm test 2>&1 | Select-Object`), and 5 cold-cache runs with `tsc` and ESLint running at the same time. Every file also passes alone. The arithmetic (177 − 9 + 1 = 169) fits one test **file** crashing or failing to load, with its 9 tests replaced by one file-level failure, rather than an assertion failing. Files with exactly 9 tests: `apiClient`, `amountInput`, `historyPaging`. A crash partway through a larger file would fit too. No time-, random- or locale-dependent assertion was found: the only real timers race against an already-settled or never-settling promise. **If it recurs, keep the full output** (`npm test > test-output.txt 2>&1`) and look for the `not ok` line.
- Live, Pixel_6 emulator (API 36, x86_64, timezone UTC), backend on 8080 (root `.env`, dev profile, transfers and synthetic credits on), Metro 8082, `EXPO_PUBLIC_MOCK_FEATURES=kycCapture,notifications`, signed in as `neos-sender@neos.local`.
  - **Activity → Incoming payment +€100.00 (tx 55) → Share receipt:** share sheet "Sharing 1 file: `NEOS-receipt-55.pdf`" → Print → preview → **Save as PDF** → Downloads (`downloadfile.pdf`, via DocumentsUI). The app's PDF is A4 (595 × 841 pt), one page: NEOS, "Sandbox · test funds" badge, watermark and footer note, "Transaction receipt", the generation time (local + UTC), **+€100.00**, COMPLETED, Incoming payment / Money in / EUR / Completed, the date (local + UTC), the ID and reference, Account holder = own email, the footer "…This is not a bank statement." **No "To" row.** The saved copy is US Letter, because "Save as PDF" re-prints at the dialog's paper size.
  - **Send €1.10 to `neos-receiver@neos.local` → "Money sent." (tx 118) → Share receipt:** `NEOS-receipt-118.pdf`, **−€1.10**, Transfer sent / Money out, "Confirmed at (device time)" (local + UTC), the full `internal_transfer:ct_…` reference, **To n\*\*\*@neos.local**, Account holder. €1.00 was asked for, but the app showed "You may have sent this already" (an earlier untracked €1.00 to the same recipient within 7 days), so the amount was changed rather than overriding the warning. The emulator has no screen lock, so confirmation went through the explicit "Send now" path.
  - **Cleanup:** while the share sheet is open, `cache/receipts/NEOS-receipt-<id>.pdf` exists. After it closes (via Print/Save, and via Back = cancel), `cache/receipts` is gone and `cache/Print` is empty. Cancelling shows no error.
  - **Logging:** after both receipts, logcat (6,440 lines) has 0 matches for the references, emails, `NEOS-receipt` or receipt HTML.
- Not verified on a device: the uncertain/rejected Send screens without a receipt button (unit test `receiptFromOutcome` plus code path only), a REVERSED item (none in the test data; unit tested), a TND receipt (unit tested), a non-UTC device timezone (local and UTC were identical on this emulator), iOS, a physical phone, and opening the saved file in a stand-alone PDF viewer app (none is installed on the emulator; the PDF was checked through the Print preview and by rendering it on the host).

## Sumsub SDK: real in-app verification (phase 2) — 4 November 2026

Branch `feat/mobile-sumsub-sdk` (integration with PRs #58, #60, #61 merged). Backend: the merged code on 8080, dev profile, Flyway V13 applied, `.env` loaded like `scripts/run-backend-local.ps1` with process-only overrides `NOTIFICATION_DISPATCHER_ENABLED=false`, `MICROSOFT_GRAPH_ENABLED=false`, `SUMSUB_SDK_TOKEN_TTL_SECONDS=120`. Keycloak 26.7.2 on 8081, Formance 8083, Postgres 5434. Pixel_6 emulator (API 36, x86_64, camera `webcam0`). Metro 8082 `--clear`, `EXPO_PUBLIC_MOCK_FEATURES=none`.

Native changes: `plugins/withSumsub.js` also patches `MainApplication` (FLAG_SECURE on `com.sumsub.*` activities). `expo prebuild -p android`, then `gradlew app:assembleDebug -PreactNativeArchitectures=x86_64 --max-workers=1` (JDK 17, emulator off): **BUILD SUCCESSFUL** (1 min 38 s).

- `tsc`, ESLint, Prettier: **PASS**. `npm test`: **163/163 PASS** (new `tests/kycFlow.test.ts`). `check:release-bundle`: 0 markers; `--control`: 6 mock markers. `check:release-readiness`: only notifications unavailable.
- New user `neos-sumsub-2@neos.local` registered in the app → onboarding → KYC intro ("Verification is done with our identity partner, Sumsub…") → **Start verification** → `kyc/start` (PENDING) → pending screen. Found and fixed during the run: the SDK module is CommonJS, so `require(...).default` was undefined and the first launch showed "We couldn't complete this check".
- **Continue verification** → the SDK opens (`SNSAppActivity`). Window flags include `SECURE`: the `adb` screenshot is black and the recents card is black.
- **Token renewal while the SDK stays open (TTL 120 s): 2 of 2 succeeded**, 195 ms and 560 ms after the expiry callback; the second one after the 300 s access token had expired (timer-free Keycloak refresh). In phase 1, renewal could only finish after the SDK closed.
- Camera: runtime prompt ("Only this time"), webcam frames flowing, camera open by the app on "Front of ID". **Capture NOT RUN** on purpose: no ID document, and the shutter would upload the developer's webcam image to Sumsub. Covered by `docs/testing/ANDROID_DEVICE_TEST_GUIDE.md`.
- SDK closed with back → pending screen with "Continue verification" / "Check for an update", no error. API and DB: `PENDING_KYC` / `PENDING` / SUMSUB applicant set. No webhook locally (expected).
- logcat (32k lines) + backend + Metro logs: **0** Sumsub tokens, JWTs, `refresh_token=`, `accessToken=` or `errorMsg`.
- Not verified live: the `errorCode` paths, the polling cadence and the ISSUE-6 restricted-screen re-check (unit tests only; the local DB was not edited to create restricted states).

## App unlock (fingerprint / screen lock), branded login — 3 November 2026

Branch `feat/mobile-biometric-unlock` (from `integration` after PR #53). Backend dev profile on 8080 (already running from the IDE; transfers flag and synthetic credits on), Keycloak 26.7.2 on 8081, Formance 8083, Postgres 5434. Pixel 6 emulator (API 36, x86_64, Chrome 133). Metro 8082 `--clear`. `EXPO_PUBLIC_MOCK_FEATURES=kycCapture,notifications`. Lock timeout: the default, **60 s**.

Native change: local config plugin `plugins/withRecentsPrivacy.js` (`setRecentsScreenshotEnabled(false)` on Android 13+). `expo prebuild -p android`, then `gradlew app:assembleDebug -PreactNativeArchitectures=x86_64 --max-workers=1` (JDK 17) with the emulator off: **BUILD SUCCESSFUL** (2 min). Gradle and Kotlin daemons were stopped before the emulator started. About 10 GB RAM stayed free.

Static checks:
- `npx tsc --noEmit`, `npm run lint`, Prettier check: **PASS**.
- `npm test`: **144/144 PASS**. New suite: `appLock` (state machine, error classification, settings, auto-lock suppression, timeout config).
- `npm run check:release-bundle`: **PASS**, 0 mock markers.
- `npm run check:release-readiness`: Security settings are no longer listed as a placeholder.

Live, emulator. Test PIN 1234 was set with `locksettings set-pin`, and fingerprint 1 was enrolled through Settings → Pixel Imprint with `adb -e emu finger touch 1`. Test users created on the running realm only: `neos-bio-sender`, `neos-bio-receiver`, `neos-bio-compliance` (`COMPLIANCE_OFFICER`), all `@neos.local`. The sender was onboarded in the app (details, terms, simulated KYC); the receiver through the API. A synthetic credit gave the sender €100.

- **Opt-in:** after the interactive sign-in and onboarding, Home showed "Unlock NEOS with your fingerprint" (1-minute wording). "Turn on" opened the system prompt "Turn on app unlock" (the Keystore marker write); finger 1 turned it on.
- **Cold start, unlock on:** the system prompt "Unlock NEOS" appeared before any app content.
  - **Finger 2** (not enrolled): rejected twice. The sensor counter `rejectCrypto` went up, so the prompt is bound to the Keystore key.
  - **Cancel:** "NEOS is locked" with "Unlock cancelled. Your account stays locked.", Unlock, and Sign in with password. No balance was visible.
  - **Unlock with finger 1:** Home with €100.00 (`acceptCrypto` went up).
- **Transfer right after unlocking:** Send €10 to `neos-bio-receiver` still showed the separate "Confirm your transfer" prompt.
  - Finger 2 was rejected. Back → "Confirmation cancelled. Nothing was sent." The API showed sender EUR 10000, receiver 0.
  - Finger 1 → **Money sent**, €10.00, transaction 93. The API showed sender 9000, receiver 1000.
- **Recents (app switcher):** the NEOS card was blank, while the Settings card next to it showed its content.
- **Background 65 s:** reopening showed the unlock prompt. Cancel → locked screen; finger 1 → Home.
- **Background 10 s:** no prompt.
- **Profile → Security:** "App unlock · On · fingerprint". Turned off → "Off", and the next cold start went straight to Home with no prompt. Turned on again from Security with finger 1.
- **Biometrics changed:** finger 2 was added in Settings, then cold start → "Sign in again: The fingerprints on this device have changed, so app unlock was turned off…". This happened without a prompt: the Keystore key had been invalidated. "Sign in with password" → Keycloak login → Home. Security then showed "Off" and no offer appeared again.
- **Sign-out** from Profile, then a new sign-in: the offer appeared again, because sign-out clears the preference.
- **System lockout:** cold start, then 6 touches with unknown finger 3 → "Sign in again: Your device has temporarily blocked fingerprint attempts…" (the sensor recorded `lockout: 1`). "Not now" → welcome, and the next cold start → welcome with no lock: the session had been removed.
- **Branded login:**
  - The Keycloak login and registration pages render with the `neos` theme: NEOS wordmark with the gold square, app colours, 56 px primary button, left-aligned title.
  - The registration header was fixed during the run: the title now comes first and "Required fields" sits under it.
  - The Custom Tab shows the title "Sign in to NEOS".
  - The **toolbar stays Chrome's default colour and the share button is still shown**: `expo-web-browser` 55.0.20 on Android applies `toolbarColor` as the secondary toolbar colour and never sets the share state OFF (`WebBrowserModule.kt`). Fixing it needs a library change. The app passes the wanted values.
- The system biometric prompt is a secure window: emulator screenshots of it are black. Its presence and text were read from the UI hierarchy.
- **Cleanup:** the test PIN was cleared with `locksettings clear --old 1234`, which removed both fingerprints (`count: 0`; Settings shows "Tap to set up fingerprint"). The emulator was then shut down.

Running environment left changed (local only, nothing tracked): the theme was copied into the `neos-keycloak` container (`docker cp`) and `loginTheme=neos` was set on the running realm (revert: `PUT /admin/realms/neos {"loginTheme":""}`). The three `neos-bio-*` users exist in the running realm. Metro was restarted, because its file watcher no longer saw new files.

Dev-only artefact seen: editing `src/services/real.ts` while signed in made Fast Refresh re-run the module, which reset its in-memory tokens, and the next request reported "session expired". This does not happen in a release build.

**Limitation (app-logic lock):** the session tokens are not cryptographically bound to the biometric. They stay in `expo-secure-store` (Android Keystore-encrypted, not bound to user authentication), because a key that requires authentication would need a prompt on every token refresh (about every 5 minutes). The unlock screen is enforced in JavaScript. Code running in the app process could skip it: a rooted device with instrumentation, or a debug build with the JS debugger attached. The biometric marker only proves that the enrolled biometrics are unchanged. Mitigations for later: Play Integrity / root detection, short Keycloak idle sessions (`docs/api/INTEGRATION_STATUS.md` R4), and server-side step-up for sensitive actions.

Not verified live:
- **3 failed attempts** (counted `failed` results). On Android the biometric prompt retries "Not recognized" internally and ends in a system lockout, which was tested. The 3-attempt counter mostly matters for the device-credential path and unexpected prompt errors: unit tests only.
- **Device-credential method** (screen lock without fingerprint) for app unlock: unit-level mapping only. The transfer confirmation's PIN path was tested on 2 November.
- **Biometrics or screen lock removed while unlock is on** (the `notEnrolled` path): unit tests only. At cleanup the session had already been removed.
- **Unlock after the 30-minute Keycloak idle timeout:** expected to unlock and then land on welcome (cold start) or show "session expired" (background). Not waited for.
- **iOS** (Face ID, Keychain behaviour of `requireAuthentication`, the app-switcher snapshot, which relies on the JS privacy cover): not run, no macOS.
- **Android 12 and lower:** the recents preview is not hidden there (no `setRecentsScreenshotEnabled`).
- **Physical devices** (a real fingerprint sensor, OEM BiometricPrompt variants).

Screenshots were inspected during the run (session scratchpad, not committed).

## Send, Activity, Receive, greeting, silent sign-in cancel — 2 November 2026 (evening run)

Branch `feat/mobile-real-integration`. The backend ran from the same worktree (identical backend code to `feat/customer-read-apis`): dev profile, port 8080, `KYC_MODE=MOCK`, transfers flag on, synthetic credits on. Keycloak 8081, Formance v2.4.14 gateway 8083, Postgres 5434. Pixel 6 emulator (API 36, x86_64). New native module `expo-local-authentication` (55.0.18): `expo prebuild` (adds `USE_BIOMETRIC`/`USE_FINGERPRINT`), then `gradlew app:installDebug -PreactNativeArchitectures=x86_64 --max-workers=1` with JDK 17: **BUILD SUCCESSFUL**. Metro 8082 `--clear` (IPv4 first). `EXPO_PUBLIC_MOCK_FEATURES=kycCapture,notifications`.

Static checks:
- `npx tsc --noEmit`, `npm run lint`, Prettier check: **PASS**.
- `npm test`: **112/112 PASS**. New suites: `amountInput`, `transferEngine`, `historyPaging`, `displayName`; `mocks` extended.
- `npm run check:release-bundle`: **PASS**, 0 mock markers.
- `npm run check:release-readiness`: Send, Receive and Activity are no longer listed as not implemented.

Live, as `neos-sender` (start: €100.00):
- **Sign-in cancel:** closing the Keycloak tab (Back, and the tab's X) returned to welcome with **no error**.
- **Greeting:** "Smoke", the Keycloak first name read from the ID token `given_name`.
- **Home "Recent activity":** the 3 real EUR items (incoming €25, transfer sent €25, incoming €100).
- **Send, recipient step:**
  - `nobody@neos.local` → "No eligible NEOS account found for this email." (backend 404).
  - `NEOS-Receiver@neos.local` (mixed case) → `n***@neos.local`.
- **Send, amount step:**
  - EUR "1,000" → "EUR allows up to 2 decimals." and Continue disabled.
  - TND "1,000" → "= TND 1.000" and "Insufficient balance" (TND balance 0.000).
  - EUR "10" → "= €10.00", "Available: €100.00".
- **Review:** €10.00 (large), To `n***@neos.local`, EUR, Fee: No fee, the instant/no-cancel note.
- **Device authentication:**
  - With no screen lock, Confirm → "No screen lock on this device" + "Send now"; Back → Review.
  - With a PIN set on the emulator (`locksettings set-pin`), Confirm → system prompt "Confirm your transfer". Back → "Confirmation cancelled. Nothing was sent." (EUR stayed 10000 via the API). Then PIN → **Money sent**: €10.00, transaction ID 90, reference `internal_transfer:ct_7bf8f8ad…_efee193a…`, Copy.
  - The emulator PIN was cleared afterwards.
- **After the send:**
  - Done → Home **€90.00** (focus refetch).
  - Activity (EUR): "Transfer sent −€10.00" on top. Detail: every field, with the same reference and transaction ID 90.
  - Receive: `neos-sender@neos.local` and the user ID, Copy ("Copied" shown), Share opened the Android share sheet with "You can send me money on NEOS using my email: …".
- **Uncertain path** (€1.00; Review reached first, then `docker stop neos-formance-gateway`, then confirmed with the PIN):
  - The backend answered **503** "Transfer outcome not confirmed" (claim UNKNOWN). The automatic history check got a 500, and the app showed **"Transfer is being processed."** with the deterministic reference, Check status, Try again and Done, and **no** Stop tracking yet.
  - Check status with the gateway still down → "We couldn't check right now."
  - Gateway restarted, then Check status → "Not confirmed yet: it doesn't appear in your activity." This is correct: nothing was posted (the API history confirms it).
  - Try again (same key) → backend **409** "Transfer is in progress or …", and the screen stayed "being processed".
  - App force-stopped and relaunched → session restored, and Home showed "A transfer is being confirmed · €1.00 to n***@neos.local". Tapping it resumed the transfer in Send.
  - Stop tracking → sheet with the agreed warning → confirmed → banner gone.
  - A new Send to the same recipient with "1.00" EUR → **"You may have sent this already"** with the same warning, before Review (Check activity / Continue anyway).
  - Continue anyway → Review; backed out without sending.
  - Expected under today's backend: this UNKNOWN claim can never resolve to success, because it was never posted.
- Sign-out → welcome.

Live, as `neos-receiver`:
- Home €35.00 (25 + 10), greeting "Smoke", and no pending-transfer banner (pending state is per user).
- Activity: "Transfer received +€10.00", 7:44 PM, on top.
- GBP → "No transactions in GBP yet."
- With the gateway stopped, Activity → "Your activity is temporarily unavailable…" + Try again. After a restart, Try again recovered.
- Receive: `neos-receiver@neos.local` and `f61b6653-…`.

Observed, not guaranteed: `GET /api/transactions` returned **newest first** (tx ids 90, 88, 56, 55). The app does not sort, so this order comes from Formance's default and is still unverified as a contract.

Not verified live:
- **Uncertain → found** (resolving to success from history). It needs a posting that succeeds while the response is lost, which this setup cannot force on demand. It is covered by unit tests (503/409/500/network/timeout/malformed 200 → found across empty pages).
- **Definitive rejections** (400/403/422/`errorCode`) from the transfer endpoint. The backend fix is not in this build, and today an insufficient-funds send is a 503 (uncertain). Unit tests only.
- **Biometrics** (fingerprint/face): only the device-PIN credential was exercised. An emulator fingerprint enrollment was not done.
- **"Load more"** paging: each currency has fewer than 20 items, so only one page. Empty-page skipping and the hop cap are covered by unit tests.
- **iOS** (Face ID permission string, share sheet) was not run (no macOS).
- 429 on lookup and 403 for an ineligible sender: unit-level mapping only.

Screenshots were inspected during the run (in the session scratchpad, not committed).

## Sign-up, verified screens, stale token — 2 November 2026 (later run)

- Keycloak 26.7.2. Running realm only: `displayName=NEOS`, `registrationAllowed`, `registrationEmailAsUsername`, `CUSTOMER` added to `default-roles-neos`. The changes requested for `neos-realm.json` are in `docs/api/INTEGRATION_STATUS.md` (R1). `prompt=create` was checked with curl (`kc-register-form`) and on the device.
- `tsc`, `lint`, Prettier: **PASS**. `npm test`: **65/65 PASS** (new `verifiedScreens.test.ts`).
- Sign-up: "Let's begin" → Keycloak **Register** form (email, password, first/last name) → registered `abir.demo1@neos.local` → app (`/api/auth/me` OK thanks to the default `CUSTOMER` role) → details → review/terms → KYC → "You're verified" → "ready" → Home (6 wallets at 0, Sandbox badge).
- Returning `neos-sender` via "Already have an account? Sign in" (login form, username still accepted in the "Email" field) → **straight to Home**, with no verified screens.
- Cold start with a stored token Keycloak no longer accepts (no active session) → plain welcome, **no error**.
- Session revoked in Keycloak while signed in, then a request after the access token expired → welcome with "Your session has expired" (an in-use expiry is still reported).
- Note: the Home greeting shows the email for self-registered users, because `preferred_username` = email. First and last name are collected by Keycloak but not exposed by `/api/auth/me`. Follow-up: a display name from the ID token, or a backend field.
- Note: closing the Keycloak tab without signing in shows "We couldn't sign you in". That behaviour is unchanged; it could be silent on user cancel.

## Real backend integration (wallets, profile, navigation) — 2 November 2026

Branch `feat/mobile-real-integration`, against backend `feat/customer-read-apis` (dev profile, `KYC_MODE=MOCK`, synthetic credits on), Keycloak 8081, Formance 8083, Postgres 5434. `EXPO_PUBLIC_MOCK_FEATURES=kycCapture,notifications`. Pixel 6 emulator (API 36, x86_64), debug dev-client build (JDK 17, `-PreactNativeArchitectures=x86_64`), Metro 8082 `--clear`.

- `npx tsc --noEmit`: **PASS**. `npm run lint`: **PASS** (0 problems). Prettier check: **PASS**.
- `npm test`: **59/59 PASS** (journey, config, mocks, money, apiClient, profileMapping, wallets).
- `npm run check:release-bundle`: **PASS**, 0 mock markers in the production-like Android bundle. `npm run check:release-readiness`: wallets now real; kycCapture and notifications have no real implementation.
- Live, `neos-sender`: Keycloak login form (forced by `prompt=login`) → "You're verified" → Home with the 6 real wallets from `GET /api/wallets` (€75.00, TND at 3 decimals) and the permanent Sandbox badge. A synthetic credit of 1000 MUR (`POST /api/backoffice/synthetic-credits` as `neos-compliance`) then a pull-to-refresh showed MUR 1,000.00. A €25 credit while the Send placeholder was open showed €100.00 on return, with no pull (focus refetch). With the Formance gateway stopped the backend answered 503: Home kept the last balances with "Balances are temporarily unavailable… Showing the last balances we received" and Try again, which recovered after the gateway restarted.
- Profile (`GET /api/profile`): masked email `n***@neos.local`, phone, Active, Verified, member since, user ID with working Copy. Security, Send and Activity placeholders work. Android Back pops stack screens and returns from the Profile tab to Home.
- Live, fresh user `neos-fresh-1`: login → `GET /api/profile` 404 → onboarding intro → details/review/terms → `POST /api/onboarding/personal` → KYC intro → simulated capture → `POST /api/kyc/start` + `GET /api/profile` → verified → Home showed READY (wallet provisioning had finished by then). `PROVISIONING` / `NOT_ELIGIBLE` banners and "Setting up…" are covered by unit tests only; they were not observed live.
- Sign-out then sign-in shows the Keycloak login form again (no silent SSO re-login).
- Screenshots were inspected during the run (not committed; `artifacts/` is ignored).

Environment notes found during this run:
- **Metro must bind IPv4.** Node resolves `localhost` to `::1`, but `adb reverse` dials `127.0.0.1`. The dev client then fails with "unexpected end of stream". Start Metro with `NODE_OPTIONS=--dns-result-order=ipv4first` (now set by `scripts/android.ps1`).
- **Parallel native builds** intermittently failed with `CreateProcess: Accès refusé` (access denied) from the NDK toolchain. `gradlew app:installDebug -PreactNativeArchitectures=x86_64 --max-workers=1` succeeded.

Follow-ups:
- **Keycloak end-session logout on sign-out.** Sign-out is local token removal plus refresh-token revocation. The browser SSO session survives, and `prompt=login` is only a stopgap so another user can sign in on the same device.
- Activity/history, Receive and Send were wired in the evening run above. Add money, Convert, Notifications and Security remain placeholders.

## Home and currency wallets — 17 September 2026

- TypeScript, ESLint and Prettier: **PASS**. Domain tests: **13/13 PASS**.
- `node scripts/smoke.mjs`: **PASS**, including onboarding, Home, upcoming-action sheet, restart and sign-out/sign-in. The script now uses the input's Done action to dismiss the keyboard; sending Android Back could also leave the form on this emulator.
- `node scripts/home.mjs` (start on demo Home): **PASS**. Checks MUR/USD/EUR cards, selected state, currency-menu selection, hidden balances in both visible text and accessibility labels, selected currency in action sheets, and Android Back closing the menu.
- Home was exercised at **360 × 640 dp**, including wallet selection, menu selection and scrolling to activity. Original emulator size/density were restored. Standard and small-screen screenshots were inspected.
- Captures: `artifacts/home-final.png`, `home-usd.png`, `home-wallet-menu.png`, `home-small-top.png`, `home-small.png`, `home-small-activity.png`.
- Balances remain zero-value previews. This validates the interface, not ledger integration or real transfers. iOS runtime remains untested.

## Completed checks

- Android native debug build: **PASS** (`app:assembleDebug`). App installed and launched as `com.neos.mobile`.
- TypeScript strict check: **PASS**.
- ESLint: **PASS**.
- Prettier check: **PASS**.
- Frontend domain tests: **8 PASS** (lifecycle/reset/restoration, all KYC demo outcomes, failures/retry, expiry, restriction priority, phone/back rules, no KYC before onboarding, timeout recovery).
- Expo Doctor: **20/20 PASS**.
- Android and iOS Hermes bundle exports: **PASS**.
- Pixel emulator: sign-in, onboarding, review/edit, terms, sample document/selfie capture, approved, ready and home were exercised. Screenshots were inspected, including approval and network-error screens.
- Android happy-path UI script: **PASS**, including cold-restart restoration and upcoming-feature sheet. It waits for the expected screen rather than assuming a fixed development reload time.
- Android non-happy-path UI script: **PASS** for pending, network failure/retry, rejected, additional information/recapture, hardware back, expiry and hidden reset.
- Small display at **360 × 640 dp**: welcome, field focus, automatic keyboard scrolling and visible inline validation **PASS**. Emulator size/density were restored to the original 1080 × 2400 / 420 dpi afterwards.
- The keyboard check exposed a clipped error message; the form now scrolls the focused phone field and error into view when the keyboard opens or validation content changes.

The first clean Android build installed API 36, NDK 27.1, Build Tools 35 (transitive requirement) and CMake 3.22.1 using already accepted SDK licences. This did not alter the backend JDK or global Java settings. Expo uses Build Tools 36 / compile and target SDK 36; the API 35 emulator runs the app.

## Reproduce device checks

With Metro and the installed app running, reset the demo to welcome using the hidden wordmark gesture, then:

```powershell
node scripts/smoke.mjs
node scripts/scenarios.mjs
```

The first script exercises happy path, invalid phone, keyboard, edit/back persistence, terms, document/selfie, approval, activation, upcoming-action sheet and cold restart. The second starts at home and covers pending, one-shot network failure/retry, rejected, additional information/recapture, Android hardware back, session expiry and hidden reset. It leaves the app at welcome.

`node scripts/small-screen.mjs` can be run from demo home or welcome. It checks the small-display form and restores the original display configuration in `finally`. It leaves the sample onboarding form open. The delivered emulator has been reset to a fresh welcome after testing.

Screenshots and result files go to ignored `artifacts/`. They contain only synthetic demo data. The scripts interact only with the NEOS app and do not require an external testing service.

## Existing backend suite

`mvnw.cmd test -q` ran with Java 21: **29 tests, 11 passed, 18 errors**. The integration application context fails because PostgreSQL rejects the configured `fintech_app` password; subsequent tests hit Spring's context failure threshold. No backend files, credentials or database configuration were changed. This is separate from the offline frontend demonstration.

## Remaining verification

- Real Keycloak/browser round trip and live API calls require the services running, valid database credentials, the `neos://auth` redirect registered and an approved terms URL. The real adapter is implemented and typechecked, but has not been validated against a live authenticated environment here.
- iOS native runtime/VoiceOver and physical devices require macOS/Xcode. iOS bundle generation is not a substitute for that testing.
- Full TalkBack audit, French/Arabic translations, RTL and large accessibility fonts remain release-readiness work. Layout uses safe areas, logical spacing, scalable system text, labels, button/checkbox states and reduced-motion handling.
- Camera/provider verification and all financial actions are deliberately simulated or upcoming, as described in README.
