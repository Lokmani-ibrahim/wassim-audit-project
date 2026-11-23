// App unlock state machine (pure: no React, no Expo). The hook in useAppLock.ts feeds it
// events (cold start, app state changes, prompt results, session changes) and renders the
// unlock screen whenever `isLocked(state)` is true.
//
// Rules:
//   - Unlock is opt-in. With the preference off, or without a stored session, it is `off`.
//   - Cold start with the preference on and a stored session => locked, BEFORE the session is
//     restored (no account data is loaded or shown until unlocked).
//   - Background => foreground after `timeoutMs` or more => locked. A clock that went backwards
//     also locks (fail-safe).
//   - Backgrounding caused by the app's own prompts (device credential screen, biometric
//     prompt, Keycloak tab) is ignored (`own: true`).
//   - Cancel => stay locked (Try again / Sign in with password); it is not counted as a failure.
//   - `maxFailures` failed attempts, or any system lockout => Keycloak login required.
//   - Biometrics changed (Keystore key invalidated) or no longer enrolled => unlock is turned
//     off and Keycloak login is required.
//   - Unlocking never counts as a transfer confirmation: Send always asks again (separate prompt,
//     no "recently authenticated" state exists here).

export type UnlockResult =
  'success' | 'cancelled' | 'failed' | 'lockout' | 'invalidated' | 'notEnrolled';

export type LoginRequiredReason =
  'tooManyAttempts' | 'lockout' | 'biometricsChanged' | 'notEnrolled';

export type LockState =
  | { name: 'off' }
  // `backgroundAt`: when the app left the foreground (null while in the foreground).
  | { name: 'unlocked'; backgroundAt: number | null }
  | { name: 'locked'; failures: number; notice: 'cancelled' | 'failed' | null }
  | { name: 'loginRequired'; reason: LoginRequiredReason };

export type LockEvent =
  | { type: 'coldStart'; enabled: boolean; hasSession: boolean }
  | { type: 'background'; at: number; own?: boolean }
  | { type: 'foreground'; at: number }
  | { type: 'unlockResult'; result: UnlockResult }
  // The journey gained or lost a session (sign-in, restore, expiry).
  | { type: 'session'; present: boolean; enabled: boolean }
  | { type: 'enabled' }
  | { type: 'disabled' }
  // Explicit sign-out, or the user chose "Sign in with password" / left the login-required screen.
  | { type: 'signedOut' };

export interface LockPolicy {
  timeoutMs: number;
  maxFailures: number;
}

export const DEFAULT_MAX_FAILURES = 3;

export const initialLockState: LockState = { name: 'off' };

export function isLocked(state: LockState): boolean {
  return state.name === 'locked' || state.name === 'loginRequired';
}

// Whether entering this state must turn the unlock preference off (the stored marker can no
// longer prove the enrolled biometrics).
export function disablesUnlock(state: LockState): boolean {
  return (
    state.name === 'loginRequired' &&
    (state.reason === 'biometricsChanged' || state.reason === 'notEnrolled')
  );
}

// A new object each time: the hook auto-prompts once per lock, keyed on state identity.
const locked = (): LockState => ({ name: 'locked', failures: 0, notice: null });

export function reduceLock(state: LockState, event: LockEvent, policy: LockPolicy): LockState {
  switch (event.type) {
    case 'coldStart':
      return event.enabled && event.hasSession ? locked() : { name: 'off' };

    case 'background':
      if (state.name !== 'unlocked' || event.own) return state;
      // Keep the first timestamp if several background events arrive.
      return state.backgroundAt === null ? { ...state, backgroundAt: event.at } : state;

    case 'foreground': {
      if (state.name !== 'unlocked' || state.backgroundAt === null) return state;
      const away = event.at - state.backgroundAt;
      if (away < 0 || away >= policy.timeoutMs) return locked();
      return { name: 'unlocked', backgroundAt: null };
    }

    case 'unlockResult': {
      if (state.name !== 'locked') return state;
      switch (event.result) {
        case 'success':
          return { name: 'unlocked', backgroundAt: null };
        case 'cancelled':
          return { ...state, notice: 'cancelled' };
        case 'failed': {
          const failures = state.failures + 1;
          return failures >= policy.maxFailures
            ? { name: 'loginRequired', reason: 'tooManyAttempts' }
            : { name: 'locked', failures, notice: 'failed' };
        }
        case 'lockout':
          return { name: 'loginRequired', reason: 'lockout' };
        case 'invalidated':
          return { name: 'loginRequired', reason: 'biometricsChanged' };
        case 'notEnrolled':
          return { name: 'loginRequired', reason: 'notEnrolled' };
      }
      return state;
    }

    case 'session':
      // A lost session (expiry) or another user's session: nothing to protect any more. The
      // lock screens are left alone (they handle their own way out).
      if (!event.present || !event.enabled)
        return state.name === 'unlocked' ? { name: 'off' } : state;
      return state.name === 'off' ? { name: 'unlocked', backgroundAt: null } : state;

    case 'enabled':
      return state.name === 'off' ? { name: 'unlocked', backgroundAt: null } : state;

    case 'disabled':
      return state.name === 'unlocked' ? { name: 'off' } : state;

    case 'signedOut':
      return { name: 'off' };
  }
}

// EXPO_PUBLIC_LOCK_TIMEOUT_SECONDS: whole seconds, 0..3600. Unset/empty => default.
export const DEFAULT_LOCK_TIMEOUT_SECONDS = 60;
export function parseLockTimeoutSeconds(raw: string | undefined): number {
  const value = (raw ?? '').trim();
  if (!value) return DEFAULT_LOCK_TIMEOUT_SECONDS;
  if (!/^\d+$/.test(value) || Number(value) > 3600)
    throw new Error(
      `EXPO_PUBLIC_LOCK_TIMEOUT_SECONDS must be a whole number of seconds between 0 and 3600 (got "${value}").`,
    );
  return Number(value);
}
