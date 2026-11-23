# Frontend ↔ Backend Integration Audit — Tanit / Nova Pay / Neos

**Scope:** read-only static analysis of the repository on branch `feat/frontend` (commit `7e4e2a5`). No files were modified to produce this audit.

--- 

## 1. Executive Summary

- The repository contains **one real frontend** (`mobile/`, React Native + Expo) and **one Spring Boot backend monolith** (`src/main/java/com/fintech`). **No web backoffice exists anywhere in the repo** — it is referenced in the intended architecture but has not been started.
- The backend currently implements **3 REST endpoints total**: `GET /api/auth/me`, `POST /api/onboarding/personal`, `POST /api/kyc/start` (plus the framework's `GET /actuator/health`). Of the 8 architecture modules named in the brief, only **Identity, Onboarding, and KYC** have any real code. **Wallet, Ledger, Transaction, Notification, and Backoffice do not exist** — not stubbed, not partially built, simply absent from source (no packages, no entities, no tables).
- Everywhere the 3 real endpoints exist, the frontend-to-backend contract is a **clean, verified match** — same path, same method, same field names/types on both sides (Section 4). This is a genuinely well-integrated slice, as far as it goes.
- The frontend ships with **`EXPO_PUBLIC_APP_DEMO_MODE=true` by default** (`mobile/.env.example:1`). In this default state, **every screen** — including onboarding and KYC, which do have real backend support — runs against an in-memory mock (`mobile/src/mocks/demo.ts`) instead of the API. A developer has to explicitly set the flag to `false` to see any real network traffic at all.
- **Wallets, balances, top-up, transfers, beneficiaries, FX, transaction history, notifications, support, and backoffice screens are 100% UI/local-state mock**, independent of the demo flag — there is no code path to a real backend for any of them because no such backend exists.
- KYC approval, even in the "real" (non-demo) backend path, is **synthetic**: `KycStartService` calls the mock provider and auto-approves in the same request with no waiting period, no webhook, no reviewer step (`src/main/java/com/fintech/kyc/KycStartService.java:99-108`). There is no Sumsub (or any other real KYC vendor) client implemented — only a config placeholder.
- **Honest completion estimate against the 15 Phase-1 journeys below: ~2 of 15 are genuinely end-to-end (personal registration, mock-KYC submission), both resting on a mock KYC decision engine; the other 13 are either UI-only mock or entirely unimplemented on the backend.** That is roughly **13%** truly wired, generously counted.
- **Biggest demo risk:** the Home screen (wallets, balances) looks like a real dashboard but is hardcoded to display MUR/USD/EUR at zero balance (`mobile/src/screens/Home.tsx:27-40`) with no backend underneath it whatsoever — this is the single easiest thing for a client to click on and discover is fake.

---

## 2. Repo Map and Stack

```
/                           Spring Boot backend (root pom.xml)
  pom.xml                   Spring Boot 4.1.0, Java 21, Maven, Spring Modulith 2.1.0
  src/main/java/com/fintech Backend source
  src/.env.example          DB_URL, DB_USERNAME, DB_PASSWORD, SPRING_PROFILES_ACTIVE only
  infra/keycloak/           Keycloak realm export + docker-compose for local Keycloak
mobile/                     Frontend — React Native 0.83 + Expo ~55, TypeScript strict
  App.tsx, app.config.ts    Expo entry/config
  src/                      screens, services, mocks, hooks, navigation, types
  .env.example              EXPO_PUBLIC_* vars (API URL, Keycloak issuer/client, demo flags)
```

No other frontend exists in the repo (confirmed by directory listing at root and inside `mobile/` — no `web/`, `backoffice/`, or similar folder).

### Backend
- Spring Boot **4.1.0**, Java **21**, Maven build (`pom.xml:17-19,40`), Spring Modulith **2.1.0** for module boundaries (`pom.xml:35,110-125`).
- Entry point: `src/main/java/com/fintech/FintechBackendApplication.java:8`.
- Profiles: `application.yaml` (base; `neos.kyc.mode` defaults to `MOCK`), `application-dev.yml` (local Postgres + local Keycloak issuer `http://localhost:8081/realms/neos`, Flyway on), `application-prod.yml` (DB vars only — no Keycloak issuer override present, UNVERIFIED whether prod profile is deploy-ready).
- `src/.env.example` only lists `SPRING_PROFILES_ACTIVE`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` — it does **not** list `KEYCLOAK_ISSUER_URI`, `KYC_MODE`, or `SUMSUB_*`, even though code reads them. The env example is incomplete relative to actual config surface.
- Migrations: Flyway, only 2 files exist — `V1__create_identity_and_onboarding_schema.sql` (users, kyc_status, devices) and `V2__create_terms_acceptances.sql` (terms_acceptances). `ddl-auto: validate` — migrations are the real source of truth, no auto-schema-generation.
- Started via `./mvnw spring-boot:run` (standard Spring Boot Maven plugin, present in `pom.xml`).

### Frontend (`mobile/`)
- **React Native 0.83.10 + Expo ~55.0.31**, React 19.2.0, TypeScript strict (`mobile/package.json:17-30`, `mobile/tsconfig.json`).
- No React Navigation — custom state machine (`mobile/src/navigation/state.ts:4-21`).
- No Redux/MobX — plain hooks (`mobile/src/hooks/useJourney.ts`).
- HTTP: raw `fetch`, no axios (`mobile/src/services/real.ts:78`).
- Auth: `expo-auth-session` (OIDC + PKCE) and `expo-secure-store` for token persistence.
- Env vars are `EXPO_PUBLIC_*`, loaded with defaults in `mobile/src/config.ts:1-10`; see exact values in Section 3.
- Started via `npm start` → `expo start --localhost --port 8082` (`mobile/package.json` scripts); `npm run android` for device/emulator.
- This is **Android-targeted via Expo**, not a native-only Android project and not React Native for web/iOS-first — matches the brief's "Android app" Phase 1 scope. It is **not** a web SPA and **not** Flutter.

### Infra
- `infra/keycloak/` — a real Keycloak realm export (`infra/keycloak/realm/neos-realm.json`) defining roles `CUSTOMER`, `ADMIN`, `OPERATIONS`, `COMPLIANCE_OFFICER`, plus `infra/keycloak/compose.yml` to run Keycloak locally. This is genuine, not a mock.
- No Formance, SQS, Redis, or AWS infra config found anywhere in the repo.

---

## 3. Backend Inventory

### REST endpoints (exhaustive — this is the entire backend REST surface)

| Method | Path | Controller | Role required | Real or stub |
|---|---|---|---|---|
| GET | `/api/auth/me` | `AuthController.java:16` | `CUSTOMER` (`@PreAuthorize`, line 17) | **Real but trivial** — echoes JWT claims (`subject`, `preferred_username`, `email`) back as a `Map`, no DB read (`AuthController.java:22-28`) |
| POST | `/api/onboarding/personal` | `PersonalOnboardingController.java:25` | `CUSTOMER` | **Real** — persists `UserEntity`, `DeviceEntity`, `TermsAcceptanceEntity`, initializes `KycStatusEntity` via JPA, `saveAndFlush` |
| POST | `/api/kyc/start` | `KycController.java:25-27` | `CUSTOMER` | **Partially real / partially fake** — see below |
| GET | `/actuator/health` | Spring Boot Actuator (framework) | public | Real (framework default) |

No endpoint exists for: wallets, balances, top-up, transfers (internal or external), beneficiaries, FX quotes, transaction history, receipts, notifications, support, or any backoffice function. These are not present in any form.

### Module inventory

Packages actually present under `src/main/java/com/fintech`: `identity`, `onboarding`, `kyc`, `notifications` (empty shell), `payments` (empty shell), `shared`. **No `wallet`, `ledger`, `transaction`, or `backoffice` package exists.**

- **Identity** — `AuthController` (above). `SecurityConfig.java` wires `oauth2ResourceServer` JWT validation against the configured Keycloak issuer; `KeycloakRealmRoleConverter.java` maps `realm_access.roles` claim → Spring `GrantedAuthority`. Real, not mocked.
- **Onboarding** — `PersonalOnboardingService` persists real rows across 3 entities + flips `KycStatusEntity` to `NOT_STARTED`. Genuinely real.
- **KYC** — `KycStartService.java:99-108`: calls `MockKycProvider.createApplicant(...)` then immediately, in the same call, transitions `NOT_STARTED → PENDING → APPROVED` and calls `identityAccountService.activate(userId)`. A code comment at `KycStartService.java:86-98` states: *"Mock validation flow... The real Sumsub implementation will not automatically approve here."* This confirms the auto-approve is a deliberate placeholder, not a bug.
  - `MockKycProvider.java:16-41` is `@ConditionalOnProperty(..., havingValue="MOCK", matchIfMissing=true)` — **active by default**. Generates fake applicant id `"mock-" + userId` (line 24) and fake session token `"mock-session-" + UUID.randomUUID()` (line 35).
  - `SumsubProperties.java` exists (a config-only record) but **no class implements the `KycProvider` interface for Sumsub** anywhere in the codebase. Setting `KYC_MODE=SUMSUB` would leave no Spring bean satisfying `KycProvider` and the app would likely fail to start — UNVERIFIED without actually trying it, but no second implementing class exists to grep for.
- **Notifications** — `NotificationPort.java:3` is an empty marker interface, zero methods, zero implementations. No email/SMS/push code exists anywhere.
- **Payments** — `SponsorBankPort.java:3` is an empty marker interface, zero implementations. No bank/payment partner integration exists.
- `PolicyProvider.java:3` — another empty marker interface, unused.

### DB entities (4 total — matches the 2 Flyway migrations, nothing more)

| Entity | Table | File:line |
|---|---|---|
| `UserEntity` | `users` | `identity/entity/UserEntity.java:11` (name UNVERIFIED package, see fork note) / `V1...sql:17` |
| `DeviceEntity` | `devices` | `V1...sql:110` |
| `KycStatusEntity` | `kyc_status` | `kyc/entity/KycStatusEntity.java:12` / `V1...sql:60` |
| `TermsAcceptanceEntity` | `terms_acceptances` | `onboarding/entity/TermsAcceptanceEntity.java:11` / `V2...sql:7` |

No Wallet/Ledger/Transaction/Beneficiary/Notification tables exist in any migration.

### External integrations

| Integration | Status | Evidence |
|---|---|---|
| Keycloak (OIDC/JWT) | **Real** | `SecurityConfig.java` resource-server JWT validation; real realm file `infra/keycloak/realm/neos-realm.json`; local Keycloak via `infra/keycloak/compose.yml` |
| Sumsub (KYC vendor) | **Absent** (config placeholder only) | `SumsubProperties.java` has no implementing adapter |
| KYC overall | **Mock**, default and only working mode | `MockKycProvider.java`, auto-approve in `KycStartService.java:99-108` |
| Formance (ledger) | **Absent** | No dependency in `pom.xml`, no package anywhere |
| Sponsor bank / payments | **Absent** | `SponsorBankPort.java` empty interface only |
| FX-rate source | **Absent** | No code found |
| SQS | **Absent** | No dependency |
| Email/SMS/push | **Absent** | `NotificationPort.java` empty interface only |
| Postgres + Flyway | **Real** | Live deps, 2 applied migrations, `ddl-auto: validate` |
| Redis | **Absent** | Not in `pom.xml`, not configured |

---

## 4. Frontend Inventory

### Stack / config
- `mobile/.env.example`:
  ```
  EXPO_PUBLIC_APP_DEMO_MODE=true          # demo mode ON by default
  EXPO_PUBLIC_API_URL=http://localhost:8080
  EXPO_PUBLIC_KEYCLOAK_ISSUER=http://localhost:8081/realms/neos
  EXPO_PUBLIC_KEYCLOAK_CLIENT_ID=neos-mobile
  EXPO_PUBLIC_REDIRECT_URI=neos://auth
  EXPO_PUBLIC_TERMS_VERSION=1.0
  EXPO_PUBLIC_DEMO_KYC_RESULT=APPROVED    # steerable mock KYC outcome
  ```
- `mobile/src/config.ts:1-10` — `demo` defaults **true** unless the env var is literally `'false'` (line 2). This is the master switch.
- `mobile/src/services/index.ts:5-6` — `services = config.demo ? demo.services : realServices`. Single switch point for the entire data layer.

### Auth flow — **real**, but gated behind the demo flag
- `mobile/src/services/real.ts:150-175` — genuine OIDC PKCE flow (`AuthSession.AuthRequest({ usePKCE: true, ... })`, `exchangeCodeAsync`), token refresh at lines 47-71, revoke-on-signout at 186-199, tokens persisted via `expo-secure-store`.
- Bearer token attached in the shared `api()` helper: `real.ts:80` (`Authorization: Bearer ${token}`).
- This path exists and is wired correctly, but is **never reached** while `EXPO_PUBLIC_APP_DEMO_MODE=true` (the shipped default) — `DemoAuth.tsx` is used instead (only rendered `if config.demo`, `App.tsx:33`).
- The repo's own `mobile/VALIDATION.md` states the real Keycloak round-trip "has not been validated against a live authenticated environment here."

### Per-screen inventory

| Screen | Data / actions | Classification | Evidence |
|---|---|---|---|
| Welcome | Static copy; branches to demo sign-up vs real `signIn()` | n/a (routing only) | `screens/Welcome.tsx:18,23` |
| DemoAuth | Email/password form | **MOCK**, demo-only screen | `mocks/demo.ts:72-88`; fixed seed account `khalil@gmail.com` at `mocks/demo.ts:11` |
| Onboarding (intro/details/review) | Phone, terms acceptance | **HYBRID** — `mocks/demo.ts:98-103` (setTimeout) in demo; **REAL API** `POST /api/onboarding/personal` otherwise (`services/real.ts:205-211`) |
| KYC capture (document/selfie) | "Capture" UI | **MOCK always**, regardless of demo flag — simulated with `setTimeout(...850)`, no real camera/liveness capture exists at all | `screens/Kyc.tsx:28-34` |
| KYC submit | Triggers verification | **HYBRID** — mock outcome steerable via `EXPO_PUBLIC_DEMO_KYC_RESULT` / dev panel in demo (`mocks/demo.ts:60-69`); **REAL API** `POST /api/kyc/start` otherwise (`services/real.ts:137`) |
| Processing | Pure animation | n/a — `setInterval`, `screens/Kyc.tsx:166`, no data |
| StatusScreen | KYC/account status | Derived from local state only, no own fetch |
| **Home (wallets/balances)** | MUR/USD/EUR balances, send/receive/convert buttons, notifications bell | **MOCK, permanently, independent of demo flag** — hardcoded wallet array (`screens/Home.tsx:27-31`, comment at line 26 admits balances must come from the ledger once connected), balance always rendered as zero (lines 39-40). Actions open a "coming soon" sheet (line 162). Notifications bell opens a static empty-state sheet. **There is no wallet/balance/notification API anywhere in the backend to call.** |
| Profile | Identity display | Reflects whichever profile (demo-seeded or real) is in session state |
| Dev panel | Force KYC outcome/failure for QA | `App.tsx:44-88`, gated `__DEV__ && config.demo` — confirms the mock is deliberately steerable tooling, not an accidental leftover |

### Mock-pattern grep results (frontend)
Hits confined to: `services/timeout.ts` / `services/real.ts` (legitimate request-timeout `setTimeout`, not fake data), `services/index.ts` (the demo/real switch), `screens/Kyc.tsx` (simulated capture delay), `mocks/demo.ts` (the entire mock service layer), `locales/en.ts` (demo-mode UI copy strings), `hooks/useJourney.ts:126` (fake KYC processing delay). No `faker`, `msw`, `json-server`, or stray fixture JSON files were found. No hardcoded IBANs; the only hardcoded financial data is the Home screen's zero-balance wallet array noted above.

### Consolidated real API calls (frontend → backend, complete list)

| Method | Path | Frontend call site | Matching backend endpoint |
|---|---|---|---|
| GET | `/api/auth/me` | `services/real.ts:115` | `AuthController.java:16` ✅ |
| POST | `/api/onboarding/personal` | `services/real.ts:211` | `PersonalOnboardingController.java:25` ✅ |
| POST | `/api/kyc/start` | `services/real.ts:137` | `KycController.java:25` ✅ |

---

## 5. Contract Cross-Check

All three real frontend calls were checked field-by-field against the backend DTOs (not just path/method):

**`GET /api/auth/me`**
- Backend returns `Map.of("subject", ..., "username", ..., "email", ...)` — `AuthController.java:22-28`.
- Frontend `Identity` type: `{ subject, username, email }` — `mobile/src/types/domain.ts:4-8`. **Exact match.**

**`POST /api/onboarding/personal`**
- Backend `PersonalOnboardingRequest` record: `phoneNumber?, installationId, platform ("ANDROID"|"IOS"|"WEB"), termsVersion` — `onboarding/api/PersonalOnboardingRequest.java:7-26`.
- Frontend builds: `{ phoneNumber, installationId, platform: 'ANDROID'|'IOS', termsVersion }` — `real.ts:205-210`. Frontend type restricts `platform` to `'ANDROID'|'IOS'` only (`domain.ts:18`), narrower than backend's allowed `WEB` — harmless, frontend simply never sends `WEB`.
- Backend response: `{ userId, deviceId, kycStatus, termsVersion }` — `PersonalOnboardingResponse.java:7-12`. Frontend type matches exactly (`domain.ts:21-26`) — **but the frontend ignores `kycStatus` from the response** and hardcodes local state to `'NOT_STARTED'` instead (`real.ts:213`). Not a crash risk, but a latent inconsistency: if the backend ever returns a non-`NOT_STARTED` status here, the frontend will silently disagree with it.

**`POST /api/kyc/start`**
- Backend ignores the request body entirely (uses `jwt.getSubject()` only, `KycController.java:27-32`); frontend sends `{}` (`real.ts:137`) — consistent, no mismatch.
- Backend response `KycStartResponse`: `{ userId, status, provider, verificationMode }` — `KycStartResponse.java:7-12`. Frontend type matches exactly (`domain.ts:27-32`). **Exact match.**

**Contract gap (not a field mismatch, a missing capability):** the frontend's `kyc.refresh` is implemented as a re-call to `startKyc()` (`real.ts:217`: `kyc: { submit: startKyc, refresh: startKyc }`), because **no `GET /api/kyc/status` (or similar polling) endpoint exists on the backend** — confirmed absent from the endpoint list in Section 3, and explicitly flagged in a frontend comment (`real.ts:119`: *"No GET status endpoint exists."*). Re-triggering KYC start instead of polling status works today only because KYC auto-approves instantly; it will break the moment KYC becomes asynchronous (real Sumsub), since repeated "start" calls are not a substitute for status polling.

### Endpoints never used by the frontend
None — all 3 real backend endpoints (excluding `/actuator/health`) are called by the frontend.

### Frontend calls with no backend endpoint
None in the "real" service layer — the frontend was built narrowly enough to only call what exists. All wallet/transfer/FX/etc. screens simply never attempt a network call for those features (they're mock/local-state), so there's no broken call to find — the gap is at the feature level, not the wiring level.

---

## 6. Runtime Check

Not performed. Starting the backend requires a running PostgreSQL instance and the dev Keycloak container (`infra/keycloak/compose.yml`), and this audit was scoped to static, read-only analysis with no environment setup. Noted as a gap rather than attempted and guessed at — this is UNVERIFIED. If you want, I can start Postgres via `infra/keycloak/compose.yml` (does it include Postgres? UNVERIFIED — only Keycloak was named) plus `./mvnw spring-boot:run` and curl the 3 endpoints in a follow-up pass with your approval, since that would involve starting background services rather than pure reading.

---

## 7. Feature Status Matrix

| # | Feature | Frontend UI exists? | Data source | Backend endpoint | Backend logic | External integration | Overall | Evidence |
|---|---|---|---|---|---|---|---|---|
| 1 | Personal registration + KYC submission | Yes | Hybrid (mock by default, real when demo off) | `POST /api/onboarding/personal`, `POST /api/kyc/start` | Real persistence; KYC auto-approve is mock | Keycloak real; KYC vendor mock | 🟡 Partially wired | `real.ts:211`, `KycStartService.java:99-108` |
| 2 | Business registration + KYB submission | No | — | None | Missing | None | ❌ Not implemented | No `kyb`/`business` package or screen found |
| 3 | Login (OTP/2FA/biometrics/sessions) | Login yes; OTP/2FA/biometrics/device-session mgmt no | Hybrid (mock `DemoAuth` vs real OIDC) | Keycloak (external, not a custom endpoint) | Real for base login; no OTP/2FA/biometric/session-list logic anywhere | Keycloak real | 🟡 Partially wired | `real.ts:150-175`, `screens/DemoAuth.tsx` |
| 4 | Wallets & balances (available vs pending) | Yes | **Mock, permanent** | None | Missing | None | 🎭 UI mock only | `screens/Home.tsx:27-40` |
| 5 | Top-up instructions (bank details + reference) | No | — | None | Missing | None | ❌ Not implemented | No evidence found |
| 6 | Incoming transfer recognition / backoffice review | No | — | None | Missing | None | ❌ Not implemented | No backoffice module exists |
| 7 | Internal (user-to-user) transfer | "Send" button opens placeholder sheet only | Mock | None | Missing | None | 🎭 UI mock only | `screens/Home.tsx:162` ("coming soon" sheet) |
| 8 | Beneficiary management | No | — | None | Missing | None | ❌ Not implemented | No evidence found |
| 9 | External transfer + status tracking | No | — | None | Missing | None | ❌ Not implemented | No evidence found |
| 10 | FX conversion (quote/rate lock/confirm) | "Convert" button opens placeholder sheet only | Mock | None | Missing | None | 🎭 UI mock only | `screens/Home.tsx:162` |
| 11 | Transaction history + PDF receipts | No | — | None | Missing | None | ❌ Not implemented | No evidence found |
| 12 | Notifications (push/email/SMS) | Bell icon, static empty state | Mock | None | `NotificationPort.java` empty interface, no implementation | Absent | 🎭 UI mock only | `NotificationPort.java:3` |
| 13 | Customer support entry point | UNVERIFIED — no dedicated screen found in fork reports | — | None | Missing | None | ❌ Not implemented (UNVERIFIED absence) | Not located during inventory |
| 14 | Backoffice: users/KYC review/block/alerts | No | — | None | No backoffice module/package exists | None | ❌ Not implemented | Package listing shows no `backoffice` package |
| 15 | Basic reconciliation (statement import, ledger vs bank) | No | — | None | Missing (no Ledger module, no Formance) | Absent | ❌ Not implemented | No evidence found |

**Legend:** ✅ Fully wired · 🟡 Partially wired · 🎭 UI mock only · ❌ Not implemented

**Tally:** 0 fully wired · 2 partially wired (#1, #3) · 4 UI-mock-only (#4, #7, #10, #12) · 9 not implemented. On a lenient reading where "partially wired" counts as half, that's roughly **13%** (2/15) truly end-to-end, consistent with the executive summary.

---

## 8. Mock Inventory

| Location | What it fakes |
|---|---|
| `mobile/src/mocks/demo.ts` (whole file) | Entire backend — auth, onboarding, KYC — behind the default-on demo flag. `wait()` (setTimeout) fakes latency; `DEMO_FALLBACK_EMAIL = 'khalil@gmail.com'` is a seeded fake account (line 11); KYC outcome steerable via `EXPO_PUBLIC_DEMO_KYC_RESULT` |
| `mobile/src/config.ts:2` | `demo` flag defaults `true` — the master switch for the above |
| `mobile/src/screens/Kyc.tsx:28-34` | Document/selfie "capture" is always a `setTimeout` simulation, demo flag irrelevant — no real camera/liveness code exists at all |
| `mobile/src/screens/Home.tsx:27-40` | Hardcoded MUR/USD/EUR wallet list, balance always zero, independent of demo flag — no wallet backend exists to call instead |
| `mobile/src/screens/Home.tsx:162` | "Coming soon" placeholder sheet behind send/receive/convert actions |
| `mobile/src/App.tsx:44-88` | Dev panel to force KYC outcomes — intentional QA tool, gated to `__DEV__ && demo` |
| `src/main/java/com/fintech/kyc/adapter/mock/MockKycProvider.java` | Fake KYC vendor — fake applicant IDs (`"mock-" + userId`), fake session tokens, synchronous auto-approve |
| `src/main/java/com/fintech/kyc/KycStartService.java:99-108` | Backend-side auto-approve logic layered on top of the mock provider — explicitly documented in-code as a placeholder for the real Sumsub flow |

No `msw`, `json-server`, `faker` library, or standalone JSON fixture files were found anywhere in the repo.

---

## 9. Risks for a Client Demo

1. **Home screen is the highest-risk surface.** It looks like a fully functional wallet dashboard (balances, send/receive/convert, notifications) but every element is either hardcoded zero or a placeholder sheet. Any client who taps past the onboarding flow will hit this immediately.
2. **Demo mode is the shipped default.** If the app is handed to anyone with the stock `.env`, they will see the mock login/onboarding/KYC flow, not the real backend — even though real onboarding and KYC endpoints exist and work.
3. **KYC "approval" is instantaneous and unconditional** even on the real backend path — there is no actual decisioning, document checks, or vendor round-trip. A live demo of "real" KYC will still just be auto-approving itself.
4. **No business/KYB, transfers, FX, beneficiaries, statements, or backoffice exist at all** — if the client's script touches any of these, there is nothing behind the UI (for most, there isn't even UI).
5. **`kyc.refresh` re-triggers KYC start rather than polling status** (`real.ts:217`) — fine today only because approval is instant; will silently misbehave the moment a real async KYC vendor is wired in, since there's no status-check endpoint for it to call instead.

---

## 10. Open Questions / Could Not Verify

- Whether `application-prod.yml` has a working Keycloak issuer / secrets setup for a real deployment — only DB vars were confirmed present (UNVERIFIED).
- Whether `infra/keycloak/compose.yml` also provisions Postgres, or only Keycloak (UNVERIFIED — not opened in this pass).
- Whether a customer-support entry point exists anywhere in the frontend — not located by the inventory pass; could exist under a screen name not matching common patterns (UNVERIFIED, leaning "absent").
- Whether setting `KYC_MODE=SUMSUB` actually fails to start the app, versus some unseen fallback — inferred from absence of any `KycProvider` implementation for Sumsub, but not runtime-tested (UNVERIFIED).
- No runtime check was performed at all (Section 6) — every "Real" classification in this report is based on static code reading (persistence calls, JWT validation wiring, etc.), not an observed live response.
