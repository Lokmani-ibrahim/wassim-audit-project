// Device-side read/unread state for the notification feed (Option A: the backend has no read
// state). Pure: no I/O. Persistence lives in readStore.ts, sharing in store.ts.
//
// Model: everything created at or before `watermark` is read, plus the ids in `readIds`.
// - First use (no saved state): the watermark is the newest item seen, so notifications that
//   existed before the feature shipped do not light the badge.
// - "Mark all as read": the watermark moves to the newest item seen.
// - Opening one notification adds its id to `readIds` (capped, newest first).
import type { NotificationItem } from '../api/contract';

export const READ_STATE_VERSION = 1;
export const MAX_READ_IDS = 500;

export interface ReadState {
  version: typeof READ_STATE_VERSION;
  // createdAt of the newest notification known to be read (ISO-8601), or null.
  watermark: string | null;
  // Individually read ids, newest first, at most MAX_READ_IDS.
  readIds: string[];
}

type Dated = Pick<NotificationItem, 'createdAt'>;
type Identified = Pick<NotificationItem, 'id' | 'createdAt'>;

const INSTANT = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/;

// Orders two ISO-8601 instants exactly, including sub-millisecond digits (the server sends
// microseconds; Date.parse keeps milliseconds only). Unparseable values sort first.
export function compareInstants(a: string, b: string): number {
  const ma = INSTANT.exec(a);
  const mb = INSTANT.exec(b);
  if (ma && mb) {
    const [, sa = '', ra = ''] = ma;
    const [, sb = '', rb = ''] = mb;
    if (sa !== sb) return sa < sb ? -1 : 1;
    const fa = ra.padEnd(9, '0');
    const fb = rb.padEnd(9, '0');
    return fa === fb ? 0 : fa < fb ? -1 : 1;
  }
  const ta = Date.parse(a);
  const tb = Date.parse(b);
  const na = Number.isNaN(ta) ? -Infinity : ta;
  const nb = Number.isNaN(tb) ? -Infinity : tb;
  return na === nb ? 0 : na < nb ? -1 : 1;
}

function newest(items: readonly Dated[]): string | null {
  let best: string | null = null;
  for (const item of items)
    if (best === null || compareInstants(item.createdAt, best) > 0) best = item.createdAt;
  return best;
}

// State on first use: everything currently in the feed counts as already read.
export function initialReadState(items: readonly Dated[]): ReadState {
  return { version: READ_STATE_VERSION, watermark: newest(items), readIds: [] };
}

export function isUnread(state: ReadState | null, item: Identified): boolean {
  // Not initialised yet: nothing is shown as unread until the first page has been seen.
  if (!state) return false;
  if (state.watermark !== null && compareInstants(item.createdAt, state.watermark) <= 0)
    return false;
  return !state.readIds.includes(item.id);
}

export function unreadCount(state: ReadState | null, items: readonly Identified[]): number {
  return items.filter((item) => isUnread(state, item)).length;
}

export function markRead(state: ReadState, item: Identified): ReadState {
  if (!isUnread(state, item)) return state;
  return {
    ...state,
    readIds: [item.id, ...state.readIds.filter((id) => id !== item.id)].slice(0, MAX_READ_IDS),
  };
}

export function markAllRead(state: ReadState, items: readonly Identified[]): ReadState {
  const top = newest(items);
  const watermark =
    top !== null && (state.watermark === null || compareInstants(top, state.watermark) > 0)
      ? top
      : state.watermark;
  // Ids now covered by the watermark no longer need to be kept.
  const covered = new Set(
    items
      .filter((item) => watermark !== null && compareInstants(item.createdAt, watermark) <= 0)
      .map((item) => item.id),
  );
  return { ...state, watermark, readIds: state.readIds.filter((id) => !covered.has(id)) };
}

// Saved JSON → state. Anything unexpected → null (the caller re-initialises: no badge burst).
export function parseReadState(value: unknown): ReadState | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null;
  const body = value as Record<string, unknown>;
  if (body.version !== READ_STATE_VERSION) return null;
  const watermark = body.watermark;
  if (watermark !== null && (typeof watermark !== 'string' || Number.isNaN(Date.parse(watermark))))
    return null;
  if (!Array.isArray(body.readIds) || !body.readIds.every((id) => typeof id === 'string'))
    return null;
  return {
    version: READ_STATE_VERSION,
    watermark,
    readIds: (body.readIds as string[]).slice(0, MAX_READ_IDS),
  };
}
