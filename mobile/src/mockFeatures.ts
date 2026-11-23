// Closed list of capabilities that can be faked during development. This file holds no fake
// data and is safe to ship: it only names features and resolves which are active. The fakes
// themselves live in src/mocks/** and are loaded solely from services/index.ts under __DEV__.
export const MOCK_FEATURES = [
  'auth',
  'onboarding',
  'kyc',
  'kycCapture',
  'wallets',
  'notifications',
] as const;
export type MockFeature = (typeof MOCK_FEATURES)[number];
export type MockSet = ReadonlySet<MockFeature>;

// Features that call the backend with the user's bearer token. If `auth` is mocked there is no
// real token, so every one of these must be mocked as well. `kycCapture` asks the backend for a
// Sumsub SDK token (POST /api/kyc/sdk-token), so it is one of them.
export const BACKEND_FEATURES = [
  'onboarding',
  'kyc',
  'kycCapture',
  'wallets',
  'notifications',
] as const satisfies readonly MockFeature[];

export const EMPTY_MOCKS: MockSet = new Set<MockFeature>();

const byLowerCaseName = new Map<string, MockFeature>(
  MOCK_FEATURES.map((feature) => [feature.toLowerCase(), feature]),
);

// `all` / `none` / unset / a comma-separated list of feature names (names are case-insensitive).
export function parseMockFeatures(raw: string | undefined): MockSet {
  const value = (raw ?? '').trim();
  if (value === '' || value === 'none') return EMPTY_MOCKS;
  if (value === 'all') return new Set<MockFeature>(MOCK_FEATURES);
  const names = value
    .split(',')
    .map((name) => name.trim())
    .filter((name) => name !== '');
  const selected = new Set<MockFeature>();
  const unknown: string[] = [];
  for (const name of names) {
    const feature = byLowerCaseName.get(name.toLowerCase());
    if (feature) selected.add(feature);
    else unknown.push(name);
  }
  if (unknown.length > 0)
    throw new Error(
      `EXPO_PUBLIC_MOCK_FEATURES has unknown feature(s): ${unknown.join(', ')}. ` +
        `Use "all", "none" or a comma-separated list of: ${MOCK_FEATURES.join(', ')}.`,
    );
  return selected;
}

export function assertMockConsistency(mocks: MockSet): void {
  // A real Sumsub session needs a real applicant, which only the real KYC start creates.
  if (mocks.has('kyc') && !mocks.has('kycCapture'))
    throw new Error(
      'EXPO_PUBLIC_MOCK_FEATURES mocks "kyc" but not "kycCapture": a real Sumsub session needs a real KYC start. Also mock kycCapture.',
    );
  if (!mocks.has('auth')) return;
  const missing = BACKEND_FEATURES.filter((feature) => !mocks.has(feature));
  if (missing.length > 0)
    throw new Error(
      `EXPO_PUBLIC_MOCK_FEATURES mocks "auth", so no real token exists; also mock: ${missing.join(', ')}.`,
    );
}

// Fail closed: outside a development build the mock set is always empty and the env value is
// not even parsed, so a stray or malformed value can never alter (or break) a release build.
export function resolveMockFeatures(raw: string | undefined, isDev: boolean): MockSet {
  if (!isDev) return EMPTY_MOCKS;
  const mocks = parseMockFeatures(raw);
  assertMockConsistency(mocks);
  return mocks;
}
