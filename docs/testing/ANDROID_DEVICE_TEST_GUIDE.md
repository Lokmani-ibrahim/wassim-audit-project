# Android physical-device test guide: Sumsub KYC end to end

For the backend developer's final run on a real Android phone: backend, Keycloak, Formance and the Sumsub sandbox + webhook on your side, the NEOS app on your phone over USB.

- **Repo state this guide was written against:** branch `feat/mobile-sumsub-sdk`, phase 2 of `docs/api/SUMSUB_MOBILE_INTEGRATION_PLAN.md` (§11), on top of `integration` with PRs #58, #60 and #61. Every command, script name and variable below was checked against that state on 2026-11-04.
- **Secrets:** never paste `.env` values, passwords or tokens into the results; use `***`.

---

## 1. Prerequisites

| Tool | Version the build uses | Notes |
|---|---|---|
| Node.js | **≥ 20.19.4** (`react-native@0.83.10` `engines`) | Checked with Node 20.20.2 / npm 10.8.2. |
| JDK for the Android build | **17** | Point `NEOS_ANDROID_JAVA_HOME` at it (used by `mobile/scripts/android.ps1`), and set `JAVA_HOME` to it for Gradle. |
| JDK for the backend | **21** | `scripts/run-backend-local.ps1` uses the Android Studio JBR (`C:\Program Files\Android\Android Studio\jbr`, a JDK 21). |
| Android SDK Platform | **android-36** | compile and target SDK 36; the app's `minSdk` is **24** (phone must run Android 7.0+). |
| Build Tools | **36.0.0** (35.0.0 is pulled transitively) | |
| NDK | **27.1.12297006** | `ndkVersion` from React Native 0.83. |
| CMake | **3.22.1** | |
| Platform-tools | any recent (`adb`) | |
| Gradle / AGP | wrapper **9.0.0**, AGP 8.12 | Downloaded by the wrapper. |

Install the SDK pieces with `sdkmanager` (Android Studio → SDK Manager works too):

```powershell
# Windows (PowerShell)
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;27.1.12297006" "cmake;3.22.1"
$env:NEOS_ANDROID_JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot'   # your JDK 17 path
```

```bash
# macOS / Linux
export ANDROID_HOME="$HOME/Library/Android/sdk"        # Linux: $HOME/Android/Sdk
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;27.1.12297006" "cmake;3.22.1"
export NEOS_ANDROID_JAVA_HOME="$(/usr/libexec/java_home -v 17)"   # Linux: path to your JDK 17
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

Free disk: keep **≥ 10 GB** before a native build (Gradle and NDK caches grow by several GB).

## 2. Phone setup

1. Settings → About phone → tap **Build number** 7 times (Developer options on).
2. Settings → System → Developer options → **USB debugging** on. Connect the USB cable and accept the "Allow USB debugging?" prompt (tick "Always allow").
3. Check the connection and note the serial:
   ```bash
   adb devices -l          # expect: <SERIAL>  device  ... model:...
   ```
4. Camera permission: the app does **not** ask at install. The Sumsub SDK asks the first time it opens the camera ("Allow NEOS to take pictures and record video?"). Answer **While using the app** or **Only this time**. The app declares only `CAMERA` among sensitive permissions (microphone, location, NFC and `WRITE_SETTINGS` are removed in `mobile/app.config.ts` → `android.blockedPermissions`).

## 3. Keycloak realm `neos` (your side)

The app needs these on the realm (details and reasons in `docs/api/INTEGRATION_STATUS.md` R1/R2/R3). `infra/keycloak/realm/neos-realm.json` does **not** contain them yet (its `neos-mobile` client has an empty `redirectUris` and registration is off).

| Setting | Value |
|---|---|
| Client `neos-mobile` → Valid redirect URIs | `neos://auth` (public client, standard flow, PKCE S256 are already set) |
| Realm → Login → User registration | **On** (`registrationAllowed: true`) |
| Realm → Login → Email as username | **On** (`registrationEmailAsUsername: true`) |
| Realm → Display name | `NEOS` |
| Realm roles → `default-roles-neos` | add realm role **`CUSTOMER`** |
| Optional: Realm → Themes → Login theme | `neos` (mount `infra/keycloak/themes/neos`, see R3) |

**Admin console** (http://localhost:8081/admin, realm `neos`):
- Clients → `neos-mobile` → Settings → **Valid redirect URIs** → add `neos://auth` → Save.
- Realm settings → General → **Display name** `NEOS`; Realm settings → Login → **User registration** on, **Email as username** on.
- Realm settings → User registration → **Default roles** → Assign role → `CUSTOMER`.
- (Optional) Realm settings → Themes → Login theme `neos`.

**Or with the admin API** (bash; the admin user is `KC_BOOTSTRAP_ADMIN_USERNAME` from `infra/keycloak/compose.yml`, password from your setup, never printed):

```bash
KC=http://localhost:8081
read -rs KC_ADMIN_PASSWORD
ADMIN=$(curl -s -d grant_type=password -d client_id=admin-cli -d username=admin --data-urlencode "password=$KC_ADMIN_PASSWORD" \
  "$KC/realms/master/protocol/openid-connect/token" | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
H="Authorization: Bearer $ADMIN"
# Realm flags
curl -s -X PUT -H "$H" -H 'Content-Type: application/json' "$KC/admin/realms/neos" \
  -d '{"displayName":"NEOS","registrationAllowed":true,"registrationEmailAsUsername":true}'
# CUSTOMER in the default roles
ROLE=$(curl -s -H "$H" "$KC/admin/realms/neos/roles/CUSTOMER")
curl -s -X POST -H "$H" -H 'Content-Type: application/json' "$KC/admin/realms/neos/roles/default-roles-neos/composites" -d "[$ROLE]"
# neos://auth on neos-mobile
CID=$(curl -s -H "$H" "$KC/admin/realms/neos/clients?clientId=neos-mobile" | sed -E 's/^\[\{"id":"([^"]+)".*/\1/')
curl -s -X PUT -H "$H" -H 'Content-Type: application/json' "$KC/admin/realms/neos/clients/$CID" \
  -d '{"clientId":"neos-mobile","redirectUris":["neos://auth"]}'
# Optional login theme
curl -s -X PUT -H "$H" -H 'Content-Type: application/json' "$KC/admin/realms/neos" -d '{"loginTheme":"neos"}'
unset ADMIN KC_ADMIN_PASSWORD
```

The phone reaches Keycloak at `http://localhost:8081` through `adb reverse` (§6). The JWT issuer is then `http://localhost:8081/realms/neos`, which matches the backend default `KEYCLOAK_ISSUER_URI`.

## 4. Backend settings the app needs (names only)

Root `.env` (loaded by Spring through `spring.config.import: optional:file:./.env[.properties]`, and by `scripts/run-backend-local.ps1`):

| Variable | Value for this run | Why |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` | |
| `SERVER_PORT` | **`8080`** | The dev profile default is **8082**, which is Metro's port. The app expects the API on 8080. |
| `FORMANCE_BASE_URL` | e.g. `http://localhost:8083` | The dev default is `http://localhost:8080`, which clashes with the backend. |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | your Postgres | Flyway runs up to **V13** (`users.suspension_reason`). |
| `KEYCLOAK_ISSUER_URI` | default `http://localhost:8081/realms/neos` | Must match the issuer the phone sees. |
| `NEOS_CUSTOMER_TRANSFER_API_ENABLED` | `true` | The dev profile default is true, but a `false` in `.env` wins (see ISSUE-4 in the PR #55 report). |
| `MICROSOFT_GRAPH_ENABLED`, `NOTIFICATION_DISPATCHER_ENABLED` | `false` | Unless you are testing email. |
| `SUMSUB_APP_TOKEN`, `SUMSUB_SECRET_KEY` | sandbox keys | |
| `SUMSUB_LEVEL_NAME` | `id-and-liveness` | Must exist in the Sumsub sandbox and match the webhook `levelName`. |
| `SUMSUB_SANDBOX_MODE` | `true` | Must match the webhook's `sandboxMode`. |
| `SUMSUB_WEBHOOK_ENABLED` | `true` | Otherwise no decision ever arrives. |
| `SUMSUB_WEBHOOK_SECRET` | the secret configured in the Sumsub dashboard (HMAC_SHA256_HEX) | |
| `SUMSUB_SDK_TOKEN_TTL_SECONDS` | **`120` for step 7.4**, then back to the default (600) | Forces token renewals while the SDK is open. |

Webhook: Sumsub sandbox → `POST https://<your public tunnel>/api/webhooks/sumsub`, event `applicantReviewed`. The parser rejects `testMode: true` payloads, so the dashboard's "send test webhook" cannot approve anyone. Use a real review decision on the applicant.

**Start the backend.** `scripts/run-backend-local.ps1` exists in the author's working copy but is **not committed**. Its whole content:

```powershell
$root = Split-Path $PSScriptRoot -Parent
Get-Content "$root\.env" | ForEach-Object {
  if ($_ -match '^\s*([^#][^=]*)=(.*)$') { Set-Item -Path "env:$($matches[1].Trim())" -Value $matches[2].Trim() }
}
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
Set-Location $root
.\mvnw.cmd spring-boot:run
```

macOS / Linux equivalent (from the repo root):

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # Linux: path to your JDK 21
set -a; . ./.env; set +a
./mvnw spring-boot:run
```

Check: `curl -s http://localhost:8080/actuator/health` → `{"status":"UP",...}`.

## 5. `mobile/.env` for a USB-connected phone

Copy `mobile/.env.example` to `mobile/.env` (git-ignored) and use:

| Variable | Value | Notes |
|---|---|---|
| `EXPO_PUBLIC_MOCK_FEATURES` | **`none`** | Fully real run: real Keycloak, backend, KYC start **and** Sumsub SDK. |
| `EXPO_PUBLIC_APP_ENV` | `development` | Shows the Sandbox badge. |
| `EXPO_PUBLIC_API_URL` | `http://localhost:8080` | Through `adb reverse`. |
| `EXPO_PUBLIC_KEYCLOAK_ISSUER` | `http://localhost:8081/realms/neos` | Through `adb reverse`. |
| `EXPO_PUBLIC_KEYCLOAK_CLIENT_ID` | `neos-mobile` | |
| `EXPO_PUBLIC_REDIRECT_URI` | `neos://auth` | Must be registered on `neos-mobile` (§3). |
| `EXPO_PUBLIC_TERMS_VERSION` | `1.0` | The backend accepts `1.0`. |
| `EXPO_PUBLIC_TERMS_URL` | empty | Optional in development builds. |
| `EXPO_PUBLIC_DEMO_KYC_RESULT` | (ignored) | Only used when `kyc` is mocked. |
| `EXPO_PUBLIC_LOCK_TIMEOUT_SECONDS` | optional (default 60) | App-unlock background timeout. |

## 6. Build, install and launch on the phone

Close the emulator if one is running (two devices → you must pass `-s <SERIAL>` everywhere).

```powershell
# Windows (PowerShell), from the repo root
cd mobile
npm ci                                   # also runs postinstall: patch-package (token-log patch)
npx expo prebuild -p android             # generates android/ (git-ignored), applies plugins/withSumsub.js
$env:JAVA_HOME = $env:NEOS_ANDROID_JAVA_HOME
cd android
.\gradlew.bat app:assembleDebug -PreactNativeArchitectures=arm64-v8a --max-workers=1
.\gradlew.bat --stop
cd ..
$SERIAL = '<SERIAL from adb devices>'
adb -s $SERIAL install -r android\app\build\outputs\apk\debug\app-debug.apk
adb -s $SERIAL reverse tcp:8080 tcp:8080     # backend
adb -s $SERIAL reverse tcp:8081 tcp:8081     # Keycloak
adb -s $SERIAL reverse tcp:8082 tcp:8082     # Metro
$env:NODE_OPTIONS = '--dns-result-order=ipv4first'
npx expo start --dev-client --port 8082 --clear
# in a second terminal:
adb -s $SERIAL shell am start -a android.intent.action.VIEW -d "exp+neos://expo-development-client/?url=http%3A%2F%2Flocalhost%3A8082" com.neos.mobile
```

```bash
# macOS / Linux, from the repo root
cd mobile
npm ci
npx expo prebuild -p android
export JAVA_HOME="$NEOS_ANDROID_JAVA_HOME"
(cd android && ./gradlew app:assembleDebug -PreactNativeArchitectures=arm64-v8a --max-workers=1 && ./gradlew --stop)
SERIAL=<SERIAL from adb devices>
adb -s "$SERIAL" install -r android/app/build/outputs/apk/debug/app-debug.apk
for p in 8080 8081 8082; do adb -s "$SERIAL" reverse tcp:$p tcp:$p; done
NODE_OPTIONS=--dns-result-order=ipv4first npx expo start --dev-client --port 8082 --clear
# second terminal:
adb -s "$SERIAL" shell am start -a android.intent.action.VIEW -d "exp+neos://expo-development-client/?url=http%3A%2F%2Flocalhost%3A8082" com.neos.mobile
```

Notes:
- `-PreactNativeArchitectures=arm64-v8a` overrides the `reactNativeArchitectures` line in `android/gradle.properties`. Almost every current phone is arm64; check with `adb -s <SERIAL> shell getprop ro.product.cpu.abi`.
- Alternative: `mobile/scripts/android.ps1` with `$env:ANDROID_SERIAL = '<SERIAL>'` sets the reverses and runs `npx expo run:android --port 8082`. That builds for the connected device's ABI but **without** `--max-workers=1` (see §8.2).
- Check that the patch is in the build: after the first token renewal, `adb logcat -s SNSRNModule` must show `updateAccessToken (token redacted, present=true)`. If it shows a raw token, `npm ci` did not run `patch-package`. Re-run it and rebuild.
- `adb reverse` does not survive a phone reconnect or reboot. Re-run the three commands.

## 7. Test script

Use a **new** user each run (e.g. `neos-device-1@neos.local`). Keep a logcat capture running for the whole test:

```bash
adb -s <SERIAL> logcat -c
adb -s <SERIAL> logcat -v time > device-logcat.txt     # stop with Ctrl+C at the end; do not commit
```

### 7.1 Sign-up and onboarding
1. Welcome → **Let's begin** → Keycloak **Register** (email, password, confirm, first/last name) → Register.
2. "First, the essentials." (email prefilled, phone optional) → **Continue** → "Looking good?" → tick "I agree to the NEOS terms of service." → **Confirm and continue**.
3. Expected: KYC intro "Verify your identity." with Government-issued ID / A quick selfie / About 2 minutes and "Verification is done with our identity partner, Sumsub…". `GET /api/profile` → `PENDING_KYC` / `NOT_STARTED`.

### 7.2 Start verification
4. **Start verification**. Expected: `POST /api/kyc/start` → 200 `PENDING`; `POST /api/kyc/sdk-token` → 200; the Sumsub SDK opens ("Verify your identity / It will only take 2 minutes", steps Identity document + Selfie).
5. Recents check: press the app-switcher button while the SDK is open. Expected: the NEOS card is **black** (FLAG_SECURE on `com.sumsub.*`). A screenshot (Power + Volume down) is black or blocked too.

### 7.3 Close early and continue
6. Close the SDK with its ✕ / back before finishing. Expected: "We're taking a closer look." with **Continue verification** and **Check for an update**, no error.
7. **Continue verification** → the SDK reopens where you left it.

### 7.4 Token renewal while the SDK stays open (backend `SUMSUB_SDK_TOKEN_TTL_SECONDS=120`)
8. Leave the SDK open on any step for **more than 2 minutes**, then continue (tap Continue / take a step). Repeat after **more than 5 minutes** in total since sign-in (the Keycloak access token lives 300 s, so this also covers the Keycloak refresh).
9. Expected: the SDK continues without "Unauthorized". In logcat, per renewal:
   `Calling onTokenExpired()` → `Requesting new access token from tokenExpirationHandler` → `New access token received.` → `updateAccessToken (token redacted, present=true)`, all within about a second. Never `Timeout while waiting for new access token` or `Failed to get new access token!`.

### 7.5 Capture with the real camera
10. Consent "Agree and continue" → (Sumsub ID: either choice) → country + document type → camera prompt → capture the ID (front, and back if asked) → selfie / liveness.
11. **Microphone:** the app has no `RECORD_AUDIO`. Check that liveness completes. Settings → Apps → NEOS → Permissions must list only Camera. If liveness refuses to run without the microphone, record it as an issue (proposal: unblock `RECORD_AUDIO` in `app.config.ts`).
12. Finish the SDK. Expected: back on "We're taking a closer look." The app polls `GET /api/profile` (5 s, 10 s, 20 s, 30 s, then every 60 s, for at most 15 minutes) and on **Check for an update**.

### 7.6 Decisions from the Sumsub sandbox
In the Sumsub dashboard (sandbox), set the review result on the applicant (`externalUserId` = the NEOS `userId`). The exact menu names are Sumsub's; the webhook `applicantReviewed` must reach your tunnel.

13. **GREEN** → backend: KYC `APPROVED`, account `ACTIVE`, `KycApprovedEvent`. App, within one polling interval: "You're verified." → **Continue** → "Your account is ready." → **Go to home** → Home with the **6 wallets** (EUR, USD, GBP, AED, MUR, TND; "Setting up…" first if provisioning is still running).
14. **RED / RETRY** on that now-active user → backend: KYC `RESUBMISSION_REQUIRED`, account `SUSPENDED` with reason `KYC_REVERIFICATION`. App (bring it to the foreground): "One more detail." + "We need updated documents to complete your verification. Some account features are paused until then." + **Update your documents** → the SDK reopens (sdk-token 200) → resubmit → close → waiting screen. A new **GREEN** → account back to `ACTIVE` → "You're verified." → Home.
   - First-time applicant variant (RETRY before any GREEN): account stays `PENDING_KYC`; text "We need updated documents to complete your verification."
15. **RED / FINAL** (use a fresh user for a first-time rejection) → KYC `REJECTED`. App: "Let's try that again." with "Verification unsuccessful" and only **Sign out** (no retry). For a previously approved user, FINAL → `SUSPENDED` (`KYC_FINAL_REJECTION`) → "Your account is unavailable."

### 7.7 Other checks
16. **No tokens in logcat** (after stopping the capture):
    ```bash
    grep -cE "_act-[A-Za-z0-9_-]{20,}" device-logcat.txt            # expect 0 (Sumsub tokens)
    grep -cE "eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}" device-logcat.txt   # expect 0 (JWTs)
    grep -cE "accessToken=|errorMsg|refresh_token=" device-logcat.txt       # expect 0
    grep -c "token redacted" device-logcat.txt                              # expect ≥ 1 per renewal
    ```
    Delete `device-logcat.txt` afterwards.
17. **Account-unavailable screen re-check (ISSUE-6):** with the app on "Your account is unavailable.", restore the account (`ACTIVE`) on the backend, send the app to the background and back. Expected: it leaves the restricted screen without a cold start ("You're verified." → Home for an approved user).
18. **Restricted probe:** a user `SUSPENDED` with reason `ADMINISTRATIVE` and KYC `RESUBMISSION_REQUIRED` → app shows "Update your documents"; tapping it → `sdk-token` 403 `ACCOUNT_RESTRICTED` → "Your account is unavailable." (no SDK).
19. **Sumsub down / timeout (PR #64: 503 `SUMSUB_UNAVAILABLE`, or any other 5xx):** on start or sdk-token → "Verification is temporarily unavailable. Please try again in a few minutes." with the button to try again; nothing retries on its own.

### 7.8 PDF receipts (device-generated, not authoritative)
20. **From Activity:** Activity → open a transaction → **Share receipt** → the share sheet opens with `NEOS-receipt-<transactionId>.pdf`. Save it to Files (or Drive) and open it in a PDF viewer. Expected: NEOS header, "Transaction receipt", generation time, the signed amount at the currency scale (TND has 3 decimals), the type, direction, status, date (local + UTC), transaction ID, reference, your own email (no name), and the footer "This is not a bank statement". **No "To" row** (history has no counterparty). A sandbox build also shows "Sandbox · test funds".
21. **Reversed item:** if one exists, the receipt shows "Reversed" in red with the amount struck through.
22. **From Send:** complete a transfer → on "Money sent", tap **Share receipt**. Expected: the same layout plus **To** = the recipient's masked email, and "Confirmed at (device time)".
23. **No receipt when not confirmed:** on "Transfer is being processed" (uncertain) and "Transfer not sent" (rejected) there is **no** Share receipt button.
24. **Cleanup:** after the share sheet closes, `adb shell run-as com.neos.mobile ls cache/receipts cache/Print` → empty or "No such file".

## 8. Troubleshooting (from our own setup history)

1. **Gradle cannot download dependencies (DNS / network)** *(general guidance, not recorded in `mobile/VALIDATION.md`)*: check VPN or proxy, then rerun with `--refresh-dependencies`. Make sure `https://maven.sumsub.com/repository/maven-public/` is reachable (the Sumsub SDK is only published there; `plugins/withSumsub.js` adds it to `android/build.gradle`).
2. **`CreateProcess: Access denied` (`Accès refusé`) from the NDK toolchain** (`mobile/VALIDATION.md`): build a **single ABI** with **`--max-workers=1`**, as in §6. `npx expo run:android` does not pass `--max-workers=1`.
3. **Dev client "unexpected end of stream" / cannot reach Metro** (`mobile/VALIDATION.md`): Node resolves `localhost` to `::1` but `adb reverse` dials `127.0.0.1`. Start Metro with `NODE_OPTIONS=--dns-result-order=ipv4first` (set by `mobile/scripts/android.ps1`), and re-run `adb reverse tcp:8082 tcp:8082`.
4. **Low disk / RAM** (PR #55 run, `docs/testing/INTEGRATION_TEST_REPORT_PR55.md`): stop below 10 GB free; run `gradlew --stop` after the build; don't run the emulator, a Gradle build and the backend at the same time on a 16 GB machine.
5. **Keycloak page instead of the app after sign-in, or "Please re-authenticate to continue" / "Restart login"** *(general guidance; the re-authenticate page was seen on 2026-11-04 when a browser SSO session outlived the app's tokens)*: a stale Chrome Custom Tab can still own the Keycloak session or swallow the `neos://auth` redirect. Close all Chrome tabs for `localhost:8081` (or force-stop Chrome) and sign in again. Check that `neos://auth` is a valid redirect URI on `neos-mobile`.
6. **Manifest merger failed on `com.google.mlkit.vision.DEPENDENCIES`** (phase 1): fixed by `plugins/withSumsub.js`. If it comes back, `android/` was not regenerated: run `npx expo prebuild -p android`.
7. **"This isn't available yet. It will arrive in a future release." right after Start verification, and no SDK**: the installed app has no Sumsub native module (built before the module was added, Expo Go, or `npm ci` skipped). Rebuild and reinstall (§6). If the message is "We couldn't complete this check." instead, capture the logcat `ReactNativeJS` lines and report it as an issue.
8. **Registration works but `/api/auth/me` → 403**: `CUSTOMER` is missing from `default-roles-neos` (§3).

## 9. Results template

Fill one row per step. For every failure, add an issue in the format of `docs/testing/INTEGRATION_TEST_REPORT_PR55.md` §3.

| # | Step | Expected | Actual | Pass / Fail | Notes (no secrets) |
|---|---|---|---|---|---|
| 1 | Sign-up (Keycloak Register) | Registered, back in the app on "First, the essentials." | | | |
| 2 | Onboarding | KYC intro; profile `PENDING_KYC` / `NOT_STARTED` | | | |
| 3 | Start verification | start 200 `PENDING`, sdk-token 200, SDK opens | | | |
| 4 | Recents / screenshot during the SDK | Black card, black or blocked screenshot | | | |
| 5 | Close early → Continue verification | Waiting screen, SDK reopens | | | |
| 6 | Renewal after > 2 min | SDK continues; logcat "New access token received." + "token redacted" | | | |
| 7 | Renewal after > 5 min (Keycloak refresh) | Same as 6 | | | |
| 8 | ID capture (real camera) | Accepted by the SDK | | | |
| 9 | Selfie / liveness without microphone | Completes; only Camera permission granted | | | |
| 10 | Close after submit | "We're taking a closer look.", polling | | | |
| 11 | GREEN | "You're verified." → "Your account is ready." → Home | | | |
| 12 | 6 wallets after GREEN | EUR USD GBP AED MUR TND (maybe "Setting up…" first) | | | |
| 13 | RETRY on an active user | Account `SUSPENDED` (`KYC_REVERIFICATION`); "Update your documents" + neutral text | | | |
| 14 | Update your documents | sdk-token 200, SDK reopens, resubmission OK | | | |
| 15 | GREEN after RETRY | Account `ACTIVE`, Home | | | |
| 16 | FINAL (first-time user) | "Let's try that again.", Sign out only | | | |
| 17 | logcat token scan | 0 / 0 / 0, ≥ 1 "token redacted" | | | |
| 18 | Restricted screen re-check on foreground | Leaves "Your account is unavailable." without a cold start | | | |
| 19 | Restricted probe (`ADMINISTRATIVE` + RETRY) | `ACCOUNT_RESTRICTED` → "Your account is unavailable." | | | |

Issue format (copy per failure):

```markdown
### ISSUE-N — [Area] One-line summary (FAIL, step #)

- **Endpoint / screen:**
- **HTTP status:** actual …, expected …
- **Request (redacted):** `Authorization: Bearer ***`, body …
- **Response (redacted):** …
- **Expected vs actual:** …
- **Reproduction:** 1. … 2. … 3. …
- **Logs:** relevant logcat / backend lines (no tokens, no passwords)
- **Severity:** High / Medium / Low
```
