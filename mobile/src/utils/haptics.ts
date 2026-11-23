import { Platform } from 'react-native';
import * as Haptics from 'expo-haptics';

// Two haptics only, both on success or selection, never on errors (approved scope):
// - selection: a light tick when the wallet shown on Home changes;
// - success: once, when a transfer or conversion the user confirmed comes back succeeded.
// Android uses performHapticFeedback (performAndroidHapticsAsync), which honours the system
// "touch feedback" setting and needs no vibrator call; iOS applies its own system haptics setting.
// Feedback is best effort: a missing module or a refused call is ignored.

export function selectionHaptic(): void {
  void run(() =>
    Platform.OS === 'android'
      ? Haptics.performAndroidHapticsAsync(Haptics.AndroidHaptics.Segment_Tick)
      : Haptics.selectionAsync(),
  );
}

export function successHaptic(): void {
  void run(() =>
    Platform.OS === 'android'
      ? Haptics.performAndroidHapticsAsync(Haptics.AndroidHaptics.Confirm)
      : Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success),
  );
}

async function run(effect: () => Promise<void>): Promise<void> {
  try {
    await effect();
  } catch {
    // No haptics on this device or build: nothing to do.
  }
}
