# Backoffice customer directory (Package B)

The production backoffice exposes a read-only customer directory to a dedicated `COMPLIANCE_OFFICER`. A valid staff JWT must still pass Package A's issuer, audience, authorized-party and role-separation checks. `ADMIN` can use the shell but receives 403 for list and detail. The frontend hides the directory from ADMIN and denies a direct `/customers` or `/customers/{userId}` route before requesting data. Backend authorization remains authoritative.

## HTTP contract

`GET /api/backoffice/customers` accepts at most one value for each of these optional query parameters:

| Parameter | Meaning |
| --- | --- |
| `search` | Exact UUID `userId`, or exact email after trimming and case folding. No prefix, substring or wildcard search. A nonempty value must be a canonical UUID or have an email-shaped `local@domain` form; maximum 320 characters. |
| `accountStatus` | Exact enum: `PENDING_KYC`, `ACTIVE`, `SUSPENDED`, `CLOSED`. |
| `kycStatus` | Exact enum: `NOT_STARTED`, `PENDING`, `IN_REVIEW`, `APPROVED`, `REJECTED`, `RESUBMISSION_REQUIRED`; `MISSING` selects customers with no KYC record. Omission includes all states, including missing. |
| `pageSize` | Integer 1–100, default 25. Changing it invalidates an existing cursor. |
| `cursor` | Opaque, URL-safe continuation from the preceding response. It binds the normalized search, both filters and page size. A malformed cursor or one reused with different values returns 400. |

Unknown or repeated parameters and invalid enum, size, search or cursor values return 400. The response is:

```json
{
  "items": [
    {
      "userId": "00000000-0000-0000-0000-000000000001",
      "email": "invented@review.invalid",
      "accountStatus": "ACTIVE",
      "kycStatus": null,
      "createdAt": "2026-11-09T10:00:00Z"
    }
  ],
  "nextCursor": null
}
```

Each item has exactly those five fields. `kycStatus: null` means no KYC row. An exact search with no match returns an empty `items` array. `GET /api/backoffice/customers/{userId}` returns the same five-field item, or 404 when the identity user does not exist. Both successful endpoints use `Cache-Control: no-store`. Missing or invalid JWTs return 401; valid unauthorized accounts return 403. Database access failure returns 503 with `{"error":"CUSTOMER_DIRECTORY_UNAVAILABLE"}` and no database detail. No customer write endpoint is added.

## Ordering and pagination

Rows are ordered by `userId` ascending, a unique immutable key. The cursor resumes strictly after the last checked ID, so a fixed dataset pages without repeats or omissions. It is a logical keyset cursor, not a historical database snapshot: inserts, deletes or status changes between requests can change subsequent pages. Start again without a cursor for a fresh traversal.

Identity owns the bounded user projection. KYC owns a single bulk status lookup for the candidate IDs. No module reads the other's table. Without a KYC filter, one request checks up to `pageSize` identities plus one look-ahead row. With a KYC filter, one request checks at most 1,000 identity candidates plus one look-ahead row, and performs one bulk KYC lookup of at most 1,000 IDs. A selective KYC filter can therefore yield an empty or short page with a non-null `nextCursor`; follow it until null. This keeps memory and SQL calls bounded and avoids N+1 reads, at the cost of additional pages for rare statuses. The frontend offers Next page in that case.

Migration V18 adds `(account_status, id)` for selective status scans and `lower(email)` for exact case-insensitive email lookup. The users primary key supports unfiltered keyset order; the existing unique KYC `user_id` index supports the bulk lookup. No cross-module SQL join or exception is required.

## UI and scope

The connected `/customers` page supports exact search, both filters and page navigation. `/customers/{userId}` shows the five fields. Changing search or filters resets the cursor. Requests from an old filter or route are ignored after cleanup; the customer view unmounts on logout, subject change or loss of `CUSTOMER_READ`. Malformed responses fail closed into a retry state. The production bundle does not import the fixture demo.

There are no customer edits, account restrictions, KYC decisions, financial records, exports, dashboard aggregates, notes, assignments or staff-management actions in Package B.
