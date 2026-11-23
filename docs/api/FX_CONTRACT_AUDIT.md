# FX conversion — contract audit and mobile plan

- **Status:** approved 2026-11-06 and implemented (Part B): mobile flow on `feat/mobile-fx-convert`; BUG-FX-1/2/3 fixed on `fix/fx-conversion-bugs`; BUG-FX-4/5 and RISK-FX-6/8 reported (`INTEGRATION_STATUS.md` R10–R12). Plan change at approval: no automatic resend or check of an uncertain conversion (manual only).
- **Code read:** `origin/integration` at `dfccade` (PRs #73, #74: commits `4e85634`, `2fa6767`, `a9f259c`, `2ea4d0c`), `neos-fx-demo.ps1` (read, not run).
- **Method:** static reading only. Nothing was run; every runtime claim below is **UNVERIFIED** until Part B's live test.
- **Branch:** `feat/mobile-fx-convert` (from `origin/integration`, `feat/mobile-design-refresh` merged in).

## 1. Endpoints

Common to all three: role `CUSTOMER` (`@PreAuthorize`), user resolved from the Keycloak `sub`; unknown user → **403**. Eligibility (`CustomerDebitAuthorization.requireEligible`, `kyc/CustomerDebitAuthorization.java:33-47`): KYC `APPROVED` and account `ACTIVE`, else **403** (`AccessDeniedException`). Error bodies are Spring's default (`status`, `error`, `path`): there is no `@RestControllerAdvice` for `com.fintech.ledger.fx` and `server.error.include-message` is not set, so **the reason text is not in the body**. The app can only use the HTTP status.

### 1.1 `POST /api/fx/quotes` — indicative sandbox quote

`fx/api/FxQuoteController.java:35-48`, `fx/FxQuoteService.java:76-218`.

Request (`fx/api/FxQuoteRequest.java`):

```json
{ "idempotencyKey": "3f0c…-uuid", "sourceCurrency": "EUR", "destinationCurrency": "USD", "sourceMinor": "10000" }
```

Response 200 (`fx/api/FxQuoteResponse.java`):

```json
{
  "quoteId": "a1b2…-uuid", "idempotencyKey": "3f0c…-uuid",
  "sourceCurrency": "EUR", "destinationCurrency": "USD",
  "sourceMinor": "10000", "destinationMinor": "10800",
  "sourceScale": 2, "destinationScale": 2,
  "exchangeRate": "1.08",
  "pricingMode": "SANDBOX_INDICATIVE", "executable": false,
  "asOf": "2026-11-06T10:00:00.000000Z", "expiresAt": "2026-11-06T10:00:30.000000Z"
}
```

- **Gate:** `NEOS_FX_QUOTES_ENABLED=true` **and** `NEOS_FX_SANDBOX_MODE=true`, else **503** (`FxQuoteService.java:45-49, 286-294`). Not tied to the `demo` profile: quotes can be on without the demo conversion endpoint.
- **Amount:** `sourceMinor` 1–18 digits, > 0 (`:315-336`; DTO pattern `FxQuoteRequest.java:84-88`), else **400**. Same currency on both sides → **400** (`:105-110`). Unknown currency → **400** (`:338-352`).
- **Price:** `destinationMinor = floor(sourceMinor / 10^sourceScale × rate × 10^destinationScale)` (`RoundingMode.DOWN`, `:142-156`). Scales from `LedgerCurrency` (TND = 3). Result ≤ 0 or > `Long.MAX_VALUE` → **422** (`:158-165`). **No fee** anywhere in the FX code.
- **Rate source:** static configuration `NEOS_FX_SANDBOX_RATES`, e.g. `EUR/USD=1.08,EUR/TND=3.35` (`FxSandboxRateProvider.java:401-474`). Any configured pair works (not EUR-only); the direction is explicit (EUR/USD does not imply USD/EUR). Unconfigured pair → **503** (`:483-490`), indistinguishable from "quotes disabled". There is no endpoint listing the available pairs.
- **Lifetime:** 30 s (`QUOTE_TTL_SECONDS`, `:26`); `expiresAt` is server time. The quote is a lock in the sense that the conversion uses the stored numbers, and a conversion after `expiresAt` is refused (1.2).
- **Labelling:** `pricingMode` is always `SANDBOX_INDICATIVE` and `executable` always `false` (`:375-376`; DB checks `V14…sql:46-50`). The app must show "Sandbox rate · indicative".
- **Idempotency:** key `idempotencyKey` (UUID), scope = user (`UNIQUE (user_id, idempotency_key)`, V14). Same key + same parameters → the original quote, even if expired (`:122-140`). Same key + different parameters → **409** (`:296-313`). A new price needs a **new** key.
- **Balance:** not checked at quote time.

`GET /api/fx/quotes/{quoteId}` (`FxQuoteController.java:50-63`): own quote only, else **404**; same gate (503).

### 1.2 `POST /api/fx/demo/conversions` — execute a quote (demo only)

`fx/demo/FxDemoConversionController.java:42-56`, `fx/demo/FxDemoConversionService.java:87-287`.

Request: `{ "quoteId": "a1b2…", "idempotencyKey": "9d8e…" }` (both UUID, `@NotNull`).

Response 200 (`DemoResult`, `:493-514`):

```json
{
  "operationId": "…", "quoteId": "…", "idempotencyKey": "…",
  "status": "SUCCEEDED", "simulated": true,
  "sourceCurrency": "EUR", "destinationCurrency": "USD",
  "sourceMinor": "10000", "destinationMinor": "10800", "exchangeRate": "1.08",
  "reservationReference": "fx_reserve:…", "reservationTransactionId": 141,
  "settlementReference": "fx_convert:…", "settlementTransactionId": 142,
  "requiresReconciliation": false
}
```

- **Gate:** bean exists only with Spring profile `demo` and not `prod` **and** `neos.fx.demo.enabled=true` (`:24-31`). Otherwise the route does not exist → **404**. A 404 therefore means "nothing was executed".
- **Synchronous.** Lifecycle (V16 `ck_fx_demo_status`): `CLAIMED → RESERVE_POSTING → RESERVED → SETTLE_POSTING → SUCCEEDED`, with `RESERVE_UNKNOWN` / `SETTLE_UNKNOWN` on any failure. **There is no failed/rejected status.** A 200 is always `SUCCEEDED` (other states are thrown, `:321-328`).
  1. Reserve: `users:{id}:wallet:{SRC}:available → …:holds:fx_{op}` (`FxDemoFormanceAdapter.java:39-86`).
  2. Settle, one Formance transaction with four postings (`FormanceFxConversionEvidenceVerifier.java:129-191`): hold → `control:safeguarded:SRC`; `bank:sponsor:master:SRC` → `world`; `world` → `bank:sponsor:master:DST`; `control:safeguarded:DST` → customer `DST` available. Then re-read and verified (`:241-251`).
- **Quote checks:** own quote else **404** (`:115-123`); expired → **409** (`:125-130`); one conversion per quote (`UNIQUE (quote_id)`, V16:32).
- **Idempotency:** key `idempotencyKey`, scope = user (`UNIQUE (user_id, request_key)`). Same key: `SUCCEEDED` → original result; another quote → **409**; not finished → **409** "in progress or requires reconciliation; operationId=…" (`:309-331`, the id is only in the unexposed message).
- **Errors:** any failure while reserving or settling → **503** "outcome requires review; do not retry with another key" (`:196-209, 262-275`). 409 is used for four different situations (expired quote, key reused with another quote, operation unfinished, unresolved state `:436-443`).
- **Fees, limits:** none. Bank ID `neos.fx.demo.bank-id`, default `sponsor` (`:58-59`), the same bank that receives incoming payments (`SyntheticCreditService.java:23`).

### 1.3 `GET /api/fx/demo/conversions/{operationId}`

Own operation, else **404** (`:289-307`). Same `DemoResult`, any status; `requiresReconciliation=true` for the posting/unknown states. **No eligibility check** (reading stays possible for a suspended user: fine).

### 1.4 `GET /api/wallets`

Unchanged. During a conversion the source amount sits in an FX hold, which `CustomerWalletService` counts as **pending** (`holdsPattern`, `CustomerWalletService.java:408-413`); after success it is gone, and the destination available balance is up.

## 2. Enabling it locally

| Setting | Value | Where read |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `dev,demo` (the root `.env` sets `dev`) | `application.yaml:17` |
| `NEOS_FX_DEMO_ENABLED` (= `neos.fx.demo.enabled`) | `true` | `FxDemoConversionController.java:26-30` |
| `NEOS_FX_QUOTES_ENABLED` | `true` | `FxQuoteService.java:45` |
| `NEOS_FX_SANDBOX_MODE` | `true` | `FxQuoteService.java:48` |
| `NEOS_FX_SANDBOX_RATES` | e.g. `EUR/USD=1.08,EUR/GBP=0.86,EUR/TND=3.35,EUR/AED=3.97,EUR/MUR=49.5,USD/EUR=0.92` | `FxSandboxRateProvider.java:402` |

- **`.env` interplay:** `scripts/run-backend-local.ps1` copies every `.env` line into the process environment before starting, so overrides set *before* the script are overwritten for keys present in `.env` (`SPRING_PROFILES_ACTIVE` is). Part B will run the same three steps inline (load `.env`, then set the five overrides in the same process, then `mvnw spring-boot:run`), so `.env` is never edited. `dev` keeps its settings (port, transfers on); `demo` only adds the FX demo beans; `prod` would disable them (`demo & !prod`).
- **V14–V16:** three new tables (`fx_sandbox_quotes`, `fx_conversion_operations`, `fx_demo_conversions`); no change to existing tables. Flyway applies them on the next start. V15 (`fx_conversion_operations`) is for the future real flow and forbids sandbox providers; the demo does not write to it.
- **Formance:** nothing to create (accounts are implicit). Settlement debits `bank:sponsor:master:{SRC}` with no overdraft, so it only works while that account holds at least the converted amount. Incoming payments and synthetic credits fund it (`world → bank:sponsor:master`), so the local ledger already has EUR there; a fresh ledger would not.

## 3. History, notifications, receipts

- **History:** `GET /api/transactions` ignores both FX transactions. `fx_reserve` and `fx_convert` fall into `default -> Optional.empty()` (`CustomerTransactionHistoryService.java:239`). After a conversion the EUR wallet goes down and the USD wallet goes up with **no history entry** on either side (BUG-FX-1).
- **Notifications:** the FX service enqueues nothing (no call to `FinancialTransactionNotificationService`), so `GET /api/notifications` shows nothing either.
- **Receipts:** the app builds receipts from confirmed history items or a confirmed transfer result. Until history exposes conversions, the only source is the `SUCCEEDED` `DemoResult` (operation ID, references, amounts, rate): enough for a conversion receipt from the result screen, not from Activity.
- **Proposed mapping for the backend** (BUG-FX-1): one item per wallet, `type: "CONVERSION"`, `DEBIT` on the source wallet (from `fx_reserve`, like `payout_reserve`) and `CREDIT` on the destination wallet (from `fx_convert`), `reference` = the operation ID, plus optional `counterCurrency`, `counterAmountMinor` and `exchangeRate`, so each side can say "Converted to $108.00".

## 4. Findings (backend developer's issue format)

### BUG-FX-1 — Conversions missing from customer history (high)
- **Where:** `CustomerTransactionHistoryService.java:152-241` (no `fx_reserve` / `fx_convert` case).
- **Impact:** balances change with no explanation in Activity; no receipts from history; reconciliation by the customer is impossible.
- **Expected:** a `CONVERSION` debit on the source wallet and credit on the destination wallet (3).
- **Test:** history for both wallets after a demo conversion contains exactly one item each, with matching amounts.

### BUG-FX-2 — Insufficient funds becomes "requires review" forever (high)
- **Where:** `FxDemoConversionService.java:177-209`; no balance check in quote or convert.
- **Impact:** a conversion above the available balance: Formance refuses the reserve posting; the generic catch marks the operation `RESERVE_UNKNOWN` and returns 503 "outcome requires review". The operation stays unresolved forever. The quote is consumed (`UNIQUE (quote_id)`), and the customer sees an uncertain outcome for a definitive refusal. This is the same family as ISSUE-1 for transfers.
- **Expected:** classify Formance's definitive rejection (`INSUFFICIENT_FUND`) as a final `REJECTED` state with a 422 and an error code; optionally pre-check the available balance before reserving.
- **Test:** quote above the balance → convert → 422 `INSUFFICIENT_FUNDS`, status `REJECTED`, balance unchanged, nothing in hold.

### BUG-FX-3 — After an uncertain outcome the app cannot find the operation (high for clients)
- **Where:** `FxDemoConversionService.java:196-209, 262-275, 321-328`; `GET` needs `operationId`.
- **Impact:** on a 503, a timeout, or a retry that gets 409, the client never receives the `operationId` (it is only in the exception message, which is not in the body). It therefore cannot call `GET …/{operationId}` to resolve the outcome. The only safe move left is to stop and show "requires review".
- **Expected:** `GET /api/fx/demo/conversions?idempotencyKey=…` (own operations only), or the `operationId` and `status` in the 409/503 error body.
- **Test:** force a settlement failure → the lookup by key returns the operation with `requiresReconciliation=true`.

### BUG-FX-4 — No machine-readable errors; 409 and 503 overloaded (medium)
- **Where:** all FX `ResponseStatusException`s; no FX advice; default error body without the message.
- **Impact:** the app cannot tell an expired quote (re-quote) from a key conflict, from an operation in progress (poll), or from an unresolved state (review). It also cannot tell "FX disabled" from "pair not configured" (both 503 on quotes) or from "outcome unknown" (503 on convert).
- **Expected:** `errorCode` as in request R0: `FX_DISABLED`, `FX_PAIR_UNAVAILABLE`, `QUOTE_EXPIRED`, `QUOTE_ALREADY_USED`, `IDEMPOTENCY_KEY_REUSED`, `CONVERSION_IN_PROGRESS`, `CONVERSION_REQUIRES_REVIEW`, `INSUFFICIENT_FUNDS`.

### BUG-FX-5 — Funds stuck in hold after a settlement failure (medium)
- **Where:** `FxDemoConversionService.java:262-275`; no release or reconciliation job.
- **Impact:** if settlement fails after a successful reserve (e.g. Formance down, or the bank master short of funds), the source amount stays in `holds:fx_{op}` (shown as pending) with no path back to available.
- **Expected:** a reconciliation step: verify by reference, then settle or release the hold.

### RISK-FX-6 — Pair discovery (low)
No endpoint lists the configured pairs, and a missing pair returns 503. Proposal: `GET /api/fx/pairs` (or `available pairs` in an error body). Without it, the app offers all wallet pairs and handles 503 per pair.

### RISK-FX-7 — 30 s quote vs device authentication (low, app-side)
Fingerprint or PIN can take most of the 30 s. The app re-checks `expiresAt` after authentication and never sends an expired quote; a server-side 409 then means "re-quote".

### RISK-FX-8 — Demo script (low)
`neos-fx-demo.ps1:3` defaults to `http://localhost:8082`, which locally is Metro (the backend runs on 8080). `:6` uses the password-grant client `neos-dev-cli` (defined in `infra/keycloak/realm/neos-realm.json`; dev only, fine as long as it never reaches a production realm). `:168-172` calls `POST /api/kyc/start` (state-changing) only to print the KYC status; `GET /api/profile` is read-only.

## 5. Mobile plan (Part B)

**Availability.** Convert stays the "Not available yet" placeholder unless the build is a sandbox build (`isSandbox(config)`), so a production build can never reach the demo endpoint. Inside a sandbox build:
- a 503 on the quote, or a 404 on the conversion, shows an honest "Convert isn't available in this environment".
- a 404 is "nothing was converted"; it is never a retry.

**Screens** (new `screens/convert/Convert.tsx`, design-refresh tokens and components):

1. **From and to.**
   - From: the selected wallet on Home, or any active wallet with a balance; currency chips plus the available balance.
   - To: the other currencies.
   - Not-provisioned wallets show "Setting up", are not selectable, never 0.
2. **Amount.**
   - Entered with `parseAmountInput` and `scaleFor` from `src/money`, string minor units, scale per currency (TND 3), live "= TND 1.000" preview.
   - Checked against the available balance before quoting.
3. **Quote.**
   - `POST /api/fx/quotes` with a new UUID per (pair, amount) request.
   - Shows the rate ("1 EUR = 1.08 USD"), "You convert" and "You receive" (`destinationMinor`, formatted with `destinationScale`), "Fee: none", and a **"Sandbox rate · indicative"** tag.
   - Live countdown to `expiresAt` (server time; corrected by the device-clock offset taken from `asOf` at receipt).
   - On expiry the numbers grey out and "Get a new rate" requests a new quote with a new key. No silent refresh: the user always confirms the price they see.
4. **Review and confirm.**
   - Device authentication as for transfers (`confirmDeviceOwner`; no-lock flow as in Send).
   - After authentication, if the quote expired: back to the quote with a new rate, never executed.
5. **Submit.**
   - `POST /api/fx/demo/conversions` with **one idempotency key per (quoteId) intent**, persisted *before* the call (pattern of `transfers/engine.ts`: pending record in SecureStore keyed per user).
   - Same key on every retry; never a new key while the outcome is unknown (CLAUDE.md rule).
6. **Outcome.**
   - **200 `SUCCEEDED`:** "Converted", both amounts, rate, operation ID and references; both wallets re-read from `/api/wallets`; Home and Activity refresh on focus; a share-receipt button built from the `DemoResult`.
   - **409 or 503 or timeout** (uncertain, BUG-FX-3/4): "Conversion being checked".
     - "Check status" resends the **same** request (same key and quote), which is safe: `SUCCEEDED` returns the result, unfinished returns 409.
     - Checks back off: 2 s, 5 s, 15 s, then manual only.
     - The pending conversion is shown on Home like a pending transfer.
     - "Stop tracking" only after a manual check, with a warning.
     - Once BUG-FX-3 is fixed, the check uses the lookup by key and `GET …/{operationId}` instead.
   - **409 right after an expired quote:** the app sees an expired quote only if the clock offset was wrong; the status is still uncertain (409 is ambiguous), so it goes through the same check path, never a new key.
   - **403:** account restricted (existing mapping). **400 or 422:** "Conversion not possible" (nothing moved).
7. **Activity.** No FX entries exist until BUG-FX-1 is fixed. After a conversion the result screen states this plainly, and Activity shows a one-time note on the two wallets ("Conversions don't appear in Activity yet in this sandbox"). When the backend adds `CONVERSION`, the app adds the type, title ("Converted to USD" / "Converted from EUR") and receipt.

**Code**
- `src/fx/`: `contract` types and parsers (strict, like `api/parse.ts`); `engine.ts` (intent → pending → submit → outcome, reusing `KeyValueStore`, `deviceAuth` and the outcome classification style of `transfers/`); `countdown.ts` (pure, clock-offset aware).
- `services.fx` in `real.ts`.
- No mock for FX (money movement is never mocked); the mock registry and `notImplementedActions` drop "Convert" for sandbox builds.
- Release readiness lists "Convert: sandbox demo only".

**Tests** (tsx): quote parsing (TND scale 3, string amounts, `executable=false` kept); countdown and clock offset; engine (key reused on retry, persisted before send, 200, 409, 503 and timeout → uncertain, 404 → unavailable, 400/422 → rejected, expiry after authentication → re-quote, no request with an expired quote); readiness (Convert not reachable when not sandbox).

**Live test (Part B, needs your password file):**
- backend with the five overrides (2);
- `neos-sender`: quote → wait for expiry → new quote → convert → both balances and Activity (expected: no FX items, BUG-FX-1);
- failure paths: expired quote, amount above the balance (expected: BUG-FX-2 behaviour, recorded, not hidden), feature disabled (restart without the demo flag → 404 path).

**Backend fixes.** BUG-FX-2 and BUG-FX-3 are small and self-contained (a definitive-rejection branch plus a lookup by key). BUG-FX-1 touches his history service. Proposal: one branch per fix from `origin/integration` with tests and a drafted PR, nothing on the mobile branch. Which ones I take is for you to decide (the end of the report lists them).
