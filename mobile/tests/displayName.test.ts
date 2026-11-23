import { test } from 'node:test';
import assert from 'node:assert/strict';
import { decodeJwtClaims, displayName, givenNameFromIdToken } from '../src/utils/idToken';

// Unsigned test tokens: the app never verifies the ID token (display only).
function token(claims: unknown): string {
  const encode = (value: unknown) =>
    Buffer.from(JSON.stringify(value), 'utf8').toString('base64url');
  return `${encode({ alg: 'RS256', typ: 'JWT' })}.${encode(claims)}.signature`;
}

test('given_name from the ID token', () => {
  assert.equal(givenNameFromIdToken(token({ given_name: 'Abir', email: 'a@b.c' })), 'Abir');
  assert.equal(givenNameFromIdToken(token({ given_name: '  Kais  ' })), 'Kais');
  // Multi-byte UTF-8 (2, 3 and 4 bytes).
  assert.equal(givenNameFromIdToken(token({ given_name: 'Zoé' })), 'Zoé');
  assert.equal(givenNameFromIdToken(token({ given_name: 'عبير' })), 'عبير');
  assert.equal(givenNameFromIdToken(token({ given_name: 'Ana 🌍' })), 'Ana 🌍');
  assert.equal(givenNameFromIdToken(token({ given_name: 'x'.repeat(100) }))!.length, 64);
});

test('missing, blank or malformed tokens give no name', () => {
  assert.equal(givenNameFromIdToken(undefined), null);
  assert.equal(givenNameFromIdToken(null), null);
  assert.equal(givenNameFromIdToken(''), null);
  assert.equal(givenNameFromIdToken(token({ email: 'a@b.c' })), null);
  assert.equal(givenNameFromIdToken(token({ given_name: '   ' })), null);
  assert.equal(givenNameFromIdToken(token({ given_name: 42 })), null);
  assert.equal(givenNameFromIdToken('not-a-jwt'), null);
  assert.equal(givenNameFromIdToken('a.!!!.c'), null);
  assert.equal(givenNameFromIdToken(`a.${Buffer.from('[1,2]').toString('base64url')}.c`), null);
  assert.equal(
    givenNameFromIdToken(`a.${Buffer.from([0xff, 0xfe]).toString('base64url')}.c`),
    null,
  );
  assert.equal(decodeJwtClaims(token({ sub: 'x' }))?.sub, 'x');
});

test('greeting: first name, else email local part, never the full email', () => {
  assert.equal(displayName('Abir', 'abir.demo1@neos.local'), 'Abir');
  assert.equal(displayName(null, 'abir.demo1@neos.local'), 'abir.demo1');
  assert.equal(displayName(undefined, 'neos-sender@neos.local'), 'neos-sender');
  assert.equal(displayName('   ', 'neos-sender@neos.local'), 'neos-sender');
  assert.equal(displayName(null, '@neos.local'), '');
  assert.equal(displayName(null, ''), '');
  assert.equal(displayName(null, null), '');
  for (const name of [displayName(null, 'a.b@c.d'), displayName('', 'x@y.z')])
    assert.ok(!name.includes('@'));
});
