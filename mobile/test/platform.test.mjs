import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { buildConfig } from '../scripts/config.mjs';
const require = createRequire(import.meta.url);
const createPlatform = require('../../src/main/resources/static/platform.js');
const createLiveActivity = require('../../src/main/resources/static/live-activity.js');
function fixture(extra = {}) {
  const values = new Map();
  const disk = new Map();
  const events = {};
  const calls = [];
  const host = { navigator: { onLine: true }, location: { origin: 'https://web.test' }, btoa,
    localStorage: { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) },
    fetch: async (...args) => { calls.push(args); return new Response('{}'); }, ...extra };
  const native = { isNative: true,
    Preferences: { get: async ({ key }) => ({ value: disk.get(key) ?? null }), set: async ({ key, value }) => disk.set(key, value), remove: async ({ key }) => disk.delete(key) },
    App: { addListener: async (key, fn) => { events[key] = fn; }, getState: async () => ({ isActive: true }), getLaunchUrl: async () => null },
    Network: { addListener: async (key, fn) => { events[key] = fn; }, getStatus: async () => ({ connected: true }) },
    Filesystem: { readdir: async () => ({ files: [] }), writeFile: async (data) => { calls.push(data); return { uri: 'file:///cache/card.png' }; }, deleteFile: async (data) => calls.push(data) },
    Share: { share: async data => calls.push(data) }
  };
  const platform = createPlatform(host, native, { API_BASE_URL: 'https://api.test', PUBLIC_WEB_BASE_URL: 'https://public.test' });
  return { platform, host, native, disk, values, events, calls };
}
test('production origins are separate and reject missing, loopback, HTTP and placeholders', () => {
  assert.equal(buildConfig({ API_BASE_URL: 'https://api.onestepflight.com', PUBLIC_WEB_BASE_URL: 'https://onestepflight.com' }).API_BASE_URL, 'https://api.onestepflight.com');
  for (const value of ['', 'http://api.test', 'https://localhost', 'https://127.0.0.1', 'https://example.com', 'https://your-api.test', 'https://api.test/path']) {
    assert.throws(() => buildConfig({ API_BASE_URL: value }));
  }
});
test('web stays same-origin; every API verb including multipart uses native API origin', async () => {
  const { platform, host, calls } = fixture();
  assert.equal(createPlatform(host, null, {}).apiUrl('/api/journeys/next-action'), '/api/journeys/next-action');
  for (const method of ['GET', 'POST', 'PUT', 'DELETE']) await platform.apiFetch('/api/share-sessions/token', { method });
  const body = new FormData(); body.set('file', new Blob(['sample'], { type: 'application/pdf' }), 'sample.pdf');
  await platform.apiFetch('/api/documents/travel-document', { method: 'POST', body });
  assert.ok(calls.every(([url]) => url.startsWith('https://api.test/api/')));
  assert.equal(calls.at(-1)[1].body, body);
  assert.equal(calls.at(-1)[1].headers['Content-Type'], undefined);
  assert.throws(() => platform.apiUrl('/styles.css'));
});
test('relative and unsafe server share URLs resolve only to the canonical public link', () => {
  const { platform } = fixture();
  for (const supplied of ['/share/view_token', 'capacitor://localhost/share/view_token', 'https://evil.test/share/view_token?updateToken=secret', 'https://[malformed']) {
    assert.equal(platform.publicShareUrl('view_token', supplied), 'https://public.test/share/view_token');
  }
});
test('a browser opened offline follows later network changes without native events', async () => {
  const { host } = fixture({ navigator: { onLine: false } });
  const web = createPlatform(host, null, {});
  await web.init();
  assert.equal(web.isOnline(), false);
  host.navigator.onLine = true;
  await web.apiFetch('/api/journeys/next-action');
  assert.equal(web.isOnline(), true);
  host.navigator.onLine = false;
  assert.equal(web.isOnline(), false);
});
test('hydration is awaited, migrates private keys separately, and serializes set/delete without losing revocations', async () => {
  const { platform, native, values, disk } = fixture();
  let release;
  const wait = new Promise(resolve => { release = resolve; });
  const get = native.Preferences.get;
  native.Preferences.get = async data => { await wait; return get(data); };
  const key = 'onestep-flight-offline-journey-v1';
  const revoke = 'onestep-flight-share-revocation-pending-v1';
  values.set(key, '{"version":1}'); values.set(revoke, '[{"updateToken":"private"}]');
  let initialized = false;
  const loading = platform.init().then(() => { initialized = true; });
  await Promise.resolve(); assert.equal(initialized, false);
  release(); await loading;
  assert.equal(platform.storage.getItem(key), '{"version":1}');
  assert.equal(values.has(key), false);
  platform.storage.setItem(key, '{"version":2}'); platform.storage.removeItem(key);
  await platform.flush();
  assert.equal(disk.has(key), false); assert.equal(disk.has(revoke), true);
});
test('read failure blocks initialization; failed write keeps the previous durable snapshot', async () => {
  const { platform, native, disk } = fixture();
  const key = 'onestep-flight-offline-journey-v1'; disk.set(key, 'old');
  await platform.init();
  native.Preferences.set = async () => { throw new Error('disk full'); };
  platform.storage.setItem(key, 'new'); await platform.flush();
  assert.equal(disk.get(key), 'old'); assert.equal(platform.storageHealthy(), false);
  const other = fixture(); other.native.Preferences.get = async () => { throw new Error('locked'); };
  await assert.rejects(other.platform.init());
});
test('network API failure is offline even with a connected radio; abort is preserved', async () => {
  const { platform, host } = fixture({ fetch: async () => { throw new TypeError('unreachable'); } });
  await assert.rejects(platform.apiFetch('/api/journeys/next-action'));
  assert.equal(platform.isOnline(), false);
  host.fetch = async () => new Response('{}'); await platform.apiFetch('/api/journeys/next-action');
  assert.equal(platform.isOnline(), true);
  host.fetch = async (_, settings) => { assert.equal(settings.signal.aborted, true); throw new DOMException('aborted', 'AbortError'); };
  const control = new AbortController(); control.abort();
  await assert.rejects(platform.apiFetch('/api/test', { signal: control.signal }), { name: 'AbortError' });
  assert.equal(platform.isOnline(), true);
});
test('failed revocation journal write cannot erase the last durable update credential', async () => {
  const { platform, native, disk } = fixture();
  const sessionKey = 'onestep-flight-share-session-v1';
  const session = JSON.stringify({viewToken:'view',updateToken:'private'});
  disk.set(sessionKey, session);
  await platform.init();
  native.Preferences.set = async () => { throw new Error('disk full'); };
  platform.storage.setItem('onestep-flight-share-revocation-pending-v1', '[' + session + ']');
  platform.storage.removeItem(sessionKey);
  await platform.flush();
  assert.equal(disk.get(sessionKey), session);
  assert.equal(platform.storage.getItem(sessionKey), null);
});
test('PNG share uses a local file, cancellation is normal, and temporary files are deleted', async () => {
  const { platform, native, calls } = fixture();
  native.Share.share = async data => { calls.push(data); throw new Error('Share canceled'); };
  assert.deepEqual(await platform.sharePng(new Blob(['png'])), { cancelled: true });
  assert.equal(calls[0].directory, 'CACHE'); assert.deepEqual(calls[1].files, ['file:///cache/card.png']);
  assert.equal(calls.at(-1).path, calls[0].path);
  for (const url of ['javascript:alert(1)', 'file:///etc/passwd', 'http://example.test', 'https://user:pass@airport.test']) assert.equal(platform.externalUrl(url), null);
  assert.equal(platform.externalUrl('tel:+44123456789'), 'tel:+44123456789');
});
test('live activity never recreates dismissed activities, ends on segment change, rejects expired start', async () => {
  let current = { active: false, enabled: true, supported: true };
  const calls = [];
  const live = createLiveActivity({
    status: async () => current,
    start: async data => { calls.push('start'); return current = { active: true, journeyKey: data.journeyKey }; },
    update: async () => { calls.push('update'); return current; },
    end: async () => { calls.push('end'); current = { active: false }; }
  });
  const data = { journeyKey: 'flight-one', expiresAt: Date.now() + 10000 };
  await live.update(data); assert.deepEqual(calls, []);
  await live.start(data); await live.update(data); await live.update({ ...data, journeyKey: 'flight-two' });
  assert.deepEqual(calls, ['start', 'update', 'end']);
  await live.update(data); assert.equal(calls.length, 3);
  await assert.rejects(live.start({ ...data, expiresAt: 1 }));
});
