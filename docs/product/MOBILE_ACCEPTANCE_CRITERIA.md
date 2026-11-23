# Mobile Acceptance Criteria (from GitHub)

- **Source:** `bentanfousk/TANYT`, fetched 2026-11-01 with read-only `gh issue view --json` and `gh api .../milestones`.
- **Issue bodies:** copied verbatim below. Bodies were last updated 2026-08-28.
- **Comments:** none of the issues (#17, #22–#29) has any comment, so no scope changes come from comments.
- **Dependencies:** none of the issue bodies lists a dependency. The "Related issues" lines are **inferred by this doc**, not taken from GitHub.

## Milestones

| # | Milestone | Open / closed | Due date | Mobile issues in it |
|---|---|---|---|---|
| 1 | V1 - Validation 1 | 21 / 8 | none set | #17 (B5), #22–#29 (D1–D8) |
| 2 | V2 - Validation 2 | 7 / 0 | none set | – (related: #30 Nova-to-Nova transfer, #33 Top-up, #35 Advanced auth) |
| 3 | V3 - Validation 3 | 2 / 0 | none set | – (related: #37 FX conversion, #38 SWIFT/SEPA external transfer) |

**All nine mobile issues are in V1 - Validation 1.**

| Issue | Labels |
|---|---|
| #17 | `priority/high`, `module/mobile`, `phase/1` |
| #22–#28 | `priority/low`, `module/mobile`, `phase/1` |
| #29 | `priority/low`, `module/kyc`, `phase/1` |

---

## #17 — B5 — Android React Native shell + secure navigation/auth state
**Milestone:** V1 - Validation 1. **State:** OPEN. **Priority:** high.

> **Context:** Android is delivered first; React Native keeps iOS adaptation open.

- [ ] App runs on a real Android device
- [ ] Navigation between V1 floor screens works
- [ ] Auth state routes correctly:
  - logged out → onboarding/login
  - authenticated + KYC pending → restricted state
  - authenticated + approved → home
- [ ] Build runs through Expo tooling
- [ ] Uses dev-client/prebuild-compatible setup so secure storage/biometrics/passkeys/native modules can be added later
- [ ] No secrets embedded in the mobile bundle

**Dependencies mentioned:** none.

**Related issues (inferred):**
- #14 B2 (Keycloak auth: PKCE, secure token storage, "log out everywhere").
- #15 B3 (KYC in-app: "User completes ID + selfie + liveness flow in-app"; "Sensitive features remain gated until approval").

---

## Common block for D1–D8

Every D issue has this **identical** context and first four criteria:

> **Context:** UI shell — visible breadth with zero false implication of real financial execution.

- [ ] Renders with mock/preview data
- [ ] Clearly marked in code/config as a shell
- [ ] Does NOT imply that real money moved
- [ ] Does NOT call backend endpoints that create financial state

Each issue then adds **one** screen-specific criterion, listed below.

## #22 — D1 — Dashboard home (shell)
**Milestone:** V1. **State:** OPEN. **Priority:** low.
- [ ] *(common 4)*
- [ ] Screen-specific content: total balance preview, wallet list, quick actions: send / receive / convert / add / history

**Related issues (inferred):**
- #19 C2 (closed): "Balance displays as zero in the app".
- #21 C4 (closed): "Synthetic credit appears in transaction history with type / amount / currency / timestamp / status".

## #23 — D2 — Send money (shell)
**Milestone:** V1. **State:** OPEN. **Priority:** low.
- [ ] *(common 4)*
- [ ] Screen-specific content: beneficiary, currency, amount, fee/rate/ETA preview; internal + international visual states

**Related issues (inferred):** #30 Nova-to-Nova transfer (**V2**), #38 SWIFT/SEPA (**V3**).

## #24 — D3 — Add money / top-up (shell)
**Milestone:** V1. **State:** OPEN. **Priority:** low.
- [ ] *(common 4)*
- [ ] Screen-specific content: master-account details + unique-reference/virtual-account placeholder

**Related issues (inferred):** #33 Top-up + inbound-funds matching (**V2**), #32 Sponsor-bank simulator (**V2**).

## #25 — D4 — Convert currency (shell)
**Milestone:** V1. **State:** OPEN. **Priority:** low.
- [ ] *(common 4)*
- [ ] Screen-specific content: source wallet, target wallet, rate/fee preview

**Related issues (inferred):** #37 FX conversion through sponsor-bank adapter (**V3**).

## #26 — D5 — Beneficiaries (shell)
**Milestone:** V1. **State:** OPEN. **Priority:** low.
- [ ] *(common 4)*
- [ ] Screen-specific content: beneficiary add/list UX; may use non-financial Postgres persistence if cheap

**Dependencies mentioned:** optional backend persistence ("non-financial Postgres persistence if cheap").

## #27 — D6 — Security & devices settings (shell)
**Milestone:** V1. **State:** OPEN. **Priority:** low.
- [ ] *(common 4)*
- [ ] Screen-specific content: passkeys / TOTP / biometrics / trusted-device list preview

**Related issues (inferred):** #35 Advanced auth (**V2**), #14 B2 ("Log out everywhere").

## #28 — D7 — Notifications list (shell)
**Milestone:** V1. **State:** OPEN. **Priority:** low.
- [ ] *(common 4)*
- [ ] Screen-specific content: notification center with mock entries

**Related issues (inferred):** #21 C4 (email notifications only; no in-app feed).

## #29 — D8 — Business account / KYB entry (shell)
**Milestone:** V1. **State:** OPEN. **Priority:** low. **Label:** `module/kyc`.
- [ ] *(common 4)*
- [ ] Screen-specific content: business onboarding entry; shell unless KYB is explicitly pulled into current validation

**Dependencies mentioned:** a scope condition ("unless KYB is explicitly pulled into current validation").

---

## Observations that affect planning

1. **D1–D8 are explicitly shells in V1.**
   - They must render **mock/preview data**.
   - They must **not call endpoints that create financial state**. In practice that rules out `POST /api/transfers/internal` from D2 in V1. Nova-to-Nova transfer is #30 in **V2**.
2. **Real money movement is planned for later milestones:**
   - Top-up is V2 (#33).
   - FX conversion is V3 (#37).
   - External transfers are V3 (#38).
   - Advanced auth is V2 (#35).
3. **The only mobile issue that requires real behaviour is B5 (#17)**: auth routing, Android device, Expo, no secrets. It is also the only high-priority one.
4. **Two closed backend issues have app-visible criteria the app does not yet show:**
   - #19 C2: "Balance displays as zero in the app". There is no balance endpoint yet.
   - #21 C4: "Synthetic credit appears in transaction history…". `GET /api/transactions` exists, but the app does not call it.

   A real balance read (the proposed `GET /api/wallets`) and history would close the app side of these. Both are read-only, so they do not breach the D-shell rule above.
