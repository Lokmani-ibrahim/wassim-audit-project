export type BackofficeConfig = {
  keycloakUrl: string;
  realm: string;
  clientId: string;
  apiBaseUrl: string;
  redirectUri: string;
  postLogoutUri: string;
  environmentLabel: string;
};

export function parseConfig(env: Record<string, unknown>, origin: string): BackofficeConfig {
  const appOrigin = new URL(origin);
  if (appOrigin.protocol !== 'https:' && appOrigin.hostname !== 'localhost' &&
      appOrigin.hostname !== '127.0.0.1') {
    throw new Error('Backoffice requires HTTPS outside local development');
  }
  const required = (key: string): string => {
    const value = env[key];
    if (typeof value !== 'string' || !value.trim()) throw new Error(`Missing ${key}`);
    return value.trim();
  };
  const exactUrl = (key: string, path: string): string => {
    const url = new URL(required(key));
    if (url.origin !== origin || url.username || url.password ||
        url.pathname !== path || url.search || url.hash) {
      throw new Error(`Invalid ${key}: expected an exact same-origin ${path} URL`);
    }
    return url.href;
  };
  const keycloak = new URL(required('VITE_KEYCLOAK_URL'));
  if (keycloak.username || keycloak.password || keycloak.search || keycloak.hash ||
      !['https:', 'http:'].includes(keycloak.protocol)) {
    throw new Error('Invalid VITE_KEYCLOAK_URL');
  }
  if (keycloak.protocol !== 'https:' && keycloak.hostname !== 'localhost' && keycloak.hostname !== '127.0.0.1') {
    throw new Error('Keycloak requires HTTPS outside local development');
  }
  const realm = required('VITE_KEYCLOAK_REALM');
  const clientId = required('VITE_KEYCLOAK_CLIENT_ID');
  if (!/^[a-zA-Z0-9._-]+$/.test(realm) || !/^[a-zA-Z0-9._-]+$/.test(clientId)) {
    throw new Error('Invalid realm or client ID');
  }
  const api = new URL(required('VITE_API_BASE_URL'), origin);
  if (api.origin !== origin || api.username || api.password ||
      api.pathname !== '/api' || api.search || api.hash) {
    throw new Error('VITE_API_BASE_URL must be same-origin /api');
  }
  const environmentLabel = required('VITE_ENVIRONMENT_LABEL');
  if (/demo|fixture/i.test(environmentLabel)) throw new Error('Connected environment cannot be labeled as demo');
  return {
    keycloakUrl: keycloak.href.replace(/\/$/, ''), realm, clientId,
    apiBaseUrl: api.href.replace(/\/$/, ''),
    redirectUri: exactUrl('VITE_REDIRECT_URI', '/auth/callback'),
    postLogoutUri: exactUrl('VITE_POST_LOGOUT_URI', '/'),
    environmentLabel,
  };
}
