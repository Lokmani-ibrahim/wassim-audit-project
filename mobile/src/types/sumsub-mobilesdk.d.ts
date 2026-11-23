// Minimal typings for @sumsub/react-native-mobilesdk-module 1.46.1 (the package ships none).
// Only the API NEOS uses. Taken from the package's index.js and the Sumsub React Native docs.
//
// Deliberately omitted:
// - `errorMsg` on the launch result: it can contain the access token (Sumsub's own example is
//   "Unauthorized access with accessToken=..."). Never read, log or display it; use errorType.
// - the `onLog` handler and withDebug(true) usage are not typed as part of our surface either:
//   SDK logs may carry sensitive data, so NEOS never registers onLog and always passes false.
declare module '@sumsub/react-native-mobilesdk-module' {
  export type SumsubSdkStatus =
    | 'Ready'
    | 'Failed'
    | 'Initial'
    | 'Incomplete'
    | 'Pending'
    | 'TemporarilyDeclined'
    | 'FinallyRejected'
    | 'Approved'
    | 'ActionCompleted';

  export type SumsubSdkErrorType =
    | 'Unknown'
    | 'InvalidParameters'
    | 'Unauthorized'
    | 'InitialLoadingFailed'
    | 'ApplicantNotFound'
    | 'ApplicantMisconfigured'
    | 'InitializationError'
    | 'NetworkError'
    | 'UnexpectedError';

  // The SDK reports what happened on the device. It is NOT the KYC decision (the backend gets
  // that from the Sumsub webhook; the app reads it from GET /api/profile).
  export interface SumsubLaunchResult {
    success: boolean;
    status: SumsubSdkStatus | string;
    errorType?: SumsubSdkErrorType | string | null;
  }

  export interface SumsubStatusChangedEvent {
    prevStatus: SumsubSdkStatus | string;
    newStatus: SumsubSdkStatus | string;
  }

  export interface SumsubHandlers {
    onStatusChanged?: (event: SumsubStatusChangedEvent) => void;
    onEvent?: (event: { eventType: string; payload?: unknown }) => void;
  }

  export interface SumsubSdk {
    launch(): Promise<SumsubLaunchResult>;
    dismiss(): void;
  }

  export interface SumsubBuilder {
    withHandlers(handlers: SumsubHandlers): SumsubBuilder;
    withDebug(flag: false): SumsubBuilder;
    withLocale(locale: string): SumsubBuilder;
    withAnalyticsEnabled(flag: boolean): SumsubBuilder;
    withAutoCloseOnApprove(seconds: number): SumsubBuilder;
    build(): SumsubSdk;
  }

  const SNSMobileSDK: {
    // expirationHandler: called by the SDK when the token is invalid or expired; must resolve a
    // fresh token (NEOS: POST /api/kyc/sdk-token). Android waits up to 120 s for it.
    init(accessToken: string, expirationHandler: () => Promise<string>): SumsubBuilder;
    reset(): void;
  };
  export default SNSMobileSDK;
}
