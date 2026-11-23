// Classifies a failed POST /api/transfers/internal (contract §5 outcome table) into:
//   - rejected: the backend definitively did NOT move money for this request;
//   - uncertain: money may or may not have moved. Never retried with a new key; the app checks
//     history for the deterministic reference and can retry with the SAME key only.
//
// Today the backend sends no error codes (§0.3) and reports a Formance rejection (e.g.
// insufficient funds) as 503 + UNKNOWN claim: that is "uncertain" here, by design. After the
// backend fix a definitive 4xx (e.g. 422), possibly with an `errorCode`, is "rejected".
import { ApiError } from '../api/client';
import { errorCodeFrom } from '../api/parse';
import { AppError } from '../services/errors';

export type RejectionReason =
  | 'invalidInput'
  | 'notAllowed'
  | 'recipientUnavailable'
  | 'insufficientFunds'
  | 'transfersUnavailable'
  | 'rateLimited'
  // The same key was sent with different parameters: an app bug. A new intent needs a new key.
  | 'keyConflict'
  | 'rejected';

export type FailureClass = { kind: 'rejected'; reason: RejectionReason } | { kind: 'uncertain' };

// Codes the app understands if/when the backend sends them. Unknown codes on a definitive 4xx
// fall back to a generic rejection.
const REJECTION_CODES: Record<string, RejectionReason> = {
  INSUFFICIENT_FUNDS: 'insufficientFunds',
  VALIDATION_FAILED: 'invalidInput',
  INVALID_REQUEST: 'invalidInput',
  SELF_TRANSFER: 'invalidInput',
  TRANSFER_NOT_ALLOWED: 'notAllowed',
  SENDER_NOT_ELIGIBLE: 'notAllowed',
  RECIPIENT_NOT_ELIGIBLE: 'recipientUnavailable',
  RECIPIENT_NOT_AVAILABLE: 'recipientUnavailable',
  IDEMPOTENCY_KEY_REUSED: 'keyConflict',
  IDEMPOTENCY_KEY_CONFLICT: 'keyConflict',
};
// A 409 is "uncertain" (attempt in progress / UNKNOWN claim) unless its code says it is the
// different-parameters conflict, the only 409 known to be definitive.
const DEFINITIVE_409_CODES = new Set(['IDEMPOTENCY_KEY_REUSED', 'IDEMPOTENCY_KEY_CONFLICT']);

export function classifyTransferFailure(error: unknown): FailureClass {
  // Network error, timeout, unreadable/malformed 200 body, anything unexpected: the request
  // may have been processed.
  if (!(error instanceof ApiError)) {
    // 401 after the refresh-retry: Spring Security rejects before the controller runs.
    if (error instanceof AppError && error.expired) return { kind: 'rejected', reason: 'rejected' };
    return { kind: 'uncertain' };
  }
  const { status } = error;
  const code = errorCodeFrom(error.bodyText);
  if (status >= 500) return { kind: 'uncertain' };
  if (status === 409) {
    if (code && DEFINITIVE_409_CODES.has(code)) return { kind: 'rejected', reason: 'keyConflict' };
    return { kind: 'uncertain' };
  }
  if (status >= 400) {
    if (code && REJECTION_CODES[code]) return { kind: 'rejected', reason: REJECTION_CODES[code] };
    if (code) return { kind: 'rejected', reason: 'rejected' };
    switch (status) {
      case 400:
        return { kind: 'rejected', reason: 'invalidInput' };
      case 403:
        return { kind: 'rejected', reason: 'notAllowed' };
      // Route absent: transfers feature flag off.
      case 404:
        return { kind: 'rejected', reason: 'transfersUnavailable' };
      case 429:
        return { kind: 'rejected', reason: 'rateLimited' };
      default:
        // 422 and any other 4xx: definitive rejection with no detail.
        return { kind: 'rejected', reason: 'rejected' };
    }
  }
  // 1xx/3xx surfacing as an error: unknown territory.
  return { kind: 'uncertain' };
}
