import React from 'react';
import type { ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { SafeAreaInsetsContext, useSafeAreaInsets } from 'react-native-safe-area-context';
import { c } from './ui';
import type { MockSet } from '../mockFeatures';

// Development-only strip naming every mocked feature. It owns the status-bar inset so screens
// below it (which pad their own top inset) are not double-padded.
export function DevBanner({ mocks, children }: { mocks: MockSet; children: ReactNode }) {
  const insets = useSafeAreaInsets();
  if (!__DEV__ || mocks.size === 0) return <>{children}</>;
  const names = [...mocks].join(', ');
  return (
    <View style={styles.root}>
      <View
        accessible
        accessibilityRole="text"
        accessibilityLabel={`Development build. Mocked features: ${names}`}
        style={[styles.banner, { paddingTop: insets.top + 4 }]}
      >
        <Text style={styles.text} numberOfLines={1}>
          MOCK: {names}
        </Text>
      </View>
      <SafeAreaInsetsContext.Provider value={{ ...insets, top: 0 }}>
        <View style={styles.root}>{children}</View>
      </SafeAreaInsetsContext.Provider>
    </View>
  );
}
const styles = StyleSheet.create({
  root: { flex: 1 },
  banner: { backgroundColor: c.ink, paddingBottom: 4, paddingHorizontal: 12 },
  text: {
    color: c.onBrand,
    fontSize: 11,
    fontWeight: '600',
    letterSpacing: 0.4,
    textAlign: 'center',
  },
});
