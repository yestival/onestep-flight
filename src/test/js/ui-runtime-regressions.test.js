'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const app = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/app.js'), 'utf8');

function sourceBetween(start, end) {
  const startIndex = app.indexOf(start);
  const endIndex = app.indexOf(end, startIndex + start.length);
  assert.ok(startIndex >= 0, `Missing app.js marker: ${start}`);
  assert.ok(endIndex > startIndex, `Missing app.js marker: ${end}`);
  return app.slice(startIndex, endIndex);
}

function loadingSandbox() {
  const fixed = {
    textContent: '',
    removeAttribute() {}
  };
  const status = { textContent: '', hidden: false };
  const progress = { style: {} };
  const progressBar = {
    attributes: {},
    setAttribute(name, value) { this.attributes[name] = value; }
  };
  const home = { setAttribute() {} };
  const brand = { textContent: '' };
  const document = {
    documentElement: { lang: '' },
    title: '',
    querySelectorAll() { return []; },
    querySelector(selector) {
      return {
        '[data-loading-fixed]': fixed,
        '[data-loading-status]': status,
        '[data-progress]': progress,
        '.progress-track': progressBar,
        '[data-action="home"]': home,
        '.brand-copy small': brand
      }[selector] || null;
    },
    getElementById() { return null; }
  };
  const sandbox = {
    currentLocale: 'zh',
    currentScreen: 'welcome',
    activeLoadingMessages: null,
    activeLoadingMessageIndex: 0,
    loadingTimer: null,
    loadingTimeout: null,
    translations: { zh: {}, en: {}, es: {} },
    document,
    window: { OneStepLocale: {} },
    qaJourneyEnabled: false,
    currentFlight: null,
    awaitingDocumentSelection: false,
    currentAction: null,
    currentError: null,
    setText() {},
    t(key) { return key; },
    localeText(zh, en, es) {
      return sandbox.currentLocale === 'zh' ? zh : sandbox.currentLocale === 'es' ? es : en;
    },
    textFor(values) {
      const index = sandbox.currentLocale === 'en' ? 1 : sandbox.currentLocale === 'es' ? 2 : 0;
      return values[index] || values[1] || values[0] || '';
    },
    showScreen(screen) { sandbox.currentScreen = screen; },
    clearInterval() {},
    clearTimeout() {},
    syncVisibleDates() {},
    invalidatePngCard() {},
    prepareFamilyCard() { return Promise.resolve(); },
    updateSpeechButtons() {},
    renderFlight() {},
    renderSegmentSelector() {},
    renderJourneyResponse() {},
    renderErrorMessage() {},
    renderRefreshStatus() {},
    renderConnectivityStatus() {},
    renderManualDocumentEntry() {},
    renderStaffCard() {},
    renderStaffOverlayView() {},
    renderShareEntryVisibility() {},
    renderScenarioTestCards() {},
    persistJourney() {}
  };
  vm.createContext(sandbox);
  vm.runInContext([
    sourceBetween('  function updateLanguage()', '  function formatVisibleDate(value)'),
    sourceBetween('  function renderActiveLoadingMessage()', '  function resolvePayload(flightNumber, departureDate, demo, documentResult, demoScenario, refresh)')
  ].join('\n'), sandbox);
  return { sandbox, fixed };
}

test('loading language switches keep the current upload progress stage', () => {
  const { sandbox, fixed } = loadingSandbox();
  const messages = [
    ['正在上传文件', 'Uploading the file', 'Subiendo el archivo'],
    ['正在读取航班信息', 'Reading flight information', 'Leyendo la información del vuelo'],
    ['正在准备下一步', 'Preparing the next step', 'Preparando el siguiente paso']
  ];

  sandbox.runLoading(messages, 'documentRecognitionWaiting');
  assert.equal(fixed.textContent, '正在上传文件');
  sandbox.currentLocale = 'en';
  sandbox.updateLanguage();
  assert.equal(fixed.textContent, 'Uploading the file');
  sandbox.currentLocale = 'es';
  sandbox.updateLanguage();
  assert.equal(fixed.textContent, 'Subiendo el archivo');

  sandbox.updateLoadingProgress(72, messages, 1);
  assert.equal(fixed.textContent, 'Leyendo la información del vuelo');
  sandbox.currentLocale = 'zh';
  sandbox.updateLanguage();
  assert.equal(fixed.textContent, '正在读取航班信息');
  sandbox.currentLocale = 'en';
  sandbox.updateLanguage();
  assert.equal(fixed.textContent, 'Reading flight information');

  sandbox.updateLoadingProgress(100, messages, 2);
  sandbox.currentLocale = 'es';
  sandbox.updateLanguage();
  assert.equal(fixed.textContent, 'Preparando el siguiente paso');
});

test('live-flight loading messages also retranslate without changing progress stage', () => {
  const { sandbox, fixed } = loadingSandbox();
  const messages = [
    ['正在查询航班', 'Searching for the flight', 'Buscando el vuelo'],
    ['正在查询航班', 'Searching for the flight', 'Buscando el vuelo'],
    ['正在准备下一步', 'Preparing the next step', 'Preparando el siguiente paso']
  ];

  sandbox.runLoading(messages, 'liveFlightLookupWaiting');
  sandbox.updateLoadingProgress(100, messages, 2);
  sandbox.currentLocale = 'en';
  sandbox.updateLanguage();
  assert.equal(fixed.textContent, 'Preparing the next step');
  sandbox.currentLocale = 'es';
  sandbox.updateLanguage();
  assert.equal(fixed.textContent, 'Preparando el siguiente paso');
});

function speechSandbox() {
  const sandbox = {
    currentLocale: 'zh',
    currentScreen: 'step',
    currentFlight: null,
    currentAction: null,
    currentDestinationCopy: null,
    activeDialog: null,
    flightCorrectionOpen: false,
    document: {
      querySelector(selector) {
        return selector === '[data-action-time-primary]' ? { textContent: '' } : null;
      }
    },
    actionLocaleText(action, field) {
      const suffix = sandbox.currentLocale === 'zh' ? 'Zh' : sandbox.currentLocale === 'es' ? 'Es' : 'En';
      return action[field + suffix] || '';
    },
    isDepartureStopAction(action) {
      return Boolean(action && ['FLIGHT_CANCELLED', 'DEPARTURE_TIME_PASSED', 'FLIGHT_ALREADY_DEPARTED'].includes(action.reason));
    },
    t(key) { return key; },
    localeText(zh, en, es) {
      return sandbox.currentLocale === 'zh' ? zh : sandbox.currentLocale === 'es' ? es : en;
    }
  };
  vm.createContext(sandbox);
  vm.runInContext(
    sourceBetween('  function compactSpeechParts(parts)', '  function updateSpeechButtons()'),
    sandbox
  );
  return sandbox;
}

test('departure-stop speech keeps description but omits its duplicate destination in all languages', () => {
  const sandbox = speechSandbox();
  sandbox.currentAction = {
    stepType: 'ACTION', actionCode: 'ASK_FOR_HELP', reason: 'FLIGHT_ALREADY_DEPARTED',
    titleZh: '这趟航班已经离港',
    descriptionZh: '点击下方“请工作人员帮忙”，并前往航空公司服务柜台出示给工作人员。',
    titleEn: 'This flight has already departed',
    descriptionEn: 'Select “Ask a member of staff”, then go to the airline service desk and show this screen.',
    titleEs: 'Este vuelo ya ha salido',
    descriptionEs: 'Pulsa «Pedir ayuda al personal», ve al mostrador de atención de la aerolínea y muestra esta pantalla.'
  };
  sandbox.currentDestinationCopy = {
    primaryZh: '前往航空公司服务柜台，请工作人员帮忙',
    primaryEn: 'Go to the airline service desk and ask staff for help',
    primaryEs: 'Ve al mostrador de atención de la aerolínea y pide ayuda'
  };

  const cases = [
    ['zh', '航空公司服务柜台'],
    ['en', 'airline service desk'],
    ['es', 'mostrador de atención de la aerolínea']
  ];
  for (const [locale, phrase] of cases) {
    sandbox.currentLocale = locale;
    const speech = sandbox.buildSpeechTextForCurrentView();
    assert.equal(speech.toLowerCase().split(phrase.toLowerCase()).length - 1, 1, `${locale}: ${speech}`);
  }
});

test('non-departure actions still speak their exact destination', () => {
  const sandbox = speechSandbox();
  sandbox.currentLocale = 'en';
  sandbox.currentAction = {
    stepType: 'ACTION', actionCode: 'GO_TO_GATE', reason: 'SECURITY_COMPLETED',
    titleEn: 'Go to your gate', descriptionEn: 'Check the flight number on the screen.'
  };
  sandbox.currentDestinationCopy = {
    primaryZh: 'B32 登机口', primaryEn: 'Gate B32', primaryEs: 'Puerta B32'
  };

  assert.match(sandbox.buildSpeechTextForCurrentView(), /Gate B32/);
});
