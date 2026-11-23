// FX conversion state machine (contract §14, sandbox demo). Pure: HTTP, storage, UUIDs and time
// are injected, so every path is unit-tested under node. Same guarantees as transfers/engine.ts:
//   - One idempotency key per confirmed quote, persisted BEFORE the POST together with the
//     quote; removed only on a final outcome (200 SUCCEEDED, a definitive rejection, or the
//     user stopping tracking after a manual check).
//   - The engine never sends anything on its own: no automatic resend, no automatic check.
//     "Try again" resends the SAME key and quote; "Check status" only reads (lookup by key).
//   - A quote is never sent once its device-side deadline has passed (clock.ts): the user gets
//     a new price to confirm instead.
import type {
  Currency,
  FxConversion,
  FxConversionRequest,
  FxQuote,
  FxQuoteRequest,
} from '../api/contract';
import { ApiError } from '../api/client';
import type { KeyValueStore } from '../transfers/engine';
import { isExpired, type ReceivedQuote } from './clock';
import {
  classifyConversionFailure,
  classifyQuoteFailure,
  type ConversionRejection,
  type QuoteFailure,
} from './outcome';

export interface FxIntent {
  sourceCurrency: Currency;
  destinationCurrency: Currency;
  // Integer minor units, digit string (money.parseAmountInput).
  sourceMinor: string;
}

export interface PendingConversion {
  version: 1;
  userId: string;
  quoteId: string;
  idempotencyKey: string;
  sourceCurrency: Currency;
  destinationCurrency: Currency;
  sourceMinor: string;
  destinationMinor: string;
  exchangeRate: string;
  createdAt: string;
  // User-initiated status checks or retries that did not resolve the outcome.
  manualChecks: number;
}

export type CheckResult =
  // Not checked yet (straight after an uncertain POST).
  | 'notChecked'
  // The backend has the operation but it is not final (posting, unknown: requires review).
  | 'inProgress'
  // Lookup by key returned 404: nothing claimed, or the demo endpoint is gone. Not proof.
  | 'notFound'
  // This backend has no lookup by key (before fix BUG-FX-3): use "Try again" (same key).
  | 'checkUnavailable'
  // Network or server error while checking.
  | 'checkFailed';

export type FxOutcome =
  | { kind: 'succeeded'; conversion: FxConversion; pending: PendingConversion }
  | { kind: 'rejected'; reason: ConversionRejection; pending: PendingConversion }
  // Nothing was sent: the quote's deadline passed before submission.
  | { kind: 'expired' }
  | { kind: 'uncertain'; pending: PendingConversion; check: CheckResult };

export type QuoteResult =
  { kind: 'quote'; received: ReceivedQuote } | { kind: 'error'; reason: QuoteFailure };

export interface FxEngineDeps {
  quote(request: FxQuoteRequest): Promise<FxQuote>;
  convert(request: FxConversionRequest): Promise<FxConversion>;
  findByKey(idempotencyKey: string): Promise<FxConversion>;
  store: KeyValueStore;
  uuid(): string;
  now(): number;
}

export const fxPendingStorageKey = (userId: string) => `neos.fx.pending.${userId}`;

function isPendingConversion(value: unknown, userId: string): value is PendingConversion {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Partial<PendingConversion>;
  return (
    v.version === 1 &&
    v.userId === userId &&
    typeof v.quoteId === 'string' &&
    typeof v.idempotencyKey === 'string' &&
    typeof v.sourceCurrency === 'string' &&
    typeof v.destinationCurrency === 'string' &&
    typeof v.sourceMinor === 'string' &&
    /^\d+$/.test(v.sourceMinor) &&
    typeof v.destinationMinor === 'string' &&
    /^\d+$/.test(v.destinationMinor) &&
    typeof v.exchangeRate === 'string' &&
    typeof v.manualChecks === 'number'
  );
}

export async function readPendingConversion(
  store: KeyValueStore,
  userId: string,
): Promise<PendingConversion | null> {
  const value = await store.read<unknown>(fxPendingStorageKey(userId));
  return isPendingConversion(value, userId) ? value : null;
}

export function createFxEngine(userId: string, deps: FxEngineDeps) {
  const pendingKey = fxPendingStorageKey(userId);

  async function finish() {
    await deps.store.remove(pendingKey);
  }

  async function bump(pending: PendingConversion): Promise<PendingConversion> {
    const next = { ...pending, manualChecks: pending.manualChecks + 1 };
    await deps.store.write(pendingKey, next);
    return next;
  }

  // One POST. Never called by the engine itself: submit() (after confirmation) or retry().
  async function attempt(pending: PendingConversion, manual: boolean): Promise<FxOutcome> {
    const request: FxConversionRequest = {
      quoteId: pending.quoteId,
      idempotencyKey: pending.idempotencyKey,
    };
    try {
      const conversion = await deps.convert(request);
      if (
        conversion.status === 'SUCCEEDED' &&
        conversion.idempotencyKey === pending.idempotencyKey
      ) {
        await finish();
        return { kind: 'succeeded', conversion, pending };
      }
      // A 200 that is not a confirmed success of THIS request: treat as not final.
      const next = manual ? await bump(pending) : pending;
      return { kind: 'uncertain', pending: next, check: 'notChecked' };
    } catch (error) {
      const failure = classifyConversionFailure(error);
      if (failure.kind === 'rejected') {
        // Final: nothing was converted for this key. A new attempt is a new quote and key.
        await finish();
        return { kind: 'rejected', reason: failure.reason, pending };
      }
      const next = manual ? await bump(pending) : pending;
      return { kind: 'uncertain', pending: next, check: 'notChecked' };
    }
  }

  return {
    // A fresh indicative price. A new key every time: a new price is a new request.
    async requestQuote(intent: FxIntent): Promise<QuoteResult> {
      const request: FxQuoteRequest = {
        idempotencyKey: deps.uuid(),
        sourceCurrency: intent.sourceCurrency,
        destinationCurrency: intent.destinationCurrency,
        sourceMinor: intent.sourceMinor,
      };
      const sentAt = deps.now();
      try {
        const quote = await deps.quote(request);
        if (
          quote.sourceCurrency !== intent.sourceCurrency ||
          quote.destinationCurrency !== intent.destinationCurrency ||
          quote.sourceMinor !== intent.sourceMinor
        )
          return { kind: 'error', reason: 'invalidInput' };
        return { kind: 'quote', received: { quote, sentAt } };
      } catch (error) {
        return { kind: 'error', reason: classifyQuoteFailure(error) };
      }
    },

    // The user confirmed this quote (device authentication passed). Persists, then POSTs once.
    async submit(received: ReceivedQuote): Promise<FxOutcome> {
      if (isExpired(received, deps.now())) return { kind: 'expired' };
      const { quote } = received;
      const existing = await readPendingConversion(deps.store, userId);
      if (existing) throw new Error('A conversion is already being processed; resolve it first.');
      const pending: PendingConversion = {
        version: 1,
        userId,
        quoteId: quote.quoteId,
        idempotencyKey: deps.uuid(),
        sourceCurrency: quote.sourceCurrency,
        destinationCurrency: quote.destinationCurrency,
        sourceMinor: quote.sourceMinor,
        destinationMinor: quote.destinationMinor,
        exchangeRate: quote.exchangeRate,
        createdAt: new Date(deps.now()).toISOString(),
        manualChecks: 0,
      };
      // If this write fails nothing is sent (the error propagates; nothing moved).
      await deps.store.write(pendingKey, pending);
      return attempt(pending, false);
    },

    // "Try again" (manual only): SAME key and quote. Safe: a finished operation answers with its
    // original result; an unfinished one with 409 (still uncertain); an expired quote with 409.
    retry(pending: PendingConversion): Promise<FxOutcome> {
      return attempt(pending, true);
    },

    // "Check status" (manual only): read-only lookup by key.
    async check(pending: PendingConversion): Promise<FxOutcome> {
      try {
        const conversion = await deps.findByKey(pending.idempotencyKey);
        if (conversion.idempotencyKey !== pending.idempotencyKey)
          return { kind: 'uncertain', pending: await bump(pending), check: 'checkFailed' };
        if (conversion.status === 'SUCCEEDED') {
          await finish();
          return { kind: 'succeeded', conversion, pending };
        }
        if (conversion.status === 'REJECTED') {
          await finish();
          return { kind: 'rejected', reason: 'insufficientFunds', pending };
        }
        return { kind: 'uncertain', pending: await bump(pending), check: 'inProgress' };
      } catch (error) {
        const check: CheckResult =
          error instanceof ApiError
            ? error.status === 404
              ? 'notFound'
              : error.status >= 500
                ? 'checkFailed'
                : 'checkUnavailable'
            : 'checkFailed';
        return { kind: 'uncertain', pending: await bump(pending), check };
      }
    },

    resume(): Promise<PendingConversion | null> {
      return readPendingConversion(deps.store, userId);
    },

    // Only after at least one manual check or retry. The conversion may still have run.
    async stopTracking(pending: PendingConversion): Promise<void> {
      if (pending.manualChecks < 1)
        throw new Error('stopTracking() requires at least one manual check');
      await finish();
    },
  };
}

export type FxEngine = ReturnType<typeof createFxEngine>;
