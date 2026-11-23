import React, { useCallback, useRef, useState } from 'react';
import { StyleSheet, Switch, View } from 'react-native';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { Button, Copy, ErrorNotice, InfoRow, Intro, Screen, c } from '../components/ui';
import { t, tf, type MessageKey } from '../locales';
import { useSession } from '../session/SessionContext';
import { lockDuration } from './Unlock';

// Profile → Security: the app-unlock toggle plus how sign-in and transfers are protected.
export function Security() {
  const navigation = useNavigation();
  const { appLock } = useSession();
  const [available, setAvailable] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<MessageKey | null>(null);
  const mounted = useRef(true);
  const lockRef = useRef(appLock);
  lockRef.current = appLock;
  useFocusEffect(
    useCallback(() => {
      mounted.current = true;
      void lockRef.current.available().then((value) => {
        if (mounted.current) setAvailable(value);
      });
      return () => {
        mounted.current = false;
      };
    }, []),
  );
  const toggle = async (on: boolean) => {
    if (busy) return;
    setBusy(true);
    setNotice(null);
    try {
      if (!on) {
        await appLock.disable();
        return;
      }
      const outcome = await appLock.enable();
      if (!mounted.current) return;
      if (outcome === 'unavailable') {
        setAvailable(false);
        setNotice('unlockUnavailable');
      } else if (outcome !== 'enabled') setNotice('unlockNotEnabled');
    } finally {
      if (mounted.current) setBusy(false);
    }
  };
  const status = appLock.enabled
    ? t(appLock.method === 'biometric' ? 'unlockSettingOnBiometric' : 'unlockSettingOnDevice')
    : t('unlockSettingOff');
  const back = () => navigation.goBack();
  return (
    <Screen brand back={back} footer={<Button secondary label={t('back')} onPress={back} />}>
      <Intro title={t('security')} body={t('securityBody')} />
      <View style={styles.setting}>
        <View style={styles.settingText}>
          <Copy style={styles.settingTitle}>{t('unlockSetting')}</Copy>
          <Copy muted>{status}</Copy>
        </View>
        <Switch
          accessibilityLabel={t('unlockSetting')}
          accessibilityHint={tf('unlockSettingBody', {
            minutes: lockDuration(appLock.timeoutSeconds),
          })}
          value={appLock.enabled}
          disabled={busy || (!appLock.enabled && available === false)}
          onValueChange={(value) => void toggle(value)}
          trackColor={{ true: c.blue, false: c.disabled }}
          thumbColor={c.surface}
        />
      </View>
      <Copy muted style={styles.settingBody}>
        {!appLock.enabled && available === false
          ? t('unlockUnavailable')
          : tf('unlockSettingBody', { minutes: lockDuration(appLock.timeoutSeconds) })}
      </Copy>
      <ErrorNotice message={notice && t(notice)} />
      <InfoRow icon="globe" title={t('securitySignIn')} description={t('securitySignInBody')} />
      <InfoRow
        icon="shield"
        title={t('securityTransfers')}
        description={t('securityTransfersBody')}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  setting: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 16,
    paddingVertical: 16,
    borderTopWidth: 1,
    borderBottomWidth: 1,
    borderColor: c.border,
  },
  settingText: { flex: 1, gap: 2 },
  settingTitle: { fontWeight: '600', color: c.ink },
  settingBody: { marginTop: 12, marginBottom: 24 },
});
