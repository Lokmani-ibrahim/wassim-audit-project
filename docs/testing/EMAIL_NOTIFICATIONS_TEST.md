# Email Notifications Test — Microsoft Graph, end to end (local)

- **Date:** 2026-11-05, ~04:10–04:30 local (UTC+1)
- **Branch:** `integration` @ `ffa61b7` (clean). No application code or config changed. `.env` not edited.
- **Scope:** one synthetic credit → outbox → dispatcher → Microsoft Graph `sendMail` → one real mailbox.
- **Redaction:** no password, token, `.env` value or full stored address of a test user appears here. Tokens are `Bearer ***`. The only full address shown is the approved test recipient.

## 1. Summary

| Item | Result |
|---|---|
| Real recipients | **Exactly one:** the approved address `mohamedkhalil.tanfous@esprit.tn` |
| Outbox backlog before the test | 40 sendable rows (38 `PENDING`, 2 `PROCESSING`), all to `@neos.local` → neutralized to `DEAD_LETTER` (approved), then restored at the end (approved) |
| Credit | `POST /api/backoffice/synthetic-credits` EUR 25.00 → `neos-receiver`, **200**, `transactionId 117` |
| Outbox lifecycle | `PENDING` → `PROCESSING` (attempt 1) → **`PROVIDER_ACCEPTED`**, no error, ~2.1 s after the row was created |
| Inbox | **Delivered to the inbox** (not spam). From "NEOS Notifications" `<no-reply@neos-world.com>`, received Thu 05/11/2026 04:17 local |
| Content | Matches the template: "Neos / You received EUR 25.00. / Reference: incoming_payment:email-test-20261105-1 / Transaction ID: 117". Shown as plain-looking text. |
| Idempotency | **PASS.** Replay with the same `operationId` → same tx 117, no new outbox row, no second send |
| Problems | EMAIL-1 … EMAIL-6 (§8). The main one: earlier runs already sent 27 emails to fake `neos.local` addresses, and 40 more stay queued to them |
| Cleanup | All DB changes reverted. Backend left **stopped** (your choice). Temp files deleted. |

> `PROVIDER_ACCEPTED` means Microsoft Graph returned `202 Accepted` for `sendMail`. It does **not** prove delivery. Delivery was confirmed separately: you saw the email in your inbox.

## 2. How notifications work (code review, step 1)

### 2.1 Events that enqueue an email

All of them go through `FinancialTransactionNotificationService.enqueueSafely(...)`, always with the event type `TRANSACTION_COMPLETED` and the channel `EMAIL`:

| Type / direction | Subject | Enqueued by |
|---|---|---|
| `INCOMING_PAYMENT` / CREDIT | "Money received" | `ledger/SyntheticCreditService.java:132` (synthetic credit) |
| `TRANSFER` / DEBIT (sender) and CREDIT (receiver) | "Transfer sent" / "Transfer received" | `ledger/InternalTransferService.java:147,164`; repair paths: `CustomerTransferManualReconciliationService.java:314`, `CustomerTransferNotificationRepairService.java:293` |
| `PAYOUT` / DEBIT | "Payout wallet debit recorded" | `PayoutSettlementService.java:129`, `PayoutFinalizationExecutionService.java:356`, `PayoutManualReconciliationService.java:433`, `PayoutNotificationRepairService.java:222` |
| `FEE` / DEBIT | "Fee charged" | `FeeCollectionService.java:98` |

The repair services are `@ConditionalOnProperty` (off by default) and `@PreAuthorize`. Nothing schedules them, so they did not run here.

### 2.2 Recipient

- The recipient is the **stored `users.email`** (`identity/UserContactLookup.findEmail`), **not** the JWT. If it's missing or blank, the notification is skipped with a WARN log (`FinancialTransactionNotificationService.java:100-113`).
- Nothing syncs `users.email` from the token (`UserEntity.updateEmail` has no callers).
- The address is **copied into `notification_outbox.recipient` when the row is created**. Later changes to `users.email` don't affect queued rows. That's why the test email swap had to happen before the credit.

### 2.3 Outbox, keys and statuses

- Key: `TRANSACTION_COMPLETED:{transactionId}:{userId}:EMAIL`. It's `UNIQUE` in the DB (`uq_notification_outbox_key`), and `enqueueTransactionEmail` returns the existing row when the key exists (`NotificationOutboxService.java`).
- Statuses (`NotificationOutboxStatus`): `PENDING`, `PROCESSING`, `RETRY`, `PROVIDER_ACCEPTED`, `DEAD_LETTER`, plus `PUBLISHED`, which is never used (EMAIL-6). There's no CHECK constraint on `status`.
- Claim: `lockReadyBatch` takes `PENDING`/`RETRY`, and `PROCESSING` rows whose 5-min lease has expired (`next_attempt_at <= now`), with `FOR UPDATE SKIP LOCKED` (`NotificationOutboxRepository.java:36-55`). Each claim does `attempt_count++`.
- Scheduler: `NotificationOutboxScheduler`, a single thread `notification-dispatcher-1`, fixed delay of 5 s, batch of 25. It only exists when `neos.notifications.dispatcher.enabled=true`.
- Email port: `MicrosoftGraphEmailAdapter`, which only exists when `neos.notifications.microsoft-graph.enabled=true`. Without it, `runOnce` returns 0 and claims nothing.

### 2.4 Retry behaviour (`NotificationDeliveryWorker`)

| Outcome | Result |
|---|---|
| Graph `202` | `PROVIDER_ACCEPTED`, `published_at` set |
| `429` | `RETRY` after `Retry-After` (default 60 s) |
| `5xx`, network error, token endpoint `429/5xx`, unexpected non-202 success | `RETRY` after 30 s (or `Retry-After`) |
| `401` | token refreshed once, then sent again; if it still fails → non-retryable |
| `400/403/404`, missing tenant/client/secret | `DEAD_LETTER` right away |
| Bad channel, event type or payload JSON | `DEAD_LETTER` |
| Unexpected `RuntimeException` | `RETRY` 30 s |
| Any retryable failure on attempt ≥ 5 (`MAX_ATTEMPTS`) | `DEAD_LETTER` |

### 2.5 Template (`NotificationDeliveryWorker.java:229-345`)

```html
<html><body>
  <h2>Neos</h2>
  <p>You received EUR 25.00.</p>
  <p>Reference: <strong>{reference, HTML-escaped}</strong></p>
  <p>Transaction ID: {ledger transactionId}</p>
</body></html>
```

The amount is `currency + " " + BigDecimal(amountMinor, scale from the asset "EUR/2").toPlainString()`. Graph request: `POST /users/{MAIL_FROM}/sendMail`, `contentType: HTML`, one `toRecipients`, `saveToSentItems: true`.

## 3. Setup (step 2)

| Check | Result |
|---|---|
| Disk C: | 10.1 GB free (tight) |
| RAM | 6.4 GB free of 31.7 GB |
| Containers | `neos-backend-db` (:5434, healthy), `neos-keycloak` (:8081), `neos-keycloak-db`, `neos-formance-gateway` (:8083), `neos-formance-ledger`, `neos-formance-db`: all up ~9 h |
| Backend | not running beforehand (8080 free) |

### 3.1 Outbox census before the test (`fintech_dev`, read-only)

| Status | Rows | Recipient domain | Sendable? |
|---|---|---|---|
| `PENDING` | 38 | `neos.local` | yes (created 2026-11-02 → 11-04) |
| `PROCESSING` | 2 | `neos.local` | yes: leases expired, `attempt_count = 2`, no error |
| `DEAD_LETTER` | 4 | `neos.local` | no |
| `PROVIDER_ACCEPTED` | 27 | `neos.local` | no: **already sent to Graph on 2026-11-02** (EMAIL-1) |

Neutralization (approved): the 40 sendable rows were backed up (id, status, attempt_count, next_attempt_at, last_error, updated_at; no recipients) to a scratchpad CSV. Then, in one guarded transaction (abort unless exactly 40): `status='DEAD_LETTER'`, `last_error='EMAIL_TEST_20261105_NEUTRALIZED'`, `updated_at=now()`. Re-check: **0 sendable rows.**

### 3.2 Test recipient (approved)

`neos-receiver` = Keycloak subject `c71a66d3-…`, `users.id f61b6653-aded-40c8-97bc-f118a0ffeb9f`, original email `neo***@neos.local` (24 chars, saved to a scratchpad file, not printed). Set to the test address in one guarded update (1 row). No other user had the test address (unique constraint `users_email_key`).

## 4. Backend start (step 4)

- The script `scripts/run-backend-local.ps1` loads `.env` with `Set-Item env:...`. That **overwrites** any env var set beforehand, and `.env` itself defines `MICROSOFT_GRAPH_ENABLED`, `NOTIFICATION_DISPATCHER_ENABLED`, `NEOS_SYNTHETIC_CREDIT_ENABLED` and `NEOS_CUSTOMER_TRANSFER_API_ENABLED`.
- So the overrides went in as **process-only `SPRING_APPLICATION_JSON`**, which outranks OS env vars and the `.env` config import:
  ```json
  {"neos":{"notifications":{"microsoft-graph":{"enabled":true},"dispatcher":{"enabled":true}},
           "backoffice":{"synthetic-credit":{"enabled":true}},"transfers":{"customer-api-enabled":true}}}
  ```
  It was set in a throwaway launcher that then invoked the unchanged script.
- "Started FintechBackendApplication in 29.717 seconds" (PID 19044, Java 21.0.6), health `200` after ~30 s.
- Checks, since the startup log doesn't show these flags (EMAIL-5):
  - `POST /api/backoffice/synthetic-credits` without a token → **401** (the controller exists; it would be 404 if the flag were off);
  - `jcmd Thread.print` shows `notification-dispatcher-1`.

## 5. Synthetic credit and lifecycle (steps 5–6)

Token: `neos-compliance` through the `neos-mobile` Authorization Code + PKCE flow, scripted over HTTP against Keycloak (no realm change). TTL 300 s, `COMPLIANCE_OFFICER` present. The password was read from a scratchpad file and never printed.

```http
POST /api/backoffice/synthetic-credits
Authorization: Bearer ***
Content-Type: application/json

{"userId":"f61b6653-aded-40c8-97bc-f118a0ffeb9f","currency":"EUR","amountMinor":2500,"operationId":"email-test-20261105-1"}
```
→ **200** `{"userId":"f61b6653-…","currency":"EUR","amountMinor":2500,"transactionId":117,"reference":"incoming_payment:email-test-20261105-1"}`

| Time (UTC) | Event |
|---|---|
| 03:17:32.6 | API call sent |
| 03:17:35.27 | `occurredAt` in the payload (ledger posted) |
| 03:17:35.467 | outbox row `c541db50-ffe1-42a0-ad90-0e7bfff56a47` created (`PENDING`) |
| 03:17:36.318 | claimed → `PROCESSING`, `attempt_count = 1` |
| 03:17:37.541 | **`PROVIDER_ACCEPTED`**, `published_at` set, `last_error` null |
| 03:17:37.567 | log: `Notification dispatcher processed 1 item(s)` (the only dispatcher line; no ERROR/WARN in the whole run) |

Row: key `TRANSACTION_COMPLETED:117:f61b6653-…:EMAIL`, recipient = the test address (checked by an equality query), payload `{"type":"INCOMING_PAYMENT","direction":"CREDIT","currency":"EUR","asset":"EUR/2","amountMinor":2500,"reference":"incoming_payment:email-test-20261105-1",...}`.

## 6. Inbox result and content review

| Item | Result |
|---|---|
| Delivered? | **Yes, inbox** (not spam/junk) |
| Sender | **NEOS Notifications `<no-reply@neos-world.com>`** (as reported by the recipient). The display name comes from the Exchange mailbox, not the code: the adapter sends no `from` field, so Graph uses the `/users/{sender}` mailbox identity. |
| Arrival time | **Thu 05/11/2026 04:17 local** (as reported), the same minute Graph accepted it (04:17:37). End-to-end latency is under 1 min: about 5 s from the API call to Graph accepting it, plus Exchange delivery. |
| Subject | "Money received" (from the code; not contradicted) |
| Body as seen | "Neos / You received EUR 25.00. / Reference: incoming_payment:email-test-20261105-1 / Transaction ID: 117" |
| Formatting | "normal text, no special things": the bare `<h2>`/`<p>` HTML renders like plain text; no branding, no footer |

Content review:

- **Personal data:** minimal. No name, no balance, no IBAN or account number, no user ID. Good.
- **Internal identifiers exposed:** the `Reference` shows the raw ledger reference `incoming_payment:{operationId}`. For a synthetic credit, the operationId is chosen by back-office staff and is internal, so it reaches the customer unchanged. The `Transaction ID` is the internal Formance ledger ID. Low risk, but these mean nothing to a customer (EMAIL-3).
- **Amount/currency:** `EUR 25.00` is correct (ISO code, 2 decimals from `EUR/2`). `toPlainString()` adds no thousands separator (EUR 1,000,000.00 → `EUR 1000000.00`), and there's no locale formatting (EMAIL-3).
- **Sandbox wording:** **none.** A synthetic or sandbox credit produces the same "Money received / You received EUR 25.00." as a real incoming payment, with no "test / sandbox / not real money" marker (EMAIL-2).
- **Missing elements:** no transaction date/time (the payload has `occurredAt`, but it isn't rendered), no greeting, no support or "wasn't you?" line, no legal footer, no plain-text alternative part.

## 7. Idempotency (step 7)

| Check | Result |
|---|---|
| Rows for tx 117 | **1** (`notification_key` UNIQUE) |
| Replay (approved): the same request with the same `operationId` and a fresh token | **200**, same `transactionId 117` (the ledger is idempotent on the reference) |
| Outbox after the replay (+12 s) | still 72 rows. The tx 117 row is unchanged: `PROVIDER_ACCEPTED`, attempt 1, same `updated_at 03:17:37.542` |
| Dispatcher | still exactly **one** "processed" log line. No second send. You reported a single email. |
| Can it be resent? | No. `PROVIDER_ACCEPTED` is terminal and isn't in the claim query. |

Caveat (EMAIL-4): delivery is **at least once**. If the process dies between Graph's `202` and the `PROVIDER_ACCEPTED` commit, the expired `PROCESSING` lease is claimed again and the email is sent a second time. The 2 `PROCESSING` rows with `attempt_count = 2` in the backlog show that interrupted claims do happen locally.

## 8. Issues (backend developer's format)

### EMAIL-1 — [Notifications/Dev] Outbox sends to non-routable `@neos.local` test addresses; 27 already sent, 40 still queued (finding)

- **Where:** `notification_outbox` in the local `fintech_dev`; `FinancialTransactionNotificationService.enqueueSafely` / `NotificationDeliveryWorker`.
- **Actual:** all 71 rows that existed before the test target `@neos.local`. 27 are `PROVIDER_ACCEPTED`, dated **2026-11-02**: real Graph `sendMail` calls from the production sender mailbox to a fake domain, which likely produced bounces (NDRs) in that mailbox. 38 `PENDING` + 2 `PROCESSING` rows, restored at the end as requested, will be sent the next time someone runs locally with both flags on.
- **Expected:** in non-production, never call Graph for test domains. Either a recipient allowlist (e.g. `NEOS_NOTIFICATION_ALLOWED_RECIPIENTS` / domains), a blocklist of `*.local`, `*.test`, `*.example`, or a "redirect all mail to X" dev setting. Dead-letter blocked rows with a clear reason.
- **Reproduction:** run any money movement for a seeded user (`neos-*@neos.local`) with `MICROSOFT_GRAPH_ENABLED=true` and `NOTIFICATION_DISPATCHER_ENABLED=true`.
- **Severity:** Medium (sender reputation / bounce noise, and the risk of mailing real people from seeded data). **Action now:** decide whether to dead-letter or delete the 40 queued rows before anyone enables Graph locally again.

### EMAIL-2 — [Notifications/Content] Synthetic (sandbox) credits produce a "Money received" email with no test marker (finding)

- **Endpoint:** `POST /api/backoffice/synthetic-credits` → `SyntheticCreditService.java:132` uses `INCOMING_PAYMENT`.
- **Actual:** subject "Money received", body "You received EUR 25.00.", exactly the same as a real incoming payment.
- **Expected:** a separate notification type, or a "Sandbox / test credit – not real money" line, or no customer email at all for synthetic credits, depending on the product decision.
- **Severity:** Medium for any shared or staging environment with real mailboxes.

### EMAIL-3 — [Notifications/Template] Bare template: internal refs exposed, no date, no locale amount formatting (finding)

- **Code:** `NotificationDeliveryWorker.java:252-345`.
- **Actual:**
  - `Reference: incoming_payment:{operationId}` (a raw internal ledger reference) and `Transaction ID: {formance id}`;
  - `occurredAt` isn't shown;
  - `toPlainString()` → `EUR 1000000.00`;
  - no plain-text part, no branding, no footer or support line.
- **Expected:**
  - a customer-facing reference, or none;
  - the transaction date and time;
  - a locale-formatted amount (e.g. `€25.00` / `EUR 1,000,000.00`);
  - a `multipart/alternative` text part;
  - a support/security line.
- **Severity:** Low (UX, minor internal-information exposure).

### EMAIL-4 — [Notifications/Delivery] At-least-once delivery can duplicate an email after a crash between Graph 202 and the DB commit (observation)

- **Code:** `NotificationDeliveryWorker.process` (send, then `claimService.markDelivered` in a separate transaction); lease re-claim in `NotificationOutboxRepository.java:36-55`.
- **Evidence:** 2 backlog rows were in `PROCESSING` with `attempt_count = 2`, leases expired, no `last_error`: interrupted claims, outcome unknown.
- **Expected:** accept and document this, or reduce the window (e.g. a stable `internetMessageId`/custom header per `notification_key`, so duplicates can be detected).
- **Severity:** Low.

### EMAIL-5 — [Notifications/Ops] No startup log for whether Graph/dispatcher are enabled; the success log has no item IDs (finding)

- **Actual:** the startup log doesn't say whether the email adapter or the dispatcher is active. Verifying it took a 401-vs-404 probe and a thread dump. The only per-run log is `Notification dispatcher processed 1 item(s)`, with no outbox ID or status (`NotificationOutboxScheduler.java:87`). A missing Graph bean is silent: `runOnce` returns 0 and rows stay `PENDING`.
- **Expected:** an INFO line at startup ("Microsoft Graph email: enabled, sender domain X; dispatcher: enabled, batch 25, delay 5 s"), and DEBUG/INFO per item with the outbox ID and final status (no address).
- **Severity:** Low.

### EMAIL-6 — [Notifications] `NotificationOutboxStatus.PUBLISHED` is never used (cleanup)

- No code sets or reads `PUBLISHED` (only the enum declaration). Remove it, or document it.
- **Severity:** Trivial.

Related, already known: PR55 **ISSUE-4**. `scripts/run-backend-local.ps1` overwrites pre-set env vars from `.env`, so process-only overrides need `SPRING_APPLICATION_JSON` (or `-Dspring-boot.run.arguments`).

## 9. Environment and state changes, and cleanup (step 8)

| Change | Reverted? |
|---|---|
| 40 outbox rows → `DEAD_LETTER` + marker | **Restored** from the CSV in one guarded transaction (exactly 40, marker count 0 afterwards). The census matches §3.1 again. |
| `neos-receiver` `users.email` (+ `updated_at`) | **Restored** to the original value and `updated_at 2026-11-02 00:28:03.325615+00` (1 row; 0 users now carry the test address) |
| Synthetic credit tx 117 (+EUR 25.00 on `neos-receiver`) | **Not reversible** (append-only Formance ledger). It stays as test data. |
| New outbox row `c541db50-…` (tx 117) | **Kept** as evidence (your choice). Terminal `PROVIDER_ACCEPTED`, not claimable. |
| Backend with the overrides (PID 19044 + Maven parent 22064) | **Stopped**. Port 8080 free, no backend JVM left. **Left stopped** (your choice). |
| Temp files: creds, token, email backup, outbox CSV, PKCE helper, launcher, backend log; container `/tmp/outbox_backup.csv` | **Deleted**. The scratchpad is empty. |
| `.env`, code, config | untouched |

Final outbox state: 72 rows. `PROVIDER_ACCEPTED` 28 (27 old + tx 117), `DEAD_LETTER` 4, and **40 sendable rows (38 `PENDING`, 2 `PROCESSING`) to `@neos.local`, restored as requested.** None of them came from this test. They'll be sent if Graph and the dispatcher are both enabled locally again (EMAIL-1).

Handling note: the `neos-compliance` test password was typed into this chat session's answer field, so it's in the session transcript. It never appeared in a command output, a log or this report. Rotate it if it's used anywhere else.
