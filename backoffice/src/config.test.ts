import { describe, expect, it } from 'vitest';
import { parseConfig } from './config';

const origin = 'http://localhost:5173';
const valid = {
  VITE_KEYCLOAK_URL: 'http://localhost:8081',
  VITE_KEYCLOAK_REALM: 'neos',
  VITE_KEYCLOAK_CLIENT_ID: 'neos-backoffice',
  VITE_API_BASE_URL: '/api',
  VITE_REDIRECT_URI: `${origin}/auth/callback`,
  VITE_POST_LOGOUT_URI: `${origin}/`,
  VITE_ENVIRONMENT_LABEL: 'Local isolated realm',
};

describe('production configuration', () => {
  it('requires explicit exact values', () => {
    expect(parseConfig(valid, origin).clientId).toBe('neos-backoffice');
    for (const key of Object.keys(valid)) {
      expect(() => parseConfig({ ...valid, [key]: '' }, origin)).toThrow();
    }
  });
  it('rejects foreign callback, API origin and fixture labeling', () => {
    expect(() => parseConfig({ ...valid, VITE_REDIRECT_URI: 'https://evil.example/auth/callback' }, origin)).toThrow();
    expect(() => parseConfig({ ...valid, VITE_API_BASE_URL: 'https://evil.example/api' }, origin)).toThrow();
    expect(() => parseConfig({ ...valid, VITE_ENVIRONMENT_LABEL: 'demo' }, origin)).toThrow();
    expect(() => parseConfig(valid, 'http://backoffice.example')).toThrow();
  });
});
