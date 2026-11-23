// Runs only against disposable, synthetic Keycloak, backend and ledger services.
import { pathToFileURL } from 'node:url';
const modulePath = process.env.PACK_C_PLAYWRIGHT_MODULE;
const { chromium } = await import(modulePath ? pathToFileURL(modulePath).href : 'playwright');
const password = process.env.PACK_C_BROWSER_PASSWORD;
if (!password) throw new Error('Missing disposable browser password');

const browser = await chromium.launch({ headless: true });
const customerId = '00000000-0000-0000-0000-000000b00001';
const base = 'http://localhost:5173';
const api = `http://localhost:8082/api/backoffice/customers/${customerId}`;
const cases = [
  ['review-staff@review.invalid', 200],
  ['review-admin@review.invalid', 403],
  ['review-customer@review.invalid', 403],
  ['review-mixed@review.invalid', 403],
  ['review-combined@review.invalid', 403],
  ['review-operations@review.invalid', 403],
];
const results = [];
let stage = 'start';
let activePage;
let meStatuses = [];
try {
  for (const [username, expected] of cases) {
    stage = username.split('@')[0];
    const context = await browser.newContext();
    const page = await context.newPage();
    activePage = page;
    meStatuses = [];
    let bearer;
    let pkce = false;
    const financialRequests = [];
    page.on('request', request => {
      const url = new URL(request.url());
      if (url.pathname.endsWith('/protocol/openid-connect/auth'))
        pkce ||= url.searchParams.get('code_challenge_method') === 'S256';
      if (url.pathname === '/api/backoffice/me') bearer = request.headers().authorization;
      if (url.pathname.endsWith('/wallets') || url.pathname.endsWith('/transactions'))
        financialRequests.push(url.pathname);
    });
    page.on('response', response => {
      if (new URL(response.url()).pathname === '/api/backoffice/me') meStatuses.push(response.status());
    });
    await page.goto(`${base}/customers/${customerId}`);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.locator('#username').fill(username);
    await page.locator('#password').fill(password);
    await page.locator('#kc-login').click();
    await page.getByRole('heading', { name: expected === 200 ? 'Customer detail' : 'Access denied' }).waitFor({ timeout: 20000 });
    if (!pkce || !bearer) throw new Error(`OIDC flow failed for ${username}`);
    const headers = { Authorization: bearer };
    const wallet = await context.request.get(`${api}/wallets`, { headers, timeout: 15000 });
    const history = await context.request.get(`${api}/transactions?currency=TND&pageSize=2`,
      { headers, timeout: 15000 });
    if (expected === 403) {
      if (wallet.status() !== 403 || history.status() !== 403 || financialRequests.length)
        throw new Error(`Financial authorization failed for ${username}`);
    } else {
      await page.getByRole('heading', { name: 'Wallets and balances' }).waitFor();
      await page.getByRole('heading', { name: 'Transaction history' }).waitFor();
      if (![200, 503].includes(wallet.status()) || ![200, 503].includes(history.status()))
        throw new Error('Unexpected staff financial status');
      if (wallet.status() === 200 && history.status() === 200) {
        await page.getByText('4.760 TND').waitFor({ timeout: 15000 });
        await page.getByLabel('Transaction currency').selectOption('TND');
        await page.getByText('1.250 TND').waitFor({ timeout: 15000 });
      } else if (wallet.status() === 503) {
        await page.getByText('Ledger service unavailable').first().waitFor({ timeout: 15000 });
        await page.getByRole('button', { name: 'Retry' }).first().click();
        await page.getByText('Ledger service unavailable').first().waitFor({ timeout: 15000 });
      }
    }
    results.push({ roleCase: username.split('@')[0], pkceS256: pkce,
      wallet: wallet.status(), history: history.status(),
      browser: expected === 200 ? (wallet.status() === 200 ?
        'customer detail, TND wallet and transaction history' : 'provider error and retry') : 'direct route denied' });
    await context.close();
  }
  console.log(JSON.stringify(results));
} catch (error) {
  const path = activePage ? new URL(activePage.url()).pathname : 'none';
  const headings = activePage ? await activePage.locator('h1').allTextContents().catch(() => []) : [];
  console.error(`Package C browser check failed at ${stage} on ${path}, headings ${headings.join('|')}, me ${meStatuses.join(',')}: ${error.message?.split('\n')[0]}`);
  process.exitCode = 1;
} finally {
  await browser.close();
}
