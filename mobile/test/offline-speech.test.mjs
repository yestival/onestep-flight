import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { runSync } from '../scripts/process.mjs';

test('bundled offline voices and their complete component source match the pinned release', () => {
  const directory = new URL('../android/offline-speech/', import.meta.url);
  const lock = JSON.parse(readFileSync(new URL('speech.lock.json', directory), 'utf8'));
  const hash = name => createHash('sha256').update(readFileSync(new URL(name, directory))).digest('hex');
  assert.equal(hash('espeak-ng-' + lock.version + '-source.tar.gz'), lock.sourceSha256);
  assert.equal(hash('espeak-ng-data.zip'), lock.dataSha256);
  const result = runSync(process.platform === 'win32' ? 'tar.exe' : 'tar', ['-tf', fileURLToPath(new URL('espeak-ng-data.zip', directory))], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  const files = result.stdout.split(/\r?\n/);
  for (const required of ['cmn_dict', 'en_dict', 'es_dict', 'phondata', 'phonindex', 'phontab']) {
    assert.ok(files.includes('espeak-ng-data/' + required), 'Missing offline voice resource: ' + required);
  }
  assert.deepEqual(lock.abis, ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']);
  assert.equal(lock.androidApi, 24);
});
