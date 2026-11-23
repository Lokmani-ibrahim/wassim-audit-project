// Emulator UI smoke test. Run with a fresh demo on the welcome screen and Metro running.
// Only interacts with com.neos.mobile; screenshots contain synthetic demo data.
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
const adb = join(process.env.ANDROID_HOME || join(process.env.LOCALAPPDATA, 'Android', 'Sdk'), 'platform-tools', 'adb.exe');
const serial = process.env.ANDROID_SERIAL || 'emulator-5554';
const out = join(process.cwd(), 'artifacts');
mkdirSync(out, { recursive: true });
const call = (...args) => execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8' });
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function nodes() {
  call('shell', 'uiautomator', 'dump', '/sdcard/neos-ui.xml');
  const xml = call('shell', 'cat', '/sdcard/neos-ui.xml');
  return [...xml.matchAll(/<node\s+([^>]+)>?/g)].map(match => Object.fromEntries([...match[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(m => [m[1], m[2]])));
}
function find(label) {
  return nodes().find(n => n.text === label || n['content-desc'] === label);
}
function tapNode(node) {
  const bounds = node.bounds.match(/\d+/g).map(Number);
  call('shell', 'input', 'tap', String(Math.round((bounds[0] + bounds[2]) / 2)), String(Math.round((bounds[1] + bounds[3]) / 2)));
}
async function tap(label) {
  for (let attempt = 0; attempt < 6; attempt++) {
    const node = find(label);
    if (node) { tapNode(node); await sleep(600); return; }
    await sleep(500);
  }
  throw new Error(`Missing UI control: ${label}`);
}
async function expect(label) {
  for (let attempt = 0; attempt < 6; attempt++) {
    if (find(label)) return;
    await sleep(500);
  }
  throw new Error(`Missing visible text: ${label}`);
}
function screenshot(name) {
  call('shell', 'screencap', '-p', '/sdcard/neos-screen.png');
  call('pull', '/sdcard/neos-screen.png', join(out, `${name}.png`));
}
async function fill(label, value) {
  const field = nodes().find(n => n.class === 'android.widget.EditText' && n['content-desc'] === label);
  if (!field) throw new Error(`Missing input: ${label}`);
  tapNode(field); call('shell', 'input', 'text', value);
  dismissKeyboard(); await sleep(300);
}
function dismissKeyboard() {
  // Fields use returnKeyType="done". Back can also trigger app navigation on this emulator.
  call('shell', 'input', 'keyevent', '66');
}
await expect('Let’s begin'); screenshot('01-welcome');
await tap('Already have an account? Sign in'); await expect('Welcome back.');
await tap('Forgot password?'); await expect('Signing in to the preview'); await tap('Got it');
screenshot('auth-sign-in'); await tap('Go back');
await tap('Let’s begin'); await expect('Create your NEOS account');
await fill('Email address', 'abir@example.com');
await fill('Password', 'sample-pass'); await fill('Confirm password', 'sample-pass');
screenshot('auth-create-account'); await tap('Create account');
await expect('abir@example.com'); screenshot('02-details');
const input = nodes().find(n => n.class === 'android.widget.EditText' && n.enabled === 'true');
if (!input) throw new Error('Phone input missing');
tapNode(input); call('shell', 'input', 'text', '123'); await sleep(300);
await expect('Enter a valid phone number, including your country code.'); screenshot('03-validation-keyboard');
call('shell', 'input', 'keyevent', '67'); call('shell', 'input', 'keyevent', '67'); call('shell', 'input', 'keyevent', '67');
call('shell', 'input', 'text', '+23051234567'); dismissKeyboard();
await tap('Continue'); await tap('Edit details'); await expect('+23051234567');
await tap('Continue'); await expect('abir@example.com'); screenshot('04-review');
const checkbox = nodes().find(n => n.checkable === 'true');
if (!checkbox) throw new Error('Terms checkbox missing');
tapNode(checkbox); await tap('Confirm and continue'); await tap('Start verification'); screenshot('05-document');
await tap('Capture document'); await sleep(1000); await tap('Continue'); screenshot('06-selfie');
await tap('Take selfie'); await sleep(1000); await tap('Submit for verification'); await sleep(2200);
await expect('You’re verified.'); screenshot('07-verified');
await tap('Continue'); screenshot('08-ready'); await tap('Go to home'); screenshot('09-home');
await tap('Send'); await expect('This feature is coming in the next release. We’ll let you know when it’s ready.'); await tap('Got it');
call('shell', 'am', 'force-stop', 'com.neos.mobile');
call('shell', 'am', 'start', '-n', 'com.neos.mobile/.MainActivity'); await sleep(2500);
await expect('Wallet balance');
await tap('Profile'); await expect('abir@example.com'); screenshot('auth-profile-email');
await tap('Sign out'); await tap('Already have an account? Sign in');
await fill('Email address', 'abir@example.com'); await fill('Password', 'another-sample');
await tap('Sign in'); await expect('Wallet balance');
await tap('Profile'); await expect('abir@example.com'); await tap('Go back');
writeFileSync(join(out, 'smoke-result.txt'), 'PASS: happy path, phone validation, keyboard, edit/back persistence, terms, simulated captures, approval, activation, home, upcoming action, restart restoration.\n');
console.log('PASS: NEOS Android happy-path smoke test');
