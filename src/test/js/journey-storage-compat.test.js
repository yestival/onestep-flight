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

function memoryStorage(initial) {
  const values = new Map(Object.entries(initial || {}));
  const gets = [];
  const removals = [];
  return {
    getItem(key) {
      gets.push(key);
      return values.has(key) ? values.get(key) : null;
    },
    setItem(key, value) { values.set(key, String(value)); },
    removeItem(key) {
      removals.push(key);
      values.delete(key);
    },
    value(key) { return values.has(key) ? values.get(key) : null; },
    gets,
    removals
  };
}

function storageSandbox(overrides) {
  const sandbox = Object.assign({
    STORAGE_KEY: 'onestep-flight-journey-v2',
    OFFLINE_STORAGE_KEY: 'onestep-flight-offline-journey-v1',
    QA_STORAGE_KEY: 'onestep-flight-qa-journey-v1',
    QA_OFFLINE_STORAGE_KEY: 'onestep-flight-qa-offline-journey-v1',
    SHARE_SESSION_KEY: 'onestep-flight-share-session-v1',
    SHARE_PENDING_KEY: 'onestep-flight-share-pending-v1',
    QA_SHARE_SESSION_KEY: 'onestep-flight-qa-share-session-v1',
    QA_SHARE_PENDING_KEY: 'onestep-flight-qa-share-pending-v1',
    DEMO_JOURNEY_PREFIX: 'onestep-flight-demo-journey-v1:',
    DEMO_OFFLINE_PREFIX: 'onestep-flight-demo-offline-journey-v1:',
    DEMO_SHARE_PREFIX: 'onestep-flight-demo-share-session-v1:',
    DEMO_SHARE_PENDING_PREFIX: 'onestep-flight-demo-share-pending-v1:',
    demoRecordingSessionId: null,
    OFFLINE_MAX_AGE_MS: 48 * 60 * 60 * 1000,
    qaJourneyEnabled: false,
    demoFlightEnabled: false,
    demoScenariosEnabled: false,
    normalizeFlightNumber(value) {
      return String(value || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
    }
  }, overrides || {});
  vm.createContext(sandbox);
  vm.runInContext(
    sourceBetween('  function activeJourneyStorageKey()', '  function clearJourney()'),
    sandbox
  );
  return sandbox;
}

test('ordinary and QA journeys select completely separate storage keys', () => {
  const sandbox = storageSandbox();

  assert.equal(sandbox.activeJourneyStorageKey(), 'onestep-flight-journey-v2');
  assert.equal(sandbox.activeOfflineJourneyStorageKey(), 'onestep-flight-offline-journey-v1');
  sandbox.qaJourneyEnabled = true;
  assert.equal(sandbox.activeJourneyStorageKey(), 'onestep-flight-qa-journey-v1');
  assert.equal(sandbox.activeOfflineJourneyStorageKey(), 'onestep-flight-qa-offline-journey-v1');
});

test('ordinary, QA, and two recording sessions select completely separate journey and share keys', () => {
  const sandbox = storageSandbox();
  const first = 'b9500530-069d-4f82-8ed9-0dbfe6b65e9f';
  const second = '1c1fb0d2-fded-4e40-bcec-bc42dbe406bf';
  const ordinary = [sandbox.activeJourneyStorageKey(), sandbox.activeOfflineJourneyStorageKey(),
    sandbox.activeShareSessionStorageKey(), sandbox.activeSharePendingStorageKey()];

  sandbox.qaJourneyEnabled = true;
  const qa = [sandbox.activeJourneyStorageKey(), sandbox.activeOfflineJourneyStorageKey(),
    sandbox.activeShareSessionStorageKey(), sandbox.activeSharePendingStorageKey()];
  sandbox.demoRecordingSessionId = first;
  const demoOne = [sandbox.activeJourneyStorageKey(), sandbox.activeOfflineJourneyStorageKey(),
    sandbox.activeShareSessionStorageKey(), sandbox.activeSharePendingStorageKey()];
  sandbox.demoRecordingSessionId = second;
  const demoTwo = [sandbox.activeJourneyStorageKey(), sandbox.activeOfflineJourneyStorageKey(),
    sandbox.activeShareSessionStorageKey(), sandbox.activeSharePendingStorageKey()];

  const all = [...ordinary, ...qa, ...demoOne, ...demoTwo];
  assert.equal(new Set(all).size, all.length);
  assert.deepEqual(demoOne, [
    `onestep-flight-demo-journey-v1:${first}`,
    `onestep-flight-demo-offline-journey-v1:${first}`,
    `onestep-flight-demo-share-session-v1:${first}`,
    `onestep-flight-demo-share-pending-v1:${first}`
  ]);
  assert.equal(sandbox.savedJourneyModeMatches({ demoSessionId: first }), false);
  sandbox.demoRecordingSessionId = first;
  assert.equal(sandbox.savedJourneyModeMatches({ demoSessionId: first }), true);
  assert.equal(sandbox.savedJourneyModeMatches({ demoSessionId: second }), false);
  assert.equal(sandbox.savedJourneyModeMatches({ qaJourney: true }), false);
});

test('journey mode markers are enforced and only the unmarked BA117 NORMAL pollution is legacy-cleaned', () => {
  const sandbox = storageSandbox();
  const legacyQa = { flight: { flightNumber: 'BA 117', demo: true, demoScenario: 'NORMAL' } };

  assert.equal(sandbox.savedJourneyModeMatches({ qaJourney: false }), true);
  assert.equal(sandbox.savedJourneyModeMatches({ qaJourney: true }), false);
  assert.equal(sandbox.isLegacyQaJourneySnapshot(legacyQa), true);
  assert.equal(sandbox.isLegacyQaJourneySnapshot({
    qaJourney: false,
    flight: { flightNumber: 'BA117', demo: true, demoScenario: 'NORMAL' }
  }), false);
  assert.equal(sandbox.isLegacyQaJourneySnapshot({
    flight: { flightNumber: 'BA117', demo: true, demoScenario: 'DELAY' }
  }), false);

  sandbox.demoFlightEnabled = true;
  assert.equal(sandbox.isLegacyQaJourneySnapshot(legacyQa), false);
  sandbox.demoFlightEnabled = false;
  sandbox.qaJourneyEnabled = true;
  assert.equal(sandbox.savedJourneyModeMatches({ qaJourney: false }), false);
  assert.equal(sandbox.savedJourneyModeMatches({ qaJourney: true }), true);
  assert.equal(sandbox.isLegacyQaJourneySnapshot(legacyQa), false);
});

test('persistJourney writes only the active session key and records its mode', () => {
  const formalBytes = '{"flight":{"flightNumber":"MU5101"},"sentinel":"formal"}';
  const qaBytes = '{"flight":{"flightNumber":"BA117"},"sentinel":"qa"}';
  const sessionStorage = memoryStorage({
    'onestep-flight-journey-v2': formalBytes,
    'onestep-flight-qa-journey-v1': qaBytes
  });
  const sandbox = storageSandbox({
    sessionStorage,
    currentFlight: { flightNumber: 'BA117', demo: true, demoScenario: 'NORMAL' },
    currentAction: { actionCode: 'SECURITY' },
    journeyContext: { securityCleared: false },
    journeyHistory: [],
    journeyEvents: [],
    currentScreen: 'step',
    currentLocale: 'zh',
    pendingDocument: null,
    documentSegments: [],
    usableDocumentSegmentIndexes: [],
    selectedDocumentSegmentIndex: null,
    awaitingDocumentSelection: false,
    partialDocumentEntry: false,
    documentRootFields: {},
    documentRootReviewFields: [],
    documentRootReviewIssues: [],
    conflictsConfirmed: false,
    clockAnchor: null,
    refreshStatusState: null,
    checkInStatusAnswer: null,
    persistOfflineJourney() {},
    scheduleFamilyShareSync() {},
    pngCardCache: null,
    invalidatePngCard() {},
    pngCacheKey() { return ''; },
    prepareFamilyCard() { return Promise.resolve(); },
    document: { querySelector() { return null; } }
  });
  vm.runInContext(
    sourceBetween('  function persistJourney()', '  function pick(source, fields)'),
    sandbox
  );

  sandbox.qaJourneyEnabled = true;
  sandbox.persistJourney();
  assert.equal(sessionStorage.value('onestep-flight-journey-v2'), formalBytes);
  assert.equal(JSON.parse(sessionStorage.value('onestep-flight-qa-journey-v1')).qaJourney, true);

  const qaAfterQaPersist = sessionStorage.value('onestep-flight-qa-journey-v1');
  sandbox.qaJourneyEnabled = false;
  sandbox.currentFlight = { flightNumber: 'MU5101', demo: false };
  sandbox.persistJourney();
  assert.equal(sessionStorage.value('onestep-flight-qa-journey-v1'), qaAfterQaPersist);
  assert.equal(JSON.parse(sessionStorage.value('onestep-flight-journey-v2')).qaJourney, false);
});

test('persistOfflineJourney writes only the active offline key and records its mode', () => {
  const formalBytes = '{"flight":{"flightNumber":"MU5101"},"sentinel":"formal-offline"}';
  const localStorage = memoryStorage({ 'onestep-flight-offline-journey-v1': formalBytes });
  const sandbox = storageSandbox({
    localStorage,
    currentFlight: { flightNumber: 'BA117', demo: true, demoScenario: 'NORMAL' },
    currentAction: { actionCode: 'BOARD_NOW' },
    journeyContext: { atGate: true },
    journeyHistory: [],
    journeyEvents: [],
    currentScreen: 'step',
    currentLocale: 'zh',
    conflictsConfirmed: false,
    checkInStatusAnswer: 'yes',
    clockAnchor: null,
    safeFlightSnapshot(value) { return value; },
    safeActionSnapshot(value) { return value; },
    safeContextSnapshot(value) { return value; },
    safeHistorySnapshot(value) { return value; },
    safeEventSnapshot(value) { return value; }
  });
  vm.runInContext(
    sourceBetween('  function persistOfflineJourney()', '  function applySavedJourney(saved, fromOfflineStorage)'),
    sandbox
  );

  sandbox.qaJourneyEnabled = true;
  sandbox.persistOfflineJourney();
  assert.equal(localStorage.value('onestep-flight-offline-journey-v1'), formalBytes);
  assert.equal(JSON.parse(localStorage.value('onestep-flight-qa-offline-journey-v1')).qaJourney, true);
});

function runRestoreScenario(options) {
  const sessionStorage = memoryStorage(options.session);
  const localStorage = memoryStorage(options.local);
  const applied = [];
  const sandbox = storageSandbox({
    qaJourneyEnabled: Boolean(options.qa),
    demoFlightEnabled: Boolean(options.demo),
    demoScenariosEnabled: Boolean(options.demoScenarios),
    sessionStorage,
    localStorage,
    applySavedJourney(saved, offline) {
      applied.push({ saved, offline });
      return true;
    }
  });
  vm.runInContext(
    sourceBetween('  function restoreJourney()', "  document.addEventListener('click'"),
    sandbox
  );
  return { result: sandbox.restoreJourney(), sessionStorage, localStorage, applied };
}

test('restoreJourney never crosses modes and falls through legacy pollution to a valid formal snapshot', () => {
  const now = Date.now();
  const formal = JSON.stringify({
    savedAt: now, qaJourney: false,
    flight: { flightNumber: 'MU5101', demo: false },
    context: { securityCleared: false }, events: [{ id: 'sentinel' }]
  });
  const qa = JSON.stringify({
    savedAt: now, qaJourney: true,
    flight: { flightNumber: 'BA117', demo: true, demoScenario: 'NORMAL' }
  });

  const qaRestore = runRestoreScenario({
    qa: true,
    session: {
      'onestep-flight-journey-v2': formal,
      'onestep-flight-qa-journey-v1': qa
    }
  });
  assert.equal(qaRestore.result, true);
  assert.equal(qaRestore.applied[0].saved.flight.flightNumber, 'BA117');
  assert.deepEqual(qaRestore.sessionStorage.gets, ['onestep-flight-qa-journey-v1']);

  const ordinaryRestore = runRestoreScenario({
    session: { 'onestep-flight-journey-v2': formal },
    local: { 'onestep-flight-offline-journey-v1': formal }
  });
  assert.equal(ordinaryRestore.result, true);
  assert.equal(ordinaryRestore.applied[0].saved.flight.flightNumber, 'MU5101');
  assert.deepEqual(ordinaryRestore.sessionStorage.gets, ['onestep-flight-journey-v2']);

  const polluted = JSON.stringify({
    savedAt: now,
    flight: { flightNumber: 'BA117', demo: true, demoScenario: 'NORMAL' }
  });
  const cleanup = runRestoreScenario({
    session: { 'onestep-flight-journey-v2': polluted },
    local: { 'onestep-flight-offline-journey-v1': formal }
  });
  assert.equal(cleanup.result, true);
  assert.equal(cleanup.sessionStorage.value('onestep-flight-journey-v2'), null);
  assert.equal(cleanup.applied.length, 1);
  assert.equal(cleanup.applied[0].offline, true);
  assert.equal(cleanup.applied[0].saved.flight.flightNumber, 'MU5101');
});

test('legacy BA117 NORMAL cleanup is bypassed only in explicit demo mode', () => {
  const polluted = JSON.stringify({
    savedAt: Date.now(),
    flight: { flightNumber: 'BA117', demo: true, demoScenario: 'NORMAL' }
  });
  const ordinary = runRestoreScenario({
    session: { 'onestep-flight-journey-v2': polluted },
    local: { 'onestep-flight-offline-journey-v1': polluted }
  });
  assert.equal(ordinary.result, false);
  assert.equal(ordinary.applied.length, 0);
  assert.equal(ordinary.sessionStorage.value('onestep-flight-journey-v2'), null);
  assert.equal(ordinary.localStorage.value('onestep-flight-offline-journey-v1'), null);

  const demo = runRestoreScenario({
    demo: true,
    session: { 'onestep-flight-journey-v2': polluted }
  });
  assert.equal(demo.result, true);
  assert.equal(demo.applied[0].saved.flight.flightNumber, 'BA117');
});

test('clearJourney removes only the active journey keys', () => {
  const clearSource = sourceBetween('  function clearJourney()', '  function returnHome()');
  assert.match(clearSource, /sessionStorage\.removeItem\(activeJourneyStorageKey\(\)\)/);
  assert.match(clearSource, /localStorage\.removeItem\(activeOfflineJourneyStorageKey\(\)\)/);
  assert.doesNotMatch(clearSource, /removeItem\(STORAGE_KEY\)/);
  assert.doesNotMatch(clearSource, /removeItem\(OFFLINE_STORAGE_KEY\)/);
});

function eventSandbox() {
  const sandbox = {
    currentLocale: 'zh',
    PRESENTABLE_FLIGHT_CHANGE_TYPES: ['GATE_CHANGED'],
    standardActionCopy: {},
    calibratedNowIso() { return '2026-08-31T00:00:00Z'; },
    flightChangeCopyContext() { return {}; },
    flightChangeAlertCopy(change, locale) {
      return {
        zh: ['登机口已公布', `现在前往 ${change.newValue || ''}`],
        en: ['Gate announced', `Go now to ${change.newValue || ''}`],
        es: ['Puerta anunciada', `Ve ahora a ${change.newValue || ''}`]
      }[locale];
    },
    localeText(zh, en, es) {
      return sandbox.currentLocale === 'zh' ? zh : sandbox.currentLocale === 'es' ? es : en;
    },
    formatFlightNumber(value) { return value; }
  };
  vm.createContext(sandbox);
  vm.runInContext([
    sourceBetween('  function pick(source, fields)', '  function safeFlightSnapshot(flight)'),
    sourceBetween('  function safeEventSnapshot(event)', '  function safeHistorySnapshot(history, safeFlight)'),
    sourceBetween('  function journeyEventPresentation(event)', '  function renderJourneyTimeline()')
  ].join('\n'), sandbox);
  return sandbox;
}

test('legacy journey event titles and details survive persist, normalize, and three-language presentation', () => {
  const sandbox = eventSandbox();
  const legacy = {
    type: 'GATE_CHANGED',
    titleZh: '登机口已从 A1 改为 B42',
    titleEn: 'Gate changed from A1 to B42',
    details: 'A1 → B42',
    occurredAt: '2026-08-31T00:00:00Z',
    reversible: false
  };
  const persisted = JSON.parse(JSON.stringify(sandbox.safeEventSnapshot(legacy)));
  const restored = sandbox.normalizeJourneyEvent(persisted);

  assert.equal(restored.titleZh, legacy.titleZh);
  assert.equal(restored.titleEn, legacy.titleEn);
  assert.equal(restored.details, legacy.details);

  sandbox.currentLocale = 'zh';
  assert.deepEqual(JSON.parse(JSON.stringify(sandbox.journeyEventPresentation(restored))), {
    title: legacy.titleZh, detail: legacy.details
  });
  sandbox.currentLocale = 'en';
  assert.deepEqual(JSON.parse(JSON.stringify(sandbox.journeyEventPresentation(restored))), {
    title: legacy.titleEn, detail: legacy.details
  });
  sandbox.currentLocale = 'es';
  assert.deepEqual(JSON.parse(JSON.stringify(sandbox.journeyEventPresentation(restored))), {
    title: 'Puerta anunciada', detail: legacy.details
  });
});

test('new structured journey events retain the current Spanish presentation without duplicate legacy text', () => {
  const sandbox = eventSandbox();
  sandbox.currentLocale = 'es';
  const presentation = JSON.parse(JSON.stringify(sandbox.journeyEventPresentation({
    type: 'GATE_CHANGED', oldValue: 'A1', newValue: 'B42', params: {}
  })));

  assert.deepEqual(presentation, {
    title: 'Puerta anunciada', detail: 'Ve ahora a B42'
  });
});
