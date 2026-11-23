import type { MockSet } from '../mockFeatures';
import type { MockableServices, Services } from '../types/domain';
import { unavailableMoneyServices } from './unavailable';

// Runs `cleanup` on every sign-out, before the auth sign-out itself. A cleanup failure never
// blocks signing out.
export function withSignOutCleanup(services: Services, cleanup: () => void): Services {
  return {
    ...services,
    auth: {
      ...services.auth,
      async signOut() {
        try {
          cleanup();
        } catch {
          // Best effort: local data that could not be removed is overwritten per user anyway.
        }
        return services.auth.signOut();
      },
    },
  };
}

// Pure per-feature selection: each capability is the mock only if its feature is in the set,
// otherwise the real implementation. `account` follows `auth` (same session state).
// Transfers and history are never mocked: real, or "not available" when auth is mocked.
export function composeServices(
  mocks: MockSet,
  real: Services,
  mock: MockableServices | null,
): Services {
  if (!mock) return real;
  const money = mocks.has('auth') ? unavailableMoneyServices : real;
  return {
    auth: mocks.has('auth') ? mock.auth : real.auth,
    account: mocks.has('auth') ? mock.account : real.account,
    onboarding: mocks.has('onboarding') ? mock.onboarding : real.onboarding,
    kyc: mocks.has('kyc') ? mock.kyc : real.kyc,
    kycCapture: mocks.has('kycCapture') ? mock.kycCapture : real.kycCapture,
    wallets: mocks.has('wallets') ? mock.wallets : real.wallets,
    notifications: mocks.has('notifications') ? mock.notifications : real.notifications,
    transfers: money.transfers,
    transactions: money.transactions,
    fx: money.fx,
  };
}
