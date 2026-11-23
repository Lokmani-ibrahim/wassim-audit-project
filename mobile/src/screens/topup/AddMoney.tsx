import React, { useEffect, useRef, useState } from 'react';
import { Share, StyleSheet, Text, View } from 'react-native';
import {
  useNavigation,
  useRoute,
  type NavigationProp,
  type RouteProp,
} from '@react-navigation/native';
import * as Clipboard from 'expo-clipboard';
import { Banner, Button, ErrorNotice, Intro, Screen, c } from '../../components/ui';
import { CurrencyChips, DetailRow, FieldLabel } from '../../components/money';
import { theme } from '../../theme';
import { t, tf } from '../../locales';
import { useSession } from '../../session/SessionContext';
import { sandboxDepositInstructions } from '../../topup';
import type { Currency } from '../../api/contract';
import type { AppStackParamList } from '../../navigation/types';

// Add money, sandbox preview (src/topup/sandboxInstructions.ts): example deposit instructions with
// the user's real reference. No service call, no credit, no balance change, no transaction. The
// sandbox warning stays on screen and is part of every shared message.
type Field = 'bank' | 'holder' | 'account' | 'reference';

export function AddMoney() {
  const navigation = useNavigation<NavigationProp<AppStackParamList>>();
  const route = useRoute<RouteProp<AppStackParamList, 'TopUp'>>();
  const { profile } = useSession();
  const [currency, setCurrency] = useState<Currency>(route.params?.currency ?? 'EUR');
  const [copied, setCopied] = useState<Field | null>(null);
  const [shareError, setShareError] = useState(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const details = sandboxDepositInstructions(profile.customer?.userId, currency);
  const back = () => navigation.goBack();

  if (!details)
    return (
      <Screen back={back}>
        <Intro title={t('topUpTitle')} body={t('topUpBody')} />
        <ErrorNotice message={t('topUpNoUser')} />
      </Screen>
    );

  const copy = async (field: Field, value: string) => {
    await Clipboard.setStringAsync(value);
    if (!mounted.current) return;
    setCopied(field);
    setTimeout(() => mounted.current && setCopied(null), 2000);
  };
  const share = async () => {
    setShareError(false);
    try {
      await Share.share({
        message: tf('topUpShareMessage', {
          bank: details.bankName,
          holder: details.accountHolder,
          account: details.accountNumber,
          currency: details.currency,
          reference: details.reference,
        }),
      });
    } catch {
      if (mounted.current) setShareError(true);
    }
  };
  const row = (field: Field, label: string, value: string, copyLabel: string, mono = false) => (
    <DetailRow
      label={label}
      value={value}
      mono={mono}
      copyLabel={copyLabel}
      copied={copied === field}
      onCopy={() => void copy(field, value)}
    />
  );

  return (
    <Screen
      back={back}
      footer={
        <>
          <ErrorNotice message={shareError ? t('unexpectedError') : null} />
          <Button label={t('topUpShare')} onPress={() => void share()} />
        </>
      }
    >
      <Intro title={t('topUpTitle')} body={t('topUpBody')} />
      {/* Permanent: never dismissible, above the details. */}
      <View style={styles.block}>
        <Banner tone="warning" title={t('topUpSandboxTitle')} body={t('topUpSandboxBody')} />
      </View>
      <FieldLabel>{t('topUpCurrency')}</FieldLabel>
      <View style={styles.block}>
        <CurrencyChips value={currency} onChange={setCurrency} />
      </View>
      <View>
        {row('bank', t('topUpBank'), details.bankName, t('topUpCopyBank'))}
        {row('holder', t('topUpHolder'), details.accountHolder, t('topUpCopyHolder'))}
        {row(
          'account',
          t('topUpAccountNumber'),
          details.accountNumber,
          t('topUpCopyAccountNumber'),
          true,
        )}
        {row('reference', t('topUpReference'), details.reference, t('copyReference'), true)}
      </View>
      <Text style={styles.hint}>{t('topUpReferenceHint')}</Text>
      <View style={styles.spaced}>
        <Banner body={tf('topUpWhen', { currency: details.currency })} />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  block: { marginBottom: theme.space.xl },
  spaced: { marginTop: theme.space.xl },
  hint: { ...theme.text.caption, color: c.muted, marginTop: theme.space.sm },
});
