'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const app = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/app.js'), 'utf8');
const index = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/index.html'), 'utf8');
const control = fs.readFileSync(path.resolve(__dirname, '../../main/resources/demo-recording/demo-control.js'), 'utf8');
const localProperties = fs.readFileSync(path.resolve(__dirname, '../../main/resources/application.properties'), 'utf8');
const prodProperties = fs.readFileSync(path.resolve(__dirname, '../../main/resources/application-prod.properties'), 'utf8');

test('recording mode is feature-gated and keeps every required session key namespaced', () => {
  assert.match(localProperties, /onestep\.demo-recording\.enabled=true/);
  assert.match(prodProperties, /onestep\.demo-recording\.enabled=false/);
  for (const prefix of [
    'onestep-flight-demo-journey-v1:',
    'onestep-flight-demo-offline-journey-v1:',
    'onestep-flight-demo-share-session-v1:',
    'onestep-flight-demo-share-pending-v1:',
    'onestep-flight-demo-share-revocation-pending-v1:',
    'onestep-flight-demo-control-v1:'
  ]) assert.ok(app.includes(prefix) || control.includes(prefix), `missing ${prefix}`);
  assert.match(app, /fetchJson\('\/api\/demo-recording\/sessions\/'/);
  assert.match(app, /demoRecordingSessionId = null;\s+initializePage\(\);/);
});

test('recording passenger uses the production UI and hides all existing test-only surfaces', () => {
  assert.doesNotMatch(index, /href="\/demo-control"/);
  assert.match(app, /\[data-scenario-test-card\], \[data-qa-airport-panel\]/);
  assert.match(app, /element\.hidden = !demoFlightEnabled \|\| Boolean\(demoRecordingSessionId\)/);
  assert.match(app, /renderFlight\(currentFlight/);
  assert.match(app, /renderJourneyResponse/);
  assert.doesNotMatch(control, /buildQaAction/);
});

test('gate recording uses the existing refresh-change-render-speech path and passenger-side timer', () => {
  assert.match(app, /return refreshFlight\(\{ silent: true, source: 'demo-recording' \}\)/);
  assert.match(app, /recordLiveChanges\(flight\.latestChanges\)/);
  assert.match(app, /renderFlightChanges\(\)/);
  assert.match(app, /presentNewFlightChangeBatch\(\)/);
  assert.match(app, /demoRecordingCountdownTimer = window\.setInterval/);
  assert.match(control, /SCHEDULE_GATE_CHANGE/);
  assert.match(control, /delayMs: 5000/);
});

test('control commands travel over the exact per-session BroadcastChannel', () => {
  assert.match(control, /new BroadcastChannel\('onestep-flight-demo-' \+ sessionId\)/);
  assert.match(app, /new BroadcastChannel\('onestep-flight-demo-' \+ demoRecordingSessionId\)/);
  assert.match(control, /type: 'COMMAND'/);
  assert.match(app, /type: 'RESULT'/);
  assert.match(app, /type: 'STATE'/);
});
