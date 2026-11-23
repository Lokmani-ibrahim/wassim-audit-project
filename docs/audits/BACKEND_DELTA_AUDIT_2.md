# Backend Delta Audit 2 — since `051cfec` (through PR #49)

- Branch audited: `feat/mobile-on-integration` at `2e05d7a` (= `origin/integration`).
- Baseline: `051cfec` (end of `BACKEND_DELTA_AUDIT.md`, C1–C3).
- Delta: 20 non-merge backend commits plus merges #48 (C4 history and notifications) and #49 (Sumsub KYC, transfers, payouts). `git diff --stat 051cfec..HEAD -- . ':!mobile'` reports 162 files changed and +28,806 lines.
- Method: static reading only. Nothing was built, run or tested. Runtime claims are marked **UNVERIFIED**. The backend developer's commit messages were not trusted: every claim below is checked against code.

---

## 0. Headline findings

1. **There is still no wallet or balance read endpoint.**
   - The only customer-facing financial reads/writes are `GET /api/transactions` (`ledger/api/TransactionHistoryController.java:32-54`) and `POST /api/transfers/internal`.
   - The transfers endpoint is behind a flag that is off by default (`ledger/api/CustomerInternalTransferController.java:25-29`, `application.yaml:68-69`).
   - `FormanceClient.getAccountBalance` (`ledger/adapter/formance/FormanceClient.java:203`) has no caller in `src/main`.
2. **There is no Sumsub SDK access-token endpoint.**
   - `KycProvider.createVerificationSession` (`kyc/port/KycProvider.java:11-13`) is implemented by `SumsubKycProvider.java:79-109`, but nothing in `src/main` calls it (grep: only the two implementations and the port).
   - So a mobile app in `KYC_MODE=SUMSUB` cannot launch the Sumsub SDK, and the KYC flow cannot be completed from the app.
3. **Security: a suspended customer can re-activate themself through `POST /api/kyc/start`.**
   - `KycStartService.java:102-109` calls `IdentityAccountService.activate(userId)` whenever KYC is `APPROVED`. `activate()` is unconditional (`identity/IdentityAccountService.java:21-41` → `identity/entity/UserEntity.java:125-127`).
   - `SumsubDecisionService.java:174-185` deliberately leaves a `SUSPENDED` account suspended when a later `GREEN` re-approves KYC ("SUSPENDED and CLOSED remain blocked").
   - **Sequence:**
     1. A customer is approved.
     2. Sumsub sends RED/RETRY, which suspends the account (`SumsubDecisionService.java:212-221`).
     3. Sumsub sends GREEN. KYC becomes `APPROVED`, but the account stays `SUSPENDED`.
     4. The customer calls `POST /api/kyc/start`, and the account becomes `ACTIVE`.
   - The same applies to a `CLOSED` account whose KYC row is `APPROVED`.
4. **Insufficient funds on an internal transfer is reported as an uncertain outcome.**
   - Formance 4xx responses become `LedgerTransactionRejectedException` (`FormanceClient.java:587-617`).
   - The transfer orchestrator catches every `RuntimeException`, marks the claim `UNKNOWN`, and returns `503 "Transfer outcome not confirmed. Reconciliation is required."` (`ledger/CustomerTransferIdempotencyService.java:268-301`).
   - The only reconciliation path is "confirm posted" (`V9__create_transfer_reconciliation_audit.sql:8-15`, `CustomerTransferManualReconciliationService.java:86`). It has no HTTP entry point and cannot resolve a transfer that never posted.
   - Result: the key is blocked forever with `409` (`CustomerTransferIdempotencyService.java:384-390`).
5. **KYC defaults to `MOCK` (auto-approve) in every profile, including prod.**
   - `application.yaml:36` sets `mode: ${KYC_MODE:MOCK}`, and `application-prod.yml` does not override it.
   - `MockKycProvider` is `matchIfMissing = true` (`kyc/adapter/mock/MockKycProvider.java:9-15`).
   - A prod deploy without `KYC_MODE=SUMSUB` auto-approves every customer (`KycStartService.java:158-179`).
6. **Payouts are implemented as services only, with no HTTP endpoint at all.** This covers both customer and backoffice payouts (see §1.5).
7. **Transaction history hides pending payouts and holds.**
   - A reserved payout leaves `available` but appears in history only after settlement (`CustomerTransactionHistoryService.java:228-237, 243-353`).
   - `payout_release` / `payout_settle` rows on the available account are suppressed.

---

## 1. What changed since `051cfec`

### 1.1 Modules and packages

The Spring Modulith top-level modules are unchanged: `identity`, `onboarding`, `kyc`, `ledger`, `notifications`, `payments`, `shared`. New subpackages and types:

| Package | New / changed (main code only) |
|---|---|
| `kyc` | `SumsubDecisionService`, `CustomerDebitAuthorization`, `KycWalletProvisioningEligibility`, `KycStartService` (rewritten for MOCK vs SUMSUB) |
| `kyc.adapter.sumsub` (new) | `SumsubClient`, `SumsubKycProvider`, `SumsubRequestSigner`, `SumsubReviewedEvent`, `SumsubReviewedEventParser`, `SumsubWebhookSignatureVerifier` (and `SumsubProperties`) |
| `kyc.api` | `SumsubWebhookController` (new) |
| `kyc.entity` / `kyc.repository` | `KycStatus` gains `RESUBMISSION_REQUIRED` (`KycStatus.java:9`). `KycStatusEntity` gains event-ordering, rejection-lock and manual-review fields (`KycStatusEntity.java:53-71`). The repository gains `findByUserIdForUpdate` (pessimistic lock). |
| `identity` | `UserAccountStatusLookup`, `UserContactLookup`, `IdentityAccountService.activateIfPendingKyc / suspendIfActive` (`IdentityAccountService.java:42-75`), and a webhook permit in `SecurityConfig.java:36-41` |
| `ledger` | C4 history: `CustomerTransactionHistoryService`, `CustomerTransaction{Item,Page,Status,Type,Direction}`. Transfers: `InternalTransferService`, `CustomerTransferAdmissionService`, `CustomerTransferIdempotencyService`, `CustomerTransfer{ManualReconciliation,ReconciliationInspection,ReconciliationQuery}Service`. Fees: `FeeCollectionService`. Payouts: `PayoutRequestAdmissionService`, `PayoutReservation{,Execution}Service`, `PayoutSettlementService`, `PayoutFinalizationExecutionService`, `PayoutManualReconciliationService`, `PayoutNotificationRepairService` |
| `ledger.api` | `TransactionHistoryController`, `CustomerInternalTransferController` (+ request/response records) |
| `ledger.adapter.formance` | `FormanceInternalTransferEvidenceVerifier`, `FormancePayoutEvidenceVerifier`, `FormanceTransaction{Data,Posting}`, `FormanceTransactions{Cursor,Response}`, and transaction listing in `FormanceClient.java:313-430` |
| `notifications` | Durable email outbox: `NotificationOutboxService`, `NotificationOutboxClaimService`, `NotificationDeliveryWorker`, `NotificationOutboxScheduler`, `NotificationSchedulingConfiguration`, `FinancialTransactionNotificationService`, enums, and `NotificationOutboxEntity` |
| `notifications.adapter.graph` (new) | `MicrosoftGraphAccessTokenProvider`, `MicrosoftGraphConfiguration`, `MicrosoftGraphEmailAdapter`, `MicrosoftGraphProperties`, `MicrosoftTokenResponse` |

### 1.2 Flyway migrations (V3+)

| Migration | Content |
|---|---|
| `V3__create_notification_outbox.sql` | `notification_outbox` table, with a unique `notification_key` and an index on `(user_id, created_at DESC)` |
| `V4__rename_delivered_notification_status.sql` | `DELIVERED` → `PROVIDER_ACCEPTED` |
| `V5__extend_kyc_review_state.sql` | Adds `RESUBMISSION_REQUIRED` to `chk_kyc_status`; adds `last_provider_event_at` |
| `V6__create_event_publication_registry.sql` | Spring Modulith `event_publication` table (`:5-18`) |
| `V7__protect_initial_final_kyc_rejections.sql` | `ever_approved`, `initial_final_rejection_locked`, `manual_review_*` columns |
| `V8__create_customer_transfer_idempotency.sql` | `customer_transfer_idempotency`, with PK `(sender_user_id, request_key)` (`:26`) and status `IN_PROGRESS / SUCCEEDED / UNKNOWN` (`:13-18`) |
| `V9__create_transfer_reconciliation_audit.sql` | Audit of `CONFIRM_POSTED` only (`:8-15`) |
| `V10__allow_in_progress_transfer_reconciliation.sql` | Also allows `IN_PROGRESS → SUCCEEDED` reconciliation (`:11-18`) |
| `V11__create_payout_requests.sql` | `payout_requests`, with status `REQUESTED, RESERVE_IN_PROGRESS, RESERVE_UNKNOWN, RESERVED, SETTLE_IN_PROGRESS, SETTLE_UNKNOWN, RELEASE_IN_PROGRESS, RELEASE_UNKNOWN, LEDGER_SETTLED, RELEASED` (`:22-32`), plus trigger guards |
| `V12__create_payout_reconciliation_audit.sql` | Payout reconciliation audit (`RESERVE / SETTLE / RELEASE`) (`:25-53`) |

Out-of-band SQL is in `ops/postgres/grant_production_runtime.psql` and `ops/postgres/harden_payout_reconciliation_audit.psql`. These are manual production scripts: `application-prod.yml:6-7` sets `flyway.enabled: false`.

### 1.3 Entities / persistence style

- **JPA entities:**
  - `NotificationOutboxEntity` is new.
  - `KycStatusEntity` is extended.
- **Plain `JdbcTemplate`, no JPA entity:**
  - `customer_transfer_idempotency` (`CustomerTransferIdempotencyService.java:131-301`, `CustomerTransferAdmissionService.java:129-150`)
  - `payout_requests`
  - the reconciliation audit tables
- **Formance is still the only balance store.** No balance column exists in PostgreSQL.

### 1.4 Configuration properties and profiles

Profiles are unchanged: `dev` (the default, `application.yaml:14-15`) and `prod`. New keys in `application.yaml`:

| Key | Default | Evidence |
|---|---|---|
| `spring.modulith.events.jdbc.schema-initialization.enabled` | `false` (Flyway V6 owns the table) | `application.yaml:4-8` |
| `spring.modulith.events.republish-outstanding-events-on-restart` | **`false`** | `application.yaml:10` |
| `neos.kyc.mode` | `${KYC_MODE:MOCK}` | `application.yaml:36` |
| `neos.kyc.sumsub.{base-url, app-token, secret-key, level-name, sdk-token-ttl-seconds, sandbox-mode}` | `https://api.sumsub.com`, empty, empty, `basic-kyc-level`, `600`, `true` | `application.yaml:37-43` |
| `neos.kyc.sumsub.webhook-enabled` | **Not in any YAML.** Absent means the controller is not created. | `SumsubWebhookController.java:24-28` |
| `neos.kyc.sumsub.webhook-secret` | **Not in any YAML.** `@Value` default is empty. | `SumsubWebhookController.java:42-43` |
| `neos.backoffice.synthetic-credit.enabled` | `${NEOS_SYNTHETIC_CREDIT_ENABLED:false}` | `application.yaml:50-52` |
| `neos.notifications.microsoft-graph.{enabled, tenant-id, client-id, client-secret, sender, graph-base-url}` | `false`, empty ×3, `no-reply@neos-world.com`, `https://graph.microsoft.com/v1.0` | `application.yaml:53-60` |
| `neos.notifications.dispatcher.{enabled, batch-size, fixed-delay-ms, initial-delay-ms}` | `false`, 25, 5000, 5000 | `application.yaml:62-66` |
| `neos.transfers.customer-api-enabled` | `${NEOS_CUSTOMER_TRANSFER_API_ENABLED:false}` | `application.yaml:68-69` |
| `neos.payouts.{ledger-finalization-enabled, reconciliation-enabled, notification-repair-enabled}` | all `false` | `application.yaml:70-73` |

`.env.example` was moved from `src/` to the repo root. It still lists only `SPRING_PROFILES_ACTIVE`, `SERVER_PORT`, `DB_*` and `FORMANCE_*` (`.env.example:1-13`). It omits `KEYCLOAK_ISSUER_URI`, `KYC_MODE`, `SUMSUB_*`, the webhook keys, `MICROSOFT_GRAPH_*`/`AZURE_*`, `NOTIFICATION_DISPATCHER_*`, `NEOS_CUSTOMER_TRANSFER_API_ENABLED` and `NEOS_PAYOUT_*`.

### 1.5 Feature flags vs. HTTP surface

| Capability | Gate | HTTP entry point |
|---|---|---|
| Transaction history | none (always on) | `GET /api/transactions` |
| Customer internal transfer | `neos.transfers.customer-api-enabled=true` | `POST /api/transfers/internal` |
| Sumsub webhook | `neos.kyc.sumsub.webhook-enabled=true` **and** `neos.kyc.mode=SUMSUB` (otherwise 503, `SumsubWebhookController.java:87-91`) | `POST /api/webhooks/sumsub` |
| Synthetic credit | `neos.backoffice.synthetic-credit.enabled=true` (service and controller, `SyntheticCreditService.java:15-20`) | `POST /api/backoffice/synthetic-credits` |
| Transfer reconciliation | none | **none.** `CustomerTransferManualReconciliationService` has no caller in `src/main`. |
| Payout admission / reservation / settlement / release / reconciliation / notification repair | `neos.payouts.*` for 3 of 9 services (`PayoutFinalizationExecutionService.java:26-31`, `PayoutManualReconciliationService.java:38-43`, `PayoutNotificationRepairService.java:23-28`) | **none.** `PayoutRequestAdmissionService.admit(...)` (`:160`) has no caller in `src/main` (grep). |
| Fee collection | none | **none** (no caller of `FeeCollectionService` in `src/main`) |

### 1.6 External dependencies

| Dependency | How it is used | Evidence |
|---|---|---|
| `spring-modulith-starter-jdbc` | Persistent event publication registry | `pom.xml:47-50` |
| `spring-boot-starter-restclient` | `RestClient` for Formance, Sumsub and Graph | `pom.xml:177-180` |
| `com.tngtech.archunit:archunit:1.5.1` (runtime) | Spring Modulith verification at runtime | `pom.xml:181-186` |
| **Sumsub** REST | HMAC-signed headers `X-App-Token`, `X-App-Access-Ts`, `X-App-Access-Sig` (`SumsubClient.java:29-36`). It calls `GET /resources/applicants/-/byExternalUserId/{userId}` (`:73`), `POST /resources/applicants?levelName=` (`:226-229`, `externalUserId = NEOS userId` at `:240`) and `POST /resources/accessTokens/sdk` (`:141-146`). | `kyc/adapter/sumsub/*` |
| **Sumsub** webhook | `X-Payload-Digest` + `X-Payload-Digest-Alg` HMAC over raw bytes (`SumsubWebhookController.java:68-102`). Only `applicantReviewed` / `completed` / `individual` are accepted, with matching sandbox flag and level (`SumsubReviewedEventParser.java:54-98`). Mapping: `GREEN` → `APPROVED`, `RED+FINAL` → `REJECTED`, `RED+RETRY` → `RESUBMISSION_REQUIRED` (`:117-143`, `SumsubDecisionService.java:79-86`). | |
| **Microsoft Graph** | Client-credentials token from `https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token` (`MicrosoftGraphConfiguration.java:30`, `MicrosoftGraphAccessTokenProvider.java:113`), then `POST /users/{sender}/sendMail` (`MicrosoftGraphEmailAdapter.java:174`) | `notifications/adapter/graph/*` |
| **Formance** | Unchanged client style. It adds `GET /api/ledger/v2/{ledger}/transactions` with a `$match` body filter or an opaque cursor (`FormanceClient.java:335-399`). | |

---

## 2. Status of the issues from `BACKEND_DELTA_AUDIT.md`

| # | Issue | Status | Evidence |
|---|---|---|---|
| 1 | Synthetic credits enabled in prod | **FIXED** (default off) | `application.yaml:50-52` now defaults to `false`. The service is also conditional (`SyntheticCreditService.java:15-20`). Residual risk: `NEOS_SYNTHETIC_CREDIT_ENABLED=true` still enables it in prod. There is no `@Profile` guard and no prod override in `application-prod.yml`. |
| 2 | Wallet provisioning has no retry when Formance is down | **PARTIALLY FIXED** | Durable: yes. Automatic retry: **no**. See §2.1. |
| 3 | No wallet/balance read endpoint | **NOT FIXED** | No controller maps wallets, balances or accounts (all `@RestController`s: `AuthController`, `KycController`, `SumsubWebhookController`, `PersonalOnboardingController`, `TransactionHistoryController`, `CustomerInternalTransferController`, `SyntheticCreditController`). `FormanceClient.getAccountBalance` (`:203`) has no `src/main` caller. |
| 4 | No local Formance setup / strict schema mode | **NOT FIXED** | `git diff 051cfec..HEAD -- infra` is empty. `infra/` still contains only `formance/schemas/*.json` and `keycloak/`. Nothing creates the ledger, uploads schema `v1.1.0` or enables enforcement. Writes still pass `?schemaVersion=` (`FormanceClient.java:533-536`). Whether Formance enforces it is **UNVERIFIED**. |
| 5 | `server.port` 8082 dev default | **NOT FIXED** | `application-dev.yml:1-2` `${SERVER_PORT:8082}`. Formance default is still `http://localhost:8080` (`application-dev.yml:27`). The mobile default API URL is still `http://localhost:8080` (`mobile/src/config.ts:42`). `.env.example:3` still says 8082. |
| 6 | Keycloak `neos-mobile` empty `redirectUris` | **NOT FIXED** | `infra/keycloak/realm/neos-realm.json:929` (`neos-mobile`) → `"redirectUris": [ ]` at `:939-941`. There is still no `neos://auth`. |
| 7a | No `@ControllerAdvice` | **NOT FIXED** | grep for `ControllerAdvice` / `ExceptionHandler` / `ProblemDetail` in `src/main` returns nothing. Errors come from `@ResponseStatus` exceptions (`KycOnboardingRequiredException.java:6` 409, `TransactionHistoryCustomerNotFoundException.java:6-8` 404, `UnsupportedTermsVersionException.java:6` 400) and `ResponseStatusException` (transfers). Everything else (`IllegalArgumentException`, `IllegalStateException`, `LedgerProviderUnavailableException`, …) becomes a default **500**. |
| 7b | `/api/auth/me` `Map.of` null email | **NOT FIXED** | `identity/api/AuthController.java:22-28` is unchanged. `Map.of` throws an NPE on a null `email` or `preferred_username` claim, which gives a 500. |

### 2.1 Wallet provisioning durability (detail)

- **Event registry is present.**
  - `spring-modulith-starter-jdbc` (`pom.xml:47-50`) and table `event_publication` (`V6...sql:5-18`) give a JDBC event publication registry.
  - The listener is still a plain `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)` (`WalletProvisioningOnKycApproved.java:29-33`).
  - Spring Modulith registers publications for transactional listeners, and the code comment relies on this (`:44-45`).
  - Test evidence (not executed by this audit):
    - `SumsubDecisionPersistenceIntegrationTest.failedWalletProvisioningRemainsRecoverable` (`:393-448`) expects a `FAILED` row with `completion_date IS NULL` after a simulated Formance outage.
    - The same test expects the decision call to still return `APPLIED` (`:404-411`). So the HTTP caller is **not** given an error (**UNVERIFIED** at runtime).
- **Publication on the approval transition.**
  - The event is published on approval in both modes: MOCK (`KycStartService.java:168-179`) and SUMSUB webhook (`SumsubDecisionService.java:190-198`).
  - The listener re-checks eligibility before provisioning (`WalletProvisioningOnKycApproved.java:40-42`).
- **No automatic retry.**
  - `republish-outstanding-events-on-restart: false` (`application.yaml:10`).
  - No production code uses `IncompleteEventPublications` / `FailedEventPublications`, and there is no scheduled resubmission (grep: the only `@Scheduled` is `NotificationOutboxScheduler.java:66`).
  - Retry is demonstrated only by a **test** that calls `failedPublications.resubmit(options)` by hand (`SumsubDecisionPersistenceIntegrationTest.java:56, 539`).
  - Net effect: the failure is **recorded durably** but **nobody re-drives it** without a manual or ops action. There is no actuator or HTTP trigger: `management.endpoints.web.exposure.include: health,info` (`application.yaml:25-29`).

---

## 3. Running the backend locally (as of `2e05d7a`)

### 3.1 Containers / processes

| Component | How | Default address |
|---|---|---|
| PostgreSQL (app DB) | **Not in repo.** You must provide it. DB `fintech_dev`, user `fintech_app`. Flyway runs V1–V12 in dev (`application-dev.yml:15-17`). | `localhost:5432` (`application-dev.yml:20-22`) |
| Keycloak + its Postgres | `infra/keycloak/compose.yml` (Keycloak 26.7.2 `start-dev --import-realm`, admin/admin) | `localhost:8081` (`compose.yml:41-43`) |
| Formance stack (gateway path `/api/ledger/v2`) | **Not in repo.** Ledger `neos` and schema `v1.1.0` must exist. | `localhost:8080` by default (`application-dev.yml:27`). This collides with the mobile default. |
| Backend | `./mvnw spring-boot:run` | `localhost:8082` (`application-dev.yml:2`) |

The backend does not check Formance at startup: `verifyConnection` has no startup caller (grep). So it boots without Formance. History, transfers and wallet provisioning then fail at request time.

**Working port plan for the mobile app (UNVERIFIED):**
- Run Formance on `:8083` and set `FORMANCE_BASE_URL=http://localhost:8083`.
- Run the backend with `SERVER_PORT=8080`, so it matches `EXPO_PUBLIC_API_URL` / `mobile/src/config.ts:42`.
- Keep Metro on `:8082`.

### 3.2 Environment variables

| Purpose | Variables |
|---|---|
| Profile / DB | `SPRING_PROFILES_ACTIVE` (default `dev`), `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` |
| Auth | `KEYCLOAK_ISSUER_URI` (default `http://localhost:8081/realms/neos`, `application-dev.yml:9`) |
| Ledger | `FORMANCE_BASE_URL`, `FORMANCE_LEDGER_NAME`, `FORMANCE_SCHEMA_VERSION`, `FORMANCE_CONNECT_TIMEOUT`, `FORMANCE_READ_TIMEOUT` |
| KYC | `KYC_MODE` (`MOCK` default / `SUMSUB`). For SUMSUB: `SUMSUB_APP_TOKEN`, `SUMSUB_SECRET_KEY`, `SUMSUB_LEVEL_NAME`, `SUMSUB_SANDBOX_MODE`, `SUMSUB_SDK_TOKEN_TTL_SECONDS`, `SUMSUB_BASE_URL`. For the webhook: `NEOS_KYC_SUMSUB_WEBHOOK_ENABLED=true` and `NEOS_KYC_SUMSUB_WEBHOOK_SECRET` (relaxed binding of `neos.kyc.sumsub.webhook-*`; **UNVERIFIED**) |
| Transfers | `NEOS_CUSTOMER_TRANSFER_API_ENABLED=true` to expose `POST /api/transfers/internal` |
| Synthetic credits (to fund test wallets) | `NEOS_SYNTHETIC_CREDIT_ENABLED=true` (needs a Keycloak user with `COMPLIANCE_OFFICER`) |
| Notifications | `MICROSOFT_GRAPH_ENABLED`, `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`, `MAIL_FROM`, `MICROSOFT_GRAPH_BASE_URL`, `NOTIFICATION_DISPATCHER_ENABLED`, `NOTIFICATION_DISPATCHER_*` |

### 3.3 Fail-fast secrets

- **`KYC_MODE=SUMSUB` without `SUMSUB_SECRET_KEY` fails startup.**
  - `SumsubClient` builds a `SumsubRequestSigner` (`SumsubClient.java:55-57`).
  - The signer throws on a blank secret (`SumsubRequestSigner.java:19-20`).
- **Webhook enabled without a secret fails startup.** `SumsubWebhookSignatureVerifier.java:22-23` throws, and it is constructed in the controller constructor (`SumsubWebhookController.java:53-56`).
- **A local Sumsub webhook needs a public HTTPS tunnel** to reach `localhost`. There is no tooling for this in the repo.

### 3.4 Non-production KYC mode

- **The mock provider still exists and is the default.**
  - `KYC_MODE=MOCK`, or unset: `MockKycProvider.java:9-15`, `matchIfMissing = true`.
  - `POST /api/kyc/start` then returns `APPROVED` synchronously, activates the account and publishes `KycApprovedEvent` (`KycStartService.java:158-179`).
- **Nothing ties MOCK mode to the `dev` profile.**
  - Prod inherits `MOCK` unless `KYC_MODE` is set (`application.yaml:36`, `application-prod.yml` has no `neos.kyc`).
  - The response field `verificationMode` is hard-coded `"NON_PROD"` in both modes (`KycStartService.java:193`), so it cannot be used to tell them apart.

### 3.5 Notifications without Graph credentials

- **They can be fully disabled, and they are disabled by default.**
  - `MICROSOFT_GRAPH_ENABLED=false` means no `MicrosoftGraphConfiguration` and no `EmailNotificationPort` bean (`MicrosoftGraphConfiguration.java:17-21`).
  - `NOTIFICATION_DISPATCHER_ENABLED=false` means no scheduler (`NotificationOutboxScheduler.java:13-17`, `NotificationSchedulingConfiguration.java:13-17`).
  - `NotificationDeliveryWorker` returns 0 when no email port exists (`NotificationDeliveryWorker.java:55-69`), so startup does not need Graph.
- **Outbox rows are still enqueued** by transfers and credits through `enqueueSafely`. They stay `PENDING` in `notification_outbox`.
- Failures to enqueue are logged and swallowed (`FinancialTransactionNotificationService.java:42, 134`).

---

## 4. Other findings (new code)

| Sev. | Finding | Evidence |
|---|---|---|
| High | Account re-activation bypass through `POST /api/kyc/start` (see §0.3) | `KycStartService.java:102-109`, `UserEntity.java:125-127`, `SumsubDecisionService.java:174-185` |
| High | Prod KYC defaults to MOCK auto-approval | `application.yaml:36`, `MockKycProvider.java:10-15` |
| High (contract) | Insufficient funds and other Formance 4xx → `UNKNOWN` + 503, with the key blocked forever | `FormanceClient.java:587-617`, `CustomerTransferIdempotencyService.java:268-301, 384-390`, `V9...sql:8-15` |
| Medium | No SDK-token endpoint, so Sumsub flow cannot complete from mobile | `SumsubKycProvider.java:79-109` uncalled |
| Medium | KYC resubmission is impossible: `/api/kyc/start` short-circuits for every status ≠ `NOT_STARTED` | `KycStartService.java:90-115` |
| Medium | Transfer recipient eligibility is checked before the idempotency lookup. A `SUCCEEDED` replay returns 403 if the recipient became ineligible. A 403 vs 200 difference also tells callers whether a UUID is an approved, active customer. | `CustomerInternalTransferController.java:82`, `CustomerDebitAuthorization.java:32-52` |
| Medium (UNVERIFIED) | The history `cursor` is passed opaquely to Formance. Formance cursors encode the original filter and page size (`FormanceClient.java:335-359` comment). A tampered cursor could change the account filter or exceed `pageSize ≤ 100`. Postings are re-filtered by the caller's own address (`CustomerTransactionHistoryService.java:362-371, 391-400, 419-454`), so amounts of other users are not mapped, but page metadata is. | |
| Low | `pageSize` outside 1..100 → `IllegalArgumentException` → 500, not 400 | `CustomerTransactionHistoryService.java:66-70` |
| Low | `IN_REVIEW` exists but is never set (`markInReview` has no caller) | `KycStatusEntity.java:142-144` |
| Low | Onboarding always returns `kycStatus: "NOT_STARTED"`, even for an already-approved user re-onboarding | `PersonalOnboardingService.java:106-111` |
| Low | History ordering is Formance's default: no `order`/`sort` parameter is sent. Newest first is **UNVERIFIED**. | `FormanceClient.java:378-391` |
