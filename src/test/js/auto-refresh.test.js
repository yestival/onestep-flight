'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const app = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/app.js'), 'utf8');

function sourceBetween(start, end) {
  const startIndex = app.indexOf(start);
  const endIndex = app.indexOf(end, startIndex);
  assert.ok(startIndex >= 0, `Missing app.js marker: ${start}`);
  assert.ok(endIndex > startIndex, `Missing app.js marker: ${end}`);
  return app.slice(startIndex, endIndex);
}

const sandbox = {
  AUTO_REFRESH_FAR_MS: 300000,
  AUTO_REFRESH_NEAR_MS: 120000,
  AUTO_REFRESH_BOUNDARY_MS: 90 * 60 * 1000,
  qaJourneyEnabled: false
};
vm.createContext(sandbox);
vm.runInContext([
  sourceBetween('  var DEMO_SCENARIO_METADATA = [', '  var statusLabels = {'),
  sourceBetween('  function autoRefreshDelayMs(flight, nowMs)', '  function runtimeAutoRefreshState('),
  sourceBetween('  var PRESENTABLE_FLIGHT_CHANGE_TYPES = [', '  function retainFlightChangeBatch('),
  sourceBetween('  function retainFlightChangeBatch(', '  function flightChangesSignature('),
  sourceBetween('  function flightChangesSignature(', '  function initialDelayAnnouncement('),
  sourceBetween('  function isUnseenFlightChangeSignature(', '  function updateRefreshChangeState('),
  sourceBetween('  function initialDelayAnnouncement(', '  function refreshFlight('),
  sourceBetween('  function flightChangeSpeechText(', '  function presentNewFlightChangeBatch('),
  sourceBetween('  function localizedAlertText(', '  function displayChangeValue('),
  'this.autoRefreshApi = { autoRefreshDelayMs, autoRefreshEligible, flightChangeAlertCopy, ' +
    'flightChangeSpeechText, retainFlightChangeBatch, presentableFlightChanges, buildFlightChangeGroups, ' +
    'initialDelayAnnouncement, isUnseenFlightChangeSignature, unseenFlightChangeBatch, ' +
    'DEMO_SCENARIO_METADATA };'
].join('\n'), sandbox);

const api = sandbox.autoRefreshApi;
const now = Date.parse('2026-08-27T00:00:00Z');

function flightAt(minutes) {
  return { timeContext: { effectiveDeparture: new Date(now + minutes * 60000).toISOString() } };
}

test('uses five minutes when departure is more than 90 minutes away', () => {
  assert.equal(api.autoRefreshDelayMs(flightAt(120), now), 300000);
});

test('uses two minutes at the 90-minute boundary', () => {
  assert.equal(api.autoRefreshDelayMs(flightAt(90), now), 120000);
});

test('uses two minutes inside the 90-minute boundary', () => {
  assert.equal(api.autoRefreshDelayMs(flightAt(45), now), 120000);
});

test('uses five minutes when departure time is unavailable', () => {
  assert.equal(api.autoRefreshDelayMs({}, now), 300000);
});

test('a boarded journey is not eligible for automatic refresh', () => {
  assert.equal(api.autoRefreshEligible({
    currentFlight: { live: true }, currentScreen: 'step', online: true, visible: true,
    requestInFlight: false, journeyContext: { boarded: true }
  }), false);
});

test('QA journeys are never eligible for automatic refresh', () => {
  sandbox.qaJourneyEnabled = true;
  try {
    assert.equal(api.autoRefreshEligible({
      currentFlight: { live: true, demo: true, demoScenario: 'NORMAL' },
      currentScreen: 'step', online: true, visible: true, requestInFlight: false,
      journeyContext: { boarded: false }
    }), false);
  } finally {
    sandbox.qaJourneyEnabled = false;
  }
});

test('formats a Chinese gate change as the two-line operational instruction', () => {
  const lines = Array.from(api.flightChangeAlertCopy({
    type: 'GATE_CHANGED', oldValue: 'B32', newValue: 'C18'
  }, 'zh', copyContext));
  assert.deepEqual(lines, ['登机口已更改', '现在前往 C18']);
  assert.equal(api.flightChangeSpeechText([lines], 'zh'), '登机口已更改。现在前往 C18。');
});

const copyContext = {
  flight: { gate: 'C18', delayMinutes: 20, arrivalDelayMinutes: 15 },
  formatTerminal: value => `Terminal ${value}`,
  formatGate: value => `Gate ${value}`,
  formatCheckIn: value => `Check-in ${value}`,
  formatBagDrop: value => `Bag drop ${value}`,
  formatBaggageBelt: value => `Baggage belt ${value}`,
  formatDepartureTime: () => '09:20',
  formatArrivalTime: () => '11:15',
  formatStatus: value => ({ DIVERTED: 'Diverted', RETURNED: 'Returned' })[value] || value,
  actionTitle: () => 'Board now'
};

test('every supported backend and legacy change type has two localized lines', () => {
  const changes = [
    { type: 'TERMINAL_CHANGED', oldValue: '1', newValue: '2' },
    { type: 'GATE_CHANGED', oldValue: 'B32', newValue: 'C18' },
    { type: 'CHECK_IN_COUNTER_CHANGED', oldValue: 'A1', newValue: 'B2' },
    { type: 'BAG_DROP_DESK_CHANGED', oldValue: 'A3', newValue: 'B4' },
    { type: 'BOARDING_TIME_CHANGED', oldValue: '2026-08-27T08:00:00+08:00', newValue: '2026-08-27T08:10:00+08:00' },
    { type: 'DEPARTURE_TIME_CHANGED', oldValue: '2026-08-27T09:00:00+08:00', newValue: '2026-08-27T09:20:00+08:00' },
    { type: 'DEPARTURE_DELAY_CHANGED', oldValue: '5', newValue: '20' },
    { type: 'DELAY_CHANGED', oldValue: '5', newValue: '20' },
    { type: 'STATUS_CHANGED', oldValue: 'EXPECTED', newValue: 'BOARDING' },
    { type: 'FLIGHT_CANCELLED', oldValue: 'EXPECTED', newValue: 'CANCELLED' },
    { type: 'ARRIVAL_TIME_CHANGED', oldValue: '2026-08-27T11:00:00+08:00', newValue: '2026-08-27T11:15:00+08:00' },
    { type: 'ARRIVAL_DELAY_CHANGED', oldValue: '0', newValue: '15' },
    { type: 'BAGGAGE_BELT_CHANGED', oldValue: '5', newValue: '6' },
    { type: 'DATA_FRESHNESS_CHANGED', oldValue: 'FRESH', newValue: 'STALE' }
  ];
  for (const locale of ['zh', 'en', 'es']) {
    for (const change of changes) {
      const lines = Array.from(api.flightChangeAlertCopy(change, locale, copyContext));
      assert.equal(lines.length, 2, `${locale} ${change.type}`);
      assert.ok(lines.every(line => typeof line === 'string' && line.trim()), `${locale} ${change.type}`);
    }
  }
});

test('an empty refresh retains an unconfirmed flight-change batch and creates no new speech', () => {
  const active = [{ type: 'GATE_CHANGED', oldValue: 'B32', newValue: 'C18' }];
  assert.deepEqual(Array.from(api.retainFlightChangeBatch(active, []), change => ({ ...change })), active);
  assert.equal(api.flightChangeSpeechText([], 'zh'), '');
});

test('a stable refresh preserves the visible batch without repeating old speech or dropping its step alert', () => {
  const gateChange = { type: 'GATE_CHANGED', oldValue: 'B32', newValue: 'C18' };
  const stepChange = {
    previousIdentity: 'ACTION:WAIT',
    action: { stepType: 'ACTION', actionCode: 'GO_TO_GATE' }
  };
  const presented = {};
  const first = api.unseenFlightChangeBatch([gateChange], stepChange, presented);
  assert.ok(first);
  assert.equal(first.changes.length, 1);
  assert.equal(first.nextStepChange.action.actionCode, 'GO_TO_GATE');
  first.signatures.forEach(signature => { presented[signature] = true; });

  assert.equal(api.unseenFlightChangeBatch([gateChange], null, presented), null);

  const terminalChange = { type: 'TERMINAL_CHANGED', oldValue: '4', newValue: '5' };
  const second = api.unseenFlightChangeBatch([gateChange, terminalChange], null, presented);
  assert.deepEqual(Array.from(second.changes, change => ({ ...change })), [terminalChange]);
  assert.equal(second.nextStepChange, null);
  const speechGroups = api.buildFlightChangeGroups(second.changes, null, 'zh', copyContext);
  assert.equal(api.flightChangeSpeechText(speechGroups, 'zh').includes('登机口已更改'), false);
  assert.equal(api.flightChangeSpeechText(speechGroups, 'zh').includes('航站楼已更改'), true);
});

test('newly published fields without a meaningful old value are silent', () => {
  const firstPublications = [
    { type: 'GATE_CHANGED', oldValue: null, newValue: 'B32' },
    { type: 'TERMINAL_CHANGED', oldValue: '', newValue: '5' },
    { type: 'CHECK_IN_COUNTER_CHANGED', oldValue: '—', newValue: 'Zone A, counters 1-4' },
    { type: 'BAG_DROP_DESK_CHANGED', oldValue: null, newValue: 'Counter 5' },
    { type: 'BOARDING_TIME_CHANGED', oldValue: '', newValue: '2026-08-27T08:15:00Z' },
    { type: 'DEPARTURE_TIME_CHANGED', oldValue: null, newValue: '2026-08-27T09:00:00Z' },
    { type: 'STATUS_CHANGED', oldValue: '', newValue: 'ON_TIME' },
    { type: 'ARRIVAL_TIME_CHANGED', oldValue: null, newValue: '2026-08-27T17:00:00Z' },
    { type: 'BAGGAGE_BELT_CHANGED', oldValue: null, newValue: 'Belt 8' }
  ];
  assert.deepEqual(Array.from(api.presentableFlightChanges({ latestChanges: firstPublications })), []);
});

test('a positive initial delay produces the exact one-time copy in all languages', () => {
  const change = api.initialDelayAnnouncement({ delayMinutes: 35 });
  assert.ok(change);
  const expected = {
    zh: ['航班已公布延误', '当前预计晚点 35 分钟'],
    en: ['A flight delay has been announced', 'Current estimated delay: 35 minutes'],
    es: ['Se ha anunciado un retraso del vuelo', 'Retraso estimado actual: 35 minutos']
  };
  for (const locale of Object.keys(expected)) {
    assert.deepEqual(Array.from(api.flightChangeAlertCopy(change, locale, copyContext)), expected[locale]);
  }
  assert.equal(api.initialDelayAnnouncement({ delayMinutes: 0 }), null);
  assert.equal(api.initialDelayAnnouncement({ status: 'DELAYED' }), null);
});

test('an already presented signature is not eligible to appear again', () => {
  assert.equal(api.isUnseenFlightChangeSignature('GATE:B32:C18', {}), true);
  assert.equal(api.isUnseenFlightChangeSignature('GATE:B32:C18', { 'GATE:B32:C18': true }), false);
  assert.equal(api.isUnseenFlightChangeSignature('', {}), false);
});

test('flight-change notices persist until the traveller dismisses them', () => {
  assert.equal(app.includes('FLIGHT_CHANGE_NOTICE_MS'), false);
  assert.equal(app.includes('setTimeout(dismissFlightChangeNotice'), false);
  assert.match(app, /dataset\.action = 'dismiss-flight-change'/);
});

test('one batch deduplicates matching changes before creating one speech text', () => {
  const change = { type: 'GATE_CHANGED', oldValue: 'B32', newValue: 'C18' };
  const unique = Array.from(api.presentableFlightChanges({ latestChanges: [change, { ...change }] }));
  assert.equal(unique.length, 1);
  const groups = api.buildFlightChangeGroups(unique, null, 'zh', copyContext);
  assert.equal(api.flightChangeSpeechText(groups, 'zh'), '登机口已更改。现在前往 C18。');
});

test('departure time and departure delay changes merge into one visible and spoken group', () => {
  const groups = api.buildFlightChangeGroups([
    { type: 'DEPARTURE_TIME_CHANGED', oldValue: '2026-08-27T09:00:00+08:00', newValue: '2026-08-27T09:20:00+08:00' },
    { type: 'DEPARTURE_DELAY_CHANGED', oldValue: '5', newValue: '20' }
  ], null, 'zh', copyContext);
  assert.equal(groups.length, 1);
  assert.deepEqual(Array.from(groups[0]), ['起飞时间和延误信息已更改', '新的预计起飞时间：09:20（预计晚点 20 分钟）']);
});

test('arrival time and arrival delay changes merge into one visible and spoken group', () => {
  const groups = api.buildFlightChangeGroups([
    { type: 'ARRIVAL_TIME_CHANGED', oldValue: '2026-08-27T10:35:00+08:00', newValue: '2026-08-27T11:15:00+08:00' },
    { type: 'ARRIVAL_DELAY_CHANGED', oldValue: '0', newValue: '40' }
  ], null, 'en', copyContext);
  assert.equal(groups.length, 1);
  assert.deepEqual(Array.from(groups[0]), [
    'Arrival time and delay changed',
    'New estimated arrival: 11:15 (estimated delay: 40 minutes)'
  ]);
});

test('a delayed status is suppressed when a specific delay change is present', () => {
  const groups = api.buildFlightChangeGroups([
    { type: 'STATUS_CHANGED', oldValue: 'ON_TIME', newValue: 'DELAYED' },
    { type: 'DEPARTURE_TIME_CHANGED', oldValue: '2026-08-27T09:00:00+08:00', newValue: '2026-08-27T09:20:00+08:00' },
    { type: 'DEPARTURE_DELAY_CHANGED', oldValue: '0', newValue: '20' }
  ], null, 'en', copyContext);
  assert.equal(groups.length, 1);
  assert.equal(groups[0][0], 'Departure time and delay changed');
});

test('stale and recovered freshness copy is exact in all three languages', () => {
  const expected = {
    zh: [
      ['实时航班信息已过期', '请查看机场屏幕，或向工作人员确认'],
      ['实时航班信息已恢复更新', '请按当前显示的信息继续']
    ],
    en: [
      ['Live flight information is out of date', 'Check the airport screens or ask a member of staff'],
      ['Live flight information is updating again', 'Continue using the currently displayed information']
    ],
    es: [
      ['La información del vuelo está desactualizada', 'Consulta las pantallas del aeropuerto o pregunta al personal'],
      ['La información del vuelo vuelve a estar actualizada', 'Continúa según la información que se muestra']
    ]
  };
  for (const locale of Object.keys(expected)) {
    assert.deepEqual(Array.from(api.flightChangeAlertCopy({
      type: 'DATA_FRESHNESS_CHANGED', oldValue: 'FRESH', newValue: 'STALE'
    }, locale, copyContext)), expected[locale][0]);
    assert.deepEqual(Array.from(api.flightChangeAlertCopy({
      type: 'DATA_FRESHNESS_CHANGED', oldValue: 'STALE', newValue: 'FRESH'
    }, locale, copyContext)), expected[locale][1]);
  }
});

test('scenario metadata contains all 17 ids with their production phases and expected changes', () => {
  const metadata = Array.from(api.DEMO_SCENARIO_METADATA, scenario => ({
    id: scenario.id,
    phase: scenario.phase,
    expectedTypes: Array.from(scenario.expectedTypes)
  }));
  assert.deepEqual(metadata.map(scenario => scenario.id), [
    'NORMAL', 'TERMINAL_CHANGE', 'GATE_CHANGE', 'CHECK_IN_COUNTER_CHANGE', 'BAG_DROP_DESK_CHANGE',
    'BOARDING_TIME_CHANGE', 'DEPARTURE_TIME_CHANGE', 'DELAY', 'BOARDING', 'CANCEL', 'DEPARTED',
    'DIVERTED', 'RETURNED', 'ARRIVAL_TIME_CHANGE', 'ARRIVED', 'BAGGAGE_BELT_CHANGE', 'STALE'
  ]);
  assert.deepEqual(metadata.filter(scenario => scenario.phase === 'boarding').map(scenario => scenario.id), ['BOARDING']);
  assert.deepEqual(metadata.filter(scenario => scenario.phase === 'arrival').map(scenario => scenario.id), [
    'ARRIVAL_TIME_CHANGE', 'ARRIVED', 'BAGGAGE_BELT_CHANGE'
  ]);
  assert.deepEqual(metadata.find(scenario => scenario.id === 'DELAY').expectedTypes,
    ['STATUS_CHANGED', 'DEPARTURE_TIME_CHANGED', 'DEPARTURE_DELAY_CHANGED']);
  assert.deepEqual(metadata.find(scenario => scenario.id === 'STALE').expectedTypes,
    ['DATA_FRESHNESS_CHANGED']);
  assert.deepEqual(metadata.find(scenario => scenario.id === 'NORMAL').expectedTypes, []);
  const scenarioTypes = [...new Set(metadata.flatMap(scenario => scenario.expectedTypes))];
  for (const type of scenarioTypes) {
    const visible = Array.from(api.presentableFlightChanges({
      latestChanges: [{ type, oldValue: type.includes('DELAY') ? '0' : 'BEFORE',
        newValue: type.includes('DELAY') ? '60' : 'AFTER' }]
    }));
    assert.equal(visible.length, 1, type);
  }
});
