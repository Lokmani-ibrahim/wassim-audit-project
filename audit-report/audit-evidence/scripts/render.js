const { chromium } = require('playwright-core');
const path = require('path');
(async () => {
  const files = process.argv.slice(2);
  const browser = await chromium.launch({ executablePath: '/usr/bin/google-chrome', headless: true });
  const ctx = await browser.newContext({ deviceScaleFactor: 2, viewport: { width: 1500, height: 1000 } });
  const page = await ctx.newPage();
  for (const f of files) {
    await page.goto('file://' + path.resolve(f));
    await page.waitForTimeout(300);
    const el = await page.$('svg#d');
    await el.screenshot({ path: f.replace(/\.html$/, '.png') });
    console.log('rendered', f.replace(/\.html$/, '.png'));
  }
  await browser.close();
})();
