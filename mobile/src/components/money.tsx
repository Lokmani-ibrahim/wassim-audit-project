import React, { useEffect, useRef, useState } from 'react';
import {
  Animated,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  type StyleProp,
  type TextStyle,
} from 'react-native';
import { CURRENCIES, type Currency, type TransactionItem } from '../api/contract';
import { theme } from '../theme';
import { locale, t } from '../locales';
import { transactionView } from '../screens/activity/transactionView';
import { formatTime } from '../utils/dates';
import { initials, splitAmount } from '../utils/amountParts';
import { Icon, c, useReducedMotion } from './ui';

// A formatted amount with smaller minor units ("€72" + ".90"). The text itself comes from
// src/money unchanged. When it changes (refresh, wallet switch) it fades and settles in, unless
// reduce-motion is on.
export function Amount({
  text,
  style,
  minorStyle,
  accessibilityLabel,
  live = false,
}: {
  text: string;
  style: StyleProp<TextStyle>;
  minorStyle: StyleProp<TextStyle>;
  accessibilityLabel?: string;
  live?: boolean;
}) {
  const reduced = useReducedMotion();
  const [enter] = useState(() => new Animated.Value(1));
  const previous = useRef(text);
  useEffect(() => {
    if (previous.current === text) return;
    previous.current = text;
    if (reduced) return;
    enter.setValue(0);
    Animated.timing(enter, { toValue: 1, duration: 260, useNativeDriver: true }).start();
  }, [text, reduced, enter]);
  const parts = splitAmount(text, locale);
  return (
    <Animated.View
      style={{
        opacity: enter.interpolate({ inputRange: [0, 1], outputRange: [0.2, 1] }),
        transform: [{ translateY: enter.interpolate({ inputRange: [0, 1], outputRange: [6, 0] }) }],
      }}
    >
      <Text
        accessibilityLabel={accessibilityLabel}
        accessibilityLiveRegion={live ? 'polite' : 'none'}
        adjustsFontSizeToFit
        numberOfLines={1}
        style={style}
      >
        {parts.main}
        {!!parts.minor && <Text style={minorStyle}>{parts.minor}</Text>}
      </Text>
    </Animated.View>
  );
}

// Day section header for history. The end slot is reserved for the end-of-day balance once the
// backend sends it per item (balanceAfterMinor, INTEGRATION_STATUS R8); never computed here (D4).
export function DayHeader({ label, balance = null }: { label: string; balance?: string | null }) {
  return (
    <View style={styles.dayHeader}>
      <Text accessibilityRole="header" style={styles.dayLabel}>
        {label}
      </Text>
      {balance !== null && <Text style={styles.dayBalance}>{balance}</Text>}
    </View>
  );
}

// Horizontal currency selector (the 6 ledger currencies).
export function CurrencyChips({
  value,
  onChange,
  disabled = false,
}: {
  value: Currency;
  onChange: (currency: Currency) => void;
  disabled?: boolean;
}) {
  return (
    <ScrollView
      horizontal
      showsHorizontalScrollIndicator={false}
      contentContainerStyle={styles.chips}
      accessibilityRole="tablist"
    >
      {CURRENCIES.map((currency) => {
        const selected = currency === value;
        return (
          <Pressable
            key={currency}
            accessibilityRole="tab"
            accessibilityState={{ selected, disabled }}
            disabled={disabled}
            onPress={() => onChange(currency)}
            style={({ pressed }) => [
              styles.chip,
              selected && styles.chipSelected,
              pressed && styles.pressed,
            ]}
          >
            <Text style={[styles.chipText, selected && styles.chipTextSelected]}>{currency}</Text>
          </Pressable>
        );
      })}
    </ScrollView>
  );
}

// One history row. Credits are green with "+", debits ink with "−"; REVERSED is badged.
export function TransactionRow({
  item,
  onPress,
  timeOnly = false,
  counterparty = null,
}: {
  item: TransactionItem;
  onPress?: () => void;
  // Under a day header: the time is enough (the label still reads the full date).
  timeOnly?: boolean;
  // Display name when known (history items carry none yet): initials instead of the arrow.
  counterparty?: string | null;
}) {
  const view = transactionView(item, locale);
  const letters = counterparty ? initials(counterparty) : '';
  const label = [
    t(view.title),
    view.amountText,
    view.reversed ? t('txREVERSED') : '',
    view.dateText,
  ]
    .filter(Boolean)
    .join(', ');
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      disabled={!onPress}
      style={({ pressed }) => [styles.row, pressed && styles.pressed]}
    >
      <View style={[styles.icon, view.credit ? styles.iconCredit : styles.iconDebit]}>
        {letters ? (
          <Text style={[styles.initials, view.credit && { color: c.success }]}>{letters}</Text>
        ) : (
          <Icon
            name={view.credit ? 'arrow-down-left' : 'arrow-up-right'}
            size={18}
            color={view.credit ? c.success : c.brand}
          />
        )}
      </View>
      <View style={{ flex: 1 }}>
        <Text style={styles.title}>{t(view.title)}</Text>
        <Text style={styles.date}>
          {timeOnly ? formatTime(item.occurredAt, locale) : view.dateText}
        </Text>
      </View>
      <View style={styles.end}>
        <Text
          numberOfLines={1}
          style={[
            styles.amount,
            view.credit && styles.amountCredit,
            view.reversed && styles.amountReversed,
          ]}
        >
          {view.amountText}
        </Text>
        {view.reversed && (
          <View style={styles.reversed}>
            <Text style={styles.reversedText}>{t('txREVERSED')}</Text>
          </View>
        )}
      </View>
    </Pressable>
  );
}

// Amount entry shared by Send and Convert: label, a large tabular input, the live unambiguous
// reading of what was typed ("1,000" in TND = TND 1.000), then the error or the hint. Parsing and
// validation stay in the screen (src/money); this is presentation.
export function AmountField({
  label,
  value,
  onChangeText,
  scale,
  invalid,
  preview,
  error,
  hint,
}: {
  label: string;
  value: string;
  onChangeText: (value: string) => void;
  scale: number;
  invalid: boolean;
  preview: string | null;
  error: string | null;
  hint?: string;
}) {
  const [focused, setFocused] = useState(false);
  return (
    <View>
      <Text style={styles.fieldLabel}>{label}</Text>
      <TextInput
        accessibilityLabel={label}
        value={value}
        onChangeText={onChangeText}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        keyboardType="decimal-pad"
        inputMode="decimal"
        placeholder={scale > 0 ? `0.${'0'.repeat(scale)}` : '0'}
        placeholderTextColor={c.muted}
        maxLength={24}
        autoCorrect={false}
        style={[
          styles.amountInput,
          focused && styles.amountInputFocused,
          invalid && styles.amountInputError,
        ]}
      />
      {!!preview && (
        <Text accessibilityLiveRegion="polite" style={styles.preview}>
          {preview}
        </Text>
      )}
      {!!error && (
        <Text accessibilityLiveRegion="polite" style={styles.fieldError}>
          {error}
        </Text>
      )}
      {!!hint && <Text style={styles.fieldHint}>{hint}</Text>}
    </View>
  );
}

// Section label above a control group (e.g. "From", "Currency").
export function FieldLabel({ children }: { children: string }) {
  return <Text style={styles.fieldLabel}>{children}</Text>;
}

// Label/value row with an optional copy button (transaction detail, transfer result, receive).
export function DetailRow({
  label,
  value,
  mono = false,
  copyLabel,
  copied = false,
  onCopy,
}: {
  label: string;
  value: string;
  mono?: boolean;
  copyLabel?: string;
  copied?: boolean;
  onCopy?: () => void;
}) {
  return (
    <View style={styles.detailRow}>
      <View style={{ flex: 1 }}>
        <Text style={styles.detailLabel}>{label}</Text>
        <Text selectable style={[styles.detailValue, mono && styles.mono]}>
          {value}
        </Text>
      </View>
      {onCopy && (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={copied ? t('copied') : copyLabel}
          onPress={onCopy}
          style={({ pressed }) => [styles.copy, pressed && styles.pressed]}
        >
          <Icon name={copied ? 'check' : 'copy'} size={16} color={c.brand} />
          <Text style={styles.copyText}>{copied ? t('copied') : t('copy')}</Text>
        </Pressable>
      )}
    </View>
  );
}

const type = theme.text;
const sp = theme.space;
const styles = StyleSheet.create({
  pressed: { backgroundColor: c.fill },
  chips: { gap: sp.sm, paddingVertical: sp.xs },
  // Selected chip in ink, not the action navy, so it is not read as a button (§2.4).
  chip: {
    minHeight: theme.sizes.minTouch,
    minWidth: 60,
    paddingHorizontal: sp.md,
    borderRadius: theme.radius.sm,
    borderWidth: 1,
    borderColor: c.border,
    backgroundColor: c.surface,
    alignItems: 'center',
    justifyContent: 'center',
  },
  chipSelected: { backgroundColor: c.ink, borderColor: c.ink },
  chipText: { ...type.caption, color: c.ink, fontWeight: '600' },
  chipTextSelected: { color: c.onBrand },
  fieldLabel: { ...type.caption, fontWeight: '500', color: c.ink, marginBottom: sp.sm },
  amountInput: {
    minHeight: 64,
    paddingHorizontal: sp.lg,
    borderRadius: theme.radius.sm,
    borderWidth: 1,
    borderColor: c.border,
    backgroundColor: c.surface,
    ...type.title,
    color: c.ink,
    fontVariant: ['tabular-nums'],
  },
  amountInputFocused: { borderColor: c.brand, borderWidth: 2, paddingHorizontal: sp.lg - 1 },
  amountInputError: { borderColor: c.error },
  preview: { ...type.bodyStrong, marginTop: sp.sm, color: c.ink, fontVariant: ['tabular-nums'] },
  fieldError: { ...type.caption, marginTop: sp.sm, color: c.error },
  fieldHint: { ...type.caption, marginTop: sp.sm, color: c.muted },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    minHeight: 64,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: c.border,
  },
  icon: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center' },
  initials: { color: c.brand, fontSize: 14, fontWeight: '600' },
  dayHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
    paddingTop: 20,
    paddingBottom: 8,
    backgroundColor: c.background,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: c.border,
  },
  dayLabel: { color: c.muted, fontSize: 14, lineHeight: 20, fontWeight: '600' },
  dayBalance: { color: c.muted, fontSize: 14, lineHeight: 20, fontVariant: ['tabular-nums'] },
  iconCredit: { backgroundColor: c.paleSuccess },
  iconDebit: { backgroundColor: c.paleBlue },
  title: { color: c.ink, fontSize: 15, fontWeight: '600' },
  date: { color: c.muted, fontSize: 13, marginTop: 2 },
  end: { alignItems: 'flex-end', flexShrink: 1, gap: 4 },
  amount: { color: c.ink, fontSize: 15, fontWeight: '600', fontVariant: ['tabular-nums'] },
  amountCredit: { color: c.success },
  amountReversed: { textDecorationLine: 'line-through', color: c.muted },
  reversed: {
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 6,
    backgroundColor: c.paleError,
  },
  reversedText: { color: c.error, fontSize: 11, fontWeight: '700' },
  detailRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: sp.md,
    minHeight: 64,
    paddingVertical: sp.md,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: c.border,
  },
  detailLabel: { ...type.caption, color: c.muted },
  detailValue: { ...type.body, color: c.ink },
  mono: { fontFamily: 'monospace', fontSize: 13, lineHeight: 18 },
  copy: {
    minHeight: theme.sizes.minTouch,
    minWidth: theme.sizes.minTouch,
    paddingHorizontal: sp.md,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    borderRadius: theme.radius.md,
  },
  copyText: { ...type.caption, color: c.brand, fontWeight: '600' },
});
