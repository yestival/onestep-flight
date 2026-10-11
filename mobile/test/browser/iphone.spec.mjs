import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { deterministicInjectionSource } from '../../../scripts/browser-acceptance.mjs';

async function layout(page) {
  const overflow = await page.evaluate(() => {
    const width = document.documentElement.clientWidth;
    return [...document.querySelectorAll('button,input,select,textarea,h1,h2,p,dd')]
      .filter(el => el.getClientRects().length && !el.closest('[hidden]') && !el.closest('.visually-hidden'))
      .map(el => ({ tag: el.tagName, text: el.textContent.slice(0,60), rect: el.getBoundingClientRect().toJSON() }))
      .filter(el => el.rect.left < -1 || el.rect.right > width + 1);
  });
  expect(overflow).toEqual([]);
}
test('packaged assets preserve three languages across 320–440px, file review and dialogs', async ({ page }) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(deterministicInjectionSource());
  await page.clock.setFixedTime(new Date('2026-09-05T07:00:00Z'));
  await page.goto('/');
  for (const width of [320, 375, 390, 440]) {
    await page.setViewportSize({ width, height: 844 });
    for (const locale of ['zh', 'en', 'es']) {
      await page.locator('[data-locale-select]').selectOption(locale);
      await layout(page);
      await page.locator('[data-screen="welcome"] [data-action="manual"]').click();
      await layout(page);
      await page.locator('[data-screen="manual"] [data-action="home"]').click();
    }
  }
  await page.locator('#ticket-file').setInputFiles({ name: 'itinerary.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-fixture') });
  await expect(page.locator('[data-segment-selector]')).toBeVisible();
  await layout(page);
  await page.locator('[data-action="select-segment"][data-segment-index="1"]').click();
  // The fixture explicitly requires confirming its second flight number.
  const flightReview = page.locator('[data-action="confirm-segment-flight-number"]');
  if (await flightReview.isVisible()) await flightReview.click();
  await layout(page);
  await page.screenshot({ path: 'test-results/iphone-document-review-' + test.info().project.name + '.png', fullPage: true });
  expect(errors).toEqual([]);
});

const snapshot = (now, age = 0) => ({
  version: 1, savedAt: now - age, qaJourney: false,
  flight: { flightNumber: 'AF1668', departureDate: '2026-09-05', departureAirport: 'CDG', arrivalAirport: 'MAN', terminal: '2E', gate: 'B12',
    status: 'SCHEDULED', live: true, dataRetrievedAt: new Date(now - age).toISOString(), dataLastUpdatedAt: new Date(now - age).toISOString(),
    timeContext: { serverNow: new Date(now).toISOString(), departureUtcOffset: '+02:00', effectiveDeparture: '2026-09-05T11:25:00+02:00', boardingTime: '2026-09-05T10:45:00+02:00' } },
  context: { flightNumber: 'AF1668', departureDate: '2026-09-05', checkedIn: true, hasCheckedBaggage: false, securityCleared: false },
  action: { stepType: 'ACTION', actionCode: 'SECURITY', titleZh: '现在前往安检', titleEn: 'Go to security now', titleEs: 'Ve ahora al control de seguridad', destination: 'Security', urgency: 'NORMAL' },
  history: [], events: [], screen: 'step', locale: 'en'
});

test('bundled Capacitor iOS bridge starts a confirmed journey with an unanswered push callback', async ({ page }) => {
  const now = Date.parse('2026-09-05T07:00:00Z');
  const saved = snapshot(now);
  saved.flight.flightNumber = 'BA117'; saved.context.flightNumber = 'BA117';
  saved.flight.departureAirport = 'LHR'; saved.flight.arrivalAirport = 'JFK';
  saved.flight.terminal = 'T5'; saved.flight.gate = 'B32';
  const bridge = readFileSync(new URL('../../node_modules/@capacitor/ios/Capacitor/Capacitor/assets/native-bridge.js', import.meta.url), 'utf8');
  // Keep native.js, registerPlugin and the installed Capacitor bridge untouched.
  // Only the WKWebView message port is doubled; Swift/ActivityKit still need iOS.
  await page.addInitScript({ content: `
    window.bridgeCalls = [];
    window.bridgeDisk = new Map([['onestep-flight-offline-journey-v1', ${JSON.stringify(JSON.stringify(saved))}]]);
    window.bridgeActivity = {supported:true,enabled:true,active:false};
    window.webkit = {messageHandlers:{bridge:{postMessage(call) {
      if (call.type !== 'message') return;
      bridgeCalls.push(JSON.parse(JSON.stringify(call)));
      if (call.methodName === 'addListener' || call.pluginId === 'FlightPush' && call.methodName === 'syncJourney') return;
      let data = {}, o = call.options || {};
      if (call.pluginId === 'Preferences') {
        if (call.methodName === 'get') data = {value:bridgeDisk.get(o.key) || null};
        if (call.methodName === 'set') bridgeDisk.set(o.key, o.value);
        if (call.methodName === 'remove') bridgeDisk.delete(o.key);
      }
      if (call.pluginId === 'App' && call.methodName === 'getState') data = {isActive:true};
      if (call.pluginId === 'Network') data = {connected:true};
      if (call.pluginId === 'Filesystem') data = {files:[]};
      if (call.pluginId === 'FlightLiveActivity') {
        if (call.methodName === 'start') bridgeActivity = {...bridgeActivity,active:true,journeyKey:o.journeyKey};
        if (call.methodName === 'end' && bridgeActivity.journeyKey === o.journeyKey) bridgeActivity.active = false;
        data = {...bridgeActivity};
      }
      queueMicrotask(() => Capacitor.fromNative({callbackId:call.callbackId,pluginId:call.pluginId,methodName:call.methodName,success:true,data,save:false}));
    }}}};
    ${bridge}
    Capacitor.PluginHeaders = Object.entries({
      Preferences:['get','set','remove'], App:['getState','getLaunchUrl'], Network:['getStatus'], Filesystem:['readdir','deleteFile'],
      FlightPush:['initialize','syncJourney','status','clearJourney','openSettings'], FlightLiveActivity:['status','start','update','end'],
      FlightNative:['openExternal'], Share:['share']
    }).map(([name, methods]) => ({name, methods: [...methods.map(name => ({name,rtype:'promise'})), {name:'addListener'},{name:'removeListener'}]}));
  ` });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.clock.setFixedTime(new Date(now));
  await page.route('**/api/**', route => route.abort());
  await page.goto('/');
  await expect(page.locator('[data-screen="step"]')).toBeVisible();
  await expect.poll(() => page.evaluate(() => bridgeCalls.filter(c => c.pluginId === 'FlightLiveActivity' && c.methodName === 'start').length)).toBe(1);
  const start = await page.evaluate(() => bridgeCalls.find(c => c.pluginId === 'FlightLiveActivity' && c.methodName === 'start').options);
  expect(start.flightNumber).toBe('BA 117');
  expect(start.gate).toBe('B32');
  expect(Number.isInteger(start.journeyEndsAt)).toBe(true);
  await page.locator('[data-locale-select]').selectOption('zh');
  await expect.poll(() => page.evaluate(() => bridgeCalls.filter(c => c.pluginId === 'FlightLiveActivity' && c.methodName === 'update').length)).toBeGreaterThan(0);
  expect(await page.evaluate(() => bridgeCalls.filter(c => c.pluginId === 'FlightLiveActivity' && c.methodName === 'start').length)).toBe(1);
  expect(errors).toEqual([]);
});

async function mockedNative(page, disk, options = {}) {
  const calls = [];
  await page.exposeFunction('nativeStorage', async (op, key, value) => {
    if (op === 'get') { await new Promise(resolve => setTimeout(resolve, 120)); return disk.get(key) ?? null; }
    if (op === 'set') disk.set(key, value); else if (op === 'remove') disk.delete(key);
  });
  await page.exposeFunction('nativeCall', async (name, payload) => { calls.push({ name, payload }); });
  await page.route('**/native.js', route => route.fulfill({ contentType: 'text/javascript', body: `
    window.nativeEvents = {}; window.activity = { supported:true, enabled:true, active:false };
    window.activityFailure = ${JSON.stringify(options.activityFailure || null)};
    window.holdJourneyPreparation = ${Boolean(options.holdJourneyPreparation)};
    window.OneStepNative = { isNative:true,
      Preferences: { get:async({key})=>({value:await nativeStorage('get',key)}),set:({key,value})=>nativeStorage('set',key,value),remove:({key})=>nativeStorage('remove',key) },
      App: { addListener:async(k,f)=>{nativeEvents[k]=f}, getState:async()=>({isActive:true}),getLaunchUrl:async()=>({url:'onestepflight://journey'}) },
      Network: { addListener:async(k,f)=>{nativeEvents[k]=f},getStatus:async()=>({connected:true}) },
      Share: { share:async p=>{await nativeCall('share',p);throw new Error('Share canceled')} },
      Filesystem: { readdir:async()=>({files:[]}),writeFile:async p=>{await nativeCall('write',p);return {uri:'file:///cache/card.png'}},deleteFile:async p=>nativeCall('delete',p) },
      FlightNative: {openExternal:async p=>nativeCall('openExternal',p)},
      FlightPush: {addListener:async()=>{},initialize:async()=>({ready:true}),syncJourney:async p=>{
        await nativeCall('syncJourney',p);
        if (holdJourneyPreparation) {
          window.journeyPreparation ||= new Promise(resolve=>window.finishJourneyPreparation=resolve);
          await window.journeyPreparation;
          if (activity.journeyKey === 'old-flight') activity.active=false;
          holdJourneyPreparation=false;
          await nativeCall('previousJourneyCleared');
        }
        return {};
      },clearJourney:async()=>nativeCall('clearJourney'),openSettings:async()=>nativeCall('settings')},
      FlightLiveActivity: {status:async()=>activity,start:async p=>{await nativeCall('start',p);return activity={supported:true,enabled:true,active:!activityFailure,journeyKey:p.journeyKey,...(activityFailure || {})}},update:async p=>{await nativeCall('update',p);return activity},end:async()=>{await nativeCall('end');activity.active=false}}
    };` }));
  return calls;
}

test('automatic activity creation proceeds while push sync is stalled and late old cleanup is harmless', async ({ page }) => {
  const now = Date.parse('2026-09-05T07:00:00Z');
  const disk = new Map([['onestep-flight-offline-journey-v1', JSON.stringify(snapshot(now))]]);
  const calls = await mockedNative(page, disk, { holdJourneyPreparation: true });
  await page.clock.setFixedTime(new Date(now));
  await page.route('**/api/**', route => route.abort());
  await page.goto('/');
  await expect(page.locator('[data-screen="step"]')).toBeVisible();
  await expect.poll(() => calls.filter(call => call.name === 'syncJourney').length).toBeGreaterThan(0);
  await expect.poll(() => page.evaluate(() => typeof window.finishJourneyPreparation)).toBe('function');
  await expect.poll(() => calls.filter(call => call.name === 'start').length).toBe(1);
  await expect(page.locator('[data-live-activity-status]')).toContainText('Journey sent to iOS');
  await page.evaluate(() => finishJourneyPreparation());
  await expect.poll(() => calls.filter(call => call.name === 'start').length).toBe(1);
  expect(calls.findIndex(call => call.name === 'previousJourneyCleared')).toBeGreaterThan(calls.findIndex(call => call.name === 'start'));
  await expect.poll(() => page.evaluate(() => activity.active)).toBe(true);
  await page.locator('[data-locale-select]').selectOption('zh');
  await expect.poll(() => calls.filter(call => call.name === 'update').length).toBeGreaterThan(0);
  expect(calls.filter(call => call.name === 'start')).toHaveLength(1);
});

test('confirming a flight creates its activity automatically when push synchronization fails', async ({ page }) => {
  const now = Date.parse('2026-09-05T07:00:00Z');
  const saved = snapshot(now);
  saved.context = null; saved.action = null; saved.screen = 'confirm';
  const disk = new Map([['onestep-flight-offline-journey-v1', JSON.stringify(saved)]]);
  const calls = await mockedNative(page, disk);
  await page.clock.setFixedTime(new Date(now));
  await page.route('**/api/**', route => route.abort());
  await page.goto('/');
  await expect(page.locator('[data-screen="confirm"]')).toBeVisible();
  expect(calls.filter(call => call.name === 'start')).toHaveLength(0);
  await page.evaluate(() => {
    OneStepNative.FlightPush.syncJourney = async () => { throw new Error('Push registration unavailable'); };
  });
  await page.locator('[data-action="continue"]').click();
  await expect(page.locator('[data-screen="step"]')).toBeVisible();
  await expect.poll(() => calls.filter(call => call.name === 'start').length).toBe(1);
  await expect(page.locator('[data-live-activity-status]')).toContainText('Journey sent to iOS');
  expect(calls.find(call => call.name === 'start').payload.gate).toBe('B12');
});

test('native activity creation failure is visible and foreground recovery precedes a stalled API request', async ({ page }) => {
  const now = Date.parse('2026-09-05T07:00:00Z');
  const disk = new Map([['onestep-flight-offline-journey-v1', JSON.stringify(snapshot(now))]]);
  const calls = await mockedNative(page, disk, { activityFailure: { errorCode:'LIVE_ACTIVITY_REQUEST_FAILED', errorReference:'ActivityKit:7' } });
  await page.clock.setFixedTime(new Date(now));
  await page.route('**/api/**', route => route.abort());
  await page.goto('/');
  const notice = page.locator('[data-live-activity-status]');
  await expect(notice).toBeVisible();
  await expect(notice).toContainText('ActivityKit:7');
  await expect(notice.locator('button')).toBeHidden();
  const attempts = calls.filter(call => call.name === 'start').length;
  let releaseRequest;
  await page.route('**/api/journeys/next-action', async route => {
    await new Promise(resolve => { releaseRequest = resolve; });
    await route.abort();
  });
  await page.clock.setFixedTime(new Date(now + 3000));
  await page.evaluate(() => {
    activityFailure = null;
    nativeEvents.appStateChange({ isActive: false });
    nativeEvents.appStateChange({ isActive: true });
  });
  await expect.poll(() => calls.filter(call => call.name === 'start').length).toBeGreaterThan(attempts);
  await expect(notice).toContainText('Journey sent to iOS');
  expect(calls.find(call => call.name === 'start').payload.gate).toBe('B12');
  if (releaseRequest) releaseRequest();
});

test('native cold start waits for private storage; offline state survives a fresh page and share cancellation', async ({ page, context }) => {
  const now = Date.parse('2026-09-05T07:00:00Z');
  const key = 'onestep-flight-offline-journey-v1';
  const disk = new Map([[key, JSON.stringify(snapshot(now))], ['onestep-flight-locale', 'es']]);
  const calls = await mockedNative(page, disk);
  await page.clock.setFixedTime(new Date(now));
  await page.route('**/api/**', route => route.abort());
  await page.goto('/');
  await expect(page.locator('[data-screen="step"]')).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('lang', 'es');
  await expect(page.locator('[data-screen="step"] [data-offline-status]')).toBeVisible();
  await layout(page);
  await expect(page.locator('[data-action="toggle-live-activity"]')).toHaveCount(0);
  await expect.poll(() => calls.filter(call => call.name === 'start').length).toBe(1);
  expect(calls.find(call => call.name === 'start').payload.stale).toBe(true);
  expect(calls.find(call => call.name === 'start').payload.boardingTime).toBe('10:45');
  await page.evaluate(() => { activity.active = false; activity.dismissedJourneyKey = activity.journeyKey; nativeEvents.appStateChange({ isActive: false }); nativeEvents.appStateChange({ isActive: true }); });
  await page.waitForTimeout(500);
  expect(calls.filter(call => call.name === 'start').length).toBe(1);
  await page.locator('[data-screen="step"] [data-action="family"]').click();
  await expect(page.locator('[data-action="share-family-card"]')).toBeEnabled();
  await page.locator('[data-action="share-family-card"]').click();
  await expect.poll(() => calls.filter(call => call.name === 'share').length).toBe(1);
  await expect.poll(() => calls.filter(call => call.name === 'delete').length).toBe(1);
  expect(calls.some(call => call.name === 'delete')).toBe(true);
  await page.close();
  const reopened = await context.newPage();
  await mockedNative(reopened, disk);
  await reopened.clock.setFixedTime(new Date(now));
  await reopened.route('**/api/**', route => route.abort());
  await reopened.goto('/');
  await expect(reopened.locator('[data-screen="step"]')).toBeVisible();
  expect(JSON.parse(disk.get(key)).flight.flightNumber).toBe('AF1668');
  expect(disk.get(key)).not.toMatch(/updateToken|passport|documentRootFields/);
});

test('expired native snapshot is not revived and pending revocation survives clearing', async ({ page }) => {
  const now = Date.parse('2026-09-05T07:00:00Z');
  const revokeKey = 'onestep-flight-share-revocation-pending-v1';
  const disk = new Map([['onestep-flight-offline-journey-v1', JSON.stringify(snapshot(now, 49 * 3600000))],
    [revokeKey, JSON.stringify([{viewToken:'view',updateToken:'private'}])]]);
  await mockedNative(page, disk);
  await page.clock.setFixedTime(new Date(now));
  await page.route('**/api/**', route => route.abort());
  await page.goto('/');
  await expect(page.locator('#main-content')).not.toHaveAttribute('inert');
  await expect(page.locator('[data-screen="welcome"]')).toBeVisible();
  expect(disk.has(revokeKey)).toBe(true);
});

test('native cold restoration keeps unresolved flight confirmation without saving document conflict values', async ({ page }) => {
  const now = Date.parse('2026-09-05T07:00:00Z');
  const saved = snapshot(now);
  saved.action = null;
  saved.context = null;
  saved.screen = 'confirm';
  saved.flight.requiresUserConfirmation = true;
  saved.flight.conflicts = [{ blocking: true, field: 'gate', documentValue: 'OCR-PRIVATE', liveValue: 'B12',
    messageEn: 'Confirm the gate with staff.' }];
  const key = 'onestep-flight-offline-journey-v1';
  const disk = new Map([[key, JSON.stringify(saved)]]);
  await mockedNative(page, disk);
  await page.clock.setFixedTime(new Date(now));
  await page.route('**/api/**', route => route.abort());
  await page.goto('/');
  await expect(page.locator('[data-action="continue"]')).toBeDisabled();
  await page.evaluate(() => nativeEvents.appStateChange({ isActive: false }));
  await expect.poll(() => disk.get(key)).not.toContain('OCR-PRIVATE');
  expect(JSON.parse(disk.get(key)).flight.requiresUserConfirmation).toBe(true);
  await page.reload();
  await expect(page.locator('[data-conflict-card]')).toBeVisible();
  await expect(page.locator('[data-action="continue"]')).toBeDisabled();
  await page.locator('[data-action="confirm-conflicts"]').click();
  await expect(page.locator('[data-action="continue"]')).toBeEnabled();
});

test('reconnect and foreground events produce one flight verification and deliver deferred revocations', async ({ page }) => {
  const now = Date.parse('2026-09-05T07:00:00Z');
  const saved = snapshot(now);
  const pending = 'onestep-flight-share-revocation-pending-v1';
  const disk = new Map([['onestep-flight-offline-journey-v1', JSON.stringify(saved)],
    [pending, JSON.stringify([{ viewToken: 'view', updateToken: 'private' }])]]);
  await mockedNative(page, disk);
  await page.clock.setFixedTime(new Date(now));
  let connected = false;
  const requests = [];
  await page.route('**/api/**', async route => {
    if (!connected) return route.abort();
    requests.push(route.request().method() + ' ' + new URL(route.request().url()).pathname);
    if (route.request().method() === 'DELETE') return route.fulfill({ status: 204 });
    return route.fulfill({ json: { flight: saved.flight, nextAction: saved.action } });
  });
  await page.goto('/');
  await expect(page.locator('[data-screen="step"]')).toBeVisible();
  await page.waitForTimeout(500);
  connected = true;
  await page.clock.setFixedTime(new Date(now + 3000));
  await page.evaluate(() => {
    nativeEvents.appStateChange({ isActive: false });
    nativeEvents.networkStatusChange({ connected: false });
    nativeEvents.appStateChange({ isActive: true });
    nativeEvents.networkStatusChange({ connected: true });
    window.dispatchEvent(new Event('online'));
  });
  await expect.poll(() => disk.has(pending)).toBe(false);
  await expect(page.locator('[data-screen="step"] [data-offline-status]')).toBeHidden();
  expect(requests.filter(value => value.includes('/api/journeys/next-action'))).toHaveLength(1);
  expect(requests.filter(value => value.startsWith('DELETE'))).toHaveLength(1);
});

test('complete simulated journey, both baggage branches, three languages and phone dialogs', async ({ page }) => {
  test.setTimeout(120000);
  await page.addInitScript(deterministicInjectionSource() + `
    const fixedFetch = window.fetch;
    window.fetch = function(url, options) {
      if (String(url).endsWith('/api/journeys/resolve-flight')) {
        const now = Date.now(), departure = new Date(now + 2 * 3600000).toISOString();
        return Promise.resolve(new Response(JSON.stringify({
          flightNumber:'BA117', departureDate:departure.slice(0,10), departureAirport:'LHR', arrivalAirport:'JFK',
          terminal:'5', gate:'B32', demo:true, demoScenario:'NORMAL', live:false, status:'SCHEDULED',
          dataRetrievedAt:new Date(now).toISOString(), conflicts:[], latestChanges:[],
          timeContext:{serverNow:new Date(now).toISOString(),departureUtcOffset:'+01:00',effectiveDeparture:departure,boardingTime:new Date(now+90*60000).toISOString()}
        }),{headers:{'Content-Type':'application/json'}}));
      }
      return fixedFetch(url, options);
    };`);
  const complete = page.locator('[data-complete-action]');
  for (const locale of ['en','zh','es']) {
    for (const baggage of ['yes','no']) {
      await page.goto('/?qaJourney=true');
      await page.locator('[data-locale-select]').selectOption(locale);
      await expect(page.locator('[data-screen="confirm"]')).toBeVisible();
      await page.locator('[data-action="continue"]').click();
      await page.locator('[data-action="checkin-status"][data-answer="yes"]').click();
      await page.locator('[data-action="baggage"][data-answer="' + baggage + '"]').click();
      for (const width of [320, 440]) {
        await page.setViewportSize({ width, height: 844 });
        await layout(page);
        await page.locator('[data-screen="step"] [data-action="staff"]:visible').click();
        await layout(page);
        await page.locator('[data-action="close-staff"]').click();
        await page.locator('[data-screen="step"] [data-action="open-journey-problem"]').click();
        await layout(page);
        await page.locator('[data-action="close-journey-problem"]').click();
      }
      if (baggage === 'yes') await complete.click(); // Bag drop.
      await complete.click(); // Security.
      await complete.click(); // Arrival at the gate.
      await complete.click(); // Boarding must be confirmed.
      await expect(page.locator('[data-board-overlay]')).toBeVisible();
      await page.locator('[data-action="cancel-board"]').click();
      await expect(page.locator('[data-screen="step"]')).toBeVisible();
      await complete.click();
      await page.locator('[data-action="confirm-board"]').click();
      await complete.click();
      await page.locator('[data-action="confirm-landed"]').click();
      if (baggage === 'yes') await complete.click(); // Baggage claim.
      await complete.click(); // Exit.
      await expect(page.locator('[data-screen="complete"]')).toBeVisible();
      await layout(page);
    }
  }
});
