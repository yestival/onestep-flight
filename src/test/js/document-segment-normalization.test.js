'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const appPath = path.resolve(__dirname, '../../main/resources/static/app.js');
const fixturePath = path.resolve(__dirname, '../fixtures/document-segment-normalization.json');
const app = fs.readFileSync(appPath, 'utf8');
const fixtures = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));
const indexHtml = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/index.html'), 'utf8');
const styles = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/styles.css'), 'utf8');

function sourceBetween(start, end) {
  const startIndex = app.indexOf(start);
  const endIndex = app.indexOf(end, startIndex);
  assert.ok(startIndex >= 0, `Missing app.js marker: ${start}`);
  assert.ok(endIndex > startIndex, `Missing app.js marker: ${end}`);
  return app.slice(startIndex, endIndex);
}

const sandbox = {};
vm.createContext(sandbox);
vm.runInContext([
  sourceBetween('  function normalizeFlightNumber(value)', '  function fetchJson(url, options)'),
  sourceBetween('  function setLookupBusy(busy)', '  function announce(message)'),
  sourceBetween('  function resolvePayload(flightNumber, departureDate, demo, documentResult, demoScenario, refresh)', '  function extractionToFlight(result)'),
  sourceBetween('  function selectedDocumentResult(source, index)', '  function renderSegmentSelector()'),
  sourceBetween('  function validDocumentFlightNumber(value)', '  function resetDocumentResultState()'),
  sourceBetween('  function syncUsableDocumentSegmentIndexes()', '  function setDocumentExtractionState(result, assessment)'),
  `this.documentSegmentApi = {
    normalizeDocumentResult, classifyDocumentResult, selectedDocumentResult, createSegmentDisplay,
    validDocumentDepartureDate, validDocumentMonthDay, normalizeFlightNumberConfidence,
    documentSegmentLookupReady, documentSegmentHasConflict, documentSegmentHasCriticalConflict,
    documentSegmentState, documentItineraryState, lookupGuardReason, applyDocumentSegmentCompletion,
    setLookupBusy, resolvePayload
  };`
].join('\n'), sandbox);

const api = sandbox.documentSegmentApi;
sandbox.confirmedDocumentFlightNumberIndexes = [];
sandbox.documentRootReviewFields = [];
sandbox.documentSegments = [];
sandbox.currentLocale = 'en';
sandbox.localeText = (zh, en) => en;

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function plain(value) {
  return JSON.parse(JSON.stringify(value));
}

function segmentIdentity(segment) {
  return {
    flightNumber: segment.flightNumber,
    departureAirport: segment.departureAirport,
    arrivalAirport: segment.arrivalAirport,
    scheduledDeparture: segment.scheduledDeparture
  };
}

function installSegmentDisplayFixture(reviewFields = []) {
  sandbox.documentRootReviewFields = reviewFields;
  sandbox.selectedDocumentSegmentIndex = null;
  sandbox.lookupInFlight = false;
  sandbox.confirmedDocumentFlightNumberIndexes = [];
  sandbox.currentLocale = 'zh';
  sandbox.formatFlightNumber = String;
  sandbox.formatDate = String;
  sandbox.t = function (key) {
    return {
      incompleteSegment: '补全这一段',
      pendingSegment: '请确认航班号',
      confirmSegment: '确认这一段',
      selectedSegment: '当前航段',
      chooseSegment: '选择这一段',
      readySegment: '可以查询'
    }[key] || key;
  };
  sandbox.document = {
    createElement(tagName) {
      return {
        tagName,
        dataset: {},
        attributes: {},
        children: [],
        className: '',
        disabled: false,
        setAttribute(name, value) { this.attributes[name] = value; },
        append(...children) { this.children.push(...children); }
      };
    }
  };
}

test('preserves the server-completed full date, month-day, and date source', () => {
  const fixture = clone(fixtures.singleSegment);
  const originalJson = JSON.stringify(fixture);
  const normalized = api.normalizeDocumentResult(fixture);
  const assessment = plain(api.classifyDocumentResult(normalized));

  assert.notStrictEqual(normalized, fixture);
  assert.notStrictEqual(normalized.segments, fixture.segments);
  assert.equal(normalized.segments[0].departureDate, '2026-08-04');
  assert.equal(normalized.segments[0].departureMonthDay, '08-04');
  assert.equal(normalized.segments[0].dateSource, 'CURRENT_YEAR_FROM_MONTH_DAY');
  assert.equal(assessment.outcome, 'USABLE');
  assert.deepEqual(assessment.usableIndexes, [0]);
  assert.equal(JSON.stringify(fixture), originalJson);
});

test('a critical flight or date conflict is never classified as USABLE', () => {
  const normalized = api.normalizeDocumentResult({
    needsReviewFields: ['segments[0].conflict.departure_date'],
    segments: [
      {
        flightNumber: 'BA117', flightNumberConfidence: 'HIGH', departureDate: '2026-08-04',
        departureMonthDay: '08-04', dateSource: 'EXPLICIT_FULL_DATE'
      },
      {
        flightNumber: 'LH400', flightNumberConfidence: 'HIGH', departureDate: '2026-08-05',
        departureMonthDay: '08-05', dateSource: 'EXPLICIT_FULL_DATE'
      }
    ]
  });
  const assessment = plain(api.classifyDocumentResult(normalized));

  assert.equal(assessment.outcome, 'PARTIAL');
  assert.deepEqual(assessment.usableIndexes, [1]);
});

test('preserves every server-completed segment in document order and keeps all cards selectable', () => {
  const fixture = clone(fixtures.multiSegment);
  const normalized = api.normalizeDocumentResult(fixture);
  const assessment = plain(api.classifyDocumentResult(normalized));
  const normalizedSegments = plain(normalized.segments);

  assert.equal(normalizedSegments.length, 4);
  assert.deepEqual(normalizedSegments.map(segment => segment.flightNumber),
    ['KL1036', 'MF812', 'MF825', 'AF1668']);
  assert.deepEqual(normalizedSegments.map(segment => segment.departureDate),
    ['2026-08-04', '2026-08-04', '2026-09-05', '2026-09-05']);
  assert.deepEqual(normalizedSegments.map(segment => segment.departureMonthDay),
    ['08-04', '08-04', '09-05', '09-05']);
  assert.deepEqual(normalizedSegments.map(segmentIdentity), fixture.segments.map(segmentIdentity));
  assert.equal(assessment.outcome, 'PARTIAL');
  assert.deepEqual(assessment.usableIndexes, [0, 2]);

  installSegmentDisplayFixture();
  const options = normalized.segments.map((segment, index) => api.createSegmentDisplay(segment, index));
  options.forEach(option => assert.equal(option.disabled, false));
  assert.equal(options[0].className.includes('is-incomplete'), false);
  assert.equal(options[0].children[2].textContent, '可以查询');
  assert.equal(options[1].className.includes('is-pending'), true);
  assert.equal(options[1].children[2].textContent, '请确认航班号');
  assert.match(options[0].children[1].textContent, /2026-08-04/);
  assert.match(options[2].children[1].textContent, /2026-09-05/);
});

test('uses exact conflict review markers without disabling the segment', () => {
  const normalized = api.normalizeDocumentResult(clone(fixtures.mixedSegments));
  installSegmentDisplayFixture(['segments[1].conflict.arrival_airport']);

  const option = api.createSegmentDisplay(normalized.segments[1], 1);

  assert.equal(api.documentSegmentHasConflict(1), true);
  assert.equal(option.disabled, false);
  assert.equal(option.className.includes('is-conflict'), true);
  assert.equal(option.children[2].textContent, '确认这一段');
});

test('conflict labeling takes precedence when a segment is also incomplete', () => {
  const normalized = api.normalizeDocumentResult(clone(fixtures.mixedSegments));
  installSegmentDisplayFixture(['segments[0].conflict.departure_airport']);

  const option = api.createSegmentDisplay(normalized.segments[0], 0);

  assert.equal(option.disabled, false);
  assert.equal(option.className.includes('is-incomplete'), true);
  assert.equal(option.className.includes('is-conflict'), true);
  assert.equal(option.children[2].textContent, '确认这一段');
});

test('validates month-day but contains no client-side year completion', () => {
  assert.equal(api.validDocumentMonthDay('08-04'), true);
  assert.equal(api.validDocumentMonthDay('02-29'), true);
  assert.equal(api.validDocumentMonthDay('02-30'), false);
  assert.equal(app.includes('combineDocumentDepartureYear'), false);
  assert.equal(app.includes('year-input'), false);
  assert.equal(indexHtml.includes('name="departureYear"'), false);
});

test('a missing full date exposes the full date control and never a separate year control', () => {
  const renderFlow = sourceBetween('  function renderManualDocumentEntry()', '  function syncUsableDocumentSegmentIndexes()');

  assert.match(renderFlow,
    /setManualFieldState\(dateField, document\.getElementById\('date-input'\), !hasDepartureDate \|\| dateConflict\)/);
  assert.doesNotMatch(renderFlow, /yearField|year-input|Departure year|出发年份/);
  assert.match(indexHtml, /id="date-input" name="departureDate" type="date"/);
  assert.doesNotMatch(indexHtml, /year-input|departureYear|data-manual-year/);
});

test('completes only the selected segment and preserves readiness of every other segment', () => {
  const normalized = api.normalizeDocumentResult(clone(fixtures.mixedSegments));
  const untouched = JSON.stringify(normalized.segments[1]);
  sandbox.documentSegments = normalized.segments;
  sandbox.usableDocumentSegmentIndexes = [1];
  sandbox.confirmedDocumentFlightNumberIndexes = [];
  sandbox.documentRootReviewFields = ['segments[0].departure_date'];
  sandbox.pendingDocument = Object.assign({}, normalized, { segments: normalized.segments.slice() });

  const completed = plain(api.applyDocumentSegmentCompletion(0, 'SHOULD-NOT-REPLACE', '2026-08-04'));

  assert.equal(completed.flightNumber, 'KL1036');
  assert.equal(completed.departureDate, '2026-08-04');
  assert.equal(completed.departureMonthDay, '08-04');
  assert.equal(JSON.stringify(sandbox.documentSegments[1]), untouched);
  assert.deepEqual(plain(sandbox.usableDocumentSegmentIndexes), [0, 1]);
  assert.deepEqual(plain(sandbox.documentRootReviewFields), []);
  assert.equal(sandbox.pendingDocument.segments.length, 2);
});

test('refuses a completed date that disagrees with the recognized month-day', () => {
  const normalized = api.normalizeDocumentResult(clone(fixtures.mixedSegments));
  sandbox.documentSegments = normalized.segments;
  sandbox.usableDocumentSegmentIndexes = [1];
  sandbox.confirmedDocumentFlightNumberIndexes = [];
  sandbox.documentRootReviewFields = ['segments[0].departure_date'];
  sandbox.pendingDocument = Object.assign({}, normalized, { segments: normalized.segments.slice() });

  assert.equal(api.applyDocumentSegmentCompletion(0, 'KL1036', '2026-08-05'), null);
  assert.equal(sandbox.documentSegments[0].departureDate, null);
  assert.deepEqual(plain(sandbox.usableDocumentSegmentIndexes), [1]);
});

test('selected result carries month-day, all segments, and only the selected conflict markers', () => {
  const normalized = api.normalizeDocumentResult(clone(fixtures.multiSegment));
  sandbox.documentSegments = normalized.segments;
  sandbox.documentRootFields = {};
  sandbox.documentRootReviewFields = [
    'spatial_review_unavailable',
    'segments[0].departure_date',
    'segments[2].conflict.departure_airport'
  ];

  const selected = plain(api.selectedDocumentResult(normalized, 2));

  assert.equal(selected.departureMonthDay, '09-05');
  assert.equal(selected.flightNumberConfidence, 'HIGH');
  assert.equal(selected.dateSource, 'CURRENT_YEAR_FROM_MONTH_DAY');
  assert.deepEqual(selected.segments.map(segment => segment.flightNumber),
    ['KL1036', 'MF812', 'MF825', 'AF1668']);
  assert.deepEqual(selected.needsReviewFields,
    ['spatial_review_unavailable', 'conflict.departure_airport']);
});

test('medium confidence is prefilled but cannot become query-ready until the user confirms and saves', () => {
  const normalized = api.normalizeDocumentResult(clone(fixtures.multiSegment));
  sandbox.documentSegments = normalized.segments;
  sandbox.documentRootReviewFields = ['segments[1].flight_number'];
  sandbox.confirmedDocumentFlightNumberIndexes = [];
  sandbox.usableDocumentSegmentIndexes = [0, 2];
  sandbox.pendingDocument = Object.assign({}, normalized, { segments: normalized.segments.slice() });

  assert.equal(api.documentSegmentState(sandbox.documentSegments[1], 1), 'NEEDS_CONFIRMATION');
  assert.equal(api.documentSegmentLookupReady(sandbox.documentSegments[1], 1), false);
  assert.equal(api.applyDocumentSegmentCompletion(1, 'MF812', '2026-08-04'), null);

  const completed = plain(api.applyDocumentSegmentCompletion(1, 'MF812', '2026-08-04', {
    flightNumber: true
  }));
  assert.equal(completed.flightNumberConfirmedByUser, true);
  assert.equal(completed.requiresFlightNumberConfirmation, false);
  assert.equal(api.documentSegmentLookupReady(sandbox.documentSegments[1], 1), true);
});

test('query guard requires trusted or user-confirmed number, valid full date, and no critical conflict but not a route', () => {
  const normalized = api.normalizeDocumentResult(clone(fixtures.multiSegment));
  sandbox.documentSegments = normalized.segments;
  sandbox.documentRootReviewFields = [];
  sandbox.confirmedDocumentFlightNumberIndexes = [];
  sandbox.selectedDocumentSegmentIndex = 1;
  sandbox.currentLocale = 'en';
  sandbox.localeText = (zh, en) => en;
  const documentResult = clone(fixtures.multiSegment);

  assert.match(api.lookupGuardReason('MF812', '2026-08-04', false, documentResult), /Confirm/);
  sandbox.documentSegments[1].flightNumberConfirmedByUser = true;
  sandbox.documentSegments[1].requiresFlightNumberConfirmation = false;
  delete sandbox.documentSegments[1].departureAirport;
  delete sandbox.documentSegments[1].arrivalAirport;
  assert.equal(api.lookupGuardReason('MF812', '2026-08-04', false, documentResult), null);
  sandbox.documentRootReviewFields = ['segments[1].conflict.departure_date'];
  assert.match(api.lookupGuardReason('MF812', '2026-08-04', false, documentResult), /Resolve/);
  assert.match(api.lookupGuardReason('MF812', '2026-02-30', false, documentResult), /complete/);
});

test('busy state is temporary and never permanently disables an incomplete segment', () => {
  const segmentButton = { dataset: { action: 'select-segment', segmentIndex: '0' }, disabled: true };
  const main = { setAttribute(name, value) { this[name] = value; } };
  sandbox.document = {
    querySelectorAll() { return [segmentButton]; },
    getElementById() { return main; }
  };
  sandbox.awaitingDocumentSelection = true;
  sandbox.currentFlight = null;
  sandbox.conflictsConfirmed = false;

  api.setLookupBusy(false);
  assert.equal(segmentButton.disabled, false);
  api.setLookupBusy(true);
  assert.equal(segmentButton.disabled, true);
  api.setLookupBusy(false);
  assert.equal(segmentButton.disabled, false);
  assert.equal(main['aria-busy'], 'false');
});

test('help question icons use dark gold on light surfaces and scoped gold on dark controls', () => {
  assert.match(styles, /--icon-unsure:#9A6B00/);
  assert.match(styles, /--icon-unsure-on-dark:#D0A13A/);
  assert.match(styles, /\.staff-icon-question\{color:var\(--icon-unsure\);stroke:var\(--icon-unsure\)\}/);
  assert.doesNotMatch(styles,
    /(?:^|})\.staff-help-button \.staff-icon-question\{color:var\(--icon-unsure-on-dark\)/);
  assert.match(styles,
    /\.button-primary\.staff-help-button \.staff-icon-question,\.step-assistance-actions \.staff-help-button \.staff-icon-question\{color:var\(--icon-unsure-on-dark\);stroke:var\(--icon-unsure-on-dark\)\}/);
});

test('upload, selection, and local completion contain no live lookup call', () => {
  const uploadFlow = sourceBetween('  function uploadDocument(file)', '  function beginFlight(flight, documentResult)');
  const selectionFlow = sourceBetween('  function selectDocumentSegment(index)', '  function uploadDocument(file)');
  const completionFlow = sourceBetween('  function applyDocumentSegmentCompletion(index, flightNumber, departureDate, confirmations)', '  function setDocumentExtractionState(result, assessment)');

  assert.match(uploadFlow, /fetchJson\('\/api\/documents\/travel-document'/);
  for (const flow of [uploadFlow, selectionFlow, completionFlow]) {
    assert.equal(flow.includes("postFlightJson('/api/journeys/resolve-flight'"), false);
    assert.equal(flow.includes('startFlightLookup('), false);
    assert.equal(flow.includes('resolveUploadedFlight('), false);
  }
  assert.equal(app.includes('discoverMissingDocumentDates'), false);
  assert.equal(app.includes('/api/journeys/discover-flight-date'), false);
});

test('an explicit query payload targets only the currently selected segment', () => {
  sandbox.selectedDocumentSegmentIndex = 2;
  const documentResult = clone(fixtures.multiSegment);

  const payload = plain(api.resolvePayload('MF825', '2026-09-05', false, documentResult, null, false));

  assert.equal(payload.flightNumber, 'MF825');
  assert.equal(payload.departureDate, '2026-09-05');
  assert.equal(payload.documentSegmentIndex, 2);
  assert.equal(payload.document.segments.length, 4);
});

test('preserves an explicit valid date and rejects an impossible ISO date-time', () => {
  const normalized = plain(api.normalizeDocumentResult({
    segments: [
      {
        flightNumber: 'KL1036',
        departureDate: '2026-08-03',
        departureMonthDay: '09-05',
        scheduledDeparture: '2026-08-04T17:30'
      },
      {
        flightNumber: 'MF812',
        departureDate: null,
        departureMonthDay: '02-30',
        scheduledDeparture: '2026-02-30T21:40'
      },
      {
        flightNumber: 'MF825',
        departureDate: null,
        scheduledDeparture: '2026-09-05T00:10:00+08:00'
      }
    ]
  }));

  assert.equal(normalized.segments[0].departureDate, '2026-08-03');
  assert.equal(normalized.segments[0].departureMonthDay, '08-03');
  assert.equal(normalized.segments[1].departureDate, null);
  assert.equal(normalized.segments[1].departureMonthDay, null);
  assert.equal(normalized.segments[2].departureDate, '2026-09-05');
  assert.equal(normalized.segments[2].departureMonthDay, '09-05');
});
