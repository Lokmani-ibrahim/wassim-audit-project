import React, { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, StatusBar, StyleSheet, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { Button, Copy, Fade, Screen, Sheet, c } from './src/components/ui';
import { useJourney } from './src/hooks/useJourney';
import { t } from './src/locales';
import { config, configError } from './src/config';
import { Welcome } from './src/screens/Welcome';
import { Onboarding } from './src/screens/Onboarding';
import { Kyc, StatusScreen } from './src/screens/Kyc';
import { AuthenticatedApp } from './src/navigation/AuthenticatedApp';
import { SessionProvider, type SessionValue } from './src/session/SessionContext';
import { ErrorBoundary } from './src/components/ErrorBoundary';
import { DevBanner } from './src/components/DevBanner';
import { DemoAuth } from './src/screens/DemoAuth';
import { PrivacyCover, UnlockOffer, UnlockScreen } from './src/screens/Unlock';
import { useAppLock } from './src/lock/useAppLock';
import { availableUnlockMethod, type UnlockMethod } from './src/lock/device';
import { isAutoLockSuppressed } from './src/lock/suppress';
import type { AuthIntentInput, DemoAuthInput } from './src/types/domain';

function JourneyApp() {
  // Invalid non-dev configuration (SEC-01) must surface loudly, not fail silently.
  if (configError) throw configError;
  // App unlock decides first: with unlock on and a stored session, nothing is restored (no
  // account data loaded) until the user passes the unlock screen.
  const lock = useAppLock();
  const journeyState = useJourney({ hold: lock.hold, paused: lock.paused });
  const [dev, setDev] = useState(false);
  const [lockBusy, setLockBusy] = useState(false);
  const [offer, setOffer] = useState<UnlockMethod | null>(null);
  // Set by an interactive sign-in: unlock is offered once that user reaches Home.
  const signedInNow = useRef(false);
  const j = {
    ...journeyState,
    signIn: (input?: AuthIntentInput | DemoAuthInput) => {
      signedInNow.current = true;
      return journeyState.signIn(input);
    },
    signOut: async () => {
      const pending = journeyState.signOut();
      await lock.signedOut();
      await pending;
    },
  };
  const subject = j.profile?.identity.subject ?? null;
  const sessionSettled = !(j.busy && !j.profile);
  const { hold, locked, syncSession, offerFor } = lock;
  useEffect(() => {
    if (hold || !sessionSettled) return;
    syncSession(subject);
  }, [hold, sessionSettled, subject, syncSession]);
  useEffect(() => {
    if (!signedInNow.current || j.route !== 'home' || !subject || locked) return;
    signedInNow.current = false;
    let alive = true;
    void offerFor(subject).then((method) => {
      if (alive && method) setOffer(method);
    });
    return () => {
      alive = false;
    };
  }, [j.route, subject, locked, offerFor]);
  // Leave a lock screen: local sign-out (pending-transfer records are kept per user), then the
  // full Keycloak login if asked. The unlock preference is kept for the same user.
  const leaveLock = async (password: boolean) => {
    if (lockBusy) return;
    setLockBusy(true);
    try {
      await journeyState.signOut();
      lock.leaveLock();
      if (password) {
        if (config.mocks.has('auth')) j.go('signIn');
        else await j.signIn({ intent: 'signIn' });
      }
    } finally {
      setLockBusy(false);
    }
  };
  // Dev panel controls exist only for the mocked features they act on (and only in __DEV__).
  const authMocked = config.mocks.has('auth');
  const kycMocked = config.mocks.has('kyc');
  const canFail = authMocked || kycMocked || config.mocks.has('onboarding');
  const devPanel = __DEV__ && canFail;
  const onDev = () => {
    if (devPanel) setDev(true);
  };
  // The authenticated area sees only this narrow session interface, never the journey hook.
  const sessionValue: SessionValue | null = j.profile
    ? {
        profile: j.profile,
        sync: j.sync,
        // Explicit sign-out also clears the unlock preference and marker.
        signOut: () => {
          void (async () => {
            await j.signOut();
          })();
        },
        busy: !!j.busy,
        error: j.error,
        onDev,
        appLock: {
          enabled: lock.enabledFor(subject),
          method: lock.settings.method,
          timeoutSeconds: lock.timeoutSeconds,
          enable: () => lock.enable(j.profile!.identity.subject),
          disable: lock.disable,
          available: async () => (await availableUnlockMethod()) !== null,
        },
      }
    : null;
  let screen;
  if (j.busy && !j.profile && !j.error && !['createAccount', 'signIn'].includes(j.route))
    screen = (
      <Screen brand>
        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', gap: 20 }}>
          <ActivityIndicator size="large" color={c.brand} />
          <Copy muted>{t(j.busy)}</Copy>
        </View>
      </Screen>
    );
  else if (j.route === 'welcome') screen = <Welcome journey={j} onDev={onDev} />;
  else if (authMocked && ['createAccount', 'signIn'].includes(j.route))
    screen = <DemoAuth key={j.route} journey={j} onDev={onDev} />;
  else if (
    ['intro', 'details', 'review', 'onboardingConflict', 'onboardingUnavailable'].includes(j.route)
  )
    screen = <Onboarding journey={j} />;
  else if (j.route === 'kycIntro') screen = <Kyc journey={j} />;
  else if (j.route === 'home' && sessionValue)
    screen = (
      <SessionProvider value={sessionValue}>
        <AuthenticatedApp />
      </SessionProvider>
    );
  else screen = <StatusScreen journey={j} onDev={onDev} />;
  return (
    <>
      <StatusBar barStyle="dark-content" />
      <Fade id={j.route}>{screen}</Fade>
      {lock.settings.enabled &&
        lock.state.name === 'unlocked' &&
        lock.appState !== 'active' &&
        !isAutoLockSuppressed() && (
          <View style={StyleSheet.absoluteFill}>
            <PrivacyCover />
          </View>
        )}
      <UnlockOffer
        visible={!!offer && !lock.locked}
        method={offer}
        timeoutSeconds={lock.timeoutSeconds}
        onAccept={async () => {
          if (!subject) return false;
          const outcome = await lock.enable(subject);
          if (outcome === 'enabled') setOffer(null);
          return outcome === 'enabled';
        }}
        onDecline={() => {
          if (subject) void lock.offered(subject);
          setOffer(null);
        }}
      />
      <UnlockScreen
        visible={lock.paused}
        state={lock.state}
        method={lock.settings.method}
        prompting={lock.prompting}
        failuresLeft={
          lock.state.name === 'locked' ? lock.maxFailures - lock.state.failures : lock.maxFailures
        }
        busy={lockBusy}
        onUnlock={() => void lock.unlock()}
        onPassword={() => void leaveLock(true)}
        onNotNow={() => void leaveLock(false)}
      />
      {devPanel && (
        <Sheet visible={dev} title={t('devTitle')} onClose={() => setDev(false)}>
          {kycMocked &&
            (
              [
                { result: 'APPROVED', key: 'devApproved' },
                { result: 'PENDING', key: 'devPending' },
                { result: 'REJECTED', key: 'devRejected' },
                { result: 'MORE_INFO_REQUIRED', key: 'devMore' },
              ] as const
            ).map((item) => (
              <Button
                key={item.result}
                secondary
                label={t(item.key)}
                onPress={() => {
                  j.scenario(item.result);
                  setDev(false);
                }}
              />
            ))}
          {canFail && (
            <Button
              secondary
              label={t('devFailure')}
              onPress={() => {
                j.failNext();
                setDev(false);
              }}
            />
          )}
          {authMocked && (
            <>
              <Button
                secondary
                label={t('devExpired')}
                onPress={() => {
                  setDev(false);
                  void j.expire();
                }}
              />
              <Button
                label={t('devReset')}
                onPress={() => {
                  setDev(false);
                  void j.reset();
                }}
              />
            </>
          )}
        </Sheet>
      )}
    </>
  );
}
export default function App() {
  return (
    <SafeAreaProvider>
      <DevBanner mocks={config.mocks}>
        <ErrorBoundary>
          <JourneyApp />
        </ErrorBoundary>
      </DevBanner>
    </SafeAreaProvider>
  );
}
