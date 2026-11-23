# Proposal: Customer Wallet Balance API

- **Status:** a **proposal** for the backend developer. The design decisions are his.
- **Purpose:** unblock the D1 dashboard (#22) and the app side of #19 C2, "Balance displays as zero in the app".
- **Constraints it keeps:**
  - Formance is the source of truth.
  - `LedgerCurrency` defines currencies and scale.
  - The account taxonomy `users:{id}:wallet:{CUR}:available` and `…:holds:{operationId}` stays as is.
  - Amounts are integer minor units.
  - **The client never computes a balance.**
- **Code references:** relative to `src/main/java/com/fintech/`.

---

## 1. Endpoint

```
GET /api/wallets
Authorization: Bearer <JWT>
```

### Role and preconditions
- Role: `CUSTOMER`, the same as `GET /api/transactions` (`ledger/api/TransactionHistoryController.java:33`).
- Resolve the user from `jwt.sub`, through `IdentityLookupService.findProfileByKeycloakSubject`, as history does (`ledger/CustomerTransactionHistoryService.java:72-79`).
- **Not onboarded:** `404 PROFILE_NOT_FOUND`. This is consistent with history (`ledger/TransactionHistoryCustomerNotFoundException.java`).
- **KYC not `APPROVED`:** wallets are created only on approval (`ledger/WalletProvisioningOnKycApproved.java:29-49`).
  - Proposed: `200` with `"walletsStatus": "NOT_ELIGIBLE"` and `wallets: []`.
  - Alternative: `403 KYC_NOT_APPROVED`. See open question Q1.
- **KYC approved, wallets not (yet) provisioned:** this covers both in-flight provisioning and a failure left in `event_publication`; see audit 2, §2.1.
  - Return `200` with `"walletsStatus": "PROVISIONING"`.
  - Each wallet that is not provisioned gets `"status": "NOT_PROVISIONED"`, and its amounts are `null`, **not** `"0"`.
  - Reason: `FormanceClient.getAccountBalance` currently returns `BigInteger.ZERO` for a missing account (`ledger/adapter/formance/FormanceClient.java:282-290`). Showing zero would hide the provisioning failure.
  - Detection options:
    - read the account metadata `neos.wallet_status`, set by `WalletProvisioningService.java:61-70`; or
    - check whether the account exists.
- **Account `SUSPENDED`:** reads are allowed and stay read-only (history already allows them). See Q2.

### Response 200
```json
{
  "walletsStatus": "READY",
  "asOf": "2026-11-03T09:15:02.412Z",
  "wallets": [
    { "currency": "EUR", "scale": 2, "asset": "EUR/2", "status": "ACTIVE",
      "availableMinor": "125000", "pendingMinor": "5000" },
    { "currency": "MUR", "scale": 2, "asset": "MUR/2", "status": "ACTIVE",
      "availableMinor": "0", "pendingMinor": "0" },
    { "currency": "TND", "scale": 3, "asset": "TND/3", "status": "ACTIVE",
      "availableMinor": "4500", "pendingMinor": "0" }
  ]
}
```

| Field | Meaning |
|---|---|
| `walletsStatus` | `READY` \| `PROVISIONING` \| `NOT_ELIGIBLE` |
| `asOf` | Server time of the read (ISO-8601 UTC) |
| `currency` / `scale` / `asset` | Straight from `LedgerCurrency` (`currencyCode()`, `scale()`, `formanceAsset()`) (`ledger/internal/taxonomy/LedgerCurrency.java:34-51`). With `scale` sent, the app does not hard-code TND = 3. |
| `status` | `ACTIVE` \| `NOT_PROVISIONED`. Later: `FROZEN` etc., if `neos.wallet_status` evolves. |
| `availableMinor` | Balance of `users:{id}:wallet:{CUR}:available` for asset `CUR/scale` |
| `pendingMinor` | **Sum of balances of all `users:{id}:wallet:{CUR}:holds:*`**, i.e. reserved for payouts in flight. Computed server-side. |
| Order | Order of `LedgerCurrency` declaration, or only the currencies enabled for the environment (Q3) |

**Display rule for the app:** show `availableMinor` as "Balance". Show `pendingMinor` as "Pending" only when it is non-zero. The app does no arithmetic on these values. If a combined figure is ever wanted, the server should add it as a field (Q4).

### Why amounts are strings
- **Formance amounts are arbitrary-precision.** The backend already reads balances as `BigInteger` (`ledger/adapter/formance/FormanceVolumeBalance.java`, `FormanceClient.java:203`).
- **A JS `number` is exact only up to 2^53−1.** Any larger value is silently rounded, with no error. A string forces the client to use an exact type (`BigInt`/decimal) or fail loudly.
- **Strings avoid narrowing in Java.** `long` caps at 2^63−1, so mapping `BigInteger` into `long` (as history does with `FormanceTransactionPosting.amount`) could overflow.
- **Trade-off:** history (`CustomerTransactionItem.amountMinor`) and transfers already use JSON numbers. Suggestion: also move those to strings **now**. No production client consumes them yet, so one rule applies across all money fields (Q5). If you prefer numbers everywhere for consistency, that is workable for realistic amounts. Please state it in the contract.

---

## 2. Implementation sketch (for reference only)

1. Resolve `userId`, then read the KYC status through the existing `kyc` public API (Spring Modulith boundary).
2. **One Formance read per user**, preferably:
   - Call `GET /api/ledger/v2/{ledger}/volumes` with an address filter that matches every `users:{id}:wallet:` account. This is the same endpoint `getAccountBalance` uses (`FormanceClient.java:239-260`).
   - Then group the results in Java by currency and by `available` vs `holds:*`, using `LedgerAccountAddressFactory` (`ledger/internal/taxonomy/LedgerAccountAddressFactory.java:19, 45`).
   - Prefix or segment filtering on addresses is **UNVERIFIED** in "our current Formance deployment" (see the comment at `FormanceClient.java:220-229`). The fallback is per-account reads for `available` plus a paged `holds:` query.
3. Never derive balances from transaction history.

---

## 3. Errors: proposed common body (RFC 9457 Problem Details)

Today there is no `@ControllerAdvice`, and the default error body carries no message or code (contract §0.3). Proposal: **one `@RestControllerAdvice` for all `/api/**` endpoints**, returning `application/problem+json`:

```json
{
  "type": "https://api.neos.example/problems/ledger-unavailable",
  "title": "Ledger temporarily unavailable",
  "status": 503,
  "detail": "Balances cannot be read right now. Try again shortly.",
  "instance": "/api/wallets",
  "errorCode": "LEDGER_UNAVAILABLE",
  "traceId": "7f3c9a…"
}
```

Error codes for this endpoint:

| HTTP | `errorCode` | When |
|---|---|---|
| 401 | `UNAUTHENTICATED` | Missing or invalid JWT. Needs a Spring Security `AuthenticationEntryPoint` that writes Problem Details. |
| 403 | `FORBIDDEN` | Missing `CUSTOMER` role. Needs a custom `AccessDeniedHandler`. |
| 404 | `PROFILE_NOT_FOUND` | Not onboarded |
| 503 | `LEDGER_UNAVAILABLE` (+ `Retry-After`) | `LedgerProviderUnavailableException` (Formance down, 5xx or timeout) |
| 500 | `INTERNAL_ERROR` | Anything else. `detail` stays generic. |

The same advice would fix the existing endpoints (contract §9). Suggested codes:

| Endpoint / case | HTTP | `errorCode` |
|---|---|---|
| History `pageSize` out of range | 400 | `INVALID_PAGE_SIZE` (today it is a 500) |
| Transfer insufficient funds | 422 | `INSUFFICIENT_FUNDS`. Needs mapping of the Formance 4xx **before** the claim is set to `UNKNOWN`. |
| Transfer pre-ledger authorization unavailable (claim released, retry with the same key is safe) | 503 | `TRANSFER_AUTHORIZATION_UNAVAILABLE` |
| Transfer ledger outcome uncertain | 503 | `TRANSFER_OUTCOME_UNKNOWN` |
| Transfer key reused with different parameters | 409 | `IDEMPOTENCY_KEY_REUSED` |
| Transfer with this key in progress or under reconciliation | 409 | `TRANSFER_IN_PROGRESS` |
| KYC start before onboarding | 409 | `ONBOARDING_REQUIRED` |
| `/api/auth/me` token without an email claim | 401 or 422 | `EMAIL_CLAIM_MISSING` (today it is a 500) |

---

## 4. Total balance in a main currency (D1 "total balance preview")

- A total across currencies **needs FX rates**. FX is #37, in V3 (sponsor-bank dependent).
- Proposal: **do not add it to `/api/wallets` now.** For the V1 shell, the app shows the total only as labelled preview data.
- If a total is needed later, use a **separate** endpoint, for example `GET /api/wallets/summary?displayCurrency=EUR`, returning:

  ```json
  {"displayCurrency":"EUR","scale":2,"estimatedTotalMinor":"131240","estimated":true,
   "rates":[{"from":"MUR","to":"EUR","rate":"0.0198","source":"…","asOf":"…"}]}
  ```

- It must be marked `estimated` and carry the rate source and time. It must never be computed on the device.

---

## 5. Caching and consistency

- **No server-side cache** of balances. Respond with `Cache-Control: no-store`, because this is per-user financial data.
- **Read-your-writes.** Transfers are synchronous: a `200` means Formance committed (`ledger/CustomerTransferIdempotencyService.java:183-219`). A `GET /api/wallets` issued after that response should reflect the change. That Formance volumes are immediately consistent after a commit is **UNVERIFIED**; please confirm for the deployed version.
- **App behaviour:**
  - Refetch on Home focus, on pull-to-refresh, and after any money action returns.
  - Never patch balances optimistically.
  - Show `asOf` when the data is older than about 1 minute, e.g. offline.
- **Uncertain transfers** (`503` / `409`): the app refetches balances and history and shows the transfer as "status uncertain". It infers nothing from the balance change.

---

## 6. Open questions

| # | Question |
|---|---|
| Q1 | For a user who is onboarded but not KYC-approved: `200 NOT_ELIGIBLE` with an empty list, or `403 KYC_NOT_APPROVED`? (The proposal prefers 200, which is simpler for the app.) |
| Q2 | Can `SUSPENDED` and `CLOSED` accounts read balances? (The proposal: yes, read-only, like history.) |
| Q3 | Return all 6 `LedgerCurrency` values, or only those enabled per environment? (`LedgerCurrency.java:15-17` says enablement is "a separate configuration concern".) Is there such a config yet? |
| Q4 | Should the server also return a per-currency `totalMinor` (available + pending), so the app never adds? |
| Q5 | Strings for all money fields, migrating history and transfers now while no client consumes them, or numbers everywhere? |
| Q6 | How should "not provisioned" be detected: via account metadata `neos.wallet_status`, or via account existence? Could the endpoint also trigger or flag re-provisioning when an incomplete `KycApprovedEvent` publication exists (`FailedEventPublications`)? |
| Q7 | Does the deployed Formance support address prefix or segment filters on `/volumes`, so all wallets and holds come from one call? |
| Q8 | Should `pendingMinor` later include other hold types, such as card authorizations? If so, should the name stay generic? |
