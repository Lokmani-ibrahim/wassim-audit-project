const { chromium } = require('playwright-core');
const out = process.argv[2];
(async () => {
  const browser = await chromium.launch({ executablePath: '/usr/bin/google-chrome', headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1360, height: 860 } });
  const page = await ctx.newPage();
  const logs = [];
  page.on('console', m => { if (m.type() !== 'debug') logs.push(`[console.${m.type()}] ${m.text()}`); });
  page.on('response', r => { if (r.status() >= 400) logs.push(`[http ${r.status()}] ${r.url()}`); });
  await page.goto('http://localhost:5173/', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  logs.push('[info] after load url=' + page.url() + ' body=' + (await page.innerText('body')).replace(/\s+/g,' ').slice(0,200));
  await page.screenshot({ path: out + '/bo-connected-start.png' });
  const signIn = page.getByRole('button', { name: /sign in/i });
  if (await signIn.count()) { await signIn.first().click(); await page.waitForLoadState('networkidle'); }
  await page.waitForTimeout(1500);
  logs.push('[info] login url=' + page.url().split('?')[0]);
  await page.screenshot({ path: out + '/kc-staff-login-with-register-link.png' });
  const reg = page.getByRole('link', { name: /register/i });
  logs.push('[info] register link on staff login: ' + (await reg.count()));
  if (await reg.count()) {
    await reg.first().click(); await page.waitForLoadState('networkidle');
    await page.fill('#email', 'audit.weakpw@example.test');
    if (await page.locator('#firstName').count()) await page.fill('#firstName', 'Audit');
    if (await page.locator('#lastName').count()) await page.fill('#lastName', 'Weak');
    await page.fill('#password', '1');
    await page.fill('#password-confirm', '1');
    await page.screenshot({ path: out + '/kc-register-weak-password-form.png' });
    await page.locator('[type=submit]').first().click();
    await page.waitForLoadState('networkidle'); await page.waitForTimeout(3000);
    logs.push('[info] after register url=' + page.url().split('?')[0] + ' body=' + (await page.innerText('body')).replace(/\s+/g,' ').slice(0,300));
    await page.screenshot({ path: out + '/kc-register-weak-password-result.png' });
  }
  require('fs').writeFileSync(out + '/shots3-console.log', logs.join('\n'));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
