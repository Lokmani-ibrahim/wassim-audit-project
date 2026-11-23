// Pure mapping of platform authentication outcomes to UnlockResult (unit-tested).
import type { UnlockResult } from './machine';

// expo-secure-store (Android, requireAuthentication) reports BiometricPrompt errors only as
// message text, e.g. "Could not Authenticate the user: User canceled the authentication. Cancel"
// (AuthenticationPrompt.kt / SecureStoreExceptions.kt, 55.0.x). Anything unrecognised counts as
// a failed attempt, which is the conservative choice (3 of them require a password sign-in).
export function classifySecureStoreError(error: unknown): UnlockResult {
  const message = error instanceof Error ? error.message : String(error ?? '');
  const text = message.toLowerCase();
  if (text.includes('lockout')) return 'lockout';
  if (
    text.includes('user canceled') ||
    text.includes('user cancelled') ||
    // The prompt could not be shown (app not in the foreground, a prompt already open): not
    // an attempt by the user.
    text.includes('not in the foreground') ||
    text.includes('already in progress')
  )
    return 'cancelled';
  if (
    text.includes('no biometrics') ||
    text.includes('none_enrolled') ||
    text.includes('no hardware') ||
    text.includes('hardware not present') ||
    text.includes('no device credential')
  )
    return 'notEnrolled';
  if (text.includes('permanently invalidated')) return 'invalidated';
  return 'failed';
}

// expo-local-authentication LocalAuthenticationError codes.
export function classifyLocalAuthError(code: string | undefined): UnlockResult {
  switch (code) {
    case 'user_cancel':
    case 'system_cancel':
    case 'app_cancel':
    case 'user_fallback':
      return 'cancelled';
    case 'lockout':
      return 'lockout';
    case 'not_enrolled':
    case 'passcode_not_set':
    case 'not_available':
      return 'notEnrolled';
    default:
      return 'failed';
  }
}
