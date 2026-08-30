'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const app = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/app.js'), 'utf8');
const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/index.html'), 'utf8');
const css = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/styles.css'), 'utf8');

function sourceBetween(start, end) {
  const from = app.indexOf(start);
  const to = app.indexOf(end, from + start.length);
  assert.notEqual(from, -1, `missing ${start}`);
  assert.notEqual(to, -1, `missing ${end}`);
  return app.slice(from, to);
}

test('journey has map and contact while the help-card top row has only contact and close', () => {
  const journeyStart = html.indexOf('class="airport-resource-actions"');
  const journey = html.slice(journeyStart, html.indexOf('data-time-status', journeyStart));
  const overlay = html.slice(html.indexOf('data-staff-overlay'), html.indexOf('data-family-overlay'));
  const topbar = overlay.slice(overlay.indexOf('staff-card-topbar'), overlay.indexOf('data-staff-card-view'));
  assert.match(journey, /data-action="open-airport-map"[\s\S]*data-action="contact-airport-resource"/);
  assert.equal((journey.match(/airport-resource-button/g) || []).length, 2);
  assert.match(topbar, /data-staff-contact-button[\s\S]*data-action="close-staff"/);
  assert.equal((overlay.match(/data-staff-contact-button/g) || []).length, 1);
  assert.equal((overlay.match(/data-action="close-staff"/g) || []).length, 1);
  assert.doesNotMatch(overlay, /staff-card-secondary-actions/);
  assert.doesNotMatch(topbar, /open-airport-map|viewAirportMap|airport map|机场地图|mapa del aeropuerto/i);
  assert.match(css, /\.airport-resource-actions\{display:grid;grid-template-columns:repeat\(2,minmax\(0,1fr\)\)/);
  assert.match(css, /\.staff-card-topbar\{display:grid;grid-template-columns:minmax\(0,1fr\) 56px/);
  assert.match(css, /@media\(max-width:390px\)\{[\s\S]*\.staff-card-topbar\{grid-template-columns:minmax\(0,1fr\) 56px;gap:8px\}/);
  assert.match(css, /html\{min-width:0;overflow-x:hidden\}/);
});

test('combined response validates map and contact independently including exact Maps phone evidence', () => {
  const verify = sourceBetween('  function verifiedAirportResourcesResponse', '  function renderStaffOverlayView');
  assert.match(verify, /!response\.map \|\| !response\.contact/);
  assert.match(verify, /'FOUND', 'OFFICIAL_PAGE_ONLY', 'AIRPORT_LOCATION', 'NOT_FOUND', 'TEMPORARILY_UNAVAILABLE'/);
  assert.match(verify, /'PHONE_FOUND', 'CONTACT_PAGE_ONLY', 'NOT_FOUND', 'TEMPORARILY_UNAVAILABLE'/);
  assert.ok(verify.includes('/^tel:\\+?\\d{5,18}$/'));
  assert.match(verify, /response\.contact\.sourceType === 'GOOGLE_MAPS'/);
  assert.match(verify, /airportIdentityConfirmed/);
  assert.match(verify, /mapsStructuredPhonePresent/);
  assert.match(verify, /map: \{ status: mapStatus/);
  assert.match(verify, /contact: \{ status: status/);
});

test('map and contact use separate cache and in-flight keys with selective targets', () => {
  const context = sourceBetween('  function airportResourceUsesArrival', '  function preferredOperationalTime');
  const request = sourceBetween('  function requestAirportResources(context, forceRefresh, refreshTarget)',
    '  function pushAirportResourceHistory()');
  const cache = sourceBetween('  function cachedAirportResources(context)',
    '  function requestAirportResources(context, forceRefresh, refreshTarget)');
  assert.match(context, /contactKey: airportCode/);
  assert.match(context, /mapKey: airportCode \+ ':'/);
  assert.match(cache, /mode === 'BOTH'/);
  assert.match(cache, /airportMapCache\[context\.mapKey\]/);
  assert.match(cache, /airportContactCache\[context\.contactKey\]/);
  assert.match(request, /target !== 'CONTACT'\) delete airportMapCache/);
  assert.match(request, /target !== 'MAP'\) delete airportContactCache/);
  assert.match(request, /refreshTarget: target/);
  assert.match(request, /inFlightKey = context\.key \+ ':' \+ target/);
  assert.match(request, /airportResourceInFlight\[inFlightKey\].*promise/);
  assert.doesNotMatch(request, /airportResourceInFlight\[context\.key\]/);
  assert.match(request, /storeAirportResources\(context, verified, target\)/);
  assert.match(request, /controller\.abort\(\); \}, 60000\)/);
  assert.doesNotMatch(request, /controller\.abort\(\); \}, 35000\)/);
});

test('airport resources are fetched only after an explicit map or contact action', () => {
  const open = sourceBetween('  function openAirportResource(mode, trigger)', '  function retryAirportResource()');
  const request = sourceBetween('  function requestAirportResources(context, forceRefresh, refreshTarget)',
    '  function pushAirportResourceHistory()');
  assert.match(open, /requestAirportResources\(context, false, mode\)/);
  assert.match(request, /refreshTarget: target/);
  assert.match(app, /openAirportResource\('MAP', actionElement\)/);
  assert.match(app, /openAirportResource\('CONTACT', actionElement\)/);
  const flightLookup = sourceBetween('  function startFlightLookup', '  function validDocumentFlightNumber');
  assert.doesNotMatch(flightLookup, /requestAirportResources|openAirportResource/);
});

test('map and contact result pages remain separate and never auto-open or auto-dial', () => {
  const render = sourceBetween('  function renderAirportResourceView()', '  function airportResourceLoadingSpeech');
  const open = sourceBetween('  function openAirportResource(mode, trigger)', '  function retryAirportResource()');
  assert.match(render, /mapMode && result\.map\.status === 'FOUND'/);
  assert.match(render, /result\.map\.status === 'AIRPORT_LOCATION'/);
  assert.match(render, /result\.contact\.status === 'PHONE_FOUND'/);
  assert.match(render, /result\.contact\.status === 'CONTACT_PAGE_ONLY'/);
  assert.match(render, /setAirportResourceLink\(phoneLink, result\.contact\.telUri/);
  assert.match(render, /setAirportResourceLink\(mapLink, result\.map\.url/);
  assert.match(open, /origin = .*\? 'JOURNEY_STEP' : 'HELP_CARD'/);
  assert.match(open, /mode === 'MAP' && origin !== 'JOURNEY_STEP'/);
  assert.doesNotMatch(open, /window\.location|window\.open/);
});

test('return and close preserve the source contract and restore focus', () => {
  const back = sourceBetween('  function returnToStaffCard(fromHistory)', '  function syncAirportResourcesContext()');
  const close = sourceBetween('  function closeStaff()', '  function openDialog(');
  const pop = sourceBetween("  window.addEventListener('popstate'", "  document.addEventListener('visibilitychange'");
  assert.match(back, /airportResourceOrigin === 'JOURNEY_STEP'/);
  assert.match(back, /finishCloseStaff\(\)/);
  assert.match(back, /cardContact\.focus\(\{ preventScroll: true \}\)/);
  assert.match(close, /airportResourceOrigin === 'JOURNEY_STEP' \? airportResourceReturnFocus : staffReturnFocusElement/);
  assert.match(close, /returnFocus\.focus\(\)/);
  assert.match(pop, /returnToStaffCard\(true\)/);
  assert.match(pop, /finishCloseStaff\(\)/);
});

test('map and contact copy is complete in Chinese English and Spanish without technical loading terms', () => {
  for (const value of [
    '查看机场地图', '联系机场', '正在查找机场地图……', '正在查找机场联系方式……',
    '可能需要等候近一分钟。',
    'View airport map', 'Contact airport', 'Finding the airport map…', 'Finding airport contact details…',
    'This may take up to a minute.',
    'Ver mapa del aeropuerto', 'Contactar con el aeropuerto', 'Buscando el mapa del aeropuerto…',
    'Buscando los datos de contacto del aeropuerto…', 'Esto puede tardar hasta un minuto.'
  ]) assert.ok(app.includes(value), value);
  const translations = app.slice(app.indexOf("viewAirportMap: '查看机场地图'"),
    app.indexOf('  Object.assign(translations.zh, { datePlaceholder'));
  assert.doesNotMatch(translations, /SerpApi|Google AI|\bAPI\b|parser|candidate|confidence/i);
});

test('one-minute guidance is rendered only while the airport resource view is loading', () => {
  const render = sourceBetween('  function renderAirportResourceView()', '  function airportResourceLoadingSpeech');
  const loadingBranch = render.slice(render.indexOf('if (loading)'), render.indexOf('if (!result)'));
  assert.match(loadingBranch, /detail\.textContent = t\('airportResourceWaitHint'\)/);
  assert.match(render, /detail\.textContent = ''/);
  assert.match(html, /airport-resource-status" role="status" aria-live="polite" aria-atomic="true"/);
  assert.match(css, /\.airport-resource-detail\{[^}]*font-size:clamp\(17px,2\.5vw,21px\)/);
});

test('localhost QA appears only on confirm and submits one forced BOTH lookup without an airport name', () => {
  const source = sourceBetween('  function airportResourceQaAllowed(locationValue)',
    '  function setupAirportResourceQaPanel()');
  const allowed = new Function(`${source}; return airportResourceQaAllowed;`)();
  assert.equal(allowed({ hostname: 'localhost', search: '?qaAirport=1' }), true);
  assert.equal(allowed({ hostname: '127.0.0.1', search: '?qaAirport=1' }), true);
  assert.equal(allowed({ hostname: 'example.test', search: '?qaAirport=1' }), false);
  const qa = html.slice(html.indexOf('data-qa-airport-panel'), html.indexOf('site-footer'));
  const sync = sourceBetween('  function syncAirportResourceQaVisibility()',
    '  function setupAirportResourceQaPanel()');
  const setup = sourceBetween('  function setupAirportResourceQaPanel()',
    '  function renderAirportQaResult(');
  const show = sourceBetween('  function showScreen(name, options)', '  function navigate(name)');
  assert.match(qa, /机场信息查询/);
  assert.match(qa, /航站楼（可选）/);
  assert.match(qa, />三字母</);
  assert.doesNotMatch(qa, /name="airportName"|机场英文名称|强制刷新/);
  assert.equal((qa.match(/type="submit"/g) || []).length, 1);
  assert.match(sync, /currentScreen === 'confirm' && !activeDialog/);
  assert.match(show, /syncAirportResourceQaVisibility\(\)/);
  assert.match(setup, /airportName: null/);
  assert.match(setup, /requestAirportResources\(context, true, 'BOTH'\)/);
});

test('QA result is action-oriented and contains no debug disclosure or technical fields', () => {
  const result = sourceBetween('  function renderAirportQaResult(', '  function appendQaHeading(');
  assert.match(result, /airportResourceDisplayName/);
  assert.match(result, /appendQaAction\(output, t\('callAirport'\)/);
  assert.match(result, /appendQaAction\(output, t\('openOfficialMap'\)/);
  assert.doesNotMatch(result, /document\.createElement\('details'\)|dataQaAirportDebug|qaAirportDebug/);
  assert.doesNotMatch(result, /resultType|evidenceTier|rejectionReasons|sourceLanguage|sourceUrl|serpApiRequestCount|elapsedMs/);
  assert.doesNotMatch(app, /data-qa-airport-debug|qa-airport-debug|qaAirportDebug/);
  assert.doesNotMatch(css, /qa-airport-debug/);
});

test('airport names never render as a duplicated IATA pair', () => {
  const display = sourceBetween('  function airportResourceDisplayName(result, context)',
    '  function renderAirportResourceView()');
  const format = new Function(`${display}; return airportResourceDisplayName;`)();
  assert.equal(format({ airportCode: 'STR', airportName: 'STR', localAirportName: '' }, {}), 'STR');
  assert.equal(format({ airportCode: 'STR', airportName: 'STR', localAirportName: '星港国际机场' }, {}),
    '星港国际机场 (STR)');
});

test('all dismissible airport, help, and family surfaces share centered SVG close controls', () => {
  const staff = html.slice(html.indexOf('data-staff-overlay'), html.indexOf('data-family-overlay'));
  const family = html.slice(html.indexOf('data-family-overlay'), html.indexOf('data-end-overlay'));
  const closePath = /<svg viewBox="0 0 24 24"[^>]*>[\s\S]*?<path d="M6 6L18 18M18 6L6 18"><\/path>[\s\S]*?<\/svg>/;
  assert.match(staff, closePath);
  assert.match(family, closePath);
  assert.doesNotMatch(staff + family, />×<|data-action="close-family"[^>]*>关闭/);
  assert.match(family, /data-action="close-family" data-i18n-aria="close"/);
  assert.match(css, /\.card-close\{width:56px;height:56px;min-width:56px;min-height:56px;padding:0;display:grid;place-items:center;line-height:0/);
  assert.match(css, /\.card-close svg\{display:block;width:28px;height:28px/);
  assert.match(css, /\.staff-card-topbar\{display:grid;grid-template-columns:minmax\(0,1fr\) 56px/);
  assert.equal((staff.match(/data-action="close-staff"/g) || []).length, 1);
  assert.ok(staff.indexOf('staff-card-topbar') < staff.indexOf('data-staff-card-view'));
  assert.ok(staff.indexOf('staff-card-topbar') < staff.indexOf('data-airport-resource-view'));
});

test('airport context follows journey direction without airport-specific constants', () => {
  const functions = sourceBetween('  function airportResourceUsesArrival', '  function preferredOperationalTime');
  const resolve = new Function('currentFlight', 'currentAction', 'journeyContext',
    `${functions}; return currentAirportResourcesContext();`);
  const flight = { departureAirport: 'STR', departureAirportName: 'Starport', terminal: 'Terminal 1',
    arrivalAirport: 'PLN', arrivalAirportName: 'Plainport', arrivalTerminal: 'Terminal 2' };
  assert.equal(resolve(flight, { stepType: 'ACTION', actionCode: 'GO_TO_GATE' }, {}).contactKey, 'STR');
  assert.equal(resolve(flight, { stepType: 'ACTION', actionCode: 'GO_TO_GATE' }, {}).mapKey, 'STR:1');
  assert.equal(resolve(flight, { stepType: 'ACTION', actionCode: 'IN_FLIGHT' }, {}).mapKey, 'PLN:2');
  assert.equal(resolve(flight, { stepType: 'ACTION', actionCode: 'BAGGAGE_CLAIM' }, {}).mapKey, 'PLN:2');
  assert.equal(resolve(flight, { stepType: 'ACTION', actionCode: 'ASK_FOR_HELP' }, { boarded: true }).mapKey, 'PLN:2');
  assert.equal(resolve(flight, { stepType: 'ACTION', actionCode: 'ASK_FOR_HELP' }, { boarded: false }).mapKey, 'STR:1');
});
