import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { BackHandler, StyleSheet, Text, View } from 'react-native';
import {
  useFocusEffect,
  useNavigation,
  useRoute,
  type NavigationProp,
  type RouteProp,
} from '@react-navigation/native';
import {
  Badge,
  Banner,
  Button,
  Copy,
  ErrorNotice,
  Intro,
  Screen,
  Sheet,
  Skeleton,
  StatusHeader,
  TextButton,
  Working,
  c,
} from '../../components/ui';
import { AmountField, CurrencyChips, DetailRow, FieldLabel } from '../../components/money';
import { successHaptic } from '../../utils/haptics';
import { theme } from '../../theme';
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
import type { Currency, WalletsResponse } from '../../api/contract';
import type { AppStackParamList } from '../../navigation/types';
import { fxEngineFor } from '../../fx';
import { secondsLeft, type ReceivedQuote } from '../../fx/clock';
import type { CheckResult, FxOutcome, PendingConversion } from '../../fx/engine';
import type { ConversionRejection, QuoteFailure } from '../../fx/outcome';
import { confirmDeviceOwner, deviceAuthAvailable } from '../../transfers/deviceAuth';

// Currency conversion (sandbox demo, contract §14): amount → indicative quote with a live
// countdown → confirm (device owner) → one POST → outcome. All money rules live in fx/engine.ts
// (one key per confirmed quote, persisted before the POST, no automatic resend or check, same
// key on a manual "Try again"). This screen is presentation.
type Step =
  | { name: 'resuming' }
  | { name: 'setup' }
  | { name: 'quote'; received: ReceivedQuote }
  | { name: 'confirmNoAuth'; received: ReceivedQuote }
  | { name: 'working'; label: MessageKey }
  | { name: 'result'; outcome: Exclude<FxOutcome, { kind: 'expired' }> };

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
const quoteErrors: Record<QuoteFailure, MessageKey> = {
  unavailable: 'fxQuoteUnavailable',
  invalidInput: 'fxQuoteInvalid',
  notAllowed: 'fxRejectNotAllowed',
  amountOutOfRange: 'fxQuoteOutOfRange',
  network: 'fxQuoteNetwork',
};
const rejections: Record<ConversionRejection, MessageKey> = {
  unavailable: 'fxRejectUnavailable',
  invalidInput: 'fxRejectInvalid',
  notAllowed: 'fxRejectNotAllowed',
  insufficientFunds: 'fxRejectInsufficient',
  rejected: 'fxRejectGeneric',
};
const checks: Record<Exclude<CheckResult, 'notChecked'>, MessageKey> = {
  inProgress: 'fxCheckInProgress',
  notFound: 'fxCheckNotFound',
  checkUnavailable: 'fxCheckUnavailable',
  checkFailed: 'fxCheckFailed',
};

const money = (minor: string, currency: Currency, scale?: number) =>
  formatMoney(minor, currency, locale, { scale });
const otherThan = (currency: Currency): Currency => (currency === 'EUR' ? 'USD' : 'EUR');

export function Convert() {
  const navigation = useNavigation<NavigationProp<AppStackParamList>>();
  const route = useRoute<RouteProp<AppStackParamList, 'Convert'>>();
  const { profile } = useSession();
  const userId = profile.customer?.userId ?? null;
  const engine = useMemo(() => (userId ? fxEngineFor(userId) : null), [userId]);

  const initialFrom = route.params?.from ?? 'EUR';
  const [step, setStep] = useState<Step>({ name: 'resuming' });
  const [from, setFrom] = useState<Currency>(initialFrom);
  const [to, setTo] = useState<Currency>(otherThan(initialFrom));
  const [amountText, setAmountText] = useState('');
  const [wallets, setWallets] = useState<Wallets>({ status: 'loading' });
  const [notice, setNotice] = useState<MessageKey | null>(null);
  const [busy, setBusy] = useState(false);
  const [stopFor, setStopFor] = useState<PendingConversion | null>(null);
  const [now, setNow] = useState(() => Date.now());
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

  // Resume: a conversion persisted before (still unconfirmed) is shown first. Nothing is sent
  // or checked automatically; the user decides.
  useEffect(() => {
    if (!engine) return;
    let alive = true;
    void (async () => {
      const pending = await engine.resume().catch(() => null);
      if (!alive) return;
      setStep(
        pending
          ? { name: 'result', outcome: { kind: 'uncertain', pending, check: 'notChecked' } }
          : { name: 'setup' },
      );
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
  useEffect(() => {
    if (step.name === 'setup') void fetchWallets();
  }, [step.name, fetchWallets]);

  // Countdown tick while a quote is on screen.
  const quoting = step.name === 'quote' || step.name === 'confirmNoAuth';
  useEffect(() => {
    if (!quoting) return;
    setNow(Date.now());
    const timer = setInterval(() => setNow(Date.now()), 500);
    return () => clearInterval(timer);
  }, [quoting]);

  const walletOf = (currency: Currency) =>
    wallets.status === 'ready'
      ? wallets.data.wallets.find((item) => item.currency === currency)
      : undefined;
  const source = walletOf(from);
  const destination = walletOf(to);
  const scale = source?.scale ?? scaleFor(from);
  const parsed = parseAmountInput(amountText, scale);
  const available = source && source.status !== 'NOT_PROVISIONED' ? source.availableMinor : null;
  const destinationReady = !destination || destination.status !== 'NOT_PROVISIONED';
  const insufficient = parsed.ok && available !== null && compareMinor(parsed.minor, available) > 0;
  const canQuote =
    parsed.ok && available !== null && destinationReady && !insufficient && from !== to;

  const back = useCallback((): boolean => {
    if (locked.current) return true;
    setNotice(null);
    switch (step.name) {
      case 'quote':
      case 'confirmNoAuth':
        setStep({ name: 'setup' });
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

  const chooseFrom = (currency: Currency) => {
    setFrom(currency);
    if (currency === to) setTo(otherThan(currency));
    setNotice(null);
  };
  const chooseTo = (currency: Currency) => {
    if (currency === from) return;
    setTo(currency);
    setNotice(null);
  };

  const getRate = () =>
    guarded(async () => {
      if (!parsed.ok) return;
      setNotice(null);
      const result = await engine.requestQuote({
        sourceCurrency: from,
        destinationCurrency: to,
        sourceMinor: parsed.minor,
      });
      if (!mounted.current) return;
      if (result.kind === 'quote') setStep({ name: 'quote', received: result.received });
      else setNotice(quoteErrors[result.reason]);
    });

  const show = (outcome: FxOutcome, received: ReceivedQuote) => {
    if (outcome.kind === 'expired') {
      setNotice('fxRateExpired');
      setStep({ name: 'quote', received });
    } else setStep({ name: 'result', outcome });
  };

  const perform = async (received: ReceivedQuote) => {
    setStep({ name: 'working', label: 'fxWorking' });
    try {
      const outcome = await engine.submit(received);
      if (outcome.kind === 'succeeded') successHaptic();
      if (mounted.current) show(outcome, received);
    } catch {
      // Only local failures reach here (storage, a conversion already pending), before any POST.
      if (!mounted.current) return;
      setNotice('unexpectedError');
      setStep({ name: 'quote', received });
    }
  };

  const confirm = (received: ReceivedQuote) =>
    guarded(async () => {
      setNotice(null);
      if (!(await deviceAuthAvailable())) {
        setStep({ name: 'confirmNoAuth', received });
        return;
      }
      const result = await confirmDeviceOwner({ title: t('fxAuthPrompt'), cancel: t('cancel') });
      if (!mounted.current) return;
      if (result === 'notEnrolled') setStep({ name: 'confirmNoAuth', received });
      else if (result === 'cancelled') setNotice('authCancelled');
      else if (result === 'failed') setNotice('authFailed');
      // The engine re-checks the deadline: an expired quote is never sent.
      else await perform(received);
    });

  const checkStatus = (pending: PendingConversion) =>
    guarded(async () => {
      setStep({ name: 'working', label: 'fxChecking' });
      const outcome = await engine.check(pending);
      if (outcome.kind === 'succeeded') successHaptic();
      if (mounted.current && outcome.kind !== 'expired') setStep({ name: 'result', outcome });
    });
  const tryAgain = (pending: PendingConversion) =>
    guarded(async () => {
      setStep({ name: 'working', label: 'fxWorking' });
      const outcome = await engine.retry(pending);
      if (outcome.kind === 'succeeded') successHaptic();
      if (mounted.current && outcome.kind !== 'expired') setStep({ name: 'result', outcome });
    });
  const stopTracking = (pending: PendingConversion) =>
    guarded(async () => {
      await engine.stopTracking(pending);
      if (!mounted.current) return;
      setStopFor(null);
      navigation.goBack();
    });
  const startAgain = () => {
    setNotice(null);
    setStep({ name: 'setup' });
  };

  if (step.name === 'resuming' || step.name === 'working')
    return (
      <Screen>
        <Working label={t(step.name === 'working' ? step.label : 'loading')} />
      </Screen>
    );

  if (step.name === 'setup') {
    const amountError = !parsed.ok && parsed.error !== 'empty' ? amountErrors[parsed.error] : null;
    return (
      <Screen
        back={leave}
        footer={
          <>
            <ErrorNotice message={notice && t(notice)} />
            <Button
              label={insufficient ? t('insufficientBalance') : t('fxGetRate')}
              disabled={!canQuote}
              loading={busy}
              onPress={() => void getRate()}
            />
          </>
        }
      >
        <Intro title={t('fxTitle')} body={t('fxBody')} />
        <FieldLabel>{t('fxFrom')}</FieldLabel>
        <View style={styles.block}>
          <CurrencyChips value={from} onChange={chooseFrom} disabled={busy} />
        </View>
        <FieldLabel>{t('fxTo')}</FieldLabel>
        <View style={styles.block}>
          <CurrencyChips value={to} onChange={chooseTo} disabled={busy} />
        </View>
        <AmountField
          label={tf('amountLabel', { currency: from })}
          value={amountText}
          onChangeText={(value) => {
            setAmountText(value);
            setNotice(null);
          }}
          scale={scale}
          invalid={!!amountError || insufficient}
          preview={
            parsed.ok ? tf('amountEquals', { amount: money(parsed.minor, from, scale) }) : null
          }
          error={amountError && tf(amountError, { currency: from, decimals: scale })}
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
              <Text style={styles.caption}>{tf('fxWalletSettingUp', { currency: from })}</Text>
            ) : (
              <Text style={[styles.availableText, insufficient && styles.availableShort]}>
                {tf('availableBalance', { amount: money(available, from, scale) })}
              </Text>
            ))}
          {wallets.status === 'ready' && !destinationReady && (
            <Text style={styles.caption}>{tf('fxWalletSettingUp', { currency: to })}</Text>
          )}
        </View>
      </Screen>
    );
  }

  if (step.name === 'quote' || step.name === 'confirmNoAuth') {
    const { received } = step;
    const { quote } = received;
    const left = secondsLeft(received, now);
    const expired = left === 0;
    const noAuth = step.name === 'confirmNoAuth';
    return (
      <Screen
        back={leave}
        footer={
          <>
            <ErrorNotice message={notice && t(notice)} />
            {expired ? (
              <Button
                label={t('fxNewRate')}
                loading={busy}
                onPress={() => {
                  setNotice(null);
                  void getRate();
                }}
              />
            ) : noAuth ? (
              <Button
                label={t('fxConvertNoAuth')}
                loading={busy}
                onPress={() => void guarded(() => perform(received))}
              />
            ) : (
              <Button
                label={t('fxConvertNow')}
                loading={busy}
                onPress={() => void confirm(received)}
              />
            )}
          </>
        }
      >
        <Badge tone="warning" label={t('fxSandboxRate')} />
        <View style={[styles.quote, expired && styles.stale]}>
          <Text style={styles.caption}>{t('fxYouConvert')}</Text>
          <Text adjustsFontSizeToFit numberOfLines={1} style={styles.secondaryAmount}>
            {money(quote.sourceMinor, quote.sourceCurrency, quote.sourceScale)}
          </Text>
          <Text style={[styles.caption, styles.spaced]}>{t('fxYouReceive')}</Text>
          <Text
            accessibilityRole="header"
            adjustsFontSizeToFit
            numberOfLines={1}
            style={styles.display}
          >
            {money(quote.destinationMinor, quote.destinationCurrency, quote.destinationScale)}
          </Text>
          <View style={styles.spaced}>
            <DetailRow
              label={t('fxRate')}
              value={tf('fxRateValue', {
                from: quote.sourceCurrency,
                rate: quote.exchangeRate,
                to: quote.destinationCurrency,
              })}
            />
            <DetailRow label={t('fee')} value={t('noFee')} />
          </View>
        </View>
        <Text
          accessibilityLiveRegion={expired ? 'polite' : 'none'}
          style={[styles.countdown, expired && styles.countdownExpired]}
        >
          {expired ? t('fxRateExpired') : tf('fxValidFor', { seconds: left })}
        </Text>
        <View style={styles.gap}>
          <Banner body={t('fxSimulatedNote')} />
          {noAuth && (
            <Banner tone="warning" title={t('noDeviceAuthTitle')} body={t('fxNoAuthBody')} />
          )}
        </View>
      </Screen>
    );
  }

  // Result.
  const { outcome } = step;
  const amounts = (pending: PendingConversion) => (
    <>
      <DetailRow
        label={t('fxYouConvert')}
        value={money(pending.sourceMinor, pending.sourceCurrency)}
      />
      <DetailRow
        label={t('fxYouReceive')}
        value={money(pending.destinationMinor, pending.destinationCurrency)}
      />
      <DetailRow
        label={t('fxRate')}
        value={tf('fxRateValue', {
          from: pending.sourceCurrency,
          rate: pending.exchangeRate,
          to: pending.destinationCurrency,
        })}
      />
    </>
  );

  if (outcome.kind === 'succeeded') {
    const { conversion } = outcome;
    return (
      <Screen footer={<Button label={t('done')} onPress={() => navigation.goBack()} />}>
        <StatusHeader tone="success" title={t('fxSucceededTitle')} body={t('fxSucceededBody')} />
        <Text adjustsFontSizeToFit numberOfLines={1} style={styles.display}>
          {money(conversion.destinationMinor, conversion.destinationCurrency)}
        </Text>
        <View style={styles.block}>
          {amounts(outcome.pending)}
          <DetailRow label={t('fxOperation')} value={conversion.operationId} mono />
          <DetailRow label={t('reference')} value={conversion.settlementReference} mono />
        </View>
        <Banner body={t('fxSimulatedNote')} />
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
          title={t('fxRejectedTitle')}
          body={tf(rejections[outcome.reason], { currency: outcome.pending.sourceCurrency })}
        />
        <Copy muted>{t('fxNothingConverted')}</Copy>
      </Screen>
    );

  const { pending, check } = outcome;
  return (
    <Screen
      footer={
        <>
          <Button
            label={t('fxCheckStatus')}
            loading={busy}
            onPress={() => void checkStatus(pending)}
          />
          <Button secondary label={t('retry')} onPress={() => void tryAgain(pending)} />
          <Button secondary label={t('done')} onPress={() => navigation.goBack()} />
          {pending.manualChecks >= 1 && (
            <TextButton
              tone="error"
              label={t('stopTracking')}
              onPress={() => setStopFor(pending)}
            />
          )}
        </>
      }
    >
      <StatusHeader tone="warning" title={t('fxUncertainTitle')} body={t('fxUncertainBody')} />
      <View style={styles.block}>{amounts(pending)}</View>
      {check !== 'notChecked' && <Banner tone={checkTones[check]} body={t(checks[check])} />}
      <Sheet visible={!!stopFor} title={t('fxStopTitle')} onClose={() => setStopFor(null)}>
        <Copy>{t('fxStopBody')}</Copy>
        <Button secondary label={t('cancel')} onPress={() => setStopFor(null)} />
        <Button
          label={t('stopTracking')}
          loading={busy}
          onPress={() => stopFor && void stopTracking(stopFor)}
        />
      </Sheet>
    </Screen>
  );
}

// A manual check's answer: still open (warning), unsupported here (info), or failed (error).
const checkTones: Record<Exclude<CheckResult, 'notChecked'>, 'info' | 'warning' | 'error'> = {
  inProgress: 'warning',
  notFound: 'warning',
  checkUnavailable: 'info',
  checkFailed: 'error',
};

const sp = theme.space;
const type = theme.text;
const styles = StyleSheet.create({
  gap: { gap: sp.md },
  spaced: { marginTop: sp.lg },
  block: { marginBottom: sp.xl },
  caption: { ...type.caption, color: c.muted },
  available: { marginTop: sp.xl, gap: sp.xs, minHeight: 20 },
  availableText: { ...type.bodyStrong, color: c.ink, fontVariant: ['tabular-nums'] },
  availableShort: { color: c.error },
  quote: { marginTop: sp.xl },
  stale: { opacity: 0.45 },
  secondaryAmount: { ...type.title, color: c.ink, fontVariant: ['tabular-nums'] },
  display: { ...type.display, color: c.ink, marginTop: sp.xs },
  countdown: {
    ...type.bodyStrong,
    color: c.brand,
    marginTop: sp.lg,
    marginBottom: sp.xl,
    fontVariant: ['tabular-nums'],
  },
  countdownExpired: { color: c.error },
});
