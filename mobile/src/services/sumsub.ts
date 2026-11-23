// Real identity capture: the Sumsub MobileSDK (@sumsub/react-native-mobilesdk-module 1.46.1).
// See docs/api/SUMSUB_MOBILE_INTEGRATION_PLAN.md (phases 1 and 2).
//
// Security rules (enforced here, keep them):
// - The SDK access token lives only in local variables: never logged, rendered or persisted.
// - withDebug(false), no onLog handler, analytics off.
// - launch() results are reduced to success/status/errorType (kyc/sdkOutcome.ts); errorMsg can
//   contain the token and is never read.
// - The SDK runs in its own Activity: the app auto-lock is suppressed meanwhile, and
//   plugins/withSumsub.js sets FLAG_SECURE on com.sumsub.* activities (no ID in the recents
//   preview or screenshots).
// - Token renewal goes through a timer-free XMLHttpRequest client (see api/xhr.ts): fetch cannot
//   complete while the SDK Activity is in front.
import { NativeModules } from 'react-native';
import type { KycSdkTokenResponse } from '../api/contract';
import { outcomeFromSdkResult } from '../kyc/sdkOutcome';
import { locale } from '../locales';
import { withAutoLockSuppressed } from '../lock/suppress';
import type { Services } from '../types/domain';
import { FeatureUnavailableError } from './errors';

export interface SumsubCaptureDeps {
  // POST /api/kyc/sdk-token on the regular client (the app is in the foreground).
  requestToken: () => Promise<KycSdkTokenResponse>;
  // POST /api/kyc/sdk-token on the timer-free client (called while the SDK is in front).
  renewToken: () => Promise<KycSdkTokenResponse>;
}

// Renewal failures are console.error()ed by the SDK's JS wrapper: keep the message generic.
class SdkTokenRenewalError extends Error {
  constructor() {
    super('Sumsub SDK token renewal failed');
    this.name = 'SdkTokenRenewalError';
  }
}

type SumsubModule = typeof import('@sumsub/react-native-mobilesdk-module').default;

function loadSdk(): SumsubModule {
  // Expo Go, or a dev client built before the module was added: no native side.
  if (!NativeModules.SNSMobileSDKModule) throw new FeatureUnavailableError('kycCapture');
  // The package is CommonJS (module.exports = { init, reset }): a plain require() has no
  // `.default` (Babel only adds it for `import`). Accept both shapes.
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const loaded = require('@sumsub/react-native-mobilesdk-module') as Partial<SumsubModule> & {
    default?: SumsubModule;
  };
  const sdk = loaded.default ?? (loaded as SumsubModule);
  if (typeof sdk?.init !== 'function') throw new FeatureUnavailableError('kycCapture');
  return sdk;
}

export function createSumsubKycCapture(deps: SumsubCaptureDeps): Services['kycCapture'] {
  return {
    async verify() {
      const SNSMobileSDK = loadSdk();
      const session = await deps.requestToken();
      const build = () =>
        SNSMobileSDK.init(session.token, async () => {
          try {
            return (await deps.renewToken()).token;
          } catch {
            throw new SdkTokenRenewalError();
          }
        })
          .withDebug(false)
          .withLocale(locale)
          .withAnalyticsEnabled(false)
          .withAutoCloseOnApprove(0)
          .build();
      const launch = () => withAutoLockSuppressed(() => build().launch());
      try {
        return outcomeFromSdkResult(await launch());
      } catch {
        // "Another instance is in use" (a previous session left after a JS reload): reset once.
        SNSMobileSDK.reset();
        try {
          return outcomeFromSdkResult(await launch());
        } catch {
          SNSMobileSDK.reset();
          return { kind: 'failed', errorType: null };
        }
      }
    },
  };
}
