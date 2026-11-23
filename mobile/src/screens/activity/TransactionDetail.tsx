import React, { useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useNavigation, useRoute, type RouteProp } from '@react-navigation/native';
import * as Clipboard from 'expo-clipboard';
import { Badge, Screen, c } from '../../components/ui';
import { DetailRow } from '../../components/money';
import { ShareReceiptButton } from '../../components/ShareReceiptButton';
import { locale, t } from '../../locales';
import type { AppStackParamList } from '../../navigation/types';
import { receiptFromHistory } from '../../receipts/model';
import { transactionView } from './transactionView';

// Every field of one history item, with copy for the reference and the transaction ID.
export function TransactionDetail() {
  const navigation = useNavigation();
  const { item, testCredit = false } =
    useRoute<RouteProp<AppStackParamList, 'TransactionDetail'>>().params;
  const view = transactionView(item, locale);
  const [copied, setCopied] = useState<'reference' | 'id' | null>(null);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  async function copy(which: 'reference' | 'id') {
    await Clipboard.setStringAsync(which === 'id' ? String(item.transactionId) : item.reference);
    setCopied(which);
    setTimeout(() => mounted.current && setCopied(null), 2000);
  }
  return (
    <Screen back={() => navigation.goBack()}>
      <Text style={styles.caption}>{t(view.title)}</Text>
      <Text
        style={[styles.amount, view.credit && styles.credit, view.reversed && styles.reversed]}
        adjustsFontSizeToFit
        numberOfLines={1}
      >
        {view.amountText}
      </Text>
      <View style={styles.badge}>
        <Badge tone={view.reversed ? 'error' : 'success'} label={t(view.status)} />
        {testCredit && <Badge tone="warning" label={t('notifTestCreditNote')} />}
      </View>
      <DetailRow label={t('dateTime')} value={view.dateText} />
      <DetailRow label={t('typeLabel')} value={t(view.title)} />
      <DetailRow label={t('directionLabel')} value={t(`direction${item.direction}`)} />
      <DetailRow label={t('currencyLabel')} value={item.currency} />
      <DetailRow label={t('statusLabel')} value={t(view.status)} />
      <DetailRow
        label={t('transactionId')}
        value={String(item.transactionId)}
        copyLabel={t('copyTransactionId')}
        copied={copied === 'id'}
        onCopy={() => void copy('id')}
      />
      <DetailRow
        label={t('reference')}
        value={item.reference}
        mono
        copyLabel={t('copyReference')}
        copied={copied === 'reference'}
        onCopy={() => void copy('reference')}
      />
      {/* History has no pending status (contract §6): every item is confirmed. */}
      <View style={styles.receipt}>
        <ShareReceiptButton data={receiptFromHistory(item)} />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  caption: { color: c.muted, fontSize: 15, marginBottom: 4 },
  amount: { color: c.ink, fontSize: 36, fontWeight: '600', fontVariant: ['tabular-nums'] },
  credit: { color: c.success },
  reversed: { textDecorationLine: 'line-through', color: c.muted },
  badge: { marginTop: 12, marginBottom: 16, gap: 8, alignItems: 'flex-start' },
  receipt: { marginTop: 24 },
});
