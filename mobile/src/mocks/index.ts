import type { SessionStore } from '../services/session';
import type { DemoControls, MockableServices } from '../types/domain';
import { createDemoServices, type DemoStorage } from './demo';
import { createMockKycCapture } from './kycCapture';
import { createMockNotifications } from './notifications';
import { createMockWallets } from './wallets';

export interface MockBundle {
  // Every mock, ready to be picked per feature by composeServices.
  services: MockableServices;
  controls: DemoControls;
}
// Only imported (via guarded require) from services/index.ts, under __DEV__.
export function createMocks(
  store: DemoStorage,
  session: SessionStore,
  authMocked: boolean,
): MockBundle {
  const demo = createDemoServices(store, 450, { session, persist: authMocked });
  return {
    services: {
      ...demo.services,
      kycCapture: createMockKycCapture(),
      wallets: createMockWallets(),
      notifications: createMockNotifications(),
    },
    controls: demo.controls,
  };
}
