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
test('hanging push initialization cannot block native hydration or local activity', async () => {
  const {native,host}=fixture();
  native.platform='android';
  native.FlightPush={addListener:async()=>{},initialize:()=>new Promise(()=>{})};
  const platform=createPlatform(host,native,{});
  await platform.init();
  assert.equal(platform.nativePlatform,'android');
  assert.equal(platform.storageHealthy(),true);
});
test('Android share retains content until a later cache sweep', async () => {
  const {native,host,calls}=fixture();native.platform='android';
  const platform=createPlatform(host,native,{});
  await platform.sharePng(new Blob(['png']));
  assert.equal(calls.filter(c=>c.path && !c.data).length,0);
  assert.deepEqual(calls[1].files,['file:///cache/card.png']);
});
test('empty push acknowledgements and older snapshots retain recovered native facts', async () => {
  const {native,host}=fixture();
  const latest={journeyKey:'flight',displayState:{snapshotVersion:20,clientRevision:4,facts:{gate:'B40'}}};
  let result=latest;
  native.FlightPush={addListener:async()=>{},initialize:async()=>latest,status:async()=>result,syncJourney:async()=>({})};
  const platform=createPlatform(host,native,{});
  await platform.init();await new Promise(resolve=>setImmediate(resolve));
  await platform.push.syncJourney({journeyKey:'flight'});
  assert.equal(platform.push.current().displayState.facts.gate,'B40');
  result={journeyKey:'flight',displayState:{snapshotVersion:19,clientRevision:5,facts:{gate:'B12'}}};
  await platform.push.status();
  assert.equal(platform.push.current().displayState.facts.gate,'B40');
  result={journeyKey:''};await platform.push.status();
  assert.equal(platform.push.current().journeyKey,'');
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
test('live activity automatically starts once, respects dismissal, rebuilds normal ends and changes segments', async () => {
  let current = { active: false, enabled: true, supported: true };
  const calls = [];
  const live = createLiveActivity({
    status: async () => current,
    start: async data => { calls.push('start'); return current = { ...current, active: true, journeyKey: data.journeyKey }; },
    update: async () => { calls.push('update'); return current; },
    end: async () => { calls.push('end'); return current = { ...current, active: false }; }
  });
  const data = { journeyKey: 'flight-one', journeyEndsAt: Math.floor(Date.now()/1000) + 10000 };
  await live.sync(data); await live.sync(data);
  assert.deepEqual(calls, ['start','update']);
  current = { ...current, active: false, dismissedJourneyKey: 'flight-one' };
  await live.sync(data); assert.equal(calls.length, 2);
  await live.sync({ ...data, journeyKey: 'flight-two' });
  assert.equal(calls.at(-1), 'start');
  current.active = false; // Normal system end is independently observed by native state handling.
  await live.sync({ ...data, journeyKey: 'flight-two' });
  assert.equal(calls.filter(c=>c==='start').length,3);
  await live.sync({...data,journeyKey:'flight-three'}); assert.deepEqual(calls.slice(-2), ['end','start']);
  await live.sync(null); assert.equal(calls.at(-1),'start');
  await live.sync({ ...data, journeyEndsAt: 1 }); assert.equal(calls.at(-1),'start');
});

test('a rejected native bridge call exposes failure without poisoning subsequent automatic recovery', async () => {
  let current = { active: false, enabled: true, supported: true };
  let fail = true, starts = 0;
  const live = createLiveActivity({
    status: async () => current,
    start: async data => {
      if (fail) throw new Error('Native plugin unavailable');
      starts++;
      return current = { ...current, active: true, journeyKey: data.journeyKey };
    },
    update: async () => current
  });
  const data = {journeyKey:'BA117|2026-10-12|LHR|JFK',journeyEndsAt:Math.floor(Date.now()/1000)+86400};
  await assert.rejects(live.sync(data));
  assert.equal(live.current().errorCode,'LIVE_ACTIVITY_BRIDGE_FAILED');
  fail = false;
  await live.sync(data);
  await live.sync(data);
  assert.equal(starts,1);
  assert.equal(live.current().active,true);
  assert.equal(live.current().errorCode,undefined);
});

test('a stalled push sync cannot block local creation and late old cleanup stays scoped', async () => {
  const calls = [];
  let current = { supported: true, enabled: true, active: false };
  let release;
  const preparing = new Promise(resolve => { release = resolve; });
  const live = createLiveActivity({
    status: async () => current,
    start: async data => { calls.push('start'); return current = { ...current, active: true, journeyKey: data.journeyKey }; },
    update: async () => current,
    end: async options => {
      calls.push(['end', options.journeyKey]);
      if (current.journeyKey === options.journeyKey) current.active = false;
      return current;
    }
  });
  const data = { journeyKey: 'new-flight', journeyEndsAt: Math.floor(Date.now()/1000) + 86400 };
  const started = live.sync(data, async () => {
    calls.push('prepare');
    await preparing;
    if (current.journeyKey === 'old-flight') current.active = false;
    calls.push('prepared');
  });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(calls, ['prepare', 'start']);
  assert.equal(live.current().active, true);
  release();
  await started;
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(calls, ['prepare', 'start', 'prepared']);
  await live.end('old-flight');
  assert.equal(live.current().active, true);
  assert.deepEqual(calls.at(-1), ['end', 'old-flight']);
});

test('a native callback timeout is reported and does not hold every future sync', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let hang = true, starts = 0;
  let current = { supported: true, enabled: true, active: false };
  const live = createLiveActivity({
    status: () => hang ? new Promise(() => {}) : Promise.resolve(current),
    start: async data => { starts++; return current = { ...current, active: true, journeyKey: data.journeyKey }; },
    update: async () => current
  });
  const data = { journeyKey: 'flight', journeyEndsAt: Math.floor(Date.now()/1000) + 86400 };
  const attempt = assert.rejects(live.sync(data), { code: 'LIVE_ACTIVITY_BRIDGE_TIMEOUT' });
  await new Promise(resolve => setImmediate(resolve));
  t.mock.timers.tick(10001);
  await attempt;
  assert.equal(live.current().errorReference, 'LIVE_ACTIVITY_BRIDGE_TIMEOUT:status');
  hang = false;
  await live.sync(data);
  assert.equal(starts, 1);
  assert.equal(live.current().active, true);
});

test('a stalled push revocation cannot prevent local removal', async () => {
  let active = true;
  const live = createLiveActivity({
    end: async options => { assert.equal(options.journeyKey, 'flight'); active = false; return { active }; },
    status: async () => ({ active })
  });
  await live.end('flight', () => new Promise(() => {}));
  assert.equal(active, false);
});

test('Android persists a disabled or dismissed card locally without claiming it is visible', async () => {
  let current = { supported: true, enabled: false, active: false, localPersistenceSupported: true };
  const saved = [];
  const live = createLiveActivity({
    status: async () => current,
    start: async data => { saved.push(data); return current; }
  });
  const display = { journeyKey: 'disabled-flight', gate: 'B32', journeyEndsAt: Math.floor(Date.now()/1000)+600 };
  await live.sync(display);
  assert.equal(saved.length, 1);
  assert.equal(saved[0].gate, 'B32');
  assert.equal(live.current().active, false);
  current = { ...current, enabled: true, dismissedJourneyKey: display.journeyKey };
  await live.sync({ ...display, gate: 'B40' });
  assert.equal(saved[1].gate, 'B40');
  assert.equal(live.current().active, false);
});
