// The app's own prompts (biometric prompt, the device-credential screen, the Keycloak tab) can
// send the app to the background on Android. Those must not start the inactivity timer, or a
// slow PIN entry while confirming a transfer would lock the app right after it.
//
// While at least one guarded operation is running, background events are reported as `own`.
// If the operation ends while the app is still in the background (e.g. the user pressed Home
// during the prompt), listeners are told so the timer starts from that moment instead.
let depth = 0;
const releaseListeners = new Set<() => void>();

export function isAutoLockSuppressed(): boolean {
  return depth > 0;
}

export function onAutoLockRelease(listener: () => void): () => void {
  releaseListeners.add(listener);
  return () => {
    releaseListeners.delete(listener);
  };
}

export async function withAutoLockSuppressed<T>(operation: () => Promise<T>): Promise<T> {
  depth += 1;
  try {
    return await operation();
  } finally {
    depth -= 1;
    if (depth === 0) for (const listener of [...releaseListeners]) listener();
  }
}
