import React from 'react';
import { StyleSheet, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { Badge, Button, Icon, Intro, Screen, c, type IconName } from '../components/ui';
import { t, type MessageKey } from '../locales';
import type { UnavailableRoute } from '../navigation/types';

// Honest placeholder: no service call, no preview data, nothing that implies money moved.
const content: Record<UnavailableRoute, { title: MessageKey; body: MessageKey; icon: IconName }> = {
  Send: { title: 'send', body: 'unavailableSend', icon: 'arrow-up-right' },
  TopUp: { title: 'add', body: 'unavailableTopUp', icon: 'plus' },
  Convert: { title: 'convert', body: 'unavailableConvert', icon: 'repeat' },
  Activity: { title: 'history', body: 'unavailableHistory', icon: 'clock' },
  Security: { title: 'security', body: 'unavailableSecurity', icon: 'shield' },
};

export function Unavailable({ route }: { route: UnavailableRoute }) {
  const navigation = useNavigation();
  const item = content[route];
  // The Activity tab has no back button (hardware back returns to Home); stack screens do.
  const back =
    route !== 'Activity' && navigation.canGoBack() ? () => navigation.goBack() : undefined;
  return (
    <Screen
      brand
      back={back}
      footer={back ? <Button secondary label={t('back')} onPress={back} /> : undefined}
    >
      <View style={styles.symbol}>
        <Icon name={item.icon} size={36} color={c.brand} />
      </View>
      <Intro title={t(item.title)} body={t(item.body)} />
      <Badge tone="warning" label={t('unavailableTitle')} />
    </Screen>
  );
}
const styles = StyleSheet.create({
  symbol: {
    width: 80,
    height: 80,
    borderRadius: 40,
    backgroundColor: c.paleBlue,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 28,
  },
});
