import test from 'node:test';
import assert from 'node:assert/strict';
import { inspectApk, latestRelease, nextVersion, releaseState } from '../scripts/android-downloads.mjs';

const certificate = 'a'.repeat(64);
const previous = { versionCode: 51, runNumber: 9, certificateSha256: certificate };

test('Android downloads increment the installed version and reject old workflow reruns', () => {
  assert.equal(nextVersion('1', null), 2);
  assert.equal(nextVersion('10', previous), 52);
  assert.equal(nextVersion('100', previous), 101);
  assert.equal(nextVersion('9', previous), 52);
  assert.throws(() => nextVersion('8', previous), /older workflow/);
  for (const value of ['0', '-1', 'NaN', '1.5', '2100000000']) assert.throws(() => nextVersion(value, null));
});

test('Manually created releases remain compatible while corrupt publication metadata fails closed', () => {
  assert.equal(releaseState('Manually uploaded Android APK'), null);
  assert.deepEqual(releaseState(`Notes\n<!-- onestep-android-build ${JSON.stringify(previous)} -->`), previous);
  assert.throws(() => releaseState('<!-- onestep-android-build {"versionCode":-1} -->'), /invalid/);
});

test('GitHub authentication and rate limits cannot be mistaken for an empty download repository', async () => {
  for (const status of [401, 403, 429, 500]) {
    await assert.rejects(latestRelease('owner/downloads', 'secret', async () => new Response(null, { status })), new RegExp(`HTTP ${status}`));
  }
  assert.equal(await latestRelease('owner/downloads', 'secret', async () => new Response(null, { status: 404 })), null);
  await assert.rejects(latestRelease('owner/downloads', '', () => assert.fail('Missing credentials must not make a request')), /ANDROID_DOWNLOADS_TOKEN/);
  await assert.rejects(latestRelease('../wrong', 'secret'), /Invalid/);
});

test('Release reads use only the expected GitHub endpoint and a bounded authenticated request', async () => {
  const result = await latestRelease('owner/downloads', 'secret', async (url, options) => {
    assert.equal(url, 'https://api.github.com/repos/owner/downloads/releases/latest');
    assert.equal(options.headers.Authorization, 'Bearer secret');
    assert.equal(options.redirect, 'error');
    assert.ok(options.signal instanceof AbortSignal);
    return Response.json({ body: 'current release' });
  });
  assert.deepEqual(result, { body: 'current release' });
});

function apkRunner({ signed = true, cert = certificate, version = 52, app = 'com.selene.onestepflight', debug = false } = {}) {
  return command => command === 'signer'
    ? { status: signed ? 0 : 1, stdout: `Signer #1 certificate SHA-256 digest: ${cert}` }
    : { status: 0, stdout: `package: name='${app}' versionCode='${version}' versionName='1.0.0'\n${debug ? 'application-debuggable\n' : ''}` };
}
const tools = { apksigner: 'signer', aapt: 'aapt' };
const state = { versionCode: 52, previous };

test('Public APK validation preserves the signing identity and rejects debug, unsigned and wrong application builds', () => {
  assert.equal(inspectApk('app.apk', state, tools, apkRunner()), certificate);
  assert.throws(() => inspectApk('app.apk', state, tools, apkRunner({ signed: false })), /no valid signing/);
  assert.throws(() => inspectApk('app.apk', state, tools, apkRunner({ cert: 'b'.repeat(64) })), /signing key changed/);
  assert.throws(() => inspectApk('app.apk', state, tools, apkRunner({ debug: true })), /release APK/);
  assert.throws(() => inspectApk('app.apk', state, tools, apkRunner({ version: 51 })), /package or version/);
  assert.throws(() => inspectApk('app.apk', state, tools, apkRunner({ app: 'another.app' })), /package or version/);
});
