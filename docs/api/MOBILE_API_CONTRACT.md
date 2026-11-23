# NEOS Mobile API Contract (customer app)

- Source of truth: backend at `2e05d7a` (`feat/mobile-on-integration` = `origin/integration`, through PR #49).
- Method: static code reading. Nothing was executed. Anything inferred is marked **UNVERIFIED**.
- Paths are relative to `src/main/java/com/fintech/` unless they start with `src/`, `mobile/` or `infra/`.
- Companion audit: `docs/audits/BACKEND_DELTA_AUDIT_2.md`.
- **Update:** §10–§12 add three endpoints **implemented on `feat/customer-read-apis`** (not merged yet). They were run against Formance v2.4.14, Keycloak and Postgres, and smoke-tested. The rest of the document still describes `2e05d7a`.
- **Mobile side:** what the app actually calls today (real/mock/placeholder) is tracked in `docs/api/INTEGRATION_STATUS.md`. §8 below is the pre-integration analysis. The app now uses §5, §6, §10, §11 and §12 (transfers, history, wallets, profile/status, recipient lookup). The wire types live in `mobile/src/api/contract.ts`; the transfer outcome handling is described in `INTEGRATION_STATUS.md` ("Internal transfer outcomes").

---

## 0. Global conventions

### 0.1 Transport and auth
- Base URL:
  - dev backend: `http://localhost:8082` (`src/main/resources/application-dev.yml:2`)
  - mobile dev default: `http://localhost:8080` (`mobile/src/config.ts:42`), which is a mismatch
- Auth is `Authorization: Bearer <Keycloak access token>` (realm `neos`).
  - Every route except `GET /actuator/health` and `POST /api/webhooks/sumsub` requires a JWT (`identity/security/SecurityConfig.java:30-45`).
- Roles:
  - They come from `realm_access.roles` and are prefixed `ROLE_` (`identity/security/KeycloakRealmRoleConverter.java:19-37`).
  - Customer endpoints need realm role `CUSTOMER`.
- Keycloak client:
  - Client `neos-mobile` is public, standard flow, PKCE `S256` (`infra/keycloak/realm/neos-realm.json:929-965`).
  - **`redirectUris` is empty** (`:939-941`), so `neos://auth` must be added by hand.
- Sessions are stateless and CSRF is disabled (`SecurityConfig.java:22-28`).

### 0.2 JSON types
| Concept | Wire representation | Evidence |
|---|---|---|
| Amount | **JSON number, integer minor units** (Java `long`). There is no decimal and no string form. | `ledger/api/CustomerInternalTransferRequest.java:17-18`, `ledger/CustomerTransactionItem.java:13` |
| Currency | String enum `EUR`, `USD`, `GBP`, `AED`, `MUR`, `TND` | `ledger/internal/taxonomy/LedgerCurrency.java:21-26` |
| Currency scale | **Not sent by any endpoint.** Fixed table: `EUR/USD/GBP/AED/MUR` = 2, **`TND` = 3**. The client must hard-code it. | `LedgerCurrency.java:21-36` |
| Timestamps | `java.time.Instant`. Expected ISO-8601 UTC string, e.g. `"2026-10-30T14:03:11.123Z"` (Jackson 3 / Boot 4 default; **UNVERIFIED** at runtime, and there is no Jackson config in `application*.yml`) | `CustomerTransactionItem.java:16` |
| IDs | `userId`, `deviceId`, `idempotencyKey` are UUID strings. `transactionId` is a JSON number (Formance tx id, `long`). | |
| Enums | Serialized as their name string | |
| Nulls | Included as `null` (Boot default; e.g. `nextCursor` on the last page). **UNVERIFIED** | |

JS safety: `Number.MAX_SAFE_INTEGER` (2^53−1) exceeds any realistic minor-unit amount. Formance amounts are big integers, and the backend narrows them to `long` (`ledger/adapter/formance/FormanceTransactionPosting.java`, `long amount`).

### 0.3 Error responses (no `@ControllerAdvice`, no Problem Details)
- No `@ControllerAdvice`, `@ExceptionHandler` or `ProblemDetail` exists in `src/main` (grep).
- Errors therefore use Spring Boot's default error JSON. **UNVERIFIED** shape; by Boot default `message` is **omitted**:
  ```json
  {"timestamp":"2026-11-01T10:00:00.000+00:00","status":409,"error":"Conflict","path":"/api/transfers/internal"}
  ```
- 401 (missing or invalid token) and 403 (role check by `@PreAuthorize`, or `AccessDeniedException` from services) come from Spring Security. They have an **empty body** plus a `WWW-Authenticate: Bearer ...` header (**UNVERIFIED**).
- **Consequence: the client can switch only on HTTP status.** The backend sends different reason strings for the same status (for example the two kinds of `503` on transfers), but the client cannot see them.
- Any unexpected exception becomes `500`, for example `IllegalArgumentException`, `IllegalStateException`, `LedgerProviderUnavailableException` (Formance down) or `LedgerTransactionRejectedException`.

### 0.4 Idempotency
- No endpoint uses an `Idempotency-Key` **header**.
- The only client idempotency is the `idempotencyKey` **body field (UUID)** on `POST /api/transfers/internal` (§5).
- `POST /api/onboarding/personal` and `POST /api/kyc/start` are naturally idempotent server-side.

---

## 1. Endpoint list (customer-callable)

| # | Method | Path | Controller | Role | Gate |
|---|---|---|---|---|---|
| 1 | GET | `/api/auth/me` | `identity/api/AuthController.java:16-29` | `CUSTOMER` | – |
| 2 | POST | `/api/onboarding/personal` | `onboarding/api/PersonalOnboardingController.java:25-45` | `CUSTOMER` | – |
| 3 | POST | `/api/kyc/start` | `kyc/api/KycController.java:25-37` | `CUSTOMER` | – |
| 4 | GET | `/api/transactions` | `ledger/api/TransactionHistoryController.java:32-54` | `CUSTOMER` | – (needs Formance) |
| 5 | POST | `/api/transfers/internal` | `ledger/api/CustomerInternalTransferController.java:51-113` | `CUSTOMER` | `neos.transfers.customer-api-enabled=true` (`:25-29`; default `false`, `application.yaml:68-69`). On `feat/customer-read-apis` it is `true` in the **dev profile only** (§12). |
| 6 | GET | `/api/wallets` | `ledger/api/CustomerWalletController.java` | `CUSTOMER` | – (needs Formance). **feat/customer-read-apis**, §10 |
| 7 | GET | `/api/profile` | `onboarding/api/CustomerProfileController.java` | `CUSTOMER` | –. **feat/customer-read-apis**, §11 |
| 8 | POST | `/api/recipients/lookup` | `ledger/api/RecipientLookupController.java` | `CUSTOMER` | same flag as #5. **feat/customer-read-apis**, §12 |
| 9 | GET | `/api/notifications` | `notifications/api/NotificationFeedController.java` | `CUSTOMER` | –. **feat/notifications-feed-api**, §13 |

**Endpoints that do not exist:**
- ~~wallets / balances~~: §10 (feat/customer-read-apis)
- KYC status `GET`: now readable through `GET /api/profile` (§11)
- Sumsub SDK token
- ~~account or profile status~~: §11 (feat/customer-read-apis)
- ~~recipient lookup~~: §12 (feat/customer-read-apis), email only
- transfer status
- payouts
- ~~notifications~~: §13 (feat/notifications-feed-api), read-only feed
- FX / convert
- top-up

See §7.

---

## 2. `GET /api/auth/me`

- **Preconditions:** a valid JWT with role `CUSTOMER`. No onboarding or KYC is needed.
- **Request:** no body. Only the `Authorization` header.
- **Response 200:** an untyped `Map` (`AuthController.java:22-28`).
  ```json
  {"subject":"8a1c…-keycloak-sub","username":"jane","email":"jane@example.com"}
  ```
  - `subject` is the JWT `sub`.
  - `username` is the `preferred_username` claim.
  - `email` is the `email` claim.
- **Errors:**
  - `401`.
  - `403`: the role is missing.
  - **`500`** if `email` or `preferred_username` is absent from the token, because `Map.of` rejects nulls (`:22-28`).
- **Note:** `userId` (the NEOS UUID) is **not** returned here.

## 3. `POST /api/onboarding/personal`

- **Preconditions:** `CUSTOMER`. The JWT must have a non-blank `email` claim; it is used as the profile email (`PersonalOnboardingController.java:33-35`, `identity/IdentityRegistrationService.java:44-48`).
- **Request** (`onboarding/api/PersonalOnboardingRequest.java:7-26`):

  | Field | Type | Constraints |
  |---|---|---|
  | `phoneNumber` | string, optional | `@Size(max=32)`. There is no format check. |
  | `installationId` | string | `@NotBlank`, `@Size(max=255)` |
  | `platform` | string | `@NotBlank`, `^(ANDROID\|IOS\|WEB)$` |
  | `termsVersion` | string | `@NotBlank`, `@Size(max=50)`. It must equal `neos.terms.current-version` = `"1.0"` (`application.yaml:32-33`), otherwise 400 (`onboarding/TermsPolicy.java:23`, `UnsupportedTermsVersionException.java:6`). |

- **Response 200** (`onboarding/api/PersonalOnboardingResponse.java:7-12`):
  ```json
  {"userId":"3f0e7c1a-…","deviceId":"b2d4…","kycStatus":"NOT_STARTED","termsVersion":"1.0"}
  ```
  - `kycStatus` is **always the literal `"NOT_STARTED"`**, even for a returning, already-approved user (`onboarding/PersonalOnboardingService.java:106-111`).
  - **Keep `userId`.** It is the identifier other customers need to send you money (§5).
- **Idempotency:** resolve-or-create by Keycloak subject, and the KYC row is created only if absent (`PersonalOnboardingService.java:98-99`, `kyc/KycInitializationService.java:30-37`).
- **Errors:**
  - `400`: validation, or a wrong `termsVersion`.
  - `401` / `403`.
  - `500`: blank email or other `IllegalArgumentException` (`IdentityRegistrationService.java:38-48`, `DeviceRegistrationService.java:50-54, 120-125`).

## 4. `POST /api/kyc/start` (start or poll KYC)

- **Preconditions:** `CUSTOMER`, and onboarding done. Otherwise `409` (`kyc/KycStartService.java:59-82`, `kyc/KycOnboardingRequiredException.java:6`).
- **Request:** the body is ignored. Send `{}`.
- **Response 200** (`kyc/api/KycStartResponse.java:7-12`):
  ```json
  {"userId":"3f0e7c1a-…","status":"PENDING","provider":"SUMSUB","verificationMode":"NON_PROD"}
  ```
  - `provider`: `"MOCK"` or `"SUMSUB"`. It is the configured mode name, written at start (`KycStartService.java:153-156`).
  - `verificationMode` is **always `"NON_PROD"`** (`:193`). Do not rely on it.
- **Behaviour:**
  - **`NOT_STARTED`:** creates or reuses the provider applicant and sets `PENDING` (`:136-156`).
    - In **MOCK** mode it then immediately sets `APPROVED`, activates the account and publishes `KycApprovedEvent`, which provisions the 6 wallets asynchronously after commit (`:158-179`). The response says `APPROVED`.
    - In **SUMSUB** mode the response says `PENDING`. The decision arrives later through the webhook (§A.2).
  - **Any other status:** returns the stored status without calling the provider (`:90-115`). **This is the only way to poll KYC status.**
  - Side effect: if the status is `APPROVED`, the account is force-activated (`:102-109`). This is a security bug; see the audit, §0.3.
- **Statuses the app can observe** (`kyc/entity/KycStatus.java:3-10`):

  | Status | Produced by | Meaning | App should show |
  |---|---|---|---|
  | `NOT_STARTED` | onboarding response only | Verification not started | KYC intro |
  | `PENDING` | start (SUMSUB) | Applicant created, waiting for the Sumsub review | "Verification in progress", with polling |
  | `IN_REVIEW` | **never set** (`KycStatusEntity.java:142-144` has no caller) | – | Treat as `PENDING` |
  | `APPROVED` | MOCK start, or webhook GREEN (`SumsubDecisionService.java:172-198`) | Verified. The account is usually `ACTIVE`, but it can be `SUSPENDED`/`CLOSED`, and account status is not exposed. | Approved / home |
  | `REJECTED` | webhook RED+FINAL (`:201-210`) | Final. A first-time final rejection is locked: later webhooks cannot auto-approve it (`:127-163`). | Rejected, **no retry** |
  | `RESUBMISSION_REQUIRED` | webhook RED+RETRY | The user must fix documents. A previously ACTIVE account is suspended with reason `KYC_REVERIFICATION` (PR #60). | "Update your documents": the app re-opens the Sumsub SDK with a new token (§4.1). |

> **Update 2026-11-04 (PRs #55, #58, #60, #61).** KYC `MOCK` mode no longer exists: every start creates or reuses a Sumsub applicant, and the decision only arrives through the webhook (§A.2). The read-only status source is `GET /api/profile` (§11), not this endpoint. The rows above that mention MOCK start describe the old behaviour.

- **Errors (PR #61, `kyc/api/KycApiErrorHandler.java`):** body `{"errorCode": string, "message": string, "status": number}`, `Cache-Control: no-store`.
  - `409 ONBOARDING_REQUIRED`: not onboarded.
  - `401` / `403` without a body: Spring Security (no token, or no `CUSTOMER` role).
  - `503 SUMSUB_UNAVAILABLE` (PR #64): Sumsub network error, connect/read timeout (3 s / 10 s, `neos.kyc.sumsub.http.*-timeout-ms`), or Sumsub answered 408/429/5xx. KYC stays `NOT_STARTED`, so Try again starts cleanly.
  - `502 SUMSUB_UPSTREAM_ERROR` (PR #64): Sumsub answered another 4xx, a malformed response, or a missing applicant ID; or a Sumsub 409 on create whose recovery lookup found no applicant. The create POST is never retried automatically.

### 4.1 `POST /api/kyc/sdk-token` (Sumsub SDK access token; PR #58, #60, #61, #64)

- **Auth:** Bearer customer JWT, `CUSTOMER`. **No body.** The customer is resolved from the JWT `sub`; the app never sends a userId or applicantId. The level (`SUMSUB_LEVEL_NAME`, `id-and-liveness` in the shared sandbox) is server-side.
- **200:** `{"token": string, "expiresInSeconds": number}`, `Cache-Control: no-store`, `Pragma: no-cache`. `expiresInSeconds` is the server's `SUMSUB_SDK_TOKEN_TTL_SECONDS` (default 600): use it, never a hardcoded value. The token is a credential: memory only, never logged, displayed or persisted.
- **Renewal:** on the SDK's token-expiration callback, call this endpoint again. No need to restart KYC.
- **Who gets a token** (`kyc/KycSdkTokenService.java`, `identity/IdentityAccountService.kycSdkAccess`):

  | Account (suspension reason) | `PENDING` / `IN_REVIEW` | `RESUBMISSION_REQUIRED` | `NOT_STARTED` | `APPROVED` | `REJECTED` |
  |---|---|---|---|---|---|
  | `PENDING_KYC`, `ACTIVE` | **200** | **200** | 409 `KYC_NOT_STARTED` | 409 `KYC_ALREADY_APPROVED` | 409 `KYC_FINAL_REJECTED` |
  | `SUSPENDED` (`KYC_REVERIFICATION`) | 403 `ACCOUNT_RESTRICTED` | **200** | 409 | 409 | 409 |
  | `SUSPENDED` (other reason), `CLOSED` | 403 `ACCOUNT_RESTRICTED` | 403 | 403 | 403 | 403 |

  Also: 409 `ONBOARDING_REQUIRED` (no profile), 409 `KYC_APPLICANT_INVALID` (no Sumsub applicant, or a legacy MOCK row), 403 `KYC_MANUAL_REVIEW_REQUIRED` (review lock), 502 `SUMSUB_UPSTREAM_ERROR` (Sumsub returned an invalid or blank token, a token for another user ID, a malformed response, or another 4xx), 503 `SUMSUB_UNAVAILABLE` (Sumsub network error, timeout, 408/429/5xx; PR #64).
- **Suspension reason:** `users.suspension_reason` (V13: `KYC_REVERIFICATION`, `KYC_FINAL_REJECTION`, `ADMINISTRATIVE`, `UNSPECIFIED`) is **not exposed** by `GET /api/profile`. The app therefore shows "Update your documents" for `SUSPENDED` + `RESUBMISSION_REQUIRED` and switches to the restricted screen when this endpoint answers `ACCOUNT_RESTRICTED`.
- **App mapping** (mobile `src/kyc/errors.ts`): codes are mapped directly. Without a code: 409 → re-read `GET /api/profile` (`NOT_STARTED` → start, then one more token request); 403 → re-read the profile (`SUSPENDED`/`CLOSED` → restricted, otherwise "manual review"); 5xx / network / timeout → "Verification is temporarily unavailable" + Try again (never retried automatically). Unknown codes: on a 5xx → "temporarily unavailable" + Try again; on a 4xx → generic message. The mobile request timeout stays 15 s: a `kyc/start` that outlasts it shows "temporarily unavailable", and Try again is safe because `kyc/start` is idempotent.

## 5. `POST /api/transfers/internal` (customer → customer, same currency)

- **Preconditions:**
  - The flag must be on. When it is off, the controller bean is absent and the call returns 404 (**UNVERIFIED**).
  - The sender needs `CUSTOMER`, an onboarded profile (otherwise 403, `CustomerInternalTransferController.java:61-68`), `KYC APPROVED` and an `ACTIVE` account. These are checked under row locks (`ledger/CustomerTransferAdmissionService.java:100-125`).
  - The **recipient** must also be `KYC APPROVED` and `ACTIVE` (`CustomerInternalTransferController.java:82`, `kyc/CustomerDebitAuthorization.java:32-52`).
- **Request** (`ledger/api/CustomerInternalTransferRequest.java:9-23`):

  | Field | Type | Constraints |
  |---|---|---|
  | `destinationUserId` | UUID | `@NotNull`. It must not be the sender's own id (400, `CustomerInternalTransferController.java:72-77`). |
  | `currency` | `LedgerCurrency` | `@NotNull`. One of the 6 codes. |
  | `amountMinor` | integer | `@Positive`. If missing it defaults to `0` and fails validation. |
  | `idempotencyKey` | UUID | `@NotNull`. Generate one per user intent and reuse it on retries. |

  ```json
  {"destinationUserId":"9b1d…","currency":"MUR","amountMinor":150000,"idempotencyKey":"5b7e0a52-0c64-4a3f-9d1b-2f0d6c8e9a11"}
  ```

- **Recipient identification:**
  - **NEOS `userId` UUID only.** There is no phone, email, handle or "Nova id" lookup endpoint.
  - On `feat/customer-read-apis`: `POST /api/recipients/lookup` (§12) turns an email into a `recipientUserId`, which the app sends as `destinationUserId`. The transfer contract is unchanged.
  - A user learns their own `userId` only from the `POST /api/onboarding/personal` or `POST /api/kyc/start` responses.
- **Execution:**
  - It is **synchronous**: claim → Formance posting `users:{src}:wallet:{CUR}:available → users:{dst}:wallet:{CUR}:available` → result (`ledger/CustomerTransferIdempotencyService.java:88-219`, `ledger/adapter/formance/FormanceLedgerProvider.java:45-104`).
  - The posting has no overdraft, so Formance rejects insufficient funds.
- **Response 200** (`ledger/api/CustomerInternalTransferResponse.java:3-6`):
  ```json
  {"transactionId":4711,"reference":"internal_transfer:ct_3f0e7c1a…(32 hex)_5b7e0a52…(32 hex)"}
  ```
  - The reference is deterministic: `internal_transfer:ct_<senderUserId without dashes>_<idempotencyKey without dashes>` (`CustomerInternalTransferController.java:89-94`, `FormanceLedgerProvider.java:554-557`).
  - There is no `status` field. A 200 means posted.
- **Idempotency semantics:**
  - The key is scoped per sender, with PK `(sender_user_id, request_key)` (`src/main/resources/db/migration/V8__create_customer_transfer_idempotency.sql:26`).
  - Possible results:
    - The same key with the same params, after success, replays the original `200` without a new posting (`CustomerTransferIdempotencyService.java:370-382`).
    - The same key with different `destinationUserId`, `currency` or `amount` gives `409` (`:358-368`).
    - The same key while the claim is `IN_PROGRESS` or `UNKNOWN` gives `409` (`:384-390`).
- **Outcome table:**

  | HTTP | Cause (server) | Funds moved? | Client action |
  |---|---|---|---|
  | 200 | Posted | yes | Success |
  | 400 | Validation, or self-transfer | no | Fix input |
  | 403 | Sender or recipient is not KYC-approved or not active, or the sender has no profile (`CustomerTransferAdmissionService.java:113-121`, `CustomerDebitAuthorization.java:46-50`) | no | "Transfer not allowed". The causes cannot be told apart. |
  | 409 | Key reused with different params | no (for this request) | Client bug. Use a new key only for a new intent. |
  | 409 | Previous attempt with this key is in progress or uncertain | **unknown** | Show "pending". Do **not** auto-retry with a new key. |
  | 503 | Pre-ledger authorization failed transiently. The claim was **released** (`:119-174`). | no | It is safe to retry with the **same** key, **but** the client cannot tell this from the next row (no message in the body, §0.3). |
  | 503 | Ledger call threw. The claim is marked `UNKNOWN` (`:268-301`). **This includes insufficient funds and every other Formance 4xx** (`FormanceClient.java:587-617`). | **unknown** | Show "transfer status uncertain". Retrying the same key gives 409 forever. |
  | 500 | Unexpected | unknown | Treat like 503-unknown |

- **Polling the outcome:**
  - There is **no transfer-status endpoint**.
  - The only signal is `GET /api/transactions?currency=<CUR>`: look for an item whose `reference` equals the deterministic reference above (type `TRANSFER`, direction `DEBIT`).
  - Absence is not proof of failure: it could be on a later page, or never posted.
  - `UNKNOWN` claims are resolved only by `CustomerTransferManualReconciliationService.confirmPostedTransfer` (`ledger/CustomerTransferManualReconciliationService.java:86`). It has **no HTTP endpoint** and supports only "posted" (`V9__create_transfer_reconciliation_audit.sql:8-15`).

## 6. `GET /api/transactions` (history, per currency)

- **Preconditions:**
  - `CUSTOMER` and an onboarded profile, otherwise `404` (`ledger/CustomerTransactionHistoryService.java:72-79`, `ledger/TransactionHistoryCustomerNotFoundException.java:6-8`).
  - KYC and account status are **not** checked.
  - Formance must be reachable, otherwise `500`.
- **Query parameters** (`TransactionHistoryController.java:38-45`):

  | Param | Type | Required | Notes |
  |---|---|---|---|
  | `currency` | `LedgerCurrency` | **yes** | Missing or invalid gives 400. There is one wallet per call, and there is no "all currencies" call. |
  | `pageSize` | int | no, default `20` | Must be 1..100. Out of range gives an `IllegalArgumentException`, so **500**, not 400 (`CustomerTransactionHistoryService.java:66-70`). Ignored when `cursor` is sent (`FormanceClient.java:335-359`). |
  | `cursor` | string | no | The opaque Formance cursor from `nextCursor`, passed through unchanged. |

- **Response 200** (`ledger/CustomerTransactionPage.java:5-11`, `ledger/CustomerTransactionItem.java:7-18`):
  ```json
  {
    "items": [
      {"transactionId":4711,"type":"TRANSFER","direction":"DEBIT","currency":"MUR","amountMinor":150000,
       "status":"COMPLETED","reference":"internal_transfer:ct_…_…","occurredAt":"2026-10-30T14:03:11Z"},
      {"transactionId":4702,"type":"INCOMING_PAYMENT","direction":"CREDIT","currency":"MUR","amountMinor":500000,
       "status":"COMPLETED","reference":"…","occurredAt":"2026-10-29T09:00:00Z"}
    ],
    "hasMore": true,
    "nextCursor": "eyJ…"
  }
  ```
  - `amountMinor` is always **positive**. The sign comes from `direction`.
  - `occurredAt` is the Formance tx `timestamp` (`CustomerTransactionHistoryService.java:481-490`).
- **Enums:**
  - `type` (`ledger/CustomerTransactionType.java:3-9`):
    - `INCOMING_PAYMENT`: Formance ops `incoming_payment`, `incoming_attribution`. This includes backoffice synthetic credits.
    - `TRANSFER`: op `internal_transfer`. `DEBIT` for the sender, `CREDIT` for the recipient.
    - `FEE`: op `fee_collection`, always `DEBIT`.
    - `PAYOUT`: always `DEBIT`. It is emitted **only once settled**.
    - Mapping: `CustomerTransactionHistoryService.java:152-241`.
  - `direction`: `CREDIT` / `DEBIT` (`CustomerTransactionDirection.java:3-7`).
  - `status`: `COMPLETED`, or `REVERSED` when the Formance tx is reverted (`CustomerTransactionStatus.java:3-7`, `CustomerTransactionHistoryService.java:476-479`). **There is no pending status.**
- **Holds and reservations:**
  - **Not represented.**
  - `payout_reserve` (available → hold) is mapped only when a matching `payout_settle` exists on the hold account. It is then shown as one `PAYOUT DEBIT` using the **settlement** tx id, reference and timestamp (`:243-353`).
  - `payout_release` and `payout_settle` are hidden (`:235-237`).
  - So a reserved-but-unsettled payout, or a released one, is invisible, even though `available` dropped while the payout was reserved.
  - Unknown operations, and transactions without `neos.operation` metadata, are dropped (`:139-150, 239-240`).
- **Pagination:**
  - Cursor-based, following Formance's `hasMore` / `next` (`:115-119`).
  - Items are filtered **after** Formance pages, so a page may hold fewer than `pageSize` items, **including zero with `hasMore: true`**. The client must keep paging on `hasMore`, not on an item count.
- **Ordering:** Formance default. No `order` param is sent (`FormanceClient.java:378-391`). Newest first is **UNVERIFIED**.
- **Filtering:** only by `currency`. There is no date, type or text filter.

---

## 7. Specific answers

### 7.1 Wallet balances
- **No endpoint returns balances** (available or pending, any currency). The only balance reader is the server-side `FormanceClient.getAccountBalance` (`ledger/adapter/formance/FormanceClient.java:203`), and it has no `src/main` caller.
- Balances **cannot be derived** from customer endpoints:
  - history is per-currency and paginated;
  - it hides holds and pending payouts (§6);
  - it drops non-NEOS transactions.
- **The app must not compute balances.**
- **On `feat/customer-read-apis`:** `GET /api/wallets` (§10) returns them.
- Wallets exist for `EUR, USD, GBP, AED, MUR, TND` after KYC approval (C2 provisioning, `ledger/WalletProvisioningOnKycApproved.java:29-49`). The app has no way to confirm provisioning happened. It may have failed and been left in `event_publication` (audit §2.1).

### 7.2 KYC with Sumsub
- **SDK access token:** `POST /api/kyc/sdk-token` (§4.1). The app runs the Sumsub React Native SDK (`@sumsub/react-native-mobilesdk-module` 1.46.1) with it and renews it on expiry.
- **Observable statuses:** `NOT_STARTED`, `PENDING`, `APPROVED`, `REJECTED`, `RESUBMISSION_REQUIRED` via `GET /api/profile`. `IN_REVIEW` is never produced.
- **Polling:** `GET /api/profile` (§11), read-only. The app polls with backoff (5 s → 60 s, at most 15 minutes) after the SDK closes, and on demand.
- **Resubmission:** supported: `RESUBMISSION_REQUIRED` gets a new SDK token, including a `SUSPENDED` account whose suspension reason is `KYC_REVERIFICATION` (PR #60).
- **Decision source:** `POST /api/webhooks/sumsub` (Appendix A.2), only when `SUMSUB_WEBHOOK_ENABLED=true` with `SUMSUB_WEBHOOK_SECRET`. The SDK's own result on the device is never the decision.

### 7.3 Transaction history
Covered in §6. The app sees the types `INCOMING_PAYMENT`, `TRANSFER`, `FEE` and `PAYOUT`, with status `COMPLETED` or `REVERSED`. Holds are not exposed.
- **Receipts:** there is **no receipt or statement endpoint**. The app generates a PDF receipt **on the device** from the §6 item or the §5 response and the data it already holds. That receipt is not legally authoritative. A server-generated receipt is out of scope for now (see `docs/api/INTEGRATION_STATUS.md`, "PDF receipts").

### 7.4 Internal transfers
Covered in §5:
- The recipient is identified by **`destinationUserId` UUID** only.
- Idempotency uses the **`idempotencyKey` UUID in the body**.
- Execution is **synchronous**.
- There is **no status endpoint**. Uncertain outcomes are `503` or `409`.

### 7.5 Payouts and external transfers
- **No HTTP endpoint at all**, for customers or backoffice.
- The services exist (`ledger/PayoutRequestAdmissionService.java:160` `admit(userId, requestKey, currency, amountMinor, settlementBankId)`, reservation, settlement and reconciliation), but have no `src/main` caller.
- The lifecycle in the DB: `REQUESTED → RESERVE_* → RESERVED → SETTLE_* | RELEASE_* → LEDGER_SETTLED | RELEASED` (`V11__create_payout_requests.sql:22-32`).
- The design suggests backoffice- or system-driven settlement. A customer payout endpoint is **UNVERIFIED / not designed**.

### 7.6 Notifications
- **Email only**, via the outbox: `NotificationChannel` has only `EMAIL` (`notifications/NotificationChannel.java:3-6`), and delivery goes through Microsoft Graph `sendMail`.
- **In-app feed (Option A):** `GET /api/notifications` (§13) reads the customer's own outbox rows, read-only. Read/unread state is kept on the device.
- **Option B (deferred):** a dedicated in-app table with server-side read state, cross-device unread counts and independence from email delivery. It needs a Flyway migration (`docs/api/INTEGRATION_STATUS.md`, R7).
- Push notifications: out of scope for this phase.

---

## 8. Mapping to the mobile app

### 8.1 Mocks in `mobile/src/mocks/registry.ts` and screens or actions

| Mock / screen / action | Backend replacement | Frontend work | Blockers |
|---|---|---|---|
| `auth` (`registry.ts:20-26`) | Keycloak PKCE + `GET /api/auth/me` (§2). Already real (`mobile/src/services/real.ts`). | none | Keycloak `neos-mobile` `redirectUris` is empty (`neos-realm.json:939-941`). `/me` returns 500 without an `email` claim. |
| `onboarding` (`:27-33`) | `POST /api/onboarding/personal` (§3). Already real. | Store `userId` from the response (needed for Receive). | none |
| `kyc` (`:34-42`) | `POST /api/kyc/start` (§4). Works end-to-end in backend `KYC_MODE=MOCK`. | Add `RESUBMISSION_REQUIRED` to the types and the route map (§8.2). Treat `IN_REVIEW` like `PENDING`. Do not offer a retry on `REJECTED`. | In SUMSUB mode: no SDK token endpoint, no resubmission, no `GET` status. The `replacedBy` text is stale: the Sumsub adapter now exists in the backend. |
| `kycCapture` (`:43-49`) | **none.** The backend accepts no images. The Sumsub native SDK replaces document and selfie capture. | Replace the `document` / `selfie` screens with a Sumsub SDK launch. This needs an Expo dev build or native module (the `mobile/android` folder exists). **UNVERIFIED** package choice. | **SDK token endpoint missing** (backend). Native SDK integration. |
| `wallets` (`:50-57`), Home wallets and balance | **none** | Keep the mock or "unavailable" state. Later: a typed wallet DTO with a `scale`. | **Missing endpoint** (backend). |
| `notifications`, Home bell | `GET /api/notifications` (§13). Real on `feat/mobile-notifications`. | Done: feed screen, bell badge, device-side read state | Option A limits (§13) |
| Home "Recent activity" (`mobile/src/screens/Home.tsx:266`) | `GET /api/transactions?currency=` (§6) | New `history` service and types. Call per selected currency. Use cursor paging until `hasMore` is false, tolerating empty pages. Use scale-aware formatting. Render `REVERSED`. | Formance must run locally. `pageSize` errors give 500. Pending payouts are not shown. Ordering is UNVERIFIED. |
| Send (`Home.tsx:27`) | `POST /api/transfers/internal` (§5) | New transfer service. Generate a UUID key per intent and persist it until a final outcome. Build a recipient-UUID input (paste or QR). Handle the §5 outcome table, with an "uncertain" UI that checks history by reference. | Flag off by default. **Recipient is UUID only** (no lookup). **Insufficient funds = 503 "uncertain"**. Error bodies have no message, so the two 503s cannot be told apart. No outcome endpoint. |
| Receive (`Home.tsx:28`) | none. The own `userId` is available from the onboarding and `kyc/start` responses. | Show and share your own `userId` (QR) | There is no `GET` for your own `userId`/profile. You must cache it from a response or call `kyc/start`. |
| Convert (`Home.tsx:29`) | **none** | Keep "coming soon" | No FX endpoint |
| Add money (`Home.tsx:26`) | **none.** `POST /api/backoffice/synthetic-credits` is a `COMPLIANCE_OFFICER` dev tool, not for the app. | Keep "coming soon" | No top-up endpoint |
| Profile / account status (`domain.ts:3`, `Profile.account`) | **none** | Keep deriving it. Do not assume `APPROVED` ⇒ `ACTIVE`. | No account-status endpoint |

**Can become real now:**
- `auth`, `onboarding` and `kyc` are already real. `kyc` is safe only with backend MOCK mode and needs the `RESUBMISSION_REQUIRED` fix.
- New, not in the registry: **history** (real), and **send** (real when the flag is on, with caveats).
- `wallets`, `notifications` and `kycCapture` **stay mocked or unavailable**.

### 8.2 Contract mismatches with the current frontend

| # | Frontend | Backend | Impact |
|---|---|---|---|
| 1 | `BackendKycStatus` lacks `RESUBMISSION_REQUIRED` (`mobile/src/types/domain.ts:1`). `knownStatuses` (`mobile/src/services/real.ts:129-135`) makes any other value throw `kycError` (`:139`). | `KycStatus.java:9` | A resubmission user is stuck on an error. Map it to the existing `MORE_INFO_REQUIRED` / `moreInfo` route (`domain.ts:2`, `navigation/state.ts:16-17`). |
| 2 | `real.ts:144` sets `account = ACTIVE` iff `APPROVED` | An approved account can be `SUSPENDED` (`SumsubDecisionService.java:174-185`). Account status is not exposed. | Wrong routing after a re-approval. A backend fix (status endpoint) is needed. |
| 3 | `REJECTED` routes to recapture (`mobile/src/screens/Kyc.tsx:233-237`: `more \|\| rejected → go('document')`) | `REJECTED` is final (`SumsubDecisionService.java:127-163`) | Offers a retry that cannot work |
| 4 | Mock KYC outcomes are `APPROVED/PENDING/REJECTED/MORE_INFO_REQUIRED` (`registry.ts:35-36`, `domain.ts` `DemoOutcome`) | The real value is `RESUBMISSION_REQUIRED`. `MORE_INFO_REQUIRED` does not exist server-side. | The mock does not exercise the real wire value |
| 5 | `api()` maps every non-OK to `saveError`, `authError` or `kycError` by path substring (`real.ts:97-104`), and supports only GET-without-body or POST-with-body (`:73-110`) | History needs a query string. Transfers need per-status handling (400/403/409/503). | Extend `api()` to return the status, or use typed errors per endpoint |
| 6 | `Wallet { currency; balanceMinor: number }` (`domain.ts:63-66`) | No wallet DTO exists | Undefined contract. Ask for `{currency, scale, availableMinor, pendingMinor}`, with amounts as strings or numbers decided explicitly. |
| 7 | `formatMinor` assumes 2 decimals (`Home.tsx:51-53`) | `TND` has scale 3 (`LedgerCurrency.java:26`). No scale is sent on the wire. | Wrong TND display. Hard-code the scale table or get it from the backend. |
| 8 | Mock wallets are `MUR, USD, EUR` (`mobile/src/mocks/wallets.ts:6-8`) | 6 currencies are provisioned (`LedgerCurrency.java:21-26`) | UI styles only 3 (`Home.tsx:31-37`) |
| 9 | `AppNotification {id,title,body}` (`domain.ts:67-71`) | No equivalent | Undefined contract |
| 10 | `Services` (`domain.ts:79-91`) has no history or transfer capabilities | §5, §6 | New interfaces are needed |
| 11 | `real.ts:120` comment: "No GET status endpoint exists" | Still true. `kyc.refresh` = `startKyc` (`real.ts` `kyc: { submit: startKyc, refresh: startKyc }`) is the correct poll today. | OK, but it relies on the side effect (audit §0.3) |
| 12 | `real.ts:214` sets `kyc: 'NOT_STARTED'` after onboarding | The backend also always returns `NOT_STARTED` (`PersonalOnboardingService.java:106-111`) | A returning approved user sees KYC intro until `kyc/start` returns `APPROVED`. Acceptable but odd. |
| 13 | `real.ts:117` requires `identity.email` | `/me` returns 500 if the email claim is missing (`AuthController.java:22-28`) | Shows `authError` instead of a clean message |
| 14 | `KycStartResponse.verificationMode: string` (`domain.ts:27-32`) | Always `"NON_PROD"` (`KycStartService.java:193`) | Do not use it for UX decisions |
| 15 | Default `apiUrl` is `http://localhost:8080` (`mobile/src/config.ts:42`) | The backend dev port is 8082, and Formance's default is 8080 (`application-dev.yml:2, 27`) | The app hits Formance, not the backend, unless ports are overridden |

---

## 9. Top blockers for mobile integration

1. **No wallet/balance endpoint.** The Home screen cannot show real money.
2. **No Sumsub SDK token endpoint, and no resubmission path.** Real KYC cannot be completed from the app. Only backend MOCK mode works.
3. **Transfer error contract:**
   - insufficient funds is reported as an uncertain 503 and permanently blocks the key;
   - the two 503 meanings cannot be told apart, because error bodies carry no message or code;
   - there is no transfer-status endpoint;
   - the recipient can be identified only by a raw UUID.
4. **No error body contract (Problem Details or error codes).** Several client errors surface as 500 (`pageSize`, a missing `email` claim, Formance down).
5. **No `GET` for the profile, account status or KYC status.** Polling KYC uses `POST /api/kyc/start`, which also carries the re-activation bug.
6. **Local environment:**
   - no Formance setup in the repo;
   - port collision between 8080 and 8082;
   - empty `neos-mobile` redirect URIs;
   - transfers flag off by default;
   - `.env.example` is missing most variables.
7. **History gaps:** no pending or held items, ordering UNVERIFIED, and pages can be empty while `hasMore` is true.

---

## 10. `GET /api/wallets` (balances) — implemented on `feat/customer-read-apis`

- **Preconditions:**
  - `CUSTOMER` and an onboarded profile. Otherwise `404`, in the same style as history (`ledger/CustomerWalletsCustomerNotFoundException.java`).
  - KYC and account status do **not** gate the read. A `SUSPENDED` or KYC-revoked customer still sees their balances as they are.
  - Formance must be reachable, otherwise `503` (see errors).
- **Request:** no body, no parameters.
- **Response 200** (`ledger/CustomerWallets.java`, `ledger/CustomerWallet.java`):
  ```json
  {"walletsStatus":"READY","asOf":"2026-11-02T00:28:19.310691300Z","wallets":[
   {"currency":"EUR","scale":2,"asset":"EUR/2","status":"ACTIVE","availableMinor":"7500","pendingMinor":"0"},
   {"currency":"USD","scale":2,"asset":"USD/2","status":"ACTIVE","availableMinor":"0","pendingMinor":"0"},
   {"currency":"GBP","scale":2,"asset":"GBP/2","status":"ACTIVE","availableMinor":"0","pendingMinor":"0"},
   {"currency":"AED","scale":2,"asset":"AED/2","status":"ACTIVE","availableMinor":"0","pendingMinor":"0"},
   {"currency":"MUR","scale":2,"asset":"MUR/2","status":"ACTIVE","availableMinor":"0","pendingMinor":"0"},
   {"currency":"TND","scale":3,"asset":"TND/3","status":"ACTIVE","availableMinor":"0","pendingMinor":"0"}]}
  ```
  (This is the sender's response from the smoke test, after a credit of 10000 and a transfer of 2500.)

  | Field | Meaning |
  |---|---|
  | `walletsStatus` | `READY`: all 6 wallets are provisioned. `PROVISIONING`: at least one is missing, and KYC is `APPROVED` or another wallet already exists (provisioning in flight or failed). `NOT_ELIGIBLE`: KYC is not `APPROVED` and no wallet exists (`ledger/CustomerWalletService.java:191-222`). |
  | `asOf` | Server time of the read (ISO-8601 UTC). |
  | `wallets` | **Always all 6 `LedgerCurrency` values**, in declaration order: EUR, USD, GBP, AED, MUR, TND. |
  | `currency` / `scale` / `asset` | From `LedgerCurrency`. The app should use `scale` and stop hard-coding TND = 3. |
  | `status` | The value of the `neos.wallet_status` metadata on `users:{id}:wallet:{CUR}:available` (today always `ACTIVE`), or `NOT_PROVISIONED` when the metadata is absent. |
  | `availableMinor` | Balance of `users:{id}:wallet:{CUR}:available` for the canonical asset. It is a **string of integer minor units**, and `"0"` when provisioned but never moved. |
  | `pendingMinor` | **Sum** of the balances of all `users:{id}:wallet:{CUR}:holds:*` accounts (payout reservations in flight), computed server-side. A string. |

  - **`NOT_PROVISIONED` ⇒ `availableMinor` and `pendingMinor` are `null`, never `"0"`.** They are serialized as `null`, not omitted.
  - Amounts are strings because Formance amounts are arbitrary-precision. A value above 2^63 is returned exactly (tested).
  - The app displays them and never adds, subtracts or derives them.
- **How it reads Formance** (2 queries, paginated at 100 rows with a safety cap of 20 pages; `ledger/CustomerWalletService.java:224-376`, `ledger/adapter/formance/FormanceClient.java:444, 555`):
  1. `GET /api/ledger/v2/{ledger}/accounts?pageSize=100`, body `{"$match":{"address":"users:{id}:wallet::available"}}`. This gives provisioning status from metadata.
  2. `GET /api/ledger/v2/{ledger}/volumes?pageSize=100`, body `{"$or":[{"$match":{"account":"users:{id}:wallet::available"}},{"$match":{"account":"users:{id}:wallet::holds:"}}]}`. This gives the balances.
  - The empty segment is a Formance v2 partial-address wildcard, and the segment count must match. Both queries were verified live on Formance **v2.4.14**: `$or`, partial addresses, cursor follow-up and big integers. Other users' accounts are excluded.
  - Rows for other assets (for example `EUR/3`) or unknown currencies are ignored.
- **Errors:**
  - `401` / `403`.
  - `404`: not onboarded.
  - **`503`**: Formance is unreachable, returns 5xx, times out, returns an invalid response, or returns a 4xx on the read (`ResponseStatusException`, reason "Wallet balances are temporarily unavailable", `CustomerWalletService.java:118-131`). There is never a partial body.
- **Caching:** the backend sets no header of its own. Spring Security's default `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` applies. Refetch on focus and after every money action.
- **Read-your-writes:** observed in the smoke test. `GET /api/wallets` right after a `200` transfer showed both sides updated.

## 11. `GET /api/profile` (own profile and statuses) — implemented on `feat/customer-read-apis`

- **Preconditions:** `CUSTOMER` and an onboarded profile, otherwise `404` (`onboarding/CustomerProfileNotFoundException.java`).
- **Read-only:** a read-only transaction, with no provider call and no status change. It is the side-effect-free replacement for polling with `POST /api/kyc/start`. `GET /api/auth/me` is unchanged.
- **Response 200** (`onboarding/api/CustomerProfileResponse.java`):
  ```json
  {"userId":"7bf8f8ad-1133-419d-9a13-9c3883690de0","email":"neos-sender@neos.local","phoneNumber":"+21620123456",
   "accountStatus":"ACTIVE","kycStatus":"APPROVED",
   "onboardedAt":"2026-11-02T00:28:01.654557Z","createdAt":"2026-11-02T00:28:01.529145Z"}
  ```

  | Field | Source |
  |---|---|
  | `userId` | NEOS user UUID |
  | `email` | Stored profile email (from the JWT at onboarding) |
  | `phoneNumber` | Self-declared at onboarding, **not verified**. `null` if not given. |
  | `accountStatus` | `PENDING_KYC` \| `ACTIVE` \| `SUSPENDED` \| `CLOSED`, as stored |
  | `kycStatus` | `NOT_STARTED` \| `PENDING` \| `IN_REVIEW` \| `APPROVED` \| `REJECTED` \| `RESUBMISSION_REQUIRED`, as stored (`kyc/KycStatusLookup.java`). Falls back to `NOT_STARTED` if no KYC row exists (`onboarding/CustomerProfileService.java:54-58`). |
  | `onboardedAt` | First `terms_acceptances.accepted_at` (`CustomerProfileService.java:73-84`) |
  | `createdAt` | `users.created_at` |

  - The two statuses are independent and reported exactly as stored. **`SUSPENDED` + `APPROVED` is a valid combination** (tested).
  - The app must route on both, for example by mapping `RESUBMISSION_REQUIRED` to "more info needed".
- **Errors:** `401` / `403`, and `404` when not onboarded.

## 12. `POST /api/recipients/lookup` (email → recipient for internal transfers) — implemented on `feat/customer-read-apis`

- **Gate:** the same flag as transfers, `neos.transfers.customer-api-enabled` (`ledger/api/RecipientLookupController.java:25-29`). When it is off the route does not exist.
  - On this branch the flag is `true` in **`application-dev.yml` only**; the base default stays `false` and prod is untouched.
- **Why POST:** so the email never appears in URLs, access logs or proxies.
- **Preconditions (caller):** `CUSTOMER`, onboarded, KYC `APPROVED` and account `ACTIVE`. This is the same `CustomerDebitAuthorization.requireEligible` check used for the transfer sender (`ledger/RecipientLookupService.java:73-84`).
- **Request** (`ledger/api/RecipientLookupRequest.java`):

  | Field | Type | Constraints |
  |---|---|---|
  | `email` | string | `@NotBlank`, `@Size(max=320)`. It is trimmed and matched **case-insensitively** (`identity/CustomerAccountLookup.java:53-71`). There is no `@Email` format check: a malformed value is simply "not available". |

  ```json
  {"email":"  NEOS-Receiver@neos.local "}
  ```
- **Email only.** Phone numbers are self-declared at onboarding and never verified, so they cannot identify a recipient. This is stated in the code comment at `ledger/RecipientLookupService.java:16-26`.
- **Response 200** (`ledger/api/RecipientLookupResponse.java`):
  ```json
  {"recipientUserId":"f61b6653-aded-40c8-97bc-f118a0ffeb9f","maskedEmail":"n***@neos.local"}
  ```
  - Masking keeps the first character of the local part and the full domain; nothing else of the local part is returned (`ledger/RecipientEmailMasking.java`).
  - There is **no `displayName`**: the backend stores no names. It can be added later as a non-breaking field.
  - **Use:** send `recipientUserId` as `destinationUserId` to `POST /api/transfers/internal` (§5). The transfer contract is unchanged.
- **Found** means: exactly one user has that email (case-insensitive), it is not the caller, and that user passes the same `requireEligible` check the transfer runs on its destination (KYC `APPROVED` + `ACTIVE`).
- **Uniform not-available response:** `404`, reason `"Recipient not available"`, for all of these cases:
  - unknown email;
  - the caller's own email;
  - an email that matches more than one row;
  - a recipient with KYC not `APPROVED`;
  - a recipient whose account is not `ACTIVE`.

  The body and status are identical, so the endpoint cannot reveal whether an email is registered or what state its account is in (tested: unknown / pending / suspended / rejected / self).
- **Rate limit (anti-enumeration):**
  - **10 lookups per minute per caller** (Keycloak subject), in a fixed window, checked **before** any DB access. Exceeding it gives **`429`**.
  - Configure with `NEOS_RECIPIENT_LOOKUP_MAX_PER_MINUTE` (`neos.recipients.lookup.max-requests-per-minute`).
  - **Limitation:** the counter is in memory, per backend instance, and resets on restart (`ledger/RecipientLookupRateLimiter.java:12-21`). Several instances multiply the effective limit, so a shared store is needed before scaling out.
  - There is no `Retry-After` header; the app should wait about a minute.
- **Errors:**

  | HTTP | Cause |
  |---|---|
  | 400 | Blank or over-long `email` |
  | 401 / 403 | No token, or no `CUSTOMER` role |
  | 403 | Caller not onboarded, or not KYC `APPROVED` + `ACTIVE` |
  | 404 | Recipient not available (uniform) |
  | 429 | Rate limit exceeded |
- **Caching:** the default Spring Security `no-store` headers apply.

### 12.1 Differences from `docs/api/PROPOSED_BALANCE_API.md`

| Proposal | Implemented | Why |
|---|---|---|
| `NOT_ELIGIBLE` returns `wallets: []` | `NOT_ELIGIBLE` returns **all 6 wallets** as `NOT_PROVISIONED` with `null` amounts | One response shape for every state. The app never special-cases an empty list. |
| `NOT_ELIGIBLE` decided from KYC alone | Formance is **always read**. A customer whose KYC is no longer `APPROVED` but who has wallets sees them as they are (`READY`/`PROVISIONING`). | A revoked customer can still hold money, and hiding it would be wrong. |
| Error body: Problem Details with `errorCode` (`application/problem+json`) | **Not implemented.** Errors use the existing mechanisms (`@ResponseStatus` exceptions, `ResponseStatusException`) and the default Spring Boot error body, so the client still switches on HTTP status only (§0.3). | Agreed constraint: no global `@ControllerAdvice` in this PR. |
| `503 LEDGER_UNAVAILABLE` + `Retry-After` | `503` without `errorCode` or `Retry-After` | Same constraint. |
| `Cache-Control: no-store` set by the endpoint | No endpoint-level header. Spring Security's default (`no-cache, no-store, max-age=0, must-revalidate`) already applies to every response. | Avoids a redundant header and matches the existing controllers. |
| Q3: all 6 currencies, or only those enabled per environment | All 6 | There is no per-environment currency configuration, and provisioning creates all 6. |
| Q4: per-currency `totalMinor` | Not added | Not requested. It could be added later without breaking anything. |
| Q5: migrate history and transfer amounts to strings | **Not changed.** Only `/api/wallets` uses strings; history and transfers still use JSON numbers. | Out of scope, and the transfer contract is frozen in this PR. |
| Q6: detect provisioning via metadata or existence | Metadata `neos.wallet_status` | Formance creates accounts implicitly on the first movement, so existence alone is not enough. No re-provisioning is triggered. |
| Q7: one query for all wallets and holds | Two queries (accounts for metadata, and volumes with `$or`) | `/volumes` does not return metadata. Both were verified live on v2.4.14. |
| §4: FX total endpoint | Not implemented | V3 (#37). |

---

## 13. `GET /api/notifications` (in-app notification feed) — implemented on `feat/notifications-feed-api`

- **Option A:** a read-only view of the customer's own `notification_outbox` rows (the email outbox). No migration and no change to the outbox, its entity or the sending logic. New files only: `notifications/api/NotificationFeedController.java`, `notifications/NotificationFeedService.java`, `NotificationFeedItem`, `NotificationFeedPage`, `NotificationFeedCursor`, and a separate read-only `notifications/repository/NotificationFeedRepository.java`.
- **Read state is not stored on the server.** The app keeps it on the device, per user. Option B (server-side read state) is deferred; see `docs/api/INTEGRATION_STATUS.md`, R7.
- **Preconditions:** `CUSTOMER` and an onboarded profile, otherwise `404` (`notifications/NotificationFeedCustomerNotFoundException.java`). KYC and account status are not checked.
- **Query parameters:**

  | Param | Type | Required | Notes |
  |---|---|---|---|
  | `pageSize` | int | no, default `20` | 1..100. Out of range or not a number gives **400** (unlike §6). Also applies with a `cursor`. |
  | `cursor` | string | no | Opaque, from `nextCursor`. A value this endpoint did not issue gives **400**. |

- **Response 200** (`notifications/NotificationFeedPage.java`, `NotificationFeedItem.java`):
  ```json
  {
    "items": [
      {"id":"3f0c…","type":"TRANSFER","direction":"DEBIT","currency":"TND","amountMinor":1500,
       "transactionId":118,"reference":"internal_transfer:ct_…","testCredit":false,
       "createdAt":"2026-11-05T15:10:02.120456Z"},
      {"id":"c541db50-ffe1-42a0-ad90-0e7bfff56a47","type":"INCOMING_PAYMENT","direction":"CREDIT",
       "currency":"EUR","amountMinor":2500,"transactionId":117,
       "reference":"incoming_payment:email-test-20261105-1","testCredit":true,
       "createdAt":"2026-11-05T03:17:35.467123Z"}
    ],
    "hasMore": true,
    "nextCursor": "djF8MjAyNi0xMS0wNVQwMzoxNzozNS40NjcxMjNafGM1NDFkYjUw…"
  }
  ```

  | Field | Meaning |
  |---|---|
  | `id` | Outbox row UUID. Created once and never changed (one row per transaction and user; the repair paths return the existing row), so the app can key its read state on it. |
  | `type`, `direction` | Same enums as §6: `INCOMING_PAYMENT`, `TRANSFER`, `FEE`, `PAYOUT`; `CREDIT` / `DEBIT`. A transfer gives one item to each side (sender `DEBIT`, recipient `CREDIT`). |
  | `currency` | ISO code string (`EUR` … `TND`). |
  | `amountMinor` | JSON number, always positive (as in §6). |
  | `transactionId`, `reference` | The Formance transaction, as in §6. Use them to open the history item. |
  | `testCredit` | `true` for a back-office synthetic credit (EMAIL-2). **Known limitation:** derived as `type == INCOMING_PAYMENT`, which is correct only while `SyntheticCreditService` is the sole producer (guarded by `NotificationFeedTestCreditArchitectureTest`). When real incoming payments exist, R6 is required. |
  | `createdAt` | When the notification was recorded (microsecond precision). It is **not** the ledger timestamp; use §6 `occurredAt` for that. |

- **Never exposed:** the recipient email, the notification key, the channel, the email delivery status, attempts, provider errors, the user ID and the Formance asset.
- **Content rules:**
  - Rows are listed **whatever their email status** (`PENDING`, `PROVIDER_ACCEPTED`, `DEAD_LETTER`, …): the money moved even if the email did not.
  - A row whose payload cannot be read, or whose type is unknown, is skipped with a WARN log that carries the row ID only. A page can therefore hold fewer than `pageSize` items.
  - No email address at the time of the money movement means no outbox row, so no notification (Option A limit).
  - A reversal is not reflected in the feed. The app opens the §6 item, which shows `REVERSED`.
- **Ordering:** `createdAt DESC`, then `id DESC` as the tie-breaker. Newest first is **verified** by tests.
- **Pagination:** keyset on `(createdAt, id)`, backed by the existing index `idx_notification_outbox_user`. The cursor is base64url of `v1|{createdAt}|{id}` and carries no user ID. Every query is filtered on the user taken from the JWT, so an edited cursor, or one copied from another user, only moves within the caller's own rows (tested). `hasMore` comes from reading one extra row, and the cursor follows the last row read, so skipped rows never stall paging.
- **Errors:** `401` / `403`, `404` when not onboarded, `400` for a bad `pageSize` or `cursor`, and `500` on a database failure.
- **Side effects:** none. A read-only transaction, so outbox rows are unchanged (tested).
- **Caching:** Spring Security's default `Cache-Control: no-store` (tested).

## 14. FX conversion (sandbox demo) — `integration` (PRs #73, #74) + fixes on `fix/fx-conversion-bugs`

Full audit with `file:line` references: `docs/api/FX_CONTRACT_AUDIT.md`. Summary for the app:

- **Gates.** Quotes: `NEOS_FX_QUOTES_ENABLED=true` and `NEOS_FX_SANDBOX_MODE=true`, else **503**. Conversions: Spring profile `demo` (never with `prod`) and `NEOS_FX_DEMO_ENABLED=true`, else the routes do not exist (**404**). Rates: `NEOS_FX_SANDBOX_RATES` (`EUR/USD=1.08,…`, one direction per entry). All three endpoints: `CUSTOMER`, KYC `APPROVED` and account `ACTIVE` (else **403**).
- **Error bodies** carry no reason and no code (Spring default); only the HTTP status is usable.

### 14.1 `POST /api/fx/quotes`

Request `{ "idempotencyKey": uuid, "sourceCurrency", "destinationCurrency", "sourceMinor": "10000" }`. Response:

```json
{ "quoteId": "…", "idempotencyKey": "…", "sourceCurrency": "EUR", "destinationCurrency": "USD",
  "sourceMinor": "10000", "destinationMinor": "10800", "sourceScale": 2, "destinationScale": 2,
  "exchangeRate": "1.08", "pricingMode": "SANDBOX_INDICATIVE", "executable": false,
  "asOf": "2026-11-06T01:59:30.1Z", "expiresAt": "2026-11-06T02:00:00.1Z" }
```

- 30 s lifetime, no fee, `destinationMinor` rounded **down**; amounts are digit strings, the rate a decimal string (display only).
- Same key + same parameters → the original quote (even expired); same key + other parameters → **409**. A new price needs a new key.
- **400** invalid input; **422** converted amount out of range; **503** disabled **or** pair without a rate (indistinguishable).
- The app labels every quote "Sandbox rate · indicative" and counts the lifetime (`expiresAt − asOf`) from the moment it **sent** the request, minus 1.5 s (`mobile/src/fx/clock.ts`), so it never sends an expired quote.

### 14.2 `POST /api/fx/demo/conversions`

Request `{ "quoteId": uuid, "idempotencyKey": uuid }`. Synchronous. Response `200` = `SUCCEEDED` only:

```json
{ "operationId": "…", "quoteId": "…", "idempotencyKey": "…", "status": "SUCCEEDED", "simulated": true,
  "sourceCurrency": "EUR", "destinationCurrency": "USD", "sourceMinor": "1000", "destinationMinor": "1080",
  "exchangeRate": "1.08", "reservationReference": "fx_reserve:…", "reservationTransactionId": 121,
  "settlementReference": "fx_convert:…", "settlementTransactionId": 122, "requiresReconciliation": false }
```

| Status | Meaning | App |
| --- | --- | --- |
| 200 | Converted (both wallets updated) | Success; wallets re-read |
| 400 | Invalid request | Rejected, nothing converted |
| 403 | Not eligible | Rejected, nothing converted |
| 404 | Demo off (no route) or unknown quote | "Not available in this environment", nothing converted |
| 409 | Expired quote, key reused, in progress or unresolved (ambiguous) | **Uncertain** |
| 422 | Insufficient funds (with fix BUG-FX-2; final `REJECTED`) | Rejected, nothing converted |
| 503 | Reservation or settlement outcome unknown | **Uncertain** |
| timeout / network / malformed 200 | — | **Uncertain** |

Uncertain: the app keeps the pending conversion (key + quote, persisted **before** the POST), sends nothing on its own, and offers **manual** "Check status" (14.3), **manual** "Try again" (same key, same quote) and, after one manual action, "Stop tracking". Never a new key.

Lifecycle: `CLAIMED → RESERVE_POSTING → RESERVED → SETTLE_POSTING → SUCCEEDED`; `RESERVE_UNKNOWN` / `SETTLE_UNKNOWN` on failure; `REJECTED` (fix BUG-FX-2, V17) for insufficient funds, which also releases the quote.

### 14.3 Status lookups

- `GET /api/fx/demo/conversions/{operationId}` — own operation, any status.
- `GET /api/fx/demo/conversions?idempotencyKey={uuid}` — **fix BUG-FX-3** (`fix/fx-conversion-bugs`): own operation for the key the app sent, any status; **404** = nothing claimed with that key. On `integration` without the fix this answers **405** (the app shows "status checks aren't available on this server").

### 14.4 History and notifications

- On `integration`: conversions are **not** in `GET /api/transactions` and produce no notification.
- With fix BUG-FX-1: one item per wallet, `type: "CONVERSION"`, `DEBIT` on the source wallet (reference `fx_reserve:{op}`), `CREDIT` on the destination wallet (`fx_convert:{op}`). The app parses `CONVERSION` ("Currency conversion"); receipts from Activity work through the generic history path.

---

## Appendix A — non-customer endpoints

### A.1 `POST /api/backoffice/synthetic-credits`
- Controller: `ledger/api/SyntheticCreditController.java:40-71`. Role `COMPLIANCE_OFFICER`.
- Gate: `neos.backoffice.synthetic-credit.enabled=true` (`:20-24`; default `false`, `application.yaml:50-52`).
- Request (`SyntheticCreditRequest.java:12-31`): `{userId: UUID, currency, amountMinor: long>0, operationId: string ≤128 ^[A-Za-z0-9_-]+$}`.
- Response (`SyntheticCreditResponse.java:7-15`): `{userId, currency, amountMinor, transactionId, reference}`.
- Use it in dev to fund wallets for testing history and transfers.

### A.2 `POST /api/webhooks/sumsub`
- Controller: `kyc/api/SumsubWebhookController.java:68-151`. Public (`SecurityConfig.java:38-41`), authenticated by HMAC instead.
- Gate: `neos.kyc.sumsub.webhook-enabled=true` (`:24-28`). It returns `503` unless `neos.kyc.mode=SUMSUB` (`:87-91`).
- Headers: `X-Payload-Digest`, `X-Payload-Digest-Alg`.
- Responses:
  - `401`: bad signature (`:94-102`).
  - `400`: malformed or unsupported event (`:108-120`).
  - `200`: applied, duplicate or stale (`:136`).
  - `503`: processing failed, so Sumsub retries (`:138-150`).

### A.3 `GET /actuator/health`
- Public (`SecurityConfig.java:32-34`).
- `info` is also exposed (`application.yaml:25-29`), but it requires a JWT.

### A.4 Services with no HTTP endpoint (for awareness)
- Transfer reconciliation: `CustomerTransferManualReconciliationService`, `CustomerTransferReconciliationInspectionService`.
- Payouts: `PayoutRequestAdmissionService`, `PayoutReservationExecutionService`, `PayoutFinalizationExecutionService`, `PayoutManualReconciliationService`, `PayoutNotificationRepairService`.
- Fees: `FeeCollectionService`.
- These are reachable only from tests (grep of `src/main` shows no callers outside `ledger/`).
