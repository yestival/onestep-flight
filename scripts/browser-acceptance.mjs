import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const BASE_URL = process.env.ONESTEP_BASE_URL || 'http://127.0.0.1:18080';
const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Google', 'Chrome', 'Application', 'chrome.exe')
].filter(Boolean);

const REPEAT_COUNT = 20;
const FIXED_DOCUMENT_SAMPLES = {
  outbound: {
    documentType: 'ITINERARY',
    dataSource: 'FIXED_ACCEPTANCE',
    extractedAt: '2026-09-05T00:00:00Z',
    fields: {},
    needsReviewFields: ['segments[1].flight_number'],
    reviewIssues: [{
      segmentIndex: 1, field: 'arrival_airport', kind: 'ADVISORY', blocking: false,
      candidates: [
        { normalizedValue: 'MAN', source: 'UNDERSTAND', confidence: 0.72 },
        { normalizedValue: 'LPL', source: 'STRUCTURE', confidence: 0.61 }
      ], selectedValue: null, reason: 'SUPPORTING_FIELD_DIFFERENCE'
    }],
    segments: [
      {
        flightNumber: 'MF825', flightNumberConfidence: 'HIGH',
        requiresFlightNumberConfirmation: false, flightNumberConfirmedByUser: false,
        departureDate: '2026-09-05', departureMonthDay: '09-05',
        dateSource: 'CURRENT_YEAR_FROM_MONTH_DAY',
        departureAirport: 'XMN', arrivalAirport: 'CDG',
        scheduledDeparture: '2026-09-05T00:10:00+08:00',
        scheduledArrival: '2026-09-05T06:55:00+02:00',
        terminal: 'T3'
      },
      {
        flightNumber: 'AF1668', flightNumberConfidence: 'MEDIUM',
        requiresFlightNumberConfirmation: true, flightNumberConfirmedByUser: false,
        departureDate: '2026-09-05', departureMonthDay: '09-05',
        dateSource: 'CURRENT_YEAR_FROM_MONTH_DAY',
        departureAirport: 'CDG', arrivalAirport: 'MAN',
        scheduledDeparture: '09:25', scheduledArrival: '10:00', terminal: 'T2E'
      }
    ]
  },
  return: {
    documentType: 'ITINERARY',
    dataSource: 'FIXED_ACCEPTANCE',
    extractedAt: '2026-08-04T00:00:00Z',
    fields: {},
    needsReviewFields: [],
    segments: [
      {
        flightNumber: 'KL1036', flightNumberConfidence: 'HIGH',
        requiresFlightNumberConfirmation: false, flightNumberConfirmedByUser: false,
        departureDate: '2026-08-04', departureMonthDay: '08-04',
        dateSource: 'CURRENT_YEAR_FROM_MONTH_DAY',
        departureAirport: 'MAN', arrivalAirport: 'AMS',
        scheduledDeparture: '17:30', scheduledArrival: '19:50', terminal: 'T2'
      },
      {
        flightNumber: 'MF812', flightNumberConfidence: 'HIGH',
        requiresFlightNumberConfirmation: false, flightNumberConfirmedByUser: false,
        departureDate: '2026-08-04', departureMonthDay: '08-04',
        dateSource: 'CURRENT_YEAR_FROM_MONTH_DAY',
        departureAirport: 'AMS', arrivalAirport: 'XMN',
        scheduledDeparture: '21:40', scheduledArrival: '14:20', terminal: 'T3'
      }
    ]
  },
  missing: {
    documentType: 'ITINERARY', dataSource: 'FIXED_ACCEPTANCE', extractedAt: '2026-08-04T00:00:00Z',
    fields: {}, needsReviewFields: ['segments[1].flight_number'],
    segments: [
      {
        flightNumber: 'KL1036', flightNumberConfidence: 'HIGH', requiresFlightNumberConfirmation: false,
        flightNumberConfirmedByUser: false, departureDate: '2026-08-04', departureMonthDay: '08-04',
        dateSource: 'CURRENT_YEAR_FROM_MONTH_DAY', departureAirport: 'MAN', arrivalAirport: 'AMS',
        scheduledDeparture: '17:30'
      },
      {
        flightNumber: null, flightNumberConfidence: 'LOW', requiresFlightNumberConfirmation: false,
        flightNumberConfirmedByUser: false, departureDate: null, departureMonthDay: null,
        dateSource: 'MISSING', departureAirport: 'AMS', arrivalAirport: 'XMN', scheduledDeparture: '21:40'
      }
    ]
  },
  conflict: {
    documentType: 'ITINERARY', dataSource: 'FIXED_ACCEPTANCE', extractedAt: '2026-09-05T00:00:00Z',
    fields: {}, needsReviewFields: ['segments[1].conflict.departure_date'],
    reviewIssues: [{
      segmentIndex: 1, field: 'departure_date', kind: 'ACTIONABLE', blocking: true,
      candidates: [
        { normalizedValue: '2026-09-05', source: 'UNDERSTAND', confidence: 0.78 },
        { normalizedValue: '2026-09-06', source: 'STRUCTURE', confidence: 0.74 }
      ], selectedValue: null, reason: 'MULTIPLE_CANDIDATES'
    }],
    segments: [
      {
        flightNumber: 'MF825', flightNumberConfidence: 'HIGH', requiresFlightNumberConfirmation: false,
        flightNumberConfirmedByUser: false, departureDate: '2026-09-05', departureMonthDay: '09-05',
        dateSource: 'CURRENT_YEAR_FROM_MONTH_DAY', departureAirport: 'XMN', arrivalAirport: 'CDG',
        scheduledDeparture: '00:10'
      },
      {
        flightNumber: 'AF1668', flightNumberConfidence: 'HIGH', requiresFlightNumberConfirmation: false,
        flightNumberConfirmedByUser: false, departureDate: '2026-09-05', departureMonthDay: '09-05',
        dateSource: 'CONFLICT', departureAirport: 'CDG', arrivalAirport: 'MAN', scheduledDeparture: '09:25'
      }
    ]
  }
};

const FIXED_FLIGHTS = {
  MF825: {
    flightNumber: 'MF825', departureDate: '2026-09-05',
    departureAirport: 'XMN', departureAirportName: 'Xiamen Gaoqi',
    arrivalAirport: 'CDG', arrivalAirportName: 'Paris Charles de Gaulle',
    scheduledDeparture: '2026-09-05T00:10:00+08:00',
    actualDeparture: '2026-09-05T00:22:00+08:00',
    scheduledArrival: '2026-09-05T06:55:00+02:00',
    actualArrival: '2026-09-05T07:03:00+02:00',
    terminal: 'T3', gate: '8', checkInDeskOrZone: 'H', seat: '18A',
    status: 'DEPARTED', live: true, verified: true, demo: false,
    liveDataSource: 'FIXED_ACCEPTANCE', dataSource: 'FIXED_ACCEPTANCE',
    lastUpdated: '2026-09-05T07:00:00Z', dataLastUpdatedAt: '2026-09-05T07:00:00Z',
    conflicts: [], latestChanges: [], requiresUserConfirmation: false,
    providerResults: { fixedAcceptance: { outcome: 'SUCCESS', quality: 'EXACT', fromCache: false } },
    timeContext: {
      serverNow: '2026-09-05T07:00:00Z', departureUtcOffset: '+08:00', arrivalUtcOffset: '+02:00',
      effectiveDeparture: '2026-09-05T00:22:00+08:00',
      effectiveArrival: '2026-09-05T07:03:00+02:00',
      boardingTime: '2026-09-04T23:30:00+08:00',
      dataRetrievedAt: '2026-09-05T07:00:00Z', dataLastUpdatedAt: '2026-09-05T07:00:00Z'
    }
  },
  AF1668: {
    flightNumber: 'AF1668', departureDate: '2026-09-05',
    departureAirport: 'CDG', departureAirportName: 'Paris Charles de Gaulle',
    arrivalAirport: 'MAN', arrivalAirportName: 'Manchester',
    scheduledDeparture: '2026-09-05T09:25:00+02:00',
    scheduledArrival: '2026-09-05T10:00:00+01:00',
    terminal: 'T2E', gate: 'B12', checkInDeskOrZone: '2E', seat: '22C',
    status: 'SCHEDULED', live: true, verified: true, demo: false,
    liveDataSource: 'FIXED_ACCEPTANCE', dataSource: 'FIXED_ACCEPTANCE',
    lastUpdated: '2026-09-05T07:00:00Z', dataLastUpdatedAt: '2026-09-05T07:00:00Z',
    conflicts: [], latestChanges: [], requiresUserConfirmation: false,
    providerResults: { fixedAcceptance: { outcome: 'SUCCESS', quality: 'EXACT', fromCache: false } },
    timeContext: {
      serverNow: '2026-09-05T07:00:00Z', departureUtcOffset: '+02:00', arrivalUtcOffset: '+01:00',
      effectiveDeparture: '2026-09-05T09:25:00+02:00',
      effectiveArrival: '2026-09-05T10:00:00+01:00',
      boardingTime: '2026-09-05T08:45:00+02:00',
      dataRetrievedAt: '2026-09-05T07:00:00Z', dataLastUpdatedAt: '2026-09-05T07:00:00Z'
    }
  }
};

const summary = {
  browser: 'Chrome DevTools Protocol (Node built-ins)',
  testType: 'FIXED_RESPONSE_FRONTEND_CONTRACT',
  apiInterception: true,
  endToEnd: false,
  console: { errors: 0, warnings: 0, javascriptExceptions: 0 },
  locales: { zh: false, en: false, es: false },
  responsive: {
    viewport320x800: false, viewport360x800: false, viewport390x844: false,
    desktop1280x900: false, zoom200Percent: false
  },
  deterministic: {
    repeatCountPerSample: REPEAT_COUNT,
    samples: { outbound: false, return: false },
    noResolverBeforeSelection: false,
    explicitQueryOnly: false,
    noSeparateYearInput: false,
    localSaveOnly: false,
    advisoryQueryable: false,
    pageStates: { queryable: false, advisory: false, missing: false, conflict: false },
    doubleClickDeduplicated: false,
    requestCounts: {
      upload: null, selectSegment: null, openCompletion: null, currentYearDate: null,
      saveCompletion: null, advisorySelection: null, conflictCandidateSelection: null,
      explicitQuery: null, rapidDoubleClick: null
    },
    departedFirst: false,
    backPreservesItinerary: false,
    secondIsNormal: false,
    resolverPayloadsPreserveAllSegments: false,
    staff: { count: 0, iconContract: false, speech: {}, lifecycle: false,
      cardOrder: 'NOT_RUN', locator: 'NOT_RUN', extraAssistance: 'NOT_RUN', browserBack: 'NOT_RUN' },
    confirmSpeech: { text: null, displayedFacts: null, passed: false },
    journeyProblem: { requests: [], locales: {}, responsive: {}, historical: false, passed: false },
    family: { controls: null, responsive: {}, nativeLink: false, nativeFile: false,
      forbiddenUiAbsent: false, clipboardUnused: false },
    mockedApiCalls: {},
    realApiRequests: [],
    unexpectedRequests: [],
    passed: false
  },
  passed: false
};

const delay = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

function safeFailure(error) {
  return String(error && error.message || error || 'Acceptance step failed')
    .replace(/https?:\/\/\S+/gi, '[url]')
    .replace(/[A-Za-z0-9_-]{32,}/g, '[redacted]')
    .slice(0, 600);
}

async function reservePort() {
  const port = await new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      server.close((error) => error ? reject(error) : resolve(port));
    });
  });
  return port === 8080 ? reservePort() : port;
}

async function readJson(url, options = {}) {
  const response = await fetch(url, { ...options, signal: AbortSignal.timeout(options.timeout || 5000) });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

async function waitForChrome(port, timeout = 15000) {
  const started = Date.now();
  while (Date.now() - started < timeout) {
    try {
      return await readJson(`http://127.0.0.1:${port}/json/version`, { timeout: 1000 });
    } catch {
      await delay(200);
    }
  }
  throw new Error('Chrome DevTools endpoint did not become ready');
}

class CdpSession {
  constructor(webSocketDebuggerUrl, label) {
    this.url = webSocketDebuggerUrl;
    this.label = label;
    this.nextId = 1;
    this.pending = new Map();
    this.listeners = new Map();
    this.documentResponses = [];
    this.documentRequests = new Map();
    this.flightResponses = [];
    this.flightRequests = new Map();
  }

  async connect() {
    this.socket = new WebSocket(this.url);
    await new Promise((resolve, reject) => {
      this.socket.addEventListener('open', resolve, { once: true });
      this.socket.addEventListener('error', () => reject(new Error('CDP WebSocket connection failed')), { once: true });
    });
    this.socket.addEventListener('message', (event) => this.receive(event.data));
    this.socket.addEventListener('close', () => {
      for (const { reject } of this.pending.values()) reject(new Error('CDP WebSocket closed'));
      this.pending.clear();
    });
    return this;
  }

  receive(data) {
    let message;
    try { message = JSON.parse(String(data)); } catch { return; }
    if (message.id) {
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id);
      if (message.error) pending.reject(new Error(`${pending.method} failed`));
      else pending.resolve(message.result || {});
      return;
    }
    const callbacks = this.listeners.get(message.method) || [];
    callbacks.forEach((callback) => Promise.resolve(callback(message.params || {})).catch(() => {}));
  }

  on(method, callback) {
    const callbacks = this.listeners.get(method) || [];
    callbacks.push(callback);
    this.listeners.set(method, callbacks);
  }

  send(method, params = {}) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject, method });
      this.socket.send(JSON.stringify({ id, method, params }));
    });
  }

  close() {
    if (this.socket && this.socket.readyState < 2) this.socket.close();
  }
}

function installSafeDiagnostics(page) {
  page.on('Runtime.consoleAPICalled', ({ type }) => {
    if (type === 'error' || type === 'assert') summary.console.errors += 1;
    if (type === 'warning') summary.console.warnings += 1;
  });
  page.on('Runtime.exceptionThrown', () => { summary.console.javascriptExceptions += 1; });
  page.on('Network.responseReceived', ({ requestId, response }) => {
    try {
      const url = new URL(response.url);
      if (url.pathname === '/api/documents/boarding-pass' || url.pathname === '/api/documents/travel-document') {
        page.documentRequests.set(requestId, { status: response.status });
      } else if (url.pathname === '/api/journeys/resolve-flight') {
        page.flightRequests.set(requestId, { status: response.status });
      }
    } catch {
      // Ignore malformed/non-HTTP URLs; no response content is logged.
    }
  });
  page.on('Network.loadingFinished', async ({ requestId }) => {
    const metadata = page.documentRequests.get(requestId);
    const flightMetadata = page.flightRequests.get(requestId);
    if (!metadata && !flightMetadata) return;
    page.documentRequests.delete(requestId);
    page.flightRequests.delete(requestId);
    let parsed = null;
    try {
      const result = await page.send('Network.getResponseBody', { requestId });
      const body = result.base64Encoded ? Buffer.from(result.body, 'base64').toString('utf8') : result.body;
      parsed = JSON.parse(body);
    } catch {
      // The status is still recorded without exposing an unreadable response.
    }
    if (metadata) {
      const segmentList = Array.isArray(parsed && parsed.segments) ? parsed.segments : [];
      page.documentResponses.push({
        status: metadata.status,
        segmentCount: segmentList.length,
        segments: segmentList.slice(0, 8).map((segment) => ({
          flightNumber: segment.flightNumber || null,
          departureDate: segment.departureDate || null,
          departureAirport: segment.departureAirport || null,
          arrivalAirport: segment.arrivalAirport || null,
          scheduledDeparture: segment.scheduledDeparture || null,
          scheduledArrival: segment.scheduledArrival || null,
          terminal: segment.terminal || null,
          gate: segment.gate || null
        })),
        needsReviewFields: Array.isArray(parsed && parsed.needsReviewFields)
          ? parsed.needsReviewFields.filter((field) => typeof field === 'string').slice(0, 30) : []
      });
    }
    if (flightMetadata) {
      const providerResults = {};
      for (const [provider, result] of Object.entries(parsed && parsed.providerResults || {})) {
        providerResults[provider] = {
          outcome: result && result.outcome || null,
          errorCode: result && result.errorCode || null,
          quality: result && result.quality || null,
          fromCache: Boolean(result && result.fromCache)
        };
      }
      page.flightResponses.push({
        status: flightMetadata.status,
        providerResults,
        selectedFields: parsed ? {
          flightNumber: parsed.flightNumber || null,
          departureDate: parsed.departureDate || null,
          departureAirport: parsed.departureAirport || null,
          arrivalAirport: parsed.arrivalAirport || null,
          scheduledDeparture: parsed.scheduledDeparture || null,
          estimatedDeparture: parsed.estimatedDeparture || null,
          scheduledArrival: parsed.scheduledArrival || null,
          estimatedArrival: parsed.estimatedArrival || null,
          terminal: parsed.terminal || null,
          gate: parsed.gate || null,
          checkInDeskOrZone: parsed.checkInDeskOrZone || null,
          status: parsed.status || null,
          arrivalBaggageBelt: parsed.arrivalBaggageBelt || null,
          dataSource: parsed.liveDataSource || parsed.dataSource || null,
          demo: Boolean(parsed.demo)
        } : {}
      });
    }
  });
}

async function enablePage(page) {
  installSafeDiagnostics(page);
  await Promise.all([
    page.send('Page.enable'),
    page.send('Runtime.enable'),
    page.send('Network.enable'),
    page.send('DOM.enable')
  ]);
}

async function evaluate(page, expression) {
  const result = await page.send('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
    userGesture: true
  });
  if (result.exceptionDetails) throw new Error('Page evaluation failed');
  return result.result && result.result.value;
}

async function waitFor(page, expression, label, timeout = 30000) {
  const started = Date.now();
  while (Date.now() - started < timeout) {
    try {
      if (await evaluate(page, `Boolean(${expression})`)) return;
    } catch {
      // Navigation may briefly invalidate an execution context.
    }
    await delay(250);
  }
  throw new Error(`${label} timed out`);
}

async function click(page, selector) {
  const found = await evaluate(page, `(() => {
    const element = document.querySelector(${JSON.stringify(selector)});
    if (!element || element.hidden || element.disabled) return false;
    element.focus();
    element.click();
    return true;
  })()`);
  if (!found) throw new Error(`Required control unavailable: ${selector}`);
}

async function setValue(page, selector, value) {
  const changed = await evaluate(page, `(() => {
    const element = document.querySelector(${JSON.stringify(selector)});
    if (!element) return false;
    element.value = ${JSON.stringify(value)};
    element.dispatchEvent(new Event('input', { bubbles: true }));
    element.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  })()`);
  if (!changed) throw new Error(`Required input unavailable: ${selector}`);
}

async function activeScreen(page, name, timeout = 30000) {
  await waitFor(page, `(() => { const e = document.querySelector('[data-screen="${name}"]'); return e && !e.hidden; })()`, `${name} screen`, timeout);
}

async function navigate(page, url) {
  await page.send('Page.navigate', { url });
  await waitFor(page, `document.readyState === 'complete' && Boolean(document.querySelector('#main-content'))`, 'page load', 30000);
}

async function hasNoHorizontalOverflow(page) {
  return evaluate(page, `document.documentElement.scrollWidth <= window.innerWidth + 1
    && document.body.getBoundingClientRect().width <= window.innerWidth + 1`);
}

async function testLocalesAndResponsive(page) {
  await navigate(page, `${BASE_URL}/`);
  const expected = {
    zh: '现在，只做下一步',
    en: 'Now, take the next step',
    es: 'Ahora, solo el siguiente paso'
  };
  for (const [locale, title] of Object.entries(expected)) {
    await setValue(page, '#locale-select', locale);
    summary.locales[locale] = await evaluate(page, `document.documentElement.lang === ${JSON.stringify(locale === 'zh' ? 'zh-CN' : locale)}
      && document.querySelector('[data-i18n="welcomeTitle"]').textContent.trim() === ${JSON.stringify(title)}`);
  }
  await setValue(page, '#locale-select', 'en');

  await page.send('Emulation.setDeviceMetricsOverride', { width: 320, height: 800, deviceScaleFactor: 1, mobile: true });
  summary.responsive.viewport320x800 = await hasNoHorizontalOverflow(page);
  await page.send('Emulation.setDeviceMetricsOverride', { width: 360, height: 800, deviceScaleFactor: 1, mobile: true });
  summary.responsive.viewport360x800 = await hasNoHorizontalOverflow(page);
  await page.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  summary.responsive.viewport390x844 = await hasNoHorizontalOverflow(page);
  await page.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  summary.responsive.desktop1280x900 = await hasNoHorizontalOverflow(page);
  await page.send('Emulation.setDeviceMetricsOverride', { width: 195, height: 422, deviceScaleFactor: 2, mobile: true });
  await page.send('Emulation.setPageScaleFactor', { pageScaleFactor: 2 });
  summary.responsive.zoom200Percent = await hasNoHorizontalOverflow(page);
  if (!summary.responsive.zoom200Percent) {
    summary.responsive.zoomOverflow = await evaluate(page, `(() => ({
      innerWidth, documentWidth: document.documentElement.scrollWidth, bodyWidth: document.body.scrollWidth,
      elements: Array.from(document.querySelectorAll('body *')).map((element) => {
        const rect = element.getBoundingClientRect();
        return { tag: element.tagName, classes: element.className, left: rect.left, right: rect.right,
          width: rect.width, text: element.textContent.trim().replace(/\\s+/g, ' ').slice(0, 60) };
      }).filter((item) => item.left < -1 || item.right > innerWidth + 1).slice(0, 12)
    }))()`);
  }
  await page.send('Emulation.setPageScaleFactor', { pageScaleFactor: 1 });
  await page.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
}

function requireAcceptance(condition, message, details) {
  if (!condition) {
    throw new Error(`${message}${details === undefined ? '' : `: ${JSON.stringify(details)}`}`);
  }
}

export function deterministicInjectionSource() {
  return `(() => {
    const clone = (value) => JSON.parse(JSON.stringify(value));
    const samples = ${JSON.stringify(FIXED_DOCUMENT_SAMPLES)};
    const flights = ${JSON.stringify(FIXED_FLIGHTS)};
    const state = window.__fixedAcceptance = {
      selectedSample: 'outbound',
      apiCalls: [],
      resolveRequests: [],
      refreshRequests: [],
      journeyProblemRequests: [],
      journeyProblemMode: 'ANSWER',
      staffRequests: [],
      shareCreates: [],
      unexpectedRequests: [],
      nativeShares: [],
      canShareCalls: [],
      clipboardWrites: [],
      flights,
      speech: { utterances: [], cancels: 0, speaking: false, pending: false, current: null }
    };
    try { localStorage.clear(); sessionStorage.clear(); } catch (_) {}

    const response = (payload, status = 200) => Promise.resolve(new Response(JSON.stringify(payload), {
      status,
      headers: { 'Content-Type': 'application/json' }
    }));
    const originalFetch = window.fetch.bind(window);
    window.fetch = function (input, init = {}) {
      const raw = typeof input === 'string' ? input : input && input.url || '';
      const url = new URL(raw, location.href);
      const method = String(init.method || input && input.method || 'GET').toUpperCase();
      if (!url.pathname.startsWith('/api/')) return originalFetch(input, init);
      state.apiCalls.push({ method, path: url.pathname });
      if (url.pathname === '/api/documents/travel-document' && method === 'POST') {
        return response(clone(samples[state.selectedSample]));
      }
      if (url.pathname === '/api/journeys/resolve-flight' && method === 'POST') {
        let request = {};
        try { request = JSON.parse(String(init.body || '{}')); } catch (_) {}
        state.resolveRequests.push(clone(request));
        const key = String(request.flightNumber || '').replace(/\\s+/g, '').toUpperCase();
        if (flights[key]) return response(clone(flights[key]));
      }
      if (url.pathname === '/api/journeys/refresh-flight' && method === 'POST') {
        let request = {};
        try { request = JSON.parse(String(init.body || '{}')); } catch (_) {}
        state.refreshRequests.push(clone(request));
        const key = String(request.flightNumber || '').replace(/\s+/g, '').toUpperCase();
        if (flights[key]) return response(clone(flights[key]));
      }
      if (url.pathname === '/api/assistance/journey-problem' && method === 'POST') {
        let request = {};
        try { request = JSON.parse(String(init.body || '{}')); } catch (_) {}
        state.journeyProblemRequests.push(clone(request));
        const answers = {
          zh: '请先到航空公司柜台询问能否继续乘坐当前航班。请留意新的起飞时间。',
          en: 'Ask the airline desk whether you can still take this flight. Monitor the updated departure time.',
          es: 'Pregunta en el mostrador si aún puedes tomar este vuelo. Consulta la nueva hora de salida.'
        };
        if (state.journeyProblemMode === 'DELAY' || state.journeyProblemMode === 'DELAY_RECOVERY') {
          return new Promise((resolve, reject) => {
            const timer = setTimeout(() => resolve(new Response(JSON.stringify({
              answer: answers[request.locale] || answers.en,
              status: 'ANSWER',
              retrievedAt: new Date().toISOString()
            }), { status: 200, headers: { 'Content-Type': 'application/json' } })), 250);
            if (init.signal) init.signal.addEventListener('abort', () => {
              clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError'));
            }, { once: true });
          });
        }
        if (state.journeyProblemMode === 'FAIL') return response({ code: 'UPSTREAM_UNAVAILABLE' }, 503);
        return response({ answer: answers[request.locale] || answers.en, status: 'ANSWER', retrievedAt: new Date().toISOString() });
      }
      if (url.pathname === '/api/assistance/staff-guidance' && method === 'POST') {
        let request = {};
        try { request = JSON.parse(String(init.body || '{}')); } catch (_) {}
        state.staffRequests.push(clone(request));
        const evidence = { id: 'source-1', purpose: 'EVIDENCE', origin: 'AIRPORT',
          title: 'Official airport information service',
          href: 'https://www.parisaeroport.fr/passagers/services/information' };
        const contact = { id: 'source-3', purpose: 'CONTACT', origin: 'AIRPORT',
          title: 'Official airport contact', href: 'https://www.parisaeroport.fr/passagers/contact' };
        const localizedName = request.assistanceType ? '机场无障碍协助服务台' : '机场旅客信息服务台';
        const purpose = request.assistanceType ? '可现场申请与所选需求相关的机场协助' : '可询问当前乘机阶段并正式请求工作人员帮助';
        const fields = ['localizedName', 'purpose', 'securitySide', 'levelOrArea',
          'zoneOrLandmark', 'staffType', 'relationToCurrentContext'].reduce((all, field) => {
          all[field] = ['source-1']; return all;
        }, {});
        return response({
          deterministicContext: {
            airportCode: request.airportCode, airportName: request.airportName,
            airlineCode: 'AF', airlineName: 'Air France', journeyDirection: request.journeyDirection,
            terminal: request.terminal, currentStage: request.currentStage,
            securityState: request.securityState, passengerAnchor: request.knownCheckInLocation,
            knownGate: request.knownGate, knownCheckInLocation: request.knownCheckInLocation,
            knownBagDropLocation: request.knownBagDropLocation,
            knownBaggageBelt: request.knownBaggageBelt,
            selectedLanguage: request.selectedLanguage, assistanceType: request.assistanceType || null
          },
          aiFindings: {
            staffLocations: [{ localizedName, purpose, terminal: null,
              securitySide: '安检前', levelOrArea: '出发层', zoneOrLandmark: '旅客信息区域',
              staffType: '机场旅客服务人员', relationToCurrentContext: '适合当前值机确认阶段',
              sourceIds: ['source-1'], fieldSourceIds: fields }],
            staffIdentification: [{ localizedDescription: '可寻找佩戴机场工作证的旅客服务人员',
              sourceIds: ['source-1'] }],
            officialContacts: [{ kind: 'CONTACT_PAGE', href: contact.href,
              displayValue: '机场联系页面', sourceIds: ['source-3'] }]
          },
          officialSources: [evidence, contact], resultMode: 'AI_ACCEPTED',
          failureReason: null, warnings: [], retrievedAt: '2026-08-28T00:00:00Z', fromCache: false
        });
      }
      if (url.pathname === '/api/share-sessions' && method === 'POST') {
        let request = {};
        try { request = JSON.parse(String(init.body || '{}')); } catch (_) {}
        state.shareCreates.push(clone(request));
        return response({
          viewToken: 'fixed-view-token', updateToken: 'fixed-update-token',
          viewUrl: location.origin + '/share/fixed-view-token',
          expiresAt: new Date(Date.now() + 48 * 60 * 60 * 1000).toISOString()
        });
      }
      if (url.pathname === '/api/share-sessions/fixed-view-token' && method === 'PUT') return response({ updated: true });
      state.unexpectedRequests.push({ method, path: url.pathname });
      return Promise.reject(new Error('Unexpected API request in fixed acceptance: ' + method + ' ' + url.pathname));
    };

    class FixedSpeechSynthesisUtterance {
      constructor(text) {
        this.text = String(text || '');
        this.lang = '';
        this.rate = 1;
        this.voice = null;
        this.onend = null;
        this.onerror = null;
      }
    }
    const speechApi = {
      get speaking() { return state.speech.speaking; },
      get pending() { return state.speech.pending; },
      getVoices() {
        return [{ lang: 'zh-CN', name: 'Fixed zh' }, { lang: 'en-US', name: 'Fixed en' },
          { lang: 'es-ES', name: 'Fixed es' }];
      },
      speak(utterance) {
        state.speech.current = utterance;
        state.speech.speaking = true;
        state.speech.pending = false;
        state.speech.utterances.push({ text: utterance.text, lang: utterance.lang, rate: utterance.rate });
      },
      cancel() {
        state.speech.cancels += 1;
        state.speech.current = null;
        state.speech.speaking = false;
        state.speech.pending = false;
      }
    };
    state.finishSpeech = function () {
      const utterance = state.speech.current;
      state.speech.current = null;
      state.speech.speaking = false;
      state.speech.pending = false;
      if (utterance && typeof utterance.onend === 'function') utterance.onend();
    };
    state.resetSpeech = function () {
      state.finishSpeech();
      state.speech.utterances.length = 0;
      state.speech.cancels = 0;
    };
    Object.defineProperty(window, 'SpeechSynthesisUtterance', {
      configurable: true, value: FixedSpeechSynthesisUtterance
    });
    Object.defineProperty(window, 'speechSynthesis', { configurable: true, value: speechApi });

    state.journeyProblemRequests = [];
    state.recognitionMode = 'success';
    state.recognitionTranscript = '语音识别的完整情况';
    class FixedSpeechRecognition {
      constructor() { this.lang = ''; this.continuous = true; this.interimResults = true; this.maxAlternatives = 0; }
      start() {
        setTimeout(() => {
          if (state.recognitionMode === 'success') {
            if (this.onresult) this.onresult({ results: [[{ transcript: state.recognitionTranscript }]] });
            if (this.onend) this.onend();
          } else if (this.onerror) this.onerror({ error: 'no-speech' });
        }, 0);
      }
      abort() { state.recognitionAborts = (state.recognitionAborts || 0) + 1; }
    }
    Object.defineProperty(window, 'SpeechRecognition', { configurable: true, value: FixedSpeechRecognition });

    const normalizeShare = (payload) => ({
      title: payload && payload.title || null,
      text: payload && payload.text || null,
      url: payload && payload.url || null,
      files: Array.from(payload && payload.files || []).map((file) => ({
        name: file.name, type: file.type, size: file.size
      }))
    });
    Object.defineProperty(navigator, 'share', {
      configurable: true,
      value(payload) { state.nativeShares.push(normalizeShare(payload)); return Promise.resolve(); }
    });
    Object.defineProperty(navigator, 'canShare', {
      configurable: true,
      value(payload) { state.canShareCalls.push(normalizeShare(payload)); return true; }
    });
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText(value) { state.clipboardWrites.push(String(value)); return Promise.resolve(); } }
    });
  })();`;
}

async function createDeterministicPage(port) {
  const descriptor = await readJson(`http://127.0.0.1:${port}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' });
  const page = await new CdpSession(descriptor.webSocketDebuggerUrl, 'fixed-contract').connect();
  page.realApiRequests = [];
  await enablePage(page);
  page.on('Network.requestWillBeSent', ({ request }) => {
    try {
      const url = new URL(request.url);
      if (url.pathname.startsWith('/api/')) page.realApiRequests.push(`${request.method} ${url.pathname}`);
    } catch {
      // Ignore non-HTTP URLs.
    }
  });
  await page.send('Page.addScriptToEvaluateOnNewDocument', { source: deterministicInjectionSource() });
  await navigate(page, `${BASE_URL}/`);
  await waitFor(page, `Boolean(window.__fixedAcceptance)`, 'fixed acceptance injection');
  return page;
}

async function returnFromSegmentSelector(page) {
  await click(page, '[data-screen="confirm"] > [data-action="journey-back"]');
  await activeScreen(page, 'welcome');
}

async function uploadFixedDocument(page, sampleName, iteration) {
  const started = await evaluate(page, `(() => {
    window.__fixedAcceptance.selectedSample = ${JSON.stringify(sampleName)};
    const transfer = new DataTransfer();
    transfer.items.add(new File(['fixed-${sampleName}-${iteration}'],
      ${JSON.stringify(sampleName)} + '-itinerary-' + ${Number(iteration)} + '.png', { type: 'image/png' }));
    const input = document.querySelector('#ticket-file');
    if (!input) return false;
    input.files = transfer.files;
    input.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  })()`);
  requireAcceptance(started, 'document file input was unavailable');
  await waitFor(page, `(() => {
    const screen = document.querySelector('[data-screen="confirm"]');
    const selector = document.querySelector('[data-segment-selector]');
    const continueButton = document.querySelector('.screen-confirm > .confirm-choice-actions [data-action="continue"]');
    return screen && !screen.hidden && selector && !selector.hidden
      && document.querySelectorAll('[data-segment-options] [data-action="select-segment"]').length === 2
      && continueButton && continueButton.disabled
      && document.querySelector('#main-content').getAttribute('aria-busy') === 'false';
  })()`, `${sampleName} segment selection`);
}

async function readRenderedSegments(page) {
  return evaluate(page, `Array.from(document.querySelectorAll('[data-segment-options] [data-action="select-segment"]'))
    .map((button) => {
      const heading = button.querySelector('strong').textContent.replace(/\\s+/g, '');
      const parts = heading.split('·');
      const route = (parts[1] || '').replace(/\\s+/g, '');
      return { flightNumber: parts[0], route,
        index: Number(button.dataset.segmentIndex), disabled: button.disabled,
        tagName: button.tagName, type: button.type,
        accessibleName: button.getAttribute('aria-label') || button.innerText.trim().replace(/\\s+/g, ' ') };
    })`);
}

function renderedSegmentIdentity(segments) {
  return segments.map(({ flightNumber, route, index, disabled }) => ({ flightNumber, route, index, disabled }));
}

function expectedRenderedSegments(sampleName) {
  return FIXED_DOCUMENT_SAMPLES[sampleName].segments.map((segment, index) => ({
    flightNumber: segment.flightNumber,
    route: `${segment.departureAirport}→${segment.arrivalAirport}`,
    index,
    disabled: false
  }));
}

async function verifyRepeatedSamples(page) {
  for (const sampleName of ['outbound', 'return']) {
    const expected = expectedRenderedSegments(sampleName);
    for (let iteration = 1; iteration <= REPEAT_COUNT; iteration += 1) {
      await uploadFixedDocument(page, sampleName, iteration);
      const rendered = await readRenderedSegments(page);
      requireAcceptance(rendered.every((item) => item.tagName === 'BUTTON' && item.type === 'button'
          && item.accessibleName && !item.disabled),
        `${sampleName} segment options are not enabled accessible buttons on repetition ${iteration}`, rendered);
      requireAcceptance(JSON.stringify(renderedSegmentIdentity(rendered)) === JSON.stringify(expected),
        `${sampleName} segments changed order or content on repetition ${iteration}`, rendered);
      if (iteration < REPEAT_COUNT || sampleName !== 'return') await returnFromSegmentSelector(page);
    }
    summary.deterministic.samples[sampleName] = true;
  }
  const resolverCount = await evaluate(page, `window.__fixedAcceptance.resolveRequests.length`);
  requireAcceptance(resolverCount === 0, 'resolver ran before a segment was selected', resolverCount);
  summary.deterministic.noResolverBeforeSelection = true;
  await returnFromSegmentSelector(page);
}

async function verifyFourItineraryStates(page) {
  await setValue(page, '#locale-select', 'zh');
  const cases = [
    ['return', '选择航段', 'queryable'],
    ['outbound', '选择航段', 'advisory'],
    ['missing', '补全航段', 'missing'],
    ['conflict', '核对航段', 'conflict']
  ];
  const before = await evaluate(page, `window.__fixedAcceptance.resolveRequests.length`);
  for (const [sample, expectedTitle, summaryKey] of cases) {
    await uploadFixedDocument(page, sample, `state-${summaryKey}`);
    const state = await evaluate(page, `(() => ({
      context: document.querySelector('[data-segment-context]').textContent.trim(),
      title: document.querySelector('[data-segment-title]').textContent.trim(),
      resolverCount: window.__fixedAcceptance.resolveRequests.length
    }))()`);
    requireAcceptance(state.context === '' && state.title === expectedTitle && state.resolverCount === before,
      `itinerary state ${summaryKey} was inconsistent`, state);
    if (summaryKey === 'advisory') {
      const advisory = await evaluate(page, `(() => {
        const second = document.querySelector('[data-segment-options] [data-segment-index="1"]');
        return second && { classes: second.className,
          conflict: second.classList.contains('is-conflict'),
          incomplete: second.classList.contains('is-incomplete'),
          pending: second.classList.contains('is-pending'),
          disabled: second.disabled,
          label: second.innerText.trim().replace(/\\s+/g, ' ') };
      })()`);
      requireAcceptance(advisory && !advisory.conflict && !advisory.incomplete
          && !advisory.pending && !advisory.disabled && advisory.label.includes('选择航段'),
        'single medium/supporting candidates were not queryable', advisory);
    }
    if (summaryKey === 'conflict') {
      await click(page, '[data-segment-options] [data-segment-index="1"]');
      await waitFor(page, `(() => {
        const manual = document.querySelector('[data-screen="manual"]');
        const candidates = document.querySelectorAll('[data-review-candidates] [data-action="select-review-candidate"]');
        const date = document.querySelector('#date-input');
        const confirmation = document.querySelector('#date-confirm-input');
        const primary = document.querySelector('[data-manual-primary]');
        return manual && !manual.hidden && candidates.length === 2 && date && !date.disabled
          && confirmation && !confirmation.disabled && primary && primary.dataset.mode === 'save';
      })()`, 'blocking conflict exposes actionable date candidates and confirmation');
      await click(page, '[data-review-candidates] [data-action="select-review-candidate"]:nth-child(2)');
      const selected = await evaluate(page, `(() => ({
        value: document.querySelector('#date-input').value,
        confirmed: document.querySelector('#date-confirm-input').checked,
        resolverCount: window.__fixedAcceptance.resolveRequests.length
      }))()`);
      requireAcceptance(selected.value === '2026-09-06' && selected.confirmed
          && selected.resolverCount === before,
        'selecting a blocking candidate was not a local-only action', selected);
      summary.deterministic.requestCounts.conflictCandidateSelection = 0;
      await click(page, '[data-screen="manual"] [data-action="home"]');
      await activeScreen(page, 'confirm');
    }
    summary.deterministic.pageStates[summaryKey] = true;
    await returnFromSegmentSelector(page);
  }
  await setValue(page, '#locale-select', 'en');
}

async function verifyLocalCompletionOnly(page) {
  const before = await evaluate(page, `window.__fixedAcceptance.resolveRequests.length`);
  await uploadFixedDocument(page, 'missing', 'local-save');
  await click(page, '[data-segment-options] [data-segment-index="1"]');
  await waitFor(page, `(() => {
    const manual = document.querySelector('[data-screen="manual"]');
    const flight = document.querySelector('#flight-input');
    const date = document.querySelector('#date-input');
    const primary = document.querySelector('[data-manual-primary]');
    return manual && !manual.hidden && flight && !flight.disabled && date && !date.disabled
      && primary && primary.dataset.mode === 'save';
  })()`, 'missing segment completion controls');
  await setValue(page, '#flight-input', 'QF 12');
  await setValue(page, '#date-input', '2026-08-04');
  await evaluate(page, `['#flight-confirm-input', '#date-confirm-input'].forEach((selector) => {
    const input = document.querySelector(selector);
    if (input && !input.disabled) {
      input.checked = true;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    }
  })`);
  await click(page, '[data-action="manual-submit"] button[type="submit"]');
  const completedLocally = `(() => {
    const selector = document.querySelector('[data-segment-selector]');
    const second = document.querySelector('[data-segment-options] [data-segment-index="1"]');
    return selector && !selector.hidden && second
      && second.querySelector('strong').textContent.replace(/\\s+/g, '').startsWith('QF12')
      && window.__fixedAcceptance.resolveRequests.length === ${before};
  })()`;
  try {
    await waitFor(page, completedLocally, 'local completion returns to the itinerary', 5000);
  } catch (error) {
    let conditionValue = null;
    let conditionError = null;
    try {
      conditionValue = await evaluate(page, completedLocally);
    } catch (evaluationError) {
      conditionError = evaluationError.message;
    }
    const state = await evaluate(page, `(() => ({
      conditionValue: ${JSON.stringify(conditionValue)},
      conditionError: ${JSON.stringify(conditionError)},
      resolverCount: window.__fixedAcceptance.resolveRequests.length,
      expectedResolverCount: ${before},
      selectorHidden: document.querySelector('[data-segment-selector]').hidden,
      secondFound: Boolean(document.querySelector('[data-segment-options] [data-segment-index="1"]')),
      secondHeading: document.querySelector('[data-segment-options] [data-segment-index="1"] strong')
        ?.textContent.replace(/\\s+/g, ''),
      flightValidation: document.querySelector('#flight-input').validationMessage,
      dateValidation: document.querySelector('#date-input').validationMessage,
      visibleScreen: document.querySelector('[data-screen]:not([hidden])')?.dataset.screen
    }))()`);
    throw new Error(`${error.message}: ${JSON.stringify(state)}`);
  }
  summary.deterministic.localSaveOnly = true;
  summary.deterministic.requestCounts.saveCompletion = 0;
  await returnFromSegmentSelector(page);
}

async function verifyStaffIconContract(page) {
  const controls = await evaluate(page, `Array.from(document.querySelectorAll('[data-action="staff"]')).map((button) => {
    const svg = button.querySelector('svg.staff-help-icon');
    const geometries = svg ? Array.from(svg.querySelectorAll('path')).map((path) => {
      const box = path.getBBox();
      const style = getComputedStyle(path);
      const strokeWidth = parseFloat(style.strokeWidth) || 0;
      const radius = strokeWidth / 2;
      const measurable = box.width > 0 || box.height > 0;
      return {
        className: path.getAttribute('class') || '', stroke: style.stroke,
        bounded: measurable ? box.x - radius >= -0.05 && box.y - radius >= -0.05
          && box.x + box.width + radius <= 26.05 && box.y + box.height + radius <= 24.05 : null
      };
    }) : [];
      return {
        classes: button.className,
        darkQuestion: button.classList.contains('button-primary')
          || Boolean(button.closest('.step-assistance-actions')),
        accessibleName: button.getAttribute('aria-label') || button.innerText.trim().replace(/\\s+/g, ' '),
      svgCount: button.querySelectorAll('svg').length,
      viewBox: svg && svg.getAttribute('viewBox'),
      ariaHidden: svg && svg.getAttribute('aria-hidden'),
      focusable: svg && svg.getAttribute('focusable'),
      geometries
    };
  })`);
  requireAcceptance(controls.length === 6, 'staff control count must be exactly six', controls.length);
  for (const control of controls) {
    const person = control.geometries.filter((path) => path.className === 'staff-icon-person');
    const question = control.geometries.filter((path) => path.className === 'staff-icon-question');
    requireAcceptance(control.classes.split(/\s+/).includes('staff-help-button'),
      'a data-action="staff" control lacks staff-help-button');
    requireAcceptance(control.accessibleName === 'Ask a member of staff for help',
      'staff control does not expose the complete English accessible name', control.accessibleName);
    requireAcceptance(control.svgCount === 1 && control.viewBox === '0 0 26 24'
      && control.ariaHidden === 'true' && control.focusable === 'false',
    'staff SVG accessibility or viewBox contract failed', control);
    requireAcceptance(person.length === 1 && question.length === 1,
      'staff SVG must contain one person and one question path', control.geometries);
    const expectedQuestion = control.darkQuestion ? 'rgb(208, 161, 58)' : 'rgb(154, 107, 0)';
    requireAcceptance(person[0].stroke === 'rgb(167, 199, 227)'
      && question[0].stroke === expectedQuestion,
    'staff SVG colors changed', { person: person[0].stroke, question: question[0].stroke,
      expectedQuestion, darkQuestion: control.darkQuestion });
    requireAcceptance(person[0].bounded !== false && question[0].bounded !== false,
      'staff SVG painted geometry leaves the 26 by 24 viewBox', control.geometries);
  }
  summary.deterministic.staff.count = controls.length;
  summary.deterministic.staff.iconContract = true;
}

async function verifyStaffSpeech(page) {
  const expected = {
    zh: { text: '请把这个页面给工作人员看。', lang: 'zh-CN' },
    en: { text: 'Show this screen to a member of staff.', lang: 'en-US' },
    es: { text: 'Muestre esta pantalla al personal.', lang: 'es-ES' }
  };
  let lifecyclePassed = true;
  for (const [locale, contract] of Object.entries(expected)) {
    await setValue(page, '#locale-select', locale);
    await delay(100);
    await evaluate(page, `window.__fixedAcceptance.resetSpeech()`);
    await click(page, '[data-departure-passed-confirmation] [data-action="staff"]');
    await waitFor(page, `(() => {
      const overlay = document.querySelector('[data-staff-overlay]');
      return overlay && !overlay.hidden && window.__fixedAcceptance.speech.utterances.length === 1;
    })()`, `${locale} staff speech`);
    const spoken = await evaluate(page, `window.__fixedAcceptance.speech.utterances[0]`);
    requireAcceptance(spoken.text === contract.text && spoken.lang === contract.lang && spoken.rate === 0.88,
      `${locale} staff speech does not match the exact contract`, spoken);
    const cancelsBeforeClose = await evaluate(page, `window.__fixedAcceptance.speech.cancels`);
    await click(page, '[data-action="close-staff"]');
    await waitFor(page, `document.querySelector('[data-staff-overlay]').hidden`, `${locale} staff dialog close`);
    const afterClose = await evaluate(page, `({
      speaking: window.__fixedAcceptance.speech.speaking,
      cancels: window.__fixedAcceptance.speech.cancels
    })`);
    lifecyclePassed = lifecyclePassed && !afterClose.speaking && afterClose.cancels > cancelsBeforeClose;
    summary.deterministic.staff.speech[locale] = spoken;
  }
  requireAcceptance(lifecyclePassed, 'closing the staff dialog did not stop speech');
  summary.deterministic.staff.lifecycle = true;
  await setValue(page, '#locale-select', 'zh');
  await evaluate(page, `window.__fixedAcceptance.resetSpeech()`);
}

async function verifyStaffLocatorExperience(page) {
  await click(page, '.screen-confirm > .confirm-choice-actions [data-action="continue"]');
  await waitFor(page, `(() => {
    const question = document.querySelector('[data-checkin-question]');
    const staff = document.querySelector('.screen-step [data-action="staff"]');
    return question && !question.hidden && staff && !staff.hidden;
  })()`, 'journey staff entry');
  await evaluate(page, `window.__fixedAcceptance.resetSpeech()`);
  await evaluate(page, `window.__fixedAcceptance.staffTrigger = document.querySelector('.screen-step [data-action="staff"]')`);
  await click(page, '.screen-step [data-action="staff"]');
  await waitFor(page, `(() => {
    const overlay = document.querySelector('[data-staff-overlay]');
    const card = document.querySelector('[data-staff-card-view]');
    return overlay && !overlay.hidden && card && !card.hidden;
  })()`, 'staff large card');

  const cardContract = await evaluate(page, `(() => {
    const shell = document.querySelector('[data-staff-overlay] .staff-card');
    const card = document.querySelector('[data-staff-card-view]');
    const find = card.querySelector('[data-action="find-staff"]');
    const extra = card.querySelector('[data-action="open-extra-assistance"]');
    return {
      shellOrder: Array.from(shell.children).map((item) => item.matches('[data-action="close-staff"]')
        ? 'close' : item.matches('[data-staff-card-view]') ? 'card'
          : item.matches('[data-staff-locator-view]') ? 'locator' : 'extra'),
      findFirst: card.firstElementChild === find,
      extraLast: card.lastElementChild === extra,
      findText: find.textContent.trim(), extraText: extra.textContent.trim(),
      closeLabel: shell.querySelector('[data-action="close-staff"]').getAttribute('aria-label'),
      explanationParagraphCount: card.querySelectorAll(':scope > p').length,
      copyCount: card.querySelectorAll('[data-action*="copy"], [data-copy]').length
    };
  })()`);
  requireAcceptance(JSON.stringify(cardContract.shellOrder) === JSON.stringify(['close', 'card', 'locator', 'extra'])
      && cardContract.findFirst && cardContract.extraLast
      && cardContract.findText === '帮我找人'
      && cardContract.extraText === '查看这里可以提供的额外协助'
      && cardContract.closeLabel === '关闭帮我找人窗口'
      && cardContract.explanationParagraphCount === 0 && cardContract.copyCount === 0,
    'staff large-card order or copy-free contract failed', cardContract);
  summary.deterministic.staff.cardOrder = true;

  await click(page, '[data-staff-card-view] [data-action="open-extra-assistance"]');
  await waitFor(page, `(() => {
    const view = document.querySelector('[data-staff-extra-view]');
    return view && !view.hidden && document.activeElement === document.querySelector('#staff-extra-title');
  })()`, 'extra assistance choices');
  const extraContract = await evaluate(page, `(() => {
    const view = document.querySelector('[data-staff-extra-view]');
    return {
      title: view.querySelector('h1').textContent.trim(),
      choices: Array.from(view.querySelectorAll('[data-action="extra-assistance"]')).map((button) => ({
        type: button.dataset.assistanceType, text: button.textContent.trim(), height: button.getBoundingClientRect().height
      })),
      inputs: view.querySelectorAll('input, textarea, [contenteditable="true"]').length
    };
  })()`);
  requireAcceptance(extraContract.title === '查看这里可以提供的额外协助'
      && JSON.stringify(extraContract.choices.map((choice) => choice.type)) === JSON.stringify([
        'MOBILITY_OR_WHEELCHAIR', 'VISION_OR_HEARING', 'BOARDING_OR_TRANSFER'
      ]) && extraContract.choices.every((choice) => choice.height >= 56)
      && extraContract.inputs === 0,
    'extra-assistance preset contract failed', extraContract);
  await click(page, '[data-staff-extra-view] [data-assistance-type="MOBILITY_OR_WHEELCHAIR"]');
  await waitFor(page, `(() => {
    const result = document.querySelector('[data-staff-guidance-result]');
    return result && !result.hidden && window.__fixedAcceptance.staffRequests.length === 1;
  })()`, 'preset assistance AI result');
  const assistanceRequest = await evaluate(page, `window.__fixedAcceptance.staffRequests[0]`);
  requireAcceptance(assistanceRequest.assistanceType === 'MOBILITY_OR_WHEELCHAIR',
    'preset assistance type was not sent', assistanceRequest);
  summary.deterministic.staff.extraAssistance = true;
  await click(page, '[data-staff-locator-view] [data-action="back-to-staff-card"]');
  await waitFor(page, `!document.querySelector('[data-staff-card-view]').hidden`, 'return from preset assistance');

  await click(page, '[data-staff-card-view] [data-action="find-staff"]');
  await waitFor(page, `(() => {
    const result = document.querySelector('[data-staff-guidance-result]');
    return result && !result.hidden && window.__fixedAcceptance.staffRequests.length === 2;
  })()`, 'staff locator AI result');
  const locatorContract = await evaluate(page, `(() => {
    const request = window.__fixedAcceptance.staffRequests[1];
    const cards = Array.from(document.querySelectorAll('[data-staff-location-list] .staff-location-card'));
    const contact = document.querySelector('[data-staff-contact-action]');
    return {
      request,
      cardCount: cards.length,
      resultText: document.querySelector('[data-staff-guidance-result]').innerText,
      contextText: document.querySelector('[data-staff-locator-context]').innerText,
      contact: { hidden: contact.hidden, href: contact.href },
      sourceButtons: document.querySelectorAll('[data-staff-location-list] .staff-location-source').length,
      failureHidden: document.querySelector('[data-staff-guidance-failure]').hidden,
      retryHidden: document.querySelector('[data-action="retry-staff-guidance"]').hidden
    };
  })()`);
  requireAcceptance(locatorContract.request.assistanceType == null
      && locatorContract.request.currentStage === 'CHECK_IN_STATUS'
      && locatorContract.request.securityState === 'BEFORE_SECURITY'
      && locatorContract.request.terminal === 'T2E'
      && locatorContract.request.knownGate === 'B12'
      && locatorContract.request.knownCheckInLocation === '2E'
      && locatorContract.cardCount === 1 && locatorContract.sourceButtons === 1
      && locatorContract.resultText.includes('机场旅客信息服务台')
      && locatorContract.contextText.includes('B12')
      && !locatorContract.contact.hidden && locatorContract.contact.href.startsWith('https://www.parisaeroport.fr/')
      && locatorContract.failureHidden && locatorContract.retryHidden,
    'staff locator did not render the verified structured response safely', locatorContract);
  summary.deterministic.staff.locator = true;

  await evaluate(page, `history.back()`);
  await waitFor(page, `(() => {
    const overlay = document.querySelector('[data-staff-overlay]');
    const card = document.querySelector('[data-staff-card-view]');
    return overlay && !overlay.hidden && card && !card.hidden;
  })()`, 'browser back to staff card');
  summary.deterministic.staff.browserBack = true;
  const cancelsBeforeClose = await evaluate(page, `window.__fixedAcceptance.speech.cancels`);
  await click(page, '[data-action="close-staff"]');
  await waitFor(page, `document.querySelector('[data-staff-overlay]').hidden`, 'close complete staff overlay');
  await delay(500);
  const closeContract = await evaluate(page, `({
    activeAction: document.activeElement && document.activeElement.dataset.action,
    activeTag: document.activeElement && document.activeElement.tagName,
    triggerIsActive: document.activeElement === window.__fixedAcceptance.staffTrigger,
    triggerConnected: Boolean(window.__fixedAcceptance.staffTrigger && window.__fixedAcceptance.staffTrigger.isConnected),
    triggerHidden: window.__fixedAcceptance.staffTrigger && window.__fixedAcceptance.staffTrigger.hidden,
    mainInert: document.querySelector('#main-content').inert,
    speaking: window.__fixedAcceptance.speech.speaking,
    cancels: window.__fixedAcceptance.speech.cancels
  })`);
  requireAcceptance(closeContract.activeAction === 'staff' && !closeContract.speaking
      && closeContract.cancels > cancelsBeforeClose,
    'closing the whole staff overlay did not stop speech and restore focus', closeContract);
}

async function verifyCurrentStaffExperience(page) {
  await click(page, '.screen-confirm > .confirm-choice-actions [data-action="continue"]');
  await activeScreen(page, 'step');
  await evaluate(page, `window.__fixedAcceptance.resetSpeech()`);
  await evaluate(page, `window.__fixedAcceptance.staffTrigger = document.querySelector('.screen-step [data-action="staff"]')`);
  await click(page, '.screen-step [data-action="staff"]');
  await waitFor(page, `!document.querySelector('[data-staff-overlay]').hidden`, 'current staff card');
  const contract = await evaluate(page, `(() => {
    const overlay = document.querySelector('[data-staff-overlay]');
    const card = overlay.querySelector('[data-staff-card-view]');
    return {
      cardVisible: card && !card.hidden,
      resourceHidden: overlay.querySelector('[data-airport-resource-view]').hidden,
      closeCount: overlay.querySelectorAll('[data-action="close-staff"]').length,
      forbiddenCount: overlay.querySelectorAll('[data-action="find-staff"], [data-action="open-extra-assistance"], [data-action*="copy"]').length,
      speechCount: window.__fixedAcceptance.speech.utterances.length
    };
  })()`);
  requireAcceptance(contract.cardVisible && contract.resourceHidden && contract.closeCount === 1
      && contract.forbiddenCount === 0 && contract.speechCount === 1,
    'current staff card contract failed', contract);
  const cancelsBefore = await evaluate(page, `window.__fixedAcceptance.speech.cancels`);
  await click(page, '[data-action="close-staff"]');
  await waitFor(page, `document.querySelector('[data-staff-overlay]').hidden`, 'close current staff card');
  requireAcceptance(await evaluate(page, `window.__fixedAcceptance.speech.cancels > ${cancelsBefore}
    && document.activeElement && document.activeElement.dataset.action === 'staff'`),
    'staff close did not cancel speech or restore focus');
  summary.deterministic.staff.cardOrder = true;
  summary.deterministic.staff.locator = 'NOT_APPLICABLE';
  summary.deterministic.staff.extraAssistance = 'NOT_APPLICABLE';
  summary.deterministic.staff.browserBack = 'NOT_APPLICABLE';
}

async function selectOutboundSegments(page) {
  await uploadFixedDocument(page, 'outbound', 'selected');
  const resolverCount = await evaluate(page, `window.__fixedAcceptance.resolveRequests.length`);
  requireAcceptance(resolverCount === 0, 'resolver ran before final outbound selection', resolverCount);
  summary.deterministic.requestCounts.upload = 0;

  const selectorState = await evaluate(page, `(() => {
    const buttons = Array.from(document.querySelectorAll('[data-segment-options] [data-action="select-segment"]'));
    return buttons.map((button) => ({ disabled: button.disabled, classes: button.className,
      label: button.innerText.trim().replace(/\\s+/g, ' ') }));
  })()`);
  requireAcceptance(selectorState.length === 2 && selectorState.every((segment) => !segment.disabled)
      && !selectorState[0].classes.includes('is-incomplete')
      && !selectorState[1].classes.includes('is-incomplete')
      && !selectorState[1].classes.includes('is-pending')
      && !selectorState[1].classes.includes('is-conflict'),
    'queryable and medium-confidence segments must both remain selectable', selectorState);

  await click(page, '[data-segment-options] [data-segment-index="0"]');
  const firstSegmentReady = `(() => {
    const manual = document.querySelector('[data-screen="manual"]');
    const submit = document.querySelector('[data-action="manual-submit"] button[type="submit"]');
    const flight = document.querySelector('#flight-input');
    const date = document.querySelector('#date-input');
    return manual && !manual.hidden && submit && !submit.disabled
      && submit.dataset.mode === 'query' && flight && flight.value.replace(/\\s+/g, '') === 'MF825'
      && date && date.value === '2026-09-05'
      && window.__fixedAcceptance.resolveRequests.length === 0;
  })()`;
  try {
    await waitFor(page, firstSegmentReady, 'complete first segment waits for explicit query');
  } catch (error) {
    const state = await evaluate(page, `(() => {
      const manual = document.querySelector('[data-screen="manual"]');
      const submit = document.querySelector('[data-action="manual-submit"] button[type="submit"]');
      return { manualHidden: manual && manual.hidden, submitFound: Boolean(submit),
        submitDisabled: submit && submit.disabled, mode: submit && submit.dataset.mode,
        text: submit && submit.textContent.trim(), flight: document.querySelector('#flight-input')?.value,
        date: document.querySelector('#date-input')?.value,
        resolverCount: window.__fixedAcceptance.resolveRequests.length };
    })()`);
    throw new Error(`${error.message}: ${JSON.stringify(state)}`);
  }
  await delay(100);
  requireAcceptance(await evaluate(page, `window.__fixedAcceptance.resolveRequests.length`) === 0,
    'selecting a complete segment unexpectedly called the live resolver');
  summary.deterministic.requestCounts.selectSegment = 0;
  summary.deterministic.requestCounts.openCompletion = 0;
  summary.deterministic.requestCounts.currentYearDate = 0;
  summary.deterministic.explicitQueryOnly = true;

  const rapidClicks = await evaluate(page, `(() => {
    const button = document.querySelector('[data-action="manual-submit"] button[type="submit"]');
    if (!button || button.disabled) return false;
    button.click();
    button.click();
    return true;
  })()`);
  requireAcceptance(rapidClicks, 'query button was unavailable for the double-click guard');
  await waitFor(page, `(() => {
    const panel = document.querySelector('[data-departure-passed-confirmation]');
    return panel && !panel.hidden
      && document.querySelector('[data-departure-passed-flight]').textContent.replace(/\\s+/g, '') === 'MF825'
      && window.__fixedAcceptance.resolveRequests.length === 1;
  })()`, 'departed first segment');
  requireAcceptance(await evaluate(page, `window.__fixedAcceptance.resolveRequests.length`) === 1,
    'rapid double-click produced more than one concurrent request');
  summary.deterministic.doubleClickDeduplicated = true;
  summary.deterministic.requestCounts.rapidDoubleClick = 1;
  summary.deterministic.requestCounts.explicitQuery = 1;
  summary.deterministic.departedFirst = true;
  await click(page, '[data-departure-passed-confirmation] [data-action="open-journey-problem"]');
  await waitFor(page, `!document.querySelector('[data-journey-problem-overlay]').hidden`,
    'departure-passed journey problem dialog');
  requireAcceptance(await evaluate(page, `(() => {
    const step = document.querySelector('[data-journey-problem-connection-step]');
    return step && !step.hidden && step.innerText.trim()
      && step.querySelectorAll('[data-action="journey-problem-connection"]').length === 2;
  })()`), 'departure-passed journey problem dialog did not start at the connection question');
  await click(page, '[data-action="close-journey-problem"]');
  await waitFor(page, `document.querySelector('[data-journey-problem-overlay]').hidden`,
    'close departure-passed journey problem dialog');
  await verifyStaffIconContract(page);
  await verifyStaffSpeech(page);

  await click(page, '[data-departure-passed-confirmation] [data-action="journey-back"]');
  await waitFor(page, `(() => {
    const selector = document.querySelector('[data-segment-selector]');
    return selector && !selector.hidden
      && document.querySelectorAll('[data-segment-options] [data-action="select-segment"]').length === 2;
  })()`, 'return to intact outbound itinerary');
  const restored = await readRenderedSegments(page);
  requireAcceptance(JSON.stringify(renderedSegmentIdentity(restored)) === JSON.stringify(expectedRenderedSegments('outbound')),
    'outbound itinerary changed after returning from departed segment', restored);
  requireAcceptance(await evaluate(page, `window.__fixedAcceptance.resolveRequests.length`) === 1,
    'back navigation unexpectedly resolved another flight');
  summary.deterministic.backPreservesItinerary = true;

  await click(page, '[data-segment-options] [data-segment-index="1"]');
  await waitFor(page, `(() => {
    const manual = document.querySelector('[data-screen="manual"]');
    const flight = document.querySelector('#flight-input');
    const date = document.querySelector('#date-input');
    const confirmation = document.querySelector('#flight-confirm-input');
    const primary = document.querySelector('[data-manual-primary]');
    return manual && !manual.hidden && !document.querySelector('.manual-fields input[maxlength="4"]')
      && flight && flight.disabled && flight.value.replace(/\\s+/g, '') === 'AF1668'
      && date && date.disabled && date.value === '2026-09-05'
      && confirmation && confirmation.disabled
      && primary && primary.dataset.mode === 'query'
      && window.__fixedAcceptance.resolveRequests.length === 1;
  })()`, 'medium-confidence segment is advisory-queryable with a targeted button');
  summary.deterministic.noSeparateYearInput = true;
  summary.deterministic.advisoryQueryable = true;
  summary.deterministic.requestCounts.advisorySelection = 0;
  await click(page, '[data-action="manual-submit"] button[type="submit"]');
  await waitFor(page, `(() => {
    const panel = document.querySelector('[data-departure-passed-confirmation]');
    const heading = document.querySelector('[data-confirm-heading]');
    const continueButton = document.querySelector('.screen-confirm > .confirm-choice-actions [data-action="continue"]');
    return panel && panel.hidden && heading && getComputedStyle(heading).display !== 'none'
      && document.querySelector('#confirm-title').textContent.trim() === '这是你的航班吗？'
      && continueButton && !continueButton.disabled
      && document.querySelector('[data-flight-number]').textContent.replace(/\\s+/g, '') === 'AF1668'
      && window.__fixedAcceptance.resolveRequests.length === 2;
  })()`, 'normal second segment');
  summary.deterministic.secondIsNormal = true;

  const requests = await evaluate(page, `window.__fixedAcceptance.resolveRequests`);
  requireAcceptance(requests.length === 2
    && requests[0].flightNumber === 'MF825' && requests[0].documentSegmentIndex === 0
    && requests[1].flightNumber === 'AF1668' && requests[1].documentSegmentIndex === 1
    && requests[0].departureDate === '2026-09-05' && requests[1].departureDate === '2026-09-05'
    && requests[0].document.segments.length === 2 && requests[1].document.segments.length === 2
    && requests[0].document.segments[0].flightNumber === 'MF825'
    && requests[0].document.segments[1].flightNumber === 'AF1668'
    && requests[0].document.segments[1].departureDate === '2026-09-05'
    && requests[0].document.segments[1].departureMonthDay === '09-05'
    && requests[0].document.segments[1].requiresFlightNumberConfirmation === false
    && requests[0].document.segments[1].flightNumberConfirmedByUser === false
    && requests[1].document.segments[0].flightNumber === 'MF825'
    && requests[1].document.segments[1].flightNumber === 'AF1668'
    && requests[1].document.segments[1].departureDate === '2026-09-05'
    && requests[1].document.segments[1].departureMonthDay === '09-05'
    && requests[1].document.segments[1].requiresFlightNumberConfirmation === false
    && requests[1].document.segments[1].flightNumberConfirmedByUser === false
    && Array.isArray(requests[0].document.reviewIssues) && requests[0].document.reviewIssues.length === 0
    && Array.isArray(requests[1].document.reviewIssues) && requests[1].document.reviewIssues.length === 1
    && requests[1].document.reviewIssues[0].field === 'arrival_airport',
  'resolver payload did not preserve the complete ordered itinerary', requests);
  summary.deterministic.resolverPayloadsPreserveAllSegments = true;
}

async function verifyConfirmationSpeech(page) {
  await delay(250);
  await evaluate(page, `window.__fixedAcceptance.resetSpeech()`);
  const displayed = await evaluate(page, `(() => ({
    flightNumber: document.querySelector('[data-flight-number]').textContent.trim(),
    terminal: document.querySelector('[data-terminal]').textContent.trim(),
    gate: document.querySelector('[data-gate]').textContent.trim(),
    boarding: document.querySelector('[data-boarding-time]').textContent.trim(),
    labels: Array.from(document.querySelectorAll('.flight-details-core [data-i18n]')).map((item) => item.textContent.trim())
  }))()`);
  requireAcceptance(displayed.terminal && displayed.terminal !== '—'
    && displayed.gate && displayed.gate !== '—'
    && displayed.boarding && displayed.boarding !== '—',
  'confirmation did not display terminal, gate, and boarding time', displayed);
  await click(page, '.screen-confirm > .confirm-assistance-actions [data-action="speak"]');
  await waitFor(page, `window.__fixedAcceptance.speech.utterances.length === 1`, 'confirmation speech');
  const spoken = await evaluate(page, `window.__fixedAcceptance.speech.utterances[0]`);
  requireAcceptance(spoken.text.includes('AF 1668') && !spoken.text.includes('MF 825')
    && spoken.text.includes('航站楼') && spoken.text.includes('登机口') && spoken.text.includes('登机时间')
    && spoken.text.includes('2E') && spoken.text.includes('英文字母 B') && spoken.text.includes('12 号登机口')
    && spoken.text.includes('08') && spoken.text.includes('45') && spoken.lang === 'zh-CN' && spoken.rate === 0.88,
  'confirmation speech does not match the displayed second-segment facts', { spoken, displayed });
  await click(page, '.screen-confirm > .confirm-assistance-actions [data-action="speak"]');
  await waitFor(page, `!window.__fixedAcceptance.speech.speaking`, 'confirmation speech stop');
  summary.deterministic.confirmSpeech = { text: spoken.text, displayedFacts: displayed, passed: true };
}

async function measureFamilyActions(page, viewport) {
  await page.send('Emulation.setDeviceMetricsOverride', {
    width: viewport.width, height: viewport.height, deviceScaleFactor: 1, mobile: viewport.width < 768
  });
  return evaluate(page, `(() => {
    const overlay = document.querySelector('[data-family-overlay]');
    const container = overlay.querySelector('.family-actions');
    const buttons = Array.from(container.children).filter((item) => item.matches('button'));
    const rects = buttons.map((button) => {
      const rect = button.getBoundingClientRect();
      return { action: button.dataset.action, label: button.innerText.trim().replace(/\\s+/g, ' '),
        width: rect.width, height: rect.height, top: rect.top, left: rect.left, right: rect.right };
    });
    const scrollWidth = Math.max(document.documentElement.scrollWidth, document.body.scrollWidth);
    return {
      actions: rects.map((item) => item.action), rects,
      trackCount: getComputedStyle(container).gridTemplateColumns.trim().split(/\\s+/).length,
      noOverflow: scrollWidth <= innerWidth + 1
        && rects.every((rect) => rect.left >= -1 && rect.right <= innerWidth + 1)
    };
  })()`);
}

async function verifyFamilySharing(page) {
  await click(page, '.screen-confirm > .confirm-assistance-actions [data-action="family"]');
  await waitFor(page, `(() => {
    const overlay = document.querySelector('[data-family-overlay]');
    const imageButton = document.querySelector('[data-action="share-family-card"]');
    return overlay && !overlay.hidden && window.__fixedAcceptance.shareCreates.length === 1
      && imageButton && !imageButton.disabled;
  })()`, 'family share preparation', 30000);

  const controls = await evaluate(page, `(() => {
    const overlay = document.querySelector('[data-family-overlay]');
    const actions = Array.from(overlay.querySelectorAll('.family-actions > button')).map((button) => ({
      action: button.dataset.action || '', label: button.innerText.trim().replace(/\\s+/g, ' '),
      accessibleName: button.getAttribute('aria-label') || button.innerText.trim().replace(/\\s+/g, ' ')
    }));
    const forbiddenSelectors = [
      'input', 'textarea', 'iframe', 'img', '[data-share-link]', '[data-share-link-box]',
      '[data-share-preview]', '[data-png-preview]', '[data-family-share-url]',
      '.share-link-box', '.family-preview'
    ];
    const forbiddenActions = [
      'create-share', 'preview-share', 'copy-share', 'copy-current', 'stop-share',
      'preview-family', 'copy-family', 'download-family-card', 'preview-family-card',
      'preview-family-share', 'create-family-share', 'stop-family-share'
    ];
    return {
      actions,
      forbiddenUiCount: forbiddenSelectors.reduce((count, selector) => count + overlay.querySelectorAll(selector).length, 0)
        + forbiddenActions.reduce((count, action) => count
          + overlay.querySelectorAll('[data-action="' + action + '"]').length, 0)
    };
  })()`);
  requireAcceptance(JSON.stringify(controls.actions.map((item) => item.action))
      === JSON.stringify(['share-family', 'share-family-card'])
      && controls.actions.every((item) => item.label && item.accessibleName),
  'family dialog must expose exactly the two localized native-share controls', controls.actions);
  requireAcceptance(controls.forbiddenUiCount === 0, 'forbidden family preview/link/copy UI is present', controls);
  summary.deterministic.family.controls = controls.actions;
  summary.deterministic.family.forbiddenUiAbsent = true;

  const viewports = [
    { label: '320x800', width: 320, height: 800 },
    { label: '360x800', width: 360, height: 800 },
    { label: '390x844', width: 390, height: 844 },
    { label: 'desktop-1280x900', width: 1280, height: 900 }
  ];
  for (const viewport of viewports) {
    const measurement = await measureFamilyActions(page, viewport);
    const widths = measurement.rects.map((rect) => rect.width);
    const tops = measurement.rects.map((rect) => rect.top);
    const passed = measurement.actions.length === 2
      && measurement.actions[0] === 'share-family' && measurement.actions[1] === 'share-family-card'
      && measurement.trackCount === 2 && Math.max(...widths) - Math.min(...widths) <= 1
      && Math.max(...tops) - Math.min(...tops) <= 1
      && measurement.rects.every((rect) => rect.height >= 63.5) && measurement.noOverflow;
    requireAcceptance(passed, `family controls failed equal two-column layout at ${viewport.label}`, measurement);
    summary.deterministic.family.responsive[viewport.label] = measurement;
  }

  await click(page, '[data-family-overlay] [data-action="share-family"]');
  await waitFor(page, `window.__fixedAcceptance.nativeShares.length === 1`, 'native link share');
  await click(page, '[data-family-overlay] [data-action="share-family-card"]');
  await waitFor(page, `window.__fixedAcceptance.nativeShares.length === 2`, 'native file share');
  const native = await evaluate(page, `({
    shares: window.__fixedAcceptance.nativeShares,
    canShareCalls: window.__fixedAcceptance.canShareCalls,
    clipboardWrites: window.__fixedAcceptance.clipboardWrites
  })`);
  const expectedUrl = new URL('/share/fixed-view-token', BASE_URL).href;
  requireAcceptance(native.shares[0].url === expectedUrl && native.shares[0].files.length === 0,
    'family link did not use the native URL share contract', native.shares[0]);
  requireAcceptance(native.shares[1].url === null && native.shares[1].files.length === 1
    && native.shares[1].files[0].type === 'image/png' && native.shares[1].files[0].size > 0,
  'family image did not use the native file share contract', native.shares[1]);
  requireAcceptance(native.canShareCalls.some((entry) => entry.files.length === 1),
    'native file-share capability was not checked');
  requireAcceptance(native.clipboardWrites.length === 0, 'family sharing wrote to the clipboard', native.clipboardWrites);
  summary.deterministic.family.nativeLink = true;
  summary.deterministic.family.nativeFile = true;
  summary.deterministic.family.clipboardUnused = true;
}

async function verifyJourneyProblem(page) {
  requireAcceptance(await evaluate(page, `document.querySelector('[data-screen="welcome"] [data-journey-problem-entry]') === null`),
    'welcome exposes the journey-problem entry');
  requireAcceptance(await evaluate(page, `(() => {
    const entry = document.querySelector('[data-departure-passed-confirmation] [data-journey-problem-entry]');
    return entry && entry.closest('[data-departure-passed-confirmation]').hidden;
  })()`), 'ordinary confirmation exposes a visible journey-problem entry');

  const alreadyOnStep = await evaluate(page, `!document.querySelector('[data-screen="step"]').hidden`);
  if (!alreadyOnStep) await click(page, '.screen-confirm > .confirm-choice-actions [data-action="continue"]');
  await activeScreen(page, 'step');
  await waitFor(page, `Boolean(document.querySelector('.screen-step [data-action="open-journey-problem"]'))`, 'journey problem entry');

  await evaluate(page, `(() => {
    const flight = window.__fixedAcceptance.flights.AF1668;
    flight.status = 'DELAYED';
    flight.estimatedDeparture = '2026-09-05T10:20:00+02:00';
    flight.delayMinutes = 55;
    flight.latestChanges = [
      { type: 'STATUS_CHANGED', oldValue: 'SCHEDULED', newValue: 'DELAYED', critical: true, detectedAt: '2026-09-05T07:10:00Z' },
      { type: 'DEPARTURE_TIME_CHANGED', oldValue: '2026-09-05T09:25:00+02:00', newValue: '2026-09-05T10:20:00+02:00', critical: true, detectedAt: '2026-09-05T07:10:00Z' }
    ];
    document.querySelector('[data-action="refresh-flight"]').click();
  })()`);
  await waitFor(page, `Boolean(document.querySelector('.screen-step .flight-change-journey-problem-link'))`, 'journey problem flight-change link');
  await click(page, '.screen-step .flight-change-journey-problem-link');
  requireAcceptance(await evaluate(page, `document.activeElement === document.querySelector('.screen-step [data-action="open-journey-problem"]')`),
    'flight-change link did not focus the gold entry');

  const localeContracts = {
    zh: { question: '这班之后还要转机吗？', mode: 'ANSWER', statement: '航班晚点了，我现在该做什么？',
      answer: '请先到航空公司柜台询问能否继续乘坐当前航班。 请留意新的起飞时间。', confirmation: '请与航空公司工作人员确认。', lang: 'zh-CN' },
    en: { question: 'Do you have another flight after this one?', mode: 'RECOVERY', statement: 'My flight is delayed. What should I do?',
      answer: 'Ask the airline desk whether you can still take this flight. Monitor the updated departure time.', confirmation: 'Please confirm with airline staff.', lang: 'en-US' },
    es: { question: '¿Tienes que hacer conexión después de este vuelo?', mode: 'FAIL', statement: 'Mi vuelo está retrasado. ¿Qué debo hacer?',
      answer: 'Conserva la tarjeta de embarque y explica al personal de la aerolínea lo ocurrido para que confirme el siguiente paso.', confirmation: 'Confírmalo con el personal de la aerolínea.', lang: 'es-ES' }
  };
  const bannedJourneyCopy = /无法找到|没有找到|未找到|找不到|可靠的解决方案|could not find|cannot find|unable to find|no reliable solution|no se pudo encontrar|ninguna solución fiable/i;
  for (const [locale, contract] of Object.entries(localeContracts)) {
    await setValue(page, '#locale-select', locale);
    await click(page, '.screen-step [data-action="open-journey-problem"]');
    await waitFor(page, `!document.querySelector('[data-journey-problem-overlay]').hidden`, `${locale} journey dialog`);
    const initial = await evaluate(page, `(() => ({
      question: document.querySelector('[data-journey-problem-connection-step]').innerText.trim(),
      formHidden: document.querySelector('[data-journey-problem-form]').hidden,
      resultHidden: document.querySelector('[data-journey-problem-result]').hidden,
      buttonCount: document.querySelectorAll('[data-action="journey-problem-connection"]').length
    }))()`);
    requireAcceptance(initial.question.includes(contract.question) && initial.formHidden && initial.resultHidden && initial.buttonCount === 2,
      `${locale} journey dialog initial state is wrong`, initial);
    await click(page, '[data-action="journey-problem-connection"][data-connection="false"]');
    requireAcceptance(await evaluate(page, `document.querySelector('[data-journey-problem-transfer-prompt]').hidden
      && !document.querySelector('[data-journey-problem-form]').hidden`), `${locale} no-connection state is wrong`);
    await evaluate(page, `window.__fixedAcceptance.journeyProblemMode = ${JSON.stringify(contract.mode)};
      window.__fixedAcceptance.resetSpeech()`);
    await setValue(page, '[data-journey-problem-statement]', contract.statement);
    await click(page, '[data-journey-problem-submit]');
    await waitFor(page, `!document.querySelector('[data-journey-problem-result]').hidden`, `${locale} journey result`);
    const localeResult = await evaluate(page, `(() => ({
      answer: document.querySelector('[data-journey-problem-answer]').textContent.trim(),
      confirmation: document.querySelector('[data-journey-problem-confirmation]').textContent.trim(),
      speech: window.__fixedAcceptance.speech.utterances.at(-1),
      cardText: document.querySelector('[data-journey-problem-card]').innerText,
      request: window.__fixedAcceptance.journeyProblemRequests.at(-1)
    }))()`);
    requireAcceptance(localeResult.request.locale === locale
        && localeResult.request.passengerStatement === contract.statement
        && localeResult.answer === contract.answer
        && localeResult.confirmation === contract.confirmation
        && localeResult.speech && localeResult.speech.lang === contract.lang
        && localeResult.speech.text.includes(contract.answer) && localeResult.speech.text.includes(contract.confirmation)
        && !/\b(?:ANSWER|RECOVERY|FALLBACK)\b/.test(localeResult.cardText)
        && !bannedJourneyCopy.test(localeResult.answer),
      `${locale} submitted journey contract failed`, localeResult);
    summary.deterministic.journeyProblem.locales[locale] = true;
    await click(page, '[data-action="close-journey-problem"]');
  }

  await setValue(page, '#locale-select', 'zh');
  await evaluate(page, `window.__fixedAcceptance.journeyProblemMode = 'ANSWER'`);
  await click(page, '.screen-step [data-action="open-journey-problem"]');
  await click(page, '[data-action="journey-problem-connection"][data-connection="true"]');
  requireAcceptance(await evaluate(page, `!document.querySelector('[data-journey-problem-transfer-prompt]').hidden
    && document.querySelector('[data-journey-problem-transfer-prompt]').textContent.trim() === '请尽力说明下一班的起飞时间、航班号和目的地。'
    && Boolean(document.querySelector('[data-action="journey-problem-voice"] svg'))`), 'connection input state is wrong');
  await click(page, '[data-action="journey-problem-voice"]');
  await waitFor(page, `document.querySelector('[data-journey-problem-statement]').value.includes('语音识别的完整情况')`, 'voice recognition success');
  await evaluate(page, `window.__fixedAcceptance.recognitionMode = 'failure'`);
  await click(page, '[data-action="journey-problem-voice"]');
  await waitFor(page, `document.querySelector('[data-journey-problem-input-status]').textContent.trim() === '没有听清，请再说一次，或者打字告诉我。'`, 'voice recognition failure');

  const statement = '原文：AF1668 延误；下一班大概晚上八点，请完整保留。';
  await setValue(page, '[data-journey-problem-statement]', statement);
  await evaluate(page, `window.__fixedAcceptance.resetSpeech()`);
  await click(page, '[data-journey-problem-submit]');
  await waitFor(page, `!document.querySelector('[data-journey-problem-result]').hidden`, 'journey problem result');
  const result = await evaluate(page, `(() => ({
    request: window.__fixedAcceptance.journeyProblemRequests.at(-1),
    answer: document.querySelector('[data-journey-problem-answer]').textContent.trim(),
    confirmation: document.querySelector('[data-journey-problem-confirmation]').textContent.trim(),
    speech: window.__fixedAcceptance.speech.utterances.at(-1)
  }))()`);
  requireAcceptance(result.request.currentFlight.flightNumber === 'AF1668'
      && result.request.currentFlight.status === 'DELAYED'
      && result.request.currentFlight.estimatedDeparture === '2026-09-05T10:20:00+02:00'
      && result.request.passengerStatement === statement
       && result.answer.split(/[。！？]/).filter(Boolean).length <= 2
       && result.confirmation === '请与航空公司工作人员确认。'
      && result.speech && result.speech.text.includes(result.answer) && result.speech.text.includes(result.confirmation),
    'journey problem request/result/speech contract failed', result);

  for (const viewport of [{ label: '320x800', width: 320, height: 800, scale: 1 },
    { label: '390x844', width: 390, height: 844, scale: 1 }, { label: '200%', width: 195, height: 422, scale: 2 }]) {
    await page.send('Emulation.setDeviceMetricsOverride', { width: viewport.width, height: viewport.height,
      deviceScaleFactor: viewport.scale, mobile: true });
    await page.send('Emulation.setPageScaleFactor', { pageScaleFactor: viewport.scale });
    const noOverflow = await hasNoHorizontalOverflow(page);
    requireAcceptance(noOverflow, `journey problem overflow at ${viewport.label}`);
    summary.deterministic.journeyProblem.responsive[viewport.label] = true;
  }
  await page.send('Emulation.setPageScaleFactor', { pageScaleFactor: 1 });
  await page.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  const cancels = await evaluate(page, `window.__fixedAcceptance.speech.cancels`);
  await click(page, '[data-action="close-journey-problem"]');
  requireAcceptance(await evaluate(page, `window.__fixedAcceptance.speech.cancels > ${cancels}`), 'closing journey dialog did not stop speech');

  for (const mode of ['DELAY', 'DELAY_RECOVERY']) {
    await setValue(page, '#locale-select', 'en');
    await evaluate(page, `window.__fixedAcceptance.journeyProblemMode = ${JSON.stringify(mode)}`);
    await click(page, '.screen-step [data-action="open-journey-problem"]');
    await click(page, '[data-action="journey-problem-connection"][data-connection="false"]');
    await setValue(page, '[data-journey-problem-statement]', 'My flight is delayed and I am worried.');
    await click(page, '[data-journey-problem-submit]');
    await click(page, '[data-action="close-journey-problem"]');
    await delay(350);
    const closedState = await evaluate(page, `({
      overlayHidden: document.querySelector('[data-journey-problem-overlay]').hidden,
      answer: document.querySelector('[data-journey-problem-answer]').textContent.trim(),
      resultHidden: document.querySelector('[data-journey-problem-result]').hidden
    })`);
    requireAcceptance(closedState.overlayHidden && !closedState.answer && closedState.resultHidden,
      `${mode} request updated the closed journey card`, closedState);
  }

  await navigate(page, `${BASE_URL}/`);
  await waitFor(page, `Boolean(window.__fixedAcceptance)`, 'historical flight acceptance reset');
  await evaluate(page, `(() => {
    const flight = window.__fixedAcceptance.flights.AF1668;
    flight.departureDate = '2026-07-29';
    flight.scheduledDeparture = '2026-07-29T09:25:00+02:00';
    flight.estimatedDeparture = '2026-07-29T10:20:00+02:00';
    flight.actualDeparture = null;
    flight.scheduledArrival = '2026-07-29T10:00:00+01:00';
    flight.status = 'DELAYED';
    flight.timeContext.serverNow = '2026-08-29T07:00:00Z';
    flight.timeContext.effectiveDeparture = '2026-07-29T10:20:00+02:00';
    flight.timeContext.effectiveArrival = '2026-07-29T10:55:00+01:00';
    flight.timeContext.boardingTime = '2026-07-29T08:45:00+02:00';
  })()`);
  await setValue(page, '#locale-select', 'en');
  await click(page, '[data-screen="welcome"] [data-action="manual"]');
  await setValue(page, '#flight-input', 'AF1668');
  await setValue(page, '#date-input', '2026-07-29');
  await evaluate(page, `document.querySelector('.manual-form').requestSubmit()`);
  await activeScreen(page, 'confirm');
  await waitFor(page, `document.getElementById('departure-passed-title').textContent.trim() === 'This flight’s departure time has passed'`,
    'past-time flight title');
  const historical = await evaluate(page, `(() => ({
    title: document.getElementById('departure-passed-title').textContent.trim(),
    lead: document.querySelector('[data-departure-passed-confirmation] .lead').textContent.trim(),
    status: document.querySelector('[data-departure-passed-status]').textContent.trim(),
    continueDisabled: document.querySelector('[data-action="continue"]').disabled,
    problemVisible: !document.querySelector('[data-departure-passed-confirmation] [data-journey-problem-entry]').hidden
  }))()`);
  requireAcceptance(historical.title === 'This flight’s departure time has passed'
      && historical.lead === 'If you have not boarded, go to the airline service desk or select “Flight problem” below.'
      && historical.status === 'Departure time passed' && historical.continueDisabled && historical.problemVisible,
    'historical flight should use the existing past-time flow', historical);
  await evaluate(page, `window.__fixedAcceptance.journeyProblemMode = 'ANSWER'`);
  await click(page, '[data-departure-passed-confirmation] [data-action="open-journey-problem"]');
  await click(page, '[data-action="journey-problem-connection"][data-connection="false"]');
  await setValue(page, '[data-journey-problem-statement]', 'This flight was delayed last month and I need help now.');
  await click(page, '[data-journey-problem-submit]');
  await waitFor(page, `!document.querySelector('[data-journey-problem-result]').hidden`, 'historical journey result');
  const historicalResult = await evaluate(page, `({
    answer: document.querySelector('[data-journey-problem-answer]').textContent.trim(),
    request: window.__fixedAcceptance.journeyProblemRequests.at(-1)
  })`);
  requireAcceptance(historicalResult.answer === 'Ask the airline desk whether you can still take this flight. Monitor the updated departure time.'
      && historicalResult.request.currentFlight.departureDate === '2026-07-29'
      && historicalResult.request.currentFlight.currentTime
      && historicalResult.request.currentFlight.status === 'DELAYED'
      && !bannedJourneyCopy.test(historicalResult.answer), 'historical journey submission failed', historicalResult);
  summary.deterministic.journeyProblem.historical = true;
  summary.deterministic.journeyProblem.requests = await evaluate(page, `window.__fixedAcceptance.journeyProblemRequests`);
  summary.deterministic.journeyProblem.passed = true;
}

async function runDeterministicContract(page) {
  await testLocalesAndResponsive(page);
  await verifyRepeatedSamples(page);
  await verifyFourItineraryStates(page);
  await verifyLocalCompletionOnly(page);
  await selectOutboundSegments(page);
  await verifyConfirmationSpeech(page);
  await verifyFamilySharing(page);
  await click(page, '[data-family-overlay] [data-action="close-family"]');
  await waitFor(page, `document.querySelector('[data-family-overlay]').hidden`, 'close family before staff locator');
  await verifyCurrentStaffExperience(page);
  await verifyJourneyProblem(page);
  const networkState = await evaluate(page, `({
    apiCalls: window.__fixedAcceptance.apiCalls,
    unexpectedRequests: window.__fixedAcceptance.unexpectedRequests
  })`);
  summary.deterministic.mockedApiCalls = networkState.apiCalls.reduce((counts, call) => {
    const key = `${call.method} ${call.path}`;
    counts[key] = (counts[key] || 0) + 1;
    return counts;
  }, {});
  summary.deterministic.realApiRequests = page.realApiRequests.slice();
  summary.deterministic.unexpectedRequests = networkState.unexpectedRequests;
  requireAcceptance(page.realApiRequests.length === 0,
    'fixed acceptance reached a real API endpoint', page.realApiRequests);
  requireAcceptance(networkState.unexpectedRequests.length === 0,
    'fixed acceptance attempted an unapproved API request', networkState.unexpectedRequests);
  summary.deterministic.passed = summary.deterministic.journeyProblem.passed;
}

function safeRemoveTemp(directory, prefix) {
  const resolved = path.resolve(directory);
  const tempRoot = path.resolve(os.tmpdir());
  if (path.dirname(resolved) === tempRoot && path.basename(resolved).startsWith(prefix)) {
    try { rmSync(resolved, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 }); }
    catch { /* A closing Chrome process may briefly retain a Windows file lock. */ }
  }
}

async function main() {
  const chromePath = CHROME_CANDIDATES.find(existsSync);
  if (!chromePath) throw new Error('Google Chrome was not found; set CHROME_PATH');
  const configuredBaseUrl = new URL(BASE_URL);
  if (configuredBaseUrl.port === '8080') throw new Error('Port 8080 is forbidden for browser acceptance');
  await fetch(`${BASE_URL}/`, { signal: AbortSignal.timeout(5000) }).then((response) => {
    if (!response.ok) throw new Error(`Local application returned HTTP ${response.status}`);
  });

  const port = await reservePort();
  const profileDir = mkdtempSync(path.join(os.tmpdir(), 'onestep-cdp-profile-'));
  const chrome = spawn(chromePath, [
    '--headless=new',
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profileDir}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-background-networking',
    '--disable-component-update',
    '--disable-sync',
    '--window-size=390,844',
    'about:blank'
  ], { stdio: 'ignore', windowsHide: true });
  let fixedPage;
  try {
    await waitForChrome(port);
    fixedPage = await createDeterministicPage(port);
    await runDeterministicContract(fixedPage);
  } finally {
    if (fixedPage) fixedPage.close();
    chrome.kill();
    await delay(300);
    safeRemoveTemp(profileDir, 'onestep-cdp-profile-');
  }

  summary.passed = summary.deterministic.passed
    && summary.locales.zh && summary.locales.en && summary.locales.es
    && Object.values(summary.responsive).every(Boolean)
    && summary.console.errors === 0 && summary.console.javascriptExceptions === 0;
  console.log(JSON.stringify(summary, null, 2));
  if (!summary.passed) process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main().catch((error) => {
  summary.failure = safeFailure(error);
  console.log(JSON.stringify(summary, null, 2));
  process.exitCode = 1;
});
