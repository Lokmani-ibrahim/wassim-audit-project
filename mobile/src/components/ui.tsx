import React, { useEffect, useRef, useState } from 'react';
import {
  AccessibilityInfo,
  ActivityIndicator,
  Animated,
  Easing,
  I18nManager,
  Keyboard,
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import type { ReactNode, ComponentProps } from 'react';
import { SafeAreaView } from 'react-native-safe-area-context';
import Feather from '@expo/vector-icons/Feather';
import { theme } from '../theme';
import { t } from '../locales';

export const c = theme.colors;
export type IconName = ComponentProps<typeof Feather>['name'];
export function Icon({
  name,
  size = 22,
  color = c.ink,
}: {
  name: IconName;
  size?: number;
  color?: string;
}) {
  return <Feather name={name} size={size} color={color} accessible={false} />;
}
export function Copy({
  children,
  style,
  muted = false,
}: {
  children: ReactNode;
  style?: ComponentProps<typeof Text>['style'];
  muted?: boolean;
}) {
  return <Text style={[s.body, muted && { color: c.muted }, style]}>{children}</Text>;
}
export function Title({ children }: { children: ReactNode }) {
  return (
    <Text accessibilityRole="header" style={s.title}>
      {children}
    </Text>
  );
}
export function Brand({
  light = false,
  onLongPress,
}: {
  light?: boolean;
  onLongPress?: () => void;
}) {
  return (
    <Pressable
      onLongPress={onLongPress}
      delayLongPress={1800}
      accessible={false}
      style={s.brandWrap}
    >
      <Text style={[s.brand, light && { color: c.onBrand }]}>{t('brand')}</Text>
      <View style={s.brandDot} />
    </Pressable>
  );
}
export function IconButton({
  icon,
  label,
  onPress,
  badge = null,
}: {
  icon: IconName;
  label: string;
  onPress: () => void;
  // Small count bubble (e.g. unread notifications). The label must carry the same information.
  badge?: string | null;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      style={({ pressed }) => [s.iconButton, pressed && s.pressed]}
    >
      <Icon name={icon} />
      {badge !== null && (
        <View style={s.iconBadge} importantForAccessibility="no-hide-descendants">
          <Text style={s.iconBadgeText}>{badge}</Text>
        </View>
      )}
    </Pressable>
  );
}
export function Button({
  label,
  onPress,
  disabled,
  loading,
  secondary = false,
  next = false,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  loading?: boolean;
  secondary?: boolean;
  // Trailing arrow: only for a step forward in a flow (Continue), never for an action.
  next?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: disabled || loading, busy: loading }}
      onPress={onPress}
      disabled={disabled || loading}
      // One press feedback: primary darkens, secondary takes the neutral fill (§2.4).
      style={({ pressed }) => [
        s.button,
        secondary && s.secondaryButton,
        pressed && (secondary ? s.secondaryPressed : s.primaryPressed),
        disabled && s.disabled,
      ]}
    >
      {loading ? (
        <ActivityIndicator color={secondary ? c.brand : c.onBrand} />
      ) : (
        <>
          <Text
            style={[s.buttonText, secondary && { color: c.ink }, disabled && { color: c.muted }]}
          >
            {label}
          </Text>
          {next && !secondary && (
            <Icon
              name={I18nManager.isRTL ? 'arrow-left' : 'arrow-right'}
              color={disabled ? c.muted : c.onBrand}
              size={20}
            />
          )}
        </>
      )}
    </Pressable>
  );
}
export function TextButton({
  label,
  onPress,
  tone = 'accent',
}: {
  label: string;
  onPress: () => void;
  // 'error' for an action the user should think twice about (e.g. Stop tracking).
  tone?: 'accent' | 'error';
}) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [s.textButton, pressed && s.pressed]}
    >
      <Text style={[s.link, tone === 'error' && { color: c.error }]}>{label}</Text>
    </Pressable>
  );
}
export function Screen({
  children,
  footer,
  back,
  progress,
  brand = false,
  onBrandLongPress,
  header,
  revealOnKeyboard = false,
  refreshControl,
  stickyHeaderIndices,
}: {
  refreshControl?: ComponentProps<typeof ScrollView>['refreshControl'];
  // Indices of direct children that stick to the top while scrolling (pass children as an array).
  stickyHeaderIndices?: number[];
  children: ReactNode;
  footer?: ReactNode;
  back?: () => void;
  progress?: number;
  brand?: boolean;
  onBrandLongPress?: () => void;
  header?: ReactNode;
  revealOnKeyboard?: boolean;
}) {
  const scroll = useRef<ScrollView>(null);
  const keyboardVisible = useRef(false);
  function reveal() {
    if (revealOnKeyboard && keyboardVisible.current)
      scroll.current?.scrollToEnd({ animated: true });
  }
  useEffect(() => {
    const show = Keyboard.addListener('keyboardDidShow', () => {
      keyboardVisible.current = true;
      if (revealOnKeyboard) scroll.current?.scrollToEnd({ animated: true });
    });
    const hide = Keyboard.addListener('keyboardDidHide', () => {
      keyboardVisible.current = false;
    });
    return () => {
      show.remove();
      hide.remove();
    };
  }, [revealOnKeyboard]);
  return (
    <SafeAreaView style={s.safe} edges={['top', 'bottom', 'left', 'right']}>
      <KeyboardAvoidingView style={s.flex} behavior={Platform.OS === 'ios' ? 'padding' : 'height'}>
        <View style={s.header}>
          {header || (
            <>
              {back ? (
                <IconButton
                  icon={I18nManager.isRTL ? 'arrow-right' : 'arrow-left'}
                  label={t('back')}
                  onPress={back}
                />
              ) : (
                <View style={{ width: 48 }} />
              )}
              {/* Pushed screens: no caption (the "Personal account" label added nothing, A11). */}
              {brand ? <Brand onLongPress={onBrandLongPress} /> : <View />}
              <View style={{ width: 48 }} />
            </>
          )}
        </View>
        {progress !== undefined && (
          <View
            accessible
            accessibilityLabel={`${Math.round(progress * 100)}%`}
            accessibilityRole="progressbar"
            accessibilityValue={{ min: 0, max: 100, now: Math.round(progress * 100) }}
            style={s.progress}
          >
            <View style={[s.progressFill, { width: `${progress * 100}%` }]} />
          </View>
        )}
        <ScrollView
          ref={scroll}
          onLayout={reveal}
          onContentSizeChange={reveal}
          keyboardShouldPersistTaps="handled"
          keyboardDismissMode="on-drag"
          automaticallyAdjustKeyboardInsets
          refreshControl={refreshControl}
          stickyHeaderIndices={stickyHeaderIndices}
          contentContainerStyle={s.content}
        >
          {children}
        </ScrollView>
        {footer && <View style={s.footer}>{footer}</View>}
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}
export function Fade({ children, id }: { children: ReactNode; id: string }) {
  const [opacity] = useState(() => new Animated.Value(0));
  useEffect(() => {
    let alive = true;
    opacity.setValue(0);
    void AccessibilityInfo.isReduceMotionEnabled().then((reduced) => {
      if (alive)
        Animated.timing(opacity, {
          toValue: 1,
          duration: reduced ? 0 : theme.motion.quick,
          useNativeDriver: true,
        }).start();
    });
    return () => {
      alive = false;
      opacity.stopAnimation();
    };
  }, [id, opacity]);
  return <Animated.View style={[s.flex, { opacity }]}>{children}</Animated.View>;
}
export function Intro({ title, body }: { title: string; body: string }) {
  return (
    <View style={s.intro}>
      <Title>{title}</Title>
      <Copy muted>{body}</Copy>
    </View>
  );
}
export function InfoRow({
  icon,
  title,
  description,
  end,
}: {
  icon: IconName;
  title: string;
  description?: string;
  end?: ReactNode;
}) {
  return (
    <View style={s.infoRow}>
      <View style={s.infoIcon}>
        <Icon name={icon} color={c.brand} size={20} />
      </View>
      <View style={s.flex}>
        <Copy style={s.rowTitle}>{title}</Copy>
        {!!description && (
          <Copy muted style={s.small}>
            {description}
          </Copy>
        )}
      </View>
      {end}
    </View>
  );
}
export function Badge({
  label,
  tone = 'success',
}: {
  label: string;
  tone?: 'success' | 'warning' | 'error';
}) {
  const color = c[tone];
  return (
    <View style={[s.badge, { backgroundColor: toneBackground[tone] }]}>
      <Icon name={toneIcon[tone]} size={14} color={color} />
      <Text style={[s.badgeText, { color }]}>{label}</Text>
    </View>
  );
}
type Tone = 'info' | 'success' | 'warning' | 'error';
const toneIcon: Record<Tone, IconName> = {
  info: 'info',
  success: 'check-circle',
  warning: 'clock',
  error: 'alert-circle',
};
const toneBackground: Record<Tone, string> = {
  info: c.fill,
  success: c.paleSuccess,
  warning: c.paleWarning,
  error: c.paleError,
};
const toneColor: Record<Tone, string> = {
  info: c.ink,
  success: c.success,
  warning: c.warning,
  error: c.error,
};
// One notice component (pending, sandbox, no screen lock, errors): tone background, 20 px tone
// icon, optional title, body. Errors and warnings are announced.
export function Banner({
  tone = 'info',
  title,
  body,
  icon,
}: {
  tone?: Tone;
  title?: string;
  body: string;
  icon?: IconName;
}) {
  const alert = tone === 'error' || tone === 'warning';
  return (
    <View
      accessible
      accessibilityRole={alert ? 'alert' : undefined}
      accessibilityLiveRegion={alert ? 'polite' : 'none'}
      style={[s.banner, { backgroundColor: toneBackground[tone] }]}
    >
      <Icon
        name={icon ?? (tone === 'warning' ? 'alert-triangle' : toneIcon[tone])}
        color={toneColor[tone]}
        size={20}
      />
      <View style={s.bannerText}>
        {!!title && <Text style={s.bannerTitle}>{title}</Text>}
        <Text style={[s.bannerBody, tone === 'error' && !title && { color: c.error }]}>{body}</Text>
      </View>
    </View>
  );
}
export function ErrorNotice({ message }: { message?: string | null }) {
  return message ? <Banner tone="error" body={message} /> : null;
}
// Top of a status screen (transfer or conversion result, KYC, unlock): a 48 px tone icon without a
// tinted tile, the title, then one body paragraph (§2.4 "Status screens").
export function StatusHeader({
  tone,
  title,
  body,
}: {
  tone: Exclude<Tone, 'info'>;
  title: string;
  body?: string;
}) {
  return (
    <View style={s.status}>
      <Icon name={toneIcon[tone]} color={toneColor[tone]} size={theme.sizes.status} />
      <Text accessibilityRole="header" style={s.statusTitle}>
        {title}
      </Text>
      {!!body && <Copy muted>{body}</Copy>}
    </View>
  );
}
// An action in flight (sending, checking): spinner in the accent colour with what is happening.
// Content loading uses skeletons instead.
export function Working({ label }: { label: string }) {
  return (
    <View style={s.working} accessible accessibilityLiveRegion="polite" accessibilityLabel={label}>
      <ActivityIndicator size="large" color={c.brand} />
      <Copy muted>{label}</Copy>
    </View>
  );
}
export function Field({
  label,
  value,
  onChangeText,
  hint,
  error,
  editable = true,
  kind = editable ? 'phone' : 'email',
  newPassword = false,
  onFocus,
  onBlur,
}: {
  label: string;
  value: string;
  onChangeText?: (value: string) => void;
  hint?: string;
  error?: string;
  editable?: boolean;
  kind?: 'phone' | 'email' | 'password';
  newPassword?: boolean;
  onFocus?: () => void;
  onBlur?: () => void;
}) {
  const [focused, setFocused] = useState(false);
  return (
    <View style={s.field}>
      <Copy style={s.label}>{label}</Copy>
      <TextInput
        accessibilityLabel={label}
        value={value}
        onChangeText={onChangeText}
        editable={editable}
        onFocus={() => {
          setFocused(true);
          onFocus?.();
        }}
        onBlur={() => {
          setFocused(false);
          onBlur?.();
        }}
        keyboardType={
          kind === 'phone' ? 'phone-pad' : kind === 'email' ? 'email-address' : 'default'
        }
        textContentType={
          kind === 'phone'
            ? 'telephoneNumber'
            : kind === 'email'
              ? 'emailAddress'
              : newPassword
                ? 'newPassword'
                : 'password'
        }
        autoComplete={
          kind === 'phone'
            ? 'tel'
            : kind === 'email'
              ? 'email'
              : newPassword
                ? 'new-password'
                : 'current-password'
        }
        autoCapitalize="none"
        autoCorrect={false}
        secureTextEntry={kind === 'password'}
        placeholder={kind === 'phone' ? t('phonePlaceholder') : undefined}
        placeholderTextColor={c.muted}
        maxLength={kind === 'phone' ? 32 : kind === 'email' ? 254 : undefined}
        returnKeyType="done"
        style={[
          s.input,
          !editable && { backgroundColor: c.disabled, color: c.muted },
          focused && s.inputFocused,
          !!error && { borderColor: c.error },
        ]}
      />
      {(error || hint) && (
        <Text accessibilityLiveRegion="polite" style={[s.hint, error && { color: c.error }]}>
          {error || hint}
        </Text>
      )}
    </View>
  );
}
export function Sheet({
  visible,
  title,
  children,
  onClose,
}: {
  visible: boolean;
  title: string;
  children: ReactNode;
  onClose: () => void;
}) {
  const reduced = useReducedMotion();
  const [offset] = useState(() => new Animated.Value(0));
  // Slide in once the modal is on screen (a native animation started before its view is attached
  // would not apply). Reduce-motion: no slide, no fade.
  const slideIn = () => {
    if (reduced) return;
    offset.setValue(1);
    Animated.timing(offset, {
      toValue: 0,
      duration: 240,
      easing: Easing.out(Easing.cubic),
      useNativeDriver: true,
    }).start();
  };
  return (
    <Modal
      transparent
      visible={visible}
      onShow={slideIn}
      animationType={reduced ? 'none' : 'fade'}
      onRequestClose={onClose}
      statusBarTranslucent
    >
      <View style={s.scrim}>
        <Pressable
          accessibilityLabel={t('close')}
          accessibilityRole="button"
          style={StyleSheet.absoluteFill}
          onPress={onClose}
        />
        <Animated.View
          style={{
            maxHeight: '85%',
            transform: [
              { translateY: offset.interpolate({ inputRange: [0, 1], outputRange: [0, 48] }) },
            ],
          }}
        >
          <SafeAreaView edges={['bottom']} style={s.sheet} accessibilityViewIsModal>
            <View style={s.sheetHeader}>
              <Text accessibilityRole="header" style={s.sectionTitle}>
                {title}
              </Text>
              <IconButton icon="x" label={t('close')} onPress={onClose} />
            </View>
            <ScrollView contentContainerStyle={{ gap: 16 }} keyboardShouldPersistTaps="handled">
              {children}
            </ScrollView>
          </SafeAreaView>
        </Animated.View>
      </View>
    </Modal>
  );
}
// System "reduce motion" setting, kept up to date while the app runs.
export function useReducedMotion(): boolean {
  const [reduced, setReduced] = useState(false);
  useEffect(() => {
    let alive = true;
    void AccessibilityInfo.isReduceMotionEnabled().then((value) => alive && setReduced(value));
    const listener = AccessibilityInfo.addEventListener('reduceMotionChanged', setReduced);
    return () => {
      alive = false;
      listener.remove();
    };
  }, []);
  return reduced;
}
// Placeholder block shown while content loads; pulses gently unless reduce-motion is on.
export function Skeleton({
  width,
  height = 14,
  radius = 6,
  dark = false,
}: {
  width: number | `${number}%`;
  height?: number;
  radius?: number;
  dark?: boolean;
}) {
  const reduced = useReducedMotion();
  const [pulse] = useState(() => new Animated.Value(1));
  useEffect(() => {
    if (reduced) return;
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(pulse, { toValue: 0.55, duration: 700, useNativeDriver: true }),
        Animated.timing(pulse, { toValue: 1, duration: 700, useNativeDriver: true }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, [reduced, pulse]);
  return (
    <Animated.View
      style={{
        width,
        height,
        borderRadius: radius,
        backgroundColor: dark ? c.onDarkBorder : c.border,
        opacity: reduced ? 1 : pulse,
      }}
    />
  );
}
// Rows of skeletons shaped like list rows (icon, two lines, amount). Announced as loading.
export function SkeletonRows({ count = 3 }: { count?: number }) {
  return (
    <View accessible accessibilityRole="progressbar" accessibilityLabel={t('loadingShort')}>
      {Array.from({ length: count }, (_, index) => (
        <View key={index} style={s.skeletonRow}>
          <Skeleton width={40} height={40} radius={20} />
          <View style={[s.flex, { gap: 8 }]}>
            <Skeleton width="55%" />
            <Skeleton width="35%" height={12} />
          </View>
          <Skeleton width={64} />
        </View>
      ))}
    </View>
  );
}
export const s = StyleSheet.create({
  flex: { flex: 1 },
  safe: { flex: 1, backgroundColor: c.background },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 12,
    minHeight: 64,
    width: '100%',
    maxWidth: 568,
    alignSelf: 'center',
  },
  content: {
    paddingHorizontal: theme.sizes.gutter,
    paddingTop: 24,
    paddingBottom: 32,
    flexGrow: 1,
    width: '100%',
    maxWidth: theme.sizes.content,
    alignSelf: 'center',
  },
  footer: {
    paddingHorizontal: 24,
    paddingTop: 12,
    paddingBottom: 12,
    gap: 8,
    width: '100%',
    maxWidth: theme.sizes.content,
    alignSelf: 'center',
  },
  body: { ...theme.text.body, color: c.ink, textAlign: 'auto' },
  title: {
    ...theme.text.title,
    color: c.ink,
    marginBottom: theme.space.sm,
    textAlign: 'auto',
  },
  sectionTitle: { ...theme.text.headline, color: c.ink, flex: 1 },
  intro: { marginBottom: theme.space.xxl, gap: 0 },
  brandWrap: { flexDirection: 'row', alignItems: 'flex-end', paddingVertical: 12 },
  brand: { color: c.ink, fontWeight: '800', fontSize: 25, letterSpacing: 2 },
  brandDot: { width: 6, height: 6, backgroundColor: c.gold, marginStart: 3, marginBottom: 5 },
  iconButton: {
    width: 48,
    height: 48,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 24,
  },
  iconBadge: {
    position: 'absolute',
    top: 6,
    right: 4,
    minWidth: 18,
    height: 18,
    borderRadius: 9,
    paddingHorizontal: 4,
    backgroundColor: c.error,
    alignItems: 'center',
    justifyContent: 'center',
  },
  iconBadgeText: { color: c.onBrand, fontSize: 11, fontWeight: '700' },
  pressed: { backgroundColor: c.fill },
  button: {
    minHeight: theme.sizes.button,
    backgroundColor: c.brand,
    borderRadius: theme.radius.md,
    paddingVertical: theme.space.md,
    paddingHorizontal: 20,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: theme.space.md,
  },
  primaryPressed: { backgroundColor: c.pressed },
  secondaryButton: { backgroundColor: c.surface, borderWidth: 1, borderColor: c.border },
  secondaryPressed: { backgroundColor: c.fill },
  disabled: { backgroundColor: c.disabled, borderColor: c.disabled },
  buttonText: {
    ...theme.text.bodyStrong,
    fontWeight: '600',
    color: c.onBrand,
    flexShrink: 1,
    textAlign: 'center',
  },
  textButton: {
    minHeight: theme.sizes.minTouch,
    minWidth: theme.sizes.minTouch,
    alignItems: 'center',
    justifyContent: 'center',
    alignSelf: 'center',
    paddingHorizontal: theme.space.md,
    borderRadius: theme.radius.md,
  },
  link: { color: c.brand, fontWeight: '600', fontSize: 15, lineHeight: 20 },
  progress: { height: 2, backgroundColor: c.border, marginHorizontal: 24 },
  progressFill: { height: 2, backgroundColor: c.brand },
  infoRow: { flexDirection: 'row', alignItems: 'center', gap: 16, paddingVertical: 18 },
  infoIcon: { width: 24, alignItems: 'center' },
  rowTitle: { fontWeight: '600' },
  skeletonRow: { flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 14 },
  small: { fontSize: 14, lineHeight: 21 },
  badge: {
    alignSelf: 'flex-start',
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingVertical: theme.space.xs,
    paddingHorizontal: 10,
    borderRadius: theme.radius.pill,
  },
  badgeText: { ...theme.text.micro, fontWeight: '600', flexShrink: 1 },
  banner: {
    borderRadius: theme.radius.md,
    padding: theme.space.lg,
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: theme.space.md,
  },
  bannerText: { flex: 1, gap: 2 },
  bannerTitle: { ...theme.text.bodyStrong, color: c.ink },
  bannerBody: { ...theme.text.caption, color: c.ink },
  status: { gap: theme.space.md, marginBottom: theme.space.xl },
  statusTitle: { ...theme.text.title, color: c.ink, textAlign: 'auto' },
  working: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: theme.space.lg },
  field: { marginBottom: theme.space.xl },
  label: { ...theme.text.caption, fontWeight: '500', marginBottom: theme.space.sm },
  input: {
    minHeight: theme.sizes.input,
    paddingHorizontal: theme.space.lg,
    paddingVertical: theme.space.md,
    borderRadius: theme.radius.sm,
    borderWidth: 1,
    borderColor: c.border,
    backgroundColor: c.surface,
    fontSize: 16,
    color: c.ink,
    textAlign: I18nManager.isRTL ? 'right' : 'left',
  },
  // Focus: 2 px accent; the padding shrinks by 1 so the text does not move.
  inputFocused: { borderColor: c.brand, borderWidth: 2, paddingHorizontal: theme.space.lg - 1 },
  hint: { ...theme.text.caption, color: c.muted, marginTop: theme.space.sm },
  scrim: { flex: 1, justifyContent: 'flex-end', backgroundColor: theme.scrim },
  sheet: {
    backgroundColor: c.background,
    borderTopStartRadius: 16,
    borderTopEndRadius: 16,
    paddingHorizontal: theme.sizes.gutter,
    paddingBottom: theme.space.lg,
    flexShrink: 1,
  },
  sheetHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.space.md,
    paddingTop: theme.space.lg,
    paddingBottom: theme.space.sm,
  },
});
