# Mobile Gap Analysis vs. GitHub Acceptance Criteria

- **Criteria:** `docs/product/MOBILE_ACCEPTANCE_CRITERIA.md` (issues #17, #22–#29, all in milestone V1).
- **Code read:** `feat/mobile-on-integration` at `2e05d7a`. Backend contract: `docs/api/MOBILE_API_CONTRACT.md`.
- **Method:** static reading only. Nothing was run for this analysis. Device and runtime claims rely on `mobile/VALIDATION.md`, and are marked **UNVERIFIED** where not re-checked.

## Status legend

| Status | Meaning |
|---|---|
| DONE | Implemented and evidenced |
| PARTIAL | Partly implemented |
| NOT STARTED | Nothing implemented |
| BLOCKED-BACKEND | Needs a backend endpoint or fix |
| BLOCKED-EXTERNAL | Needs a sponsor bank, FX, SWIFT/SEPA or the Sumsub SDK |

**Key fact for D1–D8.** The issues ask for **shells with mock/preview data**. The current mock system is **dev-build-only**:
- `resolveMockFeatures` returns an empty set when not in dev (`mobile/src/mockFeatures.ts:65-70`).
- Mocks are loaded only under `__DEV__` (`mobile/src/services/index.ts:53-59`).
- Release builds are checked to contain zero mock markers (`mobile/scripts/check-release-bundle.mjs:1-14`).

So in a staging or release build, every shell would show "not available" instead of preview data (`mobile/src/services/unavailable.ts:6-22`). See Contradiction C1.

---

## 1. B5 (#17): Android shell + secure navigation/auth state

| Criterion | Status | Evidence | Frontend work |
|---|---|---|---|
| App runs on a real Android device | PARTIAL | Only an emulator is evidenced: Pixel 8 emulator, API 35 (`mobile/VALIDATION.md:3`), and `app:assembleDebug` PASS (`:16`). Physical device steps are documented but not evidenced (`mobile/README.md:16`). | Install on a physical Android phone. For a device not on localhost, set `EXPO_PUBLIC_API_URL` and `EXPO_PUBLIC_KEYCLOAK_ISSUER` to a LAN-reachable host, and record the result in `VALIDATION.md`. |
| Navigation between V1 floor screens works | PARTIAL | These routes exist: welcome, auth, onboarding (intro/details/review), KYC (kycIntro/document/selfie/pending/rejected/moreInfo/approved/ready), home, profile, restricted (`mobile/src/types/domain.ts` `Route`, `mobile/App.tsx:40-47`). **No D2–D8 screens exist.** Quick actions open a "coming soon" sheet (`mobile/src/screens/Home.tsx:208-216`). | Add routes and screens for D2–D8 (see below) and a way into each. |
| Auth state: logged out → onboarding/login | DONE | `routeFor` returns `welcome` with no profile (`mobile/src/navigation/state.ts:5`), and `intro` when not onboarded (`:7`). Tested at `mobile/tests/journey.test.ts:27-29`. | – |
| Auth state: authenticated + KYC pending → restricted state | DONE (by interpretation) | `PENDING` / `IN_REVIEW` → `pending` screen, with no Home access (`state.ts:11-13`). `restricted` is reserved for `SUSPENDED` / `CLOSED` (`state.ts:6`). | Confirm the client accepts the "pending" screen as the "restricted state". Add `RESUBMISSION_REQUIRED` → `moreInfo` (contract §8.2 #1). |
| Auth state: authenticated + approved → home | DONE | `APPROVED` + `ACTIVE` → `approved`, then `home` (`state.ts:18-19`). Tested at `journey.test.ts:30-32`. | Do not derive `ACTIVE` from `APPROVED` (`mobile/src/services/real.ts:144`). This is BLOCKED-BACKEND: no account-status endpoint exists. |
| Build runs through Expo tooling | DONE | `npx expo run:android` (`mobile/scripts/android.ps1:22`). Expo SDK 55 (`mobile/package.json:21`). EAS profiles exist (`mobile/eas.json`). | – |
| Dev-client/prebuild-compatible setup | PARTIAL | Prebuild is used: native folders are generated from `app.config.ts` (`mobile/README.md:18`, `mobile/app.config.ts:54`). `eas.json` sets `developmentClient: true` (`mobile/eas.json:5`), **but `expo-dev-client` is not a dependency** (`mobile/package.json:19-32`). | `npx expo install expo-dev-client` (small). This also prepares for the Sumsub native SDK. |
| No secrets embedded in the bundle | DONE | Only public `EXPO_PUBLIC_*` values are used (`mobile/src/config.ts`, `mobile/README.md:99`). PKCE public client, no client secret (`README.md:89`). Release-bundle mock-marker scan (`mobile/scripts/check-release-bundle.mjs`). | Optionally add a secret-pattern scan to `check-release-bundle`. |

**B5 overall: PARTIAL.** The open items are a physical device run, D-screen navigation, and `expo-dev-client`.

---

## 2. D1–D8 common criteria

| Criterion | D1 | D2–D8 |
|---|---|---|
| Renders with mock/preview data | PARTIAL: dev only. Wallets are a zero-balance mock (`mobile/src/mocks/wallets.ts:5-9`). In a non-dev build, `wallets.list()` throws `featureUnavailable` (`unavailable.ts:12-15`). | NOT STARTED: no screens. D7 has an empty sheet (below). |
| Clearly marked in code/config as a shell | PARTIAL: a code comment (`Home.tsx:24`) and the mock registry (`mobile/src/mocks/registry.ts`). The UI shows the "Preview balances" tag only when wallets are mocked (`Home.tsx:199-204`, `mobile/src/locales/en.ts:132`). | NOT STARTED |
| Does NOT imply that real money moved | DONE: zero balances; actions say "coming in the next release" (`en.ts:150-151`); empty activity (`Home.tsx:264-279`). | Holds trivially (no screens). Must be preserved. |
| Does NOT call backend endpoints that create financial state | DONE: Home calls only `wallets.list` / `notifications.list` (`Home.tsx:74, 82`). No transfer or credit call exists in `mobile/src`. | Holds trivially. **D2 must not call `POST /api/transfers/internal`** (see C2). |

## 3. Screen-specific criteria

| Issue | Criterion | Status | Evidence | Frontend work |
|---|---|---|---|---|
| D1 #22 | Total balance preview | PARTIAL / BLOCKED-EXTERNAL for a real total | Home shows the **selected wallet's** balance labelled "Wallet balance" (`Home.tsx:164-205`, `en.ts:133`). It is not a total. A real total across currencies needs FX (#37, V3). | Add a "Total (preview)" figure from preview data with a "Preview" tag. Do not compute a real total client-side. |
| D1 #22 | Wallet list | DONE (preview) / BLOCKED-BACKEND for real | Wallet grid and picker (`Home.tsx:222-262, 293-330`). Only MUR/USD/EUR are mocked (`wallets.ts:5-9`); the backend has 6 currencies. | Real data: needs `GET /api/wallets` (proposal: `docs/api/PROPOSED_BALANCE_API.md`). Fix `formatMinor` for TND scale 3 (`Home.tsx:51-53`). |
| D1 #22 | Quick actions send / receive / convert / add / history | PARTIAL | Four actions exist: add, send, receive, convert (`Home.tsx:25-30`). **There is no "history" action.** All open "coming soon" (`:213`). | Add a "history" action. Route each action to its D screen. History can be real (`GET /api/transactions` exists, contract §6) or a preview. |
| D2 #23 | Beneficiary, currency, amount, fee/rate/ETA preview; internal + international visual states | NOT STARTED (UI) / BLOCKED-EXTERNAL (real fee, rate, ETA, international: #38 V3) | – | Static multi-step shell. Internal and international tabs. Fee, rate and ETA shown as labelled preview values. The final button must not submit (e.g. "Preview only — sending is not available yet"). |
| D3 #24 | Master-account details + unique-reference / virtual-account placeholder | NOT STARTED / BLOCKED-EXTERNAL (sponsor bank, #33/#32 V2) | – | Static screen with placeholder bank details, clearly marked "Example details — do not send money". |
| D4 #25 | Source wallet, target wallet, rate/fee preview | NOT STARTED / BLOCKED-EXTERNAL (FX, #37 V3) | – | Static shell. Wallet pickers can reuse the Home wallet list. The rate is marked "Indicative preview". No submit. |
| D5 #26 | Beneficiary add/list UX (optional non-financial Postgres persistence) | NOT STARTED. Persistence would be BLOCKED-BACKEND (no beneficiaries endpoint). | – | List plus add form with preview entries in local state. Do **not** persist IBAN/PII on the device without a decision. |
| D6 #27 | Passkeys / TOTP / biometrics / trusted-device list preview | NOT STARTED (Profile has one static "Security" row: `Home.tsx:112-113`, `en.ts:161`). Real features are V2 (#35). | – | Static settings screen with disabled toggles and a preview device list ("This device" from the installation id is fine). |
| D7 #28 | Notification center with mock entries | PARTIAL | A bell opens a sheet (`Home.tsx:79-83, 331-346`), but the mock returns `[]` (`mobile/src/mocks/notifications.ts`), and in a non-dev build it shows `featureUnavailable`. There is no backend feed (email only, contract §7.6). | Add a few clearly fake entries (e.g. "Welcome to NEOS", "Identity verified"), or a full-screen list. Do not mirror real transactions as notifications. |
| D8 #29 | Business onboarding entry (shell unless KYB pulled in) | NOT STARTED | Account type is personal only (`en.ts:57`, onboarding flow). | An entry card ("Open a business account — coming soon") on Welcome/intro or Profile. |

**Update 2026-11-06 (design refresh, `feat/mobile-design-refresh`): D1 #22 quick actions, deviation resolved.** The criterion lists "send / receive / convert / add / history". Home has four quick actions: Add money, Send, Convert and **Details**. Details opens the "Account details" sheet (email and user ID with Copy and Share, plus a reserved block for bank details once top-up exists): this is the "receive" function under the name Details. History is the Activity tab and the "Recent activity" list on Home (which follows the selected wallet). A short-lived version without a receive action (moved to Profile) was replaced the same day.

---

## 4. Contradictions and open points

| # | Contradiction | Evidence | Proposed resolution (needs a decision) |
|---|---|---|---|
| C1 | **"Renders with mock/preview data" vs. the SEC-01 rule "no mocks outside dev builds".** A validation build made with the staging or production profile shows no preview data. | `mockFeatures.ts:65-70`, `services/index.ts:53-59`, `check-release-bundle.mjs`, `docs/audits/FRONTEND_AUDIT.md` SEC-01 | Option A: demo Friday from a **development build** (`eas.json` `development`, mocks allowed). Option B: add a separate, explicit **"shell preview" layer**. It would hold static illustrative content, carry a permanent visible "Preview" tag, perform no service calls, and appear in a registry of shell screens. It must be kept distinct from service mocks so SEC-01 stays intact. **Decide before Monday.** |
| C2 | **The "send money" request vs. D2 and #30.** D2 says "Does NOT call backend endpoints that create financial state", and Nova-to-Nova transfer is **V2** (#30). The backend nevertheless ships `POST /api/transfers/internal` behind a flag. | Issue #23, issue #30, contract §5 | Keep D2 a shell for V1. Do not wire the real transfer. If the client wants a real P2P demo, that pulls #30 into V1 and needs the transfer error-contract fixes (contract §9.3). |
| C3 | **D1 "total balance" vs. the FX dependency.** A total across currencies needs rates (#37, V3). | Issue #22, #37 | Show a total only as preview data. Real balances are per currency only. A real total needs a later endpoint with explicit rates (proposal §4). |
| C4 | **D1 shell vs. showing real balances.** The backend's stated priority is a balance endpoint. Real (synthetic-credit) balances are not "mock/preview" data, and a non-zero real number risks "implying real money moved". | Issue #22, #20 C3 ("NON-PRODUCTION synthetic") | If real balances are shown, label them "Test funds — sandbox". This is a read-only call, so it does not violate "no financial state" and it closes C2's "Balance displays as zero in the app". Product owner to confirm. |
| C5 | **Recipient identification.** D2 and D5 imply a beneficiary concept (name, IBAN, or another NEOS user). The backend identifies recipients by **NEOS `userId` UUID only**, with no lookup. | Contract §5, §7.4 | Shell only for V1. For V2, ask the backend for a beneficiary or lookup model (phone, email or handle → userId). |
| C6 | **The KYC flow in B5/B3 vs. what exists.** B3 #15: "User completes ID + selfie + liveness flow in-app". The mobile capture is a timer mock (`registry.ts:43-49`). The backend has **no Sumsub SDK-token endpoint**. | Contract §4, §7.2 | B3 is BLOCKED-BACKEND plus BLOCKED-EXTERNAL (Sumsub native SDK). It is not in the stated mobile scope. For Friday, run backend `KYC_MODE=MOCK` (auto-approve), and say so explicitly. |
| C7 | **"KYC pending → restricted state".** The app has a separate `pending` screen and `restricted` route. | `state.ts:6, 11-13` | The two are equivalent in effect. Confirm with the client. |
| C8 | **The C2 and C4 issues are closed, but their app-visible criteria are not met.** "Balance displays as zero in the app": the zero comes from a mock, not the backend. "Synthetic credit appears in transaction history": the app does not call history. | #19, #21 | Real wallets (new endpoint) plus real history (existing endpoint) would close the app side. These are the SHOULD items below. |

---

## 5. Friday plan (validation Fri 2026-11-06, about 4 mobile days: Mon 2 – Thu 5)

Assumptions:
- (a) Existing backend: auth, onboarding, KYC (MOCK mode), and `GET /api/transactions`.
- (b) The backend developer delivers `GET /api/wallets` by about Wednesday.
- (c) One mobile developer.
- The decision on C1 is made Monday morning.

### MUST for Friday

| # | Item | Criteria closed | Est. |
|---|---|---|---|
| M1 | Decide C1. If Option B, add a `ShellPreview` tag and component plus a shell-screen registry. | D1–D8 "clearly marked as a shell" | 0.5 d |
| M2 | Navigation: add routes for D2–D7 (D8 can be a card). Home quick actions go to screens instead of the "coming soon" sheet. Add a **history** action. | B5 navigation, D1 quick actions | 0.5 d |
| M3 | Static shells D2, D3, D4, D6 with preview content and **no submit**. The final CTA is disabled with honest copy. | D2, D3, D4, D6 (all 5 criteria each) | 1.5 d |
| M4 | D7 notification list with mock entries. D8 business entry card. | D7, D8 | 0.25 d |
| M5 | D1: add a total-balance preview (preview data, tagged) next to the existing wallet list. | D1 | 0.25 d |
| M6 | B5: run on a **physical Android device**; add `expo-dev-client`; record results in `VALIDATION.md`. | B5 device, dev-client | 0.5 d |
| M7 | Map `RESUBMISSION_REQUIRED` → `moreInfo` (`domain.ts:1`, `real.ts:129-135`). This is a one-line safety fix for a real backend value. | B5 auth routing (robustness) | 0.1 d |

### SHOULD (if time or backend allow)

| # | Item | Depends on |
|---|---|---|
| S1 | Real wallets on D1 via `GET /api/wallets` (per-currency available/pending, scale-aware formatting, "Test funds" label per C4). Fix TND scale. | Backend (b) |
| S2 | Real history list (Home "Recent activity" plus history screen) via `GET /api/transactions`: per currency, cursor paging, tolerate empty pages. Closes the app side of C4 #21. | Local Formance, a synthetic credit for data |
| S3 | D5 beneficiaries with local-only list (no PII persistence). | – |
| S4 | Live end-to-end run against the backend (Keycloak `neos://auth` redirect added, ports fixed: backend 8080, Formance 8083, Metro 8082). | Env (audit 2 §3) |

### UNAVAILABLE: show clearly in the app (suggested wording)

| Feature | Reason | Suggested UI copy |
|---|---|---|
| Sending money (internal and international) | V2 #30 / V3 #38 | "Preview — sending money isn't available yet. Nothing will be sent." |
| Add money / top-up | V2 #33 (sponsor bank) | "Example details for illustration only. Do not transfer money to these details." |
| Currency conversion | V3 #37 (FX) | "Indicative preview rate. Conversion isn't available yet." |
| Total balance in one currency | Needs FX (#37) | "Preview total" tag, or omit it and show per-currency balances only. |
| Passkeys / TOTP / biometrics / trusted devices | V2 #35 | Disabled toggles: "Coming soon." |
| In-app notifications | No backend feed (email only) | "Preview notifications. You'll receive real account updates by email." |
| Business account (KYB) | Not in V1 | "Business accounts are coming soon." |
| In-app ID + selfie capture (Sumsub) | No SDK-token endpoint; native SDK not integrated | Keep the current simulated capture in dev. Present it as "Simulated verification (sandbox)". |

### Risks for Friday
- **C1 is undecided.** If the demo build is not a dev build and Option B is not built, every shell shows "not available".
- **S1 and S2 need local Formance.** It is not in the repo (audit 2 §2, item 4). Budget backend-developer time to bring it up.
- The real adapter has **never** been validated against live Keycloak and the backend (`mobile/VALIDATION.md:50-52`).
