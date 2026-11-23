// Run after the happy path, with the demo home visible.
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
const adb = join(process.env.ANDROID_HOME || join(process.env.LOCALAPPDATA, 'Android', 'Sdk'), 'platform-tools', 'adb.exe');
const call = (...args) => execFileSync(adb, ['-s', process.env.ANDROID_SERIAL || 'emulator-5554', ...args], { encoding: 'utf8' });
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const out = join(process.cwd(), 'artifacts'); mkdirSync(out, { recursive: true });
function nodes() {
  call('shell', 'uiautomator', 'dump', '/sdcard/neos-ui.xml');
  return [...call('shell', 'cat', '/sdcard/neos-ui.xml').matchAll(/<node\s+([^>]+)>?/g)].map(m => Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(a => [a[1], a[2]])));
}
async function node(label) {
  for (let i = 0; i < 6; i++) { const n = nodes().find(n => n.text === label || n['content-desc'] === label); if (n) return n; await sleep(500); }
  throw new Error(`Missing: ${label}`);
}
async function tap(label, hold = false) {
  const n = await node(label); const b = n.bounds.match(/\d+/g).map(Number);
  const x = String(Math.round((b[0] + b[2]) / 2)); const y = String(Math.round((b[1] + b[3]) / 2));
  if (hold) call('shell', 'input', 'swipe', x, y, x, y, '2100'); else call('shell', 'input', 'tap', x, y);
  await sleep(500);
}
function shot(name) { call('shell', 'screencap', '-p', '/sdcard/neos-screen.png'); call('pull', '/sdcard/neos-screen.png', join(out, `${name}.png`)); }
async function scenario(label) { await tap('NEOS', true); await tap(label); }
await node('Wallet balance');
await scenario('Pending review'); await tap('Check for an update'); await node('Review in progress'); shot('10-pending');
await scenario('Fail next request'); await tap('Check for an update'); await node('We couldn’t connect. Check your connection and try again.'); shot('11-network-error');
await tap('Check for an update'); await node('Review in progress');
await scenario('Rejected'); await tap('Check for an update'); await node('Verification unsuccessful'); shot('12-rejected');
await scenario('More information'); await tap('Check for an update'); await node('More information needed'); shot('13-more-info');
await tap('Try again'); await node('Capture document'); call('shell', 'input', 'keyevent', '4');
await node('Start verification');
// Return to a status page by completing sample capture again.
await tap('Start verification'); await tap('Capture document'); await sleep(1000); await tap('Continue'); await tap('Take selfie'); await sleep(1000); await tap('Submit for verification'); await node('More information needed');
await scenario('Expire session'); await node('Your session has expired. Please sign in again.'); shot('14-session-expired');
await scenario('Reset demo'); await node('Let’s begin');
writeFileSync(join(out, 'scenarios-result.txt'), 'PASS: pending, one-shot network failure/retry, rejected, more information/recapture, hardware back, session expiry, hidden reset.\n');
console.log('PASS: NEOS Android non-happy-path scenarios; demo reset to welcome.');
