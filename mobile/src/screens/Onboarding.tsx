import React, { useState } from 'react';
import { Linking, Pressable, StyleSheet, View } from 'react-native';
import {
  Button,
  Copy,
  ErrorNotice,
  Field,
  Icon,
  InfoRow,
  Intro,
  Screen,
  Sheet,
  TextButton,
  c,
} from '../components/ui';
import { t } from '../locales';
import { config } from '../config';
import type { Journey } from '../hooks/useJourney';
import { validPhone } from '../navigation/state';

export function Onboarding({ journey: j }: { journey: Journey }) {
  const [terms, setTerms] = useState(false);
  const [termsError, setTermsError] = useState(false);
  const valid = validPhone(j.phone);
  // Mocked onboarding accepts the on-screen preview terms; the real endpoint needs a hosted document.
  const onboardingMocked = config.mocks.has('onboarding');
  const canAccept = onboardingMocked || /^https:\/\//.test(config.termsUrl);
  async function openTerms() {
    if (onboardingMocked) {
      setTerms(true);
      return;
    }
    try {
      if (!canAccept) throw new Error();
      await Linking.openURL(config.termsUrl);
    } catch {
      setTermsError(true);
    }
  }
  let title = t('introTitle');
  let body = t('introBody');
  if (j.route === 'details') {
    title = t('detailsTitle');
    body = t('detailsBody');
  }
  if (j.route === 'review') {
    title = t('reviewTitle');
    body = t('reviewBody');
  }
  if (j.route === 'onboardingConflict') {
    title = t('identityConflictTitle');
    body = t('identityConflictBody');
  }
  if (j.route === 'onboardingUnavailable') {
    title = t('bootstrapUnavailableTitle');
    body = t('bootstrapUnavailableBody');
  }
  const action =
    j.route === 'intro'
      ? () => j.go('details')
      : j.route === 'details'
        ? () => j.go('review')
        : j.save;
  return (
    <Screen
      revealOnKeyboard={j.route === 'details'}
      back={j.back}
      progress={j.route === 'intro' ? 0.12 : j.route === 'details' ? 0.24 : 0.38}
      footer={
        <>
          <ErrorNotice message={j.error && t(j.error)} />
          {j.route === 'onboardingConflict' || j.route === 'onboardingUnavailable' ? (
            <Button label={t('useAnotherAccount')} onPress={j.signOut} loading={!!j.busy} />
          ) : (
            <Button
              label={t(j.route === 'intro' ? 'start' : j.route === 'review' ? 'save' : 'continue')}
              onPress={action}
              loading={!!j.busy}
              disabled={
                j.route === 'details'
                  ? !valid
                  : j.route === 'review'
                    ? !j.accepted || !canAccept
                    : false
              }
            />
          )}
          {j.route !== 'onboardingConflict' && j.route !== 'onboardingUnavailable' && (
            <TextButton label={t('useAnotherAccount')} onPress={j.signOut} />
          )}
        </>
      }
    >
      <Intro title={title} body={body} />
      {j.route === 'intro' && (
        <View>
          <InfoRow icon="user" title={t('personal')} description={t('personalSub')} />
          <InfoRow icon="maximize" title={t('identity')} description={t('identitySub')} />
          <InfoRow icon="check-circle" title={t('readyLabel')} description={t('readySub')} />
          <View style={styles.note}>
            <Icon name="shield" color={c.brand} />
            <Copy muted style={{ flex: 1 }}>
              {t('secure')}
            </Copy>
          </View>
        </View>
      )}
      {j.route === 'details' && (
        <>
          <Field
            label={t('email')}
            value={j.profile?.identity.email || ''}
            hint={t('emailHint')}
            editable={false}
          />
          <Field
            label={`${t('phone')} · ${t('optional')}`}
            value={j.phone}
            onChangeText={j.setPhone}
            hint={t('phoneHint')}
            error={!valid ? t('phoneError') : undefined}
          />
        </>
      )}
      {j.route === 'review' && (
        <>
          <View style={styles.summary}>
            {[
              [t('accountType'), t('personalAccount')],
              [t('email'), j.profile?.identity.email],
              [t('phone'), j.phone.trim() || t('notProvided')],
            ].map(([label, value]) => (
              <View key={label} style={styles.summaryRow}>
                <Copy muted style={{ fontSize: 13 }}>
                  {label}
                </Copy>
                <Copy style={{ fontWeight: '500' }}>{value}</Copy>
              </View>
            ))}
            <TextButton label={t('edit')} onPress={() => j.go('details')} />
          </View>
          <Pressable
            accessibilityRole="checkbox"
            accessibilityState={{ checked: j.accepted, disabled: !canAccept }}
            onPress={() => canAccept && j.setAccepted(!j.accepted)}
            style={styles.accept}
          >
            <View
              style={[
                styles.checkbox,
                j.accepted && { backgroundColor: c.brand, borderColor: c.brand },
              ]}
            >
              {j.accepted && <Icon name="check" size={17} color={c.onBrand} />}
            </View>
            <Copy style={{ flex: 1, fontSize: 14 }}>{t('terms')}</Copy>
          </Pressable>
          <TextButton label={t('readTerms')} onPress={openTerms} />
          <ErrorNotice message={!canAccept || termsError ? t('termsUnavailable') : null} />
        </>
      )}
      <Sheet visible={terms} title={t('termsTitle')} onClose={() => setTerms(false)}>
        <Copy>{t('demoTerms')}</Copy>
        <Button label={t('gotIt')} onPress={() => setTerms(false)} />
      </Sheet>
    </Screen>
  );
}
const styles = StyleSheet.create({
  note: {
    marginTop: 32,
    paddingTop: 24,
    borderTopWidth: 1,
    borderTopColor: c.border,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  summary: { borderTopWidth: 1, borderColor: c.border },
  summaryRow: { paddingVertical: 16, borderBottomWidth: 1, borderColor: c.border, gap: 4 },
  accept: { flexDirection: 'row', alignItems: 'center', gap: 12, marginTop: 24, minHeight: 48 },
  checkbox: {
    width: 24,
    height: 24,
    borderRadius: 6,
    borderWidth: 1,
    borderColor: c.muted,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
