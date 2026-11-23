import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULT_LOCK_TIMEOUT_SECONDS,
  disablesUnlock,
  initialLockState,
  isLocked,
  parseLockTimeoutSeconds,
  reduceLock,
  type LockEvent,
  type LockPolicy,
  type LockState,
} from '../src/lock/machine';
import { classifyLocalAuthError, classifySecureStoreError } from '../src/lock/classify';
import {
  EMPTY_LOCK_SETTINGS,
  LOCK_SETTINGS_KEY,
  disabledSettings,
  enabledSettings,
  isEnabledFor,
  markOffered,
  readLockSettings,
  settingsForSignIn,
  shouldOffer,
  type LockSettingsStore,
} from '../src/lock/settings';
import {
  isAutoLockSuppressed,
  onAutoLockRelease,
  withAutoLockSuppressed,
} from '../src/lock/suppress';
import { resolveConfig } from '../src/config';

const policy: LockPolicy = { timeoutMs: 60_000, maxFailures: 3 };
const run = (events: LockEvent[], from: LockState = initialLockState) =>
  events.reduce((state, event) => reduceLock(state, event, policy), from);
const unlocked: LockState = { name: 'unlocked', backgroundAt: null };

// --- cold start ---------------------------------------------------------------------------

test('cold start: preference on + stored session => locked before anything is restored', () => {
  const state = run([{ type: 'coldStart', enabled: true, hasSession: true }]);
  assert.deepEqual(state, { name: 'locked', failures: 0, notice: null });
  assert.equal(isLocked(state), true);
});

test('cold start: no stored session or preference off => no lock', () => {
  assert.deepEqual(run([{ type: 'coldStart', enabled: true, hasSession: false }]), { name: 'off' });
  assert.deepEqual(run([{ type: 'coldStart', enabled: false, hasSession: true }]), { name: 'off' });
});

test('cold start: each lock is a new state object (auto-prompt once per lock)', () => {
  const a = run([{ type: 'coldStart', enabled: true, hasSession: true }]);
  const b = run([{ type: 'coldStart', enabled: true, hasSession: true }]);
  assert.notEqual(a, b);
});

// --- background timeout -------------------------------------------------------------------

test('background shorter than the timeout => stays unlocked', () => {
  const state = run(
    [
      { type: 'background', at: 1_000 },
      { type: 'foreground', at: 60_999 },
    ],
    unlocked,
  );
  assert.deepEqual(state, unlocked);
});

test('background for the timeout or longer => locked', () => {
  const state = run(
    [
      { type: 'background', at: 1_000 },
      { type: 'foreground', at: 61_000 },
    ],
    unlocked,
  );
  assert.equal(state.name, 'locked');
});

test('the first background timestamp counts when several arrive', () => {
  const state = run(
    [
      { type: 'background', at: 0 },
      { type: 'background', at: 50_000 },
      { type: 'foreground', at: 70_000 },
    ],
    unlocked,
  );
  assert.equal(state.name, 'locked');
});

test('a clock that went backwards locks (fail-safe)', () => {
  const state = run(
    [
      { type: 'background', at: 100_000 },
      { type: 'foreground', at: 5_000 },
    ],
    unlocked,
  );
  assert.equal(state.name, 'locked');
});

test("the app's own prompts and the Keycloak tab do not start the timer", () => {
  const state = run(
    [
      { type: 'background', at: 0, own: true },
      { type: 'foreground', at: 10 * 60_000 },
    ],
    unlocked,
  );
  assert.deepEqual(state, unlocked);
});

test('an own prompt that ended in the background starts the timer from that moment', () => {
  // The hook reports a plain background event when the prompt is released in the background.
  const state = run(
    [
      { type: 'background', at: 0, own: true },
      { type: 'background', at: 30_000 },
      { type: 'foreground', at: 95_000 },
    ],
    unlocked,
  );
  assert.equal(state.name, 'locked');
});

test('timeout 0 locks on every return from the background', () => {
  const events: LockEvent[] = [
    { type: 'background', at: 5 },
    { type: 'foreground', at: 5 },
  ];
  const state = events.reduce<LockState>(
    (s, e) => reduceLock(s, e, { timeoutMs: 0, maxFailures: 3 }),
    unlocked,
  );
  assert.equal(state.name, 'locked');
});

// --- prompt results -----------------------------------------------------------------------

const lockedState = (): LockState => run([{ type: 'coldStart', enabled: true, hasSession: true }]);

test('success unlocks', () => {
  assert.deepEqual(run([{ type: 'unlockResult', result: 'success' }], lockedState()), unlocked);
});

test('cancel stays locked with a notice and is not counted as a failure', () => {
  const state = run(
    [
      { type: 'unlockResult', result: 'cancelled' },
      { type: 'unlockResult', result: 'cancelled' },
      { type: 'unlockResult', result: 'cancelled' },
      { type: 'unlockResult', result: 'cancelled' },
    ],
    lockedState(),
  );
  assert.deepEqual(state, { name: 'locked', failures: 0, notice: 'cancelled' });
});

test('3 failed attempts => Keycloak login required (preference kept)', () => {
  const two = run(
    [
      { type: 'unlockResult', result: 'failed' },
      { type: 'unlockResult', result: 'failed' },
    ],
    lockedState(),
  );
  assert.deepEqual(two, { name: 'locked', failures: 2, notice: 'failed' });
  const three = run([{ type: 'unlockResult', result: 'failed' }], two);
  assert.deepEqual(three, { name: 'loginRequired', reason: 'tooManyAttempts' });
  assert.equal(disablesUnlock(three), false);
});

test('failures are counted across cancels', () => {
  const state = run(
    [
      { type: 'unlockResult', result: 'failed' },
      { type: 'unlockResult', result: 'cancelled' },
      { type: 'unlockResult', result: 'failed' },
      { type: 'unlockResult', result: 'failed' },
    ],
    lockedState(),
  );
  assert.equal(state.name, 'loginRequired');
});

test('any system lockout => Keycloak login required', () => {
  const state = run([{ type: 'unlockResult', result: 'lockout' }], lockedState());
  assert.deepEqual(state, { name: 'loginRequired', reason: 'lockout' });
  assert.equal(disablesUnlock(state), false);
});

test('biometrics changed (Keystore key invalidated) => unlock off + Keycloak login', () => {
  const state = run([{ type: 'unlockResult', result: 'invalidated' }], lockedState());
  assert.deepEqual(state, { name: 'loginRequired', reason: 'biometricsChanged' });
  assert.equal(disablesUnlock(state), true);
});

test('biometrics / screen lock removed => unlock off + Keycloak login', () => {
  const state = run([{ type: 'unlockResult', result: 'notEnrolled' }], lockedState());
  assert.deepEqual(state, { name: 'loginRequired', reason: 'notEnrolled' });
  assert.equal(disablesUnlock(state), true);
});

test('login required is terminal until the user leaves it (sign-out / password path)', () => {
  const required = run([{ type: 'unlockResult', result: 'lockout' }], lockedState());
  const after = run(
    [
      { type: 'unlockResult', result: 'success' },
      { type: 'foreground', at: 1 },
      { type: 'session', present: false, enabled: false },
    ],
    required,
  );
  assert.deepEqual(after, required);
  assert.deepEqual(run([{ type: 'signedOut' }], required), { name: 'off' });
});

test('a session change while locked never unlocks', () => {
  const state = run([{ type: 'session', present: true, enabled: true }], lockedState());
  assert.equal(state.name, 'locked');
});

// --- preference, session, sign-out --------------------------------------------------------

test('disabled preference: no lock on background/foreground', () => {
  const state = run([
    { type: 'coldStart', enabled: false, hasSession: true },
    { type: 'session', present: true, enabled: false },
    { type: 'background', at: 0 },
    { type: 'foreground', at: 10 * 60_000 },
  ]);
  assert.deepEqual(state, { name: 'off' });
});

test('turning unlock on/off', () => {
  const on = run([{ type: 'enabled' }]);
  assert.deepEqual(on, unlocked);
  assert.deepEqual(run([{ type: 'disabled' }], on), { name: 'off' });
});

test('turning unlock off never unlocks a locked app', () => {
  assert.equal(run([{ type: 'disabled' }], lockedState()).name, 'locked');
});

test('sign-in by the user who enabled unlock re-arms it; session loss turns it off', () => {
  const armed = run([{ type: 'session', present: true, enabled: true }]);
  assert.deepEqual(armed, unlocked);
  assert.deepEqual(run([{ type: 'session', present: false, enabled: false }], armed), {
    name: 'off',
  });
  // Another user (preference not theirs) => off.
  assert.deepEqual(run([{ type: 'session', present: true, enabled: false }], armed), {
    name: 'off',
  });
});

test('sign-out clears any lock state', () => {
  for (const from of [
    lockedState(),
    unlocked,
    { name: 'loginRequired', reason: 'lockout' },
  ] as LockState[])
    assert.deepEqual(run([{ type: 'signedOut' }], from), { name: 'off' });
});

test('unlocking does not create any "recently authenticated" state for transfers', () => {
  // The machine only knows lock states; Send always calls confirmDeviceOwner() itself.
  const state = run([{ type: 'unlockResult', result: 'success' }], lockedState());
  assert.deepEqual(Object.keys(state).sort(), ['backgroundAt', 'name']);
});

// --- classification -----------------------------------------------------------------------

test('secure-store prompt errors are classified from their message text', () => {
  const err = (detail: string) => new Error(`Could not Authenticate the user: ${detail}`);
  assert.equal(
    classifySecureStoreError(err('User canceled the authentication. Cancel')),
    'cancelled',
  );
  assert.equal(classifySecureStoreError(err('Lockout. Too many attempts')), 'lockout');
  assert.equal(classifySecureStoreError(err('Lockout permanent. Too many attempts')), 'lockout');
  assert.equal(
    classifySecureStoreError(err('No biometrics are currently enrolled')),
    'notEnrolled',
  );
  assert.equal(
    classifySecureStoreError(
      err('Cannot display biometric prompt when the app is not in the foreground'),
    ),
    'cancelled',
  );
  assert.equal(classifySecureStoreError(err('Authentication is already in progress')), 'cancelled');
  assert.equal(classifySecureStoreError(err('Timeout. Timed out')), 'failed');
  assert.equal(classifySecureStoreError(new Error('something new')), 'failed');
  assert.equal(classifySecureStoreError(undefined), 'failed');
});

test('local-authentication error codes', () => {
  assert.equal(classifyLocalAuthError('user_cancel'), 'cancelled');
  assert.equal(classifyLocalAuthError('system_cancel'), 'cancelled');
  assert.equal(classifyLocalAuthError('lockout'), 'lockout');
  assert.equal(classifyLocalAuthError('passcode_not_set'), 'notEnrolled');
  assert.equal(classifyLocalAuthError('not_enrolled'), 'notEnrolled');
  assert.equal(classifyLocalAuthError('authentication_failed'), 'failed');
  assert.equal(classifyLocalAuthError(undefined), 'failed');
});

// --- settings -----------------------------------------------------------------------------

function memoryStore(initial?: unknown): LockSettingsStore & { data: Map<string, unknown> } {
  const data = new Map<string, unknown>();
  if (initial !== undefined) data.set(LOCK_SETTINGS_KEY, initial);
  return {
    data,
    async read<T>(key: string) {
      return (data.get(key) as T) ?? null;
    },
    async write(key, value) {
      data.set(key, value);
    },
    async remove(key) {
      data.delete(key);
    },
  };
}

test('settings: missing, malformed or unreadable => off', async () => {
  assert.deepEqual(await readLockSettings(memoryStore()), EMPTY_LOCK_SETTINGS);
  assert.deepEqual(await readLockSettings(memoryStore({ enabled: 'yes' })), EMPTY_LOCK_SETTINGS);
  const broken: LockSettingsStore = {
    read: async () => {
      throw new Error('keystore');
    },
    write: async () => undefined,
    remove: async () => undefined,
  };
  assert.deepEqual(await readLockSettings(broken), EMPTY_LOCK_SETTINGS);
  // Enabled without a method or subject fails closed to off.
  const half = await readLockSettings(
    memoryStore({ enabled: true, subject: null, method: 'biometric', offered: [] }),
  );
  assert.equal(half.enabled, false);
});

test('settings: bound to the subject that enabled them', () => {
  const on = enabledSettings(EMPTY_LOCK_SETTINGS, 'alice', 'biometric');
  assert.equal(isEnabledFor(on, 'alice'), true);
  assert.equal(isEnabledFor(on, 'bob'), false);
  assert.equal(isEnabledFor(on, null), false);
  const forBob = settingsForSignIn(on, 'bob');
  assert.equal(forBob.enabled, false);
  assert.deepEqual(forBob.offered, ['alice']);
  assert.equal(settingsForSignIn(on, 'alice'), on);
  assert.equal(isEnabledFor(disabledSettings(on), 'alice'), false);
});

test('offer: once per user, only when the device has a method, not when already on', () => {
  assert.equal(shouldOffer(EMPTY_LOCK_SETTINGS, 'alice', true), true);
  assert.equal(shouldOffer(EMPTY_LOCK_SETTINGS, 'alice', false), false);
  const offered = markOffered(EMPTY_LOCK_SETTINGS, 'alice');
  assert.equal(shouldOffer(offered, 'alice', true), false);
  assert.equal(shouldOffer(offered, 'bob', true), true);
  assert.equal(
    shouldOffer(enabledSettings(EMPTY_LOCK_SETTINGS, 'bob', 'deviceCredential'), 'bob', true),
    false,
  );
});

// --- suppression --------------------------------------------------------------------------

test('own prompts suppress auto-lock until they end, then notify', async () => {
  let released = 0;
  const off = onAutoLockRelease(() => {
    released += 1;
  });
  assert.equal(isAutoLockSuppressed(), false);
  await withAutoLockSuppressed(async () => {
    assert.equal(isAutoLockSuppressed(), true);
    await withAutoLockSuppressed(async () => assert.equal(isAutoLockSuppressed(), true));
    assert.equal(released, 0);
  });
  assert.equal(isAutoLockSuppressed(), false);
  assert.equal(released, 1);
  await assert.rejects(
    withAutoLockSuppressed(async () => {
      throw new Error('prompt crashed');
    }),
  );
  assert.equal(isAutoLockSuppressed(), false);
  off();
});

// --- configuration ------------------------------------------------------------------------

test('lock timeout: default 60 s, overridable, invalid values rejected', () => {
  assert.equal(DEFAULT_LOCK_TIMEOUT_SECONDS, 60);
  assert.equal(parseLockTimeoutSeconds(undefined), 60);
  assert.equal(parseLockTimeoutSeconds(' '), 60);
  assert.equal(parseLockTimeoutSeconds('15'), 15);
  assert.equal(parseLockTimeoutSeconds('0'), 0);
  for (const bad of ['-1', '1.5', 'abc', '3601'])
    assert.throws(() => parseLockTimeoutSeconds(bad), /LOCK_TIMEOUT/);
  assert.equal(resolveConfig({}, true).lockTimeoutSeconds, 60);
  assert.equal(resolveConfig({ lockTimeoutSeconds: '120' }, true).lockTimeoutSeconds, 120);
});
