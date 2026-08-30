const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..', '..', '..');
const appPath = path.join(root, 'src', 'main', 'resources', 'static', 'app.js');
const appSource = fs.readFileSync(appPath, 'utf8');

function sourceBetween(start, end) {
  const startIndex = appSource.indexOf(start);
  const endIndex = appSource.indexOf(end, startIndex + start.length);
  assert.notEqual(startIndex, -1, `Missing source marker: ${start}`);
  assert.notEqual(endIndex, -1, `Missing source marker: ${end}`);
  return appSource.slice(startIndex, endIndex);
}

function qaSandbox() {
  const sandbox = {
    Date,
    Number,
    normalizeFlightNumber(value) {
      return String(value || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
    },
    latestFlightChange() { return null; },
    calibratedNowIso() { return '2026-08-31T00:00:00Z'; },
    translations: {
      zh: { checkInQuestion: '已经值机了吗？', checkInQuestionLead: '拿到登机牌也算。' },
      en: { checkInQuestion: 'Have you checked in?', checkInQuestionLead: 'A boarding pass counts as checked in.' },
      es: { checkInQuestion: '¿Ya has facturado?', checkInQuestionLead: 'Tener la tarjeta de embarque cuenta como facturar.' }
    },
    spanishActionCopy: {
      CHECK_IN: { title: 'Factura ahora' },
      BAG_DROP: { title: 'Entrega ahora el equipaje' },
      SECURITY: { title: 'Ve ahora al control de seguridad' },
      GO_TO_GATE: { title: 'Ve ahora a la puerta de embarque' },
      BOARD_NOW: { title: 'Embarca ahora' },
      IN_FLIGHT: { title: 'Ya estás a bordo' },
      BAGGAGE_CLAIM: { title: 'Recoge ahora el equipaje' },
      EXIT_AIRPORT: { title: 'Ve ahora a la salida de llegadas' }
    },
    currentFlight: null,
    pendingDocument: null,
    selectedDocumentSegmentIndex: null,
    lastLookupContext: null,
    conflictsConfirmed: false,
    journeyContext: null,
    currentAction: null,
    checkInStatusAnswer: null,
    qaJourneyEnabled: true,
    navigator: { onLine: true },
    dismissFlightChangeNotice() {},
    renderFlight() {},
    renderJourneyResponse() {},
    persistJourney() {},
    snapshotJourney() {},
    stopAutoRefresh() {},
    recordJourneyEvent() {},
    requestNextAction() {},
    setStaffContent() {},
    setBaggageStaffContent() {},
    showStaff() {}
  };
  vm.createContext(sandbox);
  vm.runInContext([
    sourceBetween('  function resolvePayload(flightNumber, departureDate, demo, documentResult, demoScenario, refresh)', '  function extractionToFlight(result)'),
    sourceBetween('  function qaJourneyDepartureDate()', '  function resolveUploadedFlight(options)'),
    sourceBetween('  function createJourneyContext()', '  function autoRefreshDelayMs(flight, nowMs)'),
    sourceBetween('  function requestNextAction(options)', '  var spanishActionCopy = {'),
    sourceBetween('  function applyActionCompletion(code)', '  function requestStaffHelp()')
  ].join('\n'), sandbox);
  return sandbox;
}

function demoFlight(overrides) {
  const future = new Date(Date.now() + 90 * 60 * 1000).toISOString();
  return Object.assign({
    flightNumber: 'BA117',
    departureDate: future.slice(0, 10),
    demo: true,
    demoScenario: 'NORMAL',
    timeContext: { effectiveDeparture: future },
    effectiveDeparture: future,
    terminal: '5',
    gate: 'B32'
  }, overrides || {});
}

function actionCode(sandbox) {
  const action = sandbox.buildQaAction();
  return action.stepType === 'ACTION' ? action.actionCode : action.stepType;
}

test('QA departure date is dynamic and restored snapshots must be future NORMAL demo flights', () => {
  const sandbox = qaSandbox();
  const expectedDates = new Set([
    new Date(Date.now() + 2 * 60 * 60 * 1000).toISOString().slice(0, 10)
  ]);
  const departureDate = sandbox.qaJourneyDepartureDate();
  expectedDates.add(new Date(Date.now() + 2 * 60 * 60 * 1000).toISOString().slice(0, 10));
  assert.equal(expectedDates.has(departureDate), true);
  assert.equal(sandbox.qaJourneyFlightIsUsable(demoFlight()), true);
  assert.equal(sandbox.qaJourneyFlightIsUsable(demoFlight({ demo: false })), false);
  assert.equal(sandbox.qaJourneyFlightIsUsable(demoFlight({ demoScenario: 'DELAY' })), false);
  assert.equal(sandbox.qaJourneyFlightIsUsable(demoFlight({ timeContext: null })), false);
  assert.equal(sandbox.qaJourneyFlightIsUsable(demoFlight({
    timeContext: { effectiveDeparture: new Date(Date.now() - 1000).toISOString() }
  })), false);
});

test('QA initialization and demo entry request the NORMAL demo provider without fixed dates', () => {
  const sandbox = qaSandbox();
  const departureDate = sandbox.qaJourneyDepartureDate();
  const payload = sandbox.resolvePayload('BA 117', departureDate, true, null, 'NORMAL', false);
  assert.equal(payload.flightNumber, 'BA117');
  assert.equal(payload.departureDate, departureDate);
  assert.equal(payload.demo, true);
  assert.equal(payload.demoScenario, 'NORMAL');
  assert.equal(payload.document, null);
  assert.equal(payload.refresh, false);
  assert.equal(appSource.includes('2026-08-24'), false);
  assert.equal(appSource.includes('2026-09-03'), false);
  assert.match(appSource,
    /if \(qaJourneyEnabled && \(!restoredJourney \|\| !qaJourneyFlightIsUsable\(currentFlight\)\)\) \{\s*startFlightLookup\('BA117', qaJourneyDepartureDate\(\), true, null, 'NORMAL'\);/);
  assert.match(appSource,
    /action === 'demo-flight'[\s\S]*?startFlightLookup\('BA117', qaJourneyDepartureDate\(\), true, null, 'NORMAL'\);/);
  assert.match(appSource,
    /var qaJourneyEnabled = new URLSearchParams\(window\.location\.search\)\.get\('qaJourney'\) === 'true';/);
});

test('QA refresh requests always stay in the local state machine', async () => {
  const sandbox = qaSandbox();
  let backendRequests = 0;
  sandbox.postFlightJson = () => {
    backendRequests += 1;
    return Promise.reject(new Error('QA must not reach the backend'));
  };
  sandbox.currentFlight = demoFlight();
  sandbox.journeyContext = sandbox.createJourneyContext();
  Object.assign(sandbox.journeyContext, {
    checkedIn: true,
    hasCheckedBaggage: false,
    securityCleared: true,
    atGate: true,
    refreshRequested: true
  });

  const action = await sandbox.requestNextAction({ refresh: true, silent: true });

  assert.equal(action.actionCode, 'BOARD_NOW');
  assert.equal(sandbox.currentAction.actionCode, 'BOARD_NOW');
  assert.equal(sandbox.journeyContext.refreshRequested, false);
  assert.equal(backendRequests, 0);
});

test('QA begins with the existing check-in question and does not prefill baggage', () => {
  const sandbox = qaSandbox();
  sandbox.currentFlight = demoFlight();
  sandbox.journeyContext = sandbox.createJourneyContext();
  assert.equal(sandbox.journeyContext.checkedIn, false);
  assert.equal(sandbox.journeyContext.hasCheckedBaggage, null);
  assert.equal(sandbox.buildCheckInQuestion().stepType, 'CHECK_IN_QUESTION');
  assert.equal(actionCode(sandbox), 'CHECK_IN');
  assert.match(sourceBetween("    if (action === 'continue')", "    if (action === 'confirm-conflicts')"),
    /showCheckInQuestion\(\);/);
});

test('QA checked-baggage branch includes bag drop, boarding confirmation, claim, exit, and completion', () => {
  const sandbox = qaSandbox();
  sandbox.currentFlight = demoFlight();
  sandbox.journeyContext = sandbox.createJourneyContext();

  assert.equal(actionCode(sandbox), 'CHECK_IN');
  sandbox.applyActionCompletion('CHECK_IN');
  const baggageQuestion = sandbox.buildQaAction();
  assert.equal(baggageQuestion.stepType, 'BAGGAGE_QUESTION');
  assert.equal(baggageQuestion.titleZh, '你有需要托运的行李吗？');
  assert.equal(baggageQuestion.descriptionZh,
    '随身携带的小包不算托运行李。请选择最符合你情况的一项。');
  assert.equal(baggageQuestion.titleEn, 'Do you have baggage to check?');
  assert.equal(baggageQuestion.descriptionEn,
    'A small carry-on bag does not count as checked baggage. Choose the option that fits you.');
  assert.equal(baggageQuestion.titleEs, '¿Tienes equipaje para facturar?');
  assert.equal(baggageQuestion.descriptionEs,
    'El equipaje de mano no cuenta como equipaje facturado. Elige la opción que corresponda.');

  sandbox.journeyContext.hasCheckedBaggage = true;
  assert.equal(actionCode(sandbox), 'BAG_DROP');
  sandbox.applyActionCompletion('BAG_DROP');
  assert.equal(actionCode(sandbox), 'SECURITY');
  sandbox.applyActionCompletion('SECURITY');
  assert.equal(actionCode(sandbox), 'GO_TO_GATE');
  sandbox.applyActionCompletion('GO_TO_GATE');
  assert.equal(sandbox.journeyContext.boarded, false);
  assert.equal(actionCode(sandbox), 'BOARD_NOW');
  sandbox.applyActionCompletion('BOARD_NOW');
  assert.equal(actionCode(sandbox), 'IN_FLIGHT');
  sandbox.applyActionCompletion('IN_FLIGHT');
  assert.equal(actionCode(sandbox), 'BAGGAGE_CLAIM');
  sandbox.applyActionCompletion('BAGGAGE_CLAIM');
  assert.equal(actionCode(sandbox), 'EXIT_AIRPORT');
  sandbox.applyActionCompletion('EXIT_AIRPORT');
  assert.equal(actionCode(sandbox), 'COMPLETE');
});

test('QA no-baggage branch skips bag drop and baggage claim', () => {
  const sandbox = qaSandbox();
  sandbox.currentFlight = demoFlight();
  sandbox.journeyContext = sandbox.createJourneyContext();
  sandbox.journeyContext.checkedIn = true;
  assert.equal(actionCode(sandbox), 'BAGGAGE_QUESTION');
  sandbox.journeyContext.hasCheckedBaggage = false;
  assert.equal(actionCode(sandbox), 'SECURITY');
  sandbox.applyActionCompletion('SECURITY');
  sandbox.applyActionCompletion('GO_TO_GATE');
  assert.equal(actionCode(sandbox), 'BOARD_NOW');
  sandbox.applyActionCompletion('BOARD_NOW');
  assert.equal(actionCode(sandbox), 'IN_FLIGHT');
  sandbox.applyActionCompletion('IN_FLIGHT');
  assert.equal(actionCode(sandbox), 'EXIT_AIRPORT');
  assert.equal(sandbox.journeyContext.bagDropped, false);
  assert.equal(sandbox.journeyContext.baggageCollected, false);
  sandbox.applyActionCompletion('EXIT_AIRPORT');
  assert.equal(actionCode(sandbox), 'COMPLETE');
});

test('boarding and landing actions retain their confirmation dialogs', () => {
  const completionSource = sourceBetween('  function completeCurrentAction()', '  function applyActionCompletion(code)');
  assert.match(completionSource, /code === 'BOARD_NOW'[\s\S]*?showBoardConfirmation\(\);[\s\S]*?return;/);
  assert.match(completionSource, /code === 'IN_FLIGHT'[\s\S]*?showLandedConfirmation\(\);[\s\S]*?return;/);
  const actionCompletion = sourceBetween('  function applyActionCompletion(code)', '  function requestStaffHelp()');
  const gateStart = actionCompletion.indexOf("    if (code === 'GO_TO_GATE') {");
  const boardStart = actionCompletion.indexOf("    if (code === 'BOARD_NOW')", gateStart);
  assert.notEqual(gateStart, -1);
  assert.notEqual(boardStart, -1);
  assert.equal(actionCompletion.slice(gateStart, boardStart).includes('journeyContext.boarded = true'), false);
});

test('unsure check-in and baggage answers keep the question and open the staff card', () => {
  const sandbox = qaSandbox();
  let staffOpens = 0;
  let nextRequests = 0;
  sandbox.showStaff = () => { staffOpens += 1; };
  sandbox.requestNextAction = () => { nextRequests += 1; };
  vm.runInContext([
    sourceBetween('  function answerCheckIn(answer)', '  function restoreReversibleEvents(events)'),
    sourceBetween('  function answerBaggage(answer)', '  function completeCurrentAction()')
  ].join('\n'), sandbox);

  sandbox.currentFlight = demoFlight();
  sandbox.journeyContext = sandbox.createJourneyContext();
  sandbox.currentAction = sandbox.buildCheckInQuestion();
  sandbox.answerCheckIn('unsure');
  assert.equal(sandbox.currentAction.stepType, 'CHECK_IN_QUESTION');
  assert.equal(sandbox.journeyContext.checkedIn, false);
  assert.equal(staffOpens, 1);
  assert.equal(nextRequests, 0);

  sandbox.journeyContext.checkedIn = true;
  sandbox.currentAction = sandbox.buildQaAction();
  sandbox.answerBaggage('unsure');
  assert.equal(sandbox.currentAction.stepType, 'BAGGAGE_QUESTION');
  assert.equal(sandbox.journeyContext.hasCheckedBaggage, null);
  assert.equal(staffOpens, 2);
  assert.equal(nextRequests, 0);
});
