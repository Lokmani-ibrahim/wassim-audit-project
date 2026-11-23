// Fails if any mock marker (__NEOS_MOCK__:<feature>) or dev-only tool marker
// (__NEOS_DEV_ONLY__:<tool>, src/dev/**) is present in a production-like bundle.
// Exports Android with release-style env (https URLs) while EXPO_PUBLIC_MOCK_FEATURES=all, to
// prove that the setting is ignored and the mocks and dev-only trees are dead-code-eliminated.
//
//   node scripts/check-release-bundle.mjs            # must find 0 markers
//   node scripts/check-release-bundle.mjs --control  # dev export; must FIND markers (proves the scan works)
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// Mocks (src/mocks/**) and dev-only tools (src/dev/**, e.g. the Sumsub SDK spike launcher).
const MARKERS = ['__NEOS_MOCK__:', '__NEOS_DEV_ONLY__:'];
const MARKER_PATTERN = /__NEOS_(?:MOCK|DEV_ONLY)__:\w+/g;
const control = process.argv.includes('--control');
const appRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const expoCli = join(appRoot, 'node_modules', 'expo', 'bin', 'cli');
if (!existsSync(expoCli)) {
  console.error(`expo CLI not found at ${expoCli}. Run npm install first.`);
  process.exit(2);
}

// Whether any source file under src/ defines a marker of this family.
function sourceUses(marker) {
  for (const file of walk(join(appRoot, 'src')))
    if (/\.(ts|tsx)$/.test(file) && readFileSync(file, 'utf8').includes(marker)) return true;
  return false;
}

function* walk(directory) {
  for (const name of readdirSync(directory)) {
    const path = join(directory, name);
    if (statSync(path).isDirectory()) yield* walk(path);
    else yield path;
  }
}

// Outside the repo, so nothing is left behind in the working tree.
const output = mkdtempSync(join(tmpdir(), 'neos-release-bundle-'));
let exitCode = 0;
try {
  const env = {
    ...process.env,
    NODE_ENV: control ? 'development' : 'production',
    EXPO_PUBLIC_MOCK_FEATURES: 'all',
    EXPO_PUBLIC_API_URL: 'https://api.neos-bundle-check.example',
    EXPO_PUBLIC_KEYCLOAK_ISSUER: 'https://auth.neos-bundle-check.example/realms/neos',
    EXPO_PUBLIC_KEYCLOAK_CLIENT_ID: 'neos-mobile',
    EXPO_PUBLIC_REDIRECT_URI: 'neos://auth',
    EXPO_PUBLIC_TERMS_VERSION: '1.0',
    EXPO_PUBLIC_TERMS_URL: 'https://neos-bundle-check.example/terms',
    CI: '1',
  };
  const args = [expoCli, 'export', '--platform', 'android', '--output-dir', output];
  if (control) args.push('--dev');
  console.log(
    `Exporting ${control ? 'DEV (control)' : 'production-like'} Android bundle to ${output}`,
  );
  const result = spawnSync(process.execPath, args, { cwd: appRoot, env, stdio: 'inherit' });
  if (result.status !== 0) {
    console.error(`expo export failed (exit ${result.status ?? result.signal}).`);
    exitCode = 2;
  } else {
    const hits = new Map();
    let scanned = 0;
    let bundleBytes = 0;
    for (const file of walk(output)) {
      const buffer = readFileSync(file);
      scanned += 1;
      if (/\.(hbc|js|bundle)$/.test(file)) bundleBytes += buffer.length;
      if (MARKERS.some((marker) => buffer.includes(marker))) {
        const text = buffer.toString('latin1');
        for (const match of text.matchAll(MARKER_PATTERN)) {
          const key = `${match[0]}  (${file.slice(output.length + 1)})`;
          hits.set(key, true);
        }
      }
    }
    console.log(`Scanned ${scanned} files, ${bundleBytes} bytes of JS/bytecode.`);
    if (bundleBytes < 100_000) {
      console.error(
        'FAIL: no plausible JS bundle found in the export output; the scan proves nothing.',
      );
      exitCode = 2;
    } else if (control) {
      // Every marker family used in src/ must be found, or the scan proves nothing for it.
      // (A family with no marker in the source, e.g. no dev-only tool today, is not required.)
      const used = MARKERS.filter((marker) => sourceUses(marker));
      const missing = used.filter(
        (marker) => ![...hits.keys()].some((key) => key.startsWith(marker)),
      );
      if (missing.length > 0) {
        console.error(
          `CONTROL FAIL: dev export lacks ${missing.join(', ')} markers, so the scan cannot be trusted.`,
        );
        exitCode = 1;
      } else {
        console.log(
          `CONTROL OK: dev bundle contains ${hits.size} marker hit(s), so the scan works:`,
        );
        for (const key of hits.keys()) console.log(`  ${key}`);
      }
    } else if (hits.size > 0) {
      console.error(`FAIL: ${hits.size} mock/dev-only marker(s) found in the release bundle:`);
      for (const key of hits.keys()) console.error(`  ${key}`);
      exitCode = 1;
    } else {
      console.log('OK: 0 mock or dev-only markers found in the release bundle.');
    }
  }
} finally {
  rmSync(output, { recursive: true, force: true });
}
process.exit(exitCode);
