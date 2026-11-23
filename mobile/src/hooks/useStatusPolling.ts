import { useEffect, useRef } from 'react';
import { AppState } from 'react-native';
import { createPoller, type Poller } from '../kyc/polling';

// Polls while `active` (a KYC waiting screen is shown) and the app is in the foreground.
// Restarts the schedule on return to the foreground and whenever `resetKey` changes
// (e.g. after the SDK closes or a manual "Check status").
export function useStatusPolling(active: boolean, tick: () => Promise<void>, resetKey: number) {
  const tickRef = useRef(tick);
  tickRef.current = tick;
  const poller = useRef<Poller | null>(null);
  if (!poller.current)
    poller.current = createPoller({
      tick: () => tickRef.current(),
      schedule: (callback, ms) => setTimeout(callback, ms),
      cancel: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>),
      now: () => Date.now(),
    });
  useEffect(() => {
    const instance = poller.current!;
    if (!active) {
      instance.stop();
      return;
    }
    if (AppState.currentState === 'active') instance.start();
    const listener = AppState.addEventListener('change', (state) => {
      if (state === 'active') instance.reset();
      else instance.stop();
    });
    return () => {
      listener.remove();
      instance.stop();
    };
  }, [active, resetKey]);
}
