// App-side domain model. Wire shapes live in src/api/contract.ts; real.ts maps between them.
import type {
  AccountStatus,
  AuthMeResponse,
  Currency,
  FxConversion,
  FxConversionRequest,
  FxQuote,
  FxQuoteRequest,
  InternalTransferRequest,
  InternalTransferResponse,
  NotificationPage,
  RecipientLookupResponse,
  TransactionPage,
  WalletsResponse,
} from '../api/contract';

export type { AccountStatus } from '../api/contract';
// UI-level KYC state. The backend's RESUBMISSION_REQUIRED maps to MORE_INFO_REQUIRED (the
// existing "more info" route); IN_REVIEW is shown like PENDING. See api/profileMapping.ts.
export type KycStatus =
  'NOT_STARTED' | 'PENDING' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED' | 'MORE_INFO_REQUIRED';
// givenName: display only, from the (unverified) ID token `given_name` claim. Never trusted.
export type Identity = AuthMeResponse & { givenName?: string | null };
// Present once the customer is onboarded (from GET /api/profile).
export interface CustomerDetails {
  userId: string;
  email: string;
  phoneNumber: string | null;
  onboardedAt: string | null;
}
export interface Profile {
  identity: Identity;
  onboarded: boolean;
  kyc: KycStatus;
  account: AccountStatus;
  customer?: CustomerDetails;
}
// Gate routes (backend-state driven, see navigation/state.ts). Everything after `home` lives in
// the authenticated React Navigation tree (navigation/AuthenticatedApp.tsx).
export type Route =
  | 'welcome'
  | 'createAccount'
  | 'signIn'
  | 'intro'
  | 'details'
  | 'review'
  | 'onboardingConflict'
  | 'onboardingUnavailable'
  | 'kycIntro'
  | 'pending'
  | 'rejected'
  | 'moreInfo'
  | 'approved'
  | 'ready'
  | 'home'
  | 'restricted';
export type DemoOutcome = 'APPROVED' | 'PENDING' | 'REJECTED' | 'MORE_INFO_REQUIRED';
// Which identity-provider form to open: registration (`create`) or login (`signIn`).
export interface AuthIntentInput {
  intent: 'create' | 'signIn';
}
// Mocked auth only: the in-app demo form values.
export interface DemoAuthInput extends AuthIntentInput {
  email: string;
  password: string;
  confirmPassword?: string;
}
// What happened on the device during a Sumsub SDK session. NEVER the KYC decision: that comes
// from the backend (Sumsub webhook) and is read from GET /api/profile.
// - submitted: the user went through the steps (SDK status Pending/Approved/... on close).
// - incomplete: the user closed the SDK early (Initial/Incomplete): offer "Continue".
// - failed: the SDK could not run; errorType only (errorMsg can contain the token: never read).
export type KycSdkOutcome =
  { kind: 'submitted' | 'incomplete' } | { kind: 'failed'; errorType: string | null };
// Developer-only handles into the mock layer (KYC outcome, forced failures, expiry, reset).
export interface DemoControls {
  setOutcome(value: DemoOutcome): void;
  failNext(): void;
  reset(): Promise<void>;
  expire(): Promise<void>;
}
export interface Services {
  auth: {
    // Real auth reads only `intent`; mocked auth also validates the demo form fields.
    signIn(input?: AuthIntentInput | DemoAuthInput): Promise<Profile>;
    restore(): Promise<Profile | null>;
    // Whether a session is stored on the device (no network call). The app unlock screen is
    // shown on cold start only when there is a session to protect.
    hasStoredSession(): Promise<boolean>;
    // true when browser SSO logout completed (or there was no session to end).
    signOut(): Promise<boolean>;
  };
  onboarding: { submit(phone: string): Promise<Profile> };
  // submit = start KYC (POST /api/kyc/start, side effects). refresh = read-only status re-read.
  kyc: { submit(): Promise<Profile>; refresh(): Promise<Profile> };
  // Read-only own profile/status (real: GET /api/profile). Updates the shared session.
  account: { get(): Promise<Profile> };
  // Identity capture by the Sumsub SDK: POST /api/kyc/sdk-token, then the SDK (renewing the token
  // on expiry). Token failures throw KycFlowError (kyc/errors.ts).
  kycCapture: { verify(): Promise<KycSdkOutcome> };
  // Wire shape as-is (GET /api/wallets): amounts are strings, see money/ for display.
  wallets: { list(): Promise<WalletsResponse> };
  // GET /api/notifications (contract §13), newest first, cursor paging. Read state is local.
  notifications: { list(cursor: string | null, pageSize: number): Promise<NotificationPage> };
  // Real only (never mocked: no fake money movement). With mocked auth there is no real token,
  // so these are "not available" (services/compose.ts).
  transfers: {
    // POST /api/recipients/lookup. Errors are mapped to user messages (AppError).
    lookup(email: string): Promise<RecipientLookupResponse>;
    // POST /api/transfers/internal. Errors are NOT mapped: the raw ApiError / AppError is
    // classified by transfers/outcome.ts (rejected vs uncertain).
    send(request: InternalTransferRequest): Promise<InternalTransferResponse>;
  };
  // GET /api/transactions (one currency, cursor paging).
  transactions: {
    list(currency: Currency, cursor: string | null, pageSize: number): Promise<TransactionPage>;
  };
  // §14 FX (sandbox demo). Real only, never mocked. Errors are NOT mapped: fx/outcome.ts
  // classifies them (unavailable, rejected, uncertain).
  fx: {
    // POST /api/fx/quotes.
    quote(request: FxQuoteRequest): Promise<FxQuote>;
    // POST /api/fx/demo/conversions. Moves money: never called automatically.
    convert(request: FxConversionRequest): Promise<FxConversion>;
    // GET /api/fx/demo/conversions?idempotencyKey= (backend fix BUG-FX-3). Read-only.
    findByKey(idempotencyKey: string): Promise<FxConversion>;
  };
}
// What the dev mock bundle can provide (money movement and history are never mocked).
export type MockableServices = Omit<Services, 'transfers' | 'transactions' | 'fx'>;
