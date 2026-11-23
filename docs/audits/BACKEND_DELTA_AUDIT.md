# Backend Delta Audit — C1 / C2 / C3 (vs `INTEGRATION_AUDIT.md`)

- Branch: `feat/frontend-integration`, merge commit `051cfec`
- Baseline: backend at `87a8361` (B1 + B2), which is what `INTEGRATION_AUDIT.md` described
- Delta: `4d17e5d` (C1 ledger taxonomy), `ec5b139` (C2 wallet provisioning), `f9b6c8f` (C3 synthetic credits)
- Method: static reading only (`git diff 87a8361 051cfec`). Nothing was run for this phase; runtime claims are marked **UNVERIFIED**.

---

## 0. Headline findings

1. **No wallet / balance read endpoint exists.** C2 provisions Formance accounts, but nothing exposes wallets or balances to the mobile app. The `wallets` mock has no real replacement yet.
2. **Port collision (dev profile).** `application-dev.yml` now sets `server.port: ${SERVER_PORT:8082}` and `neos.ledger.formance.base-url: ${FORMANCE_BASE_URL:http://localhost:8080}`.
   - The backend now defaults to **8082**, the port Metro uses.
   - Formance is expected on **8080**, where the mobile app's `EXPO_PUBLIC_API_URL` (`http://localhost:8080`) points.
   - Running backend + Formance + Metro with the documented defaults does not work without overriding ports.
3. **C3 synthetic credits are enabled in every profile, including prod.**
   - `application.yaml` (base) sets `neos.backoffice.synthetic-credit.enabled: true`.
   - `application-prod.yml` does not override it, and no `@Profile` guard exists.
   - The only guard is the `COMPLIANCE_OFFICER` role. This contradicts "non-production synthetic credits".
4. **No local Formance setup in the repo.**
   - The repo has only schema JSON files (`infra/formance/schemas/`).
   - There is no compose file or image, and nothing creates the ledger or schema.
   - The client targets the gateway path `/api/ledger/v2/...` with no auth header.
5. **Wallet provisioning has no retry/outbox.**
   - Provisioning runs `@TransactionalEventListener(AFTER_COMMIT)` after the KYC row is committed as `APPROVED`.
   - If Formance is down, the event is lost: later `/api/kyc/start` calls see `APPROVED` and deliberately do not republish.
   - Whether the HTTP call then returns 200 or 500 depends on how Spring handles listener exceptions in this phase: **UNVERIFIED**, to be observed in Phase C.

---

## 1. New / changed backend code

### Packages (all new, under `com.fintech.ledger`)

| Package | Content |
|---|---|
| `ledger` | `LedgerConfiguration` (Formance `RestClient` bean), `WalletProvisioningService`, `WalletProvisioningOnKycApproved`, `SyntheticCreditService`, `LedgerProviderUnavailableException`, `LedgerTransactionRejectedException` |
| `ledger.api` | `SyntheticCreditController`, `SyntheticCreditRequest`, `SyntheticCreditResponse` |
| `ledger.port` | `LedgerProvider` (port), `LedgerOperationResult` |
| `ledger.adapter.formance` | `FormanceClient` (HTTP), `FormanceLedgerProvider` (implements port with Numscript), `FormanceProperties` and Formance DTOs |
| `ledger.internal.taxonomy` | `LedgerCurrency`, `LedgerAccountAddress`, `LedgerAccountAddressFactory`, `LedgerAccountKind`, `LedgerBalancePolicy` |

### Changed existing code

- **`kyc/KycStartService.java`:**
  - Now publishes `KycApprovedEvent(userId)` only on the real `NOT_STARTED → APPROVED` transition (`:201-211`).
  - The mock auto-approve flow is unchanged (`:157-165`): still `MockKycProvider`, still auto-approves.
- **`kyc/KycApprovedEvent.java`:** new record `(UUID userId)`.

### Unchanged

- REST DTOs for auth, onboarding and KYC: no diff in `kyc/api`, `onboarding/**`, `identity/**`.
- `pom.xml`: no new dependencies. Formance is called with Spring's `RestClient`, with no Formance SDK.

### Entities / Flyway

- **No new entities, no new migrations.** Still `V1` + `V2` only. By design (`docs/ledger/C1_LEDGER_TAXONOMY.md`), Formance is the source of truth for balances and PostgreSQL stores no balances.

---

## 2. REST surface now exposed (complete)

| Method | Path | Controller | Roles | Request | Response |
|---|---|---|---|---|---|
| GET | `/api/auth/me` | `identity/api/AuthController.java:16` | `CUSTOMER` | – | `Map` `{subject, username, email}` from JWT claims (unchanged) |
| POST | `/api/onboarding/personal` | `onboarding/api/PersonalOnboardingController.java:25` | `CUSTOMER` | unchanged | unchanged |
| POST | `/api/kyc/start` | `kyc/api/KycController.java:25` | `CUSTOMER` | – | `{userId: UUID, status: String, provider: String, verificationMode: String}` (unchanged; `verificationMode` = `"NON_PROD"`) |
| **POST** | **`/api/backoffice/synthetic-credits`** | **`ledger/api/SyntheticCreditController.java:40`** | **`COMPLIANCE_OFFICER`** | `SyntheticCreditRequest` | `SyntheticCreditResponse` |
| GET | `/actuator/health` | Actuator | public (`SecurityConfig.java:31-33`) | – | – |

The synthetic-credit controller only exists when `neos.backoffice.synthetic-credit.enabled=true` (`@ConditionalOnProperty`, `:20-24`).

**`SyntheticCreditRequest`** (`ledger/api/SyntheticCreditRequest.java`):
- `userId: UUID` (`@NotNull`)
- `currency: LedgerCurrency` (enum `EUR|USD|GBP|AED|MUR|TND`, `@NotNull`)
- `amountMinor: long` (`@Positive`, integer minor units)
- `operationId: String` (`@NotBlank`, max 128, `^[A-Za-z0-9_-]+$`)

**`SyntheticCreditResponse`:** `{userId: UUID, currency: LedgerCurrency, amountMinor: long, transactionId: long, reference: String}`.

**Wallets / balances:** **no endpoint exists.** No `GET` for wallets, balances, accounts or transactions. Balances are readable only server-side via `FormanceClient.getAccountBalance(address, asset)` (`FormanceClient.java:202`), which returns a `BigInteger` and is used by tests only.

### Amount and currency representation

| Context | Representation |
|---|---|
| API (C3 DTOs) | integer **minor units** (`long amountMinor`) + `LedgerCurrency` enum, serialized as the code string (`"MUR"`) |
| Ledger (Formance) | UMN asset notation `CODE/scale`: `EUR/2`, `USD/2`, `GBP/2`, `AED/2`, `MUR/2`, **`TND/3`** (`LedgerCurrency.java:21-26, 49-51`); amounts as integers, read back as `BigInteger` |
| Account addresses | `users:{userId}:wallet:{CUR}:available`, `users:{userId}:wallet:{CUR}:holds:{operationId}`, `bank:{bankId}:master:{CUR}`, `control:safeguarded:{CUR}`, `suspense:incoming:{CUR}`, `platform:fees:{CUR}` (`LedgerAccountAddressFactory.java`) |

---

## 3. C2 — wallet provisioning

- **Trigger:**
  - `KycStartService` publishes `KycApprovedEvent` on the APPROVED transition.
  - `WalletProvisioningOnKycApproved` (`@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)`, `:25-28`) calls `WalletProvisioningService.provisionCustomerWallets(userId)`.
- **What it does:**
  - It runs for **every** `LedgerCurrency`: EUR, USD, GBP, AED, MUR, TND (6 wallets).
  - Each wallet gets `POST /api/ledger/v2/{ledger}/accounts/{address}/metadata?schemaVersion=v1.1.0`, where the address is `users:{userId}:wallet:{CUR}:available`.
  - The metadata is `neos.account_kind=customer_available`, `neos.user_id`, `neos.currency`, `neos.wallet_status=ACTIVE`.
  - No posting is made, so the balance is implicitly 0.
- **Idempotency:**
  - Formance `Idempotency-Key: wallet-provision:{userId}:{CUR}` (`WalletProvisioningService.java:74-78`).
  - At the domain level, the event is published only once (on transition).
- **Failure handling:**
  - Exceptions become `LedgerProviderUnavailableException` / `LedgerTransactionRejectedException`.
  - There is **no `@ControllerAdvice`** anywhere, so unhandled errors give Spring's default 500.
  - There is no outbox and no retry. If provisioning fails after commit, the user stays `APPROVED` with no wallets and nothing re-triggers provisioning. The HTTP outcome in that case is **UNVERIFIED**.
- **Mobile-callable endpoint to list wallets/balances: NONE.**

## 4. C3 — synthetic credits

- **Endpoint:** `POST /api/backoffice/synthetic-credits`, role `COMPLIANCE_OFFICER`.
- **Accounting:**
  - `SyntheticCreditService` calls `LedgerProvider.recordIncomingPayment(userId, "sponsor", currency, amountMinor, operationId)`.
  - Atomically, this posts `world → bank:sponsor:master:{CUR}` and `control → users:{userId}:wallet:{CUR}:available` (`SyntheticCreditService.java:72-98`). It never does `world → customer` directly.
- **Flag:** `neos.backoffice.synthetic-credit.enabled`.
- **Profiles where it is enabled:** **all**. It is `true` in `application.yaml`, with no override in `application-dev.yml` or `application-prod.yml`, and no `@Profile`. Only `SyntheticCreditDisabledIntegrationTest` sets it to `false`. **Risk: enabled in production unless overridden by env (`NEOS_BACKOFFICE_SYNTHETIC_CREDIT_ENABLED=false`).**

## 5. New external dependencies and config

### Formance ledger
- **How it is called:**
  - Spring `RestClient` with no auth header.
  - Paths are prefixed `/api/ledger/v2/...`, which suggests a Formance stack gateway rather than a bare ledger service.
  - A code comment refers to "our current Formance deployment" (`FormanceClient.java:222`). That deployment is not described anywhere in the repo.
- **Local setup in repo:** **none.**
  - There is no compose file or image reference.
  - The ledger `neos` must already exist (`verifyConnection` does `GET /api/ledger/v2/{ledger}`).
  - The account metadata calls pass `schemaVersion`, so schema `v1.1.0` (`infra/formance/schemas/neos-v1.1.0.json`) must be uploaded to that ledger beforehand. **UNVERIFIED** whether Formance rejects writes when the schema is missing.

### New config
- **`neos.ledger.formance.base-url`:**
  - dev: `${FORMANCE_BASE_URL:http://localhost:8080}`
  - prod: `${FORMANCE_BASE_URL}`, required
- **`neos.ledger.formance.ledger-name`:** `${FORMANCE_LEDGER_NAME:neos}`.
- **`neos.ledger.formance.schema-version`:** `${FORMANCE_SCHEMA_VERSION:v1.1.0}`.
- **`neos.ledger.formance.connect-timeout`:** `${FORMANCE_CONNECT_TIMEOUT:2s}`.
- **`neos.ledger.formance.read-timeout`:** `${FORMANCE_READ_TIMEOUT:5s}`.
- **`server.port`:** `${SERVER_PORT:8082}` (dev). **New, and conflicts with Metro.**
- **`neos.backoffice.synthetic-credit.enabled`:** `true` (base).
- **`src/.env.example`:** now lists `SERVER_PORT=8082` and the `FORMANCE_*` vars. It still omits `KEYCLOAK_ISSUER_URI`, `KYC_MODE` and `SUMSUB_*` (same gap as the previous audit).

### Tests
- **No Testcontainers in `pom.xml`.**
- Every `@SpringBootTest` needs a running PostgreSQL at the dev datasource (`localhost:5432/fintech_dev`).
- All Formance integration tests are `@EnabledIfEnvironmentVariable(named = "RUN_FORMANCE_IT", matches = "true")`, so they are skipped unless that var is set and a real Formance is reachable. This applies to `FormanceConnectivityIntegrationTest`, `WalletProvisioning*IntegrationTest`, `KycApprovalWalletProvisioningIntegrationTest`, `SyntheticCreditIntegrationTest` and `SyntheticCreditControllerIntegrationTest`.
- `FormanceClientTest` uses `MockRestServiceServer` (no network).

### Keycloak realm export (`infra/keycloak/realm/neos-realm.json`)
- Client `neos-mobile`: public, standard flow, PKCE `S256`, but **`redirectUris` is empty** (`neos://auth` must be added in the running realm).
- **No users in the export.**

## 6. Mobile mock registry vs backend

| Feature | Real backend now? | Registry `replacedBy` accurate? | Proposed text (not applied) |
|---|---|---|---|
| `auth` | Yes (unchanged) | Yes | – |
| `onboarding` | Yes (unchanged) | Yes | – |
| `kyc` | Endpoint yes, but backend KYC is itself **mock** (`MockKycProvider`, instant APPROVED) | **No**: says "Sumsub-backed, backend B2" | `POST /api/kyc/start (backend currently auto-approves via MockKycProvider; Sumsub adapter not implemented)` |
| `kycCapture` | No | Yes | – |
| `wallets` | **No read endpoint.** C2 only provisions Formance accounts | Partly: `GET /api/wallets` is an invented path | `No wallet/balance read endpoint yet. C2 provisions Formance accounts users:{id}:wallet:{CUR}:available for EUR, USD, GBP, AED, MUR, TND on KYC approval; balances are only readable server-side.` |
| `notifications` | No | Yes | – |

## 7. Contract notes (backend vs `mobile/src/types/domain.ts`)

1. **Currencies.**
   - The mock wallets are `MUR, USD, EUR`; the backend provisions 6 (`+ GBP, AED, TND`).
   - Home's presentation map covers only MUR, USD and EUR. Others fall back to the code, which works but has no styling.
2. **Scale.**
   - `Wallet.balanceMinor: number` matches the backend's minor-unit convention.
   - Home's `formatMinor` assumes 2 decimals, which is **wrong for `TND` (scale 3)**.
   - A future wallets DTO should carry `scale` (or the asset `TND/3`).
3. **Magnitude.** Backend balances are `BigInteger` and amounts are `long`. A JS `number` is exact only to 2^53. A future DTO should send balances as **strings**, or the frontend should accept a string.
4. **Ports.**
   - With dev defaults, the backend is on 8082 and Formance on 8080.
   - `mobile/.env` `EXPO_PUBLIC_API_URL=http://localhost:8080` would hit **Formance**, not the backend.
   - Phase B/C must override `SERVER_PORT=8080` and move Formance elsewhere (e.g. `FORMANCE_BASE_URL=http://localhost:8083`), or change the mobile URL. Metro must keep 8082.
5. **KYC errors.**
   - If provisioning throws after commit and that surfaces as a 500, the mobile app maps it to `kycError` while the DB says `APPROVED`. A later "check status" returns 200 `APPROVED` (no reprovision).
   - The UX is recoverable, but wallets are silently missing. **UNVERIFIED** which path occurs.
6. **`/api/auth/me` (pre-existing).** It uses `Map.of(...)`, which throws on null, so a token without an `email` claim produces a 500 rather than a clean error.
7. **Unchanged and compatible:** `KycStartResponse` (`verificationMode: string`), onboarding DTOs, `BackendKycStatus` values.

## 8. Items to verify in Phase B/C

- Formance runtime: how to run it locally, ledger creation and schema upload.
- HTTP result of `/api/kyc/start` when Formance is unreachable.
- Actual provisioning of the 6 accounts after a real KYC approval (query Formance).
- Keycloak running realm: redirect URI and test `CUSTOMER` user.
- Test suite results with Postgres up (`RUN_FORMANCE_IT` unset vs set).
