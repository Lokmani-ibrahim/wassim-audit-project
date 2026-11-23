import { test } from 'node:test';
import assert from 'node:assert/strict';
import { endBrowserSession } from '../src/services/logout';

test('end-session uses the discovered endpoint and validated callback, without a token in the URL', async () => {
  let opened = '';
  const ended = await endBrowserSession(
    'https://auth.test/logout',
    'neos-mobile',
    'neos://auth',
    async (url, redirect) => {
      opened = url;
      assert.equal(redirect, 'neos://auth');
      return { type: 'success' };
    },
  );
  assert.equal(ended, true);
  assert.equal(
    opened,
    'https://auth.test/logout?client_id=neos-mobile&post_logout_redirect_uri=neos%3A%2F%2Fauth',
  );
});

test('browser cancel, browser failure, and missing discovery leave local logout complete', async () => {
  assert.equal(
    await endBrowserSession('https://auth.test/logout', 'neos-mobile', 'neos://auth', async () => ({
      type: 'cancel',
    })),
    false,
  );
  assert.equal(
    await endBrowserSession('https://auth.test/logout', 'neos-mobile', 'neos://auth', async () => {
      throw new Error('browser unavailable');
    }),
    false,
  );
  assert.equal(
    await endBrowserSession(undefined, 'neos-mobile', 'neos://auth', async () => {
      throw new Error('must not open');
    }),
    false,
  );
});
