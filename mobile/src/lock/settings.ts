// Persisted unlock preference, bound to the Keycloak subject that turned it on.
import type { UnlockMethod } from './device';

export interface LockSettings {
  enabled: boolean;
  // Keycloak `sub` of the user who turned unlock on; another user signing in resets it.
  subject: string | null;
  method: UnlockMethod | null;
  // Subjects that were already offered unlock after sign-in (offer once per user).
  offered: string[];
}

export const LOCK_SETTINGS_KEY = 'neos.lock.settings';

export const EMPTY_LOCK_SETTINGS: LockSettings = {
  enabled: false,
  subject: null,
  method: null,
  offered: [],
};

export interface LockSettingsStore {
  read<T>(key: string): Promise<T | null>;
  write(key: string, value: unknown): Promise<void>;
  remove(key: string): Promise<void>;
}

function valid(value: unknown): value is LockSettings {
  if (!value || typeof value !== 'object') return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.enabled === 'boolean' &&
    (v.subject === null || typeof v.subject === 'string') &&
    (v.method === null || v.method === 'biometric' || v.method === 'deviceCredential') &&
    Array.isArray(v.offered) &&
    v.offered.every((item) => typeof item === 'string')
  );
}

// Unreadable or malformed settings fail closed to "off" (never a lock without a method, never
// an unlock that silently skips the check).
export async function readLockSettings(store: LockSettingsStore): Promise<LockSettings> {
  try {
    const value = await store.read<unknown>(LOCK_SETTINGS_KEY);
    if (!valid(value)) return EMPTY_LOCK_SETTINGS;
    if (value.enabled && (!value.subject || !value.method))
      return { ...value, enabled: false, method: null };
    return value;
  } catch {
    return EMPTY_LOCK_SETTINGS;
  }
}

export function isEnabledFor(settings: LockSettings, subject: string | null | undefined): boolean {
  return settings.enabled && !!subject && settings.subject === subject && !!settings.method;
}

// Settings to keep when `subject` signs in: another user's preference is dropped, the offer
// history is kept.
export function settingsForSignIn(settings: LockSettings, subject: string): LockSettings {
  if (settings.enabled && settings.subject !== subject)
    return { ...settings, enabled: false, subject: null, method: null };
  return settings;
}

export function shouldOffer(
  settings: LockSettings,
  subject: string,
  methodAvailable: boolean,
): boolean {
  return methodAvailable && !isEnabledFor(settings, subject) && !settings.offered.includes(subject);
}

export function markOffered(settings: LockSettings, subject: string): LockSettings {
  return settings.offered.includes(subject)
    ? settings
    : { ...settings, offered: [...settings.offered, subject].slice(-10) };
}

export function enabledSettings(
  settings: LockSettings,
  subject: string,
  method: UnlockMethod,
): LockSettings {
  return { ...markOffered(settings, subject), enabled: true, subject, method };
}

export function disabledSettings(settings: LockSettings): LockSettings {
  return { ...settings, enabled: false, subject: null, method: null };
}
