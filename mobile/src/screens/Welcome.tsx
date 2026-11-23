import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { Brand, Button, Copy, ErrorNotice, Icon, Screen, TextButton, c } from '../components/ui';
import { t } from '../locales';
import type { Journey } from '../hooks/useJourney';
import { config } from '../config';

export function Welcome({ journey, onDev }: { journey: Journey; onDev: () => void }) {
  const authMocked = config.mocks.has('auth');
  return (
    <Screen
      brand
      onBrandLongPress={onDev}
      footer={
        <>
          <ErrorNotice message={journey.error && t(journey.error)} />
          <Button
            label={t('start')}
            // Real auth: Keycloak registration form (prompt=create).
            onPress={() =>
              authMocked ? journey.go('createAccount') : journey.signIn({ intent: 'create' })
            }
            loading={!!journey.busy}
          />
          <TextButton
            label={t('signInNote')}
            // Real auth: Keycloak login form (prompt=login).
            onPress={() =>
              authMocked ? journey.go('signIn') : journey.signIn({ intent: 'signIn' })
            }
          />
        </>
      }
    >
      <View style={styles.hero}>
        <Copy style={styles.eyebrow}>{t('welcomeCaption')}</Copy>
        <Text accessibilityRole="header" style={styles.title}>
          {t('welcomeTitle')}
        </Text>
        <Copy muted>{t('welcomeBody')}</Copy>
      </View>
      <View style={styles.visual}>
        <View style={styles.visualTop}>
          <Brand light />
          <Icon name="arrow-up-right" color={c.onBrand} size={28} />
        </View>
        <View style={styles.lines} accessible={false}>
          {[0, 1, 2, 3, 4, 5].map((i) => (
            <View key={i} style={[styles.line, { marginStart: i * 20 }]} />
          ))}
        </View>
        <View>
          <Text style={styles.visualTitle}>{t('welcomeFeature')}</Text>
          <Text style={styles.visualSubtitle}>{t('welcomeFeatureSub')}</Text>
        </View>
      </View>
      <View style={styles.trust}>
        <Icon name="shield" size={16} color={c.muted} />
        <Copy style={styles.trustText}>{t('secure')}</Copy>
        {authMocked && <Copy style={styles.preview}>{t('demoLabel')}</Copy>}
      </View>
    </Screen>
  );
}
const styles = StyleSheet.create({
  hero: { marginBottom: 32 },
  eyebrow: { color: c.brand, fontSize: 13, fontWeight: '600', marginBottom: 16 },
  title: {
    fontSize: 39,
    lineHeight: 44,
    letterSpacing: -1.5,
    fontWeight: '600',
    color: c.ink,
    marginBottom: 20,
  },
  visual: {
    backgroundColor: c.ink,
    borderRadius: 16,
    padding: 24,
    minHeight: 255,
    overflow: 'hidden',
    justifyContent: 'space-between',
  },
  visualTop: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  visualTitle: { color: c.onBrand, fontSize: 23, lineHeight: 29, fontWeight: '500', marginTop: 40 },
  visualSubtitle: { color: c.onDarkSecondary, fontSize: 13, marginTop: 12 },
  lines: { position: 'absolute', end: -25, top: 30, transform: [{ rotate: '35deg' }] },
  line: {
    width: 100,
    height: 160,
    borderColor: c.onDarkBorder,
    borderStartWidth: 1,
    position: 'absolute',
  },
  trust: { flexDirection: 'row', gap: 8, alignItems: 'center', marginTop: 20, flexWrap: 'wrap' },
  trustText: { color: c.muted, fontSize: 12 },
  preview: { color: c.muted, fontSize: 11, marginStart: 'auto' },
});
