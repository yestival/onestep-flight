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
  sourceBetween('  function offsetMinutesFromDateTime(value)', '  function arrivalOffsetMinutes()'),
  sourceBetween('  function formatAtOffset(value, includeDate, offsetMinutes)', '  function formatAtDeparture(value, includeDate)'),
  sourceBetween('  function setLookupBusy(busy)', '  function announce(message)'),
  sourceBetween('  function resolvePayload(flightNumber, departureDate, demo, documentResult, demoScenario, refresh)', '  function extractionToFlight(result)'),
  sourceBetween('  function selectedDocumentResult(source, index)', '  function renderSegmentSelector()'),
  sourceBetween('  function validDocumentFlightNumber(value)', '  function resetDocumentResultState()'),
  sourceBetween('  function syncUsableDocumentSegmentIndexes()', '  function setDocumentExtractionState(result, assessment)'),
  sourceBetween('  function isLocalDebugEnvironment()', '  function safeProviderDebugResult(result)'),
  `this.documentSegmentApi = {
    normalizeFlightNumber, normalizeDocumentResult, classifyDocumentResult, selectedDocumentResult, createSegmentDisplay,
    offsetMinutesFromDateTime, formatAtOffset, formatDocumentSegmentDate,
    formatDocumentSegmentTime, formatDocumentSegmentDateTime,
    validDocumentDepartureDate, validDocumentMonthDay, normalizeFlightNumberConfidence,
    documentSegmentLookupReady, documentSegmentHasConflict, documentSegmentHasCriticalConflict,
    documentSegmentHasAdvisory, documentSegmentReviewIssues, normalizeReviewIssue,
    documentSegmentState, documentItineraryState, lookupGuardReason, applyDocumentSegmentCompletion,
	setLookupBusy, resolvePayload, liveFlightErrorCopy
  };`
].join('\n'), sandbox);

const api = sandbox.documentSegmentApi;
const realFormatAtOffset = api.formatAtOffset;
const realOffsetMinutesFromDateTime = api.offsetMinutesFromDateTime;

for (const locale of ['zh', 'en', 'es']) {
  test(`provider failures show only friendly ${locale} copy and clear previously visible diagnostics`, () => {
    const diagnostic = 'HTTP status: 502 ALL_LIVE_FLIGHT_PROVIDERS_FAILED AERODATABOX_RESPONSE_NOT_ARRAY SERPAPI_ROUTE_INCOMPLETE';
    const friendly = api.liveFlightErrorCopy({ status: 502, requestUrl: '/api/journeys/resolve-flight' }, {}, 'ALL_LIVE_FLIGHT_PROVIDERS_FAILED');
    for (const origin of ['http://localhost:8080', 'capacitor://localhost', 'https://onestep-flight.onrender.com']) {
      const title = { textContent: '' };
      const message = { textContent: '' };
      const details = { hidden: false, open: true };
      const content = { textContent: diagnostic };
      const nodes = { '[data-error-message]': message, '[data-error-technical]': details, '[data-error-technical-content]': content };
      const page = vm.createContext({ currentLocale: locale, currentError: { ...friendly, technicalLines: [diagnostic] },
        window: { location: new URL(origin) },
        document: { getElementById: () => title, querySelector: selector => nodes[selector] } });
      vm.runInContext(sourceBetween('  function errorTitleForLocale(errorState)', '  function showError(error, retry)'), page);
      page.renderErrorMessage();
      assert.equal(title.textContent, friendly['title' + locale[0].toUpperCase() + locale.slice(1)]);
      assert.equal(message.textContent, friendly[locale]);
      assert.equal(details.hidden, true, origin);
      assert.equal(details.open, false, origin);
      assert.equal(content.textContent, '', origin);
      assert.doesNotMatch(title.textContent + message.textContent, /HTTP|AERODATABOX|SERPAPI|502/);
    }
  });
}

sandbox.confirmedDocumentFlightNumberIndexes = [];
sandbox.documentRootReviewFields = [];
sandbox.documentRootReviewIssues = [];
sandbox.documentSegments = [];
sandbox.currentLocale = 'en';
sandbox.localeText = (zh, en) => en;

test('top-level live-flight error code takes precedence over provider details', () => {
	const request = { requestUrl: '/api/flights/BA117?departureDate=2026-08-25', status: 502 };
	const details = { providerResults: {
		SERPAPI: { outcome: 'TIMEOUT' },
		AERODATABOX: { outcome: 'PARSE_ERROR' }
	} };
	const mixed = api.liveFlightErrorCopy(request, details, 'ALL_LIVE_FLIGHT_PROVIDERS_FAILED');
	assert.equal(mixed.titleZh, '航班信息暂时不可用');
	assert.equal(mixed.en, 'Try again later or ask staff for help.');
	assert.doesNotMatch(mixed.en, /timed out/i);

	const timeout = api.liveFlightErrorCopy(request, details, 'LIVE_FLIGHT_TIMEOUT');
	assert.equal(timeout.titleEn, 'Search timed out');
	assert.equal(timeout.en, 'No result yet. Try again later.');

	const missing = api.liveFlightErrorCopy(
		{ requestUrl: '/api/flights/BA117', status: 404 }, details, 'FLIGHT_NOT_FOUND');
	assert.equal(missing.titleZh, '没有找到这个航班');
	assert.equal(missing.es, 'Comprueba el número de vuelo y la fecha.');

	const missingWithoutCode = api.liveFlightErrorCopy(
		{ requestUrl: '/api/flights/BA117', status: 404 }, details, 'REQUEST_FAILED');
	assert.equal(missingWithoutCode.titleEn, 'Flight not found');
});

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
  sandbox.documentRootReviewIssues = [];
  sandbox.selectedDocumentSegmentIndex = null;
  sandbox.lookupInFlight = false;
  sandbox.confirmedDocumentFlightNumberIndexes = [];
  sandbox.currentLocale = 'zh';
  sandbox.formatFlightNumber = String;
  sandbox.formatDate = String;
  sandbox.offsetMinutesFromDateTime = () => 0;
  sandbox.formatAtOffset = (value, includeDate) => includeDate
    ? String(value).slice(0, 10) + ' · ' + String(value).slice(11, 16)
    : String(value).slice(11, 16);
  sandbox.t = function (key) {
    return {
      incompleteSegment: '补全这一段',
      pendingSegment: '请确认航班号',
      confirmSegment: '处理字段冲突',
      selectedSegment: '当前航段',
      chooseSegment: '选择这一段',
      readySegment: '可以查询',
      advisorySegment: '请确认'
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

test('formatAtOffset formats a scheduled document departure without a current flight', () => {
  const scheduledDeparture = '2026-08-04T17:30:00+01:00';
  sandbox.currentFlight = null;
  sandbox.currentLocale = 'zh';
  let formatted;

  assert.doesNotThrow(() => {
    formatted = realFormatAtOffset(
      scheduledDeparture,
      true,
      realOffsetMinutesFromDateTime(scheduledDeparture)
    );
  });
  assert.equal(typeof formatted, 'string');
  assert.ok(formatted.trim());
  assert.doesNotMatch(formatted, /undefined|NaN/);
});

test('a scheduled document segment uses the real formatter and remains selectable without a current flight', () => {
  const segment = {
    flightNumber: 'KL1036',
    departureAirport: 'MAN',
    arrivalAirport: 'AMS',
    departureDate: '2026-08-04',
    scheduledDeparture: '2026-08-04T17:30:00+01:00'
  };
  installSegmentDisplayFixture();
  sandbox.currentFlight = null;
  sandbox.formatAtOffset = realFormatAtOffset;
  sandbox.offsetMinutesFromDateTime = realOffsetMinutesFromDateTime;
  let option;

  assert.doesNotThrow(() => {
    option = api.createSegmentDisplay(segment, 0);
  });
  assert.ok(option);
  assert.equal(option.type, 'button');
  assert.equal(option.dataset.action, 'select-segment');
  assert.equal(option.dataset.segmentIndex, '0');
  assert.equal(option.disabled, false);
  assert.ok(option.children[1].textContent.trim());
  assert.notEqual(option.children[1].textContent.trim(), '—');
  assert.match(option.children[1].textContent, /2026/);
  assert.match(option.children[1].textContent, /17:30/);
  assert.doesNotMatch(option.children[1].textContent, /undefined|NaN/);
});

test('segment cards keep departureDate authoritative and use scheduledDeparture only for time', () => {
  installSegmentDisplayFixture();
  const mismatchedDate = {
    flightNumber: 'KL1036', departureAirport: 'MAN', arrivalAirport: 'AMS',
    departureDate: '2026-08-30', scheduledDeparture: '2026-08-31T21:40:00+02:00'
  };

  const option = api.createSegmentDisplay(mismatchedDate, 0);
  const payload = plain(api.resolvePayload('KL1036', mismatchedDate.departureDate,
    false, { segments: [mismatchedDate] }, null, false));

  assert.equal(option.children[1].textContent, '2026-08-30 · 21:40');
  assert.doesNotMatch(option.children[1].textContent, /2026-08-31/);
  assert.equal(payload.departureDate, '2026-08-30');
});

test('segment cards retain HH:mm and HH:mm:ss time-only values', () => {
  installSegmentDisplayFixture();

  assert.equal(api.formatDocumentSegmentDateTime({
    departureDate: '2026-08-30', scheduledDeparture: '21:40'
  }), '2026-08-30 · 21:40');
  assert.equal(api.formatDocumentSegmentDateTime({
    departureDate: '2026-08-30', scheduledDeparture: '17:30:00'
  }), '2026-08-30 · 17:30');
  assert.equal(api.formatDocumentSegmentDateTime({
    departureDate: '2026-08-30'
  }), '2026-08-30');
});

test('formatAtOffset does not read currentFlight.timeContext without a null guard', () => {
  const formatterSource = sourceBetween(
    '  function formatAtOffset(value, includeDate, offsetMinutes)',
    '  function formatAtDeparture(value, includeDate)'
  );

  assert.doesNotMatch(formatterSource, /!currentFlight\.timeContext/);
  assert.match(formatterSource,
    /Boolean\(currentFlight && currentFlight\.timeContext\)/);
});

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

test('preserves every server-completed segment and lets medium single candidates be searched without confirmation', () => {
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
  assert.equal(assessment.outcome, 'USABLE');
  assert.deepEqual(assessment.usableIndexes, [0, 1, 2, 3]);

  installSegmentDisplayFixture();
  const options = normalized.segments.map((segment, index) => api.createSegmentDisplay(segment, index));
  options.forEach(option => assert.equal(option.disabled, false));
  assert.equal(options[0].className.includes('is-incomplete'), false);
  assert.equal(options[0].children[2].textContent, '可以查询');
  assert.equal(options[1].className.includes('is-advisory'), false);
  assert.equal(options[1].children[2].textContent, '可以查询');
  assert.match(options[0].children[1].textContent, /2026-08-04/);
  assert.match(options[2].children[1].textContent, /2026-09-05/);
});

test('legacy auxiliary conflicts never change the segment card status', () => {
  const normalized = api.normalizeDocumentResult(clone(fixtures.mixedSegments));
  installSegmentDisplayFixture(['segments[1].conflict.arrival_airport']);

  const option = api.createSegmentDisplay(normalized.segments[1], 1);

  assert.equal(api.documentSegmentHasConflict(1), false);
  assert.equal(api.documentSegmentHasAdvisory(1, normalized.segments[1]), false);
  assert.equal(option.disabled, false);
  assert.equal(option.className.includes('is-conflict'), false);
  assert.equal(option.className.includes('is-advisory'), false);
  assert.equal(option.children[2].textContent, '可以查询');
});

test('an auxiliary conflict does not replace the missing-key-field action', () => {
  const normalized = api.normalizeDocumentResult(clone(fixtures.mixedSegments));
  installSegmentDisplayFixture(['segments[0].conflict.departure_airport']);

  const option = api.createSegmentDisplay(normalized.segments[0], 0);

  assert.equal(option.disabled, false);
  assert.equal(option.className.includes('is-incomplete'), true);
  assert.equal(option.className.includes('is-conflict'), false);
  assert.equal(option.children[2].textContent, '补全这一段');
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

  assert.match(renderFlow, /var dateNeedsInput = !hasDepartureDate \|\| dateConflict \|\| dateReview/);
  assert.match(renderFlow, /setManualFieldState\(dateField, document\.getElementById\('date-input'\),\s*dateNeedsInput && !dateCandidateMode, dateNeedsInput\)/);
  assert.doesNotMatch(renderFlow, /yearField|year-input|Departure year|出发年份/);
  assert.match(indexHtml, /id="date-input" name="departureDate" type="date"/);
  assert.doesNotMatch(indexHtml, /year-input|departureYear|data-manual-year/);
});

test('one document help entry covers flight date and combined problems', () => {
  const renderFlow = sourceBetween('  function renderManualDocumentEntry()', '  function syncUsableDocumentSegmentIndexes()');
  const helpFlow = sourceBetween('  function manualDocumentProblemFields(', '  function documentQueryTargetLabel(');

  assert.match(renderFlow, /var help = document\.querySelector\('\[data-manual-document-help\]'\)/);
  assert.match(helpFlow, /flight_information/);
  assert.match(helpFlow, /flight_number/);
  assert.match(helpFlow, /departure_date/);
  assert.doesNotMatch(app, /function renderFieldStaffHelp\(/);
  assert.doesNotMatch(app, /dataset\.action = 'field-staff'/);
});

test('ordinary candidate cards render each normalized value once without audit details', () => {
  const candidateFlow = sourceBetween('  function renderManualReviewCandidates(',
    '  function selectDocumentReviewCandidate(');

  assert.match(app, /if \(!candidate \|\| seen\[candidate\.value\]\) return false/);
  assert.match(candidateFlow, /button\.dataset\.candidateValue = candidate\.value/);
  assert.match(candidateFlow, /formatFlightNumber\(candidate\.value\)/);
  assert.doesNotMatch(candidateFlow, /reviewCandidateDetails|candidate\.source|candidate\.confidence|Math\.round/);
  assert.doesNotMatch(candidateFlow, /STRUCTURE|UNDERSTAND|TEXT ·|%/);
});

test('completes only the selected segment and preserves readiness of every other segment', () => {
  const normalized = api.normalizeDocumentResult(clone(fixtures.mixedSegments));
  const untouched = JSON.stringify(normalized.segments[1]);
  sandbox.documentSegments = normalized.segments;
  sandbox.usableDocumentSegmentIndexes = [1];
  sandbox.confirmedDocumentFlightNumberIndexes = [];
  sandbox.documentRootReviewIssues = [];
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
  sandbox.documentRootReviewIssues = [];
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
  sandbox.documentRootReviewIssues = [];
  sandbox.documentRootReviewFields = [
    'spatial_review_unavailable',
    'segments[0].departure_date',
    'segments[2].conflict.departure_airport'
  ];
  sandbox.documentRootReviewIssues = [
    api.normalizeReviewIssue({
      segmentIndex: 0, field: 'arrival_airport', kind: 'ADVISORY', blocking: false,
      candidates: [{ normalizedValue: 'AMS', source: 'UNDERSTAND', confidence: 0.8 }]
    }),
    api.normalizeReviewIssue({
      segmentIndex: 2, field: 'arrival_airport', kind: 'ADVISORY', blocking: false,
      candidates: [{ normalizedValue: 'CDG', source: 'STRUCTURE', confidence: 0.7 }]
    })
  ];

  const selected = plain(api.selectedDocumentResult(normalized, 2));

  assert.equal(selected.departureMonthDay, '09-05');
  assert.equal(selected.flightNumberConfidence, 'HIGH');
  assert.equal(selected.dateSource, 'CURRENT_YEAR_FROM_MONTH_DAY');
  assert.deepEqual(selected.segments.map(segment => segment.flightNumber),
    ['KL1036', 'MF812', 'MF825', 'AF1668']);
  assert.deepEqual(selected.needsReviewFields,
    ['spatial_review_unavailable', 'conflict.departure_airport']);
  assert.equal(selected.reviewIssues.length, 1);
  assert.equal(selected.reviewIssues[0].segmentIndex, 2);
  assert.equal(selected.reviewIssues[0].field, 'arrival_airport');
});

test('a single medium-confidence flight number is immediately query-ready without confirmation', () => {
  const normalized = api.normalizeDocumentResult(clone(fixtures.multiSegment));
  sandbox.documentSegments = normalized.segments;
  sandbox.documentRootReviewFields = ['segments[1].flight_number'];
  sandbox.documentRootReviewIssues = [];
  sandbox.confirmedDocumentFlightNumberIndexes = [];
  sandbox.usableDocumentSegmentIndexes = [0, 2];
  sandbox.pendingDocument = Object.assign({}, normalized, { segments: normalized.segments.slice() });

  assert.equal(api.documentSegmentState(sandbox.documentSegments[1], 1), 'QUERYABLE');
  assert.equal(api.documentSegmentLookupReady(sandbox.documentSegments[1], 1), true);
  assert.equal(api.documentSegmentHasAdvisory(1, sandbox.documentSegments[1]), false);
});

test('medium confidence and auxiliary review still render as queryable', () => {
  const normalized = api.normalizeDocumentResult({
    reviewIssues: [{
      segmentIndex: 0,
      field: 'departure_airport',
      kind: 'ADVISORY',
      blocking: false,
      candidates: [{ normalizedValue: 'AAA', source: 'STRUCTURE', confidence: 0.62 }],
      reason: 'AUXILIARY_EVIDENCE_DISAGREEMENT'
    }],
    segments: [{
      flightNumber: 'XY123', flightNumberConfidence: 'MEDIUM', departureDate: '2026-08-04',
      departureAirport: 'AAA', scheduledDeparture: '09:25'
    }]
  });
  sandbox.documentSegments = normalized.segments;
  installSegmentDisplayFixture();
  sandbox.documentRootReviewIssues = normalized.reviewIssues;

  const option = api.createSegmentDisplay(normalized.segments[0], 0);

  assert.equal(normalized.segments[0].requiresFlightNumberConfirmation, false);
  assert.equal(api.documentSegmentHasAdvisory(0, normalized.segments[0]), false);
  assert.equal(option.className.includes('is-advisory'), false);
  assert.equal(option.children[2].textContent, '可以查询');
});

test('low flight confidence and a single departure-date review still require confirmation', () => {
  const low = api.normalizeDocumentResult({
    segments: [{
      flightNumber: 'XY123', flightNumberConfidence: 'LOW', departureDate: '2026-08-04'
    }]
  });
  sandbox.documentSegments = low.segments;
  installSegmentDisplayFixture();
  let option = api.createSegmentDisplay(low.segments[0], 0);
  assert.equal(api.documentSegmentHasAdvisory(0, low.segments[0]), true);
  assert.equal(option.children[2].textContent, '请确认');

  const dateReview = api.normalizeDocumentResult({
    reviewIssues: [{
      segmentIndex: 0,
      field: 'departure_date',
      kind: 'ADVISORY',
      blocking: false,
      candidates: [{ normalizedValue: '2026-08-04', source: 'UNDERSTAND', confidence: 0.55 }],
      selectedValue: null,
      reason: 'SINGLE_CANDIDATE_REVIEW'
    }],
    segments: [{
      flightNumber: 'XY123', flightNumberConfidence: 'HIGH', departureDate: '2026-08-04'
    }]
  });
  sandbox.documentSegments = dateReview.segments;
  installSegmentDisplayFixture();
  sandbox.documentRootReviewIssues = dateReview.reviewIssues;
  option = api.createSegmentDisplay(dateReview.segments[0], 0);
  assert.equal(api.documentSegmentHasAdvisory(0, dateReview.segments[0]), true);
  assert.equal(option.children[2].textContent, '请确认');
});

test('blocking flight-number or departure-date alternatives render as field conflicts', () => {
  for (const field of ['flight_number', 'departure_date']) {
    const normalized = api.normalizeDocumentResult({
      reviewIssues: [{
        segmentIndex: 0,
        field,
        kind: 'ACTIONABLE',
        blocking: true,
        candidates: [
          { normalizedValue: field === 'flight_number' ? 'XY123' : '2026-08-04' },
          { normalizedValue: field === 'flight_number' ? 'XW123' : '2026-08-05' }
        ],
        selectedValue: null,
        reason: 'MULTIPLE_UNRESOLVED_CANDIDATES'
      }],
      segments: [{
        flightNumber: 'XY123', flightNumberConfidence: 'HIGH', departureDate: '2026-08-04'
      }]
    });
    sandbox.documentSegments = normalized.segments;
    installSegmentDisplayFixture();
    sandbox.documentRootReviewIssues = normalized.reviewIssues;
    const option = api.createSegmentDisplay(normalized.segments[0], 0);
    assert.equal(api.documentSegmentState(normalized.segments[0], 0), 'CONFLICT');
    assert.equal(option.children[2].textContent, '处理字段冲突');
  }
});

test('query guard requires format, full valid date, and no unresolved critical conflict but not trust or route', () => {
  const normalized = api.normalizeDocumentResult(clone(fixtures.multiSegment));
  sandbox.documentSegments = normalized.segments;
  sandbox.documentRootReviewFields = [];
  sandbox.documentRootReviewIssues = [];
  sandbox.confirmedDocumentFlightNumberIndexes = [];
  sandbox.selectedDocumentSegmentIndex = 1;
  sandbox.currentLocale = 'en';
  sandbox.localeText = (zh, en) => en;
  const documentResult = clone(fixtures.multiSegment);

  assert.equal(api.lookupGuardReason('MF812', '2026-08-04', false, documentResult), null);
  delete sandbox.documentSegments[1].departureAirport;
  delete sandbox.documentSegments[1].arrivalAirport;
  assert.equal(api.lookupGuardReason('MF812', '2026-08-04', false, documentResult), null);
  sandbox.documentRootReviewFields = ['segments[1].conflict.departure_date'];
  documentResult.needsReviewFields = ['segments[1].conflict.departure_date'];
  assert.match(api.lookupGuardReason('MF812', '2026-08-04', false, documentResult), /Resolve/);
  assert.match(api.lookupGuardReason('MF812', '2026-02-30', false, documentResult), /complete/);
});

test('a single low-confidence candidate remains queryable and is marked advisory', () => {
  const normalized = api.normalizeDocumentResult({
    needsReviewFields: ['segments[0].flight_number'],
    reviewIssues: [{
      segmentIndex: 0,
      field: 'flight_number',
      kind: 'ADVISORY',
      blocking: false,
      candidates: [{ normalizedValue: 'QF12', source: 'STRUCTURE', confidence: 0.41 }],
      reason: 'LOW_CONFIDENCE'
    }],
    segments: [{
      flightNumber: 'QF12', flightNumberConfidence: 'LOW', departureDate: '2027-03-14',
      departureMonthDay: '03-14', dateSource: 'EXPLICIT_FULL_DATE'
    }]
  });
  sandbox.documentSegments = normalized.segments;
  sandbox.documentRootReviewFields = normalized.needsReviewFields;
  sandbox.documentRootReviewIssues = normalized.reviewIssues;

  assert.equal(api.documentSegmentLookupReady(normalized.segments[0], 0), true);
  assert.equal(api.documentSegmentState(normalized.segments[0], 0), 'QUERYABLE');
  assert.equal(api.documentSegmentHasAdvisory(0, normalized.segments[0]), true);
  assert.equal(plain(api.classifyDocumentResult(normalized)).outcome, 'USABLE');
});

test('two unresolved critical candidates block only their segment until one is selected', () => {
  const result = {
    needsReviewFields: [],
    reviewIssues: [{
      segmentIndex: 0,
      field: 'flight_number',
      kind: 'ACTIONABLE',
      blocking: true,
      candidates: [
        { normalizedValue: 'BA117', source: 'UNDERSTAND', confidence: 0.77 },
        { normalizedValue: 'BA171', source: 'STRUCTURE', confidence: 0.73 }
      ],
      reason: 'MULTIPLE_CANDIDATES'
    }],
    segments: [
      { flightNumber: 'BA117', flightNumberConfidence: 'MEDIUM', departureDate: '2027-03-14' },
      { flightNumber: 'LH400', flightNumberConfidence: 'LOW', departureDate: '2027-03-15' }
    ]
  };
  const normalized = api.normalizeDocumentResult(result);
  sandbox.documentSegments = normalized.segments;
  sandbox.documentRootReviewFields = [];
  sandbox.documentRootReviewIssues = normalized.reviewIssues;

  assert.equal(api.documentSegmentHasCriticalConflict(0, normalized.segments[0]), true);
  assert.equal(api.documentSegmentLookupReady(normalized.segments[0], 0), false);
  assert.equal(api.documentSegmentLookupReady(normalized.segments[1], 1), true);
  assert.deepEqual(plain(api.classifyDocumentResult(normalized)).usableIndexes, [1]);

  normalized.reviewIssues[0].selectedValue = 'BA171';
  normalized.reviewIssues[0].resolved = true;
  sandbox.documentRootReviewIssues = normalized.reviewIssues;
  assert.equal(api.documentSegmentLookupReady(normalized.segments[0], 0), true);
});

test('an unresolved server-declared critical issue blocks even when manual entry is the only fallback', () => {
  const normalized = api.normalizeDocumentResult({
    reviewIssues: [{
      segmentIndex: 0,
      field: 'departure_date',
      kind: 'ACTIONABLE',
      blocking: true,
      candidates: [],
      reason: 'MULTIPLE_CANDIDATES'
    }],
    segments: [{ flightNumber: 'BA117', flightNumberConfidence: 'HIGH', departureDate: '2027-03-14' }]
  });
  sandbox.documentSegments = normalized.segments;
  sandbox.documentRootReviewFields = [];
  sandbox.documentRootReviewIssues = normalized.reviewIssues;

  assert.equal(api.documentSegmentHasCriticalConflict(0, normalized.segments[0]), true);
  assert.equal(api.documentSegmentLookupReady(normalized.segments[0], 0), false);
});

test('auxiliary conflicts remain in review data without changing card readiness', () => {
  const normalized = api.normalizeDocumentResult({
    reviewIssues: [{
      segmentIndex: 0,
      field: 'departure_airport',
      kind: 'ADVISORY',
      blocking: false,
      candidates: [
        { normalizedValue: 'LHR', source: 'UNDERSTAND', confidence: 0.82 },
        { normalizedValue: 'LGW', source: 'STRUCTURE', confidence: 0.76 }
      ],
      reason: 'SOURCE_DIFFERENCE'
    }],
    segments: [{ flightNumber: 'BA117', flightNumberConfidence: 'HIGH', departureDate: '2027-03-14' }]
  });
  sandbox.documentSegments = normalized.segments;
  sandbox.documentRootReviewFields = [];
  sandbox.documentRootReviewIssues = normalized.reviewIssues;

  assert.equal(api.documentSegmentLookupReady(normalized.segments[0], 0), true);
  assert.equal(api.documentSegmentHasCriticalConflict(0, normalized.segments[0]), false);
  assert.equal(api.documentSegmentHasAdvisory(0, normalized.segments[0]), false);
});

test('structured selected issue suppresses a stale legacy marker for the same field', () => {
  const normalized = api.normalizeDocumentResult({
    needsReviewFields: ['segments[0].conflict.flight_number'],
    reviewIssues: [{
      segmentIndex: 0,
      field: 'flight_number',
      kind: 'ADVISORY',
      blocking: false,
      candidates: [{ normalizedValue: 'BA117', source: 'STRUCTURE', confidence: 0.8 }],
      selectedValue: 'BA117',
      reason: 'USER_SELECTED'
    }],
    segments: [{ flightNumber: 'BA117', departureDate: '2027-03-14' }]
  });
  sandbox.documentSegments = normalized.segments;
  sandbox.documentRootReviewFields = normalized.needsReviewFields;
  sandbox.documentRootReviewIssues = normalized.reviewIssues;

  assert.equal(api.documentSegmentReviewIssues(0).length, 1);
  assert.equal(api.documentSegmentHasCriticalConflict(0, normalized.segments[0]), false);
  assert.equal(api.documentSegmentLookupReady(normalized.segments[0], 0), true);
});

test('a zero-candidate actionable critical issue uses manual confirmation and saves without lookup', () => {
  const normalized = api.normalizeDocumentResult({
    needsReviewFields: [],
    reviewIssues: [{
      segmentIndex: 0,
      field: 'flight_number',
      kind: 'ACTIONABLE',
      blocking: true,
      candidates: [],
      selectedValue: null,
      reason: 'CANDIDATE_DETAILS_UNAVAILABLE'
    }],
    segments: [{
      flightNumber: 'BA117', flightNumberConfidence: 'MEDIUM', departureDate: '2027-03-14',
      departureMonthDay: '03-14', dateSource: 'EXPLICIT_FULL_DATE'
    }]
  });
  sandbox.documentSegments = normalized.segments;
  sandbox.documentRootReviewFields = [];
  sandbox.documentRootReviewIssues = normalized.reviewIssues;
  sandbox.usableDocumentSegmentIndexes = [];
  sandbox.pendingDocument = Object.assign({}, normalized, { segments: normalized.segments.slice() });

  assert.equal(api.documentSegmentHasCriticalConflict(0, normalized.segments[0]), true);
  assert.equal(api.documentSegmentLookupReady(normalized.segments[0], 0), false);
  assert.equal(api.applyDocumentSegmentCompletion(0, 'BA117', '2027-03-14', {}), null);

  const completed = plain(api.applyDocumentSegmentCompletion(0, 'BA117', '2027-03-14', {
    flightNumber: true
  }));
  assert.equal(completed.flightNumber, 'BA117');
  assert.equal(completed.flightNumberConfirmedByUser, true);
  assert.equal(api.documentSegmentLookupReady(sandbox.documentSegments[0], 0), true);
  assert.equal(sandbox.pendingDocument.reviewIssues.length, 0);

  const completionFlow = sourceBetween(
    '  function applyDocumentSegmentCompletion(index, flightNumber, departureDate, confirmations)',
    '  function setDocumentExtractionState(result, assessment)');
  assert.equal(completionFlow.includes('startFlightLookup('), false);
  assert.equal(completionFlow.includes("postFlightJson('/api/journeys/resolve-flight'"), false);
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

test('normalizes ordinary and OCR Unicode flight-number separators generically', () => {
  assert.equal(api.normalizeFlightNumber(' BA - 117 '), 'BA117');
  assert.equal(api.normalizeFlightNumber('QF‑12'), 'QF12');
  assert.equal(api.normalizeFlightNumber('G9–123'), 'G9123');
  assert.equal(api.normalizeFlightNumber('AF−1668'), 'AF1668');
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
