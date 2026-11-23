// Pure presentation of one notification (GET /api/notifications, contract §13). The app writes
// all wording; the backend sends data only.
import type { NotificationItem } from '../api/contract';
import type { MessageKey } from '../locales';
import { signedAmount } from '../screens/activity/transactionView';
import { formatDateTime } from '../utils/dates';

export interface NotificationView {
  title: MessageKey;
  // Extra line under the title (test credits only).
  note: MessageKey | null;
  credit: boolean;
  amountText: string;
  dateText: string;
}

export function notificationTitle(
  item: Pick<NotificationItem, 'type' | 'direction' | 'testCredit'>,
): MessageKey {
  // EMAIL-2: a back-office synthetic credit never reads like a real payment, in any build.
  if (item.testCredit) return 'notifTestCredit';
  switch (item.type) {
    case 'TRANSFER':
      return item.direction === 'CREDIT' ? 'notifTransferReceived' : 'notifTransferSent';
    case 'INCOMING_PAYMENT':
      return 'notifMoneyReceived';
    case 'FEE':
      return 'notifFee';
    case 'PAYOUT':
      return 'notifPayout';
    case 'CONVERSION':
      return 'notifConversion';
  }
}

export function notificationView(item: NotificationItem, locale: string): NotificationView {
  return {
    title: notificationTitle(item),
    note: item.testCredit ? 'notifTestCreditNote' : null,
    credit: item.direction === 'CREDIT',
    amountText: signedAmount(item, locale),
    dateText: formatDateTime(item.createdAt, locale),
  };
}

// Bell badge text: nothing at zero, "9+" above nine.
export function badgeText(count: number): string | null {
  if (count <= 0) return null;
  return count > 9 ? '9+' : String(count);
}
