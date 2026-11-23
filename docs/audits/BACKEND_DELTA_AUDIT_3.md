# Backend Delta Audit 3 — since `2e05d7a` (focus: PR #55)

- **Branch audited:** `test/integration-pr55` at `a2f97a8`. That is `origin/integration` (through PR #55 `a74c4e8`, which already contains PR #54 `feat/mobile-biometric-unlock`) plus the local merge of `chore/npm-audit` (`de1981d`, mobile `package-lock.json` only).
- **Baseline:** `2e05d7a` (end of `BACKEND_DELTA_AUDIT_2.md`, PR #49).
- **Method:** static reading only. Nothing was built, run or tested. The only runtime calls made were `GET /actuator/health` on `:8080` (200 `UP`) and Keycloak's public `/.well-known/openid-configuration` on `:8081` (200). **No admin endpoint was called, and no customer endpoint either.** Which build the process on `:8080` runs is **UNVERIFIED**: the Java processes were started on 2026-11-04 03:17, after the PR #55 merge (2026-11-03 23:23 +0100).
- **The backend developer's summary of PR #55 was not trusted.** Every claim below was checked against the code.
- Paths are relative to `src/main/java/com/fintech/` unless they start with `src/`, `mobile/`, `infra/`, `docs/` or `scripts/`.
- The root `.env` was read for **variable names only**. No value was printed, logged or copied.

---

## 0. Headline findings

1. **The MOCK KYC provider is gone in every profile, not only in prod.**
   - Deleted files: `kyc/KycMode.java`, `kyc/KycProperties.java`, `kyc/adapter/mock/MockKycProvider.java`.
   - `neos.kyc.mode` was removed from `src/main/resources/application.yaml`.
   - `KYC_MODE` / `neos.kyc.mode` now have **zero references** in `src/main` or `src/test` (grep).
   - **Consequence: `KYC_MODE=MOCK` is silently ignored.** It does not fail startup and does not auto-approve. A new customer now stays `PENDING` until a signed Sumsub webhook arrives.
   - **This breaks the mobile team's local test workflow.** Every live run in `mobile/VALIDATION.md` used `KYC_MODE=MOCK` to approve new users. Customers that were already approved in the local DB keep working.
2. **`SUMSUB_SECRET_KEY` is now required to boot in every profile.**
   - `SumsubClient` lost its `@ConditionalOnProperty(neos.kyc.mode=SUMSUB)` and is now an unconditional `@Component` (`kyc/adapter/sumsub/SumsubClient.java:21`).
   - Its constructor builds `SumsubRequestSigner` (`:49-51`), which throws on a blank secret (`kyc/adapter/sumsub/SumsubRequestSigner.java:19-23`).
   - A blank `SUMSUB_APP_TOKEN` is checked only in prod. In dev it is detected only when Sumsub answers the first call (`POST /api/kyc/start` → 500).
3. **The Sumsub SDK access-token endpoint is still missing.** `SumsubKycProvider.createVerificationSession` (`kyc/adapter/sumsub/SumsubKycProvider.java:86-119`) is still called by nothing in `src/main` (grep). The mobile app therefore still cannot run real KYC, and end-to-end onboarding of a **new** customer can no longer be completed from the app.
4. **The self-reactivation bug is FIXED.**
   - `KycStartService` no longer touches account status (`kyc/KycStartService.java:65-67` short-circuits for any status other than `NOT_STARTED`).
   - The unconditional `IdentityAccountService.activate()` was replaced by `activateIfPendingKyc()`, which takes a row lock (`identity/IdentityAccountService.java:31-52`).
   - Its only callers are `IdentityAccountService` itself (`:46`) and the webhook decision (`kyc/SumsubDecisionService.java:177-180`).
5. **Insufficient funds is still reported as an uncertain outcome.** There is no definitive rejection, no 422 and no `errorCode`.
   - `CustomerTransferIdempotencyService`, `CustomerInternalTransferController` and the Formance 4xx mapping were **not changed** since `2e05d7a` (`git log 2e05d7a..HEAD` on these files is empty).
   - A Formance 4xx still becomes `LedgerTransactionRejectedException` (`ledger/adapter/formance/FormanceClient.java:833-846`), then the claim is marked `UNKNOWN` and the API returns `503` (`ledger/CustomerTransferIdempotencyService.java:268-301`).
   - The new admin reconciliation can only **confirm a posting**. A claim that never posted (insufficient funds, Formance down before the posting) still **cannot be resolved by anyone** (§1.3).
6. **New admin APIs exist** (reconciliation inspect/confirm, notification repair). Their flags are not declared in any YAML, and their role `RECONCILIATION_ADMIN` **does not exist in the Keycloak realm export** (§1.3, §1.6).
7. **There are no new migrations** (`git diff --stat 2e05d7a..HEAD -- src/main/resources/db` is empty; still V1–V12), **no error-body contract** (no `@ControllerAdvice`/`ProblemDetail`/`errorCode` in `src/main`, grep), and the **customer wire contract is unchanged**.

---

## 1. Part 1 — What changed since `2e05d7a`

### 1.1 Commits

| Range | Content | Backend impact |
|---|---|---|
| PR #50 (`86c8c9f`), PR #53 (`635e27d`), PR #54 (`f412abc`) | Mobile: real integration, send/activity/receive, biometric unlock, Keycloak NEOS theme (`infra/keycloak/themes/neos/*`) | none (theme files only, not wired: `infra/keycloak/compose.yml:45-48` has no theme mount) |
| PR #51 (`80082c6`, `5b925c2`) | `GET /api/wallets`, `GET /api/profile`, `POST /api/recipients/lookup`, `transfers.customer-api-enabled=true` in dev | Already documented in `MOBILE_API_CONTRACT.md` §10–§12 as "feat/customer-read-apis". Now merged. PR #55 changed no main code in these files (`git diff --stat 80082c6..HEAD -- src/main/java`). |
| **PR #55** (`a74c4e8`): `8f1516d`, `57ba89b`, `592e05c`, `66c7cd1`, `2658e9d`, `c494fba`, `41ad450`, `87ba8dc` | KYC hardening, MOCK removal, prod Sumsub validation, Formance response identity check, admin reconciliation API, notification repair | §1.2–§1.4 |
| `de1981d` (local `chore/npm-audit`) | `mobile/package*.json` | none |

PR #55 changes 15 main files (+1077/−334), 2 config lines and 37 test files.

### 1.2 KYC

| Change | Evidence | Verified? |
|---|---|---|
| MOCK provider, `KycMode` and `KycProperties` deleted | `git diff f412abc..a74c4e8 --stat` (`MockKycProvider.java` −42, `KycMode.java` −6, `KycProperties.java` −9). `kyc/KycConfiguration.java:7-12` now enables only `SumsubProperties`. | yes |
| `neos.kyc.mode` removed from YAML | `src/main/resources/application.yaml:37-46` (no `mode` key) | yes |
| Sumsub beans unconditional | `SumsubClient.java:21`, `SumsubKycProvider.java:13` (`@Component`, the `@ConditionalOnProperty` was removed) | yes |
| `POST /api/kyc/start` is Sumsub-only and never approves | `KycStartService.java:75-82` creates or reuses the applicant. `:84-91` throws on a blank id. `:100-103` calls `startVerification("SUMSUB", id)` (status `PENDING`). No approval, activation or event code remains. | yes |
| `kyc/start` never reactivates | `KycStartService.java:55-67`: any non-`NOT_STARTED` status returns the stored status with no side effect. Tests: `KycStartIntegrationTest.approvedRetryCannotReactivateSuspendedAccount` (`src/test/.../kyc/KycStartIntegrationTest.java:181`), `…ClosedAccount` (`:190`), `startingVerificationCannotActivate{Suspended,Closed}Account` (`:199`, `:208`). | code yes; tests not run |
| Activation only from `PENDING_KYC` | `identity/IdentityAccountService.java:31-52` (`findByIdForUpdate`, returns `false` unless `PENDING_KYC`). `suspendIfActive` is at `:59-74`. | yes |
| Wallet provisioning only after a real activation | `kyc/SumsubDecisionService.java:176-198`: `KycApprovedEvent` is published only when `previousStatus != APPROVED && activated`. This was already the case in PR #49 (file unchanged since `2e05d7a`). | yes |
| Webhook gate moved into YAML | `application.yaml:45-46` adds `webhook-enabled: ${SUMSUB_WEBHOOK_ENABLED:false}` and `webhook-secret: ${SUMSUB_WEBHOOK_SECRET:}`. The old "503 unless mode=SUMSUB" check is gone (`kyc/api/SumsubWebhookController.java:64-177`). | yes |
| Prod configuration validator | `kyc/adapter/sumsub/SumsubProductionConfigurationValidator.java:13` (`@Profile("prod")`), `afterSingletonsInstantiated` `:41-100`, details in §2.1 | code yes; `SumsubProductionStartupIntegrationTest` not run |
| `verificationMode` still hard-coded `"NON_PROD"` | `KycStartService.java:119`, including in prod | yes |

**Residual KYC issues (new or still open):**
- **Resubmission is still impossible.** `RESUBMISSION_REQUIRED` short-circuits at `KycStartService.java:65-67`, and there is no SDK token, so the user cannot re-open Sumsub.
- **A once-approved customer who gets RED/RETRY and later GREEN stays `SUSPENDED` forever.** `suspendIfActive` (`SumsubDecisionService.java:216-219`) then `activateIfPendingKyc` returns `false` (`IdentityAccountService.java:40-44`). No endpoint or service un-suspends. This is safe, but it needs an ops procedure.
- **`kyc/start` calls Sumsub inside a DB transaction, without a row lock** (`KycStartService.java:34, 48-53, 75-82`). Two concurrent calls can both create an applicant. The 409 recovery in `SumsubKycProvider.java:60-83` mitigates this. Low.
- **Applicant lookup response shape (UNVERIFIED).** `SumsubClient.findApplicantId` (`:62-127`) expects a `{exists, applicant:{id, externalUserId}}` body from `GET /resources/applicants/-/byExternalUserId/{id}`. Whether real Sumsub returns that shape, and what it does for an unknown user (an HTTP 404 would surface as a 500 from `kyc/start`), was not verified here. The opt-in `SumsubApplicantSandboxIntegrationTest` (`-DRUN_SUMSUB_CREATE_IT=true`) covers it. **Run it before relying on the KYC tests.**
- **The webhook rejects provider `MOCK` rows** (`SumsubDecisionService.java:59-62`). Local users that MOCK approved before PR #55 keep `provider='MOCK'`. They stay `APPROVED`, but no Sumsub decision can ever change them.

### 1.3 Transfer safety

| Claim (backend dev) | Code reality | Evidence |
|---|---|---|
| "Formance response identity verification" | **TRUE, narrow.** After a posting, the returned `reference` must equal `internal_transfer:<operationId>`. Otherwise an `IllegalStateException` is thrown. It is caught as a ledger failure, so the claim becomes `UNKNOWN` and the API returns `503`. Notifications are not enqueued. | `ledger/InternalTransferService.java:125-140`, `CustomerTransferIdempotencyService.java:268-301`. Tests `InternalTransferServiceTest.mismatchedLedgerReferenceRequiresReconciliation` / `nullLedgerResponseRequiresReconciliation` (`src/test/.../ledger/InternalTransferServiceTest.java:240, 303`). |
| "Uncertain-outcome preservation" | **TRUE, unchanged behaviour.** Any exception after the claim → `UNKNOWN` → `503` "Transfer outcome not confirmed. Reconciliation is required." A same-key retry gives `409` "Transfer is in progress or requires reconciliation". | `CustomerTransferIdempotencyService.java:268-301, 386-390` |
| "Administrator reconciliation using verified evidence" | **TRUE, confirm-only.** It needs fresh Formance evidence `MATCHED`, otherwise `409`. It accepts `UNKNOWN`, or `IN_PROGRESS` older than 15 min. It writes `SUCCEEDED` and the audit row, then enqueues notifications after commit. **There is no "not posted" / "release" / "fail" action.** | `ledger/CustomerTransferManualReconciliationService.java:41-42, 73-82, 96-330`. `Finding` = `MATCHED`, `NOT_FOUND_UNCONFIRMED`, `INCONCLUSIVE` (`ledger/adapter/formance/FormanceInternalTransferEvidenceVerifier.java:32-36`). The `previous_status` checks are in `V9`/`V10` (unchanged). |
| Definitive rejection for insufficient funds | **NOT IMPLEMENTED** | `FormanceClient.java:833-846` (any 4xx → `LedgerTransactionRejectedException`), caught by the generic `catch (RuntimeException)` at `CustomerTransferIdempotencyService.java:268` |

**Effect for an UNKNOWN claim:**
- If Formance did post: an admin can confirm it. The customer's **same-key retry then returns the original `200`** (`CustomerTransferIdempotencyService.java:372-382`).
- If Formance did not post (insufficient funds, outage before the posting): the key stays `UNKNOWN` forever (`409` on every retry). The inspection endpoint will show `NOT_FOUND_UNCONFIRMED`.

### 1.4 Notification recovery

- `CustomerTransferNotificationRepairService` (new, `ledger/CustomerTransferNotificationRepairService.java`):
  - gate `neos.transfers.reconciliation.notification-repair-enabled=true` (`:23-28`);
  - `@PreAuthorize("hasAuthority('ROLE_RECONCILIATION_ADMIN')")` (`:94`);
  - only rows that **join a reconciliation audit row** are eligible ("We do not trust an unconfirmed UNKNOWN claim", `:118-140`);
  - re-enqueues sender and recipient email outbox entries through the idempotent outbox keys;
  - outcomes `OUTBOX_PRESENT | STILL_MISSING | INVALID_CONFIRMED_EVIDENCE` (`:36-40`);
  - no Formance call and no email send.
- The reconciliation confirm path now also enqueues notifications after commit (`CustomerTransferManualReconciliationService.java:314`, `enqueueSafely`). Test: `CustomerTransferReconciliationNotificationIntegrationTest` (not run).
- Notifications are still **email only**. There is no customer feed endpoint.

### 1.5 Migrations, config keys, flags

| Item | Status | Evidence |
|---|---|---|
| Flyway migrations | **none new** (V1–V12) | `src/main/resources/db/migration/`, empty diff |
| `neos.kyc.mode` / `KYC_MODE` | **REMOVED** | `application.yaml:37-46` |
| `neos.kyc.sumsub.webhook-enabled` / `SUMSUB_WEBHOOK_ENABLED` (default `false`) | **NEW in YAML** (it was a bare property before) | `application.yaml:45` |
| `neos.kyc.sumsub.webhook-secret` / `SUMSUB_WEBHOOK_SECRET` (default empty) | **NEW in YAML** | `application.yaml:46` |
| `neos.kyc.sumsub.sandbox-mode` / `SUMSUB_SANDBOX_MODE` (default `true`) | unchanged. It is read with `@Value`, not through `SumsubProperties`. | `application.yaml:44`, `SumsubWebhookController.java:43-44`, validator `:31-32` |
| `neos.transfers.reconciliation.admin-api-enabled` | **NEW, in no YAML.** Absent means the controller is absent (404). | `ledger/api/CustomerTransferReconciliationAdminController.java:22-26`, `CustomerTransferNotificationRepairAdminController.java:18-25` |
| `neos.transfers.reconciliation.notification-repair-enabled` | **NEW, in no YAML** | `CustomerTransferNotificationRepairService.java:23-28`, repair controller `:18-25` |
| `spring.config.import: optional:file:./.env[.properties]` | unchanged. The root `.env` is also read as a properties file. | `application.yaml:4` |

**Env var form for the two reconciliation flags (UNVERIFIED):**
- As a process environment variable (which `scripts/run-backend-local.ps1:2-4` exports), Spring's relaxed binding should accept `NEOS_TRANSFERS_RECONCILIATION_ADMIN_API_ENABLED` / `NEOS_TRANSFERS_RECONCILIATION_NOTIFICATION_REPAIR_ENABLED`.
- Inside `.env`, when it is read as a properties file, only the literal key `neos.transfers.reconciliation.admin-api-enabled=true` binds.

### 1.6 Roles

| Role | Used by | In `infra/keycloak/realm/neos-realm.json`? |
|---|---|---|
| `CUSTOMER` | all customer endpoints (`hasRole('CUSTOMER')`) | yes |
| `COMPLIANCE_OFFICER` | `POST /api/backoffice/synthetic-credits` (`ledger/api/SyntheticCreditController.java:42`) | yes |
| **`RECONCILIATION_ADMIN`** (new for HTTP) | both admin controllers (`CustomerTransferReconciliationAdminController.java:27`, `CustomerTransferNotificationRepairAdminController.java:26`) and the services (`CustomerTransferManualReconciliationService.java:96`, `CustomerTransferNotificationRepairService.java:94`, payout services) | **NO.** Realm roles in the export: `default-roles-neos, ADMIN, offline_access, OPERATIONS, uma_authorization, CUSTOMER, COMPLIANCE_OFFICER`. It must be created by hand. Keycloak realm roles are prefixed `ROLE_` (`identity/security/KeycloakRealmRoleConverter.java:19-37`). |

---

## 2. Complete REST surface (at `a2f97a8`)

These are all 12 `@RestController`s (grep). The status column is relative to `docs/api/MOBILE_API_CONTRACT.md`.

### 2.1 Customer endpoints

| # | Method / path | Role / gate | Request → 200 response | Errors (status only, no `errorCode`) | vs contract |
|---|---|---|---|---|---|
| C1 | `GET /api/auth/me` | `CUSTOMER` | → `{subject, username, email}` (`identity/api/AuthController.java:16-29`) | 401, 403, **500** if a claim is null (`Map.of`, `:22-28`) | UNCHANGED |
| C2 | `POST /api/onboarding/personal` | `CUSTOMER` | `{phoneNumber?, installationId, platform, termsVersion}` → `{userId, deviceId, kycStatus:"NOT_STARTED", termsVersion}` | 400, 401, 403, 500 | UNCHANGED |
| C3 | `POST /api/kyc/start` | `CUSTOMER` | `{}` → `{userId, status, provider, verificationMode:"NON_PROD"}` (`kyc/api/KycStartResponse.java`) | 409 not onboarded (`KycOnboardingRequiredException`), 401, 403, **500** for a Sumsub HTTP error, a bad credential, an invalid applicant id or an initial-final-rejection lock (`KycStartService.java:84-91`, `KycStatusEntity.java:171-176`) | **CHANGED (behaviour).** A new start → `status:"PENDING"`, `provider:"SUMSUB"`. It never returns `APPROVED` from a start, and never activates. The §4 "MOCK" text and the activation side effect are obsolete. |
| C4 | `GET /api/profile` | `CUSTOMER` | §11 | 401, 403, 404 not onboarded | UNCHANGED (merged) |
| C5 | `GET /api/wallets` | `CUSTOMER` | §10 | 401, 403, 404, 503 (`ledger/CustomerWalletService.java:128`) | UNCHANGED (merged) |
| C6 | `GET /api/transactions?currency&pageSize&cursor` | `CUSTOMER` | §6 | 400 bad currency, **500** for `pageSize` outside 1..100 (`ledger/CustomerTransactionHistoryService.java:61-68`), 404, 500 when Formance is down | UNCHANGED |
| C7 | `POST /api/recipients/lookup` | `CUSTOMER` + `neos.transfers.customer-api-enabled` (`true` in dev, `application-dev.yml:32-33`) | §12 | 400, 401, 403, 404 uniform, 429 | UNCHANGED (merged) |
| C8 | `POST /api/transfers/internal` | `CUSTOMER` + same flag (`ledger/api/CustomerInternalTransferController.java:25-29`) | §5 | 400, 403, 409 (two meanings, not distinguishable), 503 (two meanings), 500 | **CHANGED (internal only).** There is one more 503-uncertain cause (reference mismatch, §1.3). The wire is unchanged. |

### 2.2 System and admin endpoints

| # | Method / path | Role / gate | Request → response | Errors | vs contract |
|---|---|---|---|---|---|
| S1 | `POST /api/webhooks/sumsub` | public, HMAC (`identity/security/SecurityConfig.java:38-41`). Gate `SUMSUB_WEBHOOK_ENABLED=true` (`SumsubWebhookController.java:23-27`). | raw Sumsub JSON + `X-Payload-Digest`, `X-Payload-Digest-Alg: HMAC_SHA256_HEX` → empty `200` for applied, duplicate or stale (`:157`) | 401 bad or missing signature (`:91-99`), 400 malformed or unsupported (`:107-119`), 503 processing failed (`:159-175`) | **CHANGED.** The gate no longer depends on `KYC_MODE`, and the "503 unless SUMSUB" check was removed. |
| S2 | `POST /api/backoffice/synthetic-credits` | `COMPLIANCE_OFFICER` + `NEOS_SYNTHETIC_CREDIT_ENABLED` | Appendix A.1 | 400, 401, 403, 500 | UNCHANGED |
| A1 | `GET /api/admin/transfers/reconciliation?senderId=<uuid>&limit=20` | `ROLE_RECONCILIATION_ADMIN` + `neos.transfers.reconciliation.admin-api-enabled` | → `[{senderUserId, requestKey, operationId, storedStatus, finding, formanceTransactionId}]` (`ledger/CustomerTransferReconciliationInspectionService.java:42-49`). It reads Formance; nothing is written. | 400 `limit` outside 1..100 (`CustomerTransferReconciliationAdminController.java:68-73`), 401, 403 | **NEW** |
| A2 | `POST /api/admin/transfers/reconciliation/{senderId}/{requestKey}/confirm` | same | no body → `{transactionId, reference, status:"SUCCEEDED"}` (`:99-123`) | 404 no claim (`CustomerTransferManualReconciliationService.java:380-381`), 409 not `UNKNOWN`/stale `IN_PROGRESS` (`:141`), 409 evidence not `MATCHED` (`:164`), 409 concurrent change (`:188, 199, 225`), 503 Formance/DB failure (`:271-272`), 401, 403 | **NEW** |
| A3 | `POST /api/admin/transfers/reconciliation/{senderId}/notifications/repair?afterRequestKey=<uuid>&batchSize=25` | same + `neos.transfers.reconciliation.notification-repair-enabled` | → `{results:[{requestKey, senderOutcome, recipientOutcome}], nextCursor}` (`CustomerTransferNotificationRepairService.java:42-52`) | 400 `batchSize` outside 1..100 (`CustomerTransferNotificationRepairAdminController.java:70-75`), 400 bad UUID, 401, 403. Other failures: default 500 (the test `repairFailureReturnsServiceUnavailable` expects 503; source **UNVERIFIED**). | **NEW** |
| S3 | `GET /actuator/health` (public), `GET /actuator/info` (JWT) | – | – | – | UNCHANGED |

Error bodies are still Spring Boot's default JSON for exceptions, and empty bodies for the 401/403 raised by Spring Security. There is no `errorCode` anywhere (grep for `ControllerAdvice|ExceptionHandler|ProblemDetail|errorCode` in `src/main` returns nothing).

### 2.3 Endpoint existence: explicit answers

| Capability | Exists? | Notes |
|---|---|---|
| Sumsub SDK access token | **NO** | `createVerificationSession` is uncalled (`SumsubKycProvider.java:86-119`) |
| KYC status read | **YES** | `GET /api/profile` `kycStatus` + `accountStatus` (C4). `kyc/start` is no longer a safe status read, because it creates an applicant when `NOT_STARTED`. |
| KYC resubmission | **NO** | §1.2 |
| Top-up / deposit instructions (Receive) | **NO** | Receive today is your own email/`userId` from `GET /api/profile`. Inbound money arrives only through internal transfers or the dev synthetic credit (S2). |
| Holds / reservations (Hold) | **NO customer endpoint** | Only the read side: `pendingMinor` in `GET /api/wallets` sums `…:holds:*` (contract §10). Reservations are created only by payout services, which have no HTTP entry (`ledger/PayoutReservationExecutionService.java` etc., no controller). |
| FX quote / convert | **NO** | no controller, no service |
| Payouts | **NO HTTP endpoint** (customer or admin) | services only; `neos.payouts.*` flags (`application.yaml:80-83`) |
| Notifications feed | **NO** | email outbox only (A3 repairs the outbox; it is not a feed) |
| Transfer status by key | **NO** | the customer can still only search history by reference. A1/A2 are admin-only. |

---

## 3. Status of previously reported issues

| # | Issue (audit 2 / contract) | Status | Evidence |
|---|---|---|---|
| 1 | Self-reactivation through `POST /api/kyc/start` | **FIXED** | `KycStartService.java:55-67`. `IdentityAccountService.java:31-52` (`activateIfPendingKyc`, locked). `UserEntity.activate()` is now called only at `IdentityAccountService.java:46` (grep). |
| 2 | `KYC_MODE` defaults to MOCK in prod | **FIXED by removal.** MOCK no longer exists in any profile. | §1.2 |
| 3 | Transfer rejection (insufficient funds) recorded as UNKNOWN | **NOT FIXED.** Still `503` + `UNKNOWN`, no 422, no `errorCode`, key blocked forever. Admin confirm cannot resolve a never-posted claim. | `FormanceClient.java:833-846`, `CustomerTransferIdempotencyService.java:268-301, 386-390`, `CustomerTransferManualReconciliationService.java:164` |
| 4 | Wallet provisioning retry after a Formance outage | **NOT FIXED** (durable, no automatic retry) | `application.yaml:11` `republish-outstanding-events-on-restart: false`. `ledger/WalletProvisioningOnKycApproved.java:29` unchanged. The only `@Scheduled` is `notifications/NotificationOutboxScheduler.java:66`. There is no `IncompleteEventPublications`/`FailedEventPublications` use in `src/main` (grep). |
| 5 | Error body format / `errorCode` | **NOT FIXED** | grep returns nothing (§2.2) |
| 6 | `/api/auth/me` null email or username → 500 | **NOT FIXED** | `identity/api/AuthController.java:22-28` |
| 7a | Keycloak `neos-mobile` empty `redirectUris` | **NOT FIXED** | `infra/keycloak/realm/neos-realm.json:939` `"redirectUris": [ ]` (no realm commit since `2e05d7a`) |
| 7b | Realm registration (R1: `registrationAllowed`, `registrationEmailAsUsername`, `displayName`, `CUSTOMER` in default roles) | **NOT FIXED in the export** | Export: `registrationAllowed=false`, `registrationEmailAsUsername=false`, no `displayName`. `default-roles-neos` composites are `offline_access, uma_authorization` only. A fresh `--import-realm` therefore breaks the app's "Let's begin". |
| 7c | Login theme (R3) | **NOT WIRED** | Theme files exist, but `compose.yml:45-48` has no mount and the realm has no `loginTheme` |
| 8 | Port defaults | **NOT FIXED** | backend dev `8082` (`application-dev.yml:2`), Formance default `8080` (`:27`), mobile default `http://localhost:8080` (`mobile/src/config.ts:66`), `.env.example:3` = 8082. Locally this is overridden by `SERVER_PORT` in `.env` (the name is present). |
| 9 | `pageSize` out of range → 500 | NOT FIXED | `CustomerTransactionHistoryService.java:61-68` |
| 10 | `verificationMode` always `NON_PROD` | NOT FIXED | `KycStartService.java:119` |
| 11 | `IN_REVIEW` never set | NOT FIXED (harmless) | unchanged |
| 12 | Reconciliation has no HTTP entry | **FIXED (confirm only)**, admin + flag | A1/A2 |

---

## 4. Breaking changes and incompatibilities for the mobile app

**Wire level:** nothing that the app parses changed.
- `mobile/src/api/contract.ts` still matches every customer DTO.
- `KycStartResponse.provider` is typed `string` (`contract.ts:51-57`).
- The transfer request and response are unchanged.

**Behaviour level:** the following break or change what the app or the test team relies on.

| # | Incompatibility | Mobile evidence | Required mobile change |
|---|---|---|---|
| M1 | **New customers can no longer be approved.** `kyc/start` returns `PENDING`, and the app's next `GET /api/profile` shows `PENDING` indefinitely: there is no webhook unless Sumsub reviews a real applicant. | `mobile/src/services/real.ts:246-260` (submit → `/api/kyc/start` → `loadProfile`). `mobile/src/navigation/state.ts:13-14` (`PENDING`/`IN_REVIEW` → pending screen). | None for correctness: the pending screen is right. **Test workflow change:** use pre-approved users, or approve through Sumsub sandbox + webhook (§5.4). Update the stale text in `mobile/src/mocks/registry.ts:41` and `docs/api/INTEGRATION_STATUS.md:24` ("Works end-to-end with backend `KYC_MODE=MOCK`"). |
| M2 | **The simulated capture (`kycCapture` mock) is now misleading.** It uploads nothing, but `kyc/start` now creates a **real Sumsub sandbox applicant** with no documents. | `mobile/src/screens/Kyc.tsx:33-44` (capture before verify), `mobile/src/services/compose.ts:20`. In release builds `kycCapture` throws `FeatureUnavailableError` (`mobile/src/services/unavailable.ts:6-9`), so `kyc/start` is never reached. | Replace document/selfie capture with the Sumsub native SDK. **Order change:** `kyc/start` (applicant) → **SDK token (missing endpoint)** → SDK → poll `GET /api/profile`. Until then, label the mock "does not submit to Sumsub". |
| M3 | **SDK token endpoint missing** (backend) | – | Blocked. Ask for e.g. `POST /api/kyc/sdk-token` → `{token, expiresInSeconds}` (the shape of `KycProvider.VerificationSession`, `kyc/port/KycProvider.java:30-33`), allowed for `PENDING` and `RESUBMISSION_REQUIRED`. |
| M4 | **`RESUBMISSION_REQUIRED` has no backend path.** The app maps it to "more info" and routes the user to recapture, which cannot submit anything. | `mobile/src/api/profileMapping.ts:15`, `mobile/src/navigation/state.ts:18` | Show "We need more information. We'll contact you" with no recapture until M3 exists. A previously approved user is `SUSPENDED` and goes to restricted first (`state.ts:8`), which is correct. |
| M5 | **Re-approved users stay `SUSPENDED`** (§1.2) | `state.ts:8` → restricted | None. The copy should say "contact support". |
| M6 | **`kyc/start` failure modes changed.** Missing or invalid Sumsub credentials, a Sumsub outage or an applicant-lookup error now give **500**. Before, MOCK never failed. | `real.ts:250-254` → `kycError` | None (already mapped to "couldn't complete this check" + retry). Retrying is safe: the applicant is reused (`SumsubKycProvider.java:42-47`). |
| M7 | **Transfers: one more uncertain cause** (reference mismatch → 503 `UNKNOWN`). An admin confirm can now turn an `UNKNOWN` into `SUCCEEDED`, and the app's same-key "Try again" then gets the **replayed 200**. | `mobile/src/transfers/outcome.ts:54` (5xx → uncertain), `INTEGRATION_STATUS.md` "Try again (same key only)" | None. **Compatible.** Still no definitive rejection, so `outcome.ts:59-75` (422/`errorCode`) remains dead code against this backend. |
| M8 | **Insufficient funds is still uncertain** | `outcome.ts:6-8` | None. Keep the balance guard. The R0 error codes are still unanswered. |
| M9 | **Local realm re-import breaks sign-up** (the realm export lacks R1) | – | Re-apply R1 on the running realm after any re-import, or have the backend developer merge R1 into `neos-realm.json`. |

---

## 5. Part 2 — Running it locally now

### 5.1 Startup requirements (names only)

| Variable | Profile | If missing / blank | Evidence |
|---|---|---|---|
| `SUMSUB_SECRET_KEY` | **all** | **Startup fails.** `IllegalArgumentException("Sumsub secret key must not be blank")` while creating the `SumsubClient` bean. | `SumsubClient.java:21, 49-51`, `SumsubRequestSigner.java:19-23` |
| `SUMSUB_APP_TOKEN` | all | dev: boots, then `kyc/start` → Sumsub 401 → **500**. prod: startup fails. | `SumsubClient.java:84`, validator `:51-54` |
| `SUMSUB_LEVEL_NAME` (default `basic-kyc-level`) | all | Must name a level that exists in the Sumsub account (**UNVERIFIED** behaviour when it does not). It must also match the webhook `levelName`, otherwise 400. | `application.yaml:42`, `SumsubReviewedEventParser.java:94-100` |
| `SUMSUB_BASE_URL` (default `https://api.sumsub.com`), `SUMSUB_SDK_TOKEN_TTL_SECONDS` (default 600) | all | defaults apply. prod: `https://*.sumsub.com`, port 443, no query, TTL > 0. | validator `:66-71, 143-199` |
| `SUMSUB_SANDBOX_MODE` (default `true`) | all | Must equal the webhook's `sandboxMode`, otherwise 400. prod: must be `false`, otherwise startup fails. | parser `:75-81`, validator `:79-84` |
| `SUMSUB_WEBHOOK_ENABLED` (default `false`) | all | `false` → `/api/webhooks/sumsub` does not exist, so no KYC decision can arrive. prod: startup fails. | controller `:23-27`, validator `:90-95` |
| `SUMSUB_WEBHOOK_SECRET` | when the webhook is enabled | **Startup fails** when the webhook is enabled and the secret is blank (all profiles). prod: placeholder values are rejected too. | `SumsubWebhookController.java:50-53`, `SumsubWebhookSignatureVerifier.java:22-26`, validator `:97-100, 121-139` |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | dev has defaults; prod required | dev: falls back to the defaults in `application-dev.yml:20-22` | |
| `KEYCLOAK_ISSUER_URI` | dev default `http://localhost:8081/realms/neos` | – | `application-dev.yml:9` |
| `FORMANCE_BASE_URL`, `FORMANCE_LEDGER_NAME` | dev defaults (`:8080`, `neos`); prod `FORMANCE_BASE_URL` required | The app boots without Formance; requests then fail with 500/503 | `application-dev.yml:27-28`, `application-prod.yml:11-12` |
| `NEOS_CUSTOMER_TRANSFER_API_ENABLED` | dev default `true` | – | `application-dev.yml:33` |

Prod also has no `issuer-uri` in `application-prod.yml`. That is pre-existing and its effect at startup is **UNVERIFIED**.

### 5.2 `KYC_MODE=MOCK` in dev after PR #55

- **It is not accepted, not rejected and not blocked. It is ignored.** No code reads `KYC_MODE` or `neos.kyc.mode` any more (grep across `src/main` and `src/test`: 0 hits).
- With `KYC_MODE=MOCK` in `.env`, the dev backend starts normally, as long as `SUMSUB_SECRET_KEY` is set.
- `POST /api/kyc/start` then creates a **real Sumsub applicant** with the `.env` credentials and returns `PENDING`. **There is no auto-approval in any profile.**
- The test `SumsubProductionStartupIntegrationTest.developmentDoesNotRegisterProductionValidator` (`src/test/.../SumsubProductionStartupIntegrationTest.java:199`) confirms that only the prod validator is profile-specific.

### 5.3 Root `.env` vs code (names only; values not read)

- **Present:** `AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, AZURE_TENANT_ID, DB_PASSWORD, DB_URL, DB_USERNAME, FORMANCE_BASE_URL, GRAPH_TEST_RECIPIENT, KYC_MODE, MAIL_FROM, MICROSOFT_GRAPH_ENABLED, NEOS_CUSTOMER_TRANSFER_API_ENABLED, NEOS_SYNTHETIC_CREDIT_ENABLED, NOTIFICATION_DISPATCHER_BATCH_SIZE, NOTIFICATION_DISPATCHER_ENABLED, NOTIFICATION_DISPATCHER_FIXED_DELAY_MS, NOTIFICATION_DISPATCHER_INITIAL_DELAY_MS, RUN_GRAPH_IT, SERVER_PORT, SPRING_PROFILES_ACTIVE, SUMSUB_APP_TOKEN, SUMSUB_LEVEL_NAME, SUMSUB_SECRET_KEY`.
  - No line has an empty value. This was checked without printing any value.
- **Obsolete:** `KYC_MODE` (ignored). `RUN_GRAPH_IT` and `GRAPH_TEST_RECIPIENT` are used only by `src/test/.../MicrosoftGraphEmailAdapterIntegrationTest.java:32-52`.
- **Missing and needed for the KYC decision path:** `SUMSUB_WEBHOOK_ENABLED`, `SUMSUB_WEBHOOK_SECRET`. Without them no approval can ever arrive. `SUMSUB_SANDBOX_MODE` is missing too, but its default `true` is the right value for sandbox.
- **Missing and needed only for admin testing:** `NEOS_TRANSFERS_RECONCILIATION_ADMIN_API_ENABLED`, `NEOS_TRANSFERS_RECONCILIATION_NOTIFICATION_REPAIR_ENABLED` (env form **UNVERIFIED**, §1.5).
- **Missing, defaults are fine:** `KEYCLOAK_ISSUER_URI, FORMANCE_LEDGER_NAME, FORMANCE_SCHEMA_VERSION, FORMANCE_CONNECT_TIMEOUT, FORMANCE_READ_TIMEOUT, SUMSUB_BASE_URL, SUMSUB_SDK_TOKEN_TTL_SECONDS, NEOS_RECIPIENT_LOOKUP_MAX_PER_MINUTE, MICROSOFT_GRAPH_BASE_URL, NEOS_PAYOUT_*`.
- **Nothing that startup strictly requires is missing.** `SUMSUB_SECRET_KEY` is present and non-empty. Whether it is a *valid* Sumsub secret is **UNVERIFIED**.

### 5.4 How a Sumsub decision reaches the backend locally

1. **Real path.** Sumsub (sandbox) calls `POST {public HTTPS URL}/api/webhooks/sumsub`. Locally this needs:
   - a **tunnel** to the backend port (for example `cloudflared tunnel --url http://localhost:8080` or `ngrok http 8080`; there is no tooling in the repo);
   - the webhook registered in the Sumsub sandbox dashboard for `applicantReviewed`, with the secret = `SUMSUB_WEBHOOK_SECRET` and digest `HMAC_SHA256_HEX`;
   - `SUMSUB_WEBHOOK_ENABLED=true`, `SUMSUB_SANDBOX_MODE=true`, and a level name matching `SUMSUB_LEVEL_NAME`.

   The applicant must also actually be reviewed. The app cannot upload documents (no SDK, no token). Options (all **UNVERIFIED**, Sumsub-side features):
   - a Sumsub WebSDK / applicant link generated from the dashboard and completed in a browser;
   - a sandbox review simulation in the dashboard or the sandbox API.

   Note: the parser **rejects payloads with `testMode: true`** (`SumsubReviewedEventParser.java:83-90`), so the dashboard's "send test webhook" cannot approve anyone.
2. **Local-only alternative: a hand-signed webhook.** This needs no backend change:
   - POST to `http://localhost:<port>/api/webhooks/sumsub` a body with `type=applicantReviewed`, `reviewStatus=completed`, `applicantType=individual`, `sandboxMode=true`, `levelName=<SUMSUB_LEVEL_NAME>`, `externalUserId=<NEOS userId>`, `applicantId=<kyc_status.provider_applicant_id>`, `reviewResult.reviewAnswer=GREEN` (or `RED` + `reviewRejectType` `FINAL`/`RETRY`) and `createdAtMs` formatted `yyyy-MM-dd HH:mm:ss.SSS` (UTC). Sources: `SumsubReviewedEventParser.java:16-19, 54-150`, `SumsubDecisionService.java:59-76`.
   - Send `X-Payload-Digest = hex(HMAC-SHA256(secret, rawBody))` and `X-Payload-Digest-Alg: HMAC_SHA256_HEX`.
   - This runs the real verified code path: lock, ordering, activation, `KycApprovedEvent`, wallets.
   - It requires the webhook secret and a read of `provider_applicant_id` from the local DB. **Use it only against a local backend and a throwaway DB, never against a shared environment.** It is equivalent to forging a Sumsub decision.
3. **Is there any other non-production way to approve KYC?**
   - **No.** There is no mock provider, no dev endpoint and no flag left in the code.
   - Editing the DB by hand (`kyc_status.status='APPROVED'`, `users.account_status='ACTIVE'`) is **not recommended**: it publishes no `KycApprovedEvent`, so **no wallets are provisioned** (`SumsubDecisionService.java:193`, `ledger/WalletProvisioningOnKycApproved.java:29`).
   - Users approved under MOCK before PR #55 stay approved and are the practical way to test everything after KYC.

### 5.5 Microsoft Graph locally

- **Sending requires both flags:**
  - `MICROSOFT_GRAPH_ENABLED=true` creates the Graph beans (`notifications/adapter/graph/MicrosoftGraphConfiguration.java:17-21`);
  - **and** `NOTIFICATION_DISPATCHER_ENABLED=true` creates the scheduler (`notifications/NotificationOutboxScheduler.java:13-17`, `NotificationSchedulingConfiguration.java:13-17`).
  - With Graph alone, nothing is sent.
- **Who receives the email:**
  - the **stored customer email** (`users.email`, taken from the JWT at onboarding), resolved through `UserContactLookup` (`notifications/FinancialTransactionNotificationService.java:98-110`);
  - sent with `POST /users/{MAIL_FROM}/sendMail`, `saveToSentItems` (`MicrosoftGraphEmailAdapter.java:166, 174`), from the real tenant mailbox named by `MAIL_FROM`.
  - **There is no allowlist and no recipient override.** `GRAPH_TEST_RECIPIENT` is test-only.
  - `@neos.local` test users → undeliverable (non-delivery reports land in the sender mailbox). Self-registered users with real addresses → **real emails**.
- **Backlog risk.** The dispatcher claims every `PENDING`/`RETRY` row (`notifications/repository/NotificationOutboxRepository.java:41`). Every transfer and credit made in earlier local runs left outbox rows. **Turning the dispatcher on would send that whole backlog.**
- **Recommended local settings:**
  - `MICROSOFT_GRAPH_ENABLED=false`, `NOTIFICATION_DISPATCHER_ENABLED=false`. Outbox rows still accumulate as `PENDING` and can be checked in `notification_outbox`.
  - To test real email delivery:
    - use only users whose email is a mailbox you control;
    - first inspect or clean the `PENDING`/`RETRY` rows of the local DB;
    - set `MAIL_FROM` to a dedicated test sender;
    - turn it off afterwards.
