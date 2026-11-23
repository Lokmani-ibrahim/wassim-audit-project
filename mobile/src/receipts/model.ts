// What a PDF receipt may show, built only from data the app already has (no extra request).
// Receipts are generated on the device and are not legally authoritative: a server-issued
// receipt is out of scope (docs/api/INTEGRATION_STATUS.md).
import type {
  Currency,
  TransactionDirection,
  TransactionItem,
  TransactionStatus,
  TransactionType,
} from '../api/contract';
import type { TransferOutcome } from '../transfers/engine';

export interface ReceiptData {
  transactionId: number;
  type: TransactionType;
  direction: TransactionDirection;
  currency: Currency;
  // Integer minor units, always positive (the sign comes from direction).
  amountMinor: string | number;
  status: TransactionStatus;
  reference: string;
  // Ledger time (history occurredAt) when known.
  occurredAt: string | null;
  // Device time the app saw the transfer confirmed; shown only when occurredAt is unknown.
  confirmedAt: string | null;
  // Masked recipient email from the lookup (Send only). History items carry no counterparty,
  // so this is null for them and the receipt omits the row (never invented).
  recipient: string | null;
}

export function receiptFromHistory(item: TransactionItem): ReceiptData {
  return {
    transactionId: item.transactionId,
    type: item.type,
    direction: item.direction,
    currency: item.currency,
    amountMinor: item.amountMinor,
    status: item.status,
    reference: item.reference,
    occurredAt: item.occurredAt,
    confirmedAt: null,
    recipient: null,
  };
}

// Only a confirmed transfer gets a receipt. An uncertain (pending) or rejected transfer has
// nothing to document: null, and the Send screen shows no receipt action.
export function receiptFromOutcome(outcome: TransferOutcome): ReceiptData | null {
  if (outcome.kind !== 'succeeded') return null;
  const { transfer } = outcome;
  return {
    transactionId: outcome.transactionId,
    type: 'TRANSFER',
    direction: 'DEBIT',
    currency: transfer.currency,
    amountMinor: transfer.amountMinor,
    status: outcome.status,
    reference: outcome.reference,
    occurredAt: outcome.occurredAt ?? null,
    confirmedAt: outcome.confirmedAt,
    recipient: transfer.maskedEmail,
  };
}
