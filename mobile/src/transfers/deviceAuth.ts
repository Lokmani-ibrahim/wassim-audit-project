// Device-owner confirmation before a transfer (biometrics, or the device PIN/pattern/password
// as fallback). This proves presence of the device owner; it does not replace server auth.
import * as LocalAuthentication from 'expo-local-authentication';
import { withAutoLockSuppressed } from '../lock/suppress';

export type DeviceAuthResult = 'success' | 'cancelled' | 'failed' | 'notEnrolled';

// false => the device has neither biometrics nor a screen lock: the UI asks for an explicit
// confirm tap instead (and says so).
export async function deviceAuthAvailable(): Promise<boolean> {
  try {
    const level = await LocalAuthentication.getEnrolledLevelAsync();
    return level !== LocalAuthentication.SecurityLevel.NONE;
  } catch {
    return false;
  }
}

// Always a fresh prompt: app unlock never counts as a transfer confirmation. The prompt (and the
// device-credential screen it may open) does not start the app-unlock inactivity timer.
export function confirmDeviceOwner(prompt: {
  title: string;
  cancel: string;
}): Promise<DeviceAuthResult> {
  return withAutoLockSuppressed(() => promptDeviceOwner(prompt));
}

async function promptDeviceOwner(prompt: {
  title: string;
  cancel: string;
}): Promise<DeviceAuthResult> {
  try {
    const result = await LocalAuthentication.authenticateAsync({
      promptMessage: prompt.title,
      cancelLabel: prompt.cancel,
      disableDeviceFallback: false,
    });
    if (result.success) return 'success';
    switch (result.error) {
      case 'user_cancel':
      case 'system_cancel':
      case 'app_cancel':
      case 'user_fallback':
        return 'cancelled';
      case 'not_enrolled':
      case 'passcode_not_set':
      case 'not_available':
        return 'notEnrolled';
      default:
        return 'failed';
    }
  } catch {
    return 'failed';
  }
}
