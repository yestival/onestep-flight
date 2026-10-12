import test from 'node:test';
import assert from 'node:assert/strict';
import { inspectApk, latestRelease, nextVersion, releaseState, signingBaseline, publishRelease } from '../scripts/android-downloads.mjs';
import { verifiedCandidate, sourceRepository, downloadsRepository } from '../scripts/android-download-request.mjs';
import { createHash } from 'node:crypto';

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

const sha = 'c'.repeat(40);
const run = { head_sha: sha, head_branch: 'main', head_repository: { full_name: sourceRepository },
  event: 'push', status: 'completed', conclusion: 'success', path: '.github/workflows/android.yml' };

test('Only passing main regressions can request downloads; stale, failed and foreign runs cannot publish', () => {
  assert.equal(verifiedCandidate(sha, sha, run), true);
  assert.equal(verifiedCandidate(sha, 'd'.repeat(40), run), false);
  for (const change of [{ conclusion: 'failure' }, { status: 'in_progress' }, { head_branch: 'feature' },
    { event: 'pull_request' }, { head_sha: 'd'.repeat(40) }, { head_repository: { full_name: 'attacker/fork' } },
    { path: '.github/workflows/unrelated.yml' }]) {
    assert.throws(() => verifiedCandidate(sha, sha, { ...run, ...change }), /successful main/);
  }
  assert.throws(() => verifiedCandidate('bad', sha, run), /Invalid/);
});

test('The first automatic release retains the manually uploaded APK signing identity', () => {
  const baseline = signingBaseline('Existing manually uploaded APK', certificate);
  assert.equal(baseline.certificateSha256, certificate);
  assert.equal(nextVersion('1', baseline), 2);
  assert.deepEqual(signingBaseline(`<!-- onestep-android-build ${JSON.stringify(previous)} -->`, certificate), previous);
  assert.throws(() => signingBaseline('', ''), /ANDROID_SIGNING_CERTIFICATE_SHA256/);
  assert.throws(() => signingBaseline(`<!-- onestep-android-build ${JSON.stringify(previous)} -->`, 'b'.repeat(64)), /signing identity/);
});

function publicationFixture({ uploadStatus = 201, digest, staleAfterUpload = false, staleInitially = false } = {}) {
  const apk = Buffer.from('signed-APK-test-data');
  const hash = createHash('sha256').update(apk).digest('hex');
  const asset = { id: 7, state: 'uploaded', size: apk.length, digest: digest ?? `sha256:${hash}` };
  const calls = [];
  let branches = 0;
  return { apk, calls, request: async (url, options) => {
    calls.push({ url, ...options });
    assert.equal(options.redirect, 'error');
    assert.ok(options.signal instanceof AbortSignal);
    if (url.endsWith('/branches/main')) return Response.json({ commit: { sha: staleInitially || (staleAfterUpload && branches++ > 0) ? 'd'.repeat(40) : sha } });
    if (url.includes('/actions/runs/')) return Response.json(run);
    if (url.includes('uploads.github.com')) return Response.json(asset, { status: uploadStatus });
    if (options.method === 'POST') return Response.json({ id: 6 });
    if (options.method === 'PATCH') return Response.json({ id: 6, draft: false });
    return Response.json({ id: 6, assets: [asset], html_url: 'https://github.com/owner/downloads/releases/tag/android-2-1' });
  } };
}
function publication(fixture) {
  return publishRelease({ repository: downloadsRepository, token: 'test-only', tag: 'android-2-1',
    body: 'release notes', apk: fixture.apk, sourceSha: sha, runId: '123' }, fixture.request);
}

test('Public latest changes only after a complete, hash-verified draft upload', async () => {
  const fixture = publicationFixture();
  assert.match(await publication(fixture), /android-2-1/);
  const create = fixture.calls.find(call => call.method === 'POST' && !call.url.includes('uploads.github.com'));
  assert.equal(JSON.parse(create.body).draft, true);
  const upload = fixture.calls.findIndex(call => call.url.includes('uploads.github.com'));
  const publish = fixture.calls.findIndex(call => call.method === 'PATCH');
  assert.ok(upload > 0 && publish > upload);
  assert.equal(JSON.parse(fixture.calls[publish].body).make_latest, 'true');
  assert.ok(fixture.calls.every(call => call.method !== 'DELETE'));
});

test('Failed or corrupt uploads leave the current public APK untouched', async () => {
  for (const options of [{ uploadStatus: 500 }, { digest: 'sha256:wrong' }]) {
    const fixture = publicationFixture(options);
    await assert.rejects(publication(fixture), /upload failed|hash or size/);
    assert.ok(fixture.calls.every(call => call.method !== 'PATCH' && call.method !== 'DELETE'));
  }
});

test('A main commit superseded before or during upload cannot replace the current APK', async () => {
  for (const options of [{ staleInitially: true }, { staleAfterUpload: true }]) {
    const fixture = publicationFixture(options);
    assert.equal(await publication(fixture), null);
    assert.ok(fixture.calls.every(call => call.method !== 'PATCH'));
  }
});
