import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { Badge, ErrorNotice, Icon, InfoRow, Screen, TextButton, c } from '../components/ui';
import { AccountDetailsSheet } from '../components/AccountDetails';
import { theme } from '../theme';
import { locale, t, type MessageKey } from '../locales';
import { services } from '../services';
import { AppError, errorKey } from '../services/errors';
import { useSession } from '../session/SessionContext';
import { displayName } from '../utils/idToken';

function formatDate(iso: string | null): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return null;
  try {
    return new Intl.DateTimeFormat(locale, {
      day: 'numeric',
      month: 'long',
      year: 'numeric',
    }).format(date);
  } catch {
    return iso.slice(0, 10);
  }
}

// Real data from GET /api/profile (contract §11), re-read on every focus. The fresh profile is
// fed back to the gate, so a suspension leaves the authenticated area immediately. The receiving
// details live in the shared Account details sheet (also opened from Home), not repeated here.
export function Profile() {
  const session = useSession();
  const navigation = useNavigation();
  const [loadError, setLoadError] = useState<MessageKey | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [details, setDetails] = useState(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  // Ref, not a dependency: sync() updates the profile, which must not re-trigger the focus read.
  const sync = useRef(session.sync);
  sync.current = session.sync;
  const reload = useCallback(async (pull = false) => {
    if (pull) setRefreshing(true);
    try {
      const next = await services.account.get();
      if (!mounted.current) return;
      setLoadError(null);
      sync.current(next);
    } catch (cause) {
      if (cause instanceof AppError && cause.expired) return;
      if (mounted.current) setLoadError(errorKey(cause, 'unexpectedError'));
    } finally {
      if (mounted.current && pull) setRefreshing(false);
    }
  }, []);
  useFocusEffect(
    useCallback(() => {
      void reload();
    }, [reload]),
  );
  const profile = session.profile;
  const customer = profile.customer;
  const name = displayName(profile.identity.givenName, profile.identity.email) || t('brand');
  const memberSince = formatDate(customer?.onboardedAt ?? null);
  const accountTone =
    profile.account === 'ACTIVE'
      ? 'success'
      : profile.account === 'PENDING_KYC'
        ? 'warning'
        : 'error';
  const chevron = <Icon name="chevron-right" color={c.muted} size={20} />;
  return (
    <Screen
      brand
      onBrandLongPress={session.onDev}
      refreshControl={
        <RefreshControl
          refreshing={refreshing}
          onRefresh={() => void reload(true)}
          colors={[c.brand]}
          tintColor={c.brand}
        />
      }
    >
      <View style={styles.identity}>
        <View style={styles.profileAvatar}>
          <Text style={styles.avatarText}>{name.slice(0, 1).toUpperCase()}</Text>
        </View>
        <View style={styles.identityText}>
          <Text accessibilityRole="header" numberOfLines={2} style={styles.profileName}>
            {name}
          </Text>
          <Badge tone={accountTone} label={t(`status${profile.account}` as const)} />
        </View>
      </View>
      <ErrorNotice message={loadError && t(loadError)} />

      <View style={styles.list}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`${t('accountDetails')}. ${t('accountDetailsBody')}`}
          onPress={() => setDetails(true)}
          style={({ pressed }) => pressed && styles.pressed}
        >
          <InfoRow
            icon="file-text"
            title={t('accountDetails')}
            description={t('accountDetailsBody')}
            end={chevron}
          />
        </Pressable>
        <InfoRow
          icon="shield"
          title={t('kycStatus')}
          description={t(`kyc${profile.kyc}` as const)}
        />
        {customer?.phoneNumber ? (
          <InfoRow icon="phone" title={t('phone')} description={customer.phoneNumber} />
        ) : null}
        {memberSince ? (
          <InfoRow icon="calendar" title={t('memberSince')} description={memberSince} />
        ) : null}
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('security')}
          onPress={() => navigation.navigate('Security')}
          style={({ pressed }) => pressed && styles.pressed}
        >
          <InfoRow
            icon="lock"
            title={t('security')}
            description={t('securityBody')}
            end={chevron}
          />
        </Pressable>
      </View>

      {/* Discreet, at the end of the list; not pinned to the bottom of the screen. */}
      <View style={styles.signOut}>
        <ErrorNotice message={session.error && t(session.error)} />
        {session.busy ? (
          <ActivityIndicator color={c.brand} />
        ) : (
          <TextButton label={t('signOut')} onPress={session.signOut} />
        )}
      </View>
      <AccountDetailsSheet visible={details} onClose={() => setDetails(false)} />
    </Screen>
  );
}

const sp = theme.space;
const type = theme.text;
const styles = StyleSheet.create({
  pressed: { opacity: 0.65 },
  identity: { flexDirection: 'row', alignItems: 'center', gap: sp.lg, marginBottom: sp.lg },
  identityText: { flex: 1, gap: sp.sm, alignItems: 'flex-start' },
  profileAvatar: {
    width: 64,
    height: 64,
    borderRadius: theme.radius.pill,
    backgroundColor: c.paleBlue,
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarText: { ...type.title, color: c.brand },
  profileName: { ...type.title, color: c.ink },
  list: { marginTop: sp.sm },
  signOut: { marginTop: sp.xl, gap: sp.sm, alignItems: 'center' },
});
