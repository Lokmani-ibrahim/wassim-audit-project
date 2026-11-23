import type { NavigatorScreenParams } from '@react-navigation/native';
import type { Currency, TransactionItem } from '../api/contract';

// Authenticated area (APPROVED + ACTIVE only). The onboarding/KYC gate stays in
// navigation/state.ts (routeFor), driven by backend state.
export type TabParamList = {
  Home: undefined;
  // History (GET /api/transactions), one currency at a time.
  Activity: { currency?: Currency } | undefined;
  Profile: undefined;
};

export type AppStackParamList = {
  Tabs: NavigatorScreenParams<TabParamList> | undefined;
  Send: { currency?: Currency } | undefined;
  TopUp: { currency?: Currency } | undefined;
  Convert: { from?: Currency } | undefined;
  Notifications: undefined;
  Security: undefined;
  // testCredit: opened from a notification marked as a back-office test credit (EMAIL-2).
  TransactionDetail: { item: TransactionItem; testCredit?: boolean };
};

// Routes that can render an honest "Not available yet" screen (Send/Activity only when
// auth is mocked in a dev build: no real token, and money movement is never mocked).
export type UnavailableRoute =
  Exclude<keyof AppStackParamList, 'Tabs' | 'TransactionDetail' | 'Notifications'> | 'Activity';

declare global {
  namespace ReactNavigation {
    // eslint-disable-next-line @typescript-eslint/no-empty-object-type
    interface RootParamList extends AppStackParamList {}
  }
}
