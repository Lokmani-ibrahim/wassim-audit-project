const { chromium } = require('playwright-core');
const out = process.argv[2];
(async () => {
  const browser = await chromium.launch({ executablePath: '/usr/bin/google-chrome', headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1360, height: 860 }, deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  const logs = [];
  page.on('console', m => logs.push(`[console.${m.type()}] ${m.text()}`));
  page.on('requestfailed', r => logs.push(`[requestfailed] ${r.method()} ${r.url()} ${r.failure()?.errorText}`));
  // 1. Connected backoffice without Keycloak running
  const t0 = Date.now();
  await page.goto('http://127.0.0.1:5173/', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(12000);
  logs.push(`[info] connected mode body after ${(Date.now()-t0)/1000}s: ` + (await page.innerText('body')).replace(/\s+/g,' ').slice(0,300));
  await page.screenshot({ path: out + '/bo-connected-no-keycloak.png' });
  // 2. Demo login page
  await page.goto('http://127.0.0.1:5174/demo.html', { waitUntil: 'networkidle' });
  await page.waitForTimeout(800);
  await page.screenshot({ path: out + '/bo-demo-login.png' });
  logs.push('[info] demo url ' + page.url());
  require('fs').writeFileSync(out + '/shots1-console.log', logs.join('\n'));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
