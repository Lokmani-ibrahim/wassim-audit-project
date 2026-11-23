import React, { useCallback, useEffect, useRef, useState, type ReactElement } from 'react';
import { RefreshControl, StyleSheet, View } from 'react-native';
import {
  useFocusEffect,
  useNavigation,
  useRoute,
  type NavigationProp,
  type RouteProp,
} from '@react-navigation/native';
import { Button, Copy, ErrorNotice, Screen, SkeletonRows, Title, c } from '../../components/ui';
import { CurrencyChips, DayHeader, TransactionRow } from '../../components/money';
import { formatDay, groupByDay } from '../../history/days';
import { locale, t, tf, type MessageKey } from '../../locales';
import { services } from '../../services';
import { AppError, errorKey } from '../../services/errors';
import { loadPage } from '../../history/paging';
import type { Currency, TransactionItem } from '../../api/contract';
import type { AppStackParamList, TabParamList } from '../../navigation/types';

type Load =
  | { status: 'loading' }
  | { status: 'error'; key: MessageKey }
  | {
      status: 'ready';
      items: TransactionItem[];
      hasMore: boolean;
      nextCursor: string | null;
      loadingMore: boolean;
      moreError: MessageKey | null;
    };

const list = (currency: Currency, cursor: string | null, pageSize: number) =>
  services.transactions.list(currency, cursor, pageSize);

// GET /api/transactions (contract §6), one currency at a time. Items are shown in the order the
// server returns them (ordering is UNVERIFIED server-side; nothing is re-sorted here).
export function Activity() {
  const route = useRoute<RouteProp<TabParamList, 'Activity'>>();
  const navigation = useNavigation<NavigationProp<AppStackParamList>>();
  const [currency, setCurrency] = useState<Currency>(route.params?.currency ?? 'EUR');
  const [load, setLoad] = useState<Load>({ status: 'loading' });
  const [refreshing, setRefreshing] = useState(false);
  const requestId = useRef(0);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  // Opened from Home with a wallet selected.
  const requested = route.params?.currency;
  useEffect(() => {
    if (requested) setCurrency(requested);
  }, [requested]);

  const reload = useCallback(
    async (pull = false) => {
      const id = ++requestId.current;
      if (pull) setRefreshing(true);
      try {
        const page = await loadPage(list, currency, null);
        if (!mounted.current || id !== requestId.current) return;
        setLoad({ status: 'ready', ...page, loadingMore: false, moreError: null });
      } catch (cause) {
        if (cause instanceof AppError && cause.expired) return;
        if (!mounted.current || id !== requestId.current) return;
        setLoad({ status: 'error', key: errorKey(cause, 'unexpectedError') });
      } finally {
        if (mounted.current && pull) setRefreshing(false);
      }
    },
    [currency],
  );

  // Every focus and every currency change: a fresh first page.
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
      const page = await loadPage(list, currency, cursor);
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

  function changeCurrency(next: Currency) {
    if (next === currency) return;
    requestId.current += 1;
    setLoad({ status: 'loading' });
    setCurrency(next);
  }

  // Day groups (presentation only, server order kept). Headers stick while scrolling. The header
  // end slot stays empty until the backend sends an end-of-day balance (R8); never computed (D4).
  const now = new Date();
  const blocks: ReactElement[] = [
    <Title key="title">{t('activityTitle')}</Title>,
    <View key="chips" style={styles.chips}>
      <CurrencyChips value={currency} onChange={changeCurrency} />
    </View>,
  ];
  const sticky: number[] = [];
  if (load.status === 'loading') blocks.push(<SkeletonRows key="loading" count={6} />);
  if (load.status === 'error')
    blocks.push(
      <View key="error" style={styles.gap}>
        <ErrorNotice message={t(load.key)} />
        <Button secondary label={t('retry')} onPress={() => void reload(true)} />
      </View>,
    );
  if (load.status === 'ready') {
    if (load.items.length === 0)
      blocks.push(
        <Copy key="empty" muted>
          {load.hasMore ? t('olderMayExist') : tf('noTransactionsIn', { currency })}
        </Copy>,
      );
    for (const group of groupByDay(load.items, (item) => item.occurredAt, now)) {
      sticky.push(blocks.length);
      blocks.push(
        <DayHeader
          key={`day-${group.key}`}
          label={group.relative ? t(group.relative) : formatDay(group, locale, now)}
          balance={null}
        />,
      );
      blocks.push(
        <View key={`items-${group.key}`}>
          {group.items.map((item) => (
            <TransactionRow
              key={`${item.transactionId}-${item.reference}-${item.direction}`}
              item={item}
              timeOnly={group.date !== null}
              onPress={() => navigation.navigate('TransactionDetail', { item })}
            />
          ))}
        </View>,
      );
    }
    if (load.moreError)
      blocks.push(
        <View key="more-error" style={styles.more}>
          <ErrorNotice message={t(load.moreError)} />
        </View>,
      );
    if (load.hasMore)
      blocks.push(
        <View key="more" style={styles.more}>
          <Button
            secondary
            label={load.moreError ? t('retry') : t('loadMore')}
            loading={load.loadingMore}
            onPress={() => void loadMore()}
          />
        </View>,
      );
  }

  return (
    <Screen
      brand
      stickyHeaderIndices={sticky}
      refreshControl={
        <RefreshControl
          refreshing={refreshing}
          onRefresh={() => void reload(true)}
          colors={[c.brand]}
          tintColor={c.brand}
        />
      }
    >
      {blocks}
    </Screen>
  );
}

const styles = StyleSheet.create({
  chips: { marginBottom: 8 },
  gap: { gap: 12 },
  more: { marginTop: 16 },
});
