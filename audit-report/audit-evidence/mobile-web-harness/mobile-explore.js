// Navigation pas à pas : node mobile-explore.js <outdir> <name> <action1> <action2> ...
// action = "click:<texte exact ou regex /.../>" | "fill:<placeholder>=<valeur>" | "wait:<ms>" | "shot:<nom>"
const { chromium } = require('playwright-core');
const fs = require('fs');
const [out, ...rawActions] = process.argv.slice(2);
const actions = rawActions.flatMap(a => a.startsWith('@') ? fs.readFileSync(a.slice(1), 'utf8').split('\n').map(l => l.trim()).filter(Boolean) : [a]);
(async () => {
  const browser = await chromium.launch({ executablePath: '/usr/bin/google-chrome', headless: true });
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  const page = await ctx.newPage();
  const logs = [];
  page.on('pageerror', e => logs.push(`[pageerror] ${String(e).slice(0,300)}`));
  page.on('console', m => { if (m.type() === 'error') logs.push(`[console.error] ${m.text().slice(0,300)}`); });
  await page.goto('http://localhost:8090', { waitUntil: 'domcontentloaded', timeout: 120000 });
  await page.waitForTimeout(6000);
  for (const a of actions) {
    const [kind, ...rest] = a.split(':'); const arg = rest.join(':');
    try {
      if (kind === 'click') {
        const loc = arg.startsWith('/') ? page.getByText(new RegExp(arg.slice(1, -1), 'i')) : page.getByText(arg, { exact: true });
        await loc.last().click({ timeout: 8000 });
        await page.waitForTimeout(1800);
      } else if (kind === 'fill') {
        const [ph, val] = arg.split('=');
        await page.getByPlaceholder(new RegExp(ph, 'i')).first().fill(val);
      } else if (kind === 'input') {
        const [idx, val] = arg.split('=');
        await page.locator('input, textarea').nth(Number(idx)).fill(val);
      } else if (kind === 'body') {
        logs.push(`[body:${arg}] ` + (await page.innerText('body').catch(()=>'')).replace(/\s+/g,' ').slice(0,400));
      } else if (kind === 'back') { await page.goBack(); await page.waitForTimeout(1500);
      } else if (kind === 'aria') { await page.getByLabel(new RegExp(arg, 'i')).first().click({ timeout: 8000 }); await page.waitForTimeout(1800);
      } else if (kind === 'labels') {
        const ls = await page.$$eval('[aria-label]', els => els.map(e => e.getAttribute('aria-label')).filter(Boolean));
        logs.push('[labels] ' + [...new Set(ls)].join(' | ').slice(0, 600));
      } else if (kind === 'wait') await page.waitForTimeout(Number(arg));
      else if (kind === 'shot') { await page.screenshot({ path: `${out}/${arg}.png` }); logs.push(`[shot] ${arg}`); }
      else if (kind === 'full') { await page.screenshot({ path: `${out}/${arg}.png`, fullPage: true }); logs.push(`[full] ${arg}`); }
      else if (kind === 'scroll') { await page.mouse.wheel(0, Number(arg)); await page.waitForTimeout(800); }
    } catch (e) { logs.push(`[fail] ${a}: ${String(e).split('\n')[0].slice(0,200)}`); }
  }
  logs.push('[body] ' + (await page.innerText('body').catch(()=>'')).replace(/\s+/g,' ').slice(0,700));
  console.log(logs.join('\n'));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
