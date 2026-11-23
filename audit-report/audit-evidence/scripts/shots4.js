const { chromium } = require('playwright-core');
const fs = require('fs');
const [out, tag, user, pwFile] = process.argv.slice(2);
const pw = fs.readFileSync(pwFile, 'utf8');
(async () => {
  const browser = await chromium.launch({ executablePath: '/usr/bin/google-chrome', headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1360, height: 860 } });
  const page = await ctx.newPage();
  const logs = [];
  page.on('response', r => { if (r.url().includes('/api/')) logs.push(`[api ${r.status()}] ${r.request().method()} ${r.url().replace('http://localhost:5173','')}`); });
  page.on('console', m => { if (m.type() === 'error') logs.push(`[console.error] ${m.text()}`); });
  await page.goto('http://localhost:5173/', { waitUntil: 'networkidle' });
  await page.getByRole('button', { name: /sign in/i }).first().click();
  await page.waitForLoadState('networkidle');
  await page.fill('#username', user); await page.fill('#password', pw);
  await page.locator('#kc-login').click();
  await page.waitForLoadState('networkidle'); await page.waitForTimeout(3000);
  logs.push(`[info] ${tag} after login url=${page.url()} body=` + (await page.innerText('body')).replace(/\s+/g,' ').slice(0,300));
  await page.screenshot({ path: `${out}/bo-${tag}-after-login.png` });
  if (tag === 'staff') {
    const row = page.locator('tbody tr').first();
    if (await row.count()) {
      await row.click(); await page.waitForTimeout(3500);
      logs.push(`[info] detail url=${page.url()} body=` + (await page.innerText('body')).replace(/\s+/g,' ').slice(0,500));
      await page.screenshot({ path: `${out}/bo-staff-customer-detail.png`, fullPage: true });
    }
  }
  fs.writeFileSync(`${out}/shots4-${tag}-console.log`, logs.join('\n'));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
