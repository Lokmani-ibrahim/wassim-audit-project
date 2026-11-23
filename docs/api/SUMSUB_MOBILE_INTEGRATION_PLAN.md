# Sumsub mobile SDK integration: feasibility and plan

- **Type:** read-only spike. No code, dependency or config was changed, and nothing was installed or run. This file is the only file created.
- **Date:** 2026-11-04
- **Branch:** `feat/mobile-sumsub-sdk`, created from `origin/integration` @ `185db52`. That commit includes PR #58 (`d9ef208`, `feat(kyc): expose secured Sumsub SDK token endpoint`) and PR #59. Not pushed. The upstream is unset, so a plain `git push` cannot reach `integration` by accident.
- **Inputs read:**
  - `docs/api/MOBILE_API_CONTRACT.md`
  - `docs/audits/BACKEND_DELTA_AUDIT_3.md`
  - `docs/testing/INTEGRATION_TEST_REPORT_PR55.md`
  - `mobile/src/mocks/registry.ts`, `mobile/src/mocks/kycCapture.ts`
  - `mobile/src/screens/Kyc.tsx`, `mobile/src/hooks/useJourney.ts`, `mobile/src/services/{real,compose,index,unavailable}.ts`
  - `mobile/src/navigation/state.ts`, `mobile/src/lock/*`
  - `mobile/app.config.ts`, `mobile/plugins/withRecentsPrivacy.js`
  - PR #58 source and tests
- **External verification:**
  - npm registry metadata, read with `npm view`.
  - The published npm tarball of `@sumsub/react-native-mobilesdk-module@1.46.1`. I downloaded it to a scratch folder outside the repo, checked it against the registry's `sha512` integrity value, and read its files. Nothing was executed.
  - The core Android artifact `com.sumsub.sns:idensic-mobile-sdk:1.46.0` (POM and AAR manifest) from Sumsub's public Maven.
  - Sumsub's official docs and the module's GitHub issues. Sources are listed in §9.
- **Convention:** anything I could not confirm from code, an artifact or an official page is marked **UNVERIFIED**.

---

## Part A: the new backend endpoint `POST /api/kyc/sdk-token`

### A.1 Shape

| Item | Behaviour | Code |
|---|---|---|
| Route | `POST /api/kyc/sdk-token`. No body; the identity comes only from the JWT `sub`. | `kyc/api/KycSdkTokenController.java:44` |
| Role | `@PreAuthorize("hasRole('CUSTOMER')")`, and `anyRequest().authenticated()` in the security chain | `KycSdkTokenController.java:45`, `identity/security/SecurityConfig.java:30-45` |
| 200 body | `{"token": string, "expiresInSeconds": number}` | `KycSdkTokenController.java:70-74` |
| Caching | `Cache-Control: no-store` + `Pragma: no-cache` | `KycSdkTokenController.java:60-61` |
| Applicant identity | Sumsub `userId` = **NEOS `userId`** (UUID string). This is the same value used as `externalUserId` when `kyc/start` creates the applicant. The backend checks that Sumsub's response echoes the same `userId`; on a mismatch it fails with `IllegalStateException`. | `adapter/sumsub/SumsubClient.java:141-212` (request `:148-152`, mismatch check `:205-209`), applicant `externalUserId` `:254-255`; `SumsubKycProvider.java:109-113` |
| Sumsub call | `POST /resources/accessTokens/sdk` with `{ttlInSecs, userId, levelName}`, HMAC-signed | `SumsubClient.java:146-194`, `:214-219` |
| Level | `SUMSUB_LEVEL_NAME`, default `basic-kyc-level`. Must exist in the Sumsub account and must match the webhook's `levelName`. | `application.yaml:42`, `SumsubClient.java:151` |
| TTL | `SUMSUB_SDK_TOKEN_TTL_SECONDS`, default **600**. It is sent to Sumsub as `ttlInSecs`, and the **same configured value** is returned as `expiresInSeconds`; Sumsub's response does not provide it. | `application.yaml:43`, `SumsubKycProvider.java:115-118` |
| Idempotency | None. Every call mints a new Sumsub token; there is no server-side cache. Whether earlier tokens stay valid until their TTL: **UNVERIFIED** (Sumsub side). | `KycSdkTokenService.java:132-138` |
| Rate limit | **None.** Each call makes one outbound Sumsub API call. The only rate limiter in `src/main` is the recipient lookup limiter. | grep: `ledger/RecipientLookupRateLimiter.java` only |
| DB transaction | None held during the Sumsub call (comment `:127-131`). The service has no `@Transactional`. | `KycSdkTokenService.java:126-138` |
| Token logging | **Not logged by backend code.** There is no logger in `KycSdkTokenService`, `KycSdkTokenController`, `SumsubKycProvider` or the `SumsubClient` token path (grep). Exception messages on this path do not contain the token. | grep `Logger\|log\.` in `kyc/**`: only `SumsubWebhookController` |
| Outbound timeout | No explicit connect or read timeout on the Sumsub `RestClient` (`SumsubClient.java:39-53`). The effective default is **UNVERIFIED**. | – |

### A.2 Decision table (checked in this order)

| # | Condition | HTTP | Body / reason | Code |
|---|---|---|---|---|
| 0 | No or invalid JWT | **401** | Spring Security, empty body | `SecurityConfig.java` |
| 0b | JWT without `CUSTOMER` | **403** | Spring Security (`AccessDeniedException`), empty body | `KycSdkTokenController.java:45` |
| 1 | Blank `sub` | 401 | "Authenticated customer required" (defensive; not reachable with a valid JWT) | `KycSdkTokenService.java:43-49` |
| 2 | No NEOS user for `sub` (not onboarded) | **409** | `KycOnboardingRequiredException`: "Personal onboarding must be completed before starting KYC" | `:56-60`, `KycOnboardingRequiredException.java:6-10` |
| 3 | Account not `PENDING_KYC`/`ACTIVE` (that is, `SUSPENDED`, `CLOSED`) | **403** | "Customer account is not eligible for KYC" | `:70-78` |
| 4 | No `kyc_status` row | **409** | same onboarding message as #2 | `:86-90` |
| 5 | KYC status not in {`PENDING`, `IN_REVIEW`, `RESUBMISSION_REQUIRED`} | **409** | "KYC is not eligible for an SDK session" | `:92-101` |
| 6 | Provider ≠ `SUMSUB` (legacy `MOCK` rows) or no applicant id | **409** | "A valid Sumsub applicant is required" | `:109-116` |
| 7 | `initialFinalRejectionLocked` or `manualReviewRequired` | **403** | "KYC requires administrative review" | `:118-124` |
| 8 | Sumsub HTTP error, network error, invalid token or user id mismatch in the adapter | **500** | No `@ControllerAdvice`. A `RestClientException` or `IllegalStateException` becomes Boot's default 500. | `SumsubClient.java:175-209`, `SumsubKycProvider.java:100-113` |
| 9 | Session returned but token blank or `expiresInSeconds <= 0` | **502** | "Sumsub returned an invalid SDK session". In practice reachable only with TTL ≤ 0 (the adapter already rejects a blank token at #8; prod startup rejects TTL ≤ 0). | `KycSdkTokenService.java:143-151` |
| – | Otherwise | **200** | `{token, expiresInSeconds}` | – |

Error body: a `ResponseStatusException` goes through Boot's `/error` JSON. In the **dev** profile, devtools adds `message` and `trace` (INTEGRATION_TEST_REPORT_PR55 ISSUE-3). With the **prod** defaults, `message` is omitted (Boot default, **UNVERIFIED** for this app: no `server.error.*` in `src/main/resources`). **The app can switch only on the HTTP status.**

### A.3 Per state

| KYC status | Account `PENDING_KYC` | Account `ACTIVE` | Account `SUSPENDED` / `CLOSED` |
|---|---|---|---|
| no profile (not onboarded) | 409 (#2) | – | – |
| `NOT_STARTED` | **409** (#5). The app must call `kyc/start` first. | 409 | 403 (#3) |
| `PENDING` | **200** | 200 (not normal; possible for a re-verified user) | 403 |
| `IN_REVIEW` (never set today: `KycStatusEntity.java:142-144` has no caller) | 200 | 200 | 403 |
| `RESUBMISSION_REQUIRED` | **200**: the SDK can handle re-upload | 200 (not reachable: the decision suspends ACTIVE accounts, `SumsubDecisionService.java:212-221`) | **403**: a **previously approved** user who gets RETRY is `SUSPENDED` (`:218`) and **cannot get a token** |
| `APPROVED` | 409 | **409** | 403 |
| `REJECTED` (final) | **409** (#5; the lock check #7 is never reached because #5 fires first) | 409 | 403 |

**Does `RESUBMISSION_REQUIRED` get a token?** Yes, but only while the account is `PENDING_KYC`, which covers a first-time applicant. A customer who had been approved and is then asked to resubmit is suspended by the decision service (`SumsubDecisionService.java:212-221`) and gets **403**. The app routes them to the restricted screen first anyway (`mobile/src/navigation/state.ts:8`).

### A.4 Tests in PR #58

- `src/test/java/com/fintech/kyc/KycSdkTokenServiceTest.java`: 12 unit tests.
  - Eligible: PENDING ok; IN_REVIEW renew ok; RESUBMISSION ok.
  - Rejected: NOT_STARTED, APPROVED, REJECTED, SUSPENDED, CLOSED, missing onboarding, legacy MOCK applicant, missing applicant id, malformed provider session.
- `src/test/java/com/fintech/kyc/KycSdkTokenControllerIntegrationTest.java`: 5 MockMvc tests with the **service mocked** (`@MockitoBean`, `:44-45`): 401, 403 without role, 200 + `no-store`/`no-cache` headers, 409, 403. `@SpringBootTest`, so it uses the dev DB (ISSUE-5 in the PR #55 report).
- **Not covered:** the review-lock 403 branch (#7); Sumsub HTTP or network failure (→ 500); user id mismatch through the full service; a test asserting the token is absent from logs; a live sandbox call (branch `origin/test/sumsub-sdk-http-sandbox` exists; not reviewed here).

### A.5 Gaps vs what the mobile app needs

| # | Gap | Impact on mobile | Ask |
|---|---|---|---|
| G1 | No `errorCode`; prod omits `message`. Three different 409s (#2/#4, #5, #6) and three different 403s (#0b, #3, #7) are indistinguishable. | The app cannot tell "start KYC first" from "already decided" from "broken applicant". | Add a stable `errorCode` (e.g. `KYC_NOT_STARTED`, `KYC_NOT_ELIGIBLE`, `APPLICANT_MISSING`, `ACCOUNT_NOT_ELIGIBLE`, `KYC_REVIEW_LOCKED`). |
| G2 | A previously approved customer with `RESUBMISSION_REQUIRED` is `SUSPENDED` → 403. | That customer cannot re-upload from the app. | Product decision: allow `SUSPENDED` + `RESUBMISSION_REQUIRED` for this endpoint only? Or keep it support-only and say so in the contract. |
| G3 | `PENDING` is ambiguous: "applicant created, nothing uploaded" vs "submitted, under review". `IN_REVIEW` is never set (no `applicantPending` webhook handling). | The pending screen cannot tell the user whether to continue or wait. The plan shows both actions (§B.3). | Set `IN_REVIEW` from the Sumsub `applicantPending` webhook, or expose a submitted flag. |
| G4 | No rate limit, and each call hits Sumsub. | A bugged or looping client can burn the Sumsub API quota (limits **UNVERIFIED**). | Per-user limit (e.g. 10/min) with 429, like the recipient lookup. |
| G5 | Sumsub failures → **500**, not 502/503; no outbound timeout configured. | The app treats 500 as "unexpected". With no backend timeout, a request can hang until the app's 15 s abort. | Map provider failures to 502/503; set connect/read timeouts. |
| G6 | `kyc/start` still returns `verificationMode: "NON_PROD"` hard-coded (`KycStartService.java:111-121`). | None if the app ignores it, as it does today. | Remove or fix the field. |
| G7 | Local `.env` has no `SUMSUB_WEBHOOK_ENABLED` / `SUMSUB_WEBHOOK_SECRET` (Audit 3 §5). | No decision ever reaches a locally created applicant, so end-to-end tests stay at `PENDING`. | Backend developer: webhook + tunnel setup (§B.5). |
| G8 | `docs/api/MOBILE_API_CONTRACT.md` §4 and §7.2 still say "no SDK token endpoint" and "resubmission impossible". | Stale contract. | Update the contract with §A.1–A.3. |
| G9 | Level content (which steps: ID, selfie, liveness, video, geolocation, NFC) is defined only in the Sumsub dashboard. | It decides which permissions the app must keep (§B.2) and the UX copy. | Share the `basic-kyc-level` step list for sandbox and prod. |

---

## Part B: mobile integration plan

### B.1 Which SDK

| | **Sumsub React Native module** (recommended) | Sumsub WebSDK in a WebView | WebSDK in the system browser |
|---|---|---|---|
| Package | `@sumsub/react-native-mobilesdk-module` **1.46.1** (npm `latest`, published 2026-09-01, MIT). Native core: Android `com.sumsub.sns:idensic-mobile-sdk:1.46.0`, iOS pod `IdensicMobileSDK` `=1.46.1` (from the tarball's `android/build.gradle` and podspec). | `@sumsub/websdk-react` **2.9.1** (npm), plus `react-native-webview` (a new native dependency) | None in the app. It needs a backend-generated WebSDK link (no endpoint today). |
| Capture quality | Native camera (CameraX 1.5.3 per the POM), liveness, document auto-capture | Browser `getUserMedia` inside a WebView. Android needs `onPermissionRequest` plumbing; Sumsub notes iOS WebView camera limits on older versions ([WebSDK docs](https://docs.sumsub.com/docs/get-started-with-web-sdk)). | Good camera; the user leaves the app. Return handling and app-lock interplay are messy. |
| Token | The same `POST /api/kyc/sdk-token` token, with `tokenExpirationHandler` | Access token, same concept (whether the same `/resources/accessTokens/sdk` token is accepted: **UNVERIFIED**) | Link/permalink, which needs a new endpoint |
| Expo | No official config plugin ([issue #11](https://github.com/SumSubstance/react-native-mobilesdk-module/issues/11), open). A **local** plugin is needed (§B.2). | `react-native-webview` has Expo support | – |
| Verdict | **Recommended.** It is the only option with native liveness and matches PR #58 directly. | Fallback if the native module cannot build | Last resort |

**Compatibility with RN 0.83.10 + Expo SDK 55 + New Architecture** (`mobile/package.json`; `android/gradle.properties` `newArchEnabled=true`; Gradle wrapper **9.0.0**; RN 0.83 `minSdk 24`, `compile/targetSdk 36`, AGP 8.12.0, Kotlin **2.1.20** per `node_modules/react-native/gradle/libs.versions.toml`):

| Topic | Finding | Status |
|---|---|---|
| peerDependency | `react-native >=0.60.0-rc.0 <1.0.x` (tarball `package.json`) | ok |
| Architecture | **Legacy native module.** `NativeModules.SNSMobileSDKModule` + `NativeEventEmitter` (`index.js:1-3, 51`), Java `ReactContextBaseJavaModule` (`SNSMobileSdkReactNativeModule.java:45`), no `codegenConfig`. On RN 0.83 it runs only through the New Architecture **interop layer**. Sumsub docs say nothing about the New Architecture. | **UNVERIFIED** until a dev build launches it |
| Gradle 9 | [Issue #27](https://github.com/SumSubstance/react-native-mobilesdk-module/issues/27) "incompatible with Gradle 9.0.0 (Expo SDK 55 / RN 0.83)" is still open, reported on 1.40.2 (`lintOptions`, `destinationDir`). **The 1.46.1 tarball already contains both guards** (`android.hasProperty('lint') ? lint {…} : lintOptions {…}`; `hasProperty('destinationDirectory') ? … : destinationDir`). It also still uses `android.libraryVariants` and the `archives` configuration (deprecated, still present on AGP 8.12 / Gradle 9 per my reading). | Likely fixed; **UNVERIFIED** until `assembleDebug` |
| Kotlin | The core SDK POM pulls `kotlin-stdlib 2.1.21`; the docs say "Kotlin 2.1.21 or higher" ([Android docs](https://docs.sumsub.com/docs/get-started-android)). The project compiles with 2.1.20; same minor version, so metadata should be readable. | **UNVERIFIED** |
| Duplicate classes | Issue #11 reports a bouncycastle `bcprov-jdk15to18` vs `bcprov-jdk18on` duplicate-class failure with Expo. | Risk; check at the first build |
| Single instance | The JS keeps `_currentInstance` and rejects a second `launch()` (`index.js:45-47`); `reset()` clears it (`:286-288`). The docs say "Only one SDK instance can run at a time". | Handle in the service |
| TypeScript | No `.d.ts` in the package; types issues #7 and #18 are open. | A local declaration file is needed |
| Maintenance | The GitHub repo shows a single commit, 23 open issues and no visible maintainer replies (one commenter calls it "abandoned"). npm releases are regular (1.45.1 on 2026-07-15, 1.46.1 on 2026-09-01). | Risk: fixes come only through npm releases |
| iOS | Pod `IdensicMobileSDK`, iOS ≥ 13 (podspec), Podfile `source 'https://github.com/sumsub/Specs.git'` + CDN, Info.plist `NSCameraUsageDescription`, `NSMicrophoneUsageDescription`, `NSPhotoLibraryUsageDescription`, `NSLocationWhenInUseUsageDescription` ([RN docs](https://docs.sumsub.com/docs/react-native-module)). Open [issue #29](https://github.com/SumSubstance/react-native-mobilesdk-module/issues) "iOS 26 crash – nil modal view controller". | Later phase (Android first) |

**API used by the app** (from `index.js` and the docs):
- `SNSMobileSDK.init(token, expirationHandler).withHandlers({onStatusChanged, onEvent}).withLocale('en').withDebug(false).withAnalyticsEnabled(…).withAutoCloseOnApprove(0).build().launch()`.
- `launch()` resolves `{success, status, errorType?, errorMsg?}` (Android `getResult`, `SNSMobileSdkReactNativeModule.java:317-324`).
- Statuses ([RN docs](https://docs.sumsub.com/docs/react-native-module)): `Ready`, `Failed`, `Initial`, `Incomplete`, `Pending`, `TemporarilyDeclined`, `FinallyRejected`, `Approved`.
- Error types: `Unknown`, `InvalidParameters`, `Unauthorized`, `InitialLoadingFailed`, `ApplicantNotFound`, `ApplicantMisconfigured`, `InitializationError`, `NetworkError`, `UnexpectedError`.
- **Token expiration:**
  - The native side emits `onTokenExpired`. JS calls `tokenExpirationHandler()` and passes the result to `updateAccessToken` (`index.js:28-40`).
  - Android **blocks up to 120 s** waiting for the new token (`SNSMobileSdkReactNativeModule.java:137-165`). On failure JS sends `null`, and the SDK ends with `Unauthorized`.
  - Open [issue #22](https://github.com/SumSubstance/react-native-mobilesdk-module/issues/22): "Refresh token handler not working same on Android and iOS" (RN 0.79.3, module 1.35.1).

**Token-handling findings in the SDK (important for "never log the token"):**

1. **The Android bridge logs renewed tokens to logcat:**
   - `Log.d(LOG_TAG, "Sumsub: updateAccessToken " + accessToken …)` (`SNSMobileSdkReactNativeModule.java:61`)
   - `Log.d(LOG_TAG, "Sumsub: Received new token: " + newAccessToken …)` (`:163`)

   This happens on every **renewal**; the initial token is not logged by the bridge. `Log.d` is not stripped from release builds unless R8 runs with an `-assumenosideeffects` rule, and this project has minify **off** by default (`android/app/build.gradle:69, 118`). Other apps cannot read logcat (Android ≥ 4.1), but `adb logcat` and bug reports can. Options, in order of preference:
   - (a) a `patch-package` patch removing the token from both lines (adds a dev dependency, needs approval);
   - (b) enable R8 with `-assumenosideeffects class android.util.Log { public static int d(...); public static int v(...); }` (proguard rules come from the generated `android/`, so this needs a config-plugin change too);
   - (c) accept the risk for sandbox only and fix it before production.

   Report it to Sumsub as well.
2. **`launch()`'s `errorMsg` can contain the token.** The official example is `"Unauthorized access with accessToken=[your access token]"` ([RN docs](https://docs.sumsub.com/docs/react-native-module)). The app must **never log, display, persist or send** `errorMsg`; switch only on `errorType`.
3. With `withDebug(true)`, iOS raises the SDK log level to Debug and `NSLog`s every SDK message (`ios/MobilesdkModule.m:58, 71-72`). Use `withDebug(false)` everywhere except a local dev toggle, and **do not register `onLog`** in release.
4. The JS wrapper `console.error(err)`s a failed renewal (`index.js:37`). Our handler must reject with an error whose message never includes the token. The current `ApiError` messages are safe; keep it that way.

### B.2 Native changes (all through config plugins and `app.config.ts`; generated `android/` stays untouched, as it is git-ignored, `mobile/.gitignore:5`)

| Change | Needed? | How (no manual `android/` edits) |
|---|---|---|
| Sumsub Maven repo `https://maven.sumsub.com/repository/maven-public/` in `allprojects.repositories` | **Yes.** The module's own `repositories {}` (which uses `…/maven-releases/`) does not apply when the **app** resolves the transitive AAR. The docs require it in the root `android/build.gradle` ([RN docs](https://docs.sumsub.com/docs/react-native-module)). I confirmed `maven-public` serves `idensic-mobile-sdk:1.46.0` (POM and AAR: HTTP 200). | New local plugin `mobile/plugins/withSumsub.js` using `withProjectBuildGradle`. Same pattern as `withRecentsPrivacy.js`: idempotent marker, throw if the `allprojects { repositories {` anchor is missing, export the pure transform for unit tests. A community version of this plugin exists in issue #11 (antoniogoulao). |
| Camera / microphone permissions | **No manual add.** The core AAR manifest already declares `CAMERA`, `RECORD_AUDIO`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `NFC`, `ACCESS_NETWORK_STATE`, `INTERNET`, `WRITE_SETTINGS` (protected), plus `uses-feature camera required="true"` and `nfc required="false"`. These are merged into the app. | Strip the permissions the level does not need (likely location, NFC, `WRITE_SETTINGS`, and `RECORD_AUDIO` unless the level uses video; depends on G9). Use Expo's `android.blockedPermissions` in `app.config.ts`, which writes `tools:node="remove"`. Check the merged manifest after prebuild (**UNVERIFIED** that every merged entry is removed). Optional: `withAndroidManifest` to set `camera required="false"` so Play does not filter out camera-less devices. Runtime prompts are made by the SDK itself (**UNVERIFIED**). |
| `minSdk` | **No.** AAR `minSdkVersion 23` ≤ RN 0.83 default 24 | – |
| `compileSdk` / `targetSdk` | **No.** The SDK needs targetSdk ≥ 34 (CameraX, changelog 1.29.0); the project uses 36. | – |
| Kotlin | Probably no (2.1.20 vs 2.1.21; see B.1) | If the build fails: `expo-build-properties` `android.kotlinVersion` (new dependency) or the plugin |
| ProGuard / R8 | **No for the SDK.** The AAR ships `proguard.txt` (consumer rules, verified), and the docs say "You do not need to add it manually". | Only for the log-stripping option (B.1 finding 1b) |
| Packaging excludes | Only if the NFC module is added (docs: `META-INF/versions/9/OSGI-INF/MANIFEST.MF`) | Not planned |
| Recents preview of SDK screens | **Gap.** `withRecentsPrivacy` covers only `MainActivity`; the SDK runs its own Activity, which shows ID documents. Whether the SDK sets `FLAG_SECURE` itself: **UNVERIFIED**. | Check on the device first. If it leaks, an `AndroidManifest`/theme approach is unlikely to work for a third-party Activity. Escalate to Sumsub. |
| iOS (later) | Podfile sources and Info.plist strings | `withPodfile` (or `withDangerousMod`) in the same `withSumsub.js` for `source` lines; `ios.infoPlist` in `app.config.ts` for the four usage strings |
| JS dependency | `@sumsub/react-native-mobilesdk-module@1.46.1` (exact pin) | Needs approval. The native build must then be redone (see the native rebuild in the PR #55 report). |

### B.3 App flow and states

Today: `kycIntro → document → selfie → verify()`, where `verify()` calls `kyc.submit()` = `POST /api/kyc/start` (`Kyc.tsx:33-75`, `useJourney.ts:181-196`), and the capture screens are timers.

Target:

```
kycIntro ── "Start verification" ──► kyc.submit()            POST /api/kyc/start  (NOT_STARTED → PENDING; idempotent)
                                     kycCapture.verify()      POST /api/kyc/sdk-token → launch SDK (token in memory only)
                                       │ tokenExpirationHandler → POST /api/kyc/sdk-token again (≤ 15 s, native waits ≤ 120 s)
                                       ▼
                           SDK closes → map result (below) → GET /api/profile → route by backend status
                                                                         │
                         pending ◄───────────────────────────────────────┘  poll GET /api/profile with backoff
```

- The `document` and `selfie` routes and the simulated capture UI in `Kyc.tsx` go away (they stay only in the mock). `kycIntro`'s button runs `start + verify`.
- Wrap `launch()` in `withAutoLockSuppressed(...)` (`mobile/src/lock/suppress.ts:22`). The SDK Activity backgrounds the RN Activity, and a session longer than the lock timeout would otherwise show the unlock prompt on return (TC-AUTH-10 shows the background lock at ≥ 60 s).
- Keep the token **in memory only**: no SecureStore, no state persistence, no logs, no analytics, no error messages.

**SDK result → app action.** The SDK result is **never** the KYC decision; only `GET /api/profile` is.

| `launch()` result | App action |
|---|---|
| `success: true`, status `Pending` / `Approved` / `TemporarilyDeclined` / `FinallyRejected` | "Submitted": go to `pending` and start polling. Show no decision from the SDK status. |
| status `Initial` / `Incomplete` (user closed early) | Back to `pending` with **"Continue verification"** (relaunch with a fresh token) + "Check status" |
| `Failed` + `errorType: Unauthorized` | Fetch a new token once and relaunch automatically; if that fails, "Couldn't start verification, try again" |
| `Failed` + `NetworkError` / `InitialLoadingFailed` | Retry notice ("Check your connection") |
| `Failed` + `ApplicantNotFound` / `ApplicantMisconfigured` / `InvalidParameters` / `InitializationError` / `UnexpectedError` / `Unknown` | Generic error + "Contact support"; record `errorType` only (never `errorMsg`) |
| `launch()` rejects ("another instance is in use") | `SNSMobileSDK.reset()` and one retry (dev reloads) |
| `sdk-token` 409 | Re-read `GET /api/profile` and route (most likely already decided or not started) |
| `sdk-token` 403 | Re-read the profile: `SUSPENDED`/`CLOSED` → restricted screen; otherwise "contact support" (review lock) |
| `sdk-token` 500 / 502 / timeout | "Verification is temporarily unavailable"; retry button, no auto-loop |

**Status polling** (`GET /api/profile`, read-only):
- Backoff 5 s → 10 s → 20 s → 30 s, then every 60 s. Stop after ~15 min of foreground time on the pending screen, then show "Check status" only.
- Poll only while the pending screen is focused and the app is active. Reset the schedule on foreground and on manual "Check status".
- Pause on 401 (the session flow takes over). Back off on 5xx/network errors.
- Implement it as a pure scheduler (unit-testable) + a hook.
- Today `useJourney`'s foreground re-check applies the fresh profile only on `home` (`useJourney.ts:121-135`), so the pending screen needs this hook. It also fixes ISSUE-6-like staleness for KYC.

**Per backend status** (mapping in `api/profileMapping.ts:6-15`, routing in `navigation/state.ts`):

| Status | Screen | Actions |
|---|---|---|
| `NOT_STARTED` | kycIntro | Start verification |
| `PENDING` / `IN_REVIEW` | pending ("We're reviewing…") + polling | **Continue verification** (relaunch SDK) + Check status. Both are needed because of G3. |
| `RESUBMISSION_REQUIRED` (`MORE_INFO_REQUIRED`), account `PENDING_KYC` | moreInfo | **"Update documents"** → sdk-token → SDK (the SDK shows the rejection reasons) → pending + polling. This replaces today's "check status only" (`Kyc.tsx:233-244`). |
| `RESUBMISSION_REQUIRED` + `SUSPENDED` | restricted (existing, `state.ts:8`) | Sign out / support (G2) |
| `REJECTED` | rejected, **final** | No SDK, no retry (backend 409 anyway) |
| `APPROVED` + `ACTIVE` | approved → ready → home | Wallets may show `PROVISIONING`; existing handling |

**Token renewal:** `tokenExpirationHandler = () => services.kycCapture.renewToken()`, which is `POST /api/kyc/sdk-token` through the existing `api` client:
- It gets the 401 → refresh → retry-once logic and the 15 s timeout (`api/client.ts:4-8, 58-70`).
- It resolves the string, or rejects with an error that has no token in it.
- If the Keycloak session has expired during the SDK session: reject → the SDK ends `Unauthorized` → the journey's normal session-expired path runs.

### B.4 Mock system

- **Interface change:**
  - Today: `kycCapture: { capture(kind): Promise<CaptureResult> }` (`types/domain.ts:95`).
  - Target: `kycCapture: { verify(): Promise<KycSdkOutcome> }` with `KycSdkOutcome = { kind: 'submitted' | 'incomplete' | 'failed'; errorType?: string }`, where `errorType` is a closed union mapped from Sumsub's list and `errorMsg` is dropped at the boundary.
  - Drop `CaptureKind` and `CaptureResult`.
- **Real implementation:** a new `mobile/src/services/sumsub.ts`.
  - `require('@sumsub/react-native-mobilesdk-module')` lazily, guarded by `NativeModules.SNSMobileSDKModule` presence. When the module is absent (Expo Go, or a dev client built before the native rebuild), throw `FeatureUnavailableError('kycCapture')`.
  - Hosts `renewToken`.
  - Plug into `realServices` in place of `unavailableServices.kycCapture` (`services/real.ts:323`, `services/unavailable.ts:6-9`).
  - Add a local `mobile/src/types/sumsub-mobilesdk.d.ts`.
- **Mock** (`src/mocks/kycCapture.ts`): keep `KYC_CAPTURE_MARKER`. `verify()` waits and returns a scripted outcome (`submitted` by default, plus `incomplete` and `failed` through `DemoControls`). Never calls the backend.
- **Composition rules** (`mockFeatures.ts`):
  - Add `kycCapture` to `BACKEND_FEATURES`: it now needs the bearer token for `sdk-token`, so mocked `auth` forces mocked `kycCapture`.
  - New consistency rule: **mocked `kyc` ⇒ mocked `kycCapture`.** A real SDK against a mocked KYC start has no real applicant.
  - The reverse (real `kyc` + mocked `kycCapture`) stays allowed and **must be labelled** "does not submit to Sumsub". It leaves a real applicant at `PENDING` with no documents (Audit 3 M2).
- **Registry** (`mocks/registry.ts:43-49`):
  - `kycCapture`: `fakes: 'Sumsub SDK session (scripted result; no camera, nothing submitted)'`, `realAvailable: true`, `replacedBy: 'Sumsub MobileSDK (RN module 1.46.1) via POST /api/kyc/sdk-token'`.
  - Also fix the stale `kyc.replacedBy` (it still says "SDK-token endpoint, not implemented"; Audit 3 M1).
- **Release bundle:** `npm run check:release-bundle` stays the gate (0 `__NEOS_MOCK__` markers; `--control` must find them). Also:
  - `release-readiness.ts` will now count `kycCapture` as real.
  - Add a test that `sumsub.ts` never references `errorMsg` in logs or UI. A grep-style test like the existing marker scan is enough.

### B.5 Testing

- **Unit tests** (`tsx --test`, no device):
  - SDK result → `KycSdkOutcome` mapping, including `errorMsg` being dropped.
  - Polling scheduler (intervals, stop, reset).
  - `renewToken` success, 401-refresh and failure, with no token in the error message.
  - Compose/consistency rules (`tests/mocks.test.ts`).
  - Registry exhaustiveness.
  - The routing table for the per-status matrix (§B.3).
  - The `withSumsub.js` transform (idempotent, anchor missing → throw), like `addToOnCreate`.
- **Native build check** (first milestone, before any UI work):
  - `expo prebuild --clean` → `gradlew app:assembleDebug` (JDK 17, x86_64).
  - Confirm Gradle 9, Kotlin, duplicate classes and the New-Arch interop by launching the SDK from a dev screen with a real token.
  - Inspect `app/build/intermediates/merged_manifests` for the permission set.
  - Run `adb logcat -s SNSRNModule` during a renewal to confirm finding 1 (only redacted output goes in any report).
- **Emulator camera:**
  - The AVD `Pixel_6` currently has `hw.camera.back = virtualscene` and `hw.camera.front = emulated` (`~/.android/avd/Pixel_6.avd/config.ini:15-16`).
  - For a real document or selfie, start with `emulator -avd Pixel_6 -camera-back webcam0 -camera-front webcam0`, or set both to `webcam0` in the AVD config. That changes the AVD, not the repo.
  - Liveness on an emulator webcam may be rejected by Sumsub's anti-spoofing (**UNVERIFIED**). Plan one run on a physical device.
- **Sumsub sandbox:**
  - Applicants created with sandbox app tokens are not auto-reviewed. A decision has to be triggered in the Sumsub dashboard (exact sandbox "simulate review" flow: **UNVERIFIED**).
  - The decision reaches NEOS only through the webhook, and the parser rejects `testMode: true` payloads (Audit 3 §5.4).
- **Needs the backend developer** (webhook):
  - Set `SUMSUB_WEBHOOK_ENABLED=true` and `SUMSUB_WEBHOOK_SECRET` (local `.env`).
  - Set up a public HTTPS tunnel to `/api/webhooks/sumsub`.
  - Register the `applicantReviewed` webhook (HMAC_SHA256_HEX) in the sandbox dashboard.
  - Make sure `SUMSUB_LEVEL_NAME` matches the dashboard level.

  Without these, test the app up to "submitted → pending (polling)" only. Approval, RETRY and RED stay untestable end to end (the same blocker as TC-KYC-07…14 in the PR #55 report).
- **Test cases to add to `INTEGRATION_TEST_PLAN.md`:**
  - sdk-token per status and per account (§A.3);
  - SDK cancel → continue;
  - token expiry mid-session (set `SUMSUB_SDK_TOKEN_TTL_SECONDS` low on a local backend);
  - app background during the SDK (no lock prompt);
  - resubmission loop;
  - Formance or Sumsub down;
  - no token in logcat or app logs.

### B.6 Risks, effort, open questions

**Risks**

| Risk | Likelihood / impact | Mitigation |
|---|---|---|
| The native module fails on Gradle 9 / New Arch interop / Kotlin 2.1.20 / duplicate classes | Medium / **blocks** | Build spike first (1 day). Fallback: WebSDK in a WebView. |
| Renewed SDK token in logcat (bridge `Log.d`, B.1 finding 1) | Certain (renewal path) / Medium | patch-package or R8 strip; report to Sumsub; short TTL |
| Token in `errorMsg` (Sumsub's own example) | Certain on `Unauthorized` / Medium | Drop `errorMsg` at the service boundary, with a test |
| Module repo looks unmaintained (1 commit, no maintainer replies) | High / Medium | Pin an exact version; budget for local patches |
| Extra merged permissions (location, NFC, `WRITE_SETTINGS`, `RECORD_AUDIO`) and the camera `required=true` feature | Certain / Medium (Play review, Data safety form) | `blockedPermissions` per level content (G9) |
| SDK screens visible in the Android recents preview | **UNVERIFIED** / Medium | Test on device; escalate to Sumsub |
| App lock or foreground re-check during an SDK session | Medium / Low | `withAutoLockSuppressed` |
| Known issues: Android "loading forever" (#21), refresh-handler differences (#22), iOS 26 crash (#29) | Unknown / Medium | Device testing; Android first |
| Bigger APK (core AAR alone is 7.0 MB, plus CameraX, LiteRT, OkHttp etc.) | Certain / Low | Measure after the build (currently 65 MB debug x86_64) |
| No end-to-end decision locally (G7) | Certain / High for testing | Backend webhook setup |

**Effort (Android first)**

| Work item | Size |
|---|---|
| Native build spike + `withSumsub.js` plugin + permission trimming + token-log decision | **M** |
| Service layer (`sumsub.ts`, types, `renewToken`, result mapping) + mock/registry/compose rework | **S–M** |
| Flow: replace capture screens, pending "continue", moreInfo "update documents", polling hook, error copy (en locale) | **M** |
| Unit tests + integration-plan cases + contract doc update | **S** |
| iOS (Podfile plugin, Info.plist, device test) | **M** (later) |
| **Total, Android** | **M–L**, dominated by the native build risk |

**Open questions for the backend developer**

1. G1: can the endpoint (and `kyc/start`) return a stable `errorCode`? Which values?
2. G2: should a previously approved customer in `RESUBMISSION_REQUIRED` + `SUSPENDED` get a token, or is that support-only?
3. G3: will `IN_REVIEW` be set from the `applicantPending` webhook (or another signal), so the app can hide "Continue verification" after submission?
4. G4: rate limit for `sdk-token` (value, 429 body)?
5. G5: map Sumsub failures to 502/503, and add Sumsub HTTP timeouts?
6. G9: which steps does `basic-kyc-level` contain in sandbox and prod (document types, selfie or liveness, video, geolocation, NFC, phone/email)? This decides the permission list.
7. Are older SDK tokens invalidated when a new one is minted (Sumsub-side), and is a 600 s TTL final for production?
8. Webhook setup for local and shared testing: who owns the tunnel, the secret and the dashboard registration (G7)? Can a sandbox decision be triggered without `testMode: true`?
9. Are legacy `provider='MOCK'` users (409 at #6) to be migrated, or left as they are?
10. Should `SUMSUB_BASE_URL` (or the sandbox/prod environment) be exposed to the app, or does the token alone select the environment? (The app will use the SDK's default host; `onTestEnv()` points to `test-api.sumsub.com` and must not be used. Its meaning is **UNVERIFIED**.)

---

## 9. Sources

- npm registry, `npm view @sumsub/react-native-mobilesdk-module` (1.46.1, `latest`, MIT, published 2026-09-01; peer `react-native >=0.60.0-rc.0 <1.0.x`); `npm view @sumsub/websdk-react version` (2.9.1)
- npm tarball `https://registry.npmjs.org/@sumsub/react-native-mobilesdk-module/-/react-native-mobilesdk-module-1.46.1.tgz` (integrity matched the registry): `index.js`, `android/build.gradle`, `android/src/main/java/.../SNSMobileSdkReactNativeModule.java`, `ios/MobilesdkModule.m`, `react-native-mobilesdk-module.podspec`
- Sumsub Maven `https://maven.sumsub.com/repository/maven-public/com/sumsub/sns/idensic-mobile-sdk/1.46.0/`: the `.pom` (dependencies) and the `.aar` (`AndroidManifest.xml`, `proguard.txt`)
- [Sumsub: React Native Module](https://docs.sumsub.com/docs/react-native-module)
- [Sumsub: React Native Module Changelog](https://docs.sumsub.com/docs/react-native-module-changelog)
- [Sumsub: Get started with Android SDK](https://docs.sumsub.com/docs/get-started-android)
- [Sumsub: Get started with WebSDK integration](https://docs.sumsub.com/docs/get-started-with-web-sdk)
- GitHub issues: [#27 Gradle 9 / Expo SDK 55](https://github.com/SumSubstance/react-native-mobilesdk-module/issues/27), [#11 Expo Compatibility](https://github.com/SumSubstance/react-native-mobilesdk-module/issues/11), [#22 refresh token handler](https://github.com/SumSubstance/react-native-mobilesdk-module/issues/22), [open issue list](https://github.com/SumSubstance/react-native-mobilesdk-module/issues)
- Note: the Sumsub docs disagree with each other on the Android minimum API (21 vs 23) and Kotlin (1.9.25 vs 2.1.21). This plan uses the artifact values: AAR `minSdkVersion 23`, POM `kotlin-stdlib 2.1.21`.

---

## 10. Phase 1 results: native build spike (2026-11-04)

Branch `feat/mobile-sumsub-sdk` at `185db52` (`origin/integration` had not moved). Not committed.

### 10.1 Build

| Item | Result |
|---|---|
| Module | `@sumsub/react-native-mobilesdk-module` **1.46.1**, exact pin. The lockfile integrity equals the hash verified in this plan. Native core `com.sumsub.sns:idensic-mobile-sdk:1.46.0`. |
| Stack | RN 0.83.10, Expo SDK 55, New Architecture on, Gradle **9.0.0**, AGP 8.12, JDK 17.0.11, `assembleDebug` x86_64, `--max-workers=1` |
| Gradle 9 / Kotlin / duplicate classes | **No problem.** The 1.46.1 guards for issue #27 work. No Kotlin metadata error. No duplicate-class error (issue #11 did not reproduce). |
| New Architecture | **Works through the interop layer** (`NativeModules` legacy module). The SDK launches, emits events and resolves `launch()`. RN warns once: "`new NativeEventEmitter()` was called with a non-null argument without the required `addListener`/`removeListeners` method" (harmless). |
| Build issue found and fixed | `processDebugMainManifest` failed: `meta-data com.google.mlkit.vision.DEPENDENCIES` value `barcode_ui` (expo-dev-launcher) conflicts with `face` (Sumsub). Fixed in `plugins/withSumsub.js` (`withAndroidManifest`): the app manifest declares the union `barcode_ui,face` with `tools:replace="android:value"`. No manual `android/` edit. |
| Result | **BUILD SUCCESSFUL** (4 min 27 s). Debug APK **85.1 MB** (was 65 MB). |
| Merged manifest | Sensitive permissions: **only `CAMERA`**. Location (fine and coarse), NFC, `WRITE_SETTINGS` and `RECORD_AUDIO` are removed (`blockedPermissions`). Sumsub adds `uses-feature android.hardware.camera required=true` (Play device filtering) and `nfc required=false`. |

### 10.2 Live check (emulator `emulator-5554`, Pixel_6 API 36, backend from current integration code)

- **Backend:** `.env` loaded like `scripts/run-backend-local.ps1`, plus process-only overrides: `NOTIFICATION_DISPATCHER_ENABLED=false`, `MICROSOFT_GRAPH_ENABLED=false`, `SUMSUB_SDK_TOKEN_TTL_SECONDS=120`.
- **Test user:**
  - `neos-sumsub-1@neos.local`, registered through the app (Keycloak Register form) → onboarding (no phone) → terms → KYC intro.
  - Its password was generated, never printed, and deleted afterwards.
- **SDK launch:** DEV launcher → `POST /api/kyc/start` → `POST /api/kyc/sdk-token` (200, `expiresInSeconds` 120, `Cache-Control: no-store`, `Pragma: no-cache`) → **SDK opened** (`com.sumsub.sns...SNSAppActivity`). Screens reached, in order:
  1. "Verify your identity": steps **Identity document** + **Selfie**. A "Sumsub ID" reuse toggle is **on by default**: a product/privacy decision.
  2. Sumsub personal-data consent ("Agree and continue").
  3. Sumsub ID e-mail prompt (chose "Continue without Sumsub ID").
  4. Country (default Germany) and document type.
  5. **Runtime camera prompt**, granted "Only this time".
  6. Camera screen "Front of ID".
- **Camera:** the camera opened on the Android side (`dumpsys media.camera`: device open, client `com.neos.mobile`). However, the **host webcam delivered no frames**. Emulator log: `Unable to obtain video frame from the camera '…usb#vid_04f2&pid_b7b9…'` / `No webcam source reader`. Windows camera consent is "Allow" and no app holds the webcam, so it is most likely a hardware privacy shutter or camera kill key. The preview stayed black and the shutter did nothing. **Document and selfie capture were not completed.** Liveness, and therefore the **RECORD_AUDIO question, is UNVERIFIED.**
- **After closing the SDK:**
  - `launch()` → `success: true, status: Initial` (no `errorType`).
  - The app re-read `GET /api/profile` → **PENDING** (pending screen). API: `accountStatus PENDING_KYC`, `kycStatus PENDING`. DB: `kyc_status PENDING`, provider `SUMSUB`, applicant set.
  - No webhook locally, as expected.
- **App lock:** the SDK ran inside `withAutoLockSuppressed`; no lock prompt on return.

### 10.3 Token renewal (TTL 120 s)

- **The callback fires correctly:** `onTokenExpired` ~120 s after launch (3 times across 2 sessions). Our handler is invoked.
- **Renewals: 2 succeeded (one per session), but only after the SDK closed.** The native side waits up to 120 s, times out, and retries.
- **Root cause (verified):**
  - RN pauses JS timers while `MainActivity` is paused (`JavaTimerManager.onHostPause` → `clearFrameCallback`), and `fetch` (whatwg-fetch) resolves through `setTimeout(…, 0)` (`fetch.umd.js:560-578`). The SDK runs in its own Activity, so **no `fetch`-based renewal can complete while the SDK is open**. That covers both our `sdk-token` call and the Keycloak refresh (AuthSession).
  - Diagnostic in the renewal handler (removed afterwards): `XMLHttpRequest` → `xhr 200 +51ms` while the SDK was open; `fetch` → `fetch 200 +96007ms`, which resolved only when the SDK closed.
- **Fix for phase 2 (required):** make the renewal path timer-free.
  - `POST /api/kyc/sdk-token` over raw `XMLHttpRequest`. RN's XHR callbacks are not timer-based, as verified above.
  - The Keycloak access-token refresh must avoid `fetch` too: refresh over XHR, or renew the access token right before launching so it outlives one SDK token TTL. The Keycloak access token TTL is 300 s, the SDK TTL is 600 s in prod, so an XHR refresh is needed.
  - No `setTimeout`-based timeout in that path; rely on the native 120 s cap.
  - Alternative: a headless JS task to keep timers running. Heavier; not recommended.

### 10.4 Logs and secrets

- **Patch** (`mobile/patches/@sumsub+react-native-mobilesdk-module+1.46.1.patch`, applied by `postinstall`, compiled into the debug build): logcat shows `updateAccessToken (token redacted, present=true|false)` and `Received new token (redacted)`, **4 redacted lines, no token**.
- **Scan** (counts only) across logcat (43k lines), backend log and Metro log:
  - Sumsub token pattern `_act-…`: **0**
  - `accessToken=`: **0**
  - `errorMsg`: **0**
  - JWT-like strings: **0**
  - the exact value of a separately minted token: **0**
- The JS wrapper still logs non-secret lines ("Token expired event received", "Requesting new access token…", "New access token received."). A failed renewal is `console.error`ed as our generic `RenewalError` (no token), which shows a LogBox in development.

### 10.5 Recents preview (privacy)

- **Not protected.** The SDK Activity window flags are `KEEP_SCREEN_ON | LAYOUT_IN_SCREEN | NO_MOVE_ANIMATION`, **no `FLAG_SECURE`**.
- The app switcher shows a live snapshot of the SDK screen and offers "Screenshot". Here the camera preview was black (no webcam frames), but a real ID on the camera or review screens would be visible.
- `withRecentsPrivacy` covers only `MainActivity`.
- Phase 2 options:
  - (a) Ask Sumsub for a secure-window option.
  - (b) A local plugin or Activity lifecycle hook that sets `FLAG_SECURE` on `SNSAppActivity`. This needs native code, e.g. an `Application.ActivityLifecycleCallbacks` registered from `MainApplication` via a config plugin. Note it would also block screenshots inside the SDK.
  - (c) Accept the risk and document it.

### 10.6 Gates

- `tsc` OK, ESLint 0 problems, Prettier OK, `npm test` **150/150**.
- `check:release-bundle`: **0** mock or dev-only markers. `--control` finds all 6 mock markers and `__NEOS_DEV_ONLY__:sumsubSpike`.

### 10.7 Go / no-go for phase 2

**GO, with two blocking items for phase 2:**
1. Implement the timer-free (XHR) renewal path (§10.3) and re-verify renewal while the SDK is open.
2. Complete one capture run with a working camera (host webcam fixed, or a physical device) to verify document capture, liveness, and whether liveness works without `RECORD_AUDIO`.

Decide the recents/`FLAG_SECURE` approach (§10.5) and the Sumsub ID default (§10.2) before release.

---

## 11. Phase 2 results: real in-app verification flow (2026-11-04)

Branch `feat/mobile-sumsub-sdk`. `origin/integration` merged (PR #60 secure reverification, V13 `users.suspension_reason`; PR #61 KYC `errorCode`s), merge commit `3b00279`. The phase 2 changes are **not committed**.

### 11.1 Backend inputs (read before coding)

- **PR #60:**
  - Suspension reasons `KYC_REVERIFICATION`, `KYC_FINAL_REJECTION`, `ADMINISTRATIVE`, `UNSPECIFIED` (`identity/entity/AccountSuspensionReason.java`, V13). **Not exposed** by `GET /api/profile`.
  - `sdk-token` access (`IdentityAccountService.kycSdkAccess`): `PENDING_KYC`/`ACTIVE` → STANDARD; `SUSPENDED`+`KYC_REVERIFICATION` → REVERIFICATION (token only for `RESUBMISSION_REQUIRED`); other `SUSPENDED`, `CLOSED` → RESTRICTED.
  - GREEN reactivates `SUSPENDED`+`KYC_REVERIFICATION`.
- **PR #61:**
  - Error body `{"errorCode", "message", "status"}` for `kyc/api/**` only.
  - Codes and statuses are listed in `MOBILE_API_CONTRACT.md` §4.1.
  - No code yet: Spring Security 401/403, Sumsub transport errors and timeouts (500, Fix #10), non-KYC endpoints.

### 11.2 What changed in the app (`mobile/`)

| Area | Change |
|---|---|
| Capture | `services/sumsub.ts`: real `kycCapture.verify()` = `POST /api/kyc/sdk-token` → Sumsub SDK (`withDebug(false)`, analytics off, no `onLog`, `withAutoCloseOnApprove(0)`) inside `withAutoLockSuppressed`. `kyc/sdkOutcome.ts` reduces the result to `submitted` / `incomplete` / `failed(errorType)`; `errorMsg` is never read. The simulated document/selfie screens, the `document`/`selfie` routes and the "Processing" screen are removed. The phase 1 dev launcher (`src/dev/`) is removed. |
| Renewal | `api/xhr.ts`: a fetch-compatible transport over `XMLHttpRequest` (native `xhr.timeout`, no JS timers). `services/real.ts`: a second API client on that transport for renewals, plus a timer-free Keycloak refresh (form POST to the token endpoint cached from discovery). Same semantics: refresh near expiry; 401 → refresh → retry once. |
| Flow | `useJourney.verify`: start if `NOT_STARTED` → token → SDK → `GET /api/profile` → polling. `kyc/polling.ts` + `hooks/useStatusPolling.ts`: 5 → 10 → 20 → 30 → 60 s, stops after 15 min, restarts on foreground / "Check status" / SDK close. |
| Routing | `navigation/state.ts`: `SUSPENDED` + `RESUBMISSION_REQUIRED` → "Update your documents" (neutral text) unless `sdk-token` answered `ACCOUNT_RESTRICTED` (session flag `kycRestricted`) → restricted. Other `SUSPENDED`, `CLOSED` → restricted. Pending: "Continue verification" + "Check for an update". |
| Errors | `kyc/errors.ts`: PR #61 codes mapped; without a code, 409/403 are resolved with `GET /api/profile`; 5xx / network / timeout → "Verification is temporarily unavailable" + Try again (never automatic); unknown codes → generic. |
| ISSUE-6 | The foreground re-check now applies the fresh profile on every status screen (`STATUS_ROUTES`), including the account-unavailable screen. |
| Recents | `plugins/withSumsub.js`: `MainApplication` registers `ActivityLifecycleCallbacks` that set `FLAG_SECURE` on every `com.sumsub.*` activity in `onActivityCreated`. Chosen because no Sumsub option exists and a JS flag cannot reach another activity's window; the SDK screens are black in recents and screenshots (acceptable per decision). |
| Mocks | `kycCapture` is real in the registry; the mock simulates an SDK session. New rules: `kycCapture` is a backend feature (mocked `auth` ⇒ mocked `kycCapture`); mocked `kyc` ⇒ mocked `kycCapture`. `check:release-bundle` stays generic: `--control` only requires marker families used in `src/`. |
| Bug found live and fixed | The module is CommonJS (`module.exports = {init, reset}`): `require(...).default` is undefined. `loadSdk()` now accepts both shapes and validates `init`. |

### 11.3 Verification

- **Static:**
  - `tsc` OK, ESLint 0, Prettier OK.
  - `npm test` **163/163** (new `tests/kycFlow.test.ts`: error mapping with and without codes, failure resolution, polling, XHR transport + 401 refresh path, SDK result mapping, registry; routing matrix extended with the restricted override; FLAG_SECURE plugin transform).
  - `check:release-bundle`: 0 markers; `--control`: all 6 mock markers.
  - `check:release-readiness`: only `notifications` unavailable.
- **Build:** `expo prebuild -p android`, `assembleDebug` x86_64, `--max-workers=1`, JDK 17: **BUILD SUCCESSFUL** (1 min 38 s). The `MainApplication` patch is applied once.
- **Live (emulator Pixel_6 API 36, backend from the merged code, Flyway V13 applied):**
  - Backend: dispatcher and Graph off, `SUMSUB_SDK_TOKEN_TTL_SECONDS=120` (process-only).
  - New user `neos-sumsub-2@neos.local` registered through the app → onboarding → "Start verification": `kyc/start` → `PENDING`.
  - SDK opened from "Continue verification": `SNSAppActivity` window flags include **`SECURE`**; `adb` screenshot **black**; recents card **black**.
  - **Token renewal while the SDK stayed open: 2/2.**
    - 19:43:16.236 expiry → 19:43:16.431 new token.
    - 19:46:47.929 → 19:46:48.492, more than 300 s after sign-in, so this one also went through the timer-free Keycloak refresh (inferred from the timing; Keycloak event logging is off).
    - No timeout, no failure.
  - Camera: runtime prompt "Allow NEOS to take pictures and record video?" ("Only this time"); webcam started (`Creating webcam source reader … 1280x960`, 0 frame errors); camera open by `com.neos.mobile` on "Front of ID".
  - **Capture NOT RUN on purpose:** no ID document at hand, and pressing the shutter would have uploaded the developer's webcam image (room or face) to Sumsub. Document, liveness and the `RECORD_AUDIO` question move to the physical-device run (`docs/testing/ANDROID_DEVICE_TEST_GUIDE.md`).
  - SDK closed with back → `incomplete` (no error) → pending screen with "Continue verification" / "Check for an update".
  - API and DB: `PENDING_KYC`, `PENDING`, provider `SUMSUB`, applicant set, `suspension_reason` null. `kyc/start` is idempotent (200 `PENDING`); `sdk-token` without auth → 401.
- **Logs** (counts only), logcat 32k lines + backend + Metro:
  - Sumsub token pattern **0**, exact value of a separately minted token **0**
  - JWT-like **0**, `refresh_token=` **0**
  - `accessToken=` **0**, `errorMsg` **0**
  - redacted patch lines 4
- **Not verified live** (unit tests only): the `errorCode` paths (no account in a restricted or manual-review state was created; the local DB was not edited), the polling cadence (the backend does not log requests), and the ISSUE-6 foreground re-check on the restricted screen.
- **Secrets:** the test password file and the separately minted token file were deleted after the run; a scratchpad rescan finds no token.

### 11.4 Status

Phase 2 is functionally complete on Android. Remaining before release:
- The end-to-end run on a physical phone with the webhook (GREEN, RETRY → resubmission, FINAL; wallets after GREEN; liveness without `RECORD_AUDIO`).
- iOS.
- The Sumsub ID default toggle decision.
- Backend R5 (a)–(c) in `INTEGRATION_STATUS.md`.
