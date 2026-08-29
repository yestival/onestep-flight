import { createReadStream, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const STATIC_ROOT = path.join(ROOT, 'src', 'main', 'resources', 'static');
const SCREENSHOT = path.join(ROOT, 'target', 'airport-resources-smoke-390.png');
const CHROME_CANDIDATES = [process.env.CHROME_PATH,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Google', 'Chrome', 'Application', 'chrome.exe')]
  .filter(Boolean);
const delay = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

function requireSmoke(condition, message, details) {
  if (!condition) throw new Error(`${message}: ${JSON.stringify(details)}`);
}

function loadComparisonReplay() {
  const fixture = process.env.AIRPORT_COMPARISON_REPLAY;
  if (!fixture) return null;
  const data = JSON.parse(readFileSync(fixture, 'utf8'));
  const selected = data.results && data.results.find((item) => item.version === 'V1' && item.airport === 'DUB');
  requireSmoke(data.selectedWinner === 'V1' && selected, 'V1 DUB replay result is missing', {
    selectedWinner: data.selectedWinner
  });
  requireSmoke(selected.mapStatus === 'OFFICIAL_DIRECT_MAP'
    && selected.contactStatus === 'GENERAL_PASSENGER_PHONE', 'V1 DUB replay result changed', selected);
  return selected;
}

const COMPARISON_REPLAY = loadComparisonReplay();

async function reservePort() {
  const port = await new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const value = server.address().port;
      server.close((error) => error ? reject(error) : resolve(value));
    });
  });
  return port === 8080 ? reservePort() : port;
}

async function readJson(url, options = {}) {
  const response = await fetch(url, { ...options, signal: AbortSignal.timeout(3000) });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

async function waitForChrome(port) {
  const started = Date.now();
  while (Date.now() - started < 15000) {
    try { return await readJson(`http://127.0.0.1:${port}/json/version`); }
    catch { await delay(200); }
  }
  throw new Error('Chrome did not start');
}

class CdpSession {
  constructor(url) { this.url = url; this.nextId = 1; this.pending = new Map(); this.listeners = new Map(); }
  async connect() {
    this.socket = new WebSocket(this.url);
    await new Promise((resolve, reject) => {
      this.socket.addEventListener('open', resolve, { once: true });
      this.socket.addEventListener('error', () => reject(new Error('CDP connection failed')), { once: true });
    });
    this.socket.addEventListener('message', (event) => this.receive(event.data));
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
    for (const callback of this.listeners.get(message.method) || []) callback(message.params || {});
  }
  on(method, callback) { this.listeners.set(method, [...(this.listeners.get(method) || []), callback]); }
  send(method, params = {}) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject, method });
      this.socket.send(JSON.stringify({ id, method, params }));
    });
  }
  close() { if (this.socket && this.socket.readyState < 2) this.socket.close(); }
}

async function evaluate(page, expression) {
  const result = await page.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (result.exceptionDetails) throw new Error(`Page evaluation failed: ${JSON.stringify(result.exceptionDetails)}`);
  return result.result && result.result.value;
}

async function click(page, selector) {
  const clicked = await evaluate(page, `(() => { const item = document.querySelector(${JSON.stringify(selector)});
    if (!item || item.hidden || item.disabled) return false;
    item.focus({ preventScroll: true }); item.click(); return true; })()`);
  requireSmoke(clicked, 'Control was not clickable', { selector });
}

async function waitFor(page, expression, label) {
  const started = Date.now();
  while (Date.now() - started < 10000) {
    try { if (await evaluate(page, `Boolean(${expression})`)) return; } catch { /* navigation */ }
    await delay(100);
  }
  throw new Error(`${label} timed out`);
}

function startStaticServer() {
  const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png' };
  const server = createServer((request, response) => {
    const url = new URL(request.url, 'http://127.0.0.1');
    const relative = url.pathname === '/' ? 'index.html' : decodeURIComponent(url.pathname).replace(/^\/+/, '');
    const target = path.resolve(STATIC_ROOT, relative);
    if (!target.startsWith(STATIC_ROOT + path.sep) || !existsSync(target)) {
      response.writeHead(404); response.end(); return;
    }
    response.writeHead(200, { 'Content-Type': types[path.extname(target)] || 'application/octet-stream',
      'Cache-Control': 'no-store' });
    createReadStream(target).pipe(response);
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      if (port !== 8080) resolve({ server, port });
      else server.close(() => startStaticServer().then(resolve, reject));
    });
  });
}

function injectionSource() {
  const replay = COMPARISON_REPLAY;
  const replayCode = replay ? replay.airport : 'STR';
  const replayTerminal = replay ? replay.terminal : 'Terminal 3';
  const replayMapUrl = replay ? `https://${replay.mapCandidate}` : 'https://maps.starport.test/terminal-3';
  const replayContactUrl = replay ? `https://${replay.contactCandidate}` : 'https://starport.test/contact';
  const replayPhone = replay ? replay.phone : '+99 12 3456 7890';
  const replayTelUri = `tel:${replayPhone.replace(/[^+\d]/g, '')}`;
  const saved = {
    savedAt: Date.now(), locale: 'es', screen: 'confirm', conflictsConfirmed: false,
    flight: { flightNumber: 'SF100', departureDate: '2026-08-30',
      departureAirport: replayCode, departureAirportName: replay ? 'Dublin Airport' : 'Starport International Airport', terminal: replayTerminal,
      arrivalAirport: 'PLN', arrivalAirportName: 'Plainport International Airport', arrivalTerminal: 'Terminal 2',
      gate: 'A12', scheduledDeparture: '2026-08-30T12:00:00+02:00',
      effectiveDeparture: '2026-08-30T12:00:00+02:00', boardingTime: '2026-08-30T11:20:00+02:00',
      dataRetrievedAt: '2026-08-29T00:00:00Z', status: 'SCHEDULED', live: true,
      timeContext: { serverNow: '2026-08-29T00:00:00Z', departureLocalNow: '2026-08-29T02:00:00+02:00',
        departureUtcOffset: '+02:00', boardingTime: '2026-08-30T11:20:00+02:00',
        scheduledDeparture: '2026-08-30T12:00:00+02:00', effectiveDeparture: '2026-08-30T12:00:00+02:00' } },
    action: null,
    context: { departureDate: '2026-08-30', checkedIn: true, bagDropped: true, securityCleared: true,
      atGate: false, boarded: false, landed: false, baggageCollected: false, exitedAirport: false,
      needsAssistance: false }, history: [], events: []
  };
  const response = { airportCode: replayCode, airportName: replay ? 'Dublin Airport' : 'Starport International Airport', terminal: replayTerminal,
    localAirportName: replay ? 'Dublin Airport' : 'Aeropuerto Internacional Starport', countryOrRegion: replay ? 'Ireland' : 'España',
    map: { status: 'FOUND', url: replayMapUrl,
      sourceUrl: replayMapUrl, sourceTitle: replay ? 'Dublin Airport Map' : 'Mapa interactivo de la Terminal 3',
      resultType: 'DIRECT_INTERACTIVE_MAP', sourceLanguage: replay ? 'en' : 'es', mapScope: 'REQUESTED_TERMINAL',
      verificationReason: 'Official map', rejectedCandidateTypes: ['GUIDE_OR_GENERIC_PAGE'], fromCache: false },
    contact: { status: 'PHONE_FOUND', phoneDisplay: replayPhone, telUri: replayTelUri,
      contactUrl: replayContactUrl, sourceUrl: replayContactUrl,
      sourceTitle: replay ? 'Dublin Airport Contact Us' : 'Contacto de pasajeros', sourceLanguage: replay ? 'en' : 'es', sourceType: 'AI_MODE',
      phoneRole: 'general passenger call centre', evidenceTier: 'AI_OFFICIAL_DIRECT',
      airportIdentityConfirmed: true, aiDedicatedPhoneField: true, mapsStructuredPhonePresent: false,
      phoneConflict: false, fromCache: false }, serpApiRequestCount: 3, rejectionReasons: [],
    retrievedAt: '2026-08-29T00:00:00Z', fromCache: false };
  return `(() => {
    sessionStorage.setItem('onestep-flight-journey-v2', ${JSON.stringify(JSON.stringify(saved))});
    localStorage.setItem('onestep-flight-locale', 'es');
    window.__airportSmoke = { requests: [] };
    const originalFetch = window.fetch.bind(window);
    window.fetch = (input, init = {}) => {
      const url = new URL(typeof input === 'string' ? input : input.url, location.href);
      if (url.pathname === '/api/assistance/airport-resources') {
        const payload = JSON.parse(String(init.body || '{}'));
        window.__airportSmoke.requests.push(payload);
        const result = Object.assign({}, ${JSON.stringify(response)}, {
          airportCode: payload.airportCode,
          airportName: payload.airportName || payload.airportCode,
          terminal: payload.terminal || null,
          localAirportName: payload.airportCode === 'QAZ' ? 'Aeropuerto de prueba local' : ${JSON.stringify(response.localAirportName)}
        });
        return Promise.resolve(new Response(JSON.stringify(result), {
          status: 200, headers: { 'Content-Type': 'application/json' }
        }));
      }
      return originalFetch(input, init);
    };
  })();`;
}

function safeRemoveProfile(directory) {
  const resolved = path.resolve(directory);
  if (path.dirname(resolved) === path.resolve(os.tmpdir())
      && path.basename(resolved).startsWith('onestep-resources-smoke-')) {
    try { rmSync(resolved, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 }); } catch { /* lock */ }
  }
}

async function main() {
  const chromePath = CHROME_CANDIDATES.find(existsSync);
  if (!chromePath) throw new Error('Google Chrome was not found');
  const { server, port: appPort } = await startStaticServer();
  const cdpPort = await reservePort();
  const profile = mkdtempSync(path.join(os.tmpdir(), 'onestep-resources-smoke-'));
  const chrome = spawn(chromePath, ['--headless=new', `--remote-debugging-port=${cdpPort}`,
    `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check', '--disable-background-networking',
    '--disable-component-update', '--disable-sync', '--window-size=390,844', 'about:blank'],
  { stdio: 'ignore', windowsHide: true });
  let page;
  const summary = { viewport: '390x844', appPort, qaVisibleOnlyOnConfirm: false, qaRounded: false,
    qaNoAirportName: false, qaSingleCenteredQuery: false, qaConciseResult: false, qaDebugAbsent: false,
    noAutomaticAirportRequest: false, journeyButtons: false, mapPageClose: false, contactPageClose: false,
    separateTargets: false, helpTopbarOnlyContact: false, helpNoOverlap: false, familySvgClose: false,
    svgCentered: false, focusRestored: false, noHorizontalOverflow: false, consoleErrors: 0,
    requests: 0, replayedVersion: COMPARISON_REPLAY && COMPARISON_REPLAY.version,
    replayedAirport: COMPARISON_REPLAY && COMPARISON_REPLAY.airport, screenshot: SCREENSHOT };
  try {
    await waitForChrome(cdpPort);
    const descriptor = await readJson(`http://127.0.0.1:${cdpPort}/json/new?about%3Ablank`, { method: 'PUT' });
    page = await new CdpSession(descriptor.webSocketDebuggerUrl).connect();
    page.on('Runtime.consoleAPICalled', ({ type }) => { if (type === 'error' || type === 'assert') summary.consoleErrors += 1; });
    page.on('Runtime.exceptionThrown', () => { summary.consoleErrors += 1; });
    await Promise.all([page.send('Page.enable'), page.send('Runtime.enable')]);
    await page.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    await page.send('Page.addScriptToEvaluateOnNewDocument', { source: injectionSource() });
    await page.send('Page.navigate', { url: `http://127.0.0.1:${appPort}/?qaAirport=1` });
    try {
      await waitFor(page, `(() => { const panel = document.querySelector('[data-qa-airport-panel]');
        const confirm = document.querySelector('[data-screen="confirm"]');
        return document.readyState === 'complete' && panel && !panel.hidden && confirm && !confirm.hidden; })()`, 'confirmation QA panel');
    } catch (error) {
      const diagnostics = await evaluate(page, `(() => ({ readyState: document.readyState,
        visibleScreen: Array.from(document.querySelectorAll('[data-screen]')).find((item) => !item.hidden)?.dataset.screen || null,
        qaPresent: Boolean(document.querySelector('[data-qa-airport-panel]')),
        qaHidden: document.querySelector('[data-qa-airport-panel]')?.hidden,
        bodyText: document.body?.innerText?.slice(0, 160) || '' }))()`);
      throw new Error(`${error.message}: ${JSON.stringify(diagnostics)}`);
    }

    Object.assign(summary, await evaluate(page, `(() => {
      const panel = document.querySelector('[data-qa-airport-panel]');
      const form = panel.querySelector('[data-qa-airport-form]');
      const button = form.querySelector('button[type="submit"]');
      const panelRect = panel.getBoundingClientRect(); const buttonRect = button.getBoundingClientRect();
      return { qaRounded: parseFloat(getComputedStyle(panel).borderRadius) > 0,
        qaNoAirportName: !form.elements.airportName && !/airport english name|机场英文名称/i.test(panel.textContent),
        qaSingleCenteredQuery: form.querySelectorAll('button[type="submit"]').length === 1
          && buttonRect.height >= 44 && buttonRect.width < panelRect.width
          && Math.abs((buttonRect.left + buttonRect.right) / 2 - (panelRect.left + panelRect.right) / 2) < 2,
        noAutomaticAirportRequest: window.__airportSmoke.requests.length === 0 };
    })()`));

    await evaluate(page, `(() => { const form = document.querySelector('[data-qa-airport-form]');
      form.elements.airportCode.value = 'qaz'; form.elements.terminal.value = '1'; form.requestSubmit(); })()`);
    await waitFor(page, `document.querySelectorAll('[data-qa-airport-result] .qa-airport-action').length >= 2`, 'concise QA result');
    Object.assign(summary, await evaluate(page, `(() => {
      const output = document.querySelector('[data-qa-airport-result]');
      return { qaConciseResult: /Aeropuerto de prueba local/.test(output.textContent)
          && !/PHONE_FOUND|OFFICIAL_MAP|GOOGLE_MAPS_STRUCTURED|https:\\/\\/|tel:/.test(output.textContent),
        qaDebugAbsent: !output.querySelector('details') && !document.querySelector('[data-qa-airport-debug]')
          && !/debug|depuraci[oó]n|调试/i.test(output.textContent),
        separateTargets: window.__airportSmoke.requests[0].refreshTarget === 'BOTH'
          && window.__airportSmoke.requests[0].forceRefresh === true };
    })()`));

    await click(page, '[data-screen="confirm"] [data-action="continue"]');
    await waitFor(page, `(() => { const step = document.querySelector('[data-screen="step"]');
      const panel = document.querySelector('[data-qa-airport-panel]'); return step && !step.hidden && panel.hidden; })()`, 'QA hidden after confirmation');
    summary.qaVisibleOnlyOnConfirm = await evaluate(page, `window.__airportSmoke.requests.length === 1`);

    Object.assign(summary, await evaluate(page, `(() => {
      const row = document.querySelector('[data-airport-resource-row]');
      const controls = Array.from(row.querySelectorAll('button'));
      const rects = controls.map((item) => item.getBoundingClientRect());
      return { journeyButtons: controls.length === 2
          && controls[0].dataset.action === 'open-airport-map'
          && controls[1].dataset.action === 'contact-airport-resource'
          && Math.abs(rects[0].top - rects[1].top) < 2,
        noHorizontalOverflow: document.documentElement.scrollWidth <= innerWidth + 1 };
    })()`));

    await click(page, '[data-airport-resource-row] [data-action="open-airport-map"]');
    await waitFor(page, `(() => { const view = document.querySelector('[data-airport-resource-view]');
      const link = document.querySelector('[data-airport-map-result]');
      return view && !view.hidden && link && !link.hidden && /Mapa/.test(document.querySelector('[data-airport-resource-title]').textContent); })()`, 'map page');
    Object.assign(summary, await evaluate(page, `(() => { const card = document.querySelector('.staff-card');
      const close = document.querySelector('.staff-card-topbar [data-action="close-staff"]'); const svg = close.querySelector('svg');
      const c = card.getBoundingClientRect(); const b = close.getBoundingClientRect(); const s = svg.getBoundingClientRect();
      return { mapPageClose: b.width >= 48 && b.height >= 48 && b.right <= c.right + 1
          && c.right - b.right < 45 && b.top >= c.top - 1 && b.top - c.top < 50,
        svgCentered: Math.abs((b.left + b.right - s.left - s.right) / 2) < 1
          && Math.abs((b.top + b.bottom - s.top - s.bottom) / 2) < 1 };
    })()`));
    await click(page, '[data-action="close-staff"]');
    await waitFor(page, `document.querySelector('[data-staff-overlay]').hidden
      && document.activeElement === document.querySelector('[data-airport-resource-row] [data-action="open-airport-map"]')`, 'close map page and restore focus');
    summary.focusRestored = true;

    await click(page, '[data-airport-resource-row] [data-action="contact-airport-resource"]');
    await waitFor(page, `(() => { const link = document.querySelector('[data-airport-phone-result]');
      return link && !link.hidden && link.getAttribute('href') === ${JSON.stringify(`tel:${(COMPARISON_REPLAY ? COMPARISON_REPLAY.phone : '+99 12 3456 7890').replace(/[^+\d]/g, '')}`)}; })()`, 'contact page');
    Object.assign(summary, await evaluate(page, `(() => { const card = document.querySelector('.staff-card');
      const close = document.querySelector('.staff-card-topbar [data-action="close-staff"]'); const svg = close.querySelector('svg');
      const c = card.getBoundingClientRect(); const b = close.getBoundingClientRect(); const s = svg.getBoundingClientRect();
      return { contactPageClose: b.width >= 48 && b.height >= 48 && b.right <= c.right + 1
          && c.right - b.right < 45 && b.top >= c.top - 1 && b.top - c.top < 50,
        contactSvgCentered: Math.abs((b.left + b.right - s.left - s.right) / 2) < 1
          && Math.abs((b.top + b.bottom - s.top - s.bottom) / 2) < 1,
        contactNoHorizontalOverflow: document.documentElement.scrollWidth <= innerWidth + 1 };
    })()`));
    await click(page, '[data-action="close-staff"]');
    await waitFor(page, `document.querySelector('[data-staff-overlay]').hidden`, 'close contact page');
    summary.separateTargets = summary.separateTargets && await evaluate(page, `(() => {
      const requests = window.__airportSmoke.requests;
      return requests.length === 3 && requests[1].refreshTarget === 'MAP'
        && requests[2].refreshTarget === 'CONTACT'; })()`);

    const staffSelector = '.screen-step .step-assistance-actions [data-action="staff"]';
    await click(page, staffSelector);
    await waitFor(page, `(() => { const overlay = document.querySelector('[data-staff-overlay]');
      const card = document.querySelector('[data-staff-card-view]'); return overlay && !overlay.hidden && card && !card.hidden; })()`, 'help card');
    Object.assign(summary, await evaluate(page, `(() => {
      const topbar = document.querySelector('.staff-card-topbar');
      const contact = topbar.querySelector('[data-staff-contact-button]');
      const close = topbar.querySelector('[data-action="close-staff"]');
      const left = contact.getBoundingClientRect(); const right = close.getBoundingClientRect();
      return { helpTopbarOnlyContact: topbar.querySelectorAll('[data-action="contact-airport-resource"]').length === 1
          && !topbar.querySelector('[data-action="open-airport-map"]')
          && !document.querySelector('[data-staff-card-view] [data-action="open-airport-map"]'),
        helpNoOverlap: left.right <= right.left && Math.abs(left.top - right.top) < 2
          && right.width === 56 && right.height === 56,
        noHorizontalOverflow: document.documentElement.scrollWidth <= innerWidth + 1 };
    })()`));

    mkdirSync(path.dirname(SCREENSHOT), { recursive: true });
    const screenshot = await page.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
    writeFileSync(SCREENSHOT, Buffer.from(screenshot.data, 'base64'));
    await click(page, '[data-action="close-staff"]');
    await waitFor(page, `document.querySelector('[data-staff-overlay]').hidden
      && document.activeElement === document.querySelector(${JSON.stringify(staffSelector)})`, 'close help card and restore focus');

    const familySelector = '.screen-step [data-action="family"]';
    await click(page, familySelector);
    await waitFor(page, `(() => { const overlay = document.querySelector('[data-family-overlay]');
      return overlay && !overlay.hidden; })()`, 'family share overlay');
    Object.assign(summary, await evaluate(page, `(() => { const card = document.querySelector('.family-card');
      const close = card.querySelector('[data-action="close-family"]'); const svg = close.querySelector('svg');
      const c = card.getBoundingClientRect(); const b = close.getBoundingClientRect(); const s = svg.getBoundingClientRect();
      return { familySvgClose: close.textContent.trim() === '' && b.width === 56 && b.height === 56
          && c.right - b.right < 30 && b.top - c.top < 30,
        svgCentered: Math.abs((b.left + b.right - s.left - s.right) / 2) < 1
          && Math.abs((b.top + b.bottom - s.top - s.bottom) / 2) < 1,
        noHorizontalOverflow: document.documentElement.scrollWidth <= innerWidth + 1 };
    })()`));
    await click(page, '[data-action="close-family"]');
    await waitFor(page, `document.querySelector('[data-family-overlay]').hidden
      && document.activeElement === document.querySelector(${JSON.stringify(familySelector)})`, 'close family overlay and restore focus');
    summary.requests = await evaluate(page, `window.__airportSmoke.requests.length`);

    requireSmoke(summary.qaVisibleOnlyOnConfirm && summary.qaRounded && summary.qaNoAirportName
      && summary.qaSingleCenteredQuery && summary.qaConciseResult && summary.qaDebugAbsent
      && summary.noAutomaticAirportRequest && summary.journeyButtons && summary.mapPageClose
      && summary.contactPageClose && summary.contactSvgCentered && summary.contactNoHorizontalOverflow
      && summary.separateTargets && summary.helpTopbarOnlyContact
      && summary.helpNoOverlap && summary.familySvgClose && summary.svgCentered && summary.focusRestored
      && summary.noHorizontalOverflow && summary.consoleErrors === 0 && summary.requests === 3,
    '390px airport-resources smoke failed', summary);
    console.log(JSON.stringify(summary, null, 2));
  } finally {
    if (page) page.close();
    chrome.kill();
    await new Promise((resolve) => server.close(resolve));
    await delay(250);
    safeRemoveProfile(profile);
  }
}

main().catch((error) => { console.error(String(error && error.message || error)); process.exitCode = 1; });
