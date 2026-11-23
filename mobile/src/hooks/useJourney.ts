import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, AppState, BackHandler, Keyboard } from 'react-native';
import { services, demoControls } from '../services';
import { AppError, UserCancelledError, errorKey } from '../services/errors';
import { session } from '../services/session';
import {
  ACKNOWLEDGED_ON_SESSION_START,
  STATUS_ROUTES,
  acknowledgedAfterUpdate,
  previousRoute,
  routeFor,
} from '../navigation/state';
import { KycFlowError, needsFreshProfile, resolveKycFailure } from '../kyc/errors';
import type {
  AuthIntentInput,
  DemoAuthInput,
  DemoOutcome,
  KycSdkOutcome,
  Profile,
  Route,
} from '../types/domain';
import type { MessageKey } from '../locales';
import { t } from '../locales';

// App unlock (src/lock) integration:
//   - `hold`: the cold-start session restore waits until the unlock screen is passed, so no
//     account data is loaded before the user is authenticated.
//   - `paused`: while locked, the foreground re-check is skipped; it runs once on unlock.
export interface JourneyOptions {
  hold?: boolean;
  paused?: boolean;
}

export function useJourney({ hold = false, paused = false }: JourneyOptions = {}) {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [route, setRoute] = useState<Route>('welcome');
  const [busy, setBusy] = useState<MessageKey | null>('checking');
  const [error, setError] = useState<MessageKey | null>(null);
  const [phone, setPhone] = useState('');
  const [accepted, setAccepted] = useState(false);
  // KYC: the backend refused an SDK session for account reasons (restricted screen), or the
  // verification needs a manual review ("Continue verification" hidden). Session-local.
  const kycRestricted = useRef(false);
  const [reviewLocked, setReviewLocked] = useState(false);
  // Bumped after the SDK closes or a manual check, to restart the status polling schedule.
  const [pollKey, setPollKey] = useState(0);
  const locked = useRef(false);
  const journeyGeneration = useRef(0);
  const acknowledged = useRef(false);
  const current = useRef<Profile | null>(null);
  // Set once the session question is settled (restore ran, or sign-in/sign-out made it moot).
  const settled = useRef(false);
  const pausedRef = useRef(paused);
  const apply = useCallback((next: Profile | null) => {
    // The restricted override only ever applies to a suspended account in this session.
    if (next?.account !== 'SUSPENDED') kycRestricted.current = false;
    if (!next) setReviewLocked(false);
    current.current = next;
    setProfile(next);
    setRoute(routeFor(next, acknowledged.current, { kycRestricted: kycRestricted.current }));
  }, []);
  // Status update within the session: verified screens only on an observed transition.
  const update = useCallback(
    (next: Profile | null) => {
      acknowledged.current = acknowledgedAfterUpdate(current.current, next, acknowledged.current);
      apply(next);
    },
    [apply],
  );
  const run = useCallback(
    async (
      label: MessageKey,
      action: () => Promise<void>,
      fallback: MessageKey = 'unexpectedError',
    ) => {
      if (locked.current) return;
      const generation = journeyGeneration.current;
      locked.current = true;
      Keyboard.dismiss();
      setBusy(label);
      setError(null);
      try {
        await action();
      } catch (cause) {
        if (generation !== journeyGeneration.current) return;
        if (cause instanceof AppError && cause.expired) {
          acknowledged.current = false;
          apply(null);
          setPhone('');
          setAccepted(false);
        }
        // Closing the sign-in page is not an error: stay on welcome silently.
        if (cause instanceof AppError && cause.key === 'identityConflict')
          setRoute('onboardingConflict');
        if (
          cause instanceof AppError &&
          (cause.key === 'bootstrapUnavailable' || cause.key === 'identityError')
        )
          setRoute('onboardingUnavailable');
        if (!(cause instanceof UserCancelledError)) setError(errorKey(cause, fallback));
      } finally {
        if (generation === journeyGeneration.current) {
          locked.current = false;
          setBusy(null);
        }
      }
    },
    [apply],
  );
  useEffect(() => {
    if (hold || settled.current) return;
    settled.current = true;
    void run('checking', async () => {
      acknowledged.current = ACKNOWLEDGED_ON_SESSION_START;
      apply(await services.auth.restore());
    });
  }, [apply, hold, run]);
  // A session expiry raised by any request (including from the authenticated area) returns
  // to welcome with the session message.
  useEffect(
    () =>
      session.onExpired(() => {
        acknowledged.current = false;
        apply(null);
        setPhone('');
        setAccepted(false);
        setError('sessionError');
      }),
    [apply],
  );
  const go = useCallback((next: Route) => {
    if (locked.current) return;
    Keyboard.dismiss();
    setError(null);
    setRoute(next);
  }, []);
  const signOut = useCallback(async () => {
    const generation = ++journeyGeneration.current;
    locked.current = true;
    settled.current = true;
    acknowledged.current = false;
    Keyboard.dismiss();
    setPhone('');
    setAccepted(false);
    setError(null);
    apply(null);
    let pending: Promise<boolean>;
    try {
      pending = services.auth.signOut();
    } catch {
      setError('logoutIncomplete');
      locked.current = false;
      setBusy(null);
      return;
    }
    // Local account state is gone now. The next login waits for remote logout in real.ts.
    locked.current = false;
    setBusy(null);
    try {
      const remoteEnded = await pending;
      if (generation === journeyGeneration.current && !current.current && !remoteEnded)
        setError('logoutIncomplete');
    } catch {
      if (generation === journeyGeneration.current && !current.current)
        setError('logoutIncomplete');
    }
  }, [apply]);
  const exit = useCallback(() => {
    Alert.alert(t('leaveSetupTitle'), t('leaveSetupBody'), [
      { text: t('cancel'), style: 'cancel' },
      {
        text: t('useAnotherAccount'),
        onPress: () => {
          void signOut();
        },
      },
    ]);
  }, [signOut]);
  const back = useCallback(() => {
    if (locked.current) return true;
    if (route === 'intro' || route === 'onboardingConflict' || route === 'onboardingUnavailable') {
      exit();
      return true;
    }
    const previous = previousRoute[route];
    if (previous) {
      go(previous);
      return true;
    }
    return false;
  }, [exit, go, route]);
  useEffect(() => {
    const listener = BackHandler.addEventListener('hardwareBackPress', back);
    return () => listener.remove();
  }, [back]);
  // Re-check authentication and status on foreground (read-only GET /api/profile, never a
  // mutating KYC request). On screens that only show backend state (home, KYC status screens,
  // the account-unavailable screen: ISSUE-6) the fresh status is applied, so a suspension or a
  // restored account takes effect; on forms only a lost session is applied (keeps progress).
  const recheck = useCallback(
    () =>
      run('checking', async () => {
        const restored = await services.auth.restore();
        if (!restored) {
          // The session was in use and can no longer be restored: it expired.
          acknowledged.current = false;
          apply(null);
          setError('sessionError');
        } else if (STATUS_ROUTES.includes(route)) update(restored);
      }),
    [apply, route, run, update],
  );
  useEffect(() => {
    const listener = AppState.addEventListener('change', (state) => {
      if (state === 'active' && profile && !locked.current && !pausedRef.current) void recheck();
    });
    return () => listener.remove();
  }, [profile, recheck]);
  // Unlocked after a background lock: the re-check skipped while locked runs now.
  useEffect(() => {
    const wasPaused = pausedRef.current;
    pausedRef.current = paused;
    if (wasPaused && !paused && current.current && !locked.current) void recheck();
  }, [paused, recheck]);
  // Quiet status re-read for the polling schedule (no spinner, no error banner). Skipped while
  // another action runs. Session expiry is handled globally (session.onExpired).
  const poll = useCallback(async () => {
    if (locked.current) return;
    try {
      update(await services.kyc.refresh());
    } catch {
      /* The schedule continues; "Check status" shows errors. */
    }
  }, [update]);
  // Acts on a refused KYC start/token request (kyc/errors.ts). Returns true when KYC was started
  // and the token should be requested once more.
  const handleKycFailure = useCallback(
    async (error: KycFlowError, retried: boolean): Promise<boolean> => {
      const fresh = needsFreshProfile(error.failure) ? await services.kyc.refresh() : null;
      const step = resolveKycFailure(error.failure, fresh, retried);
      switch (step.kind) {
        case 'startAndRetry':
          update(await services.kyc.submit());
          return true;
        case 'route':
          if (step.restricted) kycRestricted.current = true;
          update(fresh ?? (await services.kyc.refresh()));
          return false;
        case 'message':
          if (step.reviewLocked) setReviewLocked(true);
          if (step.route && fresh) update(fresh);
          throw new AppError(step.key);
      }
    },
    [update],
  );
  return {
    profile,
    route,
    busy,
    error,
    phone,
    setPhone,
    accepted,
    setAccepted,
    reviewLocked,
    pollKey,
    poll,
    go,
    back: previousRoute[route]
      ? () => {
          back();
        }
      : route === 'intro' || route === 'onboardingConflict' || route === 'onboardingUnavailable'
        ? exit
        : undefined,
    exit,
    signIn: (input?: AuthIntentInput | DemoAuthInput) => {
      journeyGeneration.current += 1;
      return run(
        'signingIn',
        async () => {
          acknowledged.current = ACKNOWLEDGED_ON_SESSION_START;
          settled.current = true;
          kycRestricted.current = false;
          setReviewLocked(false);
          const next = await services.auth.signIn(input);
          setPhone('');
          setAccepted(false);
          apply(next);
          if (input?.intent === 'create' && !next.onboarded) setRoute('details');
        },
        'authError',
      );
    },
    save: () =>
      run('saving', async () => update(await services.onboarding.submit(phone)), 'saveError'),
    // Start (if needed) → SDK token → Sumsub SDK → re-read the status, then poll.
    // The SDK result is never the decision (that is the backend's, via the Sumsub webhook).
    verify: () =>
      run(
        'preparingVerification',
        async () => {
          try {
            if (current.current?.kyc === 'NOT_STARTED') update(await services.kyc.submit());
            const kyc = current.current?.kyc;
            if (kyc !== 'PENDING' && kyc !== 'IN_REVIEW' && kyc !== 'MORE_INFO_REQUIRED') return;
            let outcome: KycSdkOutcome | null = null;
            for (let attempt = 0; attempt < 2 && !outcome; attempt += 1) {
              try {
                outcome = await services.kycCapture.verify();
              } catch (cause) {
                if (!(cause instanceof KycFlowError)) throw cause;
                if (!(await handleKycFailure(cause, attempt > 0))) return;
              }
            }
            if (!outcome) throw new AppError('kycError');
            update(await services.kyc.refresh());
            setPollKey((key) => key + 1);
            if (outcome.kind === 'failed')
              setError(outcome.errorType === 'NetworkError' ? 'kycUnavailable' : 'kycSdkFailed');
          } catch (cause) {
            // kyc/start refused (e.g. ONBOARDING_REQUIRED): route on the fresh status.
            if (cause instanceof KycFlowError) {
              await handleKycFailure(cause, true);
              return;
            }
            // 5xx / network / timeout: never retried automatically.
            if (cause instanceof AppError && cause.key === 'networkError')
              throw new AppError('kycUnavailable');
            throw cause;
          }
        },
        'kycError',
      ),
    refresh: () =>
      run(
        'checking',
        async () => {
          update(await services.kyc.refresh());
          setPollKey((key) => key + 1);
        },
        'kycError',
      ),
    home: () => {
      acknowledged.current = true;
      go('home');
    },
    // Fresh profile read from the authenticated area (GET /api/profile). Re-runs the gate.
    sync: (next: Profile) => update(next),
    signOut,
    reset: () =>
      run('checking', async () => {
        await demoControls.reset();
        acknowledged.current = false;
        setPhone('');
        setAccepted(false);
        apply(null);
      }),
    expire: () =>
      run('checking', async () => {
        await demoControls.expire();
      }),
    scenario: (value: DemoOutcome) => {
      demoControls.setOutcome(value);
      if (profile?.onboarded) go('pending');
    },
    failNext: () => demoControls.failNext(),
  };
}
export type Journey = ReturnType<typeof useJourney>;
