import { config, isSandbox } from '../config';
import { topUpPreviewAvailableFor } from './sandboxInstructions';

export * from './sandboxInstructions';

// Add money shows example deposit instructions only in a sandbox build with a real session;
// a production build (EXPO_PUBLIC_APP_ENV=production) keeps the "Not available yet" screen.
export function topUpPreviewAvailable(): boolean {
  return topUpPreviewAvailableFor({
    sandbox: isSandbox(config),
    authMocked: config.mocks.has('auth'),
  });
}
