import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';

const BASE_URL = process.env.ONESTEP_BASE_URL || 'http://localhost:8080';
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
    extractedAt: '2026-08-25T00:00:00Z',
    fields: {},
    needsReviewFields: [],
    segments: [
      {
        flightNumber: 'MF825', departureDate: '2026-08-25',
        departureAirport: 'XMN', arrivalAirport: 'CDG',
        scheduledDeparture: '2026-08-25T08:00:00+08:00',
        scheduledArrival: '2026-08-25T14:00:00+02:00',
        boardingTime: '2026-08-25T07:20:00+08:00', terminal: 'T3', gate: '8'
      },
      {
        flightNumber: 'AF1668', departureDate: '2026-08-25',
        departureAirport: 'CDG', arrivalAirport: 'MAN',
        scheduledDeparture: '2026-08-25T18:00:00+02:00',
        scheduledArrival: '2026-08-25T18:30:00+01:00',
        boardingTime: '2026-08-25T17:20:00+02:00', terminal: 'T2E', gate: 'B12'
      }
    ]
  },
  return: {
    documentType: 'ITINERARY',
    dataSource: 'FIXED_ACCEPTANCE',
    extractedAt: '2026-08-25T00:00:00Z',
    fields: {},
    needsReviewFields: [],
    segments: [
      {
        flightNumber: 'KL1036', departureDate: '2026-09-02',
        departureAirport: 'MAN', arrivalAirport: 'AMS',
        scheduledDeparture: '2026-09-02T10:40:00+01:00',
        scheduledArrival: '2026-09-02T13:00:00+02:00',
        boardingTime: '2026-09-02T10:00:00+01:00', terminal: 'T2', gate: '12'
      },
      {
        flightNumber: 'MF812', departureDate: '2026-09-02',
        departureAirport: 'AMS', arrivalAirport: 'XMN',
        scheduledDeparture: '2026-09-02T15:30:00+02:00',
        scheduledArrival: '2026-09-03T09:10:00+08:00',
        boardingTime: '2026-09-02T14:50:00+02:00', terminal: 'T3', gate: 'G7'
      }
    ]
  }
};

const FIXED_FLIGHTS = {
  MF825: {
    flightNumber: 'MF825', departureDate: '2026-08-25',
    departureAirport: 'XMN', departureAirportName: 'Xiamen Gaoqi',
    arrivalAirport: 'CDG', arrivalAirportName: 'Paris Charles de Gaulle',
    scheduledDeparture: '2026-08-25T08:00:00+08:00',
    actualDeparture: '2026-08-25T08:12:00+08:00',
    scheduledArrival: '2026-08-25T14:00:00+02:00',
    actualArrival: '2026-08-25T14:08:00+02:00',
    terminal: 'T3', gate: '8', checkInDeskOrZone: 'H', seat: '18A',
    status: 'DEPARTED', live: true, verified: true, demo: false,
    liveDataSource: 'FIXED_ACCEPTANCE', dataSource: 'FIXED_ACCEPTANCE',
    lastUpdated: '2026-08-25T14:30:00Z', dataLastUpdatedAt: '2026-08-25T14:30:00Z',
    conflicts: [], latestChanges: [], requiresUserConfirmation: false,
    providerResults: { fixedAcceptance: { outcome: 'SUCCESS', quality: 'EXACT', fromCache: false } },
    timeContext: {
      serverNow: '2026-08-25T14:30:00Z', departureUtcOffset: '+08:00', arrivalUtcOffset: '+02:00',
      effectiveDeparture: '2026-08-25T08:12:00+08:00',
      effectiveArrival: '2026-08-25T14:08:00+02:00',
      boardingTime: '2026-08-25T07:20:00+08:00',
      dataRetrievedAt: '2026-08-25T14:30:00Z', dataLastUpdatedAt: '2026-08-25T14:30:00Z'
    }
  },
  AF1668: {
    flightNumber: 'AF1668', departureDate: '2026-08-25',
    departureAirport: 'CDG', departureAirportName: 'Paris Charles de Gaulle',
    arrivalAirport: 'MAN', arrivalAirportName: 'Manchester',
    scheduledDeparture: '2026-08-25T18:00:00+02:00',
    scheduledArrival: '2026-08-25T18:30:00+01:00',
    terminal: 'T2E', gate: 'B12', checkInDeskOrZone: '2E', seat: '22C',
    status: 'SCHEDULED', live: true, verified: true, demo: false,
    liveDataSource: 'FIXED_ACCEPTANCE', dataSource: 'FIXED_ACCEPTANCE',
    lastUpdated: '2026-08-25T14:30:00Z', dataLastUpdatedAt: '2026-08-25T14:30:00Z',
    conflicts: [], latestChanges: [], requiresUserConfirmation: false,
    providerResults: { fixedAcceptance: { outcome: 'SUCCESS', quality: 'EXACT', fromCache: false } },
    timeContext: {
      serverNow: '2026-08-25T14:30:00Z', departureUtcOffset: '+02:00', arrivalUtcOffset: '+01:00',
      effectiveDeparture: '2026-08-25T18:00:00+02:00',
      effectiveArrival: '2026-08-25T18:30:00+01:00',
      boardingTime: '2026-08-25T17:20:00+02:00',
      dataRetrievedAt: '2026-08-25T14:30:00Z', dataLastUpdatedAt: '2026-08-25T14:30:00Z'
    }
  }
};

const summary = {
  browser: 'Chrome DevTools Protocol (Node built-ins)',
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
    departedFirst: false,
    backPreservesItinerary: false,
    secondIsNormal: false,
    resolverPayloadsPreserveAllSegments: false,
    staff: { count: 0, iconContract: false, speech: {}, lifecycle: false },
    confirmSpeech: { text: null, displayedFacts: null, passed: false },
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
    .slice(0, 180);
}

async function reservePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      server.close((error) => error ? reject(error) : resolve(port));
    });
  });
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
  return evaluate(page, `Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) <= window.innerWidth + 1`);
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
  await page.send('Emulation.setPageScaleFactor', { pageScaleFactor: 1 });
  await page.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
}

function requireAcceptance(condition, message, details) {
  if (!condition) {
    throw new Error(`${message}${details === undefined ? '' : `: ${JSON.stringify(details)}`}`);
  }
}

function deterministicInjectionSource() {
  return `(() => {
    const clone = (value) => JSON.parse(JSON.stringify(value));
    const samples = ${JSON.stringify(FIXED_DOCUMENT_SAMPLES)};
    const flights = ${JSON.stringify(FIXED_FLIGHTS)};
    const state = window.__fixedAcceptance = {
      selectedSample: 'outbound',
      apiCalls: [],
      resolveRequests: [],
      shareCreates: [],
      unexpectedRequests: [],
      nativeShares: [],
      canShareCalls: [],
      clipboardWrites: [],
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
      if (url.pathname === '/api/share-sessions' && method === 'POST') {
        let request = {};
        try { request = JSON.parse(String(init.body || '{}')); } catch (_) {}
        state.shareCreates.push(clone(request));
        return response({
          viewToken: 'fixed-view-token', updateToken: 'fixed-update-token',
          viewUrl: location.origin + '/share/fixed-view-token',
          expiresAt: '2026-08-27T14:30:00Z'
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
      accessibleName: button.getAttribute('aria-label') || button.innerText.trim().replace(/\\s+/g, ' '),
      svgCount: button.querySelectorAll('svg').length,
      viewBox: svg && svg.getAttribute('viewBox'),
      ariaHidden: svg && svg.getAttribute('aria-hidden'),
      focusable: svg && svg.getAttribute('focusable'),
      geometries
    };
  })`);
  requireAcceptance(controls.length === 5, 'staff control count must be exactly five', controls.length);
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
    requireAcceptance(person[0].stroke === 'rgb(167, 199, 227)'
      && question[0].stroke === 'rgb(255, 209, 102)',
    'staff SVG colors changed', { person: person[0].stroke, question: question[0].stroke });
    requireAcceptance(person[0].bounded !== false && question[0].bounded !== false,
      'staff SVG painted geometry leaves the 26 by 24 viewBox', control.geometries);
  }
  summary.deterministic.staff.count = controls.length;
  summary.deterministic.staff.iconContract = true;
}

async function verifyStaffSpeech(page) {
  const expected = {
    zh: { text: '请把这个页面拿给工作人员看', lang: 'zh-CN' },
    en: { text: 'Please show this page to a member of staff.', lang: 'en-US' },
    es: { text: 'Por favor, muestra esta página a un miembro del personal.', lang: 'es-ES' }
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

async function selectOutboundSegments(page) {
  await uploadFixedDocument(page, 'outbound', 'selected');
  const resolverCount = await evaluate(page, `window.__fixedAcceptance.resolveRequests.length`);
  requireAcceptance(resolverCount === 0, 'resolver ran before final outbound selection', resolverCount);

  await click(page, '[data-segment-options] [data-segment-index="0"]');
  await waitFor(page, `(() => {
    const panel = document.querySelector('[data-departure-passed-confirmation]');
    return panel && !panel.hidden
      && document.querySelector('[data-departure-passed-flight]').textContent.replace(/\\s+/g, '') === 'MF825'
      && window.__fixedAcceptance.resolveRequests.length === 1;
  })()`, 'departed first segment');
  summary.deterministic.departedFirst = true;
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
    const panel = document.querySelector('[data-departure-passed-confirmation]');
    const heading = document.querySelector('[data-confirm-heading]');
    const continueButton = document.querySelector('.screen-confirm > .confirm-choice-actions [data-action="continue"]');
    return panel && panel.hidden
      && heading && getComputedStyle(heading).display !== 'none'
      && document.querySelector('#confirm-title').textContent.trim() === '这是你的航班吗？'
      && continueButton && !continueButton.disabled
      && document.querySelector('[data-flight-number]').textContent.replace(/\\s+/g, '') === 'AF1668'
      && window.__fixedAcceptance.resolveRequests.length === 2;
  })()`, 'normal second segment');
  summary.deterministic.secondIsNormal = true;

  const requests = await evaluate(page, `window.__fixedAcceptance.resolveRequests`);
  const expectedSegments = FIXED_DOCUMENT_SAMPLES.outbound.segments;
  requireAcceptance(requests.length === 2
    && requests[0].flightNumber === 'MF825' && requests[0].documentSegmentIndex === 0
    && requests[1].flightNumber === 'AF1668' && requests[1].documentSegmentIndex === 1
    && JSON.stringify(requests[0].document && requests[0].document.segments) === JSON.stringify(expectedSegments)
    && JSON.stringify(requests[1].document && requests[1].document.segments) === JSON.stringify(expectedSegments),
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
    && spoken.text.includes(displayed.terminal) && spoken.text.includes(displayed.gate)
    && spoken.text.includes(displayed.boarding) && spoken.lang === 'zh-CN' && spoken.rate === 0.88,
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

async function runDeterministicContract(page) {
  await testLocalesAndResponsive(page);
  await verifyRepeatedSamples(page);
  await selectOutboundSegments(page);
  await verifyConfirmationSpeech(page);
  await verifyFamilySharing(page);
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
  summary.deterministic.passed = true;
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

main().catch((error) => {
  summary.failure = safeFailure(error);
  console.log(JSON.stringify(summary, null, 2));
  process.exitCode = 1;
});
