import type { NotificationItem, NotificationPage } from '../api/contract';
import type { Services } from '../types/domain';

export const NOTIFICATIONS_MARKER = '__NEOS_MOCK__:notifications';

// Fixed demo feed in the GET /api/notifications shape (contract §13), newest first. The ids are
// stable so the device-side read state can be exercised. Dev builds only.
export const MOCK_NOTIFICATION_ITEMS: readonly NotificationItem[] = [
  {
    id: '00000000-0000-4000-8000-000000000003',
    type: 'TRANSFER',
    direction: 'DEBIT',
    currency: 'TND',
    amountMinor: 1500,
    transactionId: 9003,
    reference: 'internal_transfer:mock-3',
    testCredit: false,
    createdAt: '2026-11-05T10:30:00Z',
  },
  {
    id: '00000000-0000-4000-8000-000000000002',
    type: 'TRANSFER',
    direction: 'CREDIT',
    currency: 'EUR',
    amountMinor: 1000,
    transactionId: 9002,
    reference: 'internal_transfer:mock-2',
    testCredit: false,
    createdAt: '2026-11-05T09:15:00Z',
  },
  {
    id: '00000000-0000-4000-8000-000000000001',
    type: 'INCOMING_PAYMENT',
    direction: 'CREDIT',
    currency: 'EUR',
    amountMinor: 2500,
    transactionId: 9001,
    reference: 'incoming_payment:mock-1',
    testCredit: true,
    createdAt: '2026-11-05T08:00:00Z',
  },
];

// The cursor is the index of the next item (opaque to callers, as on the wire).
export function createMockNotifications(
  items: readonly NotificationItem[] = MOCK_NOTIFICATION_ITEMS,
): Services['notifications'] {
  return {
    async list(cursor, pageSize): Promise<NotificationPage> {
      const start = cursor ? Number(cursor) : 0;
      const end = start + pageSize;
      const hasMore = end < items.length;
      return {
        items: items.slice(start, end),
        hasMore,
        nextCursor: hasMore ? String(end) : null,
      };
    },
  };
}
