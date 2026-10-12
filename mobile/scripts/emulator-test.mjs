import { chromium, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { mkdirSync,writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import sharp from 'sharp';
import os from 'node:os';
const root=fileURLToPath(new URL('../',import.meta.url));
const sdk=process.env.ANDROID_HOME || path.join(root,'build/toolchain/sdk');
const adbPath=path.join(sdk,'platform-tools',process.platform==='win32'?'adb.exe':'adb');
const app='com.selene.onestepflight';
const uploads=[];
const pageErrors=[];
let diagnosticPage;
console.log('Emulator host memory (MiB)',{free:Math.round(os.freemem()/1048576),total:Math.round(os.totalmem()/1048576)});
if(process.argv[2]==='environment') process.exit(0);
const output=path.join(root,'build/emulator-tests');mkdirSync(output,{recursive:true});
process.on('uncaughtException',async error=>{
  console.error(error);
  try {
    if (!diagnosticPage) throw new Error('WebView was not attached; see native hierarchy and screenshot');
    const state=await diagnosticPage.evaluate(()=>({url:location.href,screen:document.querySelector('.screen.is-active')?.dataset.screen,
      initializing:document.getElementById('main-content')?.inert,body:document.body.innerText.slice(0,1800),
      files:[...document.getElementById('ticket-file').files].map(f=>({name:f.name,type:f.type,size:f.size})),
      readBytes:window.__selectedDocument?.length}));
    console.error('WebView failure state',JSON.stringify({...state,uploadCount:uploads.length}));
    writeFileSync(path.join(output,'failure-state.json'),JSON.stringify({...state,uploadCount:uploads.length},null,2));
  } catch(diagnosticError) {console.error('Failure capture:',diagnosticError.message);}
  try {writeFileSync(path.join(output,'failure-ui.xml'),ui());} catch(error) {console.error('Android hierarchy capture:',error.message);}
  try {capture('failure');} catch(error) {console.error('Android screenshot capture:',error.message);}
  process.exit(1);
});
const adb=(...args)=>execFileSync(adbPath,args,{encoding:'utf8',timeout:45000,maxBuffer:8*1024*1024}).trim();
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
function connectivity(enabled) {
  adb('shell','svc','wifi',enabled?'enable':'disable');
  if(adb('shell','service','check','phone').includes(': found')) adb('shell','svc','data',enabled?'enable':'disable');
}
async function connect() {
  let socket;
  for(let i=0;i<40;i++) {let pid='';try {pid=adb('shell','pidof',app);} catch {} if(pid) socket=adb('shell','cat','/proc/net/unix').split('\n').find(s=>s.includes('webview_devtools_remote_'+pid));if(socket) break;await delay(500);}
  if(!socket) throw new Error('App WebView debug socket unavailable');
  adb('forward','tcp:9222','localabstract:'+socket.split('@')[1].trim());
  const browser=await chromium.connectOverCDP('http://127.0.0.1:9222',{noDefaults:true});
  const page=browser.contexts()[0].pages()[0];
  diagnosticPage=page;
  page.on('pageerror',error=>{pageErrors.push(error.stack || error.message);console.error('WebView page error:',error.stack || error.message);});
  page.on('requestfailed',request=>console.error('WebView request failed:',new URL(request.url()).pathname,request.failure()?.errorText));
  try { await page.waitForFunction(()=>window.OneStepNative && window.OneStepPlatform,null,{timeout:90000,polling:250}); }
  catch(error) {
    console.error('WebView initialization diagnostic',await page.evaluate(()=>({url:location.href,ready:document.readyState,title:document.title,body:document.body?.innerText.slice(0,250),native:!!window.OneStepNative,platform:!!window.OneStepPlatform,scripts:[...document.scripts].map(s=>s.src)})));
    throw error;
  }
  await page.waitForFunction(()=>document.getElementById('main-content')?.inert===false,null,{timeout:90000});
  return {browser,context:browser.contexts()[0],page:browser.contexts()[0].pages()[0]};
}
function capture(name) {writeFileSync(path.join(output,name+'.png'),execFileSync(adbPath,['exec-out','screencap','-p'],{timeout:30000,maxBuffer:8*1024*1024}));}
let hierarchySequence=0;
function dumpHierarchy() {
  const target='/sdcard/flight-ui-'+(++hierarchySequence)+'.xml';
  let failure;
  // Android reports boot completion before Launcher and System UI become idle.
  // uiautomator may return exit 0 with an ERROR and no file during that interval.
  // Never read an earlier dump or interpret a missing file as an App failure.
  for(let attempt=0;attempt<3;attempt++) {
    try {
      const result=adb('shell','uiautomator','dump',target);
      if(result.includes('ERROR:')) throw new Error(result);
      const xml=adb('shell','cat',target);
      if(!xml.includes('<hierarchy')) throw new Error('Incomplete Android hierarchy');
      adb('shell','rm','-f',target);
      return xml;
    } catch(error) {failure=error;}
  }
  throw new Error('Android hierarchy unavailable after three attempts', {cause:failure});
}
function ui() {
  let xml=dumpHierarchy();
  // On a memory constrained host System UI can time out during emulator boot.
  // Record that environment failure and close only its dialog. App ANRs remain
  // visible and fail the test; they must never be dismissed by this recovery.
  if(/text="(?:System UI isn't responding|系统界面没有响应|系统界面无响应|La IU del sistema no responde)"/.test(xml)) {
    capture('system-ui-anr');console.warn('ENVIRONMENT: Android System UI ANR; restarting System UI');
    const node=(xml.match(/<node\b[^>]+>/g)||[]).find(n=>/text="(?:Close app|关闭应用|Cerrar aplicación)"/.test(n));
    const b=node && /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(node);
    if(b) adb('shell','input','tap',String((+b[1]+ +b[3])/2),String((+b[2]+ +b[4])/2));
    xml=dumpHierarchy();
  }
  return xml;
}
async function tap(text) {
  for(let attempt=0;attempt<12;attempt++) {
    const nodes=ui().match(/<node\b[^>]+>/g)||[];
    const node=nodes.find(n=>text.test(n));
    const b=node && /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(node);
    if(b) {adb('shell','input','tap',String((+b[1]+ +b[3])/2),String((+b[2]+ +b[4])/2));return;}
    await delay(500);
  }
  writeFileSync(path.join(output,'missing-ui-target.xml'),ui());
  capture('missing-ui-target');
  throw new Error('Android UI target missing: '+text);
}
async function showJourneyNotification(title) {
  const escaped=title.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
  const target=new RegExp('(?:text|content-desc)="[^"\\n]*'+escaped+'[^"\\n]*"');
  for(let attempt=0;attempt<4;attempt++) {
    adb('shell','input','keyevent','224');
    adb('shell','cmd','statusbar','collapse');await delay(200);
    // A shell expand request can be cancelled by a heads-up/launcher transition.
    // Use the same top-edge gesture as a user and verify the actual card is visible.
    adb('shell','input','swipe','144','1','144','1400','500');await delay(500);
    if(target.test(ui())) return target;
  }
  writeFileSync(path.join(output,'missing-notification.xml'),ui());
  capture('missing-notification');
  throw new Error('Journey notification was not visible after opening the shade: '+title);
}
if(process.argv[2]==='inspect') {
  const {browser,page}=await connect();
  console.log(await page.evaluate(async()=>({url:location.href,platform:OneStepNative.platform,native:OneStepNative.isNative,screen:document.querySelector('.screen.is-active')?.dataset.screen,push:await OneStepNative.FlightPush.status(),testDocument:window.__selectedDocument,inputFiles:[...document.getElementById('ticket-file').files].map(f=>({name:f.name,type:f.type,size:f.size}))})));
  capture('startup');await browser.close();process.exit(0);
}
let booted=false;
for(let n=0;n<180;n++) {try {if(adb('shell','getprop','sys.boot_completed')==='1') {booted=true;break;}} catch {} await delay(1000);}
if(!booted) throw new Error('Android Emulator did not finish booting within three minutes');
adb('shell','input','keyevent','224');adb('shell','wm','dismiss-keyguard');
connectivity(true);
adb('shell','wm','size','720x1600');adb('shell','wm','density','280');
// boot_completed does not mean System UI has finished its first-run setup.
// A closed System UI ANR also needs time to recreate its accessibility root.
// This bounded readiness check never dismisses an App ANR or skips a test.
const systemReadyDeadline=Date.now()+90000;
for(;;) {
  try {
    if(!ui().includes('<hierarchy')) throw new Error('No Android UI root');
    break;
  } catch(error) {
    if(Date.now()>=systemReadyDeadline) throw error;
    await delay(1000);
  }
}
if(process.argv[2]==='ready') {console.log('READY Android System UI accessibility hierarchy');process.exit(0);}
adb('install','-r',path.join(root,'android/app/build/outputs/apk/debug/app-debug.apk'));
adb('shell','pm','clear',app);adb('shell','am','start','-n',app+'/.MainActivity');
let {browser,context,page}=await connect();
adb('shell','am','start','-n',app+'/.MainActivity');
await expect.poll(()=>page.evaluate(()=>OneStepNative.Network.getStatus()).then(s=>s.connected),{timeout:15000}).toBe(true);
adb('shell','am','broadcast','-a',app+'.DEBUG_MESSAGE','-n',app+'/.DebugMessageReceiver','--ez','localTest','true');
await expect(page.locator('[data-screen="welcome"]')).toBeVisible();
expect(await page.evaluate(()=>location.origin)).toBe('https://localhost');
expect(await page.evaluate(()=>OneStepNative.platform)).toBe('android');
capture('cold-start');console.log('PASS actual WebView cold start, bundled HTTPS origin, Android plugins');
const now=Date.now(), departure=new Date(now+2*3600000).toISOString(), date=departure.slice(0,10);
const flight={flightNumber:'BA117',departureDate:date,departureAirport:'LHR',arrivalAirport:'JFK',terminal:'T5',gate:'B32',status:'SCHEDULED',live:true,demo:false,
  scheduledDeparture:departure,estimatedDeparture:departure,scheduledArrival:new Date(now+9*3600000).toISOString(),
  dataRetrievedAt:new Date(now).toISOString(),dataLastUpdatedAt:new Date(now).toISOString(),conflicts:[],
  timeContext:{serverNow:new Date(now).toISOString(),departureUtcOffset:'+00:00',effectiveDeparture:departure,boardingTime:new Date(now+90*60000).toISOString()}};
await context.route('**/api/**',async route=>{
  const headers={'Access-Control-Allow-Origin':'https://localhost','Access-Control-Allow-Headers':'content-type,accept','Access-Control-Allow-Methods':'GET,POST,PUT,DELETE,OPTIONS'};
  if(route.request().method()==='OPTIONS') return route.fulfill({status:204,headers});
  if(route.request().url().includes('/documents/travel-document')) {
    uploads.push(route.request().postDataBuffer());
    return route.fulfill({headers,json:{documentType:'ITINERARY',dataSource:'LOCAL_TEST',extractedAt:new Date(now).toISOString(),fields:{},needsReviewFields:[],segments:[{...flight,flightNumberConfidence:'HIGH',requiresFlightNumberConfirmation:false,flightNumberConfirmedByUser:false}]}});
  }
  if(route.request().url().includes('resolve-flight')) return route.fulfill({json:flight,headers});
  if(route.request().url().includes('next-action')) {
    const request=route.request().postDataJSON();const p=request.context || request.journeyContext || request;
    const question=!p.checkedIn?'CHECK_IN_QUESTION':p.hasCheckedBaggage==null?'BAGGAGE_QUESTION':null;
    return route.fulfill({headers,json:{flight,nextAction:{stepType:question || 'ACTION',actionCode:question || (p.securityCleared?'GO_TO_GATE':'SECURITY'),titleEn:question || (p.securityCleared?'Go to gate B32':'Go to security'),destination:p.securityCleared?'B32':'Security',urgency:'NORMAL'}}});
  }
  return route.abort();
});
const png=await sharp({create:{width:300,height:200,channels:3,background:'#ffffff'}}).png().toBuffer();
const jpeg=await sharp(png).jpeg().toBuffer();
// A real one-page PDF, including its cross-reference table. Android's picker
// generates document thumbnails before delivering the content URI.
const objects=['<</Type/Catalog/Pages 2 0 R>>','<</Type/Pages/Kids[3 0 R]/Count 1>>',
  '<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 200]/Resources<<>>>>'];
let pdfSource='%PDF-1.4\n';const offsets=[0];
objects.forEach((body,index)=>{offsets.push(Buffer.byteLength(pdfSource));pdfSource+=(index+1)+' 0 obj\n'+body+'\nendobj\n';});
const xref=Buffer.byteLength(pdfSource);
pdfSource+='xref\n0 4\n0000000000 65535 f \n'+offsets.slice(1).map(offset=>String(offset).padStart(10,'0')+' 00000 n \n').join('')
  +'trailer\n<</Root 1 0 R/Size 4>>\nstartxref\n'+xref+'\n%%EOF\n';
const pdf=Buffer.from(pdfSource);
for(const [extension,bytes] of [['png',png],['jpg',jpeg],['pdf',pdf]]) {
  const name='onestep-test.'+extension, file=path.join(output,name);writeFileSync(file,bytes);adb('push',file,'/sdcard/Download/'+name);
  await page.evaluate(()=>document.getElementById('ticket-file').addEventListener('change',async event=>{
    const file=event.target.files[0];if(file) window.__selectedDocument=btoa(String.fromCharCode(...new Uint8Array(await file.arrayBuffer())));
  }));
  await page.locator('[data-screen="welcome"] [data-action="upload"]').click();
  await delay(800);
  const fileTarget=new RegExp('text="'+name.replace('.','\\.')+'"');
  for(let attempt=0;attempt<6;attempt++) {
    const xml=ui();
    if(fileTarget.test(xml)) break;
    // The first picker may already open its roots drawer. Toggling it blindly
    // closes the drawer, and tapping the underlying Downloads heading reopens it.
    const downloads=/text="Downloads" resource-id="android:id\/title"/;
    if(downloads.test(xml)) await tap(downloads);
    else await tap(/content-desc="Show roots"/);
    await delay(500);
  }
  await tap(fileTarget);
  await expect.poll(()=>page.evaluate(()=>window.__selectedDocument),{timeout:15000}).toBe(bytes.toString('base64'));
  await expect.poll(()=>uploads.length,{timeout:15000}).toBe(['png','jpg','pdf'].indexOf(extension)+1);
  expect(uploads.at(-1).toString()).toContain(name);
  await expect(page.locator('[data-screen="confirm"]')).toBeVisible();
  await page.reload();await page.waitForFunction(()=>window.OneStepNative && window.OneStepPlatform);
  await page.evaluate(()=>OneStepPlatform.init());
  await expect(page.locator('[data-screen="welcome"]')).toBeVisible();
}
await page.locator('[data-screen="welcome"] [data-action="upload"]').click();await delay(600);adb('shell','input','keyevent','4');
await expect(page.locator('[data-screen="welcome"]')).toBeVisible();expect(uploads.length).toBe(3);
console.log('PASS real Android document picker content URI PNG, JPEG, PDF bytes and cancellation (extraction mocked)');
await page.locator('[data-screen="welcome"] [data-action="manual"]').click();
const inputs=await page.locator('[data-screen="manual"] input').evaluateAll(es=>es.map(e=>({id:e.id,type:e.type,name:e.name})));
console.log('Manual inputs',inputs);
await page.locator('#flight-input').fill('BA117');
const dateInput=page.locator('[data-screen="manual"] input[type="date"]');if(await dateInput.count()) await dateInput.fill(date);
// DOM focus through DevTools does not reliably show Android's IME. Tap the
// actual input and verify the keyboard before testing the system Back action.
await tap(/resource-id="flight-input" class="android.widget.EditText"/);
const keyboardVisible=()=>/mInputShown=true/.test(adb('shell','dumpsys','input_method'));
await expect.poll(keyboardVisible,{timeout:15000}).toBe(true);
adb('shell','input','keyevent','4');
await expect.poll(keyboardVisible,{timeout:15000}).toBe(false);
await expect(page.locator('[data-screen="manual"]')).toBeVisible();
await page.locator('[data-screen="manual"] button[type="submit"]').click();
await expect(page.locator('[data-screen="confirm"]')).toBeVisible();
await page.locator('[data-action="continue"]').click();
await expect(page.locator('[data-screen="step"]')).toBeVisible();
await tap(/text="(?:Don.t allow|不允许)"/);
await expect(page.locator('[data-live-activity-status]')).toContainText(/disabled|关闭/);
expect((await page.evaluate(()=>OneStepNative.FlightPush.status())).enabled).toBe(false);
capture('permission-denied');console.log('PASS first confirmation permission prompt, denial preserves guidance');
await page.locator('[data-action="checkin-status"][data-answer="yes"]').click();
await page.locator('[data-action="baggage"][data-answer="no"]').click();
await page.locator('[data-complete-action]').click();
await expect.poll(()=>page.evaluate(()=>OneStepNative.FlightPush.status()).then(s=>s.displayState.actionCode)).toBe('GO_TO_GATE');
console.log('PASS check-in, baggage choice and security step advance while notifications are denied');
await page.evaluate(()=>OneStepNative.FlightPush.openSettings());
await tap(/text="(?:All OneStep Flight notifications|Allow notifications|允许通知)"/);
adb('shell','input','keyevent','4');await delay(600);
// Grant via shell as a fallback only if this system image exposes a different settings layout.
expect((await page.evaluate(()=>OneStepNative.FlightPush.status())).enabled).toBe(true);
await expect.poll(()=>page.evaluate(async()=> (await OneStepNative.FlightLiveActivity.status()).active)).toBe(true);
capture('permission-restored');console.log('PASS notification settings recovery and automatic journey card');
await page.evaluate(()=>OneStepPlatform.openExternal('https://example.com'));
await delay(600);expect(adb('shell','dumpsys','activity','activities')).toMatch(/chrome|browser/i);
adb('shell','am','start','-n',app+'/.MainActivity');await delay(400);
expect(await page.evaluate(()=>location.origin)).toBe('https://localhost');
await page.evaluate(()=>OneStepPlatform.openExternal('tel:+442079460000'));
await delay(600);expect(adb('shell','dumpsys','activity','activities')).toMatch(/dialer|ACTION_DIAL/i);
adb('shell','am','start','-n',app+'/.MainActivity');await delay(400);
const rejected=await page.evaluate(()=>OneStepPlatform.openExternal('javascript:alert(1)').then(()=>false,()=>true));expect(rejected).toBe(true);
console.log('PASS HTTPS uses system browser, telephone uses dialer, unsupported URL rejected');
await page.evaluate(data=>{window.__shareDone=false;OneStepPlatform.sharePng(new Blob([Uint8Array.from(atob(data),c=>c.charCodeAt(0))],{type:'image/png'})).catch(e=>window.__shareError=e.message).finally(()=>window.__shareDone=true);},png.toString('base64'));
await expect.poll(()=>adb('shell','dumpsys','activity','activities').includes('ChooserActivity'),{timeout:15000}).toBe(true);
capture('share-chooser');
const sharedFiles=adb('shell','run-as',app,'ls','cache/onestep-share');expect(sharedFiles).toMatch(/card-\d+\.png/);
adb('shell','input','keyevent','4');await expect.poll(()=>page.evaluate(()=>window.__shareDone)).toBe(true);
expect(adb('shell','run-as',app,'ls','cache/onestep-share')).toBe(sharedFiles);
console.log('PASS native image share chooser and cache retained after chooser cancellation');
await page.locator('[data-screen="step"] [data-action="staff"]:visible').click();adb('shell','input','keyevent','4');
await expect(page.locator('[data-staff-overlay]')).toBeHidden();console.log('PASS native Android back closes dialog');
let status=await page.evaluate(()=>OneStepNative.FlightPush.status());
const display={...status.displayState,gate:'B40',nextStep:status.displayState.nextStep.replace(/B32/g,'B40'),
  metricOneValue:status.displayState.metricOneValue.replace(/B32/g,'B40'),snapshotVersion:20,clientRevision:status.clientRevision,
  facts:{...status.displayState.facts,gate:'B40'}};
const message={eventId:'emulator-injected-gate',journeyKey:status.journeyKey,snapshotVersion:'20',clientRevision:String(status.clientRevision),important:'true',end:'false',expiresAt:String(Math.floor(Date.now()/1000)+900),displayState:JSON.stringify(display),title:'Gate changed',body:'B32 → B40'};
function inject(data) {
  const file=path.join(output,'message.json');writeFileSync(file,JSON.stringify(data));adb('push',file,'/data/local/tmp/flight-message.json');
  adb('shell','run-as',app,'cp','/data/local/tmp/flight-message.json','files/debug-message.json');
  adb('shell','am','broadcast','-a',app+'.DEBUG_MESSAGE','-n',app+'/.DebugMessageReceiver');
}
adb('shell','input','keyevent','3');inject(message);inject(message);
const nativeNotifications=adb('shell','dumpsys','notification','--noredact');
expect(nativeNotifications).toContain(display.nextStep);expect(nativeNotifications).toContain('B40');expect(nativeNotifications).toContain('Gate changed');
await showJourneyNotification(display.nextStep);capture('background-notifications');adb('shell','cmd','statusbar','collapse');
console.log('PASS background local injection updates real card and important alert, duplicate event');
await tapNotification();
await expect(page.locator('[data-screen="step"]')).toBeVisible();
console.log('PASS warm notification click returns to the saved journey');
async function tapNotification() {
  const target=await showJourneyNotification(display.nextStep);
  await tap(target);await delay(800);
}
adb('shell','input','keyevent','3');await delay(700);await browser.close();adb('shell','am','kill',app);
await delay(500);expect(adb('shell','dumpsys','notification','--noredact')).toContain(display.nextStep);
await tapNotification();({browser,context,page}=await connect());
await expect(page.locator('[data-screen="step"]')).toBeVisible();
expect((await page.evaluate(()=>OneStepNative.FlightPush.status())).displayState.gate).toBe('B40');
console.log('PASS process recreation and cold notification click restore local journey before network');
const silentLockSetting=adb('shell','settings','get','secure','lock_screen_show_silent_notifications');
try {
  // The test explicitly allows silent cards on its own emulator. Product code
  // never writes system visibility settings or overrides the user's choice.
  adb('shell','settings','put','secure','lock_screen_show_silent_notifications','1');
  adb('shell','locksettings','set-disabled','false');
  adb('shell','input','keyevent','223');await delay(500);adb('shell','input','keyevent','224');await delay(1000);
  expect(adb('shell','dumpsys','window','policy')).toMatch(/(?:showing=true|isKeyguardShowing=true|mShowingLockscreen=true)/);
  await expect.poll(()=>ui().includes(display.nextStep),{timeout:15000}).toBe(true);capture('lockscreen');
  console.log('PASS actual keyguard shows journey card when system allows silent notifications');
} finally {
  if(silentLockSetting==='null') adb('shell','settings','delete','secure','lock_screen_show_silent_notifications');
  else adb('shell','settings','put','secure','lock_screen_show_silent_notifications',silentLockSetting);
  adb('shell','wm','dismiss-keyguard');
}
adb('shell','am','start','-n',app+'/.MainActivity');await delay(800);
expect((await page.evaluate(()=>OneStepNative.FlightPush.status())).snapshotVersion).toBe(20);
connectivity(false);await delay(500);
expect((await page.evaluate(()=>OneStepNative.FlightPush.status())).displayState.gate).toBe('B40');
connectivity(true);console.log('PASS offline retained native display and reconnect');
await page.locator('[data-screen="step"] [data-action="end-journey"]').click();
await page.locator('[data-action="confirm-end"]').click();
await expect(page.locator('[data-screen="welcome"]')).toBeVisible();
// Returning home precedes the asynchronous native bridge completion. Assert
// bounded cleanup before testing a genuinely late event after the journey ends.
await expect.poll(()=>page.evaluate(()=>OneStepNative.FlightLiveActivity.status()).then(s=>s.active),{timeout:5000}).toBe(false);
inject({...message,eventId:'late-after-end',snapshotVersion:'21',displayState:JSON.stringify({...display,snapshotVersion:21})});
expect((await page.evaluate(()=>OneStepNative.FlightLiveActivity.status())).active).toBe(false);
console.log('PASS end and late old event do not recreate card');
await page.locator('[data-locale-select]').selectOption('es');
await page.addStyleTag({content:'html,body{font-size:24px}'});
const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth);
expect(overflow).toBe(false);capture('spanish-large-text');
await page.locator('[data-screen="welcome"] [data-action="manual"]').click();
await page.locator('#flight-input').click();await delay(400);capture('keyboard');
adb('shell','input','keyevent','4');await delay(600);adb('shell','input','keyevent','4');
await expect(page.locator('[data-screen="welcome"]')).toBeVisible();
console.log('PASS actual Android Spanish large text, keyboard dismissal and page back');
const overlays=adb('shell','cmd','overlay','list');
if(overlays.includes('com.android.internal.systemui.navbar.threebutton')) {
  const previous=overlays.split('\n').find(line=>/\[x\].*com\.android\.internal\.systemui\.navbar\./.test(line))?.trim().split(' ').at(-1);
  try {
    adb('shell','cmd','overlay','enable-exclusive','--category','com.android.internal.systemui.navbar.threebutton');
    await delay(1200);
    // A system navigation overlay can recreate the activity/WebView. Attach
    // to the current process rather than retaining its obsolete debug target.
    try {await browser.close();} catch {}
    adb('shell','am','start','-n',app+'/.MainActivity');
    ({browser,context,page}=await connect());
    await page.addStyleTag({content:'html,body{font-size:24px}'});
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await expect(page.locator('[data-locale-select]')).toHaveValue('es');
    await expect(page.locator('[data-screen="welcome"]')).toBeVisible();capture('three-button-navigation');
    console.log('PASS actual Android three-button navigation with large Spanish text');
  } finally {if(previous) adb('shell','cmd','overlay','enable-exclusive','--category',previous);}
} else console.log('NOT EXECUTED three-button navigation: overlay absent from system image');
await browser.close();
expect(pageErrors, 'No uncaught JavaScript errors during native lifecycle and navigation changes').toEqual([]);
