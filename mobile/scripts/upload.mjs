import { readdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mobileRoot } from './config.mjs';
if (process.env.UPLOAD_TO_TESTFLIGHT !== 'true') throw new Error('Upload disabled. Explicitly set UPLOAD_TO_TESTFLIGHT=true for this manual cloud build.');
for (const key of ['APP_STORE_CONNECT_ISSUER_ID', 'APP_STORE_CONNECT_KEY_IDENTIFIER', 'APP_STORE_CONNECT_PRIVATE_KEY']) {
  if (!process.env[key]) throw new Error(`Set ${key} as a protected Codemagic environment variable; never commit credentials.`);
}
const dir = new URL('build/ipa/', mobileRoot);
const files = readdirSync(dir).filter(name => name.endsWith('.ipa'));
if (files.length !== 1) throw new Error('Expected exactly one exported IPA');
// Upload only: no --testflight (external beta review), --app-store, beta groups, or release flags.
const result = spawnSync('app-store-connect', ['publish', '--path', fileURLToPath(new URL(files[0], dir)),
  '--issuer-id', '@env:APP_STORE_CONNECT_ISSUER_ID', '--key-id', '@env:APP_STORE_CONNECT_KEY_IDENTIFIER',
  '--private-key', '@env:APP_STORE_CONNECT_PRIVATE_KEY'], { stdio: 'inherit' });
if (result.error) throw result.error;
process.exit(result.status ?? 1);
