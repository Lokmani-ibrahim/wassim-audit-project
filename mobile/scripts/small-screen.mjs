// Run after smoke.mjs in demo mode. Temporarily tests a 360 x 640 dp display.
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { writeFileSync } from 'node:fs';
const adb = join(process.env.ANDROID_HOME || join(process.env.LOCALAPPDATA, 'Android', 'Sdk'), 'platform-tools', 'adb.exe');
const call = (...args) => {
  const device = ['-s', process.env.ANDROID_SERIAL || 'emulator-5554'];
  try { return execFileSync(adb, [...device, ...args], { encoding: 'utf8', timeout: 15000 }); }
  catch (error) {
    if (!String(error).includes('offline')) throw error;
    execFileSync(adb, [...device, 'wait-for-device'], { timeout: 15000 });
    return execFileSync(adb, [...device, ...args], { encoding: 'utf8', timeout: 15000 });
  }
};
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function nodes() {
  call('shell', 'uiautomator', 'dump', '/sdcard/neos-ui.xml');
  return [...call('shell', 'cat', '/sdcard/neos-ui.xml').matchAll(/<node\s+([^>]+)>?/g)].map(m => Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(a => [a[1], a[2]])));
}
function tapNode(n) { if (!n) throw new Error('Control missing'); const b = n.bounds.match(/\d+/g).map(Number); call('shell', 'input', 'tap', String((b[0] + b[2]) / 2), String((b[1] + b[3]) / 2)); }
async function tap(label) { tapNode(nodes().find(n => n.text === label || n['content-desc'] === label)); await sleep(700); }
function shot(name) { call('shell', 'screencap', '-p', '/sdcard/neos-screen.png'); call('pull', '/sdcard/neos-screen.png', join(process.cwd(), 'artifacts', name + '.png')); }
const oldSize = call('shell', 'wm', 'size').match(/Override size: (\S+)/)?.[1];
const oldDensity = call('shell', 'wm', 'density').match(/Override density: (\d+)/)?.[1];
try {
  if (nodes().some(n => n.text === 'Wallet balance')) { await tap('Profile'); await tap('Sign out'); }
  if (!nodes().some(n => n.text === 'Preview experience')) throw new Error('Only run this check in demo mode');
  call('shell', 'wm', 'size', '720x1280'); call('shell', 'wm', 'density', '320'); await sleep(1500);
  shot('15-small-welcome'); await tap('Already have an account? Sign in');
  for (const [label, value] of [['Email address', 'small@example.com'], ['Password', 'sample-pass']]) {
    tapNode(nodes().find(n => n.class === 'android.widget.EditText' && n['content-desc'] === label));
    call('shell', 'input', 'text', value); call('shell', 'input', 'keyevent', '4');
  }
  await tap('Sign in'); await tap('Let’s begin');
  call('shell', 'input', 'swipe', '360', '850', '360', '350', '350');
  tapNode(nodes().find(n => n.class === 'android.widget.EditText' && n.enabled === 'true'));
  call('shell', 'input', 'text', '123'); await sleep(1200);
  const error = nodes().find(n => n.text === 'Enter a valid phone number, including your country code.');
  if (!error || error.bounds === '[0,0][0,0]') throw new Error('Validation message is not visible');
  shot('16-small-keyboard');
  writeFileSync(join(process.cwd(), 'artifacts', 'small-ui.json'), JSON.stringify(nodes(), null, 2));
  call('shell', 'input', 'keyevent', '4');
  console.log('PASS: small-screen welcome, input focus, keyboard scrolling and inline validation.');
} finally {
  call('shell', 'wm', 'size', oldSize || 'reset');
  call('shell', 'wm', 'density', oldDensity || 'reset');
}
