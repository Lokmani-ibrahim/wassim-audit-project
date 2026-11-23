// Uses an externally supplied Playwright installation; no app dependency is added.
const { chromium } = await import(process.env.PACK_B_PLAYWRIGHT_MODULE ?? 'playwright');
const password = process.env.PACK_B_BROWSER_PASSWORD;
if (!password) throw new Error('Missing disposable browser password');

const browser = await chromium.launch({ headless: true });
const base = 'http://localhost:5173';
const api = 'http://localhost:8082/api/backoffice/customers';
const customerId = '00000000-0000-0000-0000-000000b00002';
const cases = [
  ['review-staff@review.invalid', 'Customers', 200, 200],
  ['review-admin@review.invalid', 'Access denied', 200, 403],
  ['review-customer@review.invalid', 'Access denied', 403, 403],
  ['review-mixed@review.invalid', 'Access denied', 403, 403],
  ['review-combined@review.invalid', 'Access denied', 403, 403],
  ['review-operations@review.invalid', 'Access denied', 403, 403],
];
const results = [];
let stage = 'start';
try {
  for (const [username, heading, meStatus, directoryStatus] of cases) {
    stage = `browser flow for ${username}`;
    const context = await browser.newContext();
    const page = await context.newPage();
    let bearer;
    const statuses = { me: [], list: [], detail: [] };
    let pkce = false;
    page.on('request', request => {
      const url = new URL(request.url());
      if (url.pathname.endsWith('/protocol/openid-connect/auth'))
        pkce ||= url.searchParams.get('code_challenge_method') === 'S256';
      if (url.pathname === '/api/backoffice/me') bearer = request.headers().authorization;
    });
    page.on('response', response => {
      const path = new URL(response.url()).pathname;
      if (path === '/api/backoffice/me') statuses.me.push(response.status());
      if (path === '/api/backoffice/customers') statuses.list.push(response.status());
      if (path === `/api/backoffice/customers/${customerId}`) statuses.detail.push(response.status());
    });
    await page.goto(`${base}/customers`);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.locator('#username').fill(username);
    await page.locator('#password').fill(password);
    await page.locator('#kc-login').click();
    await page.getByRole('heading', { name: heading, exact: true }).waitFor({ timeout: 15000 });
    if (!pkce || statuses.me.at(-1) !== meStatus || !bearer)
      throw new Error('OIDC or staff authorization mismatch');
    if (new URL(page.url()).pathname !== '/customers') throw new Error('Deep link not restored');
    const directList = await context.request.get(api, { headers: { Authorization: bearer } });
    const directDetail = await context.request.get(`${api}/${customerId}`,
      { headers: { Authorization: bearer } });
    if (directList.status() !== directoryStatus || directDetail.status() !== directoryStatus)
      throw new Error('Direct directory authorization mismatch');

    if (directoryStatus === 200) {
      await page.getByRole('link', { name: 'Customers' }).waitFor();
      await page.getByLabel('Exact email or user ID').fill('directory-two@review.invalid');
      await page.getByRole('button', { name: 'Search' }).click();
      await page.getByRole('link', { name: 'directory-two@review.invalid' }).waitFor();
      if (!statuses.list.includes(200)) throw new Error('Connected list did not load');
      await page.getByRole('link', { name: 'directory-two@review.invalid' }).click();
      await page.getByRole('heading', { name: 'Customer detail' }).waitFor();
      await page.getByText('IN_REVIEW').waitFor();
      if (!statuses.detail.includes(200)) throw new Error('Connected detail did not load');
      await page.getByRole('link', { name: 'Customers' }).first().click();
      await page.getByRole('heading', { name: 'Customers', exact: true }).waitFor();
      await page.getByLabel('Exact email or user ID').fill('directory-one@review.invalid');
      await page.getByRole('button', { name: 'Search' }).click();
      await page.getByRole('link', { name: 'directory-one@review.invalid' }).waitFor();
      await page.getByRole('link', { name: 'directory-one@review.invalid' }).click();
      await page.getByText('No KYC record').waitFor();
    } else if (meStatus === 200) {
      if (await page.getByRole('link', { name: 'Customers' }).count())
        throw new Error('ADMIN navigation exposed customer directory');
      if (statuses.list.length || statuses.detail.length)
        throw new Error('ADMIN route requested customer data');
      await page.goto(`${base}/customers/${customerId}`);
      await page.getByRole('heading', { name: 'Access denied' }).waitFor();
    }
    results.push({ account: username, pkceS256: pkce, me: meStatus,
      directList: directList.status(), directDetail: directDetail.status(),
      ui: directoryStatus === 200 ? 'list, detail, missing KYC' : heading });
    await context.close();
  }
  console.log(JSON.stringify(results, null, 2));
} catch (error) {
  console.error(`Browser verification failed at ${stage}: ${error.message?.split('\n')[0]}`);
  process.exitCode = 1;
} finally {
  await browser.close();
}
