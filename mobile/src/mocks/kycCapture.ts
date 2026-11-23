import type { KycSdkOutcome, Services } from '../types/domain';

export const KYC_CAPTURE_MARKER = '__NEOS_MOCK__:kycCapture';
// Fakes a Sumsub SDK session: no camera, no token request, nothing is submitted anywhere.
// Returns a scripted on-device outcome; the KYC decision still comes from the (mocked or real)
// status source, never from this result.
export function createMockKycCapture(
  delay = 850,
  outcome: () => KycSdkOutcome = () => ({ kind: 'submitted' }),
): Services['kycCapture'] {
  return {
    async verify() {
      await new Promise<void>((resolve) => setTimeout(resolve, delay));
      return outcome();
    },
  };
}
