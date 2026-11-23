// Shared, per-user read state: one in-memory copy for the bell (Home) and the Notifications
// screen, persisted through an injected ReadStatePersistence. Pure apart from that injection,
// so it is unit-tested with an in-memory persistence.
import type { NotificationItem } from '../api/contract';
import {
  initialReadState,
  markAllRead,
  markRead,
  parseReadState,
  type ReadState,
} from './readState';

export interface ReadStatePersistence {
  load(userId: string): unknown;
  save(userId: string, state: ReadState): void;
  // Every user's state (sign-out).
  clearAll(): void;
}

type Identified = Pick<NotificationItem, 'id' | 'createdAt'>;

export interface ReadStateStore {
  // Current state, or null until the user's first page has been seen (nothing is unread then).
  get(userId: string): ReadState | null;
  // Called with every successfully loaded page: initialises the state on first use.
  observe(userId: string, items: readonly Identified[]): void;
  markRead(userId: string, item: Identified): void;
  markAllRead(userId: string, items: readonly Identified[]): void;
  // Sign-out: forget every user's state, in memory and on disk.
  clear(): void;
  subscribe(listener: () => void): () => void;
  // Changes on every update (for useSyncExternalStore).
  version(): number;
}

export function createReadStateStore(persistence: ReadStatePersistence): ReadStateStore {
  const cache = new Map<string, ReadState | null>();
  const listeners = new Set<() => void>();
  let revision = 0;

  // Storage failures never break the feed: the state then lives in memory for this run.
  function quietly<T>(action: () => T, fallback: T): T {
    try {
      return action();
    } catch {
      return fallback;
    }
  }

  function current(userId: string): ReadState | null {
    if (!cache.has(userId))
      cache.set(
        userId,
        quietly(() => parseReadState(persistence.load(userId)), null),
      );
    return cache.get(userId) ?? null;
  }

  function set(userId: string, next: ReadState) {
    if (cache.get(userId) === next) return;
    cache.set(userId, next);
    quietly(() => persistence.save(userId, next), undefined);
    revision += 1;
    for (const listener of [...listeners]) listener();
  }

  return {
    get: current,
    observe(userId, items) {
      if (current(userId) === null) set(userId, initialReadState(items));
    },
    markRead(userId, item) {
      const state = current(userId) ?? initialReadState([]);
      set(userId, markRead(state, item));
    },
    markAllRead(userId, items) {
      const state = current(userId) ?? initialReadState([]);
      set(userId, markAllRead(state, items));
    },
    clear() {
      cache.clear();
      quietly(() => persistence.clearAll(), undefined);
      revision += 1;
      for (const listener of [...listeners]) listener();
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    version: () => revision,
  };
}
