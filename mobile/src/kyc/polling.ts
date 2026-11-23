// KYC status polling after the Sumsub SDK closes. The decision arrives by webhook on the backend;
// the app only re-reads GET /api/profile. Pure scheduler: timing is injected for unit tests.
//
// Backoff 5 s → 10 s → 20 s → 30 s, then every 60 s; stops after 15 minutes of polling.
// A manual "Check status" or a return to the foreground restarts the schedule (reset()).
export const POLL_DELAYS_MS = [5_000, 10_000, 20_000, 30_000, 60_000] as const;
export const POLL_MAX_MS = 15 * 60_000;

export function pollDelay(attempt: number): number {
  const index = Math.min(Math.max(attempt, 0), POLL_DELAYS_MS.length - 1);
  return POLL_DELAYS_MS[index] ?? 60_000;
}

export interface PollerDeps {
  // Re-reads the status. Errors are swallowed by the poller (the schedule continues); session
  // expiry is handled globally by the API client.
  tick: () => Promise<void>;
  schedule: (callback: () => void, ms: number) => unknown;
  cancel: (handle: unknown) => void;
  now: () => number;
}

export interface Poller {
  start(): void;
  stop(): void;
  reset(): void;
  readonly running: boolean;
}

export function createPoller(deps: PollerDeps): Poller {
  let handle: unknown = null;
  let attempt = 0;
  let startedAt = 0;
  let running = false;
  let generation = 0;

  function next() {
    if (!running) return;
    const delay = pollDelay(attempt);
    if (deps.now() - startedAt + delay > POLL_MAX_MS) {
      running = false;
      handle = null;
      return;
    }
    const mine = generation;
    handle = deps.schedule(() => {
      handle = null;
      if (!running || mine !== generation) return;
      attempt += 1;
      void deps
        .tick()
        .catch(() => undefined)
        .finally(() => {
          if (mine === generation) next();
        });
    }, delay);
  }

  function stop() {
    running = false;
    generation += 1;
    if (handle !== null) deps.cancel(handle);
    handle = null;
  }

  function start() {
    stop();
    running = true;
    attempt = 0;
    startedAt = deps.now();
    next();
  }

  return {
    start,
    stop,
    reset: start,
    get running() {
      return running;
    },
  };
}
