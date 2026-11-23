const { chromium } = require('playwright-core');
const out = process.argv[2];
(async () => {
  const browser = await chromium.launch({ executablePath: '/usr/bin/google-chrome', headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1360, height: 860 } });
  const page = await ctx.newPage();
  const logs = [];
  page.on('console', m => logs.push(`[console.${m.type()}] ${m.text()}`));
  page.on('requestfailed', r => logs.push(`[requestfailed] ${r.method()} ${r.url()} ${r.failure()?.errorText}`));
  page.on('response', r => { if (r.status() >= 400) logs.push(`[http ${r.status()}] ${r.url()}`); });
  for (const wait of [3000, 20000, 45000]) {
    const t0 = Date.now();
    await page.goto('http://127.0.0.1:5173/', { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(wait);
    logs.push(`[info] wait ${wait}ms url=${page.url()} body=` + (await page.innerText('body')).replace(/\s+/g,' ').slice(0,300));
  }
  await page.screenshot({ path: out + '/bo-connected-no-keycloak.png' });
  require('fs').writeFileSync(out + '/shots2-console.log', logs.join('\n'));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
