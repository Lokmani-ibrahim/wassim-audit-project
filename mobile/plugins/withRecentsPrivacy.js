// Local Expo config plugin (no dependency): hides the app's content from the Android recents
// (app switcher) preview on Android 13+ (API 33) with Activity.setRecentsScreenshotEnabled(false).
// Unlike FLAG_SECURE it does not block screenshots or screen recording, and it does not need a
// JS round trip (a JS "privacy cover" drawn on AppState change is too late for the snapshot).
// Android 12 and lower: no effect (documented in VALIDATION.md). iOS: the JS cover in App.tsx.
const { withMainActivity } = require('expo/config-plugins');

const MARKER = 'setRecentsScreenshotEnabled(false)';
const SNIPPET = `
    // NEOS: no account data in the recents preview (Android 13+). plugins/withRecentsPrivacy.js
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      setRecentsScreenshotEnabled(false)
    }`;

function addToOnCreate(contents) {
  if (contents.includes(MARKER)) return contents;
  const anchor = /super\.onCreate\([^)]*\)\s*\n/;
  if (!anchor.test(contents))
    throw new Error('withRecentsPrivacy: could not find super.onCreate(...) in MainActivity');
  let next = contents.replace(anchor, (match) => `${match.trimEnd()}${SNIPPET}\n`);
  if (!/^import android\.os\.Build$/m.test(next))
    next = next.replace(/^(package .+\n)/, '$1\nimport android.os.Build\n');
  return next;
}

module.exports = function withRecentsPrivacy(config) {
  return withMainActivity(config, (mod) => {
    if (mod.modResults.language !== 'kt')
      throw new Error('withRecentsPrivacy: only a Kotlin MainActivity is supported');
    mod.modResults.contents = addToOnCreate(mod.modResults.contents);
    return mod;
  });
};
module.exports.addToOnCreate = addToOnCreate;
