import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createCredentialQueue } from '../src/services/credentialQueue';

test('logout clear waits for an in-flight write and precedes the next account write', async () => {
  const queue = createCredentialQueue();
  const events: string[] = [];
  let signalStart!: () => void;
  const oldWriteStarted = new Promise<void>((resolve) => {
    signalStart = resolve;
  });
  let finishOldWrite!: () => void;
  const oldWriteGate = new Promise<void>((resolve) => {
    finishOldWrite = resolve;
  });
  const oldWrite = queue.enqueue(async () => {
    events.push('old-start');
    signalStart();
    await oldWriteGate;
    events.push('old-finish');
  });
  const logout = queue.enqueue(async () => {
    events.push('clear');
  });
  const newWrite = queue.enqueue(async () => {
    events.push('new-write');
  });
  await oldWriteStarted;
  assert.deepEqual(events, ['old-start']);
  finishOldWrite();
  await Promise.all([oldWrite, logout, newWrite]);
  assert.deepEqual(events, ['old-start', 'old-finish', 'clear', 'new-write']);
});

test('a failed old write cannot prevent logout cleanup', async () => {
  const queue = createCredentialQueue();
  const oldWrite = queue.enqueue(async () => {
    throw new Error('SecureStore write failed');
  });
  let cleared = false;
  const logout = queue.enqueue(async () => {
    cleared = true;
  });
  await assert.rejects(oldWrite);
  await logout;
  assert.equal(cleared, true);
});
