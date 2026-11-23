import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  AppState,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  View,
  type NativeScrollEvent,
  type NativeSyntheticEvent,
} from 'react-native';
import {
  useFocusEffect,
  useIsFocused,
  useNavigation,
  type NavigationProp,
} from '@react-navigation/native';
import {
  Brand,
  Button,
  Copy,
  ErrorNotice,
  Icon,
  IconButton,
  Screen,
  Sheet,
  Skeleton,
  c,
  type IconName,
} from '../components/ui';
import { theme } from '../theme';
import { locale, t, tf, type MessageKey } from '../locales';
import { config, isSandbox } from '../config';
import { services } from '../services';
import { AppError, errorKey } from '../services/errors';
import { useSession } from '../session/SessionContext';
import type { Currency, WalletsResponse } from '../api/contract';
import type { AppStackParamList } from '../navigation/types';
import {
  availableText,
  pendingText,
  walletsViewModel,
  type WalletItem,
} from './home/walletsViewModel';
import { RecentActivity } from './home/RecentActivity';
import { Amount } from '../components/money';
import { AccountDetailsSheet } from '../components/AccountDetails';
import { displayName } from '../utils/idToken';
import { formatMoney } from '../money';
import { pendingTransferOf } from '../transfers';
import { fxAvailable, pendingConversionOf } from '../fx';
import type { PendingConversion } from '../fx/engine';
import { selectionHaptic } from '../utils/haptics';
import { useUnreadCount } from '../notifications/hooks';
import { badgeText } from '../notifications/view';
import type { PendingTransfer } from '../transfers/engine';

type Action = {
  label: MessageKey;
  icon: IconName;
  primary?: boolean;
  // A route to open, or 'details' for the Account details sheet.
  go: ((navigate: Navigate, currency?: Currency) => void) | 'details';
};
type Navigate = NavigationProp<AppStackParamList>['navigate'];
// Send is real. Convert (FX demo) and Add money (example deposit instructions) are sandbox
// previews; a production build shows "Not available yet" for both (AuthenticatedApp). History is
// the Activity tab and Recent activity below; receiving details live in the Details sheet.
const actions: Action[] = [
  { label: 'add', icon: 'plus', go: (nav, currency) => nav('TopUp', { currency }) },
  {
    label: 'send',
    icon: 'arrow-up-right',
    primary: true,
    go: (nav, currency) => nav('Send', { currency }),
  },
  { label: 'convert', icon: 'repeat', go: (nav, currency) => nav('Convert', { from: currency }) },
  // Receiving details (GitHub criterion D1 "receive"), shared with Profile.
  { label: 'details', icon: 'file-text', go: 'details' },
];
// Presentation only, keyed by ISO 4217 code. Which wallets exist and their balances come
// exclusively from GET /api/wallets.
const currencyStyle: Record<
  Currency,
  { name: MessageKey; symbol: string; tint: string; color: string }
> = {
  EUR: { name: 'eur', symbol: '€', tint: c.paleBlue, color: c.brand },
  USD: { name: 'usd', symbol: '$', tint: c.paleSuccess, color: c.success },
  GBP: { name: 'gbp', symbol: '£', tint: c.paleBlue, color: c.blue },
  AED: { name: 'aed', symbol: 'Dh', tint: c.paleWarning, color: c.warning },
  MUR: { name: 'mur', symbol: 'Rs', tint: c.paleWarning, color: c.warning },
  TND: { name: 'tnd', symbol: 'DT', tint: c.paleSuccess, color: c.success },
};
const HIDDEN = '••••';
// Wallet carousel: space between cards, and how much of the next card shows at the edge.
const CARD_GAP = 16;
const CARD_PEEK = 20;

function formatTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  try {
    return new Intl.DateTimeFormat(locale, {
      day: 'numeric',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
    }).format(date);
  } catch {
    return date.toISOString();
  }
}

type Load =
  | { status: 'loading' }
  | { status: 'error'; key: MessageKey }
  | { status: 'ready'; data: WalletsResponse; staleKey: MessageKey | null };

function CurrencyBadge({ currency, size = 32 }: { currency: Currency; size?: number }) {
  const look = currencyStyle[currency];
  return (
    <View
      style={[
        styles.badge,
        { width: size, height: size, borderRadius: size / 2, backgroundColor: look.tint },
      ]}
    >
      <Text style={[styles.badgeSymbol, { color: look.color }]} numberOfLines={1}>
        {look.symbol}
      </Text>
    </View>
  );
}

export function Home() {
  const session = useSession();
  const navigation = useNavigation<NavigationProp<AppStackParamList>>();
  const focused = useIsFocused();
  const [load, setLoad] = useState<Load>({ status: 'loading' });
  const [refreshing, setRefreshing] = useState(false);
  const [selectedCode, setSelectedCode] = useState<Currency | null>(null);
  const [hidden, setHidden] = useState(false);
  const [picker, setPicker] = useState(false);
  const [details, setDetails] = useState(false);
  // Width of the content column the card sits in (the page gutter is outside it).
  const [rowWidth, setRowWidth] = useState(0);
  const [refreshToken, setRefreshToken] = useState(0);
  const [pendingTransfer, setPendingTransfer] = useState<PendingTransfer | null>(null);
  const cards = useRef<ScrollView>(null);
  const userId = session.profile.customer?.userId ?? null;
  // Unread notifications on the newest page (device-side read state, contract §13).
  const unread = useUnreadCount(userId);
  // A transfer whose outcome is not confirmed yet (persisted by the Send flow).
  // A conversion whose outcome is not confirmed yet (persisted by the Convert flow).
  const [pendingConversion, setPendingConversion] = useState<PendingConversion | null>(null);
  const readPending = useCallback(async () => {
    const pending = userId ? await pendingTransferOf(userId) : null;
    const conversion = userId && fxAvailable() ? await pendingConversionOf(userId) : null;
    if (!mounted.current) return;
    setPendingTransfer(pending);
    setPendingConversion(conversion);
  }, [userId]);
  const requestId = useRef(0);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  // Always re-read from the backend; never update balances optimistically.
  const fetchWallets = useCallback(async (pull = false) => {
    const id = ++requestId.current;
    if (pull) setRefreshing(true);
    try {
      const data = await services.wallets.list();
      if (mounted.current && id === requestId.current)
        setLoad({ status: 'ready', data, staleKey: null });
    } catch (cause) {
      // Session expiry is handled globally (the gate returns to welcome).
      if (cause instanceof AppError && cause.expired) return;
      if (!mounted.current || id !== requestId.current) return;
      const key = errorKey(cause, 'unexpectedError');
      // Keep the last good balances visible, explicitly marked as possibly out of date.
      setLoad((previous) =>
        previous.status === 'ready' ? { ...previous, staleKey: key } : { status: 'error', key },
      );
    } finally {
      if (mounted.current && pull) setRefreshing(false);
    }
  }, []);

  // On every focus: first open, back from Send/TopUp/Convert/..., tab switches.
  useFocusEffect(
    useCallback(() => {
      void fetchWallets();
      void readPending();
    }, [fetchWallets, readPending]),
  );
  // Back to the foreground while Home is visible.
  useEffect(() => {
    const listener = AppState.addEventListener('change', (state) => {
      if (state === 'active' && focused) void fetchWallets();
    });
    return () => listener.remove();
  }, [focused, fetchWallets]);

  const view = load.status === 'ready' ? walletsViewModel(load.data) : null;
  const items = view?.items ?? [];
  const selectedIndex = Math.max(
    0,
    items.findIndex((item) => item.currency === selectedCode),
  );
  const selected = items[selectedIndex];
  // First name from the ID token (display only), else the email local part; never the email.
  const name =
    displayName(session.profile.identity.givenName, session.profile.identity.email) || t('brand');
  const pull = () => {
    void fetchWallets(true);
    void readPending();
    setRefreshToken((value) => value + 1);
  };
  const amountOf = (item?: WalletItem) =>
    !item ? '—' : hidden && item.available.kind === 'amount' ? HIDDEN : availableText(item, locale);
  const pendingOf = (item?: WalletItem) => (!item || hidden ? null : pendingText(item, locale));
  const labelOf = (item: WalletItem) => {
    const look = currencyStyle[item.currency];
    const amount =
      item.available.kind === 'settingUp'
        ? t('settingUpLabel')
        : hidden
          ? t('balancesHidden')
          : availableText(item, locale);
    const pending = pendingOf(item);
    return [item.currency, t(look.name), amount, pending].filter(Boolean).join(', ');
  };

  // The card is the wallet switcher: swipe between wallets, or pick one from the sheet. With
  // several wallets the carousel bleeds into the right gutter: cards are CARD_GAP apart and the
  // next one peeks CARD_PEEK at the screen edge. One swipe moves exactly one card (step).
  const carousel = items.length > 1;
  const cardWidth = carousel
    ? Math.max(0, rowWidth + theme.sizes.gutter - CARD_GAP - CARD_PEEK)
    : rowWidth;
  const step = cardWidth + CARD_GAP;
  const onSwipe = (event: NativeSyntheticEvent<NativeScrollEvent>) => {
    if (!step) return;
    const index = Math.round(event.nativeEvent.contentOffset.x / step);
    const item = items[Math.min(Math.max(index, 0), items.length - 1)];
    if (item && item.currency !== selected?.currency) {
      selectionHaptic();
      setSelectedCode(item.currency);
    }
  };
  const choose = (currency: Currency) => {
    const index = items.findIndex((item) => item.currency === currency);
    if (currency !== selected?.currency) selectionHaptic();
    setSelectedCode(currency);
    setPicker(false);
    if (index >= 0) cards.current?.scrollTo({ x: index * step, animated: false });
  };

  const card = (item: WalletItem | undefined, current: boolean) => (
    <View
      key={item?.currency ?? 'empty'}
      style={[styles.card, { width: cardWidth }]}
      // Only the visible card is exposed to screen readers; the currency button switches wallets.
      importantForAccessibility={current ? 'auto' : 'no-hide-descendants'}
      accessibilityElementsHidden={!current}
    >
      <View style={styles.cardTop}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={
            item
              ? tf('chooseCurrencyFor', {
                  currency: item.currency,
                  name: t(currencyStyle[item.currency].name),
                })
              : t('chooseCurrency')
          }
          disabled={!item}
          onPress={() => setPicker(true)}
          style={({ pressed }) => [styles.currencyButton, pressed && styles.cardPressed]}
        >
          {item && <CurrencyBadge currency={item.currency} size={28} />}
          <Text style={styles.currencyCode}>{item?.currency ?? '—'}</Text>
          {item && <Icon name="chevron-down" color={c.onDarkSecondary} size={18} />}
        </Pressable>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t(hidden ? 'showBalances' : 'hideBalances')}
          onPress={() => setHidden((value) => !value)}
          style={({ pressed }) => [styles.visibilityButton, pressed && styles.cardPressed]}
        >
          <Icon name={hidden ? 'eye-off' : 'eye'} color={c.onDarkSecondary} size={20} />
        </Pressable>
      </View>
      <Text style={styles.balanceLabel}>{t('available')}</Text>
      {load.status === 'loading' ? (
        <View
          style={styles.amountSkeleton}
          accessible
          accessibilityRole="progressbar"
          accessibilityLabel={t('loadingShort')}
        >
          <Skeleton dark width="55%" height={40} radius={8} />
        </View>
      ) : (
        <Amount
          text={amountOf(item)}
          accessibilityLabel={item ? labelOf(item) : '—'}
          live={current}
          style={[styles.amount, item?.available.kind === 'settingUp' && styles.amountMuted]}
          minorStyle={styles.amountMinor}
        />
      )}
      <View style={styles.cardBottom}>
        <Text style={styles.balanceHint} numberOfLines={1}>
          {item ? t(currencyStyle[item.currency].name) : ''}
          {pendingOf(item) ? ` · ${pendingOf(item)}` : ''}
        </Text>
        {load.status === 'ready' && load.staleKey && (
          <Text style={styles.balanceHint}>
            {t('lastUpdated')} {formatTime(load.data.asOf)}
          </Text>
        )}
      </View>
    </View>
  );

  return (
    <Screen
      refreshControl={
        <RefreshControl
          refreshing={refreshing}
          onRefresh={pull}
          colors={[c.brand]}
          tintColor={c.brand}
        />
      }
      header={
        <View style={styles.homeHeader}>
          <Brand onLongPress={session.onDev} />
          <View style={styles.headerActions}>
            <IconButton
              icon="bell"
              label={
                unread > 0 ? tf('notificationsWithUnread', { count: unread }) : t('notifications')
              }
              badge={badgeText(unread)}
              onPress={() => navigation.navigate('Notifications')}
            />
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={t('profile')}
              onPress={() => navigation.navigate('Tabs', { screen: 'Profile' })}
              style={({ pressed }) => [styles.avatar, pressed && styles.pressed]}
            >
              <Text style={styles.avatarText}>{name.slice(0, 1).toUpperCase()}</Text>
            </Pressable>
          </View>
        </View>
      }
    >
      <View style={styles.greeting}>
        <View style={styles.flex}>
          <Text style={styles.hello}>{t('hello')}</Text>
          <Text numberOfLines={2} style={styles.name}>
            {name}
          </Text>
        </View>
        {isSandbox(config) && (
          <View accessible accessibilityLabel={t('sandboxLabel')} style={styles.sandbox}>
            <View style={styles.sandboxDot} />
            <Text style={styles.sandboxText}>{t('sandbox')}</Text>
          </View>
        )}
      </View>

      {pendingConversion && (
        <Pressable
          accessibilityRole="button"
          onPress={() => navigation.navigate('Convert', { from: pendingConversion.sourceCurrency })}
          style={({ pressed }) => [styles.banner, pressed && styles.pressed]}
        >
          <Icon name="clock" color={c.warning} size={20} />
          <View style={styles.flex}>
            <Text style={styles.bannerTitle}>{t('fxPendingBanner')}</Text>
            <Text style={styles.bannerBody}>
              {tf('fxPendingBannerBody', {
                amount: formatMoney(
                  pendingConversion.sourceMinor,
                  pendingConversion.sourceCurrency,
                  locale,
                ),
                currency: pendingConversion.destinationCurrency,
              })}
            </Text>
          </View>
          <Icon name="chevron-right" color={c.muted} size={20} />
        </Pressable>
      )}

      {pendingTransfer && (
        <Pressable
          accessibilityRole="button"
          onPress={() => navigation.navigate('Send', { currency: pendingTransfer.currency })}
          style={({ pressed }) => [styles.banner, pressed && styles.pressed]}
        >
          <Icon name="clock" color={c.warning} size={20} />
          <View style={styles.flex}>
            <Text style={styles.bannerTitle}>{t('pendingTransferBanner')}</Text>
            <Text style={styles.bannerBody}>
              {tf('pendingTransferBannerBody', {
                amount: formatMoney(pendingTransfer.amountMinor, pendingTransfer.currency, locale),
                recipient: pendingTransfer.maskedEmail,
              })}
            </Text>
          </View>
          <Icon name="chevron-right" color={c.muted} size={20} />
        </Pressable>
      )}

      {view?.banner && (
        <View accessibilityRole="summary" style={styles.banner}>
          <Icon
            name={view.banner === 'provisioning' ? 'loader' : 'info'}
            color={c.warning}
            size={20}
          />
          <View style={styles.flex}>
            <Text style={styles.bannerTitle}>
              {t(
                view.banner === 'provisioning'
                  ? 'walletsProvisioningTitle'
                  : 'walletsNotEligibleTitle',
              )}
            </Text>
            <Text style={styles.bannerBody}>
              {t(
                view.banner === 'provisioning'
                  ? 'walletsProvisioningBody'
                  : 'walletsNotEligibleBody',
              )}
            </Text>
          </View>
        </View>
      )}

      <View onLayout={(event) => setRowWidth(event.nativeEvent.layout.width)}>
        {rowWidth > 0 &&
          (carousel ? (
            <ScrollView
              ref={cards}
              horizontal
              snapToInterval={step}
              snapToAlignment="start"
              decelerationRate="fast"
              disableIntervalMomentum
              showsHorizontalScrollIndicator={false}
              // Not `contentOffset`: on Android it also scrolls the page down past the greeting.
              onContentSizeChange={() =>
                cards.current?.scrollTo({ x: selectedIndex * step, animated: false })
              }
              onMomentumScrollEnd={onSwipe}
              style={styles.cardScroller}
              // Start aligned with the page content; the end padding lets the last card snap
              // to the same position as the others.
              contentContainerStyle={styles.cardTrack}
            >
              {items.map((item, index) => card(item, index === selectedIndex))}
            </ScrollView>
          ) : (
            card(selected, true)
          ))}
      </View>
      {items.length > 1 && (
        <View style={styles.dots} importantForAccessibility="no-hide-descendants">
          {items.map((item, index) => (
            <View
              key={item.currency}
              style={[styles.dot, index === selectedIndex && styles.dotActive]}
            />
          ))}
        </View>
      )}

      {load.status === 'ready' && load.staleKey && (
        <View style={styles.notice}>
          <ErrorNotice message={`${t(load.staleKey)} ${t('showingLastKnown')}`} />
          <Button secondary label={t('retry')} onPress={() => void fetchWallets(true)} />
        </View>
      )}
      {load.status === 'error' && (
        <View style={styles.notice}>
          <ErrorNotice message={t(load.key)} />
          <Button secondary label={t('retry')} onPress={() => void fetchWallets(true)} />
        </View>
      )}
      {load.status === 'ready' && items.length === 0 && (
        <View style={styles.notice}>
          <Copy muted>{t('noWallets')}</Copy>
        </View>
      )}

      <View style={styles.actions}>
        {actions.map((action) => (
          <Pressable
            key={action.label}
            accessibilityRole="button"
            accessibilityLabel={t(action.label)}
            onPress={() =>
              action.go === 'details'
                ? setDetails(true)
                : action.go(navigation.navigate, selected?.currency)
            }
            style={({ pressed }) => [styles.action, pressed && styles.pressed]}
          >
            <View style={[styles.actionIcon, action.primary && styles.primaryAction]}>
              <Icon name={action.icon} color={action.primary ? c.onBrand : c.brand} size={22} />
            </View>
            <Text style={styles.actionLabel} numberOfLines={1}>
              {t(action.label)}
            </Text>
          </Pressable>
        ))}
      </View>

      {selected && !config.mocks.has('auth') && (
        <RecentActivity currency={selected.currency} refreshToken={refreshToken} />
      )}

      <AccountDetailsSheet visible={details} onClose={() => setDetails(false)} />
      <Sheet visible={picker} title={t('chooseCurrency')} onClose={() => setPicker(false)}>
        <View>
          {items.map((item) => {
            const isSelected = item.currency === selected?.currency;
            return (
              <Pressable
                key={item.currency}
                accessibilityRole="button"
                accessibilityLabel={labelOf(item)}
                accessibilityState={{ selected: isSelected }}
                onPress={() => choose(item.currency)}
                style={({ pressed }) => [styles.pickerRow, pressed && styles.pressed]}
              >
                <CurrencyBadge currency={item.currency} size={40} />
                <View style={styles.flex}>
                  <Text style={styles.pickerCode}>{item.currency}</Text>
                  <Text style={styles.pickerName}>{t(currencyStyle[item.currency].name)}</Text>
                </View>
                <Text
                  numberOfLines={1}
                  style={[
                    styles.pickerAmount,
                    item.available.kind === 'settingUp' && styles.pickerSettingUp,
                  ]}
                >
                  {amountOf(item)}
                </Text>
                <View style={styles.check}>
                  {isSelected && <Icon name="check" color={c.brand} size={20} />}
                </View>
              </Pressable>
            );
          })}
        </View>
      </Sheet>
    </Screen>
  );
}

const sp = theme.space;
const type = theme.text;
const styles = StyleSheet.create({
  flex: { flex: 1 },
  homeHeader: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: sp.md,
  },
  headerActions: { flexDirection: 'row', alignItems: 'center', gap: sp.sm },
  pressed: { opacity: 0.65 },
  greeting: { flexDirection: 'row', gap: sp.sm, alignItems: 'center', marginBottom: sp.xl },
  hello: { ...type.caption, color: c.muted },
  name: { ...type.title, color: c.ink },
  sandbox: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: sp.md,
    paddingVertical: 6,
    borderRadius: theme.radius.pill,
    backgroundColor: c.paleWarning,
  },
  sandboxDot: { width: 6, height: 6, borderRadius: 3, backgroundColor: c.warning },
  sandboxText: { ...type.micro, color: c.warning, fontWeight: '600' },
  banner: {
    flexDirection: 'row',
    gap: sp.md,
    padding: sp.lg,
    borderRadius: theme.radius.md,
    backgroundColor: c.paleWarning,
    marginBottom: sp.lg,
  },
  bannerTitle: { ...type.bodyStrong, color: c.ink, marginBottom: 2 },
  bannerBody: { ...type.caption, color: c.muted },
  avatar: {
    width: theme.sizes.touch,
    height: theme.sizes.touch,
    borderRadius: theme.radius.pill,
    backgroundColor: c.paleWarning,
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarText: { ...type.headline, fontSize: 20, color: c.brand },
  cardScroller: { marginHorizontal: -theme.sizes.gutter },
  cardTrack: {
    gap: CARD_GAP,
    paddingStart: theme.sizes.gutter,
    paddingEnd: CARD_GAP + CARD_PEEK,
  },
  card: { backgroundColor: c.ink, padding: sp.xl, borderRadius: theme.radius.lg },
  cardTop: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: sp.sm,
    marginTop: -sp.sm,
    marginHorizontal: -sp.sm,
  },
  currencyButton: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: sp.sm,
    minHeight: theme.sizes.minTouch,
    paddingHorizontal: sp.sm,
    borderRadius: theme.radius.md,
  },
  cardPressed: { backgroundColor: c.onDarkBorder },
  currencyCode: { ...type.bodyStrong, color: c.onBrand, fontWeight: '600' },
  visibilityButton: {
    width: theme.sizes.minTouch,
    height: theme.sizes.minTouch,
    borderRadius: theme.radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
  },
  balanceLabel: { ...type.caption, color: c.onDarkSecondary, marginTop: sp.md },
  amountSkeleton: { height: 56, justifyContent: 'center', marginTop: sp.xs },
  // Large and light, with smaller minor units; tabular figures so digits do not jump.
  amount: {
    color: c.onBrand,
    fontSize: 46,
    lineHeight: 56,
    fontWeight: '300',
    letterSpacing: -1,
    fontVariant: ['tabular-nums'],
    marginTop: sp.xs,
  },
  amountMinor: { fontSize: 26, letterSpacing: -0.3 },
  amountMuted: { ...type.title, fontWeight: '400', color: c.onDarkSecondary, lineHeight: 56 },
  cardBottom: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: sp.sm,
    // The card's one gold accent (brand mark colour); gold is used nowhere else but the tab bar.
    borderTopWidth: StyleSheet.hairlineWidth * 2,
    borderTopColor: c.gold,
    paddingTop: sp.md,
    marginTop: sp.lg,
  },
  balanceHint: { ...type.micro, fontWeight: '400', color: c.onDarkSecondary, flexShrink: 1 },
  dots: {
    flexDirection: 'row',
    justifyContent: 'center',
    gap: 6,
    marginTop: sp.md,
  },
  dot: { width: 6, height: 6, borderRadius: 3, backgroundColor: c.border },
  dotActive: { width: 18, backgroundColor: c.brand },
  notice: { gap: sp.sm, marginTop: sp.lg },
  actions: { flexDirection: 'row', gap: sp.sm, marginTop: sp.xl },
  action: {
    flex: 1,
    alignItems: 'center',
    gap: sp.sm,
    paddingVertical: sp.xs,
    borderRadius: theme.radius.md,
  },
  actionIcon: {
    width: 52,
    height: 52,
    borderRadius: theme.radius.pill,
    backgroundColor: c.paleBlue,
    alignItems: 'center',
    justifyContent: 'center',
  },
  primaryAction: { backgroundColor: c.brand },
  actionLabel: { ...type.micro, color: c.ink, textAlign: 'center' },
  badge: { alignItems: 'center', justifyContent: 'center' },
  badgeSymbol: { fontSize: 13, fontWeight: '600' },
  pickerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: sp.md,
    minHeight: 64,
    paddingVertical: sp.sm,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: c.border,
  },
  pickerCode: { ...type.bodyStrong, color: c.ink },
  pickerName: { ...type.caption, color: c.muted },
  pickerAmount: { ...type.bodyStrong, color: c.ink, fontVariant: ['tabular-nums'], flexShrink: 1 },
  pickerSettingUp: { ...type.caption, color: c.muted, fontStyle: 'italic' },
  check: { width: 24, alignItems: 'center' },
});
