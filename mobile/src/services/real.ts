import * as AuthSession from 'expo-auth-session';
import * as WebBrowser from 'expo-web-browser';
import { Platform } from 'react-native';
import { config } from '../config';
import { ApiError, createApiClient, type ApiClient } from '../api/client';
import { createXhrFetch } from '../api/xhr';
import { KycFlowError, classifyKycFailure } from '../kyc/errors';
import { createSumsubKycCapture } from './sumsub';
import type {
  KycSdkTokenResponse,
  KycStartResponse,
  PersonalOnboardingRequest,
  PersonalOnboardingResponse,
  RecipientLookupRequest,
} from '../api/contract';
import {
  ContractError,
  parseAuthMe,
  parseKycSdkToken,
  parseNotificationPage,
  parseProfile,
  parseRecipientLookup,
  parseFxConversion,
  parseFxQuote,
  parseTransactionPage,
  parseTransfer,
  parseWallets,
} from '../api/parse';
import { profileFromBackend } from '../api/profileMapping';
import type { MessageKey } from '../locales';
import type { Identity, Profile, Services } from '../types/domain';
import { AppError, UserCancelledError } from './errors';
import { session } from './session';
import { installationId, storage } from './storage';
import { withTimeout } from './timeout';
import { endBrowserSession } from './logout';
import { createCredentialQueue } from './credentialQueue';
import { givenNameFromIdToken } from '../utils/idToken';
import { theme } from '../theme';

WebBrowser.maybeCompleteAuthSession();
// Keycloak still opens in the system browser (Custom Tab on Android, RFC 8252), never in a
// WebView. On Android, openAuthSessionAsync is a polyfill over openBrowserAsync and forwards
// these options; createTask stays at its default because the polyfill relies on AppState.
// Known limits of expo-web-browser 55.0.20 on Android (WebBrowserModule.kt): `toolbarColor` is
// applied as the SECONDARY toolbar colour (so the top bar keeps Chrome's colour), and the share
// state is only ever set ON, never OFF (Chrome then shows its default share button). Both need a
// library fix; the values below are what the app wants once that lands. `showTitle` works.
const BROWSER_OPTIONS: AuthSession.AuthRequestPromptOptions = {
  toolbarColor: theme.colors.brand,
  showTitle: true,
  enableDefaultShareMenuItem: false,
  enableBarCollapsing: false,
};
interface Tokens {
  accessToken: string;
  refreshToken?: string;
  expiresAt: number;
  // Display only (greeting), decoded locally from the ID token; never trusted for decisions.
  givenName?: string | null;
}
const TOKEN_KEY = 'neos.real.tokens';
// While true (cold-start restore), an expiry is silent: a token left from a previous install or
// a long-closed app is not a session that "expired while in use".
let quietExpiry = false;
// Obsolete local onboarding checkpoint: status now always comes from GET /api/profile.
const LEGACY_CHECKPOINT_KEY = 'neos.real.checkpoint';
let tokens: Tokens | null = null;
let refreshTask: Promise<string> | null = null;
let logoutTask: Promise<boolean> | null = null;
let authGeneration = 0;
const credentialQueue = createCredentialQueue();
const generation = () => authGeneration;
function requireGeneration(expected: number) {
  if (expected !== authGeneration) throw new AppError('sessionError');
}
// The fields of a token response the app keeps (AuthSession.TokenResponse or a raw refresh).
interface TokenResult {
  accessToken: string;
  refreshToken?: string;
  expiresIn?: number;
  idToken?: string;
}
async function remember(result: TokenResult, retainRefresh = true, expected = authGeneration) {
  requireGeneration(expected);
  const previous = tokens;
  const next: Tokens = {
    accessToken: result.accessToken,
    refreshToken: result.refreshToken || (retainRefresh ? previous?.refreshToken : undefined),
    expiresAt: Date.now() + (result.expiresIn || 300) * 1000,
    // A refresh may or may not return a new ID token; keep the previous name when it does not.
    givenName:
      givenNameFromIdToken(result.idToken) ??
      (retainRefresh ? (previous?.givenName ?? null) : null),
  };
  const write = credentialQueue.enqueue(async () => {
    if (expected !== authGeneration) return;
    await storage.write(TOKEN_KEY, next);
    if (expected !== authGeneration) await storage.remove(TOKEN_KEY);
  });
  await write;
  requireGeneration(expected);
  tokens = next;
}
async function expire(expected = authGeneration): Promise<never> {
  requireGeneration(expected);
  authGeneration += 1;
  tokens = null;
  session.profile = null;
  await credentialQueue.enqueue(() => storage.remove(TOKEN_KEY));
  if (!quietExpiry) session.notifyExpired();
  throw new AppError('sessionError', true);
}
async function accessToken(): Promise<string> {
  const expected = authGeneration;
  if (!tokens) return expire(expected);
  if (tokens.expiresAt > Date.now() + 30000) return tokens.accessToken;
  if (!tokens.refreshToken) return expire(expected);
  if (!refreshTask) {
    const expected = authGeneration;
    const refreshToken = tokens.refreshToken;
    let task!: Promise<string>;
    task = (async () => {
      try {
        const discovery = await withTimeout(AuthSession.fetchDiscoveryAsync(config.issuer));
        requireGeneration(expected);
        tokenEndpoint = discovery.tokenEndpoint ?? tokenEndpoint;
        const result = await withTimeout(
          AuthSession.refreshAsync({ clientId: config.clientId, refreshToken }, discovery),
        );
        await remember(result, true, expected);
        return result.accessToken;
      } catch (error) {
        requireGeneration(expected);
        if (error instanceof AuthSession.TokenError) return expire(expected);
        throw new AppError('networkError');
      } finally {
        if (refreshTask === task) refreshTask = null;
      }
    })();
    refreshTask = task;
  }
  return refreshTask;
}
const api = createApiClient({
  baseUrl: () => config.apiUrl,
  getToken: accessToken,
  invalidateToken() {
    if (!tokens?.refreshToken) return false;
    tokens.expiresAt = 0;
    return true;
  },
  expire,
  generation,
});
// --- Timer-free path, used only while the Sumsub SDK Activity is in front ---------------------
// RN pauses JS timers while MainActivity is paused, and fetch (and AuthSession, built on fetch)
// resolves through setTimeout: neither can complete while the SDK is open. This path refreshes
// the Keycloak token and calls /api/kyc/sdk-token over XMLHttpRequest instead (api/xhr.ts).
// Same semantics as the regular path: refresh near expiry; 401 => refresh, retry once.
const xhrFetch = createXhrFetch();
// Cached from discovery (sign-in / refresh); Keycloak's standard path otherwise.
let tokenEndpoint: string | null = null;
let backgroundRefreshTask: Promise<string> | null = null;
async function refreshWithoutTimers(refreshToken: string): Promise<string> {
  const expected = authGeneration;
  const endpoint = tokenEndpoint ?? `${config.issuer}/protocol/openid-connect/token`;
  // Manual form encoding: React Native's URLSearchParams polyfill is incomplete.
  const body = Object.entries({
    grant_type: 'refresh_token',
    client_id: config.clientId,
    refresh_token: refreshToken,
  })
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    .join('&');
  let response;
  try {
    response = await xhrFetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
      body,
    });
  } catch {
    requireGeneration(expected);
    throw new AppError('networkError');
  }
  requireGeneration(expected);
  // 400/401 from the token endpoint (invalid_grant, revoked session): the session is over.
  if (response.status === 400 || response.status === 401) return expire(expected);
  if (!response.ok) throw new AppError('networkError');
  let json: Record<string, unknown>;
  try {
    json = (await response.json()) as Record<string, unknown>;
  } catch {
    throw new AppError('networkError');
  }
  if (typeof json.access_token !== 'string' || !json.access_token)
    throw new AppError('networkError');
  await remember(
    {
      accessToken: json.access_token,
      refreshToken: typeof json.refresh_token === 'string' ? json.refresh_token : undefined,
      expiresIn: typeof json.expires_in === 'number' ? json.expires_in : undefined,
      idToken: typeof json.id_token === 'string' ? json.id_token : undefined,
    },
    true,
    expected,
  );
  return json.access_token;
}
async function accessTokenWithoutTimers(): Promise<string> {
  const expected = authGeneration;
  if (!tokens) return expire(expected);
  if (tokens.expiresAt > Date.now() + 30000) return tokens.accessToken;
  if (!tokens.refreshToken) return expire(expected);
  const refreshToken = tokens.refreshToken;
  if (!backgroundRefreshTask) {
    let task!: Promise<string>;
    task = refreshWithoutTimers(refreshToken).finally(() => {
      if (backgroundRefreshTask === task) backgroundRefreshTask = null;
    });
    backgroundRefreshTask = task;
  }
  return backgroundRefreshTask;
}
const sdkRenewalApi = createApiClient({
  baseUrl: () => config.apiUrl,
  getToken: accessTokenWithoutTimers,
  invalidateToken() {
    if (!tokens?.refreshToken) return false;
    tokens.expiresAt = 0;
    return true;
  },
  expire,
  generation,
  fetch: xhrFetch,
});
// POST /api/kyc/sdk-token. The token is a credential: memory only, never logged or shown.
async function requestKycSdkToken(client: ApiClient): Promise<KycSdkTokenResponse> {
  try {
    return parseKycSdkToken(await client.post<unknown>('/api/kyc/sdk-token', {}));
  } catch (cause) {
    if (cause instanceof ApiError)
      throw new KycFlowError(classifyKycFailure(cause.status, cause.bodyText));
    throw cause;
  }
}

// Per-endpoint mapping of HTTP failures to user messages. Network errors, contract errors and
// session expiry are already AppErrors and pass through unchanged.
function failWith(cause: unknown, key: (error: ApiError) => MessageKey): never {
  if (cause instanceof ApiError) throw new AppError(key(cause));
  throw cause;
}
function onboardingCode(error: ApiError): string | null {
  try {
    const body: unknown = JSON.parse(error.bodyText ?? '');
    if (typeof body !== 'object' || body === null || !('errorCode' in body)) return null;
    return typeof body.errorCode === 'string' ? body.errorCode : null;
  } catch {
    return null;
  }
}
function required() {
  if (!session.profile) throw new AppError('sessionError', true);
  return session.profile;
}
// Read-only status source (GET /api/profile, contract §11). 404 = signed in, not onboarded.
async function loadProfile(identity: Identity, fallback: MessageKey): Promise<Profile> {
  const expected = authGeneration;
  if (session.profile && session.profile.identity.subject !== identity.subject)
    throw new AppError('sessionError');
  let customer = null;
  try {
    customer = parseProfile(await api.get<unknown>('/api/profile'));
  } catch (cause) {
    if (!(cause instanceof ApiError && cause.status === 404)) failWith(cause, () => fallback);
  }
  requireGeneration(expected);
  session.profile = profileFromBackend(identity, customer);
  return session.profile;
}
async function loadIdentity(): Promise<Profile> {
  const expected = authGeneration;
  let identity: Identity;
  try {
    identity = parseAuthMe(await api.get<unknown>('/api/auth/me'));
  } catch (cause) {
    if (cause instanceof ContractError) throw new AppError('bootstrapUnavailable');
    failWith(cause, (error) =>
      onboardingCode(error) === 'IDENTITY_CLAIMS_UNUSABLE' ? 'identityError' : 'authError',
    );
  }
  requireGeneration(expected);
  if (!identity.subject || !identity.email) throw new AppError('authError');
  return loadProfile({ ...identity, givenName: tokens?.givenName ?? null }, 'unexpectedError');
}
export const realServices: Services = {
  auth: {
    async signIn(input) {
      const expected = authGeneration;
      // A new Custom Tab must not overlap the previous account's end-session flow.
      if (logoutTask) await logoutTask.catch(() => undefined);
      requireGeneration(expected);
      const discovery = await withTimeout(AuthSession.fetchDiscoveryAsync(config.issuer));
      requireGeneration(expected);
      tokenEndpoint = discovery.tokenEndpoint ?? tokenEndpoint;
      const request = new AuthSession.AuthRequest({
        clientId: config.clientId,
        redirectUri: config.redirectUri,
        scopes: ['openid', 'profile', 'email'],
        responseType: AuthSession.ResponseType.Code,
        usePKCE: true,
        // Sign-up opens the Keycloak registration form (OIDC prompt=create, Keycloak >= 22;
        // the realm must allow registration). Explicit sign-in shows the login form even if
        // browser end-session was cancelled or unavailable. `create` is not in the enum.
        ...(input?.intent === 'create'
          ? { extraParams: { prompt: 'create' } }
          : { prompt: AuthSession.Prompt.Login }),
      });
      const response = await request.promptAsync(discovery, BROWSER_OPTIONS);
      requireGeneration(expected);
      // Closing the browser tab / pressing back: silent return to welcome, no error.
      if (response.type === 'cancel' || response.type === 'dismiss') throw new UserCancelledError();
      if (response.type !== 'success' || !response.params.code || !request.codeVerifier)
        throw new AppError('authError');
      const result = await withTimeout(
        AuthSession.exchangeCodeAsync(
          {
            clientId: config.clientId,
            code: response.params.code,
            redirectUri: config.redirectUri,
            extraParams: { code_verifier: request.codeVerifier },
          },
          discovery,
        ),
      );
      await remember(result, false, expected);
      session.profile = null;
      return loadIdentity();
    },
    // A stored session that can no longer be used (rejected or unrefreshable token) is
    // cleared and reported as "no session" (null), not as an expiry error.
    async restore() {
      const expected = authGeneration;
      await storage.remove(LEGACY_CHECKPOINT_KEY);
      const stored = await storage.read<Tokens>(TOKEN_KEY);
      requireGeneration(expected);
      tokens = stored;
      if (!tokens) return null;
      quietExpiry = true;
      try {
        return await loadIdentity();
      } catch (cause) {
        if (cause instanceof AppError && cause.expired) return null;
        throw cause;
      } finally {
        quietExpiry = false;
      }
    },
    async hasStoredSession() {
      try {
        return !!(tokens ?? (await storage.read<Tokens>(TOKEN_KEY)));
      } catch {
        return false;
      }
    },
    signOut() {
      if (logoutTask) {
        authGeneration += 1;
        return logoutTask;
      }
      const previous = tokens;
      authGeneration += 1;
      tokens = null;
      refreshTask = null;
      backgroundRefreshTask = null;
      session.profile = null;
      const task = (async () => {
        await credentialQueue.enqueue(async () => {
          await storage.remove(TOKEN_KEY);
          await storage.remove(LEGACY_CHECKPOINT_KEY);
        });
        if (!previous) return true;
        let discovery: AuthSession.DiscoveryDocument | null = null;
        try {
          discovery = await withTimeout(AuthSession.fetchDiscoveryAsync(config.issuer), 3000);
        } catch {
          return false;
        }
        if (previous.refreshToken) {
          try {
            await withTimeout(
              AuthSession.revokeAsync(
                { clientId: config.clientId, token: previous.refreshToken },
                discovery,
              ),
              3000,
            );
          } catch {
            /* Local secure session has already been removed. */
          }
        }
        return endBrowserSession(
          discovery.endSessionEndpoint,
          config.clientId,
          config.redirectUri,
          (url, redirect) =>
            withTimeout(WebBrowser.openAuthSessionAsync(url, redirect, BROWSER_OPTIONS), 15000),
        );
      })();
      logoutTask = task;
      void task.then(
        () => {
          if (logoutTask === task) logoutTask = null;
        },
        () => {
          if (logoutTask === task) logoutTask = null;
        },
      );
      return task;
    },
  },
  onboarding: {
    async submit(phone) {
      const current = required();
      const expected = authGeneration;
      const body: PersonalOnboardingRequest = {
        phoneNumber: phone.trim() || undefined,
        installationId: await installationId(),
        platform: Platform.OS === 'ios' ? 'IOS' : 'ANDROID',
        termsVersion: config.termsVersion,
      };
      try {
        await api.post<PersonalOnboardingResponse>('/api/onboarding/personal', body);
      } catch (cause) {
        requireGeneration(expected);
        if (cause instanceof AppError && cause.key === 'networkError') {
          // A profile can have an older accepted terms version or a missing device/KYC row.
          // It cannot prove this submission completed. The transaction-safe manual retry
          // returns 200 only after every onboarding write has succeeded.
          throw new AppError('saveOutcomeUnknown');
        }
        failWith(cause, (error) => {
          switch (onboardingCode(error)) {
            case 'ONBOARDING_IDENTITY_CONFLICT':
              return 'identityConflict';
            case 'ONBOARDING_INVALID_REQUEST':
              return 'saveInvalid';
            case 'TERMS_VERSION_UNSUPPORTED':
              return 'termsUpdateRequired';
            case 'ONBOARDING_RETRY_REQUIRED':
              return 'saveOutcomeUnknown';
            default:
              return 'saveError';
          }
        });
      }
      requireGeneration(expected);
      // The response's kycStatus is always NOT_STARTED (§3); a returning customer may already
      // be verified, so the real status is re-read.
      return loadProfile(current.identity, 'saveError');
    },
  },
  kyc: {
    // POST /api/kyc/start only STARTS verification (it has side effects, §4). Idempotent once
    // started. HTTP failures become KycFlowError (errorCode from PR #61, else the status).
    async submit() {
      const current = required();
      const expected = authGeneration;
      try {
        await api.post<KycStartResponse>('/api/kyc/start', {});
      } catch (cause) {
        if (cause instanceof ApiError)
          throw new KycFlowError(classifyKycFailure(cause.status, cause.bodyText));
        throw cause;
      }
      requireGeneration(expected);
      return loadProfile(current.identity, 'kycError');
    },
    // Status refresh is read-only (GET /api/profile), never a second POST.
    async refresh() {
      return loadProfile(required().identity, 'kycError');
    },
  },
  account: {
    async get() {
      return loadProfile(required().identity, 'unexpectedError');
    },
  },
  wallets: {
    async list() {
      try {
        return parseWallets(await api.get<unknown>('/api/wallets'));
      } catch (cause) {
        failWith(cause, (error) =>
          error.status === 503 ? 'balancesUnavailable' : 'unexpectedError',
        );
      }
    },
  },
  transfers: {
    // §12. Uniform 404 (never speculate why), 429 rate limit (10/min), 403 sender not eligible.
    async lookup(email) {
      const body: RecipientLookupRequest = { email: email.trim() };
      try {
        return parseRecipientLookup(await api.post<unknown>('/api/recipients/lookup', body));
      } catch (cause) {
        failWith(cause, (error) => {
          switch (error.status) {
            case 400:
              return 'recipientInvalid';
            case 403:
              return 'senderNotEligible';
            case 404:
              return 'recipientNotFound';
            case 429:
              return 'recipientRateLimited';
            default:
              return 'unexpectedError';
          }
        });
      }
    },
    // §5. Errors deliberately pass through unmapped (transfers/outcome.ts decides whether money
    // may have moved). A malformed 200 surfaces as ContractError => uncertain.
    async send(request) {
      return parseTransfer(await api.post<unknown>('/api/transfers/internal', request));
    },
  },
  fx: {
    // §14. Errors pass through unmapped (fx/outcome.ts decides). A malformed 200 is a
    // ContractError: for convert that is "uncertain", never "failed".
    async quote(request) {
      return parseFxQuote(await api.post<unknown>('/api/fx/quotes', request));
    },
    async convert(request) {
      return parseFxConversion(await api.post<unknown>('/api/fx/demo/conversions', request));
    },
    async findByKey(idempotencyKey) {
      return parseFxConversion(
        await api.get<unknown>('/api/fx/demo/conversions', { query: { idempotencyKey } }),
      );
    },
  },
  transactions: {
    // §6. Formance down => 500 (not 503) today.
    async list(currency, cursor, pageSize) {
      try {
        return parseTransactionPage(
          await api.get<unknown>('/api/transactions', {
            query: { currency, pageSize, cursor: cursor ?? undefined },
          }),
        );
      } catch (cause) {
        failWith(cause, (error) =>
          error.status >= 500 ? 'historyUnavailable' : 'unexpectedError',
        );
      }
    },
  },
  kycCapture: createSumsubKycCapture({
    requestToken: () => requestKycSdkToken(api),
    renewToken: () => requestKycSdkToken(sdkRenewalApi),
  }),
  notifications: {
    // §13. Read-only feed; read/unread state is device-side (src/notifications/).
    async list(cursor, pageSize) {
      try {
        return parseNotificationPage(
          await api.get<unknown>('/api/notifications', {
            query: { pageSize, cursor: cursor ?? undefined },
          }),
        );
      } catch (cause) {
        failWith(cause, (error) =>
          error.status >= 500 ? 'notificationsUnavailable' : 'unexpectedError',
        );
      }
    },
  },
};
