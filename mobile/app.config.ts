import type { ExpoConfig } from 'expo/config';

const config: ExpoConfig = {
  name: 'NEOS',
  slug: 'neos',
  version: '1.0.0',
  scheme: 'neos',
  orientation: 'portrait',
  userInterfaceStyle: 'light',
  ios: { bundleIdentifier: 'com.neos.mobile', supportsTablet: true },
  android: {
    package: 'com.neos.mobile',
    // The Sumsub MobileSDK manifest merges these in. The verification level (id-and-liveness:
    // ID document + liveness) needs only CAMERA, so the rest are removed (tools:node="remove").
    // RECORD_AUDIO is blocked until a live check shows liveness needs it.
    blockedPermissions: [
      'android.permission.ACCESS_FINE_LOCATION',
      'android.permission.ACCESS_COARSE_LOCATION',
      'android.permission.NFC',
      'android.permission.WRITE_SETTINGS',
      'android.permission.RECORD_AUDIO',
    ],
  },
  plugins: [
    'expo-secure-store',
    'expo-web-browser',
    'expo-font',
    // Device-owner confirmation before a transfer (biometrics or device credential).
    [
      'expo-local-authentication',
      {
        faceIDPermission: 'Allow NEOS to use Face ID to unlock the app and confirm your transfers.',
      },
    ],
    // No account data in the Android 13+ recents preview (local plugin, see the file).
    './plugins/withRecentsPrivacy',
    // Sumsub Maven repository for the identity verification SDK (local plugin, see the file).
    './plugins/withSumsub',
  ],
};
export default config;
