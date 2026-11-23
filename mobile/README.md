# NEOS mobile · Validation 1

A React Native / Expo SDK 55 TypeScript app for **sign-in → personal onboarding → identity verification → active account**. Financial actions are a preview only. No backend source or Java configuration is changed.

## Run on the prepared Windows machine

```powershell
cd mobile
npm ci
Copy-Item .env.example .env
npm run android
```

Start the Pixel emulator first. The Android script discovers Microsoft's JDK 17 (or uses `NEOS_ANDROID_JAVA_HOME`), sets Java and Android SDK paths **only in its child process**, forwards emulator ports, generates the native project and builds/launches NEOS. Backend Maven continues using JDK 21. Metro uses **8082**, leaving **8081** for Keycloak and **8080** for Spring Boot.

Once installed, `npm start` starts Metro; open NEOS on the emulator. For a physical device, select it through `ANDROID_SERIAL` in your shell (or update the local launch script) and forward the same ports. No Expo account or cloud build is needed. On macOS, use `npm ci`, `npx expo run:ios --port 8082` with Xcode installed. iOS has not been runtime-tested on this Windows machine.

Native folders are reproducible output from `app.config.ts` and Expo prebuild, and are ignored. Do not place durable configuration directly in generated native files.

## Audit findings

- Before this work, `mobile/` contained only `assets/brand/TANYT02.png` and `TANYT03.png`. There was no package.json, React Native/Expo app, navigation, state management, API client, secure storage, font, icon library or design system to reuse.
- The two preserved TANYT raster logos show a navy/ivory/gold identity. They spell an old product name, so the UI uses a temporary live-text **NEOS** wordmark, retaining that palette with restrained blue accents. No approved NEOS artwork was found.
- Spring Boot 4.1, Maven, Java 21, PostgreSQL/Flyway; resource-server JWT validation using the `neos` Keycloak realm. CUSTOMER realm role is required by all three controllers. No OpenAPI file or README was found.
- `infra/keycloak/realm/neos-realm.json` already defines public client `neos-mobile`, standard flow enabled, direct/password grants disabled, but **no redirect URIs**. No existing frontend authentication implementation existed.
- Personal onboarding accepts **optional phoneNumber** (maximum 32 characters), **installationId**, **platform** (`ANDROID|IOS|WEB`) and **termsVersion**. Email and identity come from the JWT. No name, DOB, nationality or address fields exist in this DTO. Consequently there are no invented mandatory fields, date or country pickers in this slice.
- KYC statuses: `NOT_STARTED`, `PENDING`, `IN_REVIEW`, `APPROVED`, `REJECTED`, `RESUBMISSION_REQUIRED`. Account statuses: `PENDING_KYC`, `ACTIVE`, `SUSPENDED`, `CLOSED`.
- With backend `KYC_MODE=MOCK`, `POST /api/kyc/start` approves and activates immediately. A Sumsub adapter and webhook exist, but there is no SDK-token endpoint, so real capture cannot run from the app yet.
- `GET /api/profile` (backend branch `feat/customer-read-apis`) is the **read-only status source**: the app reads KYC and account status from it after sign-in, onboarding, KYC start, on "Check for an update" and on foreground. `POST /api/kyc/start` is called only to start KYC. `POST /api/onboarding/personal` always returns `NOT_STARTED`, so it is never used as a status lookup.
- `GET /api/wallets` returns the 6 wallets with string minor-unit amounts. Full status per feature: `docs/api/INTEGRATION_STATUS.md`.

## Structure and screens

`App.tsx` renders the route derived by `hooks/useJourney.ts` and `navigation/state.ts`. State transitions live there, not inside service-specific screen conditionals. React Context/Redux/navigation dependencies were unnecessary for this bounded, linear flow; React state plus a central route table handles header and Android hardware back. Completed flows are removed from back navigation.

- Welcome and secure sign-in/loading/error
- Setup introduction, contact details, editable review and terms sheet
- Verification introduction, passport/identity-card choice and sample capture, selfie sample capture
- Processing, pending, rejected and additional-information states
- Verified, account ready
- Home, profile, notification empty state and upcoming-feature sheets

Shared UI primitives provide safe areas, keyboard avoidance, scroll containers, input focus/errors, button states, progress, badges, sheets and reduced-motion-aware transitions. English copy lives in `src/locales/en.ts`; typed keys prepare French/Arabic dictionaries. Start/end spacing supports future RTL. System fonts and Feather icons are used throughout. After approval the app enters an authenticated React Navigation tree (`src/navigation/AuthenticatedApp.tsx`): bottom tabs Home / Activity / Profile, with Send, Receive, Add money, Convert, Notifications and Security stacked on top (honest "Not available yet" placeholders for now). Home shows the 6 real wallets from `GET /api/wallets`, formatted exactly by `src/money` (string minor units, API scale, locale separators). Tap a wallet to show it in the balance card; the eye control hides all balances. Not-provisioned wallets read "Setting up…", never 0. No combined total is shown (that needs FX). Balances refetch on focus, pull-to-refresh and foreground. A permanent "Sandbox · test funds" badge shows unless `EXPO_PUBLIC_APP_ENV=production`.

## Mock features

Every fake behavior is controlled from one place: `EXPO_PUBLIC_MOCK_FEATURES`. The closed feature list is in `src/mockFeatures.ts`; what each one fakes, where, and what replaces it is in `src/mocks/registry.ts`.

| Value | Meaning |
| --- | --- |
| `all` | every feature mocked |
| `none` or unset | nothing mocked: real backend and the real Sumsub SDK; `notifications` shows "not available yet" |
| `wallets,kyc` | comma-separated list; names are case-insensitive and trimmed; an unknown name is a dev-time config error naming it |

Features: `auth`, `onboarding`, `kyc`, `kycCapture`, `wallets`, `notifications`.

Rules:
- Mocks are active **only when `__DEV__` is true**. In staging/production the mock set is always empty, whatever the env says, and the mocks tree is stripped from the bundle.
- If `auth` is mocked, every backend-calling feature (`onboarding`, `kyc`, `kycCapture`, `wallets`, `notifications`) must be mocked too (no real token exists). If `kyc` is mocked, `kycCapture` must be too (a real Sumsub session needs a real KYC start). Real `auth` with mocked downstream features is fine, e.g. `EXPO_PUBLIC_MOCK_FEATURES=kycCapture,notifications` runs real sign-in + onboarding + KYC start with a simulated Sumsub session.
- Screens and hooks contain no fake data. Each capability is a service in `src/types/domain.ts` (`auth`, `account`, `onboarding`, `kyc`, `kycCapture`, `wallets`, `notifications`); `src/services/index.ts` composes mock-or-real per feature (`src/services/compose.ts`). Only that file may import `src/mocks/**` (ESLint-enforced).
- Capabilities with no backend yet (`notifications`) have a real implementation that throws `FeatureUnavailableError`; the screen shows the normal error notice. `kycCapture` is real (Sumsub SDK); it also throws `FeatureUnavailableError` when the native module is missing (Expo Go, or a dev client built before the module was added).
- `npm run check:release-bundle` also fails on `__NEOS_DEV_ONLY__:<tool>` markers (dev-only tools under `src/dev/**`, loaded only through a `__DEV__`-guarded `require()`; none exist today).
- Send / receive / convert / add money / history / notifications / security are **not mocks**: they open honest "Not available yet" screens and never fake a result.
- A dev-only banner (`MOCK: wallets, kyc`) shows whenever anything is mocked. The long-press dev panel only shows controls for mocked features (KYC outcomes need `kyc`; expire/reset need `auth`).
- `npm run check:release-bundle` exports a production-like bundle with `EXPO_PUBLIC_MOCK_FEATURES=all` and fails if any `__NEOS_MOCK__:<feature>` marker is present (`node scripts/check-release-bundle.mjs --control` does a dev export that must find markers, proving the scan works). `npm run check:release-readiness` lists what would show "not available" in a real build.

## Demo mode (`auth,onboarding,kyc` mocked)

With these mocked, `services/index.ts` swaps in the demo implementation. Welcome's “Let’s begin” opens a blank **Create your NEOS account** form (email, password, confirmation), then routes directly to personal details. “Already have an account? Sign in” opens a distinct sign-in form (email/password) and routes through the existing lifecycle router. The forgot-password sheet explains the local preview; no email is sent. Both forms validate email format and a nonblank password; signup also requires matching passwords.

`mocks/demo.ts` centralizes identity, delays, KYC decisions, reset and failure simulation. The entered email is trimmed/lowercased and stored as `Profile.identity.email` in the existing SecureStore key **`neos.demo.v1`**. Setup, review and profile read this same identity. Passwords/confirmation stay only in the form/request's memory and are never persisted or logged. No camera permission or identity images are used. Phone edits remain in memory.

This is a **single local demo account**, not an account database or password-authentication system. Sign-out marks `neos.demo.signedOut`, retaining that account's lifecycle. Signing in with the same email resumes it; another email starts an unonboarded account and cannot inherit the previous account's KYC/activation. Creating an account always starts fresh. Restart restores a signed-in account but never signs a signed-out account in automatically. Reset removes both keys and returns to welcome with blank auth forms.

The exact fallback **`khalil@gmail.com`** is centralized in `DEMO_FALLBACK_EMAIL`. It is used only by internal seed authentication without input or to migrate the previous generated identity. A supplied valid email always takes precedence; the signup screen never pre-fills or locks the fallback. Previously persisted legacy seed profiles are migrated while preserving lifecycle state.

The happy path takes about two seconds to verify and is repeatable. In a **development build with `kyc` and/or `auth` mocked**, hold the NEOS header wordmark for 1.8 seconds on welcome, either auth form, home or a verification outcome screen to choose a KYC outcome, fail the next request, expire the session or reset. Set the outcome on welcome before authenticating; for an onboarded account it routes to the pending screen, where “Check for an update” applies the selected outcome. Reset clears the saved account email/lifecycle; sign-out preserves it for a later sign-in.

`MORE_INFO_REQUIRED` is the frontend name for the backend's `RESUBMISSION_REQUIRED` (mapped in `src/api/profileMapping.ts`). It is never sent to Spring Boot. Pending remains pending until an explicit check with a different scenario; no fake automatic approval is performed by the UI.

Identity capture is the Sumsub MobileSDK (`src/services/sumsub.ts`, `@sumsub/react-native-mobilesdk-module` 1.46.1): "Start verification" → `POST /api/kyc/start` → `POST /api/kyc/sdk-token` → the SDK's own document and selfie/liveness screens → `GET /api/profile` polling. The SDK result is never the decision (the Sumsub webhook is). The `kycCapture` mock replaces the SDK session with a timer (dev only). Native setup: `plugins/withSumsub.js` (Maven repository, ML Kit manifest merge, `FLAG_SECURE` on `com.sumsub.*` activities), `android.blockedPermissions` in `app.config.ts`, and the `patch-package` patch that removes token logging from the module's Android code. See `docs/api/SUMSUB_MOBILE_INTEGRATION_PLAN.md` and `docs/testing/ANDROID_DEVICE_TEST_GUIDE.md`. No unfinished financial action changes balances or creates transactions.

## Real API adapter

| Endpoint | Integration |
| --- | --- |
| `GET /api/auth/me` | Validate bearer session; obtain subject, username, email |
| `POST /api/onboarding/personal` | Exact DTO; persistent random installation UUID; OS platform; explicit terms acceptance |
| `POST /api/kyc/start` | Submit or explicitly recheck KYC; map returned status; APPROVED implies activation under the current backend transaction |

Set `EXPO_PUBLIC_MOCK_FEATURES=none` (or just the features still to be built) and configure the URLs below. The adapter uses system-browser authorization code + S256 PKCE, discovery, access/refresh tokens, encrypted native storage, refresh before expiry, one 401 refresh/retry, timeouts for API requests and friendly errors. No client secret is used. Sign-out removes local tokens and attempts refresh-token revocation; Keycloak's browser SSO session is separate.

**Required local Keycloak setup:** in realm `neos`, client `neos-mobile`, add the exact valid redirect URI `neos://auth`, and require PKCE S256. Keep it a public client with standard flow enabled. The user must have realm role CUSTOMER and an email claim. The repository's realm export is preserved; an existing running realm is not automatically modified. Use the same reachable issuer hostname on backend and device. The supplied Android port reversal makes `http://localhost:8081/realms/neos` reachable on both. Use HTTPS for deployed environments.

**Terms:** no approved terms document exists in the repository. Real onboarding is intentionally disabled until `EXPO_PUBLIC_TERMS_URL` points to an approved HTTPS document. Demo terms explicitly describe the preview and make no banking/legal promises. `EXPO_PUBLIC_TERMS_VERSION` must match `neos.terms.current-version` (currently `1.0`).

**Restoration limitation:** real mode stores only a subject-scoped onboarding checkpoint, never a cached ACTIVE authorization. After restart it validates the session and shows pending for an onboarded user; an explicit check calls the existing idempotent KYC start endpoint. On a new installation, a previously onboarded user may repeat the idempotent onboarding form. This is a documented bridge until read-only lifecycle endpoints exist. Do not use the home shell as an authorization boundary: backend authorization must remain authoritative. `AccountService.get` only exposes current session state; it does not invent an account endpoint. Suspended/closed routing is ready, but cannot be fetched until the backend exposes it.

## Environment

All `EXPO_PUBLIC_*` values are compiled into the app and **must not contain secrets**. Restart Metro after changes.

| Variable | Default / purpose |
| --- | --- |
| `EXPO_PUBLIC_MOCK_FEATURES` | `none`; `all`, or a list such as `wallets,kyc` (dev builds only) |
| `EXPO_PUBLIC_APP_ENV` | `development` (dev default), `staging`, `demo`, `production`. Anything but `production`, including unset, shows the Sandbox badge |
| `EXPO_PUBLIC_API_URL` | `http://localhost:8080` |
| `EXPO_PUBLIC_KEYCLOAK_ISSUER` | `http://localhost:8081/realms/neos` |
| `EXPO_PUBLIC_KEYCLOAK_CLIENT_ID` | `neos-mobile` |
| `EXPO_PUBLIC_REDIRECT_URI` | `neos://auth` |
| `EXPO_PUBLIC_TERMS_VERSION` | `1.0` |
| `EXPO_PUBLIC_TERMS_URL` | Approved HTTPS terms; required in real mode |
| `EXPO_PUBLIC_DEMO_KYC_RESULT` | `APPROVED`, `PENDING`, `REJECTED`, `MORE_INFO_REQUIRED` |
| `NEOS_ANDROID_JAVA_HOME` | Optional local JDK 17 path; not bundled |

## State routing

Unauthenticated → welcome (real auth: “Let’s begin” opens the Keycloak registration form via `prompt=create`, “Sign in” the login form via `prompt=login`; a stored token rejected at cold start is cleared silently). The “You’re verified” / “ready” screens appear only when the app observes KYC becoming APPROVED + ACTIVE in the current session (`acknowledgedAfterUpdate` in `src/navigation/state.ts`); a returning approved customer goes straight to home. Authenticated without onboarding → intro/details/review. Onboarded + NOT_STARTED → KYC introduction. PENDING/IN_REVIEW → pending. REJECTED → final (sign out only, no retry). Backend RESUBMISSION_REQUIRED → frontend MORE_INFO_REQUIRED → "Check for an update" (recapture only when `kyc` is mocked). APPROVED + ACTIVE → verified → ready → home. SUSPENDED/CLOSED always override KYC and route to account unavailable. Restored demo ACTIVE opens home. Transient form/capture progress is not persisted; no sensitive KYC state is stored.

## Build profiles & configuration

`eas.json` defines three build profiles:

- **development** — internal distribution, debug APK, `EXPO_PUBLIC_MOCK_FEATURES=all` and the local/dev URLs from `.env.example`.
- **staging** — internal distribution APK, `EXPO_PUBLIC_MOCK_FEATURES=none`, `https://REPLACE_ME-staging-...` placeholder URLs.
- **production** — store distribution, Android app bundle, `EXPO_PUBLIC_MOCK_FEATURES=none`, `https://REPLACE_ME-production-...` placeholder URLs.

**Mocks (`src/config.ts`, `src/mockFeatures.ts`, `src/services/index.ts`)** are active only when the build is a development build (`__DEV__`) **and** `EXPO_PUBLIC_MOCK_FEATURES` selects them. A staging/production build always resolves an empty mock set and never even parses the value, so unset, misspelled or non-dev fails closed. The mocks tree (`src/mocks/**`) is loaded with a guarded `require()` from `services/index.ts`, not a static import, so Metro/terser strips it from non-dev bundles; `npm run check:release-bundle` verifies this.

**Startup validation**: in a non-development build, `resolveConfig` (in `src/config.ts`) requires `apiUrl`, `issuer`, `clientId`, `redirectUri` and `termsVersion` to be present, requires `apiUrl`/`issuer` to be `https://`, and rejects any value still containing `REPLACE_ME`. A failure throws a descriptive `Error` (naming only the offending field, never a value) which `App.tsx` re-throws from `JourneyApp`'s render so the existing root `ErrorBoundary` shows it, instead of the app silently running with a broken/placeholder config. Before cutting a staging or production build, replace every `REPLACE_ME-...` placeholder in `eas.json` with real values.

## Checks

```powershell
npm run typecheck
npm run lint
npm test
npm run format:check
npx expo install --check
npx expo export --platform android
npm run check:release-bundle
npm run check:release-readiness
```

Automated domain tests cover lifecycle/restoration/reset, all demo outcomes, failure/retry, expiry, restricted states and optional-phone/back rules. See `VALIDATION.md` for actual execution evidence and remaining device checks.

Dependency audit currently reports 9 moderate transitive findings through Expo's Xcode build tooling / old uuid; no high or critical findings. npm's proposed automatic fix downgrades Expo incompatibly. No forced downgrade was applied; reassess with the next compatible SDK update.

## Next backend validation

1. Add an authoritative read-only current-customer endpoint including onboarding, KYC and account status, and replace the checkpoint/recheck bridge.
2. Done (phase 2): Sumsub SDK session, permissions, real capture, webhook-derived outcomes read from `GET /api/profile`, resubmission through a new SDK token. Remaining: the end-to-end physical-device run (`docs/testing/ANDROID_DEVICE_TEST_GUIDE.md`) and iOS.
3. Supply approved NEOS artwork, terms, support contact and French/Arabic translations; validate TalkBack/VoiceOver, RTL, larger text and physical iOS devices.
4. Connect balances, wallets and activity only when their backend contracts are ready. Replace each upcoming action individually; never infer ledger operations from this shell.

Authentication design follows the official [Expo authentication guide](https://docs.expo.dev/guides/authentication/) and [AuthSession API](https://docs.expo.dev/versions/latest/sdk/auth-session/).
