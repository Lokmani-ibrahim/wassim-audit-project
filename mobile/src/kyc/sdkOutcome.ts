// Maps the Sumsub SDK launch() result to what the app needs. Pure.
// Only `success`, `status` and `errorType` are read. `errorMsg` is never touched: Sumsub's own
// example shows it can contain the access token ("Unauthorized access with accessToken=...").
import type { KycSdkOutcome } from '../types/domain';

// Statuses after which the applicant has gone through the steps (the review is Sumsub's).
const SUBMITTED = new Set([
  'Pending',
  'TemporarilyDeclined',
  'FinallyRejected',
  'Approved',
  'ActionCompleted',
]);

export interface SdkLaunchResultLike {
  success?: unknown;
  status?: unknown;
  errorType?: unknown;
}

export function outcomeFromSdkResult(
  result: SdkLaunchResultLike | null | undefined,
): KycSdkOutcome {
  const status = typeof result?.status === 'string' ? result.status : 'Unknown';
  const errorType = typeof result?.errorType === 'string' ? result.errorType : null;
  if (result?.success !== true || status === 'Failed') return { kind: 'failed', errorType };
  if (SUBMITTED.has(status)) return { kind: 'submitted' };
  // Initial, Incomplete, Ready or anything new: the user can continue later.
  return { kind: 'incomplete' };
}
