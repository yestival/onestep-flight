import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { appConfig, mobileRoot } from './config.mjs';
import { configureIOS } from './configure-ios.mjs';
import { makeAssets } from './assets.mjs';
import { validateSigning } from './signing.mjs';
const cwd = fileURLToPath(mobileRoot);
const action = process.argv[2] || 'sync';
function run(command, args) {
  const result = spawnSync(command, args, { cwd, stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} exited with ${result.status}`);
}
const cli = fileURLToPath(new URL('node_modules/@capacitor/cli/bin/capacitor', mobileRoot));
if (action === 'init' || action === 'sync') {
  if (!existsSync(new URL('www/index.html', mobileRoot))) run(process.execPath, ['scripts/build.mjs']);
  if (!existsSync(new URL('ios/App/App.xcodeproj/project.pbxproj', mobileRoot))) run(process.execPath, [cli, 'add', 'ios', '--packagemanager', 'SPM']);
  run(process.execPath, [cli, 'sync', 'ios']);
  configureIOS();
  await makeAssets();
  console.log('iPhone App + FlightActivity targets configured. Compilation requires macOS/Xcode.');
} else {
  if (process.platform !== 'darwin') throw new Error('Native compilation requires macOS with Xcode 26+. Use the Codemagic workflow from Windows.');
  const base = ['-project', 'ios/App/App.xcodeproj', '-scheme', 'App', '-derivedDataPath', 'build/DerivedData'];
  if (action === 'simulator') {
    run('xcodebuild', [...base, '-configuration', 'Debug', '-sdk', 'iphonesimulator', '-destination', 'generic/platform=iOS Simulator', 'CODE_SIGNING_ALLOWED=NO', 'build']);
  } else if (action === 'archive') {
    if (!/^[A-Z0-9]{10}$/.test(process.env.APPLE_TEAM_ID || '')) throw new Error('Set APPLE_TEAM_ID in the secure Codemagic ios_signing environment group.');
    if (!process.env.APP_BUILD_NUMBER && process.env.PROJECT_BUILD_NUMBER === undefined) {
      throw new Error('Set PROJECT_BUILD_NUMBER in Codemagic or a unique APP_BUILD_NUMBER for both targets.');
    }
    validateSigning();
    configureIOS();
    run('xcode-project', ['use-profiles', '--project', 'ios/App/App.xcodeproj']);
    run('xcodebuild', [...base, '-configuration', 'Release', '-destination', 'generic/platform=iOS', '-archivePath', 'build/OneStepFlight.xcarchive', 'archive']);
    // Codemagic use-profiles creates export_options.plist from both installed matching profiles.
    if (!process.env.HOME) throw new Error('Codemagic HOME unavailable');
    run('xcodebuild', ['-exportArchive', '-archivePath', 'build/OneStepFlight.xcarchive', '-exportPath', 'build/ipa', '-exportOptionsPlist', `${process.env.HOME}/export_options.plist`]);
  } else throw new Error(`Unknown iOS command: ${action}`);
}
