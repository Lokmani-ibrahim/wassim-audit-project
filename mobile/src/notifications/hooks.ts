import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { AppState } from 'react-native';
import { useFocusEffect, useIsFocused } from '@react-navigation/native';
import type { NotificationItem } from '../api/contract';
import { services } from '../services';
import { notificationReadState } from './readStore';
import { unreadCount, type ReadState } from './readState';

export const FEED_PAGE_SIZE = 20;

// The signed-in user's read state, re-rendering on every change (bell and screen stay in sync).
export function useReadState(userId: string | null): ReadState | null {
  useSyncExternalStore(notificationReadState.subscribe, notificationReadState.version);
  return userId ? notificationReadState.get(userId) : null;
}

// Bell badge: unread items on the newest page (device-side state; no cross-device count in
// Option A). Refetched on focus and on return to the foreground. Any failure shows no badge
// (never a guessed number).
export function useUnreadCount(userId: string | null): number {
  const state = useReadState(userId);
  const focused = useIsFocused();
  const [items, setItems] = useState<NotificationItem[]>([]);
  const mounted = useRef(true);
  const requestId = useRef(0);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const refresh = useCallback(async () => {
    if (!userId) return;
    const id = ++requestId.current;
    try {
      const page = await services.notifications.list(null, FEED_PAGE_SIZE);
      if (!mounted.current || id !== requestId.current) return;
      notificationReadState.observe(userId, page.items);
      setItems(page.items);
    } catch {
      if (mounted.current && id === requestId.current) setItems([]);
    }
  }, [userId]);

  useFocusEffect(
    useCallback(() => {
      void refresh();
    }, [refresh]),
  );
  useEffect(() => {
    const listener = AppState.addEventListener('change', (next) => {
      if (next === 'active' && focused) void refresh();
    });
    return () => listener.remove();
  }, [focused, refresh]);

  return unreadCount(state, items);
}
