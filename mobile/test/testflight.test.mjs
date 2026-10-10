import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, verify } from 'node:crypto';
import { appConfig } from '../scripts/config.mjs';
import { nextTestFlightBuildNumber } from '../scripts/version.mjs';
import { createAppleClient, prepareTestFlight, distributeInternal } from '../scripts/testflight.mjs';

const app = { id: '1234567890', attributes: { bundleId: appConfig.appId } };
const group = { id: 'internal-group', attributes: { name: 'OneStep Flight Internal', isInternalGroup: true, hasAccessToAllBuilds: false } };
const env = { PROJECT_BUILD_NUMBER: '4', APP_BUILD_NUMBER: '5.0.4' };
const ready = {
  data: [{ id: 'new-build', attributes: { version: env.APP_BUILD_NUMBER, processingState: 'VALID', expired: false },
    relationships: { preReleaseVersion: { data: { id: 'release' } } } }],
  included: [{ id: 'release', attributes: { version: appConfig.version, platform: 'IOS' } }]
};

function appleStub({ apps = [app], groups = [group], builds = [], uploaded = [ready] } = {}) {
  const calls = [];
  let poll = 0;
  return {
    calls,
    request: async (path, options = {}) => {
      const url = new URL(path, 'https://api.appstoreconnect.apple.com');
      calls.push({ url, ...options });
      if (options.method === 'POST') return null;
      if (url.pathname === '/v1/apps') return { data: apps };
      if (url.pathname.endsWith('/betaGroups')) return { data: groups };
      if (url.searchParams.has('filter[version]')) return uploaded[Math.min(poll++, uploaded.length - 1)];
      return { data: builds };
    }
  };
}
const noWait = { wait: async () => {}, log: () => {} };

test('TestFlight numbers exceed existing builds and stay distinct when concurrent counters see the same Apple list', () => {
  assert.equal(nextTestFlightBuildNumber('4', ['1', '4']), '5.0.4');
  assert.equal(nextTestFlightBuildNumber('4', ['40.12.9', '4']), '41.0.4');
  assert.equal(nextTestFlightBuildNumber('105', ['4']), '106.1.5');
  const first = nextTestFlightBuildNumber('4', ['40']);
  const second = nextTestFlightBuildNumber('5', ['40']);
  assert.notEqual(first, second);
  assert.equal(first, '41.0.4');
  assert.equal(second, '41.0.5');
  assert.equal(nextTestFlightBuildNumber('6', [second]), '42.0.6');
  for (const counter of [undefined, '', '-1', '1.5', 'oops', '10000', '9007199254740993']) {
    assert.throws(() => nextTestFlightBuildNumber(counter, ['4']));
  }
  for (const number of ['unknown', '0', '10000', '4.123', '9999']) {
    assert.throws(() => nextTestFlightBuildNumber('4', [number]));
  }
});

test('preparation resolves the exact app, reads expired builds across pages and makes no Apple mutations', async () => {
  const stub = appleStub({ apps: [app, { ...app, id: '999', attributes: { bundleId: appConfig.appId + '.FlightActivity' } }],
    builds: [{ attributes: { version: '4' } }] });
  const request = async (path, options) => {
    const result = await stub.request(path, options);
    if (path.includes('filter%5Bapp%5D')) result.links = { next: 'https://api.appstoreconnect.apple.com/v1/builds?page=2' };
    if (path.endsWith('page=2')) return { data: [{ attributes: { version: '20', expired: true } }] };
    return result;
  };
  assert.deepEqual(await prepareTestFlight(request, { ...env, APP_BUILD_NUMBER: '4' }), { appId: app.id, buildNumber: '21.0.4' });
  assert.equal(stub.calls.filter(call => call.method === 'POST').length, 0);
  const query = stub.calls.find(call => call.url.pathname === '/v1/builds').url.searchParams;
  assert.equal(query.get('filter[app]'), app.id);
  assert.equal(query.has('filter[expired]'), false);
});

test('preparation rejects missing/ambiguous apps, missing groups and external groups before archiving', async () => {
  for (const settings of [{ apps: [] }, { apps: [app, app] }, { groups: [] },
    { groups: [group, group] }, { groups: [{ ...group, attributes: { ...group.attributes, isInternalGroup: false } }] }]) {
    const stub = appleStub(settings);
    await assert.rejects(prepareTestFlight(stub.request, env));
    assert.equal(stub.calls.some(call => call.method === 'POST'), false);
  }
});

test('Apple automatic internal distribution needs no polling or group mutation', async () => {
  const stub = appleStub({ groups: [{ ...group, attributes: { ...group.attributes, hasAccessToAllBuilds: true } }] });
  await distributeInternal(stub.request, env, noWait);
  assert.equal(stub.calls.length, 2);
});

test('internal distribution waits for processing and adds only the exact iOS marketing version and build', async () => {
  const processing = structuredClone(ready);
  processing.data[0].attributes.processingState = 'PROCESSING';
  const unrelatedVersion = structuredClone(ready);
  unrelatedVersion.included[0].attributes.version = '0.9.0';
  const stub = appleStub({ uploaded: [unrelatedVersion, processing, ready] });
  let waits = 0;
  await distributeInternal(stub.request, env, { ...noWait, wait: async () => { waits++; } });
  assert.equal(waits, 2);
  const mutations = stub.calls.filter(call => call.method === 'POST');
  assert.equal(mutations.length, 1);
  assert.equal(mutations[0].url.pathname, '/v1/betaGroups/internal-group/relationships/builds');
  assert.deepEqual(mutations[0].body, { data: [{ type: 'builds', id: 'new-build' }] });
});

test('rejected, expired, ambiguous or unprocessed builds never reach internal or external testers', async () => {
  for (const change of [
    page => { page.data[0].attributes.processingState = 'INVALID'; },
    page => { page.data[0].attributes.processingState = 'FAILED'; },
    page => { page.data[0].attributes.expired = true; },
    page => { page.data.push(structuredClone(page.data[0])); },
    page => { page.data = []; }
  ]) {
    const page = structuredClone(ready);
    change(page);
    const stub = appleStub({ uploaded: [page] });
    await assert.rejects(distributeInternal(stub.request, env, { ...noWait, attempts: 2 }));
    assert.equal(stub.calls.some(call => call.method === 'POST'), false);
  }
});

test('group type is checked again before distribution so a renamed external group cannot receive the build', async () => {
  const stub = appleStub();
  let groupReads = 0;
  const request = async (path, options) => {
    const result = await stub.request(path, options);
    if (new URL(path, 'https://api.appstoreconnect.apple.com').pathname.endsWith('/betaGroups') && ++groupReads === 2) {
      return { data: [{ ...group, attributes: { ...group.attributes, isInternalGroup: false } }] };
    }
    return result;
  };
  await assert.rejects(distributeInternal(request, env, noWait), /INTERNAL/);
  assert.equal(stub.calls.some(call => call.method === 'POST'), false);
});

const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const credentials = { APP_STORE_CONNECT_ISSUER_ID: 'test-issuer', APP_STORE_CONNECT_KEY_IDENTIFIER: 'test-key',
  APP_STORE_CONNECT_PRIVATE_KEY: privateKey.export({ type: 'pkcs8', format: 'pem' }) };

test('Apple requests use signed short-lived ES256 JWTs without putting credentials in URLs', async () => {
  const request = createAppleClient(credentials, async (url, options) => {
    assert.equal(url.origin, 'https://api.appstoreconnect.apple.com');
    assert.equal(url.search, '');
    assert.equal(options.redirect, 'error');
    const [header, payload, signature] = options.headers.Authorization.slice(7).split('.');
    assert.deepEqual(JSON.parse(Buffer.from(header, 'base64url')), { alg: 'ES256', kid: 'test-key', typ: 'JWT' });
    const claims = JSON.parse(Buffer.from(payload, 'base64url'));
    assert.equal(claims.iss, 'test-issuer');
    assert.equal(claims.aud, 'appstoreconnect-v1');
    assert.ok(claims.exp - claims.iat <= 1200);
    assert.equal(verify('sha256', Buffer.from(`${header}.${payload}`), { key: publicKey, dsaEncoding: 'ieee-p1363' }, Buffer.from(signature, 'base64url')), true);
    return new Response('{"data":[]}', { status: 200 });
  });
  assert.deepEqual(await request('/v1/apps'), { data: [] });
  for (const url of ['https://example.com/v1/apps', 'http://api.appstoreconnect.apple.com/v1/apps', '/other']) {
    await assert.rejects(request(url), /non-Apple/);
  }
});

test('Apple API errors are redacted and transient failures retry without publishing', async () => {
  let count = 0;
  const request = createAppleClient(credentials, async () => {
    count++;
    return new Response(count === 1 ? 'private-response-must-not-be-logged' : '{"data":[]}', { status: count === 1 ? 503 : 200 });
  }, noWait.wait);
  await request('/v1/apps');
  assert.equal(count, 2);
  for (const status of [401, 403, 429, 500]) {
    const failing = createAppleClient(credentials, async () => new Response('private-response-must-not-be-logged', { status }), noWait.wait);
    await assert.rejects(failing('/v1/apps'), error => /HTTP/.test(error.message) && !error.message.includes('private-response'));
  }
  assert.throws(() => createAppleClient({}), /integration/);
  assert.throws(() => createAppleClient({ ...credentials, APP_STORE_CONNECT_PRIVATE_KEY: 'secret-invalid-value' }),
    error => /ES256/.test(error.message) && !error.message.includes('secret-invalid-value'));
});

test('Apple integration accepts Codemagic @file and @env credentials without copying keys into artifacts', async () => {
  for (const reference of ['@file:$HOME/private_keys/key.p8', '@file:${HOME}/private_keys/key.p8',
    '@file:~/private_keys/key.p8', '@env:APPLE_KEY_CONTENTS']) {
    let reads = 0;
    let called = false;
    const request = createAppleClient({ ...credentials, HOME: '/ci-home', APP_STORE_CONNECT_PRIVATE_KEY: reference,
      APPLE_KEY_CONTENTS: credentials.APP_STORE_CONNECT_PRIVATE_KEY,
      APP_STORE_CONNECT_KEY_IDENTIFIER: '@env:APPLE_KEY_ID', APPLE_KEY_ID: 'test-key' },
    async (_url, options) => {
      called = true;
      const [header, payload, signature] = options.headers.Authorization.slice(7).split('.');
      assert.equal(JSON.parse(Buffer.from(header, 'base64url')).kid, 'test-key');
      assert.equal(verify('sha256', Buffer.from(`${header}.${payload}`), { key: publicKey, dsaEncoding: 'ieee-p1363' }, Buffer.from(signature, 'base64url')), true);
      return new Response('{"data":[]}', { status: 200 });
    }, noWait.wait, path => {
      reads++;
      assert.equal(path, '/ci-home/private_keys/key.p8');
      return credentials.APP_STORE_CONNECT_PRIVATE_KEY;
    });
    await request('/v1/apps');
    assert.equal(called, true);
    assert.equal(reads, reference.startsWith('@file:') ? 1 : 0);
  }
});

test('missing, cyclic and unreadable key references fail with redacted errors before network access', () => {
  for (const reference of ['@file:/private/hidden-key.p8', '@env:UNDEFINED', '@env:APP_STORE_CONNECT_PRIVATE_KEY']) {
    assert.throws(() => createAppleClient({ ...credentials, APP_STORE_CONNECT_PRIVATE_KEY: reference },
      () => { assert.fail('Must not contact Apple with unresolved credentials'); }, noWait.wait,
      () => { throw new Error('private-path-must-not-be-logged'); }),
    error => /Cannot resolve APP_STORE_CONNECT_PRIVATE_KEY/.test(error.message)
      && !error.message.includes('hidden-key') && !error.message.includes('private-path'));
  }
});
