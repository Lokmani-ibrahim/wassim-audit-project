import React, { createContext, useContext, type ReactNode } from 'react';
import type { Profile } from '../types/domain';
import type { MessageKey } from '../locales';

// The authenticated area's only view of the session. It deliberately exposes no onboarding or
// KYC state (ARC-02): screens under navigation/AuthenticatedApp depend on this, not on the
// onboarding journey hook. App.tsx builds the value from the journey gate.
export interface SessionValue {
  // Always set inside the authenticated area (APPROVED + ACTIVE).
  profile: Profile;
  // Feed a freshly read profile (GET /api/profile) back into the gate. If it is no longer
  // APPROVED + ACTIVE the gate leaves the authenticated area on its own.
  sync(next: Profile): void;
  signOut(): void;
  busy: boolean;
  error: MessageKey | null;
  // Long-press on the wordmark: dev panel (no-op outside dev builds with mocks).
  onDev(): void;
  // App unlock preference for this user (Profile → Security).
  appLock: AppLockSettingValue;
}

export interface AppLockSettingValue {
  enabled: boolean;
  method: 'biometric' | 'deviceCredential' | null;
  timeoutSeconds: number;
  // Asks for one device authentication; resolves to what happened.
  enable(): Promise<'enabled' | 'cancelled' | 'failed' | 'unavailable'>;
  disable(): Promise<void>;
  // Whether this device can offer unlock right now (fingerprint or screen lock enrolled).
  available(): Promise<boolean>;
}

const SessionContext = createContext<SessionValue | null>(null);

export function SessionProvider({ value, children }: { value: SessionValue; children: ReactNode }) {
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error('useSession() used outside the authenticated area');
  return value;
}
