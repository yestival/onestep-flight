import { spawnSync, spawn } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync, copyFileSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';
import { runSync } from './process.mjs';
const root=fileURLToPath(new URL('../',import.meta.url));
const android=path.join(root,'android');
const win=process.platform==='win32';
const toolRoot=path.join(root,'build/toolchain');
const env={...process.env};
env.JAVA_HOME=env.ANDROID_JAVA_HOME || (existsSync(path.join(toolRoot,'jdk/bin/java.exe')) ? path.join(toolRoot,'jdk') : env.JAVA_HOME);
env.ANDROID_HOME=env.ANDROID_HOME || env.ANDROID_SDK_ROOT || (existsSync(path.join(toolRoot,'sdk')) ? path.join(toolRoot,'sdk') : '');
if(existsSync(path.join(toolRoot,'avd'))) {
  env.ANDROID_AVD_HOME ||= path.join(toolRoot,'avd');
  env.ANDROID_USER_HOME ||= path.join(toolRoot,'android-user');
  env.ANDROID_EMULATOR_HOME ||= path.join(toolRoot,'android-user');
}
env.GRADLE_USER_HOME ||= path.join(root,'build/gradle-cache');
function run(command,args,cwd=root) {
  const r=runSync(command,args,{cwd,env,stdio:'inherit'});
  if(r.error) throw r.error;
  if(r.status!==0) throw new Error(`${path.basename(command)} exited ${r.status}`);
}
function configureFirebase(required=false) {
  const target=path.join(android,'app/google-services.json');
  if(env.GOOGLE_SERVICES_JSON_PATH) copyFileSync(env.GOOGLE_SERVICES_JSON_PATH,target);
  if(env.GOOGLE_SERVICES_JSON_BASE64) writeFileSync(target,Buffer.from(env.GOOGLE_SERVICES_JSON_BASE64,'base64'));
  if(existsSync(target)) {
    const config=JSON.parse(readFileSync(target,'utf8'));
    if(!config.client?.some(c=>c.client_info?.android_client_info?.package_name==='com.selene.onestepflight')) throw new Error('Firebase configuration does not match com.selene.onestepflight');
  } else if(required) throw new Error('Push release requires GOOGLE_SERVICES_JSON_PATH or GOOGLE_SERVICES_JSON_BASE64');
}
async function assets() {
  const res=path.join(android,'app/src/main/res');
  const mark='<g fill="#2C648E" transform="translate(44 36) skewX(-18)"><rect x="0" y="15" width="6" height="12"/><rect x="9" y="7" width="6" height="20"/><rect x="18" y="0" width="6" height="27"/></g>';
  const svg=`<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108"><rect width="108" height="108" fill="#F3F4F7"/>${mark}</svg>`;
  for(const [density,size] of Object.entries({mdpi:48,hdpi:72,xhdpi:96,xxhdpi:144,xxxhdpi:192})) {
    const folder=path.join(res,`mipmap-${density}`);mkdirSync(folder,{recursive:true});
    for(const name of ['ic_launcher','ic_launcher_round']) await sharp(Buffer.from(svg)).resize(size,size).png().toFile(path.join(folder,name+'.png'));
  }
  const drawable=path.join(res,'drawable');
  const bars=[[0,15],[9,7],[18,0]].map(([x,y])=>[[x,y],[x+6,y],[x+6,27],[x,27]].map(([bx,by],i)=>(i?'L':'M')+(44+bx-Math.tan(Math.PI/10)*by).toFixed(3)+','+(36+by)).join('')+'Z').join('');
  const foreground=`<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108"><path android:fillColor="#2C648E" android:pathData="${bars}"/></vector>`;
  // Android selects the qualified template drawable before the base resource.
  for(const name of ['drawable','drawable-v24']) {
    const folder=path.join(res,name);mkdirSync(folder,{recursive:true});
    writeFileSync(path.join(folder,'ic_launcher_foreground.xml'),foreground);
  }
  writeFileSync(path.join(drawable,'flight_splash.xml'),'<layer-list xmlns:android="http://schemas.android.com/apk/res/android"><item android:drawable="@color/ic_launcher_background"/><item android:width="108dp" android:height="108dp" android:gravity="center" android:drawable="@drawable/ic_launcher_foreground"/></layer-list>');
  writeFileSync(path.join(res,'values/ic_launcher_background.xml'),'<resources><color name="ic_launcher_background">#F3F4F7</color></resources>');
}
const action=process.argv[2] || 'sync';
const cli=path.join(root,'node_modules/@capacitor/cli/bin/capacitor');
if(['sync','debug','release','install','check','native-test'].includes(action)) {
  run(process.execPath,['scripts/build.mjs']);
  if(!existsSync(android)) run(process.execPath,[cli,'add','android']);
  configureFirebase(env.REQUIRE_FCM==='true');
  run(process.execPath,[cli,'sync','android']);await assets();
}
const adb=path.join(env.ANDROID_HOME,'platform-tools',win ? 'adb.exe' : 'adb');
if(['debug','release','check','native-test'].includes(action)) {
  if(!env.JAVA_HOME || !env.ANDROID_HOME) throw new Error('Set ANDROID_JAVA_HOME (JDK 21+) and ANDROID_HOME. On Windows: powershell -File scripts/setup-android.ps1 -WithEmulator');
  const gradle=path.join(android,win?'gradlew.bat':'gradlew');
  if(action==='release') {
    for(const name of ['ANDROID_KEYSTORE_PATH','ANDROID_KEYSTORE_PASSWORD','ANDROID_KEY_ALIAS','ANDROID_KEY_PASSWORD']) if(!env[name]) throw new Error(`Missing release signing variable: ${name}`);
  }
  const tasks=action==='release' ? [':app:assembleRelease',':app:bundleRelease'] : action==='check' ? ['testDebugUnitTest','lintDebug',':app:assembleDebug'] : action==='native-test' ? [':app:connectedDebugAndroidTest'] : [':app:assembleDebug'];
  const flags=['--console=plain','--no-daemon'];
  if(action==='native-test') flags.push('-Dorg.gradle.jvmargs=-Xmx768m','--max-workers=2');
  run(win ? gradle : 'sh',win ? [...tasks,...flags] : [gradle,...tasks,...flags],android);
} else if(action==='emulator') {
  env.ANDROID_PREFS_ROOT ||= path.join(toolRoot,'android-user');
  env.ANDROID_SDK_HOME ||= toolRoot;
  const pinnedEmulator=path.join(toolRoot,'emulator-14763179/emulator',win?'emulator.exe':'emulator');
  const emulator=env.ANDROID_EMULATOR_PATH || (existsSync(pinnedEmulator) ? pinnedEmulator : path.join(env.ANDROID_HOME,'emulator',win?'emulator.exe':'emulator'));
  const pixel9=existsSync(path.join(env.ANDROID_AVD_HOME || '', 'OneStep_Pixel9_API36_ZH.avd/config.ini'));
  const args=['-avd',env.ANDROID_AVD_NAME || (pixel9 ? 'OneStep_Pixel9_API36_ZH' : 'OneStep_API_36'),'-no-boot-anim','-gpu','swiftshader','-feature','-Vulkan','-memory',env.ANDROID_EMULATOR_MEMORY || '2048','-cores','2','-no-snapshot'];
  if(env.ANDROID_HEADLESS==='true') args.push('-no-window');
  if(win) {
    mkdirSync(path.join(root,'build'),{recursive:true});
    const quote=value=>"'"+value.replaceAll("'","''")+"'";
    const windowStyle=env.ANDROID_HEADLESS==='true' ? 'Hidden' : 'Normal';
    run('powershell.exe',['-NoProfile','-Command',`Start-Process -FilePath ${quote(emulator)} -ArgumentList ${args.map(quote).join(',')} -WindowStyle ${windowStyle} -RedirectStandardOutput ${quote(path.join(root,'build/emulator.log'))} -RedirectStandardError ${quote(path.join(root,'build/emulator-errors.log'))}`]);
  } else { const child=spawn(emulator,args,{env,stdio:'ignore',detached:true});child.unref(); }
  console.log('Android Emulator starting; use npm run android:install after boot.');
} else if(action==='install') {
  let booted=false;
  for(let n=0;n<120;n++) {
    const state=spawnSync(adb,['shell','getprop','sys.boot_completed'],{env,encoding:'utf8',timeout:5000,windowsHide:true});
    if(state.status===0 && state.stdout.trim()==='1') {booted=true;break;}
    await new Promise(resolve=>setTimeout(resolve,1000));
  }
  if(!booted) throw new Error('Android Emulator did not finish booting; run npm run android:emulator first');
  run(adb,['install','-r',path.join(android,'app/build/outputs/apk/debug/app-debug.apk')]);
  run(adb,['shell','am','start','-n','com.selene.onestepflight/.MainActivity']);
} else if(action!=='sync') throw new Error(`Unknown Android command: ${action}`);
