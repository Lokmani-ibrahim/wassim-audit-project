import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildReceiptHtml, escapeHtml, type ReceiptOptions } from '../src/receipts/html';
import { receiptFromHistory, receiptFromOutcome, type ReceiptData } from '../src/receipts/model';
import type { TransactionItem } from '../src/api/contract';
import type { CheckResult, PendingTransfer, TransferOutcome } from '../src/transfers/engine';
import { formatDateTimeUtc } from '../src/utils/dates';
import { t } from '../src/locales';

const options: ReceiptOptions = {
  locale: 'en',
  sandbox: false,
  accountEmail: 'me@example.com',
  generatedAt: '2026-11-05T13:03:12Z',
};

const historyItem: TransactionItem = {
  transactionId: 4711,
  type: 'TRANSFER',
  direction: 'DEBIT',
  currency: 'EUR',
  amountMinor: 150000,
  status: 'COMPLETED',
  reference: 'internal_transfer:ct_abc_def',
  occurredAt: '2026-10-30T14:03:11Z',
};

const pending: PendingTransfer = {
  version: 1,
  senderUserId: 'sender',
  recipientUserId: 'recipient',
  maskedEmail: 'j***@example.com',
  currency: 'TND',
  amountMinor: '1005',
  idempotencyKey: 'key',
  reference: 'internal_transfer:ct_sender_key',
  createdAt: '2026-11-05T13:00:00Z',
  manualChecks: 0,
};

const succeeded = (extra: Partial<Extract<TransferOutcome, { kind: 'succeeded' }>> = {}) =>
  ({
    kind: 'succeeded',
    transfer: pending,
    transactionId: 9001,
    reference: pending.reference,
    status: 'COMPLETED',
    via: 'response',
    confirmedAt: '2026-11-05T13:00:02Z',
    ...extra,
  }) as TransferOutcome;

const html = (data: ReceiptData, extra: Partial<ReceiptOptions> = {}) =>
  buildReceiptHtml(data, { ...options, ...extra });

// Text of the <body>, which is where dynamic values go.
const body = (doc: string) => doc.slice(doc.indexOf('<body>'));

test('escapeHtml escapes the five HTML-significant characters', () => {
  assert.equal(
    escapeHtml(`<a href="x" onclick='y'>&</a>`),
    '&lt;a href=&quot;x&quot; onclick=&#39;y&#39;&gt;&amp;&lt;/a&gt;',
  );
  assert.equal(escapeHtml('plain'), 'plain');
});

test('dynamic values are escaped: no script or markup injection via reference or emails', () => {
  const evil = `"><script>alert(1)</script><img src=x onerror=alert(2)>&`;
  const data = { ...receiptFromHistory({ ...historyItem, reference: evil }), recipient: evil };
  const doc = html(data, { accountEmail: `${evil}@example.com` });
  assert.ok(!/<script/i.test(doc), 'no <script> element');
  assert.ok(!/<img/i.test(doc), 'no injected <img>');
  assert.ok(!doc.includes(evil), 'raw value never inserted');
  assert.ok(doc.includes('&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;'));
  // Three insertions: reference, recipient, account holder.
  assert.equal(doc.split('&lt;img src=x onerror=alert(2)&gt;&amp;').length - 1, 3);
});

test('the document blocks scripts and network loads with a CSP', () => {
  assert.match(
    html(receiptFromHistory(historyItem)),
    /<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">/,
  );
});

test('money uses the currency scale and the direction sign', () => {
  const eur = html(receiptFromHistory(historyItem));
  assert.ok(eur.includes('−€1,500.00'), 'EUR: 2 decimals, debit = minus');
  const tnd = html(
    receiptFromHistory({ ...historyItem, currency: 'TND', amountMinor: 1005, direction: 'CREDIT' }),
  );
  assert.match(tnd, /\+TND\s1\.005/, 'TND: 3 decimals, credit = plus');
  assert.ok(!/1\.01\b/.test(body(tnd)), 'never rounded to 2 decimals');
});

test('REVERSED is clearly marked; COMPLETED is not', () => {
  const reversed = html(receiptFromHistory({ ...historyItem, status: 'REVERSED' }));
  assert.ok(reversed.includes('status-reversed'));
  assert.ok(reversed.includes('class="amount reversed"'));
  assert.ok(body(reversed).includes(`>${t('txREVERSED')}<`));
  const completed = html(receiptFromHistory(historyItem));
  assert.ok(!body(completed).includes('reversed'));
  assert.ok(body(completed).includes(`>${t('txCOMPLETED')}<`));
});

test('sandbox mark only in non-production builds', () => {
  const sandbox = html(receiptFromHistory(historyItem), { sandbox: true });
  assert.ok(sandbox.includes(t('receiptSandbox')));
  assert.ok(sandbox.includes(t('receiptSandboxNote')));
  const production = html(receiptFromHistory(historyItem), { sandbox: false });
  assert.ok(!body(production).includes(t('receiptSandbox')));
  assert.ok(!production.includes('class="sandbox'));
  assert.ok(!production.includes('watermark"'));
});

test('header, footer, generation time, local + UTC date, ids', () => {
  const doc = html(receiptFromHistory(historyItem));
  assert.ok(doc.includes('>NEOS<'));
  assert.ok(doc.includes(`<h1>${t('receiptTitle')}</h1>`));
  assert.ok(doc.includes(t('receiptFooter')));
  assert.ok(doc.includes(formatDateTimeUtc(options.generatedAt)));
  assert.ok(doc.includes('2026-10-30 14:03:11 UTC'));
  assert.ok(doc.includes('>4711<'));
  assert.ok(doc.includes('internal_transfer:ct_abc_def'));
  assert.ok(doc.includes('me@example.com'));
  assert.ok(doc.includes(t('txTRANSFER_DEBIT')));
});

test('history receipts have no counterparty row; Send receipts show the masked recipient', () => {
  const history = html(receiptFromHistory(historyItem));
  assert.ok(!body(history).includes(`>${t('to')}<`));
  const send = html(receiptFromOutcome(succeeded())!);
  assert.ok(body(send).includes(`>${t('to')}<`));
  assert.ok(send.includes('j***@example.com'));
});

test('account holder row is omitted when no email is known', () => {
  const doc = html(receiptFromHistory(historyItem), { accountEmail: null });
  assert.ok(!doc.includes(t('receiptAccountHolder')));
});

test('Send receipt: device confirmation time unless the ledger time is known', () => {
  const fromResponse = html(receiptFromOutcome(succeeded())!);
  assert.ok(fromResponse.includes(t('receiptConfirmedAt')));
  assert.ok(fromResponse.includes('2026-11-05 13:00:02 UTC'));
  const fromHistory = html(
    receiptFromOutcome(succeeded({ via: 'history', occurredAt: '2026-11-05T12:59:59Z' }))!,
  );
  assert.ok(!fromHistory.includes(t('receiptConfirmedAt')));
  assert.ok(fromHistory.includes(t('dateTime')));
  assert.ok(fromHistory.includes('2026-11-05 12:59:59 UTC'));
});

test('no receipt for uncertain (pending) or rejected transfers', () => {
  for (const check of ['notFound', 'notFoundYet', 'checkFailed'] as CheckResult[])
    assert.equal(receiptFromOutcome({ kind: 'uncertain', transfer: pending, check }), null);
  assert.equal(
    receiptFromOutcome({ kind: 'rejected', reason: 'insufficientFunds', intent: pending }),
    null,
  );
});

test('a confirmed transfer gets a receipt, REVERSED included', () => {
  const data = receiptFromOutcome(succeeded())!;
  assert.deepEqual(
    {
      type: data.type,
      direction: data.direction,
      currency: data.currency,
      amount: data.amountMinor,
    },
    { type: 'TRANSFER', direction: 'DEBIT', currency: 'TND', amount: '1005' },
  );
  assert.equal(receiptFromOutcome(succeeded({ status: 'REVERSED' }))?.status, 'REVERSED');
});
