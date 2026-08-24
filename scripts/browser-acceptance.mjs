import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync } from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const BASE_URL = process.env.ONESTEP_BASE_URL || 'http://localhost:8080';
const SKIP_DOCUMENTS = process.env.ONESTEP_SKIP_DOCUMENTS === 'true';
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
      if (url.pathname === '/api/documents/boarding-pass') {
        page.documentRequests.set(requestId, { status: response.status });
      }
    } catch {
      // Ignore malformed/non-HTTP URLs; no response content is logged.
    }
  });
  page.on('Network.loadingFinished', async ({ requestId }) => {
    const metadata = page.documentRequests.get(requestId);
    if (!metadata) return;
    page.documentRequests.delete(requestId);
    let parsed = null;
    try {
      const result = await page.send('Network.getResponseBody', { requestId });
      const body = result.base64Encoded ? Buffer.from(result.body, 'base64').toString('utf8') : result.body;
      parsed = JSON.parse(body);
    } catch {
      // The status is still recorded without exposing an unreadable response.
    }
    page.documentResponses.push({
      status: metadata.status,
      documentType: parsed && typeof parsed.documentType === 'string' ? parsed.documentType : null,
      boardingPassDetected: Boolean(parsed && parsed.boardingPassDetected),
      requiresDocumentConfirmation: Boolean(parsed && parsed.requiresDocumentConfirmation),
      needsReviewFields: Array.isArray(parsed && parsed.needsReviewFields)
        ? parsed.needsReviewFields.filter((field) => typeof field === 'string').slice(0, 20) : []
    });
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

    const basePage = await createPage(port, `${BASE_URL}/`, 'base');
    pages.push(basePage);
    await runStage('locales', () => testLocalesAndResponsive(basePage));
    await runStage('realFlight', () => testRealFlight(basePage));
    await runStage('qaJourney', () => testSharingAndQa(port, browser, downloadDir, pages));
    if (!SKIP_DOCUMENTS) await runStage('documents', () => testFixtures(port, pages));
  } finally {
    pages.forEach((page) => page.close());
    if (browser) browser.close();
    chrome.kill();
    await delay(300);
    safeRemoveTemp(profileDir, 'onestep-cdp-profile-');
    safeRemoveTemp(downloadDir, 'onestep-cdp-download-');
  }

  summary.passed = Object.values(summary.locales).every(Boolean)
    && Object.values(summary.responsive).every(Boolean)
    && summary.realFlight.confirmationReached && summary.realFlight.demo === false
    && summary.qaJourney.passed
    && summary.sharing.created && summary.sharing.familyPageLoaded
    && summary.sharing.synchronizedWithin15Seconds && summary.sharing.stopped
    && summary.png.downloaded
    && (SKIP_DOCUMENTS || summary.documents.length === FIXTURES.length && summary.documents.every((item) => item.success))
    && summary.console.errors === 0 && summary.console.javascriptExceptions === 0;
  console.log(JSON.stringify(summary, null, 2));
  if (!summary.passed) process.exitCode = 1;
}

main().catch((error) => {
  summary.failure = safeFailure(error);
  console.log(JSON.stringify(summary, null, 2));
  process.exitCode = 1;
});
