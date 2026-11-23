// Serialize credential writes and clears. A logout clear enqueued after an in-flight
// write finishes before any subsequent account's write can begin.
export function createCredentialQueue() {
  let pending: Promise<void> = Promise.resolve();
  return {
    enqueue(operation: () => Promise<void>): Promise<void> {
      const next = pending.catch(() => undefined).then(operation);
      pending = next;
      return next;
    },
  };
}
