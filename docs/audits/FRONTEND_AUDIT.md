# Frontend Quality Audit — Tanit / Nova Pay / Neos (`mobile/`)

**Scope:** read-only engineering-quality audit of the `mobile/` React Native + Expo app on branch `feat/frontend` (commit `7e4e2a5`). One file created: this report. No source modified, no deps installed, no git state changed.

**Method:** every file under `mobile/src`, plus `App.tsx`, `app.config.ts`, `package.json`, `tsconfig.json`, `eslint.config.js`, `.prettierrc.json`, `.env.example`, `.gitignore`, `tests/journey.test.ts`, and `scripts/` was read in full. Safe checks (`tsc`, `lint`, `test`, `npm audit`) could **not** be run — see Section 2. Integration/backend analysis is intentionally out of scope (covered by `INTEGRATION_AUDIT.md`); this report is about engineering quality only.

Stack confirmed from code (not assumed): Expo ~55.0.31, React Native 0.83.10, React 19.2.0, TypeScript 5.9 strict (`package.json:17-38`, `tsconfig.json:3`). Custom navigation state machine (`src/navigation/state.ts`), plain-hook state via one `useJourney` hook (`src/hooks/useJourney.ts`), raw `fetch` (`src/services/real.ts:78`), `expo-auth-session` OIDC + PKCE (`src/services/real.ts:150-175`), `expo-secure-store` token storage (`src/services/storage.ts`). All as previously described — each verified.

---

## 1. Executive Summary

This is a **small, unusually disciplined codebase for its stage**. Within the narrow slice it implements (welcome → auth → personal onboarding → KYC → home), the engineering is clean: strict TypeScript with zero `any`/`@ts-ignore`/`console.log` found anywhere, a well-factored UI primitive library, typed domain enums, a single centralized async-action wrapper handling loading/error/locking, a root error boundary, Android hardware-back handling, and genuinely thoughtful accessibility and keyboard behavior. The mock/real seam is clean and swapped at exactly one line (`src/services/index.ts:6`). The real auth adapter is complete (PKCE, refresh, revoke, 401-retry, timeout) even though it is not the default path.

**Main strengths:** clear layered architecture; strong reusable component/theme system; typed status enums with a backend-wins navigation model and an exhaustive route resolver; tight error/reliability handling; double-submit guarding via a lock ref.

**Main weaknesses:** (1) the demo mock is **default-on** via an `EXPO_PUBLIC_*` flag with no build-time guard — a release build that forgets to flip it ships mock auth; (2) **no financial infrastructure exists yet** — no money type, no `Intl.NumberFormat`, no decimal/minor-unit handling — and the one money surface (Home balances) is a hardcoded zero preview; (3) a single flat `Route` string-union + one `useJourney` hook will not scale cleanly to ~10 transactional, parameterized journeys; (4) tests and the real-auth adapter don't overlap — `realServices` has **zero** test coverage; (5) no biometric/PIN confirmation primitive for the upcoming money-movement screens; (6) no `FLAG_SECURE`/screenshot protection on PII/KYC screens; (7) no `fr`/`ar` dictionaries and only scaffolded (non-functional) RTL.

**Production-readiness verdict: Not ready for production** as a fintech app — but **appropriately built for its stage**. What exists is MVP-grade on the onboarding slice and demo-grade overall. Nothing here needs a rewrite; the gaps are additive.

**What kind of app this currently is:** a **demo-grade onboarding prototype with an MVP-quality onboarding/KYC slice** and a deliberately isolated (but default-on) mock layer. It is not yet a transactional banking app in any part.

---

## 2. Checks Executed

| Command | Ran? | Result summary | Reason if not run |
|---|---|---|---|
| `npx tsc --noEmit` (`typecheck`) | Not run | — | `node_modules/` absent (verified); running would require `npm install`, which is forbidden |
| `npm run lint` (eslint) | Not run | — | Same — eslint not installed |
| `npm test` (`tsx --test tests/*.test.ts`) | Not run | — | Same — `tsx`/TS toolchain not installed |
| `npm audit` | Not run | — | No `node_modules`/resolved tree to audit; `npm audit` requires install |
| `npm outdated` | Not run | — | Same |
| Manual static read of all source | **Yes** | Basis for every finding below | — |
| `git`-level file/structure inspection | **Yes** | Tree, configs, `.gitignore`, scripts mapped | — |

Static-only caveat: every "VERIFIED" below is confirmed by reading code, not by executing it. No finding claims runtime verification.

---

## 3. Quality Scorecard

Weights per the brief (total 100). All 12 criteria had sufficient evidence to assess (none "Not Assessed").

| # | Criterion | Score | Weight | Justification (evidence) |
|---|---|---|---|---|
| 1 | Architecture | 8 | 10 | Clean layers: `screens/` `components/` `hooks/` `services/` `navigation/` `types/` `theme/` `locales/` `mocks/` `utils/`. Services hidden behind a `Services` interface (`types/domain.ts:58-67`) with one swap point (`services/index.ts:6`). Dependency direction is one-way (UI→hook→services→types). Navigation is a pure function of backend state (`navigation/state.ts:4-21`) — elegant for this stage. −2: all app state+actions funnel through one 173-line `useJourney` hook (`hooks/useJourney.ts`), a future bottleneck. |
| 2 | Code Quality | 7 | 8 | No `any`, `@ts-ignore`, or `console.*` anywhere (grep verified). Consistent naming, Prettier/ESLint configured. −3: dense parallel ternary chains (`screens/Kyc.tsx:194-240` four 6-way ternaries; `screens/Home.tsx:222-229`), a few non-null assertions (`mocks/demo.ts`, 7×), hardcoded `wallets` array. |
| 3 | Maintainability | 7 | 8 | Small files, comments placed where intent is non-obvious (`navigation/state.ts:3`, `services/real.ts:119`, `screens/Home.tsx:26`). Centralized error/lock wrapper aids debugging (`hooks/useJourney.ts:23-51`). −3: the god-hook and nested ternaries raise change-cost as journeys grow. |
| 4 | Scalability | 5 | 7 | **Primary structural concern.** `Route` is a flat string union (`types/domain.ts:33-50`) with no params — routes cannot carry a beneficiary id, quote, or transaction id. Navigation = one `routeFor` switch + a `previousRoute` map (`navigation/state.ts`). Fine for linear onboarding; absorbing ~10 parameterized, multi-step money journeys will bloat both `routeFor` and `useJourney` and force a params mechanism. See Section 5. |
| 5 | Reusability | 7 | 6 | Solid primitives in `components/ui.tsx` (Button, Field, Screen, Sheet, Badge, InfoRow, Intro, ErrorNotice, Icon) + a central `theme` token set (`theme/index.ts`). −3: some style fragments duplicated across screens (e.g. a `pressed` opacity style redefined in `Home.tsx:289` and inline elsewhere). |
| 6 | Performance | 7 | 7 | Timers/listeners all cleaned up (`screens/Kyc.tsx:23-27,166-168`, `components/ui.tsx:173-185,239-254`). Network calls use `AbortController` 15s timeout (`services/real.ts:74-75`) + `withTimeout` race (`services/timeout.ts`). Foreground re-check guarded by `locked` ref (`hooks/useJourney.ts:80`). No long lists yet (no FlatList needed). −3: `useJourney` returns a fresh object literal with many inline closures every render (`hooks/useJourney.ts:88-171`); nothing memoized, so the whole screen tree re-renders per state change. Negligible now, real once screens multiply. |
| 7 | Security | 6 | 15 | Strong auth foundation: SecureStore token storage (`services/storage.ts`), PKCE (`services/real.ts:156`), refresh + revoke-on-signout (`:47-71,186-199`), 401 retry-once-then-expire (`:89-95`), 30s expiry skew (`:49`). No secrets in bundle (only issuer/clientId/apiUrl in `EXPO_PUBLIC_*`, non-secret). Dev panel double-gated `__DEV__ && config.demo` (`App.tsx:19,44`) so it cannot ship. −: demo mock is **default-on** with no build-time guard (SEC-01); custom `neos://` scheme is Android-hijackable (SEC-02); no `FLAG_SECURE` on KYC/balance screens (SEC-03); default `apiUrl` is cleartext `http://` (SEC-04); `npm audit` unrun. |
| 8 | Testing | 5 | 10 | One suite, ~13 node:test cases, 204 lines (`tests/journey.test.ts`), covering demo-service lifecycle, `routeFor` transitions, trust-boundary precedence, `validPhone`, `authErrors`, timeout, and a "passwords never persisted" assertion (`:119-121`). Good logic coverage. −: tests exercise **only** the mock service + pure functions; `realServices` (the actual auth/token/fetch code) has zero coverage; no component/render tests; `scripts/*.mjs` are manual adb screenshot helpers, not automated E2E; suite couldn't be run here. |
| 9 | State Management | 7 | 8 | Single source of truth: `apply()` sets profile+route together from backend state (`hooks/useJourney.ts:19-22`); backend state wins (`navigation/state.ts:3`). `locked` ref serializes mutations, preventing stale-response races and double-submit (`:17,29`). `acknowledged` ref models session-local screens. −3: no server-state cache/refetch abstraction (fine now); all money state is local placeholder. |
| 10 | UI/UX | 7 | 7 | Loading/error/empty/success states present on every async screen (`busy`, `ErrorNotice`, `noActivity`, status screens). Android hardware back handled (`hooks/useJourney.ts:73-76`). Accessibility: roles, labels, `progressbar` with values, live regions, 44–48px targets (`components/ui.tsx` throughout). Reduce-motion respected (`:242`). Keyboard handling thorough. −3: only `en` exists — `fr`/`ar` are a TODO (`locales/index.ts:2`); RTL is scaffolded (`I18nManager.isRTL` for icon flip + `textAlign`, `components/ui.tsx:131,194,572`) but never enabled (no `allowRTL`/`forceRTL`) and no Arabic dictionary, so RTL is non-functional today. |
| 11 | Error Handling & Reliability | 8 | 9 | Typed `AppError` + message-key mapping (`services/errors.ts`), one `run()` wrapper centralizing try/catch/busy/lock/expiry (`hooks/useJourney.ts:23-51`), root `ErrorBoundary` (`components/ErrorBoundary.tsx`), network timeout + offline surfacing + user-driven retry. Non-idempotent POSTs are **not** auto-retried (only 401 refresh + GET-style restore), which is correct. Unknown KYC status rejected defensively (`services/real.ts:138`); `routeFor` switch is exhaustive over the typed union. −1: no crash/telemetry reporting hook. |
| 12 | Developer Experience | 7 | 5 | `strict` + `noUncheckedIndexedAccess` (`tsconfig.json:3`); ESLint (expo flat) + Prettier; scripts for typecheck/lint/format/test; `.env.example` matches `config.ts`; README + VALIDATION.md. −3: no CI workflow for `mobile/`, no `eas.json` build profiles (so no enforced prod config — ties to SEC-01), checks unrunnable without install. |

**Weighted overall score:**
`(8·10 + 7·8 + 7·8 + 5·7 + 7·6 + 7·7 + 6·15 + 5·10 + 7·8 + 7·7 + 8·9 + 7·5) / 100`
`= (80 + 56 + 56 + 35 + 42 + 49 + 90 + 50 + 56 + 49 + 72 + 35) / 100`
`= 670 / 100 =` **6.7 / 10**

(No criteria excluded, so no renormalization needed.)

---

## 4. Detailed Technical Findings

Grouped by criterion. Severity: Critical = financial error / credential-PII exposure / auth bypass; High = likely prod defect or blocker for upcoming journeys; Medium = compounding quality cost; Low = polish.

### Strengths (preserve these)
- **S-1** Centralized async wrapper with lock — `hooks/useJourney.ts:23-51`. Every action goes through `run()`, which sets busy, clears error, locks against concurrency, maps errors to typed keys, and clears processing in `finally`. This is the single best piece of engineering in the app; double-submit prevention and consistent loading/error states fall out of it for free.
- **S-2** Backend-wins navigation — `navigation/state.ts:3-21`. A pure `routeFor(profile, acknowledged)` with an exhaustive `switch` over typed KYC statuses. Deterministic, testable, and resistant to client/server drift.
- **S-3** Typed status enums + unknown-value rejection — `types/domain.ts:1-3`, `services/real.ts:128-138`. Backend statuses are a closed union; the real adapter throws on anything unexpected rather than rendering a broken state.
- **S-4** Complete real auth adapter — `services/real.ts:150-199`. PKCE, refresh with single-flight (`refreshTask`), revoke on signout, token expiry skew. Well beyond demo-grade.
- **S-5** Passwords never persisted — asserted by test `tests/journey.test.ts:119-121`; demo stores only email/profile. Good instinct for a fintech app.

### Security (criterion 7)

- **SEC-01 · Demo mock is default-on with no build-time guard** · **VERIFIED · Critical**
  - Location: `src/config.ts:2` (`demo: process.env.EXPO_PUBLIC_APP_DEMO_MODE !== 'false'`), `src/services/index.ts:6`, `.env.example:1` (`EXPO_PUBLIC_APP_DEMO_MODE=true`), absence of `eas.json`.
  - Evidence: `demo` is `true` unless the env var is *exactly* the string `'false'`. The mock auth accepts any email/password and seeds a logged-in account (`src/mocks/demo.ts:72-88,11`). There is no `eas.json` build profile and no CI pinning the flag for release builds.
  - Impact: a production/staging build that omits or misspells `EXPO_PUBLIC_APP_DEMO_MODE=false` ships an app where anyone logs in as a fake verified user against no backend. `EXPO_PUBLIC_*` values are inlined into the bundle at build time, so this is decided at build, silently, by an env default that fails *open*.
  - Recommendation: invert the default (treat unset as **not** demo), and/or gate demo additionally on `__DEV__` the way the dev panel already is (`App.tsx:19`), and add an `eas.json` release profile that sets the flag explicitly. Smallest fix: `demo: __DEV__ && process.env.EXPO_PUBLIC_APP_DEMO_MODE === 'true'`.
  - Effort: S

- **SEC-02 · OIDC redirect uses a hijackable custom scheme** · **RISK · Medium**
  - Location: `app.config.ts:7` (`scheme: 'neos'`), `src/services/real.ts:153` (`redirectUri: config.redirectUri` → `neos://auth`).
  - Evidence: redirect is a custom URL scheme, not a verified Android App Link / iOS Universal Link. A malicious app registering the same scheme can intercept the redirect.
  - Impact: PKCE (`:156`) prevents a stolen auth code from being exchanged, so this is not a full account takeover — but it is a weakened trust boundary for a fintech login and worth hardening before launch.
  - Recommendation: move to verified App Links (`autoVerify`) / Universal Links for the auth redirect; keep PKCE.
  - Effort: M

- **SEC-03 · No screenshot/recording protection on PII/KYC/balance screens** · **RISK · Medium**
  - Location: KYC capture + status (`src/screens/Kyc.tsx`), balances (`src/screens/Home.tsx:118-155`); no `FLAG_SECURE` / `expo-screen-capture` usage anywhere (verified absent).
  - Impact: once real document capture and real balances exist, these screens will be screenshot-able and visible in the app switcher. Low risk *today* (KYC images are fake silhouettes, balances are zero), but must be in place before real PII/money render.
  - Recommendation: add screen-capture protection on KYC and wallet/transaction screens when real data lands.
  - Effort: S (per screen)

- **SEC-04 · Cleartext HTTP default for API base URL** · **RISK · Low (today) / High (if it reaches a real env)**
  - Location: `src/config.ts:3` (`apiUrl: ... || 'http://localhost:8080'`).
  - Impact: the fallback is cleartext. Harmless for local dev, but there is no explicit prod enforcement of `https` and no Android network-security-config, so a misconfigured build could talk cleartext to a real host.
  - Recommendation: require `https` for non-localhost at startup; add an Android `networkSecurityConfig` disallowing cleartext in release.
  - Effort: S

### Scalability / Architecture (criteria 4, 1)

- **ARC-01 · Flat, parameter-less route model** · **VERIFIED · High (for upcoming journeys)**
  - Location: `src/types/domain.ts:33-50`, `src/navigation/state.ts:4-30`, `App.tsx:21-39`.
  - Evidence: `Route` is a flat union of screen names; `App.tsx` renders via an `if/else if` ladder; routes carry no parameters.
  - Impact: transactional journeys (open a specific beneficiary, confirm a specific FX quote, view transaction N, deep-link to a receipt) need per-route params and a navigation stack with history. The current `previousRoute` single-parent map (`:22-30`) cannot express "home → transactions → detail(id) → receipt(id)" or modal stacks. Each new journey also grows the single `useJourney` hook.
  - Recommendation: before building money journeys, either adopt a real navigator (React Navigation) or extend `Route` to a discriminated union carrying params and replace the parent-map with a history stack. Keep the backend-wins `routeFor` for the *auth/KYC gate*; layer the transactional navigator *inside* the authenticated state. See Section 5 — this is evolution, not rewrite.
  - Effort: L

- **ARC-02 · Monolithic `useJourney` hook** · **VERIFIED · Medium**
  - Location: `src/hooks/useJourney.ts:9-172`.
  - Evidence: one hook owns profile, route, busy, error, phone, accepted, processing, back-handling, foreground re-check, and every action (signIn/save/verify/refresh/signOut/reset/expire/scenario/failNext).
  - Impact: every screen depends on the whole hook; adding wallet/transfer/FX actions here makes it a merge-conflict magnet and a re-render hub (ties to PERF-01).
  - Recommendation: split into an auth/session context + per-feature hooks (`useOnboarding`, `useKyc`, later `useTransfers`) that consume it. Do this when the second authenticated feature lands, not before.
  - Effort: M

### Fintech readiness (criterion mapped to Section 6)

- **FIN-01 · No money representation or formatting infrastructure** · **VERIFIED · High (blocker for wallet/transfer/FX journeys)**
  - Location: balances are a string literal `t('zero')` (`src/screens/Home.tsx:39-40`); currency symbols hardcoded `Rs`/`$`/`€` (`:28-30`); no `Intl.NumberFormat`, no minor-unit/decimal type anywhere (verified absent).
  - Impact: there is no safe money type, no per-currency decimal handling (TND has 3 decimals; AED/GBP absent entirely), and no locale-aware/RTL numeral formatting. Any money screen built now would invent its own formatting ad hoc — exactly the drift to avoid in fintech.
  - Recommendation: build a money module first — amounts as integer minor units or decimal strings from the backend, a `formatMoney(amountMinor, currency, locale)` wrapper over `Intl.NumberFormat`, and a currency metadata table (decimals, symbol). No float arithmetic in the client. This must precede wallet/transfer/FX UI.
  - Effort: M

- **FIN-02 · No strong-confirmation (biometric/PIN/2FA) primitive** · **VERIFIED · High (for money movement)**
  - Location: no `expo-local-authentication` dependency (`package.json:17-30`); no confirm-step component anywhere.
  - Impact: transfers/FX/beneficiary-add will each need a step-up confirmation; there is no reusable pattern to hang it on. Better to design the primitive once, now, than retrofit per screen.
  - Recommendation: add a reusable `confirmSensitive()` gate (biometric with PIN fallback) and route money-moving actions through it, mirroring how `run()` centralizes async.
  - Effort: M

### UI/UX (criterion 10)

- **UX-01 · i18n is English-only; `fr`/`ar` unimplemented; RTL scaffolded but inert** · **VERIFIED · Medium**
  - Location: `src/locales/index.ts:1-5` (`t` returns `en[key]` only), `:2` (TODO for fr/ar), RTL hooks at `src/components/ui.tsx:131,194,572` with no `I18nManager.allowRTL/forceRTL` and no Arabic dictionary.
  - Impact: the brief requires EN/FR/AR incl. RTL. The *structure* is RTL-aware (start/end spacing, directional icons), which is good, but the feature does not exist yet and cannot be demoed. `t()` also has no interpolation/pluralization.
  - Recommendation: introduce a real i18n layer (locale selection + fr/ar dictionaries + `I18nManager` enablement) before claiming multilingual support; keep the existing start/end styling discipline.
  - Effort: M

### Code Quality (criterion 2)

- **CQ-01 · Dense nested ternary chains** · **VERIFIED · Low**
  - Location: `src/screens/Kyc.tsx:194-240` (four parallel 6-branch ternaries computing title/body/action/label), `src/screens/Home.tsx:141-154,222-272`.
  - Impact: correct but hard to scan and easy to desync when a status is added. 
  - Recommendation: replace with a per-status lookup object keyed by route/status.
  - Effort: S

### Performance (criterion 6)

- **PERF-01 · Unmemoized hook return re-renders whole tree** · **RISK · Low (today)**
  - Location: `src/hooks/useJourney.ts:88-171` returns a new object + new closures each render.
  - Impact: every state change re-renders the active screen subtree. Trivial now (one screen, few nodes); compounds as screens and lists arrive.
  - Recommendation: memoize action callbacks (`useCallback`) and the returned object (`useMemo`), or move to context + selectors when the tree grows.
  - Effort: S–M

### Testing (criterion 8)

- **TEST-01 · Real services and components untested** · **VERIFIED · Medium**
  - Location: coverage is `tests/journey.test.ts` only; `src/services/real.ts` and all components have none.
  - Impact: the code that will run in production (token refresh, 401 retry, request mapping) is the code with no tests; the mock that will *not* ship is the code with tests.
  - Recommendation: add tests for `real.ts` token/refresh/401 logic with a mocked fetch, and smoke-render key screens. Wire `npm test`/`typecheck`/`lint` into CI.
  - Effort: M

---

## 5. Architecture Assessment

Current structure (abridged):

```
mobile/
  App.tsx                 screen-selection ladder + dev panel + error boundary
  src/
    components/ ui.tsx, ErrorBoundary.tsx      design primitives
    hooks/ useJourney.ts                        all state + actions (single hook)
    navigation/ state.ts                        routeFor() + previousRoute + validPhone
    services/ index.ts(seam) real.ts demo-swap storage.ts errors.ts timeout.ts
    mocks/ demo.ts                              in-memory demo backend
    screens/ Welcome Onboarding Kyc Home DemoAuth
    types/ domain.ts                            enums, DTOs, Services interface, Route
    theme/ index.ts  locales/ en.ts+index  utils/ authValidation.ts
```

**Coupling / dependency direction** is healthy: screens depend on `useJourney` and `ui`; `useJourney` depends on the `services` seam; services depend only on `types`. The `Services` interface (`types/domain.ts:58-67`) is the contract both `realServices` and `createDemoServices` satisfy, and the swap is one line (`services/index.ts:6`). This is a genuinely good seam and should be kept.

**Will the custom navigation + single hook hold up for the rest of Phase 1?** For the *auth/KYC gate* — yes, keep it; `routeFor` as a pure function of backend state is the right model for "which gate is the user behind." For the *authenticated, transactional* part (wallets, top-up, transfers, beneficiaries, FX, history, receipts) — **no**. Those need parameterized routes, a history stack, and modal flows that the flat `Route` union and single-parent `previousRoute` map cannot express (ARC-01). The single `useJourney` will also accrete every new action (ARC-02).

**Is an architectural change justified — now, later, or not at all?** **Later, and incrementally — not a rewrite.** Recommended evolution: keep `routeFor`/`useJourney` as the top-level authenticated-state gate; introduce a real stack navigator *inside* the authenticated branch when the first transactional journey is built, and split feature hooks off `useJourney` at the same point. Do the money module (FIN-01) and confirmation primitive (FIN-02) *before* the first money screen so patterns are set once. There is no evidence justifying replacing the component layer, theme, services seam, or error model — those are assets.

---

## 6. Security & Fintech-Specific Assessment

Section-4 checklist, item by item (state today / readiness for money journeys):

- **Money representation** — No amounts handled anywhere; balance is the literal `t('zero')` (`screens/Home.tsx:39-40`); no arithmetic on amounts in the client (verified). *Adequate today (nothing to represent); NOT ready — no money type exists (FIN-01).*
- **Currency formatting** — No `Intl.NumberFormat`; symbols hardcoded `Rs/$/€` (`screens/Home.tsx:28-30`); AED/GBP absent; TND 3-decimal case unhandled; no locale/RTL numeral handling. *Not ready (FIN-01).*
- **Source of truth** — Balances are an explicit hardcoded "preview" (`screens/Home.tsx:26-31`, UI labels it `previewBalances`); identity/KYC/account come straight from the backend `profile`; no local financial derivation exists. *Honest and correctly scoped; the UI even tells the user these are previews.*
- **Duplicate-submission prevention** — Good: `locked` ref blocks concurrent actions (`hooks/useJourney.ts:29`), buttons disable + show spinner while busy (`components/ui.tsx:110,117`). No idempotency keys yet (no money ops), but the choke-point to add them exists. *Adequate; ready to extend.*
- **Strong confirmation for sensitive actions** — None (FIN-02). No biometric/PIN/2FA primitive. *Not ready.*
- **Time-sensitive data** — Token expiry handled with skew + refresh (`services/real.ts:49-71`). No quote/OTP countdowns (features don't exist). *Partial, adequate for what exists.*
- **Sensitive-data protection** — Passwords never persisted (test `journey.test.ts:119-121`); tokens in SecureStore (`services/storage.ts`); no `console` logging of anything (verified); terms open via `Linking.openURL` behind an `https` check (`screens/Onboarding.tsx:25,33`). Gap: no `FLAG_SECURE` on KYC/balance screens (SEC-03). KYC "documents" are fake silhouettes so no real PII is at risk yet.
- **Trust boundary** — Well-designed. "Backend state wins" (`navigation/state.ts:3`); account activation decided by backend (`services/real.ts:143`); restricted account overrides approved KYC and local acknowledgement (test `:70-80`); unknown statuses rejected (`real.ts:138`). In *demo* mode the client necessarily decides everything — acceptable and isolated. *No client-side authorization bypass in the real path.*
- **Mock isolation** — The demo *service* can leak into a release build via the default-on flag (SEC-01). The dev *panel* cannot — it is double-gated by `__DEV__ && config.demo` (`App.tsx:19,44`), and `__DEV__` is false in release. So the risk is specifically the mock data/auth, not the QA panel. *Needs the SEC-01 fix before any real build.*
- **Status fidelity** — Strong: `BackendKycStatus`/`KycStatus`/`AccountStatus` typed unions (`types/domain.ts:1-3`), exhaustive `routeFor` switch, real adapter rejects unknown values. `MORE_INFO_REQUIRED` is explicitly marked frontend-only (`:2`). *Ready.*

**Net fintech posture:** the *trust/identity* model and *reliability* are already thoughtfully built; the *money* layer (representation, formatting, confirmation, screen protection) is entirely ahead of you and must be built before any transactional screen, plus the SEC-01 default-open flag must be closed before any non-dev build.

---

## 7. Improvement Roadmap

**Immediate (Critical)**
- SEC-01 — invert the demo-mode default / gate on `__DEV__`, add an `eas.json` release profile. (S)

**High priority**
- FIN-01 — money module (minor-units/decimal-string type, `formatMoney` over `Intl.NumberFormat`, currency metadata incl. TND 3-decimals) — prerequisite for all money screens. (M)
- FIN-02 — reusable biometric/PIN confirmation gate for sensitive actions. (M)
- ARC-01 — parameterized routes + history stack for the authenticated/transactional area (keep `routeFor` as the gate). (L)
- TEST-01 — tests for `real.ts` (refresh/401/mapping) + screen smoke renders + CI wiring. (M)

**Medium priority**
- ARC-02 — split `useJourney` into session context + per-feature hooks. (M)
- UX-01 — real i18n + fr/ar dictionaries + enable RTL. (M)
- SEC-02 — verified App Links for the OIDC redirect. (M)
- SEC-03 — screen-capture protection on KYC/wallet screens (when real data lands). (S)

**Optional / polish**
- SEC-04 — enforce `https` for non-localhost + Android cleartext config. (S)
- PERF-01 — memoize `useJourney` output. (S–M)
- CQ-01 — replace nested ternaries with status lookup tables. (S)

**Suggested implementation order (dependency-aware):**
1. **SEC-01** (one-line safety fix; unblocks any non-dev build). 
2. **FIN-01** money module and **FIN-02** confirmation gate (both are prerequisites that must exist before any money UI). 
3. **ARC-01** navigator evolution (needed to host transactional screens) — do alongside **ARC-02** hook split since they touch the same seams. 
4. **TEST-01** in parallel/continuously (test `real.ts` before it carries money; add CI). 
5. **UX-01** i18n/RTL once screen set stabilizes (retrofitting translations across churning screens wastes effort). 
6. **SEC-02/03/04, PERF-01, CQ-01** as hardening/polish before launch. 

Every item above maps to a cited finding; nothing here is speculative restructuring.

---

## 8. Final Verdict

1. **Is the frontend well-engineered?** For its size and stage, **yes** — notably so. Strict typing with zero escape hatches, a clean services seam, a centralized async/lock wrapper, typed status enums with a backend-wins router, a root error boundary, and real accessibility/keyboard/back handling are all above the bar for a demo-stage app. The weak spots are scope (money layer absent) and a few default-open/scaling choices, not craftsmanship.

2. **Maintainable and scalable for the rest of Phase 1?** **Maintainable yes; scalable only with planned evolution.** The flat route model (ARC-01) and single `useJourney` hook (ARC-02) will not cleanly carry ~10 parameterized transactional journeys. These are additive changes layered inside the existing authenticated gate — **no rewrite is warranted.**

3. **Suitable for production?** **No — not as a fintech app yet.** Blockers: the default-on demo flag with no build guard (SEC-01), the complete absence of money representation/formatting (FIN-01) and step-up confirmation (FIN-02), no tests on the real service path (TEST-01), and unimplemented i18n/RTL (UX-01). It is production-*quality* in craft on the onboarding slice, but production-*ready* only after these land. Overall engineering-quality score: **6.7/10**.

4. **Top 5 to fix before continuing development (in order):**
   1. **SEC-01** — close the default-open demo flag (invert default + `__DEV__` gate + `eas.json`).
   2. **FIN-01** — build the money representation/formatting module before any wallet/transfer/FX screen.
   3. **FIN-02** — add the reusable biometric/PIN confirmation primitive for money movement.
   4. **ARC-01 (+ARC-02)** — evolve navigation to parameterized routes with history, splitting feature hooks off `useJourney`, before transactional screens multiply.
   5. **TEST-01** — test the real auth/service path and wire `typecheck`/`lint`/`test` into CI.
