// Pure receipt HTML (rendered to PDF by expo-print in receipts/share.ts). No React Native
// imports, so it is unit-tested in Node (tests/receipts.test.ts).
//
// Every dynamic value, including localized labels, goes through escapeHtml. A Content Security
// Policy blocks scripts and every network load as a second layer: the document is self-contained.
import { t, tf, type MessageKey } from '../locales';
import { formatMoney } from '../money';
import { transactionTitle } from '../screens/activity/transactionView';
import { formatDateTime, formatDateTimeUtc } from '../utils/dates';
import type { ReceiptData } from './model';

export interface ReceiptOptions {
  // BCP 47 locale for money and dates.
  locale: string;
  // Non-production build (same signal as the Home "Sandbox" badge, config.isSandbox).
  sandbox: boolean;
  // The signed-in user's own email. Null omits the row.
  accountEmail: string | null;
  // ISO-8601 instant the receipt was generated.
  generatedAt: string;
}

const ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
};

export function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (char) => ESCAPES[char]!);
}

const esc = (value: string | number) => escapeHtml(String(value));
const label = (key: MessageKey) => esc(t(key));

function row(name: string, value: string, className = ''): string {
  return `<tr><th scope="row">${name}</th><td${className ? ` class="${className}"` : ''}>${value}</td></tr>`;
}

// Local time on one line, UTC on the next (both escaped).
function when(iso: string, locale: string): string {
  return `${esc(formatDateTime(iso, locale))}<br><span class="utc">${esc(formatDateTimeUtc(iso))}</span>`;
}

export function signedReceiptAmount(data: ReceiptData, locale: string): string {
  const sign = data.direction === 'CREDIT' ? '+' : '−';
  return `${sign}${formatMoney(data.amountMinor, data.currency, locale)}`;
}

const STYLES = `
@page { size: A4; margin: 0; }
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; }
body { font-family: -apple-system, Roboto, "Helvetica Neue", Arial, sans-serif; color: #101828;
  font-size: 12px; line-height: 1.45; padding: 40px 44px; position: relative; }
.head { display: flex; justify-content: space-between; align-items: flex-start; }
.brand { font-size: 26px; font-weight: 800; letter-spacing: 1px; color: #1d4ed8; }
.sandbox { border: 2px solid #b54708; color: #b54708; background: #fffaeb; font-weight: 700;
  text-transform: uppercase; letter-spacing: 0.6px; padding: 4px 10px; border-radius: 6px; font-size: 11px; }
.watermark { position: fixed; top: 42%; left: 0; right: 0; text-align: center; font-size: 64px;
  font-weight: 800; color: rgba(181, 71, 8, 0.10); transform: rotate(-24deg); pointer-events: none; }
h1 { font-size: 18px; margin: 18px 0 2px; }
.generated { color: #667085; margin: 0; }
hr { border: 0; border-top: 1px solid #e4e7ec; margin: 20px 0; }
.amount { font-size: 30px; font-weight: 700; margin: 0; font-variant-numeric: tabular-nums; }
.amount.credit { color: #067647; }
.amount.reversed { text-decoration: line-through; color: #667085; }
.status { display: inline-block; margin-top: 8px; padding: 3px 10px; border-radius: 999px;
  font-weight: 700; font-size: 11px; letter-spacing: 0.5px; text-transform: uppercase; }
.status-completed { background: #ecfdf3; color: #067647; border: 1px solid #abefc6; }
.status-reversed { background: #fef3f2; color: #b42318; border: 2px solid #b42318; }
table { width: 100%; border-collapse: collapse; margin-top: 18px; }
th, td { text-align: start; vertical-align: top; padding: 8px 0; border-bottom: 1px solid #f2f4f7; }
th { width: 34%; color: #667085; font-weight: 500; padding-inline-end: 12px; }
td { font-weight: 600; }
.mono { font-family: "Roboto Mono", Menlo, Consolas, monospace; font-weight: 500;
  word-break: break-all; font-size: 11px; }
.utc { color: #667085; font-weight: 500; }
.reversed-text { color: #b42318; }
.footer { margin-top: 28px; color: #475467; font-size: 11px; }
.footer p { margin: 0 0 4px; }
.footer .sandbox-note { color: #b54708; font-weight: 700; }
`;

export function buildReceiptHtml(data: ReceiptData, options: ReceiptOptions): string {
  const { locale, sandbox, accountEmail, generatedAt } = options;
  const reversed = data.status === 'REVERSED';
  const credit = data.direction === 'CREDIT';
  const statusText = t(reversed ? 'txREVERSED' : 'txCOMPLETED');
  const amountClass = ['amount', credit && 'credit', reversed && 'reversed'].filter(Boolean);

  const rows = [
    row(label('typeLabel'), label(transactionTitle(data))),
    row(label('directionLabel'), label(`direction${data.direction}`)),
    row(label('currencyLabel'), esc(data.currency)),
    row(label('statusLabel'), esc(statusText), reversed ? 'reversed-text' : ''),
  ];
  if (data.occurredAt) rows.push(row(label('dateTime'), when(data.occurredAt, locale)));
  else if (data.confirmedAt)
    rows.push(row(label('receiptConfirmedAt'), when(data.confirmedAt, locale)));
  rows.push(row(label('transactionId'), esc(data.transactionId), 'mono'));
  rows.push(row(label('reference'), esc(data.reference), 'mono'));
  if (data.recipient) rows.push(row(label('to'), esc(data.recipient)));
  if (accountEmail) rows.push(row(label('receiptAccountHolder'), esc(accountEmail)));

  const generated = tf('receiptGenerated', {
    local: formatDateTime(generatedAt, locale),
    utc: formatDateTimeUtc(generatedAt),
  });

  return `<!DOCTYPE html>
<html lang="${esc(locale)}">
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${label('receiptTitle')}</title>
<style>${STYLES}</style>
</head>
<body>
${sandbox ? `<div class="watermark" aria-hidden="true">${label('receiptSandbox')}</div>` : ''}
<div class="head">
<div class="brand">NEOS</div>
${sandbox ? `<div class="sandbox">${label('receiptSandbox')}</div>` : ''}
</div>
<h1>${label('receiptTitle')}</h1>
<p class="generated">${esc(generated)}</p>
<hr>
<p class="${amountClass.join(' ')}">${esc(signedReceiptAmount(data, locale))}</p>
<span class="status ${reversed ? 'status-reversed' : 'status-completed'}">${esc(statusText)}</span>
<table>
${rows.join('\n')}
</table>
<div class="footer">
<p>${label('receiptFooter')}</p>
${sandbox ? `<p class="sandbox-note">${label('receiptSandboxNote')}</p>` : ''}
</div>
</body>
</html>`;
}
