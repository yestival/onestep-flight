import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync } from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const BASE_URL = process.env.ONESTEP_BASE_URL || 'http://localhost:8080';
const SKIP_DOCUMENTS = process.env.ONESTEP_SKIP_DOCUMENTS === 'true';
const CURRENT_ROUND = process.env.ONESTEP_CURRENT_ROUND === 'true';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FIXTURE_DIR = path.join(ROOT, 'test-fixtures', 'documents');
const FIXTURES = [
  'booking-confirmation.pdf',
  'e-ticket-itinerary.pdf',
  'boarding-pass.pdf',
  'booking-page-screenshot.png',
  'non-flight-document.png'
];
const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Google', 'Chrome', 'Application', 'chrome.exe')
].filter(Boolean);

const summary = {
  browser: 'Chrome DevTools Protocol (Node built-ins)',
  console: { errors: 0, warnings: 0, javascriptExceptions: 0 },
  locales: { zh: false, en: false, es: false, englishHasNoDuplicateDestination: false },
  responsive: { viewport360x800: false, viewport390x844: false, zoom200Percent: false },
  realFlight: { flightNumber: 'BA117', departureDate: '2026-08-24', confirmationReached: false, demo: null },
  qaJourney: { expected: ['CHECK_IN', 'BAG_DROP', 'SECURITY', 'GO_TO_GATE', 'IN_FLIGHT', 'BAGGAGE_CLAIM', 'EXIT_AIRPORT'], observed: [], passed: false },
  sharing: { created: false, familyPageLoaded: false, synchronizedWithin15Seconds: false, syncLatencyMs: null, stopped: false },
  png: { downloaded: false },
  documents: [],
  currentRound: {
    initialLocale: { browserSpanish: false, savedChineseWins: false },
    liveLookup: { reachedConfirmation: false, providerResults: {}, selectedFields: {} },
    confirmationTimes: false,
    checkInFlow: { unsureStaysOnQuestion: false, yesSkips: false, backAllowsChange: false, noShowsCheckIn: false },
    mobileHierarchy: false,
    completeDestination: { zh: null, en: null, passed: false },
    speech: { text: null, rate: null, lang: null, passed: false },
    staffCard: false,
    roundedCorners: false,
    singleBackArrow: false,
    footer: false,
    sharingOrigin: false,
    itinerary: null,
    boardingPass: null,
    desktopNoOverflow: false
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
        documentType: parsed && typeof parsed.documentType === 'string' ? parsed.documentType : null,
        boardingPassDetected: Boolean(parsed && parsed.boardingPassDetected),
        requiresDocumentConfirmation: Boolean(parsed && parsed.requiresDocumentConfirmation),
        documentTypeConfidence: Number(parsed && parsed.fields && parsed.fields.document_type && parsed.fields.document_type.confidence) || 0,
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

async function createPage(port, url, label) {
  const descriptor = await readJson(`http://127.0.0.1:${port}/json/new?${encodeURIComponent(url)}`, { method: 'PUT' });
  const page = await new CdpSession(descriptor.webSocketDebuggerUrl, label).connect();
  await enablePage(page);
  await waitFor(page, `document.readyState === 'complete'`, `${label} load`, 30000);
  return page;
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

  await page.send('Emulation.setDeviceMetricsOverride', { width: 360, height: 800, deviceScaleFactor: 1, mobile: true });
  summary.responsive.viewport360x800 = await hasNoHorizontalOverflow(page);
  await page.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  summary.responsive.viewport390x844 = await hasNoHorizontalOverflow(page);
  await page.send('Emulation.setDeviceMetricsOverride', { width: 195, height: 422, deviceScaleFactor: 2, mobile: true });
  await page.send('Emulation.setPageScaleFactor', { pageScaleFactor: 2 });
  summary.responsive.zoom200Percent = await hasNoHorizontalOverflow(page);
  await page.send('Emulation.setPageScaleFactor', { pageScaleFactor: 1 });
  await page.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
}

async function testRealFlight(page) {
  await navigate(page, `${BASE_URL}/`);
  await setValue(page, '#locale-select', 'en');
  await click(page, '[data-action="manual"]');
  await setValue(page, '#flight-input', 'BA117');
  await setValue(page, '#date-input', '2026-08-24');
  await evaluate(page, `document.querySelector('[data-action="manual-submit"]').requestSubmit()`);
  await waitFor(page, `(() => {
    const confirm = document.querySelector('[data-screen="confirm"]');
    const error = document.querySelector('[data-screen="error"]');
    return confirm && !confirm.hidden || error && !error.hidden;
  })()`, 'real BA117 result', 150000);
  const state = await evaluate(page, `(() => ({
    confirmation: !document.querySelector('[data-screen="confirm"]').hidden,
    flightNumber: document.querySelector('[data-flight-number]').textContent.replace(/\\s/g, ''),
    demo: !document.querySelector('[data-demo-disclaimer]').hidden
  }))()`);
  summary.realFlight.confirmationReached = Boolean(state && state.confirmation && state.flightNumber === 'BA117');
  summary.realFlight.demo = state ? Boolean(state.demo) : null;
}

async function enterQaJourney(page) {
  await setValue(page, '#locale-select', 'zh');
  await waitFor(page, `(() => {
    const confirm = document.querySelector('[data-screen="confirm"]');
    const error = document.querySelector('[data-screen="error"]');
    return confirm && !confirm.hidden || error && !error.hidden;
  })()`, 'QA BA117 lookup', 150000);
  if (await evaluate(page, `!document.querySelector('[data-screen="error"]').hidden`)) throw new Error('QA real flight lookup failed');
  const demo = await evaluate(page, `!document.querySelector('[data-demo-disclaimer]').hidden`);
  if (demo) throw new Error('QA journey received demo data');
  const conflictButtonVisible = await evaluate(page, `(() => { const e = document.querySelector('[data-action="confirm-conflicts"]'); return e && !e.hidden; })()`);
  if (conflictButtonVisible) await click(page, '[data-action="confirm-conflicts"]');
  await click(page, '[data-action="continue"]');
  await activeScreen(page, 'step', 30000);
  await waitFor(page, `(() => { const e = document.querySelector('[data-checkin-question]'); return e && !e.hidden; })()`, 'check-in status question', 30000);
  await click(page, '[data-action="checkin-status"][data-answer="no"]');
  await waitFor(page, `document.querySelector('[data-action-title]').textContent.trim() === '现在办理值机'`, 'QA CHECK_IN', 30000);
  summary.qaJourney.observed.push('CHECK_IN');

  await setValue(page, '#locale-select', 'en');
  const destinationIsSingle = await evaluate(page, `document.querySelector('[data-destination-primary-en]').hidden
    && document.querySelector('[data-destination-context-en]').hidden`);
  await click(page, '[data-action="staff"]');
  const staffCardIsSingle = await evaluate(page, `document.querySelector('[data-staff-title-en]').hidden
    && document.querySelector('[data-staff-en]').hidden
    && document.querySelector('[data-staff-completed-en]').hidden
    && document.querySelector('[data-staff-date-en]').hidden`);
  await click(page, '[data-action="close-staff"]');
  summary.locales.englishHasNoDuplicateDestination = Boolean(destinationIsSingle && staffCardIsSingle);
  await setValue(page, '#locale-select', 'zh');
}

async function currentActionTitle(page) {
  return evaluate(page, `document.querySelector('[data-action-title]').textContent.trim()`);
}

async function advanceQa(page, code, expectedNextTitle) {
  await click(page, '[data-action="complete-action"]');
  if (code === 'IN_FLIGHT') {
    await waitFor(page, `(() => { const e = document.querySelector('[data-landed-overlay]'); return e && !e.hidden; })()`, 'landed confirmation');
    await click(page, '[data-action="confirm-landed"]');
  }
  if (expectedNextTitle) {
    await waitFor(page, `document.querySelector('[data-action-title]').textContent.trim() === ${JSON.stringify(expectedNextTitle)}`, `QA step after ${code}`, 45000);
  } else {
    await activeScreen(page, 'complete', 30000);
  }
}

async function testSharingAndQa(port, browser, downloadDir, pages) {
  const qa = await createPage(port, `${BASE_URL}/?qaJourney=true`, 'qa');
  pages.push(qa);
  await qa.send('Page.setDownloadBehavior', { behavior: 'allow', downloadPath: downloadDir }).catch(() => {});
  await enterQaJourney(qa);

  await click(qa, '[data-action="family"]');
  await click(qa, '[data-action="create-family-share"]');
  await waitFor(qa, `(() => { const e = document.querySelector('[data-family-share-url]'); return e && e.value.length > 0; })()`, 'share creation', 30000);
  summary.sharing.created = true;
  const shareUrl = await evaluate(qa, `document.querySelector('[data-family-share-url]').value`);

  await evaluate(qa, `(() => {
    try {
      Object.defineProperty(navigator, 'share', { configurable: true, value: undefined });
      Object.defineProperty(navigator, 'canShare', { configurable: true, value: undefined });
    } catch {}
    return true;
  })()`);
  await click(qa, '[data-action="share-family-card"]');
  const downloadStarted = Date.now();
  while (Date.now() - downloadStarted < 15000) {
    if (readdirSync(downloadDir).some((name) => name.toLowerCase().endsWith('.png'))) {
      summary.png.downloaded = true;
      break;
    }
    await delay(250);
  }

  const family = await createPage(port, shareUrl, 'family');
  pages.push(family);
  await waitFor(family, `(() => { const e = document.querySelector('[data-view="journey"]'); return e && !e.hidden; })()`, 'family journey', 30000);
  summary.sharing.familyPageLoaded = true;
  const familyInitialTitle = await currentActionTitle(family);

  await click(qa, '[data-action="close-family"]');
  const syncStarted = Date.now();
  await advanceQa(qa, 'CHECK_IN', '现在托运行李');
  summary.qaJourney.observed.push('BAG_DROP');
  await waitFor(family, `document.querySelector('[data-action-title]').textContent.trim() !== ${JSON.stringify(familyInitialTitle)}`, 'family update', 16500);
  summary.sharing.syncLatencyMs = Date.now() - syncStarted;
  summary.sharing.synchronizedWithin15Seconds = summary.sharing.syncLatencyMs <= 15000;

  await click(qa, '[data-action="family"]');
  await click(qa, '[data-action="stop-family-share"]');
  await waitFor(family, `(() => { const e = document.querySelector('[data-view="stopped"]'); return e && !e.hidden; })()`, 'family stopped state', 16500);
  summary.sharing.stopped = true;
  await click(qa, '[data-action="close-family"]');

  const steps = [
    ['BAG_DROP', '现在前往安检', 'SECURITY'],
    ['SECURITY', '现在前往登机口', 'GO_TO_GATE'],
    ['GO_TO_GATE', '已经登机', 'IN_FLIGHT'],
    ['IN_FLIGHT', '现在领取托运行李', 'BAGGAGE_CLAIM'],
    ['BAGGAGE_CLAIM', '现在前往到达出口', 'EXIT_AIRPORT'],
    ['EXIT_AIRPORT', null, null]
  ];
  for (const [code, nextTitle, nextCode] of steps) {
    await advanceQa(qa, code, nextTitle);
    if (nextCode) summary.qaJourney.observed.push(nextCode);
  }
  summary.qaJourney.passed = JSON.stringify(summary.qaJourney.observed) === JSON.stringify(summary.qaJourney.expected);
}

async function uploadFixture(page, fixtureName) {
  await navigate(page, `${BASE_URL}/`);
  const before = page.documentResponses.length;
  const input = await page.send('Runtime.evaluate', {
    expression: `document.querySelector('#ticket-file')`,
    returnByValue: false
  });
  const objectId = input.result && input.result.objectId;
  if (!objectId) throw new Error('Upload input unavailable');
  await page.send('DOM.setFileInputFiles', { files: [path.join(FIXTURE_DIR, fixtureName)], objectId });
  await waitFor(page, `true`, 'upload dispatch', 1000);
  const started = Date.now();
  while (Date.now() - started < 150000 && page.documentResponses.length === before) await delay(250);
  await page.send('Runtime.releaseObject', { objectId }).catch(() => {});
  if (page.documentResponses.length === before) throw new Error('Document extraction response timed out');
  const response = page.documentResponses.at(-1);
  return { fixture: fixtureName, ...response, success: response.status >= 200 && response.status < 300 };
}

async function testFixtures(port, pages) {
  for (const fixture of FIXTURES) {
    if (!existsSync(path.join(FIXTURE_DIR, fixture))) {
      summary.documents.push({ fixture, success: false, failure: 'Fixture is missing' });
      continue;
    }
    const page = await createPage(port, `${BASE_URL}/`, `fixture-${fixture}`);
    pages.push(page);
    try {
      summary.documents.push(await uploadFixture(page, fixture));
    } catch (error) {
      summary.documents.push({ fixture, success: false, failure: safeFailure(error) });
    }
  }
}

async function runStage(name, work) {
  try {
    await work();
  } catch (error) {
    summary[name] = { ...(summary[name] || {}), failure: safeFailure(error) };
  }
}

async function testInitialBrowserLocale(port, pages) {
  const page = await createPage(port, 'about:blank', 'initial-locale');
  pages.push(page);
  const origin = new URL(BASE_URL).origin;
  const injected = await page.send('Page.addScriptToEvaluateOnNewDocument', { source: `(() => {
    try { if (location.origin === ${JSON.stringify(origin)}) localStorage.removeItem('onestep-flight-locale'); } catch {}
    try { Object.defineProperty(navigator, 'languages', { configurable: true, get: () => ['es-ES', 'en-US'] }); } catch {}
    try { Object.defineProperty(navigator, 'language', { configurable: true, get: () => 'es-ES' }); } catch {}
  })();` });
  await navigate(page, `${BASE_URL}/`);
  summary.currentRound.initialLocale.browserSpanish = await evaluate(page,
    `document.documentElement.lang === 'es' && document.querySelector('#locale-select').value === 'es'`);
  if (injected.identifier) await page.send('Page.removeScriptToEvaluateOnNewDocument', { identifier: injected.identifier });
  await evaluate(page, `localStorage.setItem('onestep-flight-locale', 'zh')`);
  await navigate(page, `${BASE_URL}/`);
  summary.currentRound.initialLocale.savedChineseWins = await evaluate(page,
    `document.documentElement.lang === 'zh-CN' && document.querySelector('#locale-select').value === 'zh'`);
}

async function testCurrentRoundLiveUi(port, pages) {
  const page = await createPage(port, `${BASE_URL}/`, 'current-round-live');
  pages.push(page);
  await setValue(page, '#locale-select', 'zh');
  await page.send('Emulation.setDeviceMetricsOverride', { width: 360, height: 800, deviceScaleFactor: 1, mobile: true });
  await click(page, '[data-action="manual"]');
  await setValue(page, '#flight-input', 'BA117');
  await setValue(page, '#date-input', '2026-08-24');
  await evaluate(page, `document.querySelector('[data-action="manual-submit"]').requestSubmit()`);
  await waitFor(page, `(() => {
    const confirm = document.querySelector('[data-screen="confirm"]');
    const error = document.querySelector('[data-screen="error"]');
    return confirm && !confirm.hidden || error && !error.hidden;
  })()`, 'current-round live flight result', 30000);
  if (await evaluate(page, `!document.querySelector('[data-screen="error"]').hidden`)) {
    throw new Error('The one permitted live-flight browser query did not reach confirmation');
  }
  await waitFor(page, `document.querySelector('[data-flight-number]').textContent.replace(/\\s/g, '') === 'BA117'`, 'BA117 confirmation');
  summary.currentRound.liveLookup.reachedConfirmation = true;
  const flightCaptureStarted = Date.now();
  while (!page.flightResponses.length && Date.now() - flightCaptureStarted < 3000) await delay(50);
  if (page.flightResponses.length) {
    const response = page.flightResponses.at(-1);
    summary.currentRound.liveLookup.providerResults = response.providerResults;
    summary.currentRound.liveLookup.selectedFields = response.selectedFields;
  }
  summary.currentRound.confirmationTimes = await evaluate(page, `(() => {
    const departure = document.querySelector('[data-route-departure-time]');
    const arrival = document.querySelector('[data-route-arrival-time]');
    const departureLabel = document.querySelector('[data-route-departure-label]');
    const arrivalLabel = document.querySelector('[data-route-arrival-label]');
    return [departure, arrival, departureLabel, arrivalLabel].every(Boolean)
      && departure.textContent.trim() !== '—' && arrival.textContent.trim() !== '—'
      && departureLabel.textContent.trim().length > 0 && arrivalLabel.textContent.trim().length > 0;
  })()`);
  const conflictVisible = await evaluate(page, `(() => { const e = document.querySelector('[data-action="confirm-conflicts"]'); return e && !e.hidden; })()`);
  if (conflictVisible) await click(page, '[data-action="confirm-conflicts"]');
  await click(page, '[data-action="continue"]');
  await activeScreen(page, 'step', 15000);
  await waitFor(page, `(() => { const e = document.querySelector('[data-checkin-question]'); return e && !e.hidden; })()`, 'check-in question');

  await click(page, '[data-action="checkin-status"][data-answer="unsure"]');
  await waitFor(page, `(() => { const e = document.querySelector('[data-staff-overlay]'); return e && !e.hidden; })()`, 'unsure staff card');
  summary.currentRound.staffCard = await evaluate(page, `(() => {
    const card = document.querySelector('.staff-card').getBoundingClientRect();
    const close = document.querySelector('[data-action="close-staff"]');
    const box = close.getBoundingClientRect();
    return document.querySelector('[data-staff-instruction-en]').textContent.trim() === 'Please show this screen to an airport staff member.'
      && document.querySelector('[data-staff-instruction-primary]').textContent.trim() === '请把这个页面拿给机场工作人员看。'
      && close.getAttribute('aria-label') === '关闭' && box.width >= 48 && box.height >= 48
      && box.left >= card.left && box.right <= card.right && box.top >= card.top && box.bottom <= card.bottom;
  })()`);
  await click(page, '[data-action="close-staff"]');
  summary.currentRound.checkInFlow.unsureStaysOnQuestion = await evaluate(page,
    `!document.querySelector('[data-checkin-question]').hidden && document.querySelector('[data-staff-overlay]').hidden`);

  await click(page, '[data-action="checkin-status"][data-answer="yes"]');
  await waitFor(page, `(() => { const e = document.querySelector('[data-step-question]'); return e && !e.hidden; })()`, 'baggage question after checked in');
  summary.currentRound.checkInFlow.yesSkips = await evaluate(page,
    `document.querySelector('[data-checkin-question]').hidden && !document.querySelector('[data-step-question]').hidden`);
  await click(page, '.screen-step [data-action="journey-back"]');
  await waitFor(page, `(() => { const e = document.querySelector('[data-checkin-question]'); return e && !e.hidden; })()`, 'returned check-in question');
  summary.currentRound.checkInFlow.backAllowsChange = await evaluate(page,
    `document.querySelector('[data-action="checkin-status"][data-answer="yes"]').getAttribute('aria-pressed') !== 'true'`);
  await click(page, '[data-action="checkin-status"][data-answer="no"]');
  await waitFor(page, `(() => { const e = document.querySelector('[data-step-result]'); return e && !e.hidden; })()`, 'check-in action');
  summary.currentRound.checkInFlow.noShowsCheckIn = await evaluate(page,
    `document.querySelector('[data-destination-primary]').textContent.includes('值机')`);

  const visual = await evaluate(page, `(() => {
    const progress = document.querySelector('.step-progress');
    const destination = document.querySelector('[data-destination-primary]');
    const english = document.querySelector('[data-destination-primary-en]');
    const primaryButton = document.querySelector('[data-complete-action]');
    const assistance = document.querySelector('.step-assistance-actions');
    const back = document.querySelector('.screen-step [data-action="journey-back"]');
    const end = document.querySelector('.journey-end-action');
    const time = document.querySelector('.step-time-status');
    const actionTitle = document.querySelector('[data-action-title]');
    const actionTime = document.querySelector('[data-action-time-primary]');
    const label = document.querySelector('[data-action-destination] .destination-label');
    const before = (first, second) => Boolean(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING);
    const enStyle = getComputedStyle(english);
    const primaryStyle = getComputedStyle(destination);
    return {
      progressHeight: progress.getBoundingClientRect().height,
      destinationBottom: destination.getBoundingClientRect().bottom,
      zh: destination.textContent.trim(), en: english.textContent.trim(),
      actionTitle: actionTitle.textContent.trim(), actionTime: actionTime.textContent.trim(),
      englishVisible: !english.hidden && enStyle.display !== 'none',
      englishFirst: before(english, destination), sameColor: enStyle.color === primaryStyle.color,
      labelHidden: !label || label.hidden || getComputedStyle(label).display === 'none',
      order: before(progress, destination) && before(destination, primaryButton) && before(primaryButton, assistance)
        && before(assistance, back) && before(back, end) && before(end, time),
      noOverflow: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) <= innerWidth + 1,
      radii: [document.querySelector('.button'), document.querySelector('.flight-card'),
        document.querySelector('.step-time-status'), document.querySelector('.staff-card')]
        .filter(Boolean).map((element) => parseFloat(getComputedStyle(element).borderTopLeftRadius) || 0),
      backSvgCount: back.querySelectorAll(':scope > svg').length,
      backText: back.textContent.trim(),
      backBefore: getComputedStyle(back, '::before').content
    };
  })()`);
  summary.currentRound.completeDestination.zh = visual.zh;
  summary.currentRound.completeDestination.en = visual.en;
  summary.currentRound.completeDestination.passed = visual.zh.includes('值机') && visual.en.toLowerCase().includes('check in')
    && visual.englishVisible && visual.englishFirst && visual.sameColor && visual.labelHidden;
  summary.currentRound.mobileHierarchy = visual.progressHeight <= 64 && visual.destinationBottom <= 800 && visual.order && visual.noOverflow;
  summary.currentRound.roundedCorners = visual.radii.length >= 4 && visual.radii.every((radius) => radius >= 10);
  summary.currentRound.singleBackArrow = visual.backSvgCount === 1 && !/[←↩]/.test(visual.backText)
    && (visual.backBefore === 'none' || visual.backBefore === 'normal' || visual.backBefore === '"none"');

  await evaluate(page, `(() => {
    window.__speechCapture = { cancelCount: 0, speakCount: 0, speaking: false };
    Object.defineProperty(window, 'SpeechSynthesisUtterance', { configurable: true, value: function (text) { this.text = text; } });
    Object.defineProperty(window, 'speechSynthesis', { configurable: true, value: {
      get speaking() { return window.__speechCapture.speaking; }, pending: false,
      getVoices() { return [{ lang: 'zh-CN', name: 'Test Chinese' }, { lang: 'en-US', name: 'Test English' }]; },
      speak(utterance) { window.__speechCapture.speaking = true; window.__speechCapture.speakCount += 1; window.__speechCapture.text = utterance.text; window.__speechCapture.rate = utterance.rate; window.__speechCapture.lang = utterance.lang; window.__speechCapture.voice = utterance.voice && utterance.voice.lang; },
      cancel() { window.__speechCapture.speaking = false; window.__speechCapture.cancelCount += 1; }
    }});
    return true;
  })()`);
  await click(page, '[data-action="speak"]');
  if (await evaluate(page, `window.__speechCapture.speakCount === 0`)) {
    await click(page, '[data-action="speak"]');
  }
  const speech = await evaluate(page, `(() => ({
    ...window.__speechCapture,
    everyButtonPressed: Array.from(document.querySelectorAll('[data-action="speak"]'))
      .every((button) => button.getAttribute('aria-pressed') === 'true')
  }))()`);
  summary.currentRound.speech = {
    text: speech.text || null, rate: speech.rate || null, lang: speech.lang || null,
    passed: speech.speakCount === 1 && speech.everyButtonPressed
      && speech.text.includes(visual.actionTitle) && speech.text.includes(visual.zh)
      && (visual.actionTime === '—' || speech.text.includes(visual.actionTime))
      && speech.rate >= 0.85 && speech.rate <= 0.9 && speech.lang === 'zh-CN'
  };
  await click(page, '[data-action="speak"]');

  await setValue(page, '#locale-select', 'en');
  const englishOnly = await evaluate(page, `(() => {
    const primary = document.querySelector('[data-destination-primary]');
    const duplicate = document.querySelector('[data-destination-primary-en]');
    return primary.lang === 'en' && primary.textContent.trim().length > 0 && duplicate.hidden;
  })()`);
  summary.locales.englishHasNoDuplicateDestination = englishOnly;
  await setValue(page, '#locale-select', 'zh');
  summary.currentRound.footer = await evaluate(page,
    `document.querySelector('.site-footer').textContent.trim() === '© 2026 OneStep Flight · 无障碍乘机指引'`);

  await click(page, '[data-action="family"]');
  await click(page, '[data-action="create-family-share"]');
  await waitFor(page, `document.querySelector('[data-family-share-url]').value.includes('/share/')`, 'share URL');
  const shareUrl = await evaluate(page, `document.querySelector('[data-family-share-url]').value`);
  summary.currentRound.sharingOrigin = shareUrl.startsWith(new URL(BASE_URL).origin + '/share/')
    && await evaluate(page, `!document.querySelector('[data-share-local-warning]').hidden`);
  const family = await createPage(port, shareUrl, 'current-round-family');
  pages.push(family);
  await page.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  summary.currentRound.desktopNoOverflow = await hasNoHorizontalOverflow(page);
  await family.send('Emulation.setDeviceMetricsOverride', { width: 360, height: 800, deviceScaleFactor: 1, mobile: true });
  await waitFor(family, `(() => { const e = document.querySelector('[data-view="journey"]'); return e && !e.hidden; })()`, 'shared journey');
  summary.currentRound.sharingOrigin = summary.currentRound.sharingOrigin
    && await hasNoHorizontalOverflow(family)
    && await evaluate(family, `document.querySelector('footer').textContent.includes('© 2026 OneStep Flight')`);
}

async function testCurrentRoundDocument(port, pages, fixtureName, kind) {
  const page = await createPage(port, `${BASE_URL}/`, `current-round-${kind}`);
  pages.push(page);
  const result = await uploadFixture(page, fixtureName);
  await activeScreen(page, 'confirm', 70000);
  if (kind === 'itinerary') {
    const ui = await evaluate(page, `(() => {
      const selector = document.querySelector('[data-segment-selector]');
      const options = Array.from(document.querySelectorAll('[data-segment-options] [data-action="select-segment"]'));
      const continueButton = document.querySelector('[data-action="continue"]');
      return { visible: selector && !selector.hidden, optionCount: options.length, continueDisabled: continueButton.disabled };
    })()`);
    if (ui.optionCount) {
      await click(page, '[data-segment-options] [data-action="select-segment"]');
      await waitFor(page, `document.querySelector('[data-flight-number]').textContent.replace(/\\s/g, '').length > 2`, 'selected itinerary segment');
    }
    summary.currentRound.itinerary = { ...result, selectorVisible: ui.visible, optionCount: ui.optionCount,
      requiredChoice: ui.visible && ui.optionCount > 1 && ui.continueDisabled };
  } else {
    const presetReady = result.boardingPassDetected && result.segmentCount === 1;
    summary.currentRound.boardingPass = { ...result, presetExpected: presetReady };
  }
}

async function runCurrentRound(port, pages) {
  await testInitialBrowserLocale(port, pages);
  await testCurrentRoundLiveUi(port, pages);
  await testCurrentRoundDocument(port, pages, 'e-ticket-itinerary.pdf', 'itinerary');
  await testCurrentRoundDocument(port, pages, 'boarding-pass.pdf', 'boarding-pass');
  const current = summary.currentRound;
  summary.passed = Object.values(current.initialLocale).every(Boolean)
    && current.liveLookup.reachedConfirmation && current.confirmationTimes
    && Object.values(current.checkInFlow).every(Boolean)
    && current.mobileHierarchy && current.completeDestination.passed && current.speech.passed
    && current.staffCard && current.roundedCorners && current.singleBackArrow && current.footer
    && current.sharingOrigin && current.desktopNoOverflow
    && current.itinerary && current.itinerary.success && current.itinerary.requiredChoice
    && current.boardingPass && current.boardingPass.success
    && summary.locales.englishHasNoDuplicateDestination
    && summary.console.errors === 0 && summary.console.javascriptExceptions === 0;
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
  const downloadDir = mkdtempSync(path.join(os.tmpdir(), 'onestep-cdp-download-'));
  mkdirSync(downloadDir, { recursive: true });
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
  const pages = [];
  let browser;
  try {
    const version = await waitForChrome(port);
    browser = await new CdpSession(version.webSocketDebuggerUrl, 'browser').connect();
    await browser.send('Browser.setDownloadBehavior', { behavior: 'allow', downloadPath: downloadDir, eventsEnabled: true }).catch(() => {});

    if (CURRENT_ROUND) {
      await runCurrentRound(port, pages);
    } else {
      const basePage = await createPage(port, `${BASE_URL}/`, 'base');
      pages.push(basePage);
      await runStage('locales', () => testLocalesAndResponsive(basePage));
      await runStage('realFlight', () => testRealFlight(basePage));
      await runStage('qaJourney', () => testSharingAndQa(port, browser, downloadDir, pages));
      if (!SKIP_DOCUMENTS) await runStage('documents', () => testFixtures(port, pages));
    }
  } finally {
    pages.forEach((page) => page.close());
    if (browser) browser.close();
    chrome.kill();
    await delay(300);
    safeRemoveTemp(profileDir, 'onestep-cdp-profile-');
    safeRemoveTemp(downloadDir, 'onestep-cdp-download-');
  }

  if (!CURRENT_ROUND) {
    summary.passed = Object.values(summary.locales).every(Boolean)
      && Object.values(summary.responsive).every(Boolean)
      && summary.realFlight.confirmationReached && summary.realFlight.demo === false
      && summary.qaJourney.passed
      && summary.sharing.created && summary.sharing.familyPageLoaded
      && summary.sharing.synchronizedWithin15Seconds && summary.sharing.stopped
      && summary.png.downloaded
      && (SKIP_DOCUMENTS || summary.documents.length === FIXTURES.length && summary.documents.every((item) => item.success))
      && summary.console.errors === 0 && summary.console.javascriptExceptions === 0;
  }
  console.log(JSON.stringify(summary, null, 2));
  if (!summary.passed) process.exitCode = 1;
}

main().catch((error) => {
  summary.failure = safeFailure(error);
  console.log(JSON.stringify(summary, null, 2));
  process.exitCode = 1;
});
