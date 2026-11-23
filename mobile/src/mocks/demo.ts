import type { DemoOutcome, Profile, Services } from '../types/domain';
import { config } from '../config';
import { AppError } from '../services/errors';
import { createSession, type SessionStore } from '../services/session';
import { authErrors, normalizeEmail } from '../utils/authValidation';

export interface DemoStorage {
  read<T>(key: string): Promise<T | null>;
  write(key: string, value: unknown): Promise<void>;
  remove(key: string): Promise<void>;
}
// Unique per-feature markers: scripts/check-release-bundle.mjs fails the release build if any
// reaches a non-dev bundle.
export const AUTH_MARKER = '__NEOS_MOCK__:auth';
export const ONBOARDING_MARKER = '__NEOS_MOCK__:onboarding';
export const KYC_MARKER = '__NEOS_MOCK__:kyc';
export const DEMO_FALLBACK_EMAIL = 'khalil@gmail.com';
const identityFor = (email: string): Profile['identity'] => ({
  subject: `demo:${email}`,
  username: email
    .split('@')[0]!
    .split(/[._-]/)[0]!
    .replace(/^./, (character) => character.toUpperCase()),
  email,
});
const seed = (email = DEMO_FALLBACK_EMAIL): Profile => ({
  identity: identityFor(email),
  onboarded: false,
  kyc: 'NOT_STARTED',
  account: 'PENDING_KYC',
});
const outcomes: DemoOutcome[] = ['APPROVED', 'PENDING', 'REJECTED', 'MORE_INFO_REQUIRED'];
export interface DemoOptions {
  // Shared with the real services so a mocked feature can sit on a real one.
  session?: SessionStore;
  // Persist the demo account (only meaningful when `auth` itself is mocked).
  persist?: boolean;
}
export function createDemoServices(store: DemoStorage, delay = 450, options: DemoOptions = {}) {
  const state = options.session ?? createSession();
  const persist = options.persist ?? true;
  let failNext = false;
  let outcome: DemoOutcome = outcomes.includes(config.demoKycResult as DemoOutcome)
    ? (config.demoKycResult as DemoOutcome)
    : 'APPROVED';
  async function readAccount() {
    const saved = await store.read<Profile>('neos.demo.v1');
    if (!saved) return null;
    // Migrate the previous generated identity without retaining its old seed email.
    if (saved.identity.subject === 'demo-alex' || !saved.identity.email) {
      saved.identity = identityFor(DEMO_FALLBACK_EMAIL);
      await store.write('neos.demo.v1', saved);
    }
    return saved;
  }
  async function wait() {
    await new Promise<void>((resolve) => setTimeout(resolve, delay));
    if (failNext) {
      failNext = false;
      throw new AppError('networkError');
    }
  }
  async function save(profile: Profile): Promise<Profile> {
    if (persist) {
      await store.write('neos.demo.v1', profile);
      await store.remove('neos.demo.signedOut');
    }
    state.profile = profile;
    return { ...profile };
  }
  function required() {
    if (!state.profile) throw new AppError('sessionError', true);
    return state.profile;
  }
  async function decision() {
    await wait();
    const profile = required();
    if (!profile.onboarded) throw new AppError('kycError');
    return save({
      ...profile,
      kyc: outcome,
      account: outcome === 'APPROVED' ? 'ACTIVE' : 'PENDING_KYC',
    });
  }
  const services: Pick<Services, 'auth' | 'onboarding' | 'kyc' | 'account'> = {
    auth: {
      async signIn(intentOrForm) {
        await wait();
        const input = intentOrForm && 'email' in intentOrForm ? intentOrForm : undefined;
        if (input) {
          const error = Object.values(authErrors(input))[0];
          if (error) throw new AppError(error);
        }
        const saved = await readAccount();
        const email = input
          ? normalizeEmail(input.email)
          : saved?.identity.email || DEMO_FALLBACK_EMAIL;
        // One local demonstration account. Never inherit another email's lifecycle.
        const account =
          intentOrForm?.intent !== 'create' &&
          saved &&
          normalizeEmail(saved.identity.email) === email
            ? saved
            : seed(email);
        return save({ ...account, identity: identityFor(email) });
      },
      async restore() {
        state.profile = (await store.read<boolean>('neos.demo.signedOut'))
          ? null
          : await readAccount();
        return state.profile;
      },
      async hasStoredSession() {
        return !(await store.read<boolean>('neos.demo.signedOut')) && !!(await readAccount());
      },
      async signOut() {
        await store.write('neos.demo.signedOut', true);
        state.profile = null;
        return true;
      },
    },
    onboarding: {
      async submit() {
        await wait();
        return save({ ...required(), onboarded: true });
      },
    },
    kyc: { submit: decision, refresh: decision },
    account: {
      async get() {
        await wait();
        return { ...required() };
      },
    },
  };
  return {
    services,
    controls: {
      setOutcome(value: DemoOutcome) {
        outcome = value;
      },
      failNext() {
        failNext = true;
      },
      async reset() {
        outcome = 'APPROVED';
        failNext = false;
        if (!persist) return;
        await services.auth.signOut();
        await store.remove('neos.demo.v1');
        await store.remove('neos.demo.signedOut');
      },
      async expire() {
        if (!persist) throw new Error('Session expiry can only be simulated when auth is mocked.');
        await services.auth.signOut();
        throw new AppError('sessionError', true);
      },
    },
  };
}
