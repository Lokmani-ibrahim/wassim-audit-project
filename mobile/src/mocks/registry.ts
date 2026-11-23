import { MOCK_FEATURES, type MockFeature } from '../mockFeatures';
import { AUTH_MARKER, KYC_MARKER, ONBOARDING_MARKER } from './demo';
import { KYC_CAPTURE_MARKER } from './kycCapture';
import { NOTIFICATIONS_MARKER } from './notifications';
import { WALLETS_MARKER } from './wallets';

export interface MockRegistryEntry {
  feature: MockFeature;
  fakes: string;
  // Source file (relative to the app root) that contains the mock and its marker.
  module: string;
  marker: string;
  // Is there a real implementation that talks to a backend/provider today?
  realAvailable: boolean;
  replacedBy: string;
}
// Single source of truth for what is faked. Never imported by app code (only by tests and
// scripts), so it is not part of any bundle. Record<MockFeature, ...> makes it exhaustive.
const entries: Record<MockFeature, Omit<MockRegistryEntry, 'feature'>> = {
  auth: {
    fakes: 'Email/password sign-up and sign-in against a single local demo account',
    module: 'src/mocks/demo.ts',
    marker: AUTH_MARKER,
    realAvailable: true,
    replacedBy: 'Keycloak authorization code + PKCE; GET /api/auth/me',
  },
  onboarding: {
    fakes: 'Personal onboarding submission (always succeeds, stored locally)',
    module: 'src/mocks/demo.ts',
    marker: ONBOARDING_MARKER,
    realAvailable: true,
    replacedBy: 'POST /api/onboarding/personal',
  },
  kyc: {
    fakes:
      'KYC start/recheck with a scripted outcome (APPROVED/PENDING/REJECTED/MORE_INFO_REQUIRED)',
    module: 'src/mocks/demo.ts',
    marker: KYC_MARKER,
    realAvailable: true,
    replacedBy:
      'POST /api/kyc/start to start; GET /api/profile for read-only status. The decision comes from the Sumsub webhook (backend).',
  },
  kycCapture: {
    fakes: 'Sumsub SDK session (timer only; no camera, no token, nothing submitted)',
    module: 'src/mocks/kycCapture.ts',
    marker: KYC_CAPTURE_MARKER,
    realAvailable: true,
    replacedBy:
      'Sumsub MobileSDK (@sumsub/react-native-mobilesdk-module 1.46.1) with POST /api/kyc/sdk-token (src/services/sumsub.ts)',
  },
  wallets: {
    fakes: 'GET /api/wallets response: READY, 6 currencies, zero balances (string minor units)',
    module: 'src/mocks/wallets.ts',
    marker: WALLETS_MARKER,
    realAvailable: true,
    replacedBy: 'GET /api/wallets (contract §10; backend branch feat/customer-read-apis)',
  },
  notifications: {
    fakes:
      'GET /api/notifications response: 3 fixed items (one test credit, one transfer in, one out)',
    module: 'src/mocks/notifications.ts',
    marker: NOTIFICATIONS_MARKER,
    realAvailable: true,
    replacedBy:
      'GET /api/notifications (contract §13; backend branch feat/notifications-feed-api). Read state is device-side.',
  },
};
export const mockRegistry: readonly MockRegistryEntry[] = MOCK_FEATURES.map((feature) => ({
  feature,
  ...entries[feature],
}));
// Not mocks: honest "not implemented" UI (no fake data). Listed for release readiness only.
// Send, Activity and Notifications are real ("not available" / mocked only with mocked
// auth in dev builds). Sandbox-only actions show a placeholder in a production build
// (EXPO_PUBLIC_APP_ENV=production):
// - Convert is a real FX call to the backend's sandbox demo;
// - Add money is a sandbox PREVIEW: example deposit instructions (fake sponsor bank, an account
//   number that cannot pass an IBAN check) with the user's real reference; no service call, no
//   credit, no balance change, no transaction (src/topup/sandboxInstructions.ts). The real
//   top-up depends on the backend's Top-Up simulation (INTEGRATION_STATUS).
export const notImplementedActions = [] as const;
export const sandboxOnlyActions = [
  'Convert (FX demo: sandbox builds only)',
  'Add money (sandbox preview: example deposit instructions, no credit; real top-up pending)',
] as const;
