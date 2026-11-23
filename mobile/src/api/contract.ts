// Wire types for the NEOS customer API, mirroring the backend exactly as documented in
// docs/api/MOBILE_API_CONTRACT.md (section numbers in each comment). Do not reshape here:
// domain mapping lives in profileMapping.ts and screen view-models.
//
// Amounts follow the wire, which is NOT uniform:
//   - GET /api/wallets (§10): strings of integer minor units, or null when not provisioned.
//   - history (§6) and transfers (§5): JSON numbers (Java long) of integer minor units.

// §0.2 — LedgerCurrency, declaration order as returned by GET /api/wallets.
export const CURRENCIES = ['EUR', 'USD', 'GBP', 'AED', 'MUR', 'TND'] as const;
export type Currency = (typeof CURRENCIES)[number];

// §4 table + §11 — every value the kyc_status column can hold.
export const BACKEND_KYC_STATUSES = [
  'NOT_STARTED',
  'PENDING',
  'IN_REVIEW',
  'APPROVED',
  'REJECTED',
  'RESUBMISSION_REQUIRED',
] as const;
export type BackendKycStatus = (typeof BACKEND_KYC_STATUSES)[number];

// §11 — users.account_status.
export const ACCOUNT_STATUSES = ['PENDING_KYC', 'ACTIVE', 'SUSPENDED', 'CLOSED'] as const;
export type AccountStatus = (typeof ACCOUNT_STATUSES)[number];

// §2 — GET /api/auth/me (subject-scoped eligibility added for onboarding preflight).
export interface AuthMeResponse {
  subject: string;
  username: string;
  email: string;
  onboardingEligibility?: 'READY' | 'EXISTING' | 'CONFLICT';
}

// §3 — POST /api/onboarding/personal.
export interface PersonalOnboardingRequest {
  phoneNumber?: string;
  installationId: string;
  platform: 'ANDROID' | 'IOS' | 'WEB';
  termsVersion: string;
}
// §3 — kycStatus is always the literal "NOT_STARTED"; never route on it.
export interface PersonalOnboardingResponse {
  userId: string;
  deviceId: string;
  kycStatus: BackendKycStatus;
  termsVersion: string;
}

// §4 — POST /api/kyc/start (body ignored; send {}). Has side effects: only to START KYC.
export interface KycStartResponse {
  userId: string;
  status: BackendKycStatus;
  provider: string;
  // Always "NON_PROD"; do not use for UX decisions.
  verificationMode: string;
}

// POST /api/kyc/sdk-token (PR #58; body ignored, send {}). A short-lived Sumsub SDK access token
// for the signed-in customer. SENSITIVE: keep it in memory only; never log, display or persist it.
// expiresInSeconds is the server-configured TTL: use it, never a hardcoded value.
// 200 for KYC PENDING / IN_REVIEW / RESUBMISSION_REQUIRED (eligible account). 409 for
// NOT_STARTED (call /api/kyc/start first) or APPROVED / REJECTED. 403 for SUSPENDED / CLOSED.
export interface KycSdkTokenResponse {
  token: string;
  expiresInSeconds: number;
}

// PRs #61, #64: stable error codes for /api/kyc/start and /api/kyc/sdk-token only
// (kyc/KycApiErrorCode.java). Error body: {"errorCode", "message", "status"}, no-store.
// Not coded (yet): Spring Security 401/403 and every non-KYC endpoint.
export const KYC_ERROR_CODES = [
  'ONBOARDING_REQUIRED', // 409
  'KYC_NOT_STARTED', // 409
  'KYC_ALREADY_APPROVED', // 409
  'KYC_FINAL_REJECTED', // 409
  'KYC_STATE_NOT_ELIGIBLE', // 409
  'KYC_APPLICANT_INVALID', // 409
  'ACCOUNT_RESTRICTED', // 403
  'KYC_MANUAL_REVIEW_REQUIRED', // 403
  'SUMSUB_UPSTREAM_ERROR', // 502
  'SUMSUB_UNAVAILABLE', // 503 (PR #64: Sumsub timeout, network error, 408/429/5xx)
] as const;
export type KycErrorCode = (typeof KYC_ERROR_CODES)[number];

// §11 — GET /api/profile. Read-only status source. 404 = not onboarded.
export interface CustomerProfileResponse {
  userId: string;
  email: string;
  phoneNumber: string | null;
  accountStatus: AccountStatus;
  kycStatus: BackendKycStatus;
  onboardedAt: string | null;
  createdAt: string;
}

// §10 — GET /api/wallets. 404 = not onboarded, 503 = ledger unavailable (never partial).
export type WalletsStatus = 'READY' | 'PROVISIONING' | 'NOT_ELIGIBLE';
// "ACTIVE" today; any other neos.wallet_status metadata value is passed through as-is.
export type WalletStatus = 'ACTIVE' | 'NOT_PROVISIONED' | (string & {});
export interface WalletDto {
  currency: Currency;
  scale: number;
  asset: string;
  status: WalletStatus;
  // null exactly when status is NOT_PROVISIONED; never "0" in that case.
  availableMinor: string | null;
  pendingMinor: string | null;
}
export interface WalletsResponse {
  walletsStatus: WalletsStatus;
  asOf: string;
  // Always all 6 currencies, in CURRENCIES order.
  wallets: WalletDto[];
}

// §12 — POST /api/recipients/lookup (dev flag). 404 uniform "not available", 429 rate limit.
export interface RecipientLookupRequest {
  email: string;
}
export interface RecipientLookupResponse {
  recipientUserId: string;
  maskedEmail: string;
}

// §6 — GET /api/transactions?currency=&pageSize=&cursor=
// CONVERSION: FX demo conversion, one item per wallet (source DEBIT, destination CREDIT); sent
// only once the backend maps fx_reserve / fx_convert (BUG-FX-1, branch fix/fx-conversion-bugs).
export type TransactionType = 'INCOMING_PAYMENT' | 'TRANSFER' | 'FEE' | 'PAYOUT' | 'CONVERSION';
export type TransactionDirection = 'CREDIT' | 'DEBIT';
export type TransactionStatus = 'COMPLETED' | 'REVERSED';
export interface TransactionItem {
  transactionId: number;
  type: TransactionType;
  direction: TransactionDirection;
  currency: Currency;
  // Always positive; the sign comes from direction.
  amountMinor: number;
  status: TransactionStatus;
  reference: string;
  occurredAt: string;
}
export interface TransactionPage {
  items: TransactionItem[];
  // Keep paging on hasMore, not on item count (pages can be empty with hasMore true).
  hasMore: boolean;
  nextCursor: string | null;
}

// §13 — GET /api/notifications?pageSize=&cursor= (newest first, read-only, Option A: backed by the
// email outbox). Read/unread state is device-side (src/notifications/).
export interface NotificationItem {
  // Outbox row id: created once and never changed. Device read state is keyed on it.
  id: string;
  type: TransactionType;
  direction: TransactionDirection;
  currency: Currency;
  // Always positive; the sign comes from direction.
  amountMinor: number;
  transactionId: number;
  reference: string;
  // Back-office synthetic credit (sandbox): never shown as a real payment (EMAIL-2).
  testCredit: boolean;
  // When the notification was recorded (not the ledger timestamp).
  createdAt: string;
}
export interface NotificationPage {
  items: NotificationItem[];
  hasMore: boolean;
  nextCursor: string | null;
}

// §5 — POST /api/transfers/internal (dev flag). No status field: 200 means posted.
export interface InternalTransferRequest {
  destinationUserId: string;
  currency: Currency;
  amountMinor: number;
  // One UUID per user intent, reused on retries.
  idempotencyKey: string;
}
export interface InternalTransferResponse {
  transactionId: number;
  reference: string;
}

// §14 — FX (sandbox, demo only). docs/api/FX_CONTRACT_AUDIT.md.
// POST /api/fx/quotes: indicative sandbox quote, 30 s. A new idempotencyKey per price request.
export interface FxQuoteRequest {
  idempotencyKey: string;
  sourceCurrency: Currency;
  destinationCurrency: Currency;
  // Integer minor units as a digit string.
  sourceMinor: string;
}
export interface FxQuote {
  quoteId: string;
  idempotencyKey: string;
  sourceCurrency: Currency;
  destinationCurrency: Currency;
  sourceMinor: string;
  destinationMinor: string;
  sourceScale: number;
  destinationScale: number;
  // Decimal string as sent (e.g. "1.08"); displayed, never used for arithmetic.
  exchangeRate: string;
  // Always "SANDBOX_INDICATIVE" today.
  pricingMode: string;
  // Always false today: a quote is never executable outside the demo endpoint.
  executable: boolean;
  asOf: string;
  expiresAt: string;
}
// POST /api/fx/demo/conversions (Spring profile `demo` + flag only; else 404).
export interface FxConversionRequest {
  quoteId: string;
  // One UUID per quote the user confirmed, reused on manual retries.
  idempotencyKey: string;
}
export const FX_CONVERSION_STATUSES = [
  'CLAIMED',
  'RESERVE_POSTING',
  'RESERVE_UNKNOWN',
  'RESERVED',
  'SETTLE_POSTING',
  'SETTLE_UNKNOWN',
  'SUCCEEDED',
  // Backend fix BUG-FX-2: insufficient funds, nothing reserved.
  'REJECTED',
] as const;
export type FxConversionStatus = (typeof FX_CONVERSION_STATUSES)[number];
export interface FxConversion {
  operationId: string;
  quoteId: string;
  idempotencyKey: string;
  status: FxConversionStatus;
  simulated: boolean;
  sourceCurrency: Currency;
  destinationCurrency: Currency;
  sourceMinor: string;
  destinationMinor: string;
  exchangeRate: string;
  reservationReference: string;
  reservationTransactionId: number | null;
  settlementReference: string;
  settlementTransactionId: number | null;
  requiresReconciliation: boolean;
}
