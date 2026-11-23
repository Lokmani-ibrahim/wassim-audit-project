import { config } from '../config';
import { storage } from './storage';
import { realServices } from './real';
import { composeServices, withSignOutCleanup } from './compose';
import { notificationReadState } from '../notifications/readStore';
import { session } from './session';
import type { DemoControls, Services } from '../types/domain';

function unavailableDemoControls(): DemoControls {
  return {
    setOutcome() {},
    failNext() {},
    async reset() {},
    async expire() {
      throw new Error('Demo controls are unavailable outside development builds.');
    },
  };
}
let resolvedServices: Services = realServices;
let resolvedControls: DemoControls = unavailableDemoControls();
// The only place that may load src/mocks (enforced by ESLint). require() (not a static
// import) inside an __DEV__ guard lets Metro/terser dead-code-eliminate the whole mocks tree
// from release bundles once __DEV__ is inlined to false (SEC-01). config.mocks is already
// empty in non-dev builds; the __DEV__ check is the second, independent lock.
if (typeof __DEV__ !== 'undefined' && __DEV__ && config.mocks.size > 0) {
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const { createMocks } = require('../mocks') as typeof import('../mocks');
  const bundle = createMocks(storage, session, config.mocks.has('auth'));
  resolvedServices = composeServices(config.mocks, realServices, bundle.services);
  resolvedControls = bundle.controls;
}
// Sign-out (real or mocked auth, from Profile or a forced login from the unlock screen) also
// forgets the device-side notification read state of every user (src/notifications/).
export const services = withSignOutCleanup(resolvedServices, () => notificationReadState.clear());
export const demoControls = resolvedControls;
