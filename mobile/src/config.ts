import { EMPTY_MOCKS, resolveMockFeatures, type MockSet } from './mockFeatures';
import { DEFAULT_LOCK_TIMEOUT_SECONDS, parseLockTimeoutSeconds } from './lock/machine';

// Which backend environment the build talks to. Anything but `production` (including an unset
// value) is a sandbox: Home shows a permanent "Sandbox" badge. A build must opt OUT of the
// badge explicitly with EXPO_PUBLIC_APP_ENV=production, so it can never be hidden by omission.
export const APP_ENVS = ['development', 'staging', 'demo', 'production'] as const;
export type AppEnv = (typeof APP_ENVS)[number] | 'unspecified';

export interface AppConfig {
  mocks: MockSet;
  appEnv: AppEnv;
  apiUrl: string;
  issuer: string;
  clientId: string;
  redirectUri: string;
  termsVersion: string;
  termsUrl: string;
  demoKycResult: string;
  // App unlock: inactivity in the background before the unlock screen is required again.
  lockTimeoutSeconds: number;
}
export interface RawEnv {
  mockFeatures?: string;
  appEnv?: string;
  apiUrl?: string;
  issuer?: string;
  clientId?: string;
  redirectUri?: string;
  termsVersion?: string;
  termsUrl?: string;
  demoKycResult?: string;
  lockTimeoutSeconds?: string;
}
const REQUIRED_FIELDS = [
  'apiUrl',
  'issuer',
  'clientId',
  'redirectUri',
  'termsVersion',
  'termsUrl',
] as const satisfies readonly (keyof AppConfig)[];
const HTTPS_FIELDS = [
  'apiUrl',
  'issuer',
  'termsUrl',
] as const satisfies readonly (keyof AppConfig)[];

// Mocks and insecure localhost defaults are dev-build-only; a non-dev build
// (staging/production via eas.json) must supply complete, https, non-placeholder values.
function resolveAppEnv(raw: string | undefined, isDev: boolean): AppEnv {
  const value = (raw ?? '').trim();
  if (!value) return isDev ? 'development' : 'unspecified';
  if ((APP_ENVS as readonly string[]).includes(value)) return value as AppEnv;
  throw new Error(
    `EXPO_PUBLIC_APP_ENV has unknown value "${value}". Use one of: ${APP_ENVS.join(', ')}.`,
  );
}
export function isSandbox(cfg: Pick<AppConfig, 'appEnv'>): boolean {
  return cfg.appEnv !== 'production';
}
export function resolveConfig(env: RawEnv, isDev: boolean): AppConfig {
  const resolved: AppConfig = {
    mocks: resolveMockFeatures(env.mockFeatures, isDev),
    appEnv: resolveAppEnv(env.appEnv, isDev),
    apiUrl: env.apiUrl || (isDev ? 'http://localhost:8080' : ''),
    issuer: env.issuer || (isDev ? 'http://localhost:8081/realms/neos' : ''),
    clientId: env.clientId || (isDev ? 'neos-mobile' : ''),
    redirectUri: env.redirectUri || (isDev ? 'neos://auth' : ''),
    termsVersion: env.termsVersion || (isDev ? '1.0' : ''),
    termsUrl: env.termsUrl || '',
    demoKycResult: env.demoKycResult || 'APPROVED',
    lockTimeoutSeconds: parseLockTimeoutSeconds(env.lockTimeoutSeconds),
  };
  if (!isDev) assertProductionReady(resolved);
  return resolved;
}
function assertProductionReady(cfg: AppConfig): void {
  const problems: string[] = [];
  for (const field of REQUIRED_FIELDS) {
    if (!cfg[field]) problems.push(`${field} is missing`);
  }
  for (const field of HTTPS_FIELDS) {
    const value = cfg[field];
    if (value && !value.startsWith('https://')) problems.push(`${field} must use https://`);
  }
  for (const [field, value] of Object.entries(cfg)) {
    if (typeof value === 'string' && value.includes('REPLACE_ME'))
      problems.push(`${field} still contains a REPLACE_ME placeholder`);
  }
  if (problems.length > 0)
    throw new Error(
      `Invalid app configuration for a non-development build: ${problems.join('; ')}`,
    );
}
// typeof-guarded: __DEV__ is a React Native global and is not defined under plain Node/tsx tests.
const isDev = typeof __DEV__ !== 'undefined' && !!__DEV__;
const env: RawEnv = {
  mockFeatures: process.env.EXPO_PUBLIC_MOCK_FEATURES,
  appEnv: process.env.EXPO_PUBLIC_APP_ENV,
  apiUrl: process.env.EXPO_PUBLIC_API_URL,
  issuer: process.env.EXPO_PUBLIC_KEYCLOAK_ISSUER,
  clientId: process.env.EXPO_PUBLIC_KEYCLOAK_CLIENT_ID,
  redirectUri: process.env.EXPO_PUBLIC_REDIRECT_URI,
  termsVersion: process.env.EXPO_PUBLIC_TERMS_VERSION,
  termsUrl: process.env.EXPO_PUBLIC_TERMS_URL,
  demoKycResult: process.env.EXPO_PUBLIC_DEMO_KYC_RESULT,
  lockTimeoutSeconds: process.env.EXPO_PUBLIC_LOCK_TIMEOUT_SECONDS,
};
let resolved: AppConfig;
let startupError: Error | null = null;
try {
  resolved = resolveConfig(env, isDev);
} catch (cause) {
  startupError = cause instanceof Error ? cause : new Error(String(cause));
  // Fail closed: nothing mocked and every URL empty until the error is fixed and the app restarted.
  resolved = {
    mocks: EMPTY_MOCKS,
    appEnv: 'unspecified',
    apiUrl: '',
    issuer: '',
    clientId: '',
    redirectUri: '',
    termsVersion: '',
    termsUrl: '',
    demoKycResult: 'APPROVED',
    lockTimeoutSeconds: DEFAULT_LOCK_TIMEOUT_SECONDS,
  };
}
export const config = resolved;
// Thrown from JourneyApp's render (App.tsx) so the existing root ErrorBoundary surfaces it.
export const configError = startupError;
