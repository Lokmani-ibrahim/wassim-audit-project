import React from 'react';
import { StyleSheet, View } from 'react-native';
import { DefaultTheme, NavigationContainer, type Theme } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { Icon, c, type IconName } from '../components/ui';
import { t } from '../locales';
import { config } from '../config';
import { Home } from '../screens/Home';
import { Profile } from '../screens/Profile';
import { Unavailable } from '../screens/Unavailable';
import { Activity } from '../screens/activity/Activity';
import { TransactionDetail } from '../screens/activity/TransactionDetail';
import { Send } from '../screens/send/Send';
import { Convert } from '../screens/convert/Convert';
import { fxAvailable } from '../fx';
import { topUpPreviewAvailable } from '../topup';
import { AddMoney } from '../screens/topup/AddMoney';
import { Security } from '../screens/Security';
import { Notifications } from '../screens/notifications/Notifications';
import type { AppStackParamList, TabParamList, UnavailableRoute } from './types';

// Rendered only when the backend-state gate (routeFor) says APPROVED + ACTIVE. Everything
// below is the authenticated area: tabs at the root, money-action routes stacked on top.
// Android hardware back is handled by the navigators (stack pop, then tab history to Home).
const Stack = createNativeStackNavigator<AppStackParamList>();
const Tab = createBottomTabNavigator<TabParamList>();

const theme: Theme = {
  ...DefaultTheme,
  colors: {
    ...DefaultTheme.colors,
    primary: c.brand,
    background: c.background,
    card: c.surface,
    text: c.ink,
    border: c.border,
  },
};

const tabIcons: Record<keyof TabParamList, IconName> = {
  Home: 'home',
  Activity: 'clock',
  Profile: 'user',
};

// One component per placeholder route keeps screen identity stable for the navigator.
const placeholder = (route: UnavailableRoute) => {
  function Placeholder() {
    return <Unavailable route={route} />;
  }
  Placeholder.displayName = `Unavailable(${route})`;
  return Placeholder;
};
// Real screens. With mocked auth (dev only) there is no real token and money movement is never
// mocked, so they are honest placeholders instead.
const authMocked = config.mocks.has('auth');
const ActivityScreen = authMocked ? placeholder('Activity') : Activity;
const SendScreen = authMocked ? placeholder('Send') : Send;
// Add money: example deposit instructions in a sandbox build (no backend yet, no credit);
// a production build keeps the honest placeholder.
const TopUpScreen = topUpPreviewAvailable() ? AddMoney : placeholder('TopUp');
// FX is a sandbox demo (contract §14): real only in a sandbox build with a real session;
// a production build keeps the honest placeholder and never calls the demo endpoint.
const ConvertScreen = fxAvailable() ? Convert : placeholder('Convert');

function Tabs() {
  return (
    <Tab.Navigator
      backBehavior="firstRoute"
      screenOptions={({ route }) => ({
        headerShown: false,
        tabBarActiveTintColor: c.brand,
        tabBarInactiveTintColor: c.muted,
        tabBarLabelStyle: { fontSize: 12, fontWeight: '600' },
        tabBarStyle: { borderTopColor: c.border, backgroundColor: c.background },
        tabBarIcon: ({ color, size, focused }) => (
          <View>
            {/* Active tab: thin gold indicator (decorative; the navy label and icon carry it). */}
            {focused && <View style={styles.indicator} />}
            <Icon name={tabIcons[route.name]} color={color} size={Math.min(size, 22)} />
          </View>
        ),
      })}
    >
      <Tab.Screen name="Home" component={Home} options={{ title: t('home') }} />
      <Tab.Screen
        name="Activity"
        component={ActivityScreen}
        options={{ title: t('activityTab') }}
      />
      <Tab.Screen name="Profile" component={Profile} options={{ title: t('profile') }} />
    </Tab.Navigator>
  );
}

export function AuthenticatedApp() {
  return (
    <NavigationContainer theme={theme}>
      <Stack.Navigator
        screenOptions={{ headerShown: false, contentStyle: { backgroundColor: c.background } }}
      >
        <Stack.Screen name="Tabs" component={Tabs} />
        <Stack.Screen name="Send" component={SendScreen} />
        <Stack.Screen name="TopUp" component={TopUpScreen} />
        <Stack.Screen name="Convert" component={ConvertScreen} />
        <Stack.Screen name="Notifications" component={Notifications} />
        <Stack.Screen name="Security" component={Security} />
        <Stack.Screen name="TransactionDetail" component={TransactionDetail} />
      </Stack.Navigator>
    </NavigationContainer>
  );
}

const styles = StyleSheet.create({
  indicator: {
    position: 'absolute',
    top: -9,
    alignSelf: 'center',
    width: 20,
    height: 2,
    borderRadius: 1,
    backgroundColor: c.gold,
  },
});
