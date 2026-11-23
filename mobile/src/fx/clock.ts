// Quote lifetime on the device, without trusting the device clock against the server's.
// The server stamps asOf after it received the request, so the moment the app SENT the quote
// request is never later than asOf. Counting the quote's lifetime (expiresAt − asOf, both server
// time) from that send moment, minus a margin for the conversion request to travel, gives a
// device-time deadline that is never later than the real expiry.
import type { FxQuote } from '../api/contract';

export const SEND_MARGIN_MS = 1500;

export interface ReceivedQuote {
  quote: FxQuote;
  // Device time (ms) when the quote request was sent.
  sentAt: number;
}

export function quoteDeadline({ quote, sentAt }: ReceivedQuote): number {
  const lifetime = Date.parse(quote.expiresAt) - Date.parse(quote.asOf);
  if (!Number.isFinite(lifetime) || lifetime <= 0) return sentAt;
  return sentAt + lifetime - SEND_MARGIN_MS;
}

export function secondsLeft(received: ReceivedQuote, now: number): number {
  return Math.max(0, Math.ceil((quoteDeadline(received) - now) / 1000));
}

export function isExpired(received: ReceivedQuote, now: number): boolean {
  return now >= quoteDeadline(received);
}
