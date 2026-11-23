// Platform side of app unlock (Expo modules only; the decisions live in machine.ts).
//
// Two methods, chosen when unlock is turned on:
//   - 'biometric' (strong biometrics enrolled, e.g. fingerprint): a random marker is written to
//     expo-secure-store with `requireAuthentication: true`. On Android that is an AES key in
//     the Keystore with setUserAuthenticationRequired(true), so reading it shows the system
//     biometric prompt, and the key is invalidated by the OS when biometrics change (a new
//     fingerprint, all removed). The read then returns null => "biometrics changed".
//     The marker holds no secret: the session tokens stay in ordinary secure storage
//     (Keystore-encrypted, not bound to authentication). Binding the tokens themselves would
//     need a biometric prompt on every token refresh (every ~5 minutes), so the lock is
//     enforced in app logic. See VALIDATION.md for the limitation (rooted / debug builds).
//   - 'deviceCredential' (screen lock but no strong biometrics): expo-local-authentication with
//     the device PIN/pattern/password. Removal of the screen lock is detected; a change is not.
import * as LocalAuthentication from 'expo-local-authentication';
import * as SecureStore from 'expo-secure-store';
import * as Crypto from 'expo-crypto';
import { classifyLocalAuthError, classifySecureStoreError } from './classify';
import type { UnlockResult } from './machine';
import { withAutoLockSuppressed } from './suppress';

export type UnlockMethod = 'biometric' | 'deviceCredential';

const MARKER_KEY = 'neos.lock.marker';
const MARKER_OPTIONS = { requireAuthentication: true } as const;

// What this device can offer right now (null: no biometrics and no screen lock).
export async function availableUnlockMethod(): Promise<UnlockMethod | null> {
  try {
    const level = await LocalAuthentication.getEnrolledLevelAsync();
    if (level === LocalAuthentication.SecurityLevel.BIOMETRIC_STRONG) {
      let strong = false;
      try {
        strong = SecureStore.canUseBiometricAuthentication();
      } catch {
        strong = false;
      }
      if (strong) return 'biometric';
    }
    return level === LocalAuthentication.SecurityLevel.NONE ? null : 'deviceCredential';
  } catch {
    return null;
  }
}

async function deviceCredential(prompt: { title: string; cancel: string }): Promise<UnlockResult> {
  try {
    const result = await LocalAuthentication.authenticateAsync({
      promptMessage: prompt.title,
      cancelLabel: prompt.cancel,
      disableDeviceFallback: false,
    });
    return result.success ? 'success' : classifyLocalAuthError(result.error);
  } catch {
    return 'failed';
  }
}

// Turning unlock on: one authentication with the chosen method (writing the marker IS the
// biometric prompt on Android).
export function enrollUnlock(
  method: UnlockMethod,
  prompt: { title: string; cancel: string },
): Promise<UnlockResult> {
  return withAutoLockSuppressed(async () => {
    if (method === 'deviceCredential') return deviceCredential(prompt);
    try {
      await SecureStore.setItemAsync(MARKER_KEY, Crypto.randomUUID(), {
        ...MARKER_OPTIONS,
        authenticationPrompt: prompt.title,
      });
      return 'success';
    } catch (error) {
      return classifySecureStoreError(error);
    }
  });
}

export function unlockWith(
  method: UnlockMethod,
  prompt: { title: string; cancel: string },
): Promise<UnlockResult> {
  return withAutoLockSuppressed(async () => {
    const available = await availableUnlockMethod();
    // Fingerprints removed (only a PIN left) or the screen lock removed: the stored method can
    // no longer be used.
    if (available === null || (method === 'biometric' && available !== 'biometric'))
      return 'notEnrolled';
    if (method === 'deviceCredential') return deviceCredential(prompt);
    try {
      const marker = await SecureStore.getItemAsync(MARKER_KEY, {
        ...MARKER_OPTIONS,
        authenticationPrompt: prompt.title,
      });
      // Null: the Keystore key was invalidated (biometrics changed) or the marker is gone.
      return marker ? 'success' : 'invalidated';
    } catch (error) {
      return classifySecureStoreError(error);
    }
  });
}

export async function clearUnlockMarker(): Promise<void> {
  for (const options of [MARKER_OPTIONS, undefined]) {
    try {
      await SecureStore.deleteItemAsync(MARKER_KEY, options);
    } catch {
      /* nothing stored / already removed */
    }
  }
}
