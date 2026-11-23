// Disposable Formance setup only. Never point this at an existing ledger.
import { readFile } from 'node:fs/promises';

const base = process.env.PACK_C_DISPOSABLE_LEDGER_URL;
if (base !== 'http://127.0.0.1:18080') throw new Error('Expected the Package C loopback gateway');
const schema = JSON.parse(await readFile(new URL('../../infra/formance/schemas/neos-v1.1.0.json', import.meta.url), 'utf8'));
const create = await fetch(`${base}/api/ledger/v2/neos`, { method: 'POST',
  headers: { 'Content-Type': 'application/json' }, body: '{}' });
console.log(`disposable ledger create: ${create.status}`);
if (![200, 201, 204].includes(create.status)) process.exit(1);
const install = await fetch(`${base}/api/ledger/v2/neos/schemas/v1.1.0`, { method: 'POST',
  headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(schema) });
console.log(`disposable schema install: ${install.status}`);
if (![200, 201, 204].includes(install.status)) process.exit(1);
