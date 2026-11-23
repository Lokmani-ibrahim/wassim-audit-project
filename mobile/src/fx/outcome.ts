// Classifies failed FX calls (contract §14, docs/api/FX_CONTRACT_AUDIT.md). The backend sends no
// error codes yet (BUG-FX-4), so only the HTTP status is used, conservatively:
//   - quote: no side effect, so every failure is just "no price" with a reason;
//   - convert: money may have moved unless the status proves otherwise. 409 and 5xx are
//     ambiguous on that endpoint (expired quote / in progress / requires review), so they are
//     "uncertain": never retried automatically, never with a new key.
import { ApiError } from '../api/client';
import { AppError } from '../services/errors';

export type QuoteFailure =
  // 503: FX disabled or this pair has no sandbox rate (indistinguishable today); 404: route absent.
  | 'unavailable'
  | 'invalidInput'
  | 'notAllowed'
  // 422: the converted amount is outside the supported range (e.g. rounds down to zero).
  | 'amountOutOfRange'
  | 'network';

export function classifyQuoteFailure(error: unknown): QuoteFailure {
  if (!(error instanceof ApiError)) {
    if (error instanceof AppError && error.expired) return 'notAllowed';
    return 'network';
  }
  switch (error.status) {
    case 400:
      return 'invalidInput';
    case 403:
      return 'notAllowed';
    case 404:
    case 503:
      return 'unavailable';
    case 422:
      return 'amountOutOfRange';
    default:
      return error.status >= 500 ? 'unavailable' : 'invalidInput';
  }
}

export type ConversionRejection =
  // 404: the demo endpoint is off (no route) or the quote is unknown. Nothing was executed.
  | 'unavailable'
  | 'invalidInput'
  | 'notAllowed'
  // 422 (backend fix BUG-FX-2): the ledger refused the reservation. Nothing was converted.
  | 'insufficientFunds'
  | 'rejected';

export type ConversionFailure =
  { kind: 'rejected'; reason: ConversionRejection } | { kind: 'uncertain' };

export function classifyConversionFailure(error: unknown): ConversionFailure {
  // Network error, timeout, malformed 200 body: the conversion may have run.
  if (!(error instanceof ApiError)) {
    // 401 after the refresh-retry: Spring Security rejects before the controller runs.
    if (error instanceof AppError && error.expired) return { kind: 'rejected', reason: 'rejected' };
    return { kind: 'uncertain' };
  }
  const { status } = error;
  if (status >= 500 || status === 409) return { kind: 'uncertain' };
  switch (status) {
    case 400:
      return { kind: 'rejected', reason: 'invalidInput' };
    case 403:
      return { kind: 'rejected', reason: 'notAllowed' };
    case 404:
      return { kind: 'rejected', reason: 'unavailable' };
    case 422:
      return { kind: 'rejected', reason: 'insufficientFunds' };
    default:
      return status >= 400 ? { kind: 'rejected', reason: 'rejected' } : { kind: 'uncertain' };
  }
}
