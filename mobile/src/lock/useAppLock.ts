// React wiring of the app-unlock machine: persisted preference, AppState, prompts.
// Rendering is done by App.tsx (UnlockScreen, the opt-in sheet, the privacy cover).
import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { AppState, type AppStateStatus } from 'react-native';
import { config } from '../config';
import { t } from '../locales';
import { services } from '../services';
import { storage } from '../services/storage';
import {
  DEFAULT_MAX_FAILURES,
  disablesUnlock,
  initialLockState,
  isLocked,
  reduceLock,
  type LockEvent,
  type LockPolicy,
  type LockState,
} from './machine';
import {
  availableUnlockMethod,
  clearUnlockMarker,
  enrollUnlock,
  unlockWith,
  type UnlockMethod,
} from './device';
import {
  EMPTY_LOCK_SETTINGS,
  LOCK_SETTINGS_KEY,
  disabledSettings,
  enabledSettings,
  isEnabledFor,
  markOffered,
  readLockSettings,
  settingsForSignIn,
  shouldOffer,
  type LockSettings,
} from './settings';
import { isAutoLockSuppressed, onAutoLockRelease } from './suppress';

const policy: LockPolicy = {
  timeoutMs: config.lockTimeoutSeconds * 1000,
  maxFailures: DEFAULT_MAX_FAILURES,
};

export type EnableOutcome = 'enabled' | 'cancelled' | 'failed' | 'unavailable';

export function useAppLock() {
  const [state, dispatchRaw] = useReducer(
    (current: LockState, event: LockEvent) => reduceLock(current, event, policy),
    initialLockState,
  );
  const [starting, setStarting] = useState(true);
  const [settings, setSettingsState] = useState<LockSettings>(EMPTY_LOCK_SETTINGS);
  const [appState, setAppState] = useState<AppStateStatus>(AppState.currentState);
  const [prompting, setPrompting] = useState(false);
  const settingsRef = useRef(settings);
  // True once the cold-start lock (if any) has been passed; the journey may restore from then on.
  const [released, setReleased] = useState(false);

  const saveSettings = useCallback(async (next: LockSettings) => {
    settingsRef.current = next;
    setSettingsState(next);
    try {
      await storage.write(LOCK_SETTINGS_KEY, next);
    } catch {
      /* the in-memory value still applies for this run */
    }
  }, []);

  // Cold start: decide before the journey restores the session.
  useEffect(() => {
    let alive = true;
    void (async () => {
      const stored = await readLockSettings(storage);
      const hasSession = stored.enabled
        ? await services.auth.hasStoredSession().catch(() => false)
        : false;
      if (!alive) return;
      settingsRef.current = stored;
      setSettingsState(stored);
      dispatchRaw({ type: 'coldStart', enabled: stored.enabled, hasSession });
      setStarting(false);
    })();
    return () => {
      alive = false;
    };
  }, []);

  useEffect(() => {
    if (!starting && !isLocked(state)) setReleased(true);
  }, [starting, state]);

  useEffect(() => {
    const subscription = AppState.addEventListener('change', (next) => {
      setAppState(next);
      if (next === 'background')
        dispatchRaw({ type: 'background', at: Date.now(), own: isAutoLockSuppressed() });
      else if (next === 'active') dispatchRaw({ type: 'foreground', at: Date.now() });
    });
    // An own prompt ended while the app is still in the background: start the timer now.
    const release = onAutoLockRelease(() => {
      if (AppState.currentState === 'background')
        dispatchRaw({ type: 'background', at: Date.now() });
    });
    return () => {
      subscription.remove();
      release();
    };
  }, []);

  const unlock = useCallback(async () => {
    const method = settingsRef.current.method;
    if (!method) {
      dispatchRaw({ type: 'unlockResult', result: 'notEnrolled' });
      return;
    }
    setPrompting(true);
    try {
      const result = await unlockWith(method, { title: t('unlockPrompt'), cancel: t('cancel') });
      dispatchRaw({ type: 'unlockResult', result });
    } finally {
      setPrompting(false);
    }
  }, []);

  // Prompt automatically when the lock appears (not again after a cancel or failure: the user
  // taps "Unlock" to retry).
  const autoPrompted = useRef<LockState | null>(null);
  useEffect(() => {
    if (state.name !== 'locked' || state.notice !== null || state.failures > 0) return;
    if (appState !== 'active' || prompting || autoPrompted.current === state) return;
    autoPrompted.current = state;
    void unlock();
  }, [appState, prompting, state, unlock]);

  // Login required: the stored session is removed at once (the password path is the only way
  // back in). Biometrics changed / removed also turns unlock off.
  useEffect(() => {
    if (state.name !== 'loginRequired') return;
    void (async () => {
      if (disablesUnlock(state)) {
        await clearUnlockMarker();
        await saveSettings(disabledSettings(settingsRef.current));
      }
      await services.auth.signOut().catch(() => undefined);
    })();
  }, [saveSettings, state]);

  // Called by App.tsx whenever the journey's session changes (after the cold-start release).
  const syncSession = useCallback(
    (subject: string | null) => {
      if (!subject) {
        dispatchRaw({ type: 'session', present: false, enabled: false });
        return;
      }
      const kept = settingsForSignIn(settingsRef.current, subject);
      if (kept !== settingsRef.current) {
        void clearUnlockMarker();
        void saveSettings(kept);
      }
      dispatchRaw({ type: 'session', present: true, enabled: isEnabledFor(kept, subject) });
    },
    [saveSettings],
  );

  const enable = useCallback(
    async (subject: string): Promise<EnableOutcome> => {
      const method = await availableUnlockMethod();
      if (!method) return 'unavailable';
      const result = await enrollUnlock(method, {
        title: t('unlockEnablePrompt'),
        cancel: t('cancel'),
      });
      if (result !== 'success')
        return result === 'cancelled'
          ? 'cancelled'
          : result === 'notEnrolled'
            ? 'unavailable'
            : 'failed';
      await saveSettings(enabledSettings(settingsRef.current, subject, method));
      dispatchRaw({ type: 'enabled' });
      return 'enabled';
    },
    [saveSettings],
  );

  const disable = useCallback(async () => {
    await clearUnlockMarker();
    await saveSettings(disabledSettings(settingsRef.current));
    dispatchRaw({ type: 'disabled' });
  }, [saveSettings]);

  const offered = useCallback(
    async (subject: string) => {
      await saveSettings(markOffered(settingsRef.current, subject));
    },
    [saveSettings],
  );

  const offerFor = useCallback(async (subject: string): Promise<UnlockMethod | null> => {
    const method = await availableUnlockMethod();
    return shouldOffer(settingsRef.current, subject, method !== null) ? method : null;
  }, []);

  // Explicit sign-out: tokens are removed by the journey; here the preference, the marker and
  // any lock state. Pending-transfer records are NOT touched (per user, see transfers/engine).
  const signedOut = useCallback(async () => {
    await clearUnlockMarker();
    settingsRef.current = EMPTY_LOCK_SETTINGS;
    setSettingsState(EMPTY_LOCK_SETTINGS);
    try {
      await storage.remove(LOCK_SETTINGS_KEY);
    } catch {
      /* already gone */
    }
    dispatchRaw({ type: 'signedOut' });
  }, []);

  // Leaving a lock screen for the password sign-in (or "Not now"): the preference is kept for
  // the same user (unless it was just turned off above), the lock state is cleared.
  const leaveLock = useCallback(() => dispatchRaw({ type: 'signedOut' }), []);

  return {
    state,
    starting,
    locked: starting || isLocked(state),
    // Journey options: hold the cold-start restore; pause the foreground re-check while locked.
    hold: !released,
    paused: isLocked(state),
    prompting,
    settings,
    appState,
    enabledFor: (subject: string | null | undefined) => isEnabledFor(settings, subject),
    unlock,
    enable,
    disable,
    offerFor,
    offered,
    syncSession,
    signedOut,
    leaveLock,
    maxFailures: policy.maxFailures,
    timeoutSeconds: config.lockTimeoutSeconds,
  };
}
export type AppLock = ReturnType<typeof useAppLock>;
