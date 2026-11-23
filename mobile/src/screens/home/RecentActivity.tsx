import React, { useCallback, useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useFocusEffect, useNavigation, type NavigationProp } from '@react-navigation/native';
import { Copy, ErrorNotice, SkeletonRows, TextButton, c } from '../../components/ui';
import { TransactionRow } from '../../components/money';
import { t, tf, type MessageKey } from '../../locales';
import { services } from '../../services';
import { AppError, errorKey } from '../../services/errors';
import { recentItems } from '../../history/paging';
import type { Currency, TransactionItem } from '../../api/contract';
import type { AppStackParamList } from '../../navigation/types';

const RECENT_COUNT = 3;

type Load =
  | { status: 'loading' }
  | { status: 'error'; key: MessageKey }
  | { status: 'ready'; items: TransactionItem[]; exhausted: boolean };

// Last 3 real history items of the selected wallet (first items in server order; ordering is
// UNVERIFIED server-side). "No activity" is shown only when the real response is empty.
export function RecentActivity({
  currency,
  refreshToken,
}: {
  currency: Currency;
  // Changes on Home pull-to-refresh.
  refreshToken: number;
}) {
  const navigation = useNavigation<NavigationProp<AppStackParamList>>();
  const [load, setLoad] = useState<Load>({ status: 'loading' });
  const requestId = useRef(0);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const reload = useCallback(async () => {
    const id = ++requestId.current;
    try {
      const { items, exhausted } = await recentItems(
        (code, cursor, pageSize) => services.transactions.list(code, cursor, pageSize),
        currency,
        RECENT_COUNT,
      );
      if (mounted.current && id === requestId.current)
        setLoad({ status: 'ready', items, exhausted });
    } catch (cause) {
      if (cause instanceof AppError && cause.expired) return;
      if (mounted.current && id === requestId.current)
        setLoad({ status: 'error', key: errorKey(cause, 'unexpectedError') });
    }
  }, [currency]);

  // Home pull-to-refresh.
  const lastRefresh = useRef(refreshToken);
  useEffect(() => {
    if (lastRefresh.current === refreshToken) return;
    lastRefresh.current = refreshToken;
    void reload();
  }, [refreshToken, reload]);

  useEffect(() => {
    setLoad({ status: 'loading' });
  }, [currency]);
  useFocusEffect(
    useCallback(() => {
      void reload();
    }, [reload]),
  );

  return (
    <View style={styles.section}>
      <View style={styles.header}>
        <Text accessibilityRole="header" style={styles.title}>
          {t('activity')}
        </Text>
        <TextButton
          label={t('seeAll')}
          onPress={() => navigation.navigate('Tabs', { screen: 'Activity', params: { currency } })}
        />
      </View>
      {load.status === 'loading' && <SkeletonRows count={3} />}
      {load.status === 'error' && <ErrorNotice message={t(load.key)} />}
      {load.status === 'ready' && load.items.length === 0 && (
        <Copy muted>
          {load.exhausted ? tf('noTransactionsIn', { currency }) : t('olderMayExist')}
        </Copy>
      )}
      {load.status === 'ready' &&
        load.items.map((item) => (
          <TransactionRow
            key={`${item.transactionId}-${item.reference}-${item.direction}`}
            item={item}
            onPress={() => navigation.navigate('TransactionDetail', { item })}
          />
        ))}
    </View>
  );
}

const styles = StyleSheet.create({
  section: { marginTop: 32 },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 4,
  },
  title: { fontSize: 17, lineHeight: 24, fontWeight: '600', color: c.ink },
});
