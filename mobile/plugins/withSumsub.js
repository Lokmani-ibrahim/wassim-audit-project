// Local Expo config plugin (no dependency): makes the Sumsub MobileSDK build on Android.
//
// 1. Maven repository. @sumsub/react-native-mobilesdk-module depends on
//    com.sumsub.sns:idensic-mobile-sdk, which is published only on Sumsub's Maven repository. The
//    library's own `repositories {}` block does not apply when the app resolves that transitive
//    AAR, so the repository must be declared for all projects in the root android/build.gradle
//    (Sumsub React Native docs).
// 2. ML Kit model meta-data. The Sumsub SDK manifest declares
//    com.google.mlkit.vision.DEPENDENCIES = "face", and expo-dev-launcher (development builds)
//    declares it = "barcode_ui". The manifest merger refuses the conflict. The app manifest
//    declares the union with tools:replace, so both libraries get their model. In release builds
//    (no dev launcher) this only pre-downloads the small barcode UI model as well.
// 3. Secure windows for the SDK. The Sumsub SDK runs in its own activities
//    (com.sumsub.sns...SNSAppActivity), which do not set FLAG_SECURE, so the app switcher showed a
//    live snapshot of the ID capture screens (phase 1, plan §10.5). MainApplication registers
//    ActivityLifecycleCallbacks that set FLAG_SECURE on every com.sumsub.* activity in
//    onActivityCreated (before its first frame): black in the recents preview and in screenshots
//    or screen recordings while the SDK is open. Our own screens are unaffected (withRecentsPrivacy
//    still covers MainActivity). Chosen over a Sumsub option (none exists) and over a JS flag
//    (cannot reach another activity's window).
//
// Permissions the SDK merges in but our level (id-and-liveness) does not need are removed with
// `android.blockedPermissions` in app.config.ts. See docs/api/SUMSUB_MOBILE_INTEGRATION_PLAN.md.
const {
  withAndroidManifest,
  withMainApplication,
  withProjectBuildGradle,
} = require('expo/config-plugins');

const SECURE_MARKER = 'NeosSumsubSecureWindows';
const SECURE_IMPORTS = [
  'android.app.Activity',
  'android.app.Application',
  'android.os.Bundle',
  'android.view.WindowManager',
];
const SECURE_SNIPPET = `
    // NEOS: no ID documents in the recents preview or screenshots while the Sumsub SDK is open.
    // plugins/withSumsub.js (${SECURE_MARKER})
    registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity.javaClass.name.startsWith("com.sumsub.")) {
          activity.window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
          )
        }
      }
      override fun onActivityStarted(activity: Activity) {}
      override fun onActivityResumed(activity: Activity) {}
      override fun onActivityPaused(activity: Activity) {}
      override fun onActivityStopped(activity: Activity) {}
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {}
    })`;

function addSecureSumsubWindows(contents) {
  if (contents.includes(SECURE_MARKER)) return contents;
  const anchor = /override fun onCreate\(\)\s*\{\s*\n\s*super\.onCreate\(\)\s*\n/;
  if (!anchor.test(contents))
    throw new Error('withSumsub: could not find onCreate() { super.onCreate() in MainApplication');
  let next = contents.replace(anchor, (match) => `${match.trimEnd()}${SECURE_SNIPPET}\n`);
  for (const name of SECURE_IMPORTS) {
    if (!new RegExp(`^import ${name.replace(/\./g, '\\.')}$`, 'm').test(next))
      next = next.replace(/^(package .+\n)/, `$1\nimport ${name}`);
  }
  return next;
}

const SUMSUB_MAVEN_URL = 'https://maven.sumsub.com/repository/maven-public/';
const LINE = `    // NEOS: Sumsub MobileSDK artifacts. plugins/withSumsub.js\n    maven { url '${SUMSUB_MAVEN_URL}' }\n`;

const MLKIT_DEPENDENCIES = 'com.google.mlkit.vision.DEPENDENCIES';
// Sumsub: face (liveness). expo-dev-launcher: barcode_ui (dev-client QR scanner).
const MLKIT_MODELS = ['barcode_ui', 'face'];

function addSumsubMaven(contents) {
  if (contents.includes(SUMSUB_MAVEN_URL)) return contents;
  const anchor = /allprojects\s*\{\s*\n(\s*)repositories\s*\{\s*\n/;
  if (!anchor.test(contents))
    throw new Error(
      'withSumsub: could not find allprojects { repositories { in android/build.gradle',
    );
  return contents.replace(anchor, (match) => `${match}${LINE}`);
}

// Pure transform on the parsed AndroidManifest (xml2js shape used by Expo config plugins).
function mergeMlKitDependencies(manifest) {
  const root = manifest.manifest;
  if (!root) throw new Error('withSumsub: AndroidManifest has no <manifest> root');
  root.$ = root.$ || {};
  if (!root.$['xmlns:tools']) root.$['xmlns:tools'] = 'http://schemas.android.com/tools';
  const application = root.application && root.application[0];
  if (!application) throw new Error('withSumsub: AndroidManifest has no <application>');
  application['meta-data'] = application['meta-data'] || [];
  const existing = application['meta-data'].find(
    (item) => item.$ && item.$['android:name'] === MLKIT_DEPENDENCIES,
  );
  const models = new Set(MLKIT_MODELS);
  if (existing && existing.$['android:value'])
    for (const model of existing.$['android:value'].split(','))
      if (model.trim()) models.add(model.trim());
  const entry = {
    $: {
      'android:name': MLKIT_DEPENDENCIES,
      'android:value': [...models].sort().join(','),
      'tools:replace': 'android:value',
    },
  };
  if (existing) existing.$ = entry.$;
  else application['meta-data'].push(entry);
  return manifest;
}

module.exports = function withSumsub(config) {
  config = withProjectBuildGradle(config, (mod) => {
    if (mod.modResults.language !== 'groovy')
      throw new Error('withSumsub: only a Groovy android/build.gradle is supported');
    mod.modResults.contents = addSumsubMaven(mod.modResults.contents);
    return mod;
  });
  config = withAndroidManifest(config, (mod) => {
    mod.modResults = mergeMlKitDependencies(mod.modResults);
    return mod;
  });
  return withMainApplication(config, (mod) => {
    if (mod.modResults.language !== 'kt')
      throw new Error('withSumsub: only a Kotlin MainApplication is supported');
    mod.modResults.contents = addSecureSumsubWindows(mod.modResults.contents);
    return mod;
  });
};
module.exports.addSumsubMaven = addSumsubMaven;
module.exports.addSecureSumsubWindows = addSecureSumsubWindows;
module.exports.mergeMlKitDependencies = mergeMlKitDependencies;
module.exports.SUMSUB_MAVEN_URL = SUMSUB_MAVEN_URL;
module.exports.MLKIT_DEPENDENCIES = MLKIT_DEPENDENCIES;
