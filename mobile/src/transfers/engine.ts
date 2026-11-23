// Internal transfer state machine (contract §5). Pure: HTTP, history, storage, UUIDs and time
// are injected, so every outcome path is unit-tested under node.
//
// Guarantees:
//   - One idempotency key per user intent. prepare() (reaching Review) keeps the key while the
//     intent is unchanged; any changed field gives a new key. A retry ALWAYS reuses the key of
//     the attempt it retries; the engine never retries with a new key on its own.
//   - The pending transfer is persisted BEFORE the POST and removed only on a final outcome
//     (200, found in history, or a definitive rejection), so an app restart can resume checking.
//     A Review that was never confirmed persists nothing.
//   - Uncertain outcomes (5xx, 409 in progress, network, timeout, malformed 200) are resolved
//     only by finding the deterministic reference in GET /api/transactions. Absence is never
//     treated as failure.
import type {
  Currency,
  InternalTransferRequest,
  InternalTransferResponse,
  TransactionStatus,
} from '../api/contract';
import { findByReference, type ListTransactions } from '../history/paging';
import { compareMinor, minorToSafeNumber } from '../money';
import { classifyTransferFailure, type RejectionReason } from './outcome';
import { transferReference } from './reference';

export interface TransferIntent {
  recipientUserId: string;
  // From the lookup response; display only.
  maskedEmail: string;
  currency: Currency;
  // Integer minor units, digit string (see money.parseAmountInput).
  amountMinor: string;
}

export interface PendingTransfer extends TransferIntent {
  version: 1;
  senderUserId: string;
  idempotencyKey: string;
  reference: string;
  createdAt: string;
  // User-initiated "Check status" attempts that found nothing (or could not check).
  manualChecks: number;
}

export type CheckResult = 'notFound' | 'notFoundYet' | 'checkFailed';

export type TransferOutcome =
  | {
      kind: 'succeeded';
      transfer: PendingTransfer;
      transactionId: number;
      reference: string;
      // From history when resolved there; a fresh 200 is COMPLETED by definition.
      status: TransactionStatus;
      via: 'response' | 'history';
      // Device time at which the app learnt the outcome (the 200 has no timestamp, contract §5).
      confirmedAt: string;
      // Ledger time (history occurredAt), only when resolved from history.
      occurredAt?: string;
    }
  | { kind: 'rejected'; reason: RejectionReason; intent: TransferIntent }
  | { kind: 'uncertain'; transfer: PendingTransfer; check: CheckResult };

export interface KeyValueStore {
  read<T>(key: string): Promise<T | null>;
  write(key: string, value: unknown): Promise<void>;
  remove(key: string): Promise<void>;
}

export interface TransferEngineDeps {
  send(request: InternalTransferRequest): Promise<InternalTransferResponse>;
  list: ListTransactions;
  store: KeyValueStore;
  uuid(): string;
  now(): number;
}

interface AbandonedTransfer {
  recipientUserId: string;
  currency: Currency;
  amountMinor: string;
  at: number;
}

const ABANDONED_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const ABANDONED_MAX = 5;

export const pendingStorageKey = (senderUserId: string) => `neos.transfer.pending.${senderUserId}`;
export const abandonedStorageKey = (senderUserId: string) =>
  `neos.transfer.abandoned.${senderUserId}`;

export function sameIntent(
  a: Pick<TransferIntent, 'recipientUserId' | 'currency' | 'amountMinor'>,
  b: Pick<TransferIntent, 'recipientUserId' | 'currency' | 'amountMinor'>,
): boolean {
  return (
    a.recipientUserId === b.recipientUserId &&
    a.currency === b.currency &&
    compareMinor(a.amountMinor, b.amountMinor) === 0
  );
}

function isPendingTransfer(value: unknown, senderUserId: string): value is PendingTransfer {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Partial<PendingTransfer>;
  return (
    v.version === 1 &&
    v.senderUserId === senderUserId &&
    typeof v.idempotencyKey === 'string' &&
    typeof v.reference === 'string' &&
    typeof v.recipientUserId === 'string' &&
    typeof v.currency === 'string' &&
    typeof v.amountMinor === 'string' &&
    /^\d+$/.test(v.amountMinor) &&
    typeof v.manualChecks === 'number'
  );
}

// Pending transfer of this sender, if any (Home banner, Send resume).
export async function readPending(
  store: KeyValueStore,
  senderUserId: string,
): Promise<PendingTransfer | null> {
  const value = await store.read<unknown>(pendingStorageKey(senderUserId));
  return isPendingTransfer(value, senderUserId) ? value : null;
}

export function createTransferEngine(senderUserId: string, deps: TransferEngineDeps) {
  const pendingKey = pendingStorageKey(senderUserId);
  const abandonedKey = abandonedStorageKey(senderUserId);
  let draft: { intent: TransferIntent; key: string } | null = null;

  async function finish() {
    await deps.store.remove(pendingKey);
    draft = null;
  }

  async function check(transfer: PendingTransfer, manual: boolean): Promise<TransferOutcome> {
    let result: Awaited<ReturnType<typeof findByReference>> | null = null;
    try {
      result = await findByReference(deps.list, transfer.currency, transfer.reference);
    } catch {
      result = null;
    }
    if (result?.found) {
      await finish();
      return {
        kind: 'succeeded',
        transfer,
        transactionId: result.item.transactionId,
        reference: result.item.reference,
        status: result.item.status,
        via: 'history',
        confirmedAt: new Date(deps.now()).toISOString(),
        occurredAt: result.item.occurredAt,
      };
    }
    const next = manual ? { ...transfer, manualChecks: transfer.manualChecks + 1 } : transfer;
    if (manual) await deps.store.write(pendingKey, next);
    return {
      kind: 'uncertain',
      transfer: next,
      check: result === null ? 'checkFailed' : result.exhausted ? 'notFound' : 'notFoundYet',
    };
  }

  // Built outside any try: a local conversion error must never look like an uncertain send.
  function requestFor(transfer: PendingTransfer): InternalTransferRequest {
    return {
      destinationUserId: transfer.recipientUserId,
      currency: transfer.currency,
      amountMinor: minorToSafeNumber(transfer.amountMinor),
      idempotencyKey: transfer.idempotencyKey,
    };
  }

  async function attempt(transfer: PendingTransfer): Promise<TransferOutcome> {
    const request = requestFor(transfer);
    try {
      const response = await deps.send(request);
      await finish();
      return {
        kind: 'succeeded',
        transfer,
        transactionId: response.transactionId,
        reference: response.reference,
        status: 'COMPLETED',
        via: 'response',
        confirmedAt: new Date(deps.now()).toISOString(),
      };
    } catch (error) {
      const failure = classifyTransferFailure(error);
      if (failure.kind === 'rejected') {
        // Final: nothing moved for this key. A later attempt is a new intent with a new key
        // (also the fix for a key-reuse conflict, which is an app bug).
        await finish();
        return { kind: 'rejected', reason: failure.reason, intent: transfer };
      }
      draft = null;
      return check(transfer, false);
    }
  }

  return {
    // Reaching Review. Returns the idempotency key for this intent (reused while unchanged).
    prepare(intent: TransferIntent): string {
      if (!draft || !sameIntent(draft.intent, intent))
        draft = { intent: { ...intent }, key: deps.uuid() };
      else draft = { intent: { ...intent }, key: draft.key };
      return draft.key;
    },
    get draftKey(): string | null {
      return draft?.key ?? null;
    },
    // Confirmed (device auth passed). Persists, then POSTs once.
    async submit(): Promise<TransferOutcome> {
      if (!draft) throw new Error('prepare() must be called before submit()');
      const { intent, key } = draft;
      const transfer: PendingTransfer = {
        version: 1,
        senderUserId,
        recipientUserId: intent.recipientUserId,
        maskedEmail: intent.maskedEmail,
        currency: intent.currency,
        amountMinor: intent.amountMinor,
        idempotencyKey: key,
        reference: transferReference(senderUserId, key),
        createdAt: new Date(deps.now()).toISOString(),
        manualChecks: 0,
      };
      requestFor(transfer);
      // If this write fails the POST is not sent (the error propagates; nothing moved).
      await deps.store.write(pendingKey, transfer);
      return attempt(transfer);
    },
    // "Try again" on an uncertain transfer: SAME key and parameters. Worst case 409 → still
    // uncertain; a replayed 200 → success without a second posting.
    retry(transfer: PendingTransfer): Promise<TransferOutcome> {
      return attempt(transfer);
    },
    // "Check status" (manual = true) or an automatic re-check (resume).
    check,
    resume(): Promise<PendingTransfer | null> {
      return readPending(deps.store, senderUserId);
    },
    // Only after at least one manual check without a result. The transfer may still have been
    // posted: it is remembered so that the same recipient + amount triggers a warning.
    async stopTracking(transfer: PendingTransfer): Promise<void> {
      if (transfer.manualChecks < 1)
        throw new Error('stopTracking() requires at least one manual status check');
      const now = deps.now();
      const previous = (await deps.store.read<AbandonedTransfer[]>(abandonedKey)) ?? [];
      const kept = (Array.isArray(previous) ? previous : []).filter(
        (entry) => typeof entry?.at === 'number' && now - entry.at < ABANDONED_TTL_MS,
      );
      const entry: AbandonedTransfer = {
        recipientUserId: transfer.recipientUserId,
        currency: transfer.currency,
        amountMinor: transfer.amountMinor,
        at: now,
      };
      await deps.store.write(abandonedKey, [entry, ...kept].slice(0, ABANDONED_MAX));
      await finish();
    },
    // Same recipient + currency + amount as a transfer the user stopped tracking (recently).
    async matchesUnconfirmed(
      intent: Pick<TransferIntent, 'recipientUserId' | 'currency' | 'amountMinor'>,
    ): Promise<boolean> {
      const now = deps.now();
      const list = (await deps.store.read<AbandonedTransfer[]>(abandonedKey)) ?? [];
      return (Array.isArray(list) ? list : []).some(
        (entry) =>
          typeof entry?.at === 'number' &&
          now - entry.at < ABANDONED_TTL_MS &&
          typeof entry.amountMinor === 'string' &&
          /^\d+$/.test(entry.amountMinor) &&
          sameIntent(entry, intent),
      );
    },
  };
}

export type TransferEngine = ReturnType<typeof createTransferEngine>;
