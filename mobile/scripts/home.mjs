// Run with the demo Home screen open and Metro running.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';

const adb = join(process.env.ANDROID_HOME || join(process.env.LOCALAPPDATA, 'Android', 'Sdk'), 'platform-tools', 'adb.exe');
const out = join(process.cwd(), 'artifacts');
mkdirSync(out, { recursive: true });
const call = (...args) => execFileSync(adb, ['-s', process.env.ANDROID_SERIAL || 'emulator-5554', ...args], { encoding: 'utf8', timeout: 20000 });
const pause = () => new Promise(resolve => setTimeout(resolve, 700));
function nodes() {
  call('shell', 'uiautomator', 'dump', '/sdcard/neos-ui.xml');
  return [...call('shell', 'cat', '/sdcard/neos-ui.xml').matchAll(/<node\s+([^>]+)>?/g)].map(m => Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(a => [a[1], a[2]])));
}
function find(label) {
  const node = nodes().find(n => n.text === label || n['content-desc'] === label);
  assert.ok(node, `Visible control: ${label}`);
  return node;
}
async function tap(label) {
  const bounds = find(label).bounds.match(/\d+/g).map(Number);
  call('shell', 'input', 'tap', String(Math.round((bounds[0] + bounds[2]) / 2)), String(Math.round((bounds[1] + bounds[3]) / 2)));
  await pause();
}
function shot(name) {
  call('shell', 'screencap', '-p', '/sdcard/neos-screen.png');
  call('pull', '/sdcard/neos-screen.png', join(out, `${name}.png`));
}

find('Wallet balance');
for (const label of ['MUR, Mauritian rupee, 0.00', 'USD, US dollar, 0.00', 'EUR, Euro, 0.00']) find(label);
await tap('USD, US dollar, 0.00');
find('Choose a wallet, USD');
assert.equal(find('USD, US dollar, 0.00').selected, 'true');
shot('home-usd');
await tap('Hide balances');
assert.ok(!nodes().some(n => (n.text + n['content-desc']).includes('0.00')), 'No visible or accessible balance after hiding');
await tap('Choose a wallet, USD');
find('USD, US dollar, Balance hidden');
assert.ok(!nodes().some(n => (n.text + n['content-desc']).includes('0.00')), 'Wallet menu respects hidden balances');
await tap('EUR, Euro, Balance hidden');
find('Choose a wallet, EUR');
await tap('Show balances');
find('EUR 0.00');
await tap('Send');
find('EUR · Euro');
await tap('Got it');
await tap('Choose a wallet, EUR');
shot('home-wallet-menu');
call('shell', 'input', 'keyevent', '4');
await pause();
find('Wallet balance');

const oldSize = call('shell', 'wm', 'size').match(/Override size: (\S+)/)?.[1];
const oldDensity = call('shell', 'wm', 'density').match(/Override density: (\d+)/)?.[1];
try {
  call('shell', 'wm', 'size', '720x1280');
  call('shell', 'wm', 'density', '320');
  await pause();
  shot('home-small-top');
  call('shell', 'input', 'swipe', '360', '970', '360', '520', '350');
  await pause();
  await tap('USD, US dollar, 0.00');
  shot('home-small');
  call('shell', 'input', 'swipe', '360', '450', '360', '1020', '350');
  await pause();
  find('Choose a wallet, USD');
  await tap('Choose a wallet, USD');
  await tap('EUR, Euro, 0.00');
  find('Choose a wallet, EUR');
  call('shell', 'input', 'swipe', '360', '960', '360', '500', '350');
  await pause();
  find('No activity yet.');
  shot('home-small-activity');
} finally {
  call('shell', 'wm', 'size', oldSize || 'reset');
  call('shell', 'wm', 'density', oldDensity || 'reset');
}
await pause();
call('shell', 'input', 'swipe', '540', '650', '540', '1800', '350');
await pause();
shot('home-final');
console.log('PASS: three wallets, card/menu selection, hidden balances, action currency context, Android back and 360 x 640 dp layout.');
