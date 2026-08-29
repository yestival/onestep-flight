import test from 'node:test';
import assert from 'node:assert/strict';

import { evaluateRecord, generateReport } from '../../../scripts/generate-airport-resources-report.mjs';

const codes = ['LHR', 'JFK', 'CDG', 'AMS', 'XMN', 'HND', 'DXB'];

function truth() {
  return { primaryPhoneE164: '+17182444444', alternativePhones: [], phoneRole: 'General support',
    contactUrls: ['https://airport.example/contact'], mapUrls: ['https://airport.example/maps'],
    sources: [{ kind: 'CONTACT', title: 'Official contact', url: 'https://airport.example/contact', evidence: 'Passenger page' }] };
}

function record(overrides = {}) {
  return { completed: true, elapsedMs: 25, stages: [{ sequence: 1, stage: 'IDENTITY_GOOGLE_MAPS',
    query: 'canonical query', httpStatus: 200, serpApiStatus: 'Success', candidates: [{
      jsonPath: '$.place_results', title: 'Airport', url: 'https://airport.example/maps' }] }],
  response: { identityStatus: 'VERIFIED', serpApiRequestCount: 3, fromCache: false, rejectionReasons: [],
    contact: { status: 'PHONE_FOUND', phoneDisplay: '+1 718 244 4444', telUri: 'tel:+17182444444',
      contactUrl: 'https://airport.example/contact' },
    map: { status: 'FOUND', resultType: 'DIRECT_INTERACTIVE_MAP', url: 'https://airport.example/maps',
      rejectedCandidateTypes: [] }, ...overrides } };
}

test('verdict is computed from golden data rather than hard-coded airport counts', () => {
  assert.equal(evaluateRecord(record(), truth()).verdict, '准确');
  const wrong = record({ contact: { phoneDisplay: '+1 999 555 0100', telUri: 'tel:+19995550100' } });
  assert.equal(evaluateRecord(wrong, truth()).verdict, '错误');
  const incomplete = { ...record(), completed: false };
  assert.equal(evaluateRecord(incomplete, truth()).verdict, '无法核实');
});

test('records without DecisionTrace are labelled unknown and never inferred from final URL', () => {
  const records = Object.fromEntries(codes.map((code) => [code, { ...record(), airportCode: code }]));
  const golden = { verifiedAt: '2026-08-29T00:00:00Z', airports:
    Object.fromEntries(codes.map((code) => [code, truth()])) };
  const { report } = generateReport(records, golden);
  assert.match(report, /未记录生产决策；报告不会依据最终 URL 或字符串位置猜测采用\/拒绝/);
  assert.match(report, /见下方真实 DecisionTrace；此表不推断/);
  assert.match(report, /本报告仍按已保存的唯一一次 live 响应判定/);
  assert.doesNotMatch(report, /采用：进入最终|function decision/);
});
