import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native';
import { useFocusEffect, useNavigation, type NavigationProp } from '@react-navigation/native';
import {
  Button,
  Copy,
  ErrorNotice,
  Icon,
  Screen,
  SkeletonRows,
  TextButton,
  Title,
  c,
} from '../../components/ui';
import { locale, t, type MessageKey } from '../../locales';
import { services } from '../../services';
import { AppError, errorKey } from '../../services/errors';
import { useSession } from '../../session/SessionContext';
import { findByTransactionId } from '../../history/paging';
import { FEED_PAGE_SIZE, useReadState } from '../../notifications/hooks';
import { notificationReadState } from '../../notifications/readStore';
import { isUnread, unreadCount } from '../../notifications/readState';
import { notificationView } from '../../notifications/view';
import type { Currency, NotificationItem } from '../../api/contract';
import type { AppStackParamList } from '../../navigation/types';

type Load =
  | { status: 'loading' }
  | { status: 'error'; key: MessageKey }
  | {
      status: 'ready';
      items: NotificationItem[];
      hasMore: boolean;
      nextCursor: string | null;
      loadingMore: boolean;
      moreError: MessageKey | null;
    };

// Opening a notification: look the transaction up in history (bounded) so the detail screen
// shows the real ledger item, including a REVERSED status the notification cannot know about.
type Opening =
  | { status: 'idle' }
  | { status: 'opening'; id: string }
  | { status: 'notFound'; currency: Currency }
  | { status: 'error'; key: MessageKey };

const list = (currency: Currency, cursor: string | null, pageSize: number) =>
  services.transactions.list(currency, cursor, pageSize);

// GET /api/notifications (contract §13), newest first as the server orders it (not re-sorted).
// Read/unread is device-side (src/notifications/).
export function Notifications() {
  const navigation = useNavigation<NavigationProp<AppStackParamList>>();
  const userId = useSession().profile.customer?.userId ?? null;
  const readState = useReadState(userId);
  const [load, setLoad] = useState<Load>({ status: 'loading' });
  const [refreshing, setRefreshing] = useState(false);
  const [opening, setOpening] = useState<Opening>({ status: 'idle' });
  const requestId = useRef(0);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const reload = useCallback(
    async (pull = false) => {
      const id = ++requestId.current;
      if (pull) setRefreshing(true);
      try {
        const page = await services.notifications.list(null, FEED_PAGE_SIZE);
        if (!mounted.current || id !== requestId.current) return;
        if (userId) notificationReadState.observe(userId, page.items);
        setLoad({ status: 'ready', ...page, loadingMore: false, moreError: null });
      } catch (cause) {
        if (cause instanceof AppError && cause.expired) return;
        if (!mounted.current || id !== requestId.current) return;
        setLoad({ status: 'error', key: errorKey(cause, 'unexpectedError') });
      } finally {
        if (mounted.current && pull) setRefreshing(false);
      }
    },
    [userId],
  );

  useFocusEffect(
    useCallback(() => {
      void reload();
    }, [reload]),
  );

  async function loadMore() {
    if (load.status !== 'ready' || !load.hasMore || load.loadingMore) return;
    const id = requestId.current;
    const cursor = load.nextCursor;
    setLoad({ ...load, loadingMore: true, moreError: null });
    try {
      const page = await services.notifications.list(cursor, FEED_PAGE_SIZE);
      if (!mounted.current || id !== requestId.current) return;
      setLoad((previous) =>
        previous.status === 'ready'
          ? {
              status: 'ready',
              items: [...previous.items, ...page.items],
              hasMore: page.hasMore,
              nextCursor: page.nextCursor,
              loadingMore: false,
              moreError: null,
            }
          : previous,
      );
    } catch (cause) {
      if (cause instanceof AppError && cause.expired) return;
      if (!mounted.current || id !== requestId.current) return;
      setLoad((previous) =>
        previous.status === 'ready'
          ? { ...previous, loadingMore: false, moreError: errorKey(cause, 'unexpectedError') }
          : previous,
      );
    }
  }

  async function open(item: NotificationItem) {
    if (opening.status === 'opening') return;
    if (userId) notificationReadState.markRead(userId, item);
    setOpening({ status: 'opening', id: item.id });
    try {
      const result = await findByTransactionId(list, item.currency, item.transactionId);
      if (!mounted.current) return;
      if (result.found) {
        setOpening({ status: 'idle' });
        navigation.navigate('TransactionDetail', {
          item: result.item,
          testCredit: item.testCredit,
        });
      } else setOpening({ status: 'notFound', currency: item.currency });
    } catch (cause) {
      if (cause instanceof AppError && cause.expired) return;
      if (mounted.current) setOpening({ status: 'error', key: errorKey(cause, 'unexpectedError') });
    }
  }

  const items = load.status === 'ready' ? load.items : [];
  const unread = unreadCount(readState, items);

  return (
    <Screen
      back={() => navigation.goBack()}
      refreshControl={
        <RefreshControl
          refreshing={refreshing}
          onRefresh={() => void reload(true)}
          colors={[c.brand]}
          tintColor={c.brand}
        />
      }
    >
      <View style={styles.header}>
        <View style={{ flex: 1 }}>
          <Title>{t('notifications')}</Title>
        </View>
        {userId && unread > 0 && (
          <TextButton
            label={t('markAllRead')}
            onPress={() => notificationReadState.markAllRead(userId, items)}
          />
        )}
      </View>
      {opening.status === 'notFound' && (
        <View style={styles.gap}>
          <ErrorNotice message={t('notifNotFound')} />
          <Button
            secondary
            label={t('openActivity')}
            onPress={() => {
              const currency = opening.currency;
              setOpening({ status: 'idle' });
              navigation.navigate('Tabs', { screen: 'Activity', params: { currency } });
            }}
          />
        </View>
      )}
      {opening.status === 'error' && (
        <View style={styles.gap}>
          <ErrorNotice message={t(opening.key)} />
        </View>
      )}
      {load.status === 'loading' && <SkeletonRows count={5} />}
      {load.status === 'error' && (
        <View style={styles.gap}>
          <ErrorNotice message={t(load.key)} />
          <Button secondary label={t('retry')} onPress={() => void reload(true)} />
        </View>
      )}
      {load.status === 'ready' && (
        <>
          {load.items.length === 0 && !load.hasMore && (
            <View style={styles.empty}>
              <Icon name="bell" color={c.muted} size={28} />
              <Text style={styles.emptyTitle}>{t('noNotifications')}</Text>
              <Copy muted>{t('noNotificationsBody')}</Copy>
            </View>
          )}
          {load.items.map((item) => (
            <NotificationRow
              key={item.id}
              item={item}
              unread={isUnread(readState, item)}
              opening={opening.status === 'opening' && opening.id === item.id}
              onPress={() => void open(item)}
            />
          ))}
          {load.moreError && (
            <View style={styles.more}>
              <ErrorNotice message={t(load.moreError)} />
            </View>
          )}
          {load.hasMore && (
            <View style={styles.more}>
              <Button
                secondary
                label={load.moreError ? t('retry') : t('loadMore')}
                loading={load.loadingMore}
                onPress={() => void loadMore()}
              />
            </View>
          )}
        </>
      )}
    </Screen>
  );
}

function NotificationRow({
  item,
  unread,
  opening,
  onPress,
}: {
  item: NotificationItem;
  unread: boolean;
  opening: boolean;
  onPress: () => void;
}) {
  const view = notificationView(item, locale);
  const label = [
    unread ? t('unread') : '',
    t(view.title),
    view.note ? t(view.note) : '',
    view.amountText,
    view.dateText,
  ]
    .filter(Boolean)
    .join(', ');
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ busy: opening }}
      onPress={onPress}
      style={({ pressed }) => [styles.row, pressed && styles.pressed]}
    >
      <View style={[styles.icon, view.credit ? styles.iconCredit : styles.iconDebit]}>
        <Icon
          name={view.credit ? 'arrow-down-left' : 'arrow-up-right'}
          size={18}
          color={view.credit ? c.success : c.ink}
        />
      </View>
      <View style={{ flex: 1 }}>
        <Text style={[styles.title, unread && styles.titleUnread]}>{t(view.title)}</Text>
        {view.note && (
          <View style={styles.test}>
            <Text style={styles.testText}>{t(view.note)}</Text>
          </View>
        )}
        <Text style={styles.date}>{view.dateText}</Text>
      </View>
      <View style={styles.end}>
        {opening ? (
          <ActivityIndicator color={c.brand} />
        ) : (
          <Text numberOfLines={1} style={[styles.amount, view.credit && styles.amountCredit]}>
            {view.amountText}
          </Text>
        )}
        {unread && <View style={styles.dot} />}
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  gap: { gap: 12, marginBottom: 16 },
  more: { marginTop: 16 },
  empty: { alignItems: 'center', gap: 8, marginTop: 32 },
  emptyTitle: { color: c.ink, fontSize: 17, fontWeight: '600' },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: c.border,
  },
  pressed: { backgroundColor: c.paleBlue },
  icon: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center' },
  iconCredit: { backgroundColor: c.paleSuccess },
  iconDebit: { backgroundColor: c.paleBlue },
  title: { color: c.ink, fontSize: 15 },
  titleUnread: { fontWeight: '700' },
  date: { color: c.muted, fontSize: 13, marginTop: 2 },
  test: {
    alignSelf: 'flex-start',
    backgroundColor: c.paleWarning,
    borderRadius: 6,
    paddingHorizontal: 6,
    paddingVertical: 2,
    marginTop: 4,
  },
  testText: { color: c.warning, fontSize: 12, fontWeight: '600' },
  end: { alignItems: 'flex-end', gap: 6 },
  amount: { color: c.ink, fontSize: 15, fontWeight: '600', fontVariant: ['tabular-nums'] },
  amountCredit: { color: c.success },
  dot: { width: 8, height: 8, borderRadius: 4, backgroundColor: c.brand },
});
