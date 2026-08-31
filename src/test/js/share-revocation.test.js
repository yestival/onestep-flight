'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const app = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/app.js'), 'utf8');

function sourceBetween(start, end) {
  const startIndex = app.indexOf(start);
  const endIndex = app.indexOf(end, startIndex + start.length);
  assert.ok(startIndex >= 0, `Missing app.js marker: ${start}`);
  assert.ok(endIndex > startIndex, `Missing app.js marker: ${end}`);
  return app.slice(startIndex, endIndex);
}

function memoryStorage() {
  const values = new Map();
  return {
    getItem(key) { return values.has(key) ? values.get(key) : null; },
    setItem(key, value) { values.set(key, String(value)); },
    removeItem(key) { values.delete(key); },
    value(key) { return values.has(key) ? values.get(key) : null; }
  };
}

function revocationSandbox(options = {}) {
  const localStorage = memoryStorage();
  const requests = [];
  const sandbox = {
    localStorage,
    navigator: { onLine: options.online !== false },
    activeShareRevocationPendingStorageKey() { return 'pending-revocations'; },
    readStoredJson(key) {
      const value = localStorage.getItem(key);
      return value ? JSON.parse(value) : null;
    },
    fetch(url, init) {
      requests.push({ url, method: init.method, updateToken: init.headers['X-Share-Update-Token'] });
      if (options.reject) return Promise.reject(new Error('temporary network failure'));
      const status = options.status == null ? 204 : options.status;
      return Promise.resolve({ ok: status >= 200 && status < 300, status });
    },
    encodeURIComponent,
    Promise
  };
  vm.createContext(sandbox);
  vm.runInContext(
    sourceBetween('  function pendingShareRevocations(storageKey)', '  function resetFamilyShareSession()'),
    sandbox
  );
  return { sandbox, localStorage, requests };
}

test('offline discard retains the exact minimal revocation token pair and reconnect removes it after DELETE', async () => {
  const { sandbox, localStorage, requests } = revocationSandbox({ online: false });
  const session = { viewToken: 'view-token-fixture', updateToken: 'update-token-fixture',
    expiresAt: '2099-01-01T00:00:00Z', lastFingerprint: '{}' };

  assert.equal(await sandbox.revokeDiscardedShareSession(session), false);
  assert.deepEqual(JSON.parse(localStorage.value('pending-revocations')), [{
    demoSessionId: null, viewToken: 'view-token-fixture', updateToken: 'update-token-fixture'
  }]);
  assert.equal(requests.length, 0);

  sandbox.navigator.onLine = true;
  assert.deepEqual(Array.from(await sandbox.retryPendingShareRevocations()), [true]);
  assert.deepEqual(requests, [{
    url: '/api/share-sessions/view-token-fixture', method: 'DELETE', updateToken: 'update-token-fixture'
  }]);
  assert.equal(localStorage.value('pending-revocations'), null);
});

for (const terminalStatus of [404, 410]) {
  test(`DELETE ${terminalStatus} completes a pending share revocation`, async () => {
    const { sandbox, localStorage } = revocationSandbox({ status: terminalStatus });
    assert.equal(await sandbox.revokeDiscardedShareSession({
      viewToken: `view-${terminalStatus}`, updateToken: `update-${terminalStatus}`
    }), true);
    assert.equal(localStorage.value('pending-revocations'), null);
  });
}

test('temporary DELETE failure retains the only update token for a later retry', async () => {
  const { sandbox, localStorage } = revocationSandbox({ status: 503 });
  assert.equal(await sandbox.revokeDiscardedShareSession({
    viewToken: 'view-retry', updateToken: 'update-retry'
  }), false);
  assert.deepEqual(JSON.parse(localStorage.value('pending-revocations')), [{
    demoSessionId: null, viewToken: 'view-retry', updateToken: 'update-retry'
  }]);
});

test('reset queues revocation before discarding the active local share session', () => {
  const active = { viewToken: 'view-reset', updateToken: 'update-reset' };
  const calls = [];
  const sandbox = {
    shareCreationGeneration: 0,
    shareCreationInFlight: true,
    shareCreationPromise: {},
    shareCreationJourneyKey: 'journey',
    shareSyncTimer: 1,
    shareSession: active,
    window: { clearTimeout() {} },
    revokeDiscardedShareSession(session) {
      calls.push({ session, activeAtCall: sandbox.shareSession });
      return Promise.resolve(true);
    },
    localStorage: memoryStorage(),
    activeShareSessionStorageKey() { return 'active-share'; },
    activeSharePendingStorageKey() { return 'pending-update'; },
    publishDemoRecordingState() {}
  };
  vm.createContext(sandbox);
  vm.runInContext(
    sourceBetween('  function resetFamilyShareSession()', '  function revokeDemoRecordingShare()'),
    sandbox
  );

  sandbox.resetFamilyShareSession();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].session, active);
  assert.equal(calls[0].activeAtCall, active);
  assert.equal(sandbox.shareSession, null);
});
