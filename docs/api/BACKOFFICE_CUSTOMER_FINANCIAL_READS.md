# Backoffice customer financial reads

Package C adds read-only customer-scoped endpoints under the connected backoffice API. They do not change the mobile `/api/wallets` or `/api/transactions` contracts.

## Authorization and resource selection

Both endpoints require a valid staff JWT with the configured issuer, audience and `azp`, and a dedicated `COMPLIANCE_OFFICER` identity. `ADMIN` retains shell access but cannot use these reads. `CUSTOMER`, mixed customer/staff, combined admin/compliance, `OPERATIONS` and other roles are denied. The backend checks the requested `userId` against the identity-owned customer directory before accessing the ledger. Wallet and transaction queries use only ledger accounts derived from that verified customer ID; no wallet or transaction ID supplied by the caller selects a resource. Successful responses use `Cache-Control: no-store`.

## `GET /api/backoffice/customers/{userId}/wallets`

`userId` is a UUID. No query parameters or request body are accepted. The response contains only:

```json
{
  "retrievedAt": "2026-11-09T12:00:00Z",
  "wallets": [
    {"currency":"TND","scale":3,"status":"ACTIVE",
     "availableMinor":"1250","pendingMinor":"0"}
  ]
}
```

Only provisioned wallets are listed, in canonical currency order (`EUR`, `USD`, `GBP`, `AED`, `MUR`, `TND`). An empty array means the customer exists but has no provisioned wallets. `status` comes from the wallet metadata; available and pending balances come from the existing Formance volume queries. The pending balance is the sum of payout holds. Amounts are signed integer **strings in minor units**, never JSON floating-point numbers. The scale is 2 except TND, which is 3. A null amount means unavailable or unknown; it is never interpreted as confirmed zero. A successful provider read of a provisioned wallet with no movements yields the confirmed string `"0"`. `retrievedAt` is the server time after both reads finished, **not** a provider snapshot timestamp; different accounts may reflect different instants. No partial wallet body is returned on a provider error.

## `GET /api/backoffice/customers/{userId}/transactions`

Required `currency` is one of `EUR`, `USD`, `GBP`, `AED`, `MUR`, `TND`. Optional `pageSize` defaults to 20 and must be an integer from 1 through 100. Optional `cursor` is the exact opaque `nextCursor` from the preceding response for the same customer, currency and page size. Unknown or repeated parameters are rejected. There are no other filters.

```json
{
  "items": [
    {"transactionId":"4711","type":"TRANSFER","direction":"DEBIT",
     "currency":"TND","scale":3,"amountMinor":"1500",
     "status":"COMPLETED","occurredAt":"2026-11-09T12:00:00Z"}
  ],
  "hasMore": true,
  "nextCursor": "opaque-value"
}
```

`transactionId` and `amountMinor` are decimal strings so JavaScript cannot lose integer precision. `amountMinor` preserves the existing customer-history magnitude; `direction` indicates credit or debit. `type`, `direction`, `status` and `occurredAt` reuse the mobile history ledger mapping, including settled-only payouts and Formance transaction timestamps. There is no invented pending status. Each request reads the selected customer's available account for exactly one currency. Payout settlement lookups for a page are batched and bounded. Transactions without recognized NEOS business metadata are omitted, so a page can have zero visible items while `hasMore` is true.

Ordering follows Formance's default transaction order; this API does not impose an additional sort. The continuation cursor wraps the provider cursor with a server-side signature and binds customer ID, currency and page size. It becomes invalid after a server restart or when a request reaches another application instance because the signing key is local and ephemeral. A deployment must keep a pagination sequence on the same instance; shared cursor signing is a follow-up for multi-instance operation. The provider supplies continuation, not a snapshot: concurrent ledger writes can change the observed sequence, so duplicate-free, gap-free traversal is only asserted for a fixed dataset. `nextCursor` is null when `hasMore` is false.

## Errors

Missing/invalid authentication yields 401; an authenticated identity outside the dedicated compliance boundary yields 403. Malformed paths, unsupported parameters, invalid page size or invalid, modified, expired or mismatched cursors yield 400 where route parsing reaches the controller. A valid but absent customer yields 404 `CUSTOMER_NOT_FOUND`. Database or ledger unavailability, invalid provider responses and bounded-query exhaustion yield a sanitized 503 `CUSTOMER_FINANCIAL_READ_UNAVAILABLE`. A customer with no wallets or visible history receives 200 with an empty array, distinct from 404 and 503.
