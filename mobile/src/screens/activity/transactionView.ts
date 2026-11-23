// Pure presentation of one history item (GET /api/transactions, contract §6). amountMinor is
// always positive on the wire; the sign shown comes from direction only.
import type { TransactionItem } from '../../api/contract';
import type { MessageKey } from '../../locales';
import { formatMoney } from '../../money';
import { formatDateTime } from '../../utils/dates';

export interface TransactionView {
  title: MessageKey;
  credit: boolean;
  // "+€10.00" / "−€10.00" (U+2212 minus).
  amountText: string;
  reversed: boolean;
  status: MessageKey;
  dateText: string;
}

export function transactionTitle(item: Pick<TransactionItem, 'type' | 'direction'>): MessageKey {
  if (item.type === 'TRANSFER')
    return item.direction === 'CREDIT' ? 'txTRANSFER_CREDIT' : 'txTRANSFER_DEBIT';
  return `tx${item.type}`;
}

export function signedAmount(
  item: Pick<TransactionItem, 'direction' | 'amountMinor' | 'currency'>,
  locale: string,
): string {
  const credit = item.direction === 'CREDIT';
  return `${credit ? '+' : '\u2212'}${formatMoney(item.amountMinor, item.currency, locale)}`;
}

export function transactionView(item: TransactionItem, locale: string): TransactionView {
  return {
    title: transactionTitle(item),
    credit: item.direction === 'CREDIT',
    amountText: signedAmount(item, locale),
    reversed: item.status === 'REVERSED',
    status: item.status === 'REVERSED' ? 'txREVERSED' : 'txCOMPLETED',
    dateText: formatDateTime(item.occurredAt, locale),
  };
}
