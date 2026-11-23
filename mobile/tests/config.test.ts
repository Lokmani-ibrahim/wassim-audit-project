import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolveConfig, type RawEnv } from '../src/config';
import { MOCK_FEATURES } from '../src/mockFeatures';

const validProdEnv: RawEnv = {
  mockFeatures: 'none',
  apiUrl: 'https://api.neos.example',
  issuer: 'https://auth.neos.example/realms/neos',
  clientId: 'neos-mobile',
  redirectUri: 'neos://auth',
  termsVersion: '1.0',
  termsUrl: 'https://neos.example/terms',
};

test('nothing is mocked when the env var is unset or "none", in dev and non-dev', () => {
  assert.equal(resolveConfig({}, true).mocks.size, 0);
  assert.equal(resolveConfig({ mockFeatures: 'none' }, true).mocks.size, 0);
  assert.equal(resolveConfig({ mockFeatures: '   ' }, true).mocks.size, 0);
  assert.throws(() => resolveConfig({}, false));
  assert.equal(resolveConfig(validProdEnv, false).mocks.size, 0);
});

test('"all" mocks every feature, in dev only', () => {
  assert.deepEqual(
    [...resolveConfig({ mockFeatures: 'all' }, true).mocks].sort(),
    [...MOCK_FEATURES].sort(),
  );
});

test('non-dev ALWAYS has an empty mock set, whatever the env says', () => {
  for (const value of ['all', 'wallets,kyc', 'auth', 'bogus,,,', 'ALL', 'true']) {
    assert.equal(
      resolveConfig({ ...validProdEnv, mockFeatures: value }, false).mocks.size,
      0,
      value,
    );
  }
});

test('a list selects exactly those features; whitespace, case and duplicates are tolerated', () => {
  assert.deepEqual(
    [...resolveConfig({ mockFeatures: ' wallets , KYC,kycCapture,wallets,,' }, true).mocks].sort(),
    ['kyc', 'kycCapture', 'wallets'],
  );
  assert.deepEqual([...resolveConfig({ mockFeatures: 'KycCapture' }, true).mocks], ['kycCapture']);
});

test('unknown names are rejected in dev with a clear error naming them', () => {
  assert.throws(
    () => resolveConfig({ mockFeatures: 'wallets,walets,banana' }, true),
    /unknown feature\(s\): walets, banana/,
  );
  // "all" / "none" are exact keywords: near-misses are treated as (unknown) feature names.
  assert.throws(() => resolveConfig({ mockFeatures: 'ALL' }, true), /unknown feature\(s\): ALL/);
  assert.throws(() => resolveConfig({ mockFeatures: 'all,wallets' }, true), /unknown feature/);
  assert.throws(() => resolveConfig({ mockFeatures: 'true' }, true), /unknown feature\(s\): true/);
});

test('mocking auth requires mocking every backend-calling feature', () => {
  assert.throws(
    () => resolveConfig({ mockFeatures: 'auth' }, true),
    /also mock: onboarding, kyc, kycCapture, wallets, notifications/,
  );
  assert.throws(
    () => resolveConfig({ mockFeatures: 'auth,onboarding,kyc,kycCapture' }, true),
    /also mock: wallets, notifications/,
  );
  // kycCapture asks the backend for a Sumsub SDK token, so it is required too.
  assert.throws(
    () => resolveConfig({ mockFeatures: 'auth,onboarding,kyc,wallets,notifications' }, true),
    /"kyc" but not "kycCapture"/,
  );
  assert.equal(
    resolveConfig({ mockFeatures: 'auth,onboarding,kyc,kycCapture,wallets,notifications' }, true)
      .mocks.size,
    6,
  );
});

test('a mocked KYC start requires a mocked Sumsub session (no real SDK on a fake applicant)', () => {
  assert.throws(() => resolveConfig({ mockFeatures: 'kyc' }, true), /"kyc" but not "kycCapture"/);
  // The reverse is allowed: real KYC start with a simulated SDK session (dev only).
  assert.deepEqual([...resolveConfig({ mockFeatures: 'kycCapture' }, true).mocks], ['kycCapture']);
});

test('real auth with mocked downstream features is allowed (feature-by-feature development)', () => {
  assert.deepEqual([...resolveConfig({ mockFeatures: 'wallets,kycCapture' }, true).mocks].sort(), [
    'kycCapture',
    'wallets',
  ]);
  assert.equal(resolveConfig({ mockFeatures: 'kyc,kycCapture' }, true).mocks.has('auth'), false);
});

test('non-dev rejects http:// apiUrl and issuer', () => {
  assert.throws(
    () => resolveConfig({ ...validProdEnv, apiUrl: 'http://api.neos.example' }, false),
    /apiUrl must use https/,
  );
  assert.throws(
    () => resolveConfig({ ...validProdEnv, issuer: 'http://auth.neos.example/realms/neos' }, false),
    /issuer must use https/,
  );
  assert.throws(
    () => resolveConfig({ ...validProdEnv, termsUrl: 'http://neos.example/terms' }, false),
    /termsUrl must use https/,
  );
});

test('non-dev rejects missing required values', () => {
  assert.throws(
    () => resolveConfig({ ...validProdEnv, apiUrl: undefined }, false),
    /apiUrl is missing/,
  );
  assert.throws(
    () => resolveConfig({ ...validProdEnv, clientId: '' }, false),
    /clientId is missing/,
  );
  assert.throws(
    () => resolveConfig({ ...validProdEnv, termsVersion: '' }, false),
    /termsVersion is missing/,
  );
  assert.throws(
    () => resolveConfig({ ...validProdEnv, termsUrl: undefined }, false),
    /termsUrl is missing/,
  );
});

test('non-dev rejects REPLACE_ME placeholders anywhere in config', () => {
  assert.throws(
    () => resolveConfig({ ...validProdEnv, apiUrl: 'https://REPLACE_ME.example' }, false),
    /apiUrl.*REPLACE_ME/,
  );
  assert.throws(
    () => resolveConfig({ ...validProdEnv, termsUrl: 'https://REPLACE_ME.example/terms' }, false),
    /termsUrl.*REPLACE_ME/,
  );
});

test('non-dev accepts a fully valid https config', () => {
  const resolved = resolveConfig(validProdEnv, false);
  assert.equal(resolved.mocks.size, 0);
  assert.equal(resolved.apiUrl, 'https://api.neos.example');
  assert.equal(resolved.issuer, 'https://auth.neos.example/realms/neos');
});

test('dev accepts localhost http defaults when nothing is set', () => {
  const resolved = resolveConfig({}, true);
  assert.equal(resolved.apiUrl, 'http://localhost:8080');
  assert.equal(resolved.issuer, 'http://localhost:8081/realms/neos');
  assert.equal(resolved.clientId, 'neos-mobile');
  assert.equal(resolved.redirectUri, 'neos://auth');
  assert.equal(resolved.termsVersion, '1.0');
});
