import React, { useState } from 'react';
import { Modal, StyleSheet, View } from 'react-native';
import { Brand, Button, Copy, ErrorNotice, Icon, Intro, Screen, Sheet, c } from '../components/ui';
import { t, tf, type MessageKey } from '../locales';
import type { LoginRequiredReason, LockState } from '../lock/machine';
import type { UnlockMethod } from '../lock/device';

export function lockDuration(seconds: number): string {
  if (seconds === 60) return t('oneMinute');
  if (seconds > 0 && seconds % 60 === 0) return tf('minutesCount', { count: seconds / 60 });
  return tf('secondsCount', { count: seconds });
}

const loginRequiredBody: Record<LoginRequiredReason, MessageKey> = {
  tooManyAttempts: 'loginRequiredTooManyAttempts',
  lockout: 'loginRequiredLockout',
  biometricsChanged: 'loginRequiredBiometricsChanged',
  notEnrolled: 'loginRequiredNotEnrolled',
};

function LockSymbol() {
  return (
    <View style={styles.symbol}>
      <Icon name="lock" size={34} color={c.brand} />
    </View>
  );
}

// Opaque full-screen modal: it sits above every other modal (sheets opened before the lock) and,
// being its own window, hides the app underneath from TalkBack/VoiceOver. Android Back is
// swallowed (the account stays locked).
export function UnlockScreen({
  visible,
  state,
  method,
  prompting,
  failuresLeft,
  busy,
  onUnlock,
  onPassword,
  onNotNow,
}: {
  visible: boolean;
  state: LockState;
  method: UnlockMethod | null;
  prompting: boolean;
  failuresLeft: number;
  busy: boolean;
  onUnlock: () => void;
  onPassword: () => void;
  onNotNow: () => void;
}) {
  let content;
  if (state.name === 'loginRequired')
    content = (
      <Screen
        brand
        footer={
          <>
            <Button label={t('signInWithPassword')} onPress={onPassword} loading={busy} />
            <Button secondary label={t('notNow')} onPress={onNotNow} disabled={busy} />
          </>
        }
      >
        <LockSymbol />
        <Intro title={t('loginRequiredTitle')} body={t(loginRequiredBody[state.reason])} />
      </Screen>
    );
  else if (state.name === 'locked')
    content = (
      <Screen
        brand
        footer={
          <>
            <ErrorNotice
              message={
                state.notice === 'cancelled'
                  ? t('unlockCancelled')
                  : state.notice === 'failed'
                    ? tf('unlockFailed', { left: failuresLeft })
                    : null
              }
            />
            <Button label={t('unlock')} onPress={onUnlock} loading={prompting} disabled={busy} />
            <Button
              secondary
              label={t('signInWithPassword')}
              onPress={onPassword}
              disabled={prompting}
              loading={busy}
            />
          </>
        }
      >
        <LockSymbol />
        <Intro
          title={t('lockedTitle')}
          body={t(method === 'biometric' ? 'lockedBodyBiometric' : 'lockedBodyDevice')}
        />
      </Screen>
    );
  return (
    <Modal
      visible={visible}
      animationType="none"
      transparent={false}
      statusBarTranslucent
      onRequestClose={() => undefined}
    >
      <View style={styles.fill}>{content}</View>
    </Modal>
  );
}

// Shown over the app while it is in the background with unlock on (iOS app switcher snapshot;
// on Android 13+ the recents snapshot is disabled natively, see plugins/withRecentsPrivacy.js).
export function PrivacyCover() {
  return (
    <View style={[styles.fill, styles.cover]} accessibilityElementsHidden>
      <Brand />
    </View>
  );
}

export function UnlockOffer({
  visible,
  method,
  timeoutSeconds,
  onAccept,
  onDecline,
}: {
  visible: boolean;
  method: UnlockMethod | null;
  timeoutSeconds: number;
  onAccept: () => Promise<boolean>;
  onDecline: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const accept = async () => {
    setBusy(true);
    setFailed(false);
    try {
      if (!(await onAccept())) setFailed(true);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Sheet
      visible={visible}
      title={t(method === 'biometric' ? 'offerTitleBiometric' : 'offerTitleDevice')}
      onClose={onDecline}
    >
      <Copy muted>{tf('offerBody', { minutes: lockDuration(timeoutSeconds) })}</Copy>
      <ErrorNotice message={failed ? t('unlockNotEnabled') : null} />
      <Button label={t('turnOn')} onPress={() => void accept()} loading={busy} />
      <Button secondary label={t('notNow')} onPress={onDecline} disabled={busy} />
    </Sheet>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1, backgroundColor: c.background },
  cover: { alignItems: 'center', justifyContent: 'center' },
  symbol: {
    width: 80,
    height: 80,
    borderRadius: 40,
    backgroundColor: c.paleBlue,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 28,
  },
});
