import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const INDEX_PATH = path.join(ROOT, 'src', 'main', 'resources', 'static', 'index.html');
const INDEX_URL = pathToFileURL(INDEX_PATH).href;
const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  process.env.LOCALAPPDATA
    ? path.join(process.env.LOCALAPPDATA, 'Google', 'Chrome', 'Application', 'chrome.exe')
    : null
].filter(Boolean);

const VIEWPORTS = [
  { label: '320px', width: 320, height: 900, deviceScaleFactor: 1 },
  { label: '360px', width: 360, height: 900, deviceScaleFactor: 1 },
  { label: '375px', width: 375, height: 900, deviceScaleFactor: 1 },
  { label: '390px', width: 390, height: 900, deviceScaleFactor: 1 },
  { label: '412px', width: 412, height: 900, deviceScaleFactor: 1 },
  { label: '320px at 200%', width: 160, height: 900, deviceScaleFactor: 2 }
];
const LOCALES = ['zh', 'en', 'es'];
const STOP_LABELS = { zh: '\u505c\u6b62', en: 'Stop', es: 'Detener' };
const STAFF_QUESTION_MIN_RIGHT_CLEARANCE_PX = 0.75;
const BUTTON_SVG_MIN_PAINTED_CLEARANCE_PX = 0.75;
const PAINTED_BOUNDS_TOLERANCE_PX = 0.05;
// Brand is intentionally excluded; every production action-control family is included.
const TARGET_SELECTOR = [
  '.button',
  '.support-button',
  '.home-back-button',
  '.back-button',
  '.card-close',
  '.text-button',
  '.scenario-actions button',
  '.end-journey-link',
  '.segment-option'
].join(',');

const SCENES = [
  'welcome',
  'scenario-controls',
  'upload',
  'document-unusable',
  'manual',
  'error',
  'confirm-flight',
  'segment-selector',
  'departure-passed',
  'field-review',
  'conflict',
  'checkin-question',
  'boarding-pass-preset',
  'baggage-question',
  'formal-action',
  'formal-action-stop',
  'departure-exception',
  'complete',
  'staff-dialog',
  'family-dialog',
  'end-dialog',
  'board-dialog',
  'landed-dialog'
];

const summary = {
  browser: 'Headless Chrome via the Chrome DevTools Protocol',
  source: INDEX_URL,
  externalRequests: [],
  expectedControlCount: 0,
  coveredControlCount: 0,
  uncoveredControls: [],
  runs: [],
  failures: [],
  passed: false
};

const delay = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

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
  const response = await fetch(url, { ...options, signal: AbortSignal.timeout(1500) });
  if (!response.ok) throw new Error(`HTTP ${response.status} from local CDP endpoint`);
  return response.json();
}

async function waitForChrome(port, timeout = 15000) {
  const started = Date.now();
  while (Date.now() - started < timeout) {
    try {
      return await readJson(`http://127.0.0.1:${port}/json/version`);
    } catch {
      await delay(150);
    }
  }
  throw new Error('Chrome DevTools endpoint did not become ready');
}

class CdpSession {
  constructor(webSocketDebuggerUrl) {
    this.url = webSocketDebuggerUrl;
    this.nextId = 1;
    this.pending = new Map();
    this.listeners = new Map();
  }

  async connect() {
    this.socket = new WebSocket(this.url);
    await new Promise((resolve, reject) => {
      this.socket.addEventListener('open', resolve, { once: true });
      this.socket.addEventListener('error', () => reject(new Error('CDP WebSocket connection failed')), { once: true });
    });
    this.socket.addEventListener('message', (event) => this.receive(event.data));
    this.socket.addEventListener('close', () => {
      for (const pending of this.pending.values()) pending.reject(new Error('CDP WebSocket closed'));
      this.pending.clear();
    });
    return this;
  }

  receive(data) {
    let message;
    try {
      message = JSON.parse(String(data));
    } catch {
      return;
    }
    if (message.id) {
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id);
      if (message.error) pending.reject(new Error(`${pending.method} failed: ${message.error.message || 'unknown error'}`));
      else pending.resolve(message.result || {});
      return;
    }
    for (const listener of this.listeners.get(message.method) || []) {
      Promise.resolve(listener(message.params || {})).catch(() => {});
    }
  }

  on(method, listener) {
    const listeners = this.listeners.get(method) || [];
    listeners.push(listener);
    this.listeners.set(method, listeners);
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

async function evaluate(page, expression) {
  const result = await page.send('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true
  });
  if (result.exceptionDetails) {
    const description = result.exceptionDetails.exception && result.exceptionDetails.exception.description;
    throw new Error(description || result.exceptionDetails.text || 'Page evaluation failed');
  }
  return result.result && result.result.value;
}

async function waitFor(page, expression, label, timeout = 15000) {
  const started = Date.now();
  while (Date.now() - started < timeout) {
    try {
      if (await evaluate(page, `Boolean(${expression})`)) return;
    } catch {
      // Navigation can briefly invalidate the execution context.
    }
    await delay(100);
  }
  throw new Error(`${label} timed out`);
}

function safeRemoveTemp(directory) {
  const resolved = path.resolve(directory);
  const tempRoot = path.resolve(os.tmpdir());
  if (path.dirname(resolved) !== tempRoot || !path.basename(resolved).startsWith('onestep-button-visual-')) return;
  try {
    rmSync(resolved, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
  } catch {
    // A closing Chrome process can briefly retain its profile on Windows.
  }
}

function recordFailure(run, scene, failure) {
  const entry = {
    locale: run.locale,
    viewport: run.viewport,
    scene,
    ...failure
  };
  run.failures.push(entry);
  summary.failures.push(entry);
}

async function initializePage(page) {
  await Promise.all([
    page.send('Page.enable'),
    page.send('Runtime.enable'),
    page.send('Network.enable')
  ]);

  page.on('Network.requestWillBeSent', ({ request }) => {
    if (/^https?:/i.test(request.url)) summary.externalRequests.push(request.url);
  });
  await page.send('Network.setBlockedURLs', { urls: ['http://*', 'https://*'] });
  await page.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `(() => {
      window.__buttonVisualBlockedRequests = [];
      const originalFetch = window.fetch;
      window.fetch = function (input, init) {
        const raw = typeof input === 'string' ? input : input && input.url || '';
        if (/^https?:/i.test(raw) || /^\\/api(?:\\/|$)/.test(raw)) {
          window.__buttonVisualBlockedRequests.push(String(raw));
          return Promise.reject(new Error('Network is disabled during button visual acceptance'));
        }
        return originalFetch.call(this, input, init);
      };
    })();`
  });

  await page.send('Page.navigate', { url: INDEX_URL });
  await waitFor(page,
    `document.readyState === 'complete' && Boolean(document.querySelector(${JSON.stringify(TARGET_SELECTOR)}))`,
    'static page');
  await evaluate(page, `document.fonts && document.fonts.ready ? document.fonts.ready.then(() => true) : true`);
  await evaluate(page, `(() => {
    const style = document.createElement('style');
    style.dataset.buttonVisualAcceptance = 'true';
    style.textContent = '*,*::before,*::after{animation:none!important;transition:none!important;scroll-behavior:auto!important}';
    document.head.appendChild(style);
    const segmentOptions = document.querySelector('[data-segment-options]');
    if (segmentOptions && !segmentOptions.children.length) {
      [
        ['BA 117', 'LHR to JFK - 2026-08-24'],
        ['IB 6251', 'MAD to JFK - 2026-08-24']
      ].forEach(([title, detail], index) => {
        const button = document.createElement('button');
        button.className = 'segment-option';
        button.type = 'button';
        button.dataset.segmentIndex = String(index);
        const strong = document.createElement('strong');
        strong.textContent = title;
        const span = document.createElement('span');
        span.textContent = detail;
        button.append(strong, span);
        segmentOptions.appendChild(button);
      });
    }
    document.querySelectorAll(${JSON.stringify(TARGET_SELECTOR)}).forEach((button, index) => {
      button.dataset.buttonVisualTestId = 'control-' + index;
    });
    return true;
  })()`);
}

async function setLocale(page, locale) {
  await evaluate(page, `new Promise((resolve) => {
    const select = document.querySelector('[data-locale-select]');
    select.value = ${JSON.stringify(locale)};
    select.dispatchEvent(new Event('change', { bubbles: true }));
    requestAnimationFrame(() => requestAnimationFrame(resolve));
  })`);
}

function sceneMeasurementExpression(scene, stopLabel) {
  return `(() => {
    const sceneName = ${JSON.stringify(scene)};
    const targetSelector = ${JSON.stringify(TARGET_SELECTOR)};
    const stopLabel = ${JSON.stringify(stopLabel)};
    const tolerance = 1.5;
    const failures = [];
    const grids = [];

    function elements(selector) {
      return Array.from(document.querySelectorAll(selector));
    }
    function hide(selector) {
      elements(selector).forEach((element) => { element.hidden = true; });
    }
    function show(selector) {
      elements(selector).forEach((element) => { element.hidden = false; });
    }
    function showScreen(name) {
      const screen = document.querySelector('[data-screen="' + name + '"]');
      if (!screen) throw new Error('Missing screen: ' + name);
      screen.hidden = false;
      screen.classList.add('is-active');
      return screen;
    }
    function showChildren(selector) {
      elements(selector).forEach((container) => {
        container.hidden = false;
        Array.from(container.children).forEach((child) => { child.hidden = false; });
      });
    }
    function addGrid(selector, columns, singleRow = true) {
      grids.push({ selector, columns, singleRow });
    }

    hide('[data-screen], [data-staff-overlay], .confirm-overlay');
    elements('[data-screen]').forEach((screen) => screen.classList.remove('is-active'));
    hide('[data-departure-passed-confirmation], [data-segment-selector], [data-field-review], [data-conflict-card]');
    hide('[data-checkin-question], [data-checkin-preset], [data-checkin-choices], [data-step-question], [data-step-result]');
    hide('[data-complete-action], [data-departure-exception-actions], [data-action="journey-back"], [data-action="refresh-arrival"]');
    hide('[data-share-entry], [data-action="refresh-flight"]');
    hide('[data-scenario-panel]');
    elements('.step-assistance-actions, .confirm-assistance-actions')
      .forEach((group) => group.classList.remove('share-unavailable'));
    const step = document.querySelector('[data-screen="step"]');
    step.classList.remove('is-departure-exception');
    show('.step-assistance-actions .staff-help-button');
    document.documentElement.scrollTop = 0;
    document.body.scrollTop = 0;

    let scope;
    if (sceneName === 'welcome' || sceneName === 'upload' || sceneName === 'document-unusable'
        || sceneName === 'manual' || sceneName === 'error') {
      scope = showScreen(sceneName);
    } else if (sceneName === 'scenario-controls') {
      showScreen('welcome');
      show('[data-scenario-panel]');
      scope = document.querySelector('[data-scenario-panel]');
    } else if (sceneName === 'confirm-flight') {
      scope = showScreen('confirm');
      hide('[data-departure-passed-confirmation]');
      show('.screen-confirm > .confirm-choice-actions, .screen-confirm > .confirm-assistance-actions');
      showChildren('.screen-confirm > .confirm-assistance-actions');
      show('.screen-confirm > [data-action="refresh-flight"]');
      addGrid('.screen-confirm > .confirm-choice-actions', 2);
      addGrid('.screen-confirm > .confirm-assistance-actions', 2);
    } else if (sceneName === 'segment-selector') {
      showScreen('confirm');
      hide('.screen-confirm > .confirm-choice-actions, .screen-confirm > .confirm-assistance-actions');
      show('[data-segment-selector]');
      scope = document.querySelector('[data-segment-selector]');
    } else if (sceneName === 'departure-passed') {
      scope = showScreen('confirm');
      hide('.screen-confirm > .home-back-button, .screen-confirm > .confirm-choice-actions, .screen-confirm > .confirm-assistance-actions');
      show('[data-departure-passed-confirmation]');
      showChildren('[data-departure-passed-confirmation] .confirm-assistance-actions');
      addGrid('[data-departure-passed-confirmation] .confirm-assistance-actions', 2);
    } else if (sceneName === 'field-review') {
      scope = showScreen('confirm');
      hide('.screen-confirm > .confirm-choice-actions, .screen-confirm > .confirm-assistance-actions');
      show('[data-field-review]');
    } else if (sceneName === 'conflict') {
      scope = showScreen('confirm');
      hide('.screen-confirm > .confirm-choice-actions, .screen-confirm > .confirm-assistance-actions');
      show('[data-conflict-card]');
    } else if (sceneName === 'checkin-question') {
      scope = showScreen('step');
      show('[data-checkin-question], [data-checkin-choices]');
      showChildren('.step-assistance-actions');
      addGrid('[data-checkin-choices]', 3);
      addGrid('.step-assistance-actions', 3);
    } else if (sceneName === 'boarding-pass-preset') {
      scope = showScreen('step');
      show('[data-checkin-question], [data-checkin-preset]');
      showChildren('.step-assistance-actions');
      addGrid('[data-checkin-preset] .two-choice-grid', 2);
      addGrid('.step-assistance-actions', 3);
    } else if (sceneName === 'baggage-question') {
      scope = showScreen('step');
      show('[data-step-question]');
      showChildren('.step-assistance-actions');
      addGrid('[data-step-question] .answer-choice-grid', 3);
      addGrid('.step-assistance-actions', 3);
    } else if (sceneName === 'formal-action' || sceneName === 'formal-action-stop') {
      scope = showScreen('step');
      show('[data-step-result], [data-complete-action], .screen-step [data-action="journey-back"], [data-action="refresh-arrival"]');
      showChildren('.step-assistance-actions');
      if (sceneName === 'formal-action-stop') {
        const label = document.querySelector('.step-assistance-actions [data-speech-label]');
        if (label) label.textContent = stopLabel;
      }
      addGrid('.step-assistance-actions', 3);
    } else if (sceneName === 'departure-exception') {
      scope = showScreen('step');
      step.classList.add('is-departure-exception');
      show('[data-step-result], [data-departure-exception-actions]');
      showChildren('.step-assistance-actions');
      hide('.step-assistance-actions .staff-help-button');
      addGrid('.step-assistance-actions', 2);
    } else if (sceneName === 'complete') {
      scope = showScreen('complete');
      showChildren('.complete-assistance-actions');
      showChildren('.complete-actions');
      addGrid('.complete-assistance-actions', 2);
      addGrid('.complete-actions.short-action-grid', 2, false);
    } else {
      const overlaySelectors = {
        'staff-dialog': '[data-staff-overlay]',
        'family-dialog': '[data-family-overlay]',
        'end-dialog': '[data-end-overlay]',
        'board-dialog': '[data-board-overlay]',
        'landed-dialog': '[data-landed-overlay]'
      };
      scope = document.querySelector(overlaySelectors[sceneName]);
      if (!scope) throw new Error('Unknown scene: ' + sceneName);
      scope.hidden = false;
      if (sceneName === 'family-dialog') {
        showChildren('[data-family-overlay] .family-actions');
        addGrid('[data-family-overlay] .family-actions', 2, false);
      }
      if (sceneName === 'board-dialog') addGrid('[data-board-overlay] .two-choice-grid', 2);
      if (sceneName === 'landed-dialog') addGrid('[data-landed-overlay] .two-choice-grid', 2);
    }

    const isRendered = (element) => {
      const rect = element.getBoundingClientRect();
      const style = getComputedStyle(element);
      return rect.width > 0 && rect.height > 0 && style.display !== 'none'
        && style.visibility !== 'hidden' && Number(style.opacity) > 0;
    };
    const inside = (inner, outer) => inner.left >= outer.left - tolerance
      && inner.right <= outer.right + tolerance && inner.top >= outer.top - tolerance
      && inner.bottom <= outer.bottom + tolerance;
    const insideViewportHorizontally = (rect) => rect.left >= -tolerance && rect.right <= innerWidth + tolerance;
    const rectValue = (rect, origin = null) => ({
      x: +((origin ? rect.left - origin.left : rect.left).toFixed(2)),
      y: +((origin ? rect.top - origin.top : rect.top).toFixed(2)),
      width: +(rect.width.toFixed(2)),
      height: +(rect.height.toFixed(2))
    });
    const textRects = (button) => {
      const walker = document.createTreeWalker(button, NodeFilter.SHOW_TEXT);
      const rects = [];
      while (walker.nextNode()) {
        const node = walker.currentNode;
        if (!node.nodeValue || !node.nodeValue.trim()) continue;
        const parentStyle = getComputedStyle(node.parentElement);
        if (parentStyle.display === 'none' || parentStyle.visibility === 'hidden') continue;
        const range = document.createRange();
        range.selectNodeContents(node);
        Array.from(range.getClientRects()).filter((rect) => rect.width > 0 && rect.height > 0)
          .forEach((rect) => rects.push({ rect, fontSize: parseFloat(parentStyle.fontSize) }));
      }
      return rects;
    };

    const controls = Array.from(scope.querySelectorAll(targetSelector)).filter(isRendered);
    const uniqueControls = Array.from(new Set(controls));
    const measurements = [];
    for (const button of uniqueControls) {
      const id = button.dataset.buttonVisualTestId || '(unassigned)';
      const label = button.innerText.trim().replace(/\\s+/g, ' ') || button.getAttribute('aria-label') || '(icon only)';
      const buttonRect = button.getBoundingClientRect();
      const buttonStyle = getComputedStyle(button);
      const buttonFailures = [];
      if (buttonRect.width < 43.5 || buttonRect.height < 43.5) buttonFailures.push('touch target is smaller than 44x44');
      if (!insideViewportHorizontally(buttonRect)) buttonFailures.push('button leaves the horizontal viewport');

      const renderedTextRects = textRects(button);
      for (const item of renderedTextRects) {
        if (!inside(item.rect, buttonRect)) buttonFailures.push('visible text leaves the button');
        if (!insideViewportHorizontally(item.rect)) buttonFailures.push('visible text leaves the horizontal viewport');
      }
      if (label !== '(icon only)' && renderedTextRects.length === 0 && !button.getAttribute('aria-label')) {
        buttonFailures.push('visible label has no rendered text rectangle');
      }

      const svgMeasurements = [];
      for (const svg of Array.from(button.querySelectorAll('svg')).filter(isRendered)) {
        const svgRect = svg.getBoundingClientRect();
        const viewBox = svg.viewBox && svg.viewBox.baseVal;
        const viewBoxValue = viewBox && viewBox.width > 0 && viewBox.height > 0 ? {
          x: viewBox.x,
          y: viewBox.y,
          width: viewBox.width,
          height: viewBox.height
        } : null;
        if (!inside(svgRect, buttonRect)) buttonFailures.push('SVG leaves the button');
        if (!insideViewportHorizontally(svgRect)) buttonFailures.push('SVG leaves the horizontal viewport');
        const paths = [];
        for (const geometry of svg.querySelectorAll('path,circle,ellipse,line,polyline,polygon,rect')) {
          const geometryStyle = getComputedStyle(geometry);
          const geometryRect = geometry.getBoundingClientRect();
          const strokeVisible = geometryStyle.stroke !== 'none' && parseFloat(geometryStyle.strokeOpacity || '1') > 0;
          const fillVisible = geometryStyle.fill !== 'none' && parseFloat(geometryStyle.fillOpacity || '1') > 0;
          const painted = (strokeVisible || fillVisible) && Number(geometryStyle.opacity || '1') > 0;
          const hasExtent = geometryRect.width > 0.1 || geometryRect.height > 0.1;
          if (!painted || !hasExtent) buttonFailures.push('SVG path has no visible painted bounds');
          if (!inside(geometryRect, svgRect)) buttonFailures.push('SVG path leaves the SVG viewport');
          let userBBox = null;
          let paintedClearanceCss = null;
          let paintedBoundsCss = null;
          try {
            const bbox = geometry.getBBox();
            userBBox = rectValue({ left: bbox.x, top: bbox.y, width: bbox.width, height: bbox.height });
            const matrix = geometry.getScreenCTM();
            if (matrix) {
              const corners = [
                [bbox.x, bbox.y],
                [bbox.x + bbox.width, bbox.y],
                [bbox.x, bbox.y + bbox.height],
                [bbox.x + bbox.width, bbox.y + bbox.height]
              ].map(([x, y]) => ({
                x: matrix.a * x + matrix.c * y + matrix.e,
                y: matrix.b * x + matrix.d * y + matrix.f
              }));
              const centerlineBounds = {
                left: Math.min(...corners.map((point) => point.x)),
                right: Math.max(...corners.map((point) => point.x)),
                top: Math.min(...corners.map((point) => point.y)),
                bottom: Math.max(...corners.map((point) => point.y))
              };
              const strokeHalf = strokeVisible ? (parseFloat(geometryStyle.strokeWidth) || 0) / 2 : 0;
              const joinFactor = strokeVisible && geometryStyle.strokeLinejoin === 'miter'
                ? Math.max(1, parseFloat(geometryStyle.strokeMiterlimit) || 4) : 1;
              const localStrokeRadius = strokeHalf * joinFactor;
              const nonScalingStroke = geometryStyle.vectorEffect === 'non-scaling-stroke';
              const strokeRadiusX = nonScalingStroke ? localStrokeRadius
                : localStrokeRadius * Math.hypot(matrix.a, matrix.c);
              const strokeRadiusY = nonScalingStroke ? localStrokeRadius
                : localStrokeRadius * Math.hypot(matrix.b, matrix.d);
              paintedBoundsCss = {
                left: +(centerlineBounds.left - strokeRadiusX).toFixed(3),
                right: +(centerlineBounds.right + strokeRadiusX).toFixed(3),
                top: +(centerlineBounds.top - strokeRadiusY).toFixed(3),
                bottom: +(centerlineBounds.bottom + strokeRadiusY).toFixed(3)
              };
              paintedClearanceCss = {
                left: +(paintedBoundsCss.left - svgRect.left).toFixed(3),
                right: +(svgRect.right - paintedBoundsCss.right).toFixed(3),
                top: +(paintedBoundsCss.top - svgRect.top).toFixed(3),
                bottom: +(svgRect.bottom - paintedBoundsCss.bottom).toFixed(3)
              };
            }
          } catch {
            buttonFailures.push('SVG path has no readable user-space bounds');
          }
          paths.push({
            tag: geometry.tagName.toLowerCase(),
            className: geometry.getAttribute('class') || '',
            bbox: rectValue(geometryRect, svgRect),
            userBBox,
            paintedBoundsCss,
            paintedClearanceCss,
            stroke: geometryStyle.stroke,
            fill: geometryStyle.fill,
            strokeWidth: geometryStyle.strokeWidth,
            strokeLinecap: geometryStyle.strokeLinecap,
            strokeLinejoin: geometryStyle.strokeLinejoin,
            strokeMiterlimit: geometryStyle.strokeMiterlimit,
            vectorEffect: geometryStyle.vectorEffect
          });
        }
        if (paths.length === 0) buttonFailures.push('SVG contains no measurable geometry');
        svgMeasurements.push({
          className: svg.getAttribute('class') || '',
          box: rectValue(svgRect, buttonRect),
          viewBox: viewBoxValue,
          paths
        });
      }

      const textFontSizes = Array.from(new Set(renderedTextRects.map((item) => +item.fontSize.toFixed(2))));
      if ((button.matches('.assistance-button') || button.matches('.dialog-speech-button')) && textFontSizes.length) {
        const minimumExpected = innerWidth <= 220 ? 18 : Math.min(27, Math.max(21, innerWidth * 0.06));
        if (Math.min(...textFontSizes) < minimumExpected - 0.2) {
          buttonFailures.push('auxiliary label font is below its responsive minimum');
        }
      }
      for (const reason of new Set(buttonFailures)) failures.push({ type: 'control', id, label, reason });
      measurements.push({
        id,
        action: button.dataset.action || '',
        label,
        classes: button.className,
        box: rectValue(buttonRect),
        computedFontSize: parseFloat(buttonStyle.fontSize),
        textFontSizes,
        textBoxes: renderedTextRects.map((item) => rectValue(item.rect, buttonRect)),
        svgs: svgMeasurements
      });
    }

    const gridMeasurements = [];
    for (const definition of grids) {
      const grid = document.querySelector(definition.selector);
      if (!grid || !isRendered(grid)) {
        failures.push({ type: 'grid', selector: definition.selector, reason: 'grid is not rendered' });
        continue;
      }
      const items = Array.from(grid.children).filter(isRendered);
      const rects = items.map((item) => item.getBoundingClientRect());
      const template = getComputedStyle(grid).gridTemplateColumns;
      const trackCount = template === 'none' ? 0 : template.trim().split(/\\s+/).length;
      const firstTop = rects.length ? rects[0].top : 0;
      const firstRowCount = rects.filter((rect) => Math.abs(rect.top - firstTop) <= 1).length;
      if (trackCount !== definition.columns) {
        failures.push({ type: 'grid', selector: definition.selector, reason: 'expected ' + definition.columns + ' tracks but found ' + trackCount });
      }
      if (definition.singleRow && (items.length !== definition.columns || firstRowCount !== definition.columns)) {
        failures.push({ type: 'grid', selector: definition.selector, reason: 'controls are not one row of ' + definition.columns });
      }
      gridMeasurements.push({ selector: definition.selector, expectedColumns: definition.columns,
        trackCount, itemCount: items.length, firstRowCount, template });
    }

    const scrollWidth = Math.max(document.documentElement.scrollWidth, document.body.scrollWidth);
    const noHorizontalOverflow = scrollWidth <= innerWidth + 1;
    if (!noHorizontalOverflow) {
      const overflowingElements = Array.from(document.querySelectorAll('body *')).filter(isRendered)
        .map((element) => {
          const rect = element.getBoundingClientRect();
          return {
            tag: element.tagName.toLowerCase(),
            id: element.id || '',
            classes: typeof element.className === 'string' ? element.className : '',
            text: (element.innerText || '').trim().replace(/\\s+/g, ' ').slice(0, 80),
            rect: rectValue(rect),
            clientWidth: element.clientWidth,
            scrollWidth: element.scrollWidth
          };
        })
        .filter((item) => item.rect.x < -tolerance
          || item.rect.x + item.rect.width > innerWidth + tolerance
          || item.scrollWidth > item.clientWidth + 1)
        .slice(0, 16);
      failures.push({ type: 'page', reason: 'horizontal overflow', innerWidth, scrollWidth, overflowingElements });
    }
    return {
      scene: sceneName,
      innerWidth,
      scrollWidth,
      noHorizontalOverflow,
      controls: measurements,
      grids: gridMeasurements,
      failures
    };
  })()`;
}

function validateButtonSvgSafety(run, expectedControls) {
  const expectedSvgControls = expectedControls.filter((control) => control.svgCount > 0);
  const uniqueMeasurements = new Map();
  for (const measurement of run.measurements) {
    if (measurement.svgs.length && !uniqueMeasurements.has(measurement.id)) {
      uniqueMeasurements.set(measurement.id, measurement);
    }
  }
  const measuredSvgControls = Array.from(uniqueMeasurements.values());
  const expectedSvgCount = expectedSvgControls.reduce((count, control) => count + control.svgCount, 0);
  const measuredSvgCount = measuredSvgControls.reduce((count, measurement) => count + measurement.svgs.length, 0);
  const missingControlIds = expectedSvgControls.filter((control) => !uniqueMeasurements.has(control.id))
    .map((control) => control.id);
  const clearances = [];
  const unsafeIcons = [];

  for (const measurement of measuredSvgControls) {
    measurement.svgs.forEach((svg, svgIndex) => {
      if (!svg.paths.length) {
        unsafeIcons.push({
          id: measurement.id,
          scene: measurement.scene,
          action: measurement.action,
          label: measurement.label,
          svgIndex,
          svgClass: svg.className,
          category: 'unmeasurable',
          reason: 'SVG contains no measurable geometry'
        });
        return;
      }
      svg.paths.forEach((geometry, geometryIndex) => {
        const edgeValues = geometry.paintedClearanceCss && Object.values(geometry.paintedClearanceCss);
        if (!edgeValues || edgeValues.some((value) => !Number.isFinite(value))) {
          unsafeIcons.push({
            id: measurement.id,
            scene: measurement.scene,
            action: measurement.action,
            label: measurement.label,
            svgIndex,
            svgClass: svg.className,
            geometryIndex,
            tag: geometry.tag,
            geometryClass: geometry.className,
            category: 'unmeasurable',
            reason: 'painted bounds could not be measured'
          });
          return;
        }
        const minimumClearance = Math.min(...edgeValues);
        clearances.push(minimumClearance);
        if (minimumClearance < BUTTON_SVG_MIN_PAINTED_CLEARANCE_PX) {
          unsafeIcons.push({
            id: measurement.id,
            scene: measurement.scene,
            action: measurement.action,
            label: measurement.label,
            svgIndex,
            svgClass: svg.className,
            geometryIndex,
            tag: geometry.tag,
            geometryClass: geometry.className,
            category: minimumClearance < 0 ? 'painted-bounds-outside' : 'insufficient-safety-margin',
            minimumPaintedClearanceCss: minimumClearance,
            paintedClearanceCss: geometry.paintedClearanceCss
          });
        }
      });
    });
  }

  const outsideCount = unsafeIcons.filter((icon) => icon.category === 'painted-bounds-outside').length;
  const insufficientMarginCount = unsafeIcons
    .filter((icon) => icon.category === 'insufficient-safety-margin').length;
  const unmeasurableCount = unsafeIcons.filter((icon) => icon.category === 'unmeasurable').length;
  run.buttonSvgSafety = {
    minimumRequiredClearanceCss: BUTTON_SVG_MIN_PAINTED_CLEARANCE_PX,
    expectedControls: expectedSvgControls.length,
    measuredControls: measuredSvgControls.length,
    expectedSvgCount,
    measuredSvgCount,
    minimumPaintedClearanceCss: clearances.length ? Math.min(...clearances) : null,
    unsafeCount: unsafeIcons.length,
    outsideCount,
    insufficientMarginCount,
    unmeasurableCount,
    missingControlIds,
    unsafeIcons
  };

  if (missingControlIds.length || measuredSvgControls.length !== expectedSvgControls.length
      || measuredSvgCount !== expectedSvgCount) {
    recordFailure(run, 'button-svg-safety', {
      type: 'button-svg-coverage',
      reason: `expected ${expectedSvgControls.length} SVG control(s)/${expectedSvgCount} SVG(s), measured ${measuredSvgControls.length}/${measuredSvgCount}`,
      missingControlIds
    });
  }
  if (unsafeIcons.length) {
    recordFailure(run, 'button-svg-safety', {
      type: 'button-svg-safety',
      reason: `${outsideCount} painted geometry item(s) leave the SVG viewport; ${insufficientMarginCount} lack the ${BUTTON_SVG_MIN_PAINTED_CLEARANCE_PX}px safety margin; ${unmeasurableCount} could not be measured`,
      unsafeIcons
    });
  }
}

function validateSemanticIconContracts(run, expectedControls) {
  const expectedStaff = expectedControls.filter((control) => control.classes.includes('staff-help-button'));
  const expectedFamily = expectedControls.filter((control) => control.classes.includes('family-help-button'));
  const staffMeasurements = run.measurements.filter((measurement) => measurement.classes.includes('staff-help-button'));
  const familyMeasurements = run.measurements.filter((measurement) => measurement.classes.includes('family-help-button'));
  const staffClearances = [];
  const familyStructures = [];
  const familyPaintedClearances = [];

  const fail = (scene, id, label, reason, details = {}) => recordFailure(run, scene || 'semantic-icons', {
    type: 'icon-contract',
    id,
    label,
    reason,
    ...details
  });

  if (staffMeasurements.length !== expectedStaff.length) {
    fail('semantic-icons', '(staff coverage)', 'staff',
      `expected ${expectedStaff.length} staff control(s) but measured ${staffMeasurements.length}`);
  }
  if (familyMeasurements.length !== expectedFamily.length) {
    fail('semantic-icons', '(family coverage)', 'share',
      `expected ${expectedFamily.length} family-share control(s) but measured ${familyMeasurements.length}`);
  }

  for (const measurement of staffMeasurements) {
    if (measurement.svgs.length !== 1) {
      fail(measurement.scene, measurement.id, measurement.label,
        `staff-help control must contain exactly one SVG; found ${measurement.svgs.length}`);
      continue;
    }
    const geometries = measurement.svgs[0].paths;
    const people = geometries.filter((geometry) => geometry.className === 'staff-icon-person');
    const questions = geometries.filter((geometry) => geometry.className === 'staff-icon-question');
    if (people.length !== 1 || questions.length !== 1) {
      fail(measurement.scene, measurement.id, measurement.label,
        `staff-help SVG must contain one person path and one question path; found ${people.length} and ${questions.length}`);
      continue;
    }
    const rightClearance = questions[0].paintedClearanceCss && questions[0].paintedClearanceCss.right;
    if (!Number.isFinite(rightClearance)) {
      fail(measurement.scene, measurement.id, measurement.label,
        'staff question path has no measurable painted right clearance');
      continue;
    }
    staffClearances.push(rightClearance);
    if (rightClearance < STAFF_QUESTION_MIN_RIGHT_CLEARANCE_PX) {
      fail(measurement.scene, measurement.id, measurement.label,
        `staff question painted right clearance is below ${STAFF_QUESTION_MIN_RIGHT_CLEARANCE_PX}px`,
        { actualRightClearanceCss: rightClearance });
    }
  }

  for (const measurement of familyMeasurements) {
    const shareSvgs = measurement.svgs.filter((svg) => svg.className.split(/\s+/).includes('family-share-icon'));
    if (measurement.svgs.length !== 1 || shareSvgs.length !== 1) {
      fail(measurement.scene, measurement.id, measurement.label,
        `family-share control must contain exactly one family-share-icon SVG; found ${shareSvgs.length} of ${measurement.svgs.length}`);
      continue;
    }
    const geometries = shareSvgs[0].paths;
    const circleCount = geometries.filter((geometry) => geometry.tag === 'circle').length;
    const pathCount = geometries.filter((geometry) => geometry.tag === 'path').length;
    familyStructures.push({ circles: circleCount, paths: pathCount, total: geometries.length });
    if (circleCount !== 3 || pathCount !== 2 || geometries.length !== 5) {
      fail(measurement.scene, measurement.id, measurement.label,
        `family-share-icon must contain exactly 3 circles and 2 paths; found ${circleCount} circles, ${pathCount} paths, ${geometries.length} total geometries`);
    }
    for (const geometry of geometries) {
      const clearances = geometry.paintedClearanceCss && Object.values(geometry.paintedClearanceCss);
      if (!clearances || clearances.some((value) => !Number.isFinite(value))) {
        fail(measurement.scene, measurement.id, measurement.label,
          `family-share ${geometry.tag} has no measurable painted bounds`);
        continue;
      }
      const minimumClearance = Math.min(...clearances);
      familyPaintedClearances.push(minimumClearance);
      if (minimumClearance < -PAINTED_BOUNDS_TOLERANCE_PX) {
        fail(measurement.scene, measurement.id, measurement.label,
          `family-share ${geometry.tag} painted stroke leaves the SVG viewport`,
          { minimumPaintedClearanceCss: minimumClearance });
      }
    }
  }

  run.semanticIcons = {
    staff: {
      expectedControls: expectedStaff.length,
      measuredControls: staffMeasurements.length,
      minimumQuestionRightClearanceCss: staffClearances.length ? Math.min(...staffClearances) : null
    },
    familyShare: {
      expectedControls: expectedFamily.length,
      measuredControls: familyMeasurements.length,
      structures: Array.from(new Map(familyStructures.map((structure) => [
        `${structure.circles}:${structure.paths}:${structure.total}`, structure
      ])).values()),
      minimumPaintedClearanceCss: familyPaintedClearances.length
        ? Math.min(...familyPaintedClearances) : null
    }
  };
}

async function runMeasurements(page) {
  const expectedControls = await evaluate(page, `Array.from(document.querySelectorAll(${JSON.stringify(TARGET_SELECTOR)}))
    .map((button) => ({ id: button.dataset.buttonVisualTestId, action: button.dataset.action || '',
      classes: button.className, svgCount: button.querySelectorAll('svg').length }))`);
  summary.expectedControlCount = expectedControls.length;
  const covered = new Set();

  for (const viewport of VIEWPORTS) {
    await page.send('Emulation.setPageScaleFactor', { pageScaleFactor: 1 });
    await page.send('Emulation.setDeviceMetricsOverride', {
      width: viewport.width,
      height: viewport.height,
      deviceScaleFactor: viewport.deviceScaleFactor,
      mobile: true
    });
    for (const locale of LOCALES) {
      await setLocale(page, locale);
      const run = {
        viewport: viewport.label,
        cssWidth: viewport.width,
        deviceScaleFactor: viewport.deviceScaleFactor,
        locale,
        scenes: [],
        measurements: [],
        failures: []
      };
      const representativeMeasurements = new Map();
      for (const scene of SCENES) {
        const result = await evaluate(page, sceneMeasurementExpression(scene, STOP_LABELS[locale]));
        result.controls.forEach((control) => {
          covered.add(control.id);
          const specificallyRequested = control.svgs.length > 0
            || control.classes.includes('assistance-button')
            || control.classes.includes('dialog-speech-button')
            || ['complete-action', 'journey-back', 'confirm-board', 'cancel-board',
              'confirm-landed', 'cancel-landed'].includes(control.action);
          if (specificallyRequested) {
            representativeMeasurements.set(`${control.id}:${control.label}`, { scene, ...control });
          }
        });
        result.failures.forEach((failure) => recordFailure(run, scene, failure));
        run.scenes.push({
          scene: result.scene,
          innerWidth: result.innerWidth,
          scrollWidth: result.scrollWidth,
          noHorizontalOverflow: result.noHorizontalOverflow,
          controlCount: result.controls.length,
          grids: result.grids
        });
      }
      run.measurements = Array.from(representativeMeasurements.values());
      validateButtonSvgSafety(run, expectedControls);
      validateSemanticIconContracts(run, expectedControls);
      summary.runs.push(run);
    }
  }

  summary.coveredControlCount = covered.size;
  summary.uncoveredControls = expectedControls.filter((control) => !covered.has(control.id));
  for (const control of summary.uncoveredControls) {
    summary.failures.push({ type: 'coverage', ...control, reason: 'control was never rendered by a visual test scene' });
  }
}

async function main() {
  if (!existsSync(INDEX_PATH)) throw new Error(`Static index was not found: ${INDEX_PATH}`);
  const chromePath = CHROME_CANDIDATES.find(existsSync);
  if (!chromePath) throw new Error('Google Chrome was not found; set CHROME_PATH');

  const port = await reservePort();
  const profileDir = mkdtempSync(path.join(os.tmpdir(), 'onestep-button-visual-'));
  const chrome = spawn(chromePath, [
    '--headless=new',
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profileDir}`,
    '--allow-file-access-from-files',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-background-networking',
    '--disable-component-update',
    '--disable-default-apps',
    '--disable-extensions',
    '--disable-sync',
    '--metrics-recording-only',
    '--window-size=412,900',
    'about:blank'
  ], { stdio: 'ignore', windowsHide: true });

  let page;
  try {
    await waitForChrome(port);
    const descriptor = await readJson(`http://127.0.0.1:${port}/json/new?${encodeURIComponent('about:blank')}`, {
      method: 'PUT'
    });
    page = await new CdpSession(descriptor.webSocketDebuggerUrl).connect();
    await initializePage(page);
    await runMeasurements(page);
    const blockedByPage = await evaluate(page, `window.__buttonVisualBlockedRequests || []`);
    summary.externalRequests.push(...blockedByPage);
  } finally {
    if (page) page.close();
    chrome.kill();
    await delay(300);
    safeRemoveTemp(profileDir);
  }

  summary.externalRequests = Array.from(new Set(summary.externalRequests));
  if (summary.externalRequests.length) {
    summary.failures.push({ type: 'network', reason: 'page attempted an HTTP(S) or /api request',
      requests: summary.externalRequests });
  }
  summary.passed = summary.failures.length === 0;
  const output = process.env.BUTTON_VISUAL_VERBOSE === 'true' ? summary : compactSummary(summary);
  console.log(JSON.stringify(output, null, 2));
  if (!summary.passed) process.exitCode = 1;
}

function compactSummary(result) {
  const viewportGroups = new Map();
  for (const run of result.runs) {
    if (!viewportGroups.has(run.viewport)) viewportGroups.set(run.viewport, []);
    viewportGroups.get(run.viewport).push(run);
  }

  const uniqueNumbers = (values) => Array.from(new Set(values)).sort((left, right) => left - right);
  const uniqueSizes = (values) => Array.from(new Set(values.map(({ width, height }) => `${width}x${height}`)));
  const minimumOrNull = (values) => {
    const finite = values.filter(Number.isFinite);
    return finite.length ? Math.min(...finite) : null;
  };
  const representativeRules = [
    ['yes', (measurement) => measurement.classes.includes('answer-yes')],
    ['no', (measurement) => measurement.classes.includes('answer-no')],
    ['unsure', (measurement) => measurement.classes.includes('answer-unsure')],
    ['done', (measurement) => measurement.action === 'complete-action'],
    ['speaker', (measurement) => measurement.action === 'speak'],
    ['share', (measurement) => measurement.classes.includes('family-help-button')],
    ['staff-person', (measurement) => measurement.classes.includes('staff-help-button'),
      (geometry) => geometry.className === 'staff-icon-person'],
    ['staff-question', (measurement) => measurement.classes.includes('staff-help-button'),
      (geometry) => geometry.className === 'staff-icon-question']
  ];

  const viewportMetrics = Array.from(viewportGroups, ([viewport, runs]) => {
    const measurements = runs.flatMap((run) => run.measurements);
    const auxiliary = measurements.filter((measurement) => measurement.classes.includes('assistance-button')
      || measurement.classes.includes('dialog-speech-button'));
    const geometries = (measurement) => measurement.svgs.flatMap((svg) => svg.paths);
    const representativePaths = {};
    for (const [name, measurementMatch, geometryMatch = () => true] of representativeRules) {
      const measurement = measurements.find((candidate) => measurementMatch(candidate)
        && geometries(candidate).some(geometryMatch));
      if (!measurement) continue;
      const geometry = geometries(measurement).find(geometryMatch);
      representativePaths[name] = {
        bbox: { width: geometry.bbox.width, height: geometry.bbox.height },
        stroke: geometry.stroke,
        strokeWidth: geometry.strokeWidth
      };
    }
    const staffQuestionClearances = runs.map((run) => run.semanticIcons.staff.minimumQuestionRightClearanceCss)
      .filter(Number.isFinite);
    const familyPaintedClearances = runs.map((run) => run.semanticIcons.familyShare.minimumPaintedClearanceCss)
      .filter(Number.isFinite);
    const familyStructures = Array.from(new Map(runs.flatMap((run) => run.semanticIcons.familyShare.structures)
      .map((structure) => [`${structure.circles}:${structure.paths}:${structure.total}`, structure])).values());
    const unsafeButtonSvgs = new Map();
    for (const unsafe of runs.flatMap((run) => run.buttonSvgSafety.unsafeIcons)) {
      const key = `${unsafe.id}:${unsafe.svgIndex}:${unsafe.geometryIndex}:${unsafe.category}`;
      const existing = unsafeButtonSvgs.get(key);
      if (!existing || (unsafe.minimumPaintedClearanceCss ?? Infinity)
          < (existing.minimumPaintedClearanceCss ?? Infinity)) {
        unsafeButtonSvgs.set(key, {
          id: unsafe.id,
          action: unsafe.action,
          svgClass: unsafe.svgClass,
          tag: unsafe.tag,
          geometryClass: unsafe.geometryClass,
          category: unsafe.category,
          minimumPaintedClearanceCss: unsafe.minimumPaintedClearanceCss,
          paintedClearanceCss: unsafe.paintedClearanceCss
        });
      }
    }
    return {
      viewport,
      cssWidth: runs[0].cssWidth,
      deviceScaleFactor: runs[0].deviceScaleFactor,
      auxiliaryFontSizes: uniqueNumbers(auxiliary.flatMap((measurement) => measurement.textFontSizes)),
      iconSizes: uniqueSizes(measurements.flatMap((measurement) => measurement.svgs.map((svg) => svg.box))),
      buttonSvgSafety: {
        minimumRequiredClearanceCss: BUTTON_SVG_MIN_PAINTED_CLEARANCE_PX,
        minimumPaintedClearanceCss: minimumOrNull(runs
          .map((run) => run.buttonSvgSafety.minimumPaintedClearanceCss)),
        unsafeCount: unsafeButtonSvgs.size,
        unsafeIcons: Array.from(unsafeButtonSvgs.values())
      },
      semanticIcons: {
        staffQuestionMinimumRightClearanceCss: staffQuestionClearances.length
          ? Math.min(...staffQuestionClearances) : null,
        familyShareStructures: familyStructures,
        familyShareMinimumPaintedClearanceCss: familyPaintedClearances.length
          ? Math.min(...familyPaintedClearances) : null
      },
      representativePaths
    };
  });

  return {
    browser: result.browser,
    source: result.source,
    externalRequests: result.externalRequests,
    expectedControlCount: result.expectedControlCount,
    coveredControlCount: result.coveredControlCount,
    uncoveredControls: result.uncoveredControls,
    runCount: result.runs.length,
    runs: result.runs.map((run) => ({
      viewport: run.viewport,
      locale: run.locale,
      passed: run.failures.length === 0,
      noHorizontalOverflow: run.scenes.every((scene) => scene.noHorizontalOverflow),
      gridChecks: run.scenes.reduce((count, scene) => count + scene.grids.length, 0),
      gridsPassed: !run.failures.some((failure) => failure.type === 'grid'),
      gridTracks: Array.from(new Set(run.scenes.flatMap((scene) => scene.grids)
        .map((grid) => `${grid.expectedColumns}->${grid.trackCount}`))),
      buttonSvgSafety: {
        passed: !run.failures.some((failure) => failure.type === 'button-svg-safety'
          || failure.type === 'button-svg-coverage'),
        expectedControls: run.buttonSvgSafety.expectedControls,
        measuredControls: run.buttonSvgSafety.measuredControls,
        minimumPaintedClearanceCss: run.buttonSvgSafety.minimumPaintedClearanceCss,
        unsafeCount: run.buttonSvgSafety.unsafeCount
      },
      semanticIconsPassed: !run.failures.some((failure) => failure.type === 'icon-contract'),
      failures: run.failures
    })),
    viewportMetrics,
    buttonSvgSafety: {
      minimumRequiredClearanceCss: BUTTON_SVG_MIN_PAINTED_CLEARANCE_PX,
      minimumPaintedClearanceCss: minimumOrNull(result.runs
        .map((run) => run.buttonSvgSafety.minimumPaintedClearanceCss)),
      unsafeCount: Math.max(0, ...result.runs.map((run) => run.buttonSvgSafety.unsafeCount)),
      unsafeRunCount: result.runs.filter((run) => run.buttonSvgSafety.unsafeCount > 0).length
    },
    failureCount: result.failures.length,
    failures: result.failures,
    passed: result.passed
  };
}

main().catch((error) => {
  summary.failure = String(error && error.message || error || 'Button visual acceptance failed').slice(0, 500);
  summary.passed = false;
  console.log(JSON.stringify(summary, null, 2));
  process.exitCode = 1;
});
