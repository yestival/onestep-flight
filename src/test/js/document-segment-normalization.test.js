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
  sourceBetween('  function selectedDocumentResult(source, index)', '  function renderSegmentSelector()'),
  sourceBetween('  function validDocumentFlightNumber(value)', '  function discoverMissingDocumentDates(result, assessment)'),
  'this.documentSegmentApi = { normalizeDocumentResult, classifyDocumentResult, selectedDocumentResult, createSegmentDisplay };'
].join('\n'), sandbox);

const api = sandbox.documentSegmentApi;

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

function installSegmentDisplayFixture(usableIndexes) {
  sandbox.usableDocumentSegmentIndexes = usableIndexes;
  sandbox.selectedDocumentSegmentIndex = null;
  sandbox.lookupInFlight = false;
  sandbox.formatFlightNumber = String;
  sandbox.formatDate = String;
  sandbox.t = function (key) {
    return {
      incompleteSegment: '信息不完整，不能用于查询',
      selectedSegment: '当前航段',
      chooseSegment: '选择这一段'
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

test('normalizes a single extracted segment before classifying it as usable', () => {
  const fixture = clone(fixtures.singleSegment);
  const originalJson = JSON.stringify(fixture);
  const normalized = api.normalizeDocumentResult(fixture);
  const assessment = plain(api.classifyDocumentResult(normalized));

  assert.notStrictEqual(normalized, fixture);
  assert.notStrictEqual(normalized.segments, fixture.segments);
  assert.equal(normalized.segments[0].departureDate, '2026-08-04');
  assert.equal(assessment.outcome, 'USABLE');
  assert.deepEqual(assessment.usableIndexes, [0]);
  assert.equal(JSON.stringify(fixture), originalJson);
});

test('keeps four extracted segments ordered, selectable, and local-only before selection', () => {
  const fixture = clone(fixtures.multiSegment);
  const originalJson = JSON.stringify(fixture);
  let resolverRequests = 0;
  sandbox.postFlightJson = function () {
    resolverRequests += 1;
    throw new Error('Resolver must not run while rendering document segment choices');
  };

  const normalized = api.normalizeDocumentResult(fixture);
  const assessment = plain(api.classifyDocumentResult(normalized));
  const normalizedSegments = plain(normalized.segments);

  assert.equal(normalizedSegments.length, 4);
  assert.deepEqual(normalizedSegments.map(segment => segment.flightNumber),
    ['KL1036', 'MF812', 'MF825', 'AF1668']);
  assert.deepEqual(normalizedSegments.map(segment => segment.departureDate),
    ['2026-08-04', '2026-08-04', '2026-09-05', '2026-09-05']);
  assert.deepEqual(normalizedSegments.map(segmentIdentity), fixture.segments.map(segmentIdentity));
  assert.equal(assessment.outcome, 'USABLE');
  assert.deepEqual(assessment.usableIndexes, [0, 1, 2, 3]);

  installSegmentDisplayFixture(assessment.usableIndexes);
  const options = normalized.segments.map((segment, index) => api.createSegmentDisplay(segment, index));
  options.forEach(option => {
    assert.equal(option.disabled, false);
    assert.equal(option.className.includes('is-incomplete'), false);
    assert.equal(option.children[2].textContent, '选择这一段');
    assert.notEqual(option.children[2].textContent, '信息不完整，不能用于查询');
  });
  assert.equal(resolverRequests, 0);

  sandbox.documentSegments = normalized.segments;
  sandbox.usableDocumentSegmentIndexes = assessment.usableIndexes;
  sandbox.documentRootFields = {};
  sandbox.documentRootReviewFields = [];
  const selected = plain(api.selectedDocumentResult(normalized, 2));
  assert.equal(selected.departureDate, '2026-09-05');
  assert.deepEqual(selected.segments.map(segment => segment.flightNumber),
    ['KL1036', 'MF812', 'MF825', 'AF1668']);
  assert.equal(JSON.stringify(fixture), originalJson);
});

test('preserves an explicit valid date and does not derive from an invalid date-time', () => {
  const normalized = plain(api.normalizeDocumentResult({
    segments: [
      {
        flightNumber: 'KL1036',
        departureDate: '2026-08-03',
        scheduledDeparture: '2026-08-04T17:30'
      },
      {
        flightNumber: 'MF812',
        departureDate: null,
        scheduledDeparture: '2026-02-30T21:40',
        scheduledArrival: '2026-03-01T08:20'
      },
      {
        flightNumber: 'MF825',
        departureDate: null,
        scheduledDeparture: '2026-09-05T00:10:00+08:00'
      },
      {
        flightNumber: 'AF1668',
        departureDate: '   ',
        scheduledDeparture: '2026-09-05T09:25Z'
      }
    ]
  }));

  assert.equal(normalized.segments[0].departureDate, '2026-08-03');
  assert.equal(normalized.segments[1].departureDate, null);
  assert.equal(normalized.segments[2].departureDate, '2026-09-05');
  assert.equal(normalized.segments[3].departureDate, '2026-09-05');
});
