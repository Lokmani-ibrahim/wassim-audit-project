import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { BackHandler, StyleSheet, Text, View } from 'react-native';
import {
  useFocusEffect,
  useNavigation,
  useRoute,
  type NavigationProp,
  type RouteProp,
} from '@react-navigation/native';
import * as Clipboard from 'expo-clipboard';
import {
  Banner,
  Button,
  Copy,
  ErrorNotice,
  Field,
  Intro,
  Screen,
  Sheet,
  Skeleton,
  StatusHeader,
  TextButton,
  Title,
  Working,
  c,
} from '../../components/ui';
import { AmountField, CurrencyChips, DetailRow, FieldLabel } from '../../components/money';
import { theme } from '../../theme';
import { successHaptic } from '../../utils/haptics';
import { ShareReceiptButton } from '../../components/ShareReceiptButton';
import { receiptFromOutcome } from '../../receipts/model';
import { locale, t, tf, type MessageKey } from '../../locales';
import { services } from '../../services';
import { AppError, errorKey } from '../../services/errors';
import { useSession } from '../../session/SessionContext';
import {
  compareMinor,
  formatMoney,
  parseAmountInput,
  scaleFor,
  type AmountInputError,
} from '../../money';
import type { Currency, RecipientLookupResponse, WalletsResponse } from '../../api/contract';
import type { AppStackParamList } from '../../navigation/types';
import type { PendingTransfer, TransferIntent, TransferOutcome } from '../../transfers/engine';
import { transferEngineFor } from '../../transfers';
import { confirmDeviceOwner, deviceAuthAvailable } from '../../transfers/deviceAuth';

// Internal transfer: recipient → amount → review → confirm (device owner) → result.
// All money rules live in transfers/engine.ts (idempotency key per intent, persistence before
// the POST, uncertain → history search, same-key retry only). This screen is presentation.
type Step =
  | { name: 'resuming' }
  | { name: 'recipient' }
  | { name: 'amount' }
  | { name: 'review' }
  | { name: 'confirmNoAuth' }
  | { name: 'working'; label: MessageKey }
  | { name: 'result'; outcome: TransferOutcome; manualCheck: boolean };

type Wallets =
  | { status: 'loading' }
  | { status: 'error'; key: MessageKey }
  | { status: 'ready'; data: WalletsResponse };

const amountErrors: Record<Exclude<AmountInputError, 'empty'>, MessageKey> = {
  invalid: 'amountInvalid',
  negative: 'amountNegative',
  zero: 'amountZero',
  tooManyDecimals: 'amountTooManyDecimals',
  tooLarge: 'amountTooLarge',
};

const money = (minor: string | number, currency: Currency, scale?: number) =>
  formatMoney(minor, currency, locale, { scale });

export function Send() {
  const navigation = useNavigation<NavigationProp<AppStackParamList>>();
  const route = useRoute<RouteProp<AppStackParamList, 'Send'>>();
  const { profile } = useSession();
  const senderUserId = profile.customer?.userId ?? null;
  const engine = useMemo(
    () => (senderUserId ? transferEngineFor(senderUserId) : null),
    [senderUserId],
  );

  const [step, setStep] = useState<Step>({ name: 'resuming' });
  const [email, setEmail] = useState('');
  const [recipient, setRecipient] = useState<RecipientLookupResponse | null>(null);
  const [currency, setCurrency] = useState<Currency>(route.params?.currency ?? 'EUR');
  const [amountText, setAmountText] = useState('');
  const [wallets, setWallets] = useState<Wallets>({ status: 'loading' });
  const [notice, setNotice] = useState<MessageKey | null>(null);
  const [busy, setBusy] = useState(false);
  const [duplicateFor, setDuplicateFor] = useState<TransferIntent | null>(null);
  const [stopFor, setStopFor] = useState<PendingTransfer | null>(null);
  const [copied, setCopied] = useState(false);
  const locked = useRef(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  // One action at a time (no double POST from a double tap).
  const guarded = useCallback(async (action: () => Promise<void>) => {
    if (locked.current) return;
    locked.current = true;
    setBusy(true);
    try {
      await action();
    } finally {
      locked.current = false;
      if (mounted.current) setBusy(false);
    }
  }, []);

  // Resume: a transfer persisted before an app restart (or still unconfirmed) is shown first
  // and re-checked once (read-only). Otherwise start with the recipient.
  useEffect(() => {
    if (!engine) return;
    let alive = true;
    void (async () => {
      const pending = await engine.resume().catch(() => null);
      if (!alive) return;
      if (!pending) {
        setStep({ name: 'recipient' });
        return;
      }
      setStep({ name: 'working', label: 'checkingTransfer' });
      const outcome = await engine.check(pending, false);
      if (alive) setStep({ name: 'result', outcome, manualCheck: false });
    })();
    return () => {
      alive = false;
    };
  }, [engine]);

  const fetchWallets = useCallback(async () => {
    setWallets({ status: 'loading' });
    try {
      const data = await services.wallets.list();
      if (mounted.current) setWallets({ status: 'ready', data });
    } catch (cause) {
      if (cause instanceof AppError && cause.expired) return;
      if (mounted.current)
        setWallets({ status: 'error', key: errorKey(cause, 'balancesUnavailable') });
    }
  }, []);

  // Always a fresh balance when the amount step opens.
  useEffect(() => {
    if (step.name === 'amount') void fetchWallets();
  }, [step.name, fetchWallets]);

  const wallet =
    wallets.status === 'ready'
      ? wallets.data.wallets.find((item) => item.currency === currency)
      : undefined;
  const scale = wallet?.scale ?? scaleFor(currency);
  const parsed = parseAmountInput(amountText, scale);
  const available = wallet && wallet.status !== 'NOT_PROVISIONED' ? wallet.availableMinor : null;
  const insufficient = parsed.ok && available !== null && compareMinor(parsed.minor, available) > 0;
  const canContinue = parsed.ok && available !== null && !insufficient;

  const back = useCallback((): boolean => {
    if (locked.current) return true;
    setNotice(null);
    switch (step.name) {
      case 'amount':
        setStep({ name: 'recipient' });
        return true;
      case 'review':
        setStep({ name: 'amount' });
        return true;
      case 'confirmNoAuth':
        setStep({ name: 'review' });
        return true;
      case 'working':
        return true;
      default:
        return false;
    }
  }, [step.name]);
  const leave = useCallback(() => {
    if (!back()) navigation.goBack();
  }, [back, navigation]);
  useFocusEffect(
    useCallback(() => {
      const subscription = BackHandler.addEventListener('hardwareBackPress', back);
      return () => subscription.remove();
    }, [back]),
  );

  if (!engine)
    return (
      <Screen back={() => navigation.goBack()}>
        <ErrorNotice message={t('unexpectedError')} />
      </Screen>
    );

  const intent = (): TransferIntent | null =>
    recipient && parsed.ok
      ? {
          recipientUserId: recipient.recipientUserId,
          maskedEmail: recipient.maskedEmail,
          currency,
          amountMinor: parsed.minor,
        }
      : null;

  const lookup = () =>
    guarded(async () => {
      setNotice(null);
      try {
        const found = await services.transfers.lookup(email);
        if (!mounted.current) return;
        setRecipient(found);
        setStep({ name: 'amount' });
      } catch (cause) {
        if (cause instanceof AppError && cause.expired) return;
        if (mounted.current) setNotice(errorKey(cause, 'unexpectedError'));
      }
    });

  const toReview = (next: TransferIntent) => {
    engine.prepare(next);
    setDuplicateFor(null);
    setNotice(null);
    setStep({ name: 'review' });
  };

  const continueToReview = () =>
    guarded(async () => {
      const next = intent();
      if (!next || !canContinue) return;
      // Same recipient + amount as a transfer the user stopped tracking: warn again first.
      const similar = await engine.matchesUnconfirmed(next).catch(() => false);
      if (!mounted.current) return;
      if (similar) setDuplicateFor(next);
      else toReview(next);
    });

  const performSend = async () => {
    setStep({ name: 'working', label: 'sendingTransfer' });
    try {
      const outcome = await engine.submit();
      if (outcome.kind === 'succeeded') successHaptic();
      if (mounted.current) setStep({ name: 'result', outcome, manualCheck: false });
    } catch {
      // Only local failures reach here (e.g. secure storage), before any POST: nothing sent.
      if (!mounted.current) return;
      setNotice('unexpectedError');
      setStep({ name: 'review' });
    }
  };
  // Explicit confirmation on a device without any screen lock.
  const send = () => guarded(performSend);

  const confirm = () =>
    guarded(async () => {
      setNotice(null);
      if (!(await deviceAuthAvailable())) {
        setStep({ name: 'confirmNoAuth' });
        return;
      }
      const result = await confirmDeviceOwner({ title: t('authPrompt'), cancel: t('cancel') });
      if (!mounted.current) return;
      if (result === 'notEnrolled') setStep({ name: 'confirmNoAuth' });
      else if (result === 'cancelled') setNotice('authCancelled');
      else if (result === 'failed') setNotice('authFailed');
      else await performSend();
    });

  const checkStatus = (pending: PendingTransfer) =>
    guarded(async () => {
      setStep({ name: 'working', label: 'checkingTransfer' });
      const outcome = await engine.check(pending, true);
      if (outcome.kind === 'succeeded') successHaptic();
      if (mounted.current) setStep({ name: 'result', outcome, manualCheck: true });
    });

  const retry = (pending: PendingTransfer) =>
    guarded(async () => {
      setStep({ name: 'working', label: 'sendingTransfer' });
      const outcome = await engine.retry(pending);
      if (outcome.kind === 'succeeded') successHaptic();
      if (mounted.current) setStep({ name: 'result', outcome, manualCheck: false });
    });

  const stopTracking = (pending: PendingTransfer) =>
    guarded(async () => {
      await engine.stopTracking(pending);
      if (!mounted.current) return;
      setStopFor(null);
      navigation.goBack();
    });

  const startAgain = () => {
    setNotice(null);
    setStep(recipient ? { name: 'amount' } : { name: 'recipient' });
  };
  const openActivity = (target: Currency) =>
    navigation.navigate('Tabs', { screen: 'Activity', params: { currency: target } });
  const copyReference = async (reference: string) => {
    await Clipboard.setStringAsync(reference);
    setCopied(true);
    setTimeout(() => mounted.current && setCopied(false), 2000);
  };

  const sheets = (
    <>
      <Sheet
        visible={!!duplicateFor}
        title={t('possibleDuplicateTitle')}
        onClose={() => setDuplicateFor(null)}
      >
        <Copy>{t('unconfirmedWarning')}</Copy>
        <Button
          secondary
          label={t('viewActivity')}
          onPress={() => {
            const target = duplicateFor?.currency ?? currency;
            setDuplicateFor(null);
            openActivity(target);
          }}
        />
        <Button
          label={t('continueAnyway')}
          onPress={() => duplicateFor && toReview(duplicateFor)}
        />
      </Sheet>
      <Sheet visible={!!stopFor} title={t('stopTrackingTitle')} onClose={() => setStopFor(null)}>
        <Copy>{t('unconfirmedWarning')}</Copy>
        <Button secondary label={t('cancel')} onPress={() => setStopFor(null)} />
        <Button
          label={t('stopTracking')}
          loading={busy}
          onPress={() => stopFor && void stopTracking(stopFor)}
        />
      </Sheet>
    </>
  );

  if (step.name === 'resuming' || step.name === 'working')
    return (
      <Screen>
        <Working label={t(step.name === 'working' ? step.label : 'loading')} />
      </Screen>
    );

  if (step.name === 'recipient')
    return (
      <Screen
        back={leave}
        footer={
          <>
            <ErrorNotice message={notice && t(notice)} />
            <Button
              label={t('findRecipient')}
              disabled={email.trim() === ''}
              loading={busy}
              onPress={() => void lookup()}
            />
          </>
        }
      >
        <Intro title={t('recipientTitle')} body={t('recipientBody')} />
        <Field
          label={t('recipientEmail')}
          value={email}
          kind="email"
          onChangeText={(value) => {
            setEmail(value);
            setRecipient(null);
            setNotice(null);
          }}
        />
      </Screen>
    );

  if (step.name === 'amount') {
    const amountError = !parsed.ok && parsed.error !== 'empty' ? amountErrors[parsed.error] : null;
    return (
      <Screen
        back={leave}
        footer={
          <>
            <ErrorNotice message={notice && t(notice)} />
            <Button
              label={insufficient ? t('insufficientBalance') : t('continue')}
              disabled={!canContinue}
              loading={busy}
              next={!insufficient}
              onPress={() => void continueToReview()}
            />
          </>
        }
      >
        <Intro title={t('amountTitle')} body={`${t('to')} ${recipient?.maskedEmail ?? ''}`} />
        <FieldLabel>{t('currencyLabel')}</FieldLabel>
        <View style={styles.block}>
          <CurrencyChips value={currency} onChange={setCurrency} disabled={busy} />
        </View>
        <AmountField
          label={tf('amountLabel', { currency })}
          value={amountText}
          onChangeText={(value) => {
            setAmountText(value);
            setNotice(null);
          }}
          scale={scale}
          invalid={!!amountError || insufficient}
          // Live, unambiguous reading of what was typed ("1,000" in TND = TND 1.000).
          preview={
            parsed.ok ? tf('amountEquals', { amount: money(parsed.minor, currency, scale) }) : null
          }
          error={amountError && tf(amountError, { currency, decimals: scale })}
          hint={tf('amountHint', { decimals: scale })}
        />
        <View style={styles.available}>
          {wallets.status === 'loading' && <Skeleton width={160} />}
          {wallets.status === 'error' && (
            <View style={styles.gap}>
              <ErrorNotice message={t(wallets.key)} />
              <Button secondary label={t('retry')} onPress={() => void fetchWallets()} />
            </View>
          )}
          {wallets.status === 'ready' &&
            (available === null ? (
              <Copy muted style={styles.caption}>
                {t('walletNotReady')}
              </Copy>
            ) : (
              <Text style={[styles.availableText, insufficient && styles.availableShort]}>
                {tf('availableBalance', { amount: money(available, currency, scale) })}
              </Text>
            ))}
        </View>
        {sheets}
      </Screen>
    );
  }

  if (step.name === 'review' || step.name === 'confirmNoAuth') {
    const next = intent();
    if (!next) return null;
    const amount = money(next.amountMinor, next.currency, scale);
    const noAuth = step.name === 'confirmNoAuth';
    return (
      <Screen
        back={leave}
        footer={
          <>
            <ErrorNotice message={notice && t(notice)} />
            {noAuth ? (
              <Button label={t('sendNow')} loading={busy} onPress={() => void send()} />
            ) : (
              <Button label={t('confirmSend')} loading={busy} onPress={() => void confirm()} />
            )}
          </>
        }
      >
        <Title>{t('reviewTransferTitle')}</Title>
        <Text style={[styles.caption, styles.spaced]}>{t('youSend')}</Text>
        <Text adjustsFontSizeToFit numberOfLines={1} style={styles.display}>
          {amount}
        </Text>
        <View style={styles.block}>
          <DetailRow label={t('to')} value={next.maskedEmail} />
          <DetailRow label={t('currencyLabel')} value={next.currency} />
          <DetailRow label={t('fee')} value={t('noFee')} />
        </View>
        <View style={styles.gap}>
          <Banner body={t('instantNote')} />
          {noAuth && (
            <Banner tone="warning" title={t('noDeviceAuthTitle')} body={t('noDeviceAuthBody')} />
          )}
        </View>
      </Screen>
    );
  }

  // Result.
  const { outcome, manualCheck } = step;
  // Receipt only for a confirmed transfer: receiptFromOutcome is null for uncertain/rejected.
  const receipt = receiptFromOutcome(outcome);
  if (outcome.kind === 'succeeded') {
    const { transfer } = outcome;
    return (
      <Screen
        footer={
          <>
            {receipt && <ShareReceiptButton data={receipt} />}
            <Button label={t('done')} onPress={() => navigation.goBack()} />
          </>
        }
      >
        <StatusHeader tone="success" title={t('sentTitle')} body={t('sentBody')} />
        <Text adjustsFontSizeToFit numberOfLines={1} style={styles.display}>
          {money(transfer.amountMinor, transfer.currency)}
        </Text>
        {outcome.status === 'REVERSED' && (
          <View style={styles.spaced}>
            <Banner tone="error" body={t('transferReversedNote')} />
          </View>
        )}
        <View style={styles.block}>
          <DetailRow label={t('to')} value={transfer.maskedEmail} />
          <DetailRow label={t('transactionId')} value={String(outcome.transactionId)} />
          <DetailRow
            label={t('reference')}
            value={outcome.reference}
            mono
            copyLabel={t('copyReference')}
            copied={copied}
            onCopy={() => void copyReference(outcome.reference)}
          />
        </View>
      </Screen>
    );
  }
  if (outcome.kind === 'rejected')
    return (
      <Screen
        footer={
          <>
            <Button label={t('startAgain')} onPress={startAgain} />
            <Button secondary label={t('done')} onPress={() => navigation.goBack()} />
          </>
        }
      >
        <StatusHeader
          tone="error"
          title={t('transferRejectedTitle')}
          body={t(`reject${outcome.reason}`)}
        />
        <Copy muted>{t('noMoneyMoved')}</Copy>
      </Screen>
    );
  const { transfer, check } = outcome;
  return (
    <Screen
      footer={
        <>
          <Button
            label={t('checkTransferStatus')}
            loading={busy}
            onPress={() => void checkStatus(transfer)}
          />
          <Button secondary label={t('retry')} onPress={() => void retry(transfer)} />
          <Button secondary label={t('done')} onPress={() => navigation.goBack()} />
          {transfer.manualChecks >= 1 && (
            <TextButton
              tone="error"
              label={t('stopTracking')}
              onPress={() => setStopFor(transfer)}
            />
          )}
        </>
      }
    >
      <StatusHeader
        tone="warning"
        title={t('processingTransferTitle')}
        body={t('processingTransferBody')}
      />
      <Text adjustsFontSizeToFit numberOfLines={1} style={styles.display}>
        {money(transfer.amountMinor, transfer.currency)}
      </Text>
      <View style={styles.block}>
        <DetailRow label={t('to')} value={transfer.maskedEmail} />
        <DetailRow label={t('reference')} value={transfer.reference} mono />
      </View>
      {manualCheck && (
        <Banner
          tone={check === 'checkFailed' ? 'error' : 'warning'}
          body={t(check === 'checkFailed' ? 'checkFailed' : 'checkNotFound')}
        />
      )}
      <View style={styles.spaced}>
        <TextButton label={t('viewActivity')} onPress={() => openActivity(transfer.currency)} />
      </View>
      {sheets}
    </Screen>
  );
}

const sp = theme.space;
const type = theme.text;
const styles = StyleSheet.create({
  gap: { gap: sp.md },
  spaced: { marginTop: sp.lg },
  block: { marginBottom: sp.xl },
  caption: { ...type.caption, color: c.muted },
  display: { ...type.display, color: c.ink, marginTop: sp.xs },
  available: { marginTop: sp.xl, minHeight: 20 },
  availableText: { ...type.bodyStrong, color: c.ink, fontVariant: ['tabular-nums'] },
  availableShort: { color: c.error },
});
