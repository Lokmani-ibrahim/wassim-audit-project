import type { Profile } from '../types/domain';

// Current-session profile, shared by the real and mock implementations so a mocked feature
// (e.g. kyc) can sit on top of a real one (e.g. auth) while developing feature by feature.
// `onExpired` lets the UI react to a session expiry raised from ANY screen's request (the
// authenticated area does not have to remember to forward errors to the onboarding hook).
export interface SessionStore {
  profile: Profile | null;
  onExpired(listener: () => void): () => void;
  notifyExpired(): void;
}
export function createSession(): SessionStore {
  const listeners = new Set<() => void>();
  return {
    profile: null,
    onExpired(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    notifyExpired() {
      for (const listener of [...listeners]) listener();
    },
  };
}
export const session: SessionStore = createSession();
