import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import xcode from 'xcode';
import { mobileRoot, appConfig } from './config.mjs';

// Instrument only the CI checkout. The shipped App and Widget sources remain unchanged.
const root = fileURLToPath(mobileRoot);
function run(command, args, options = {}) {
  const result = spawnSync(command, args, { cwd: root, encoding: 'utf8', ...options });
  if (result.error || result.status !== 0) throw new Error(result.error?.message || `${command}: ${result.stderr || result.stdout}`);
  return result.stdout;
}
if (process.platform !== 'darwin') throw new Error('Widget runtime verification requires macOS and an iOS Simulator.');
const projectPath = new URL('ios/App/App.xcodeproj/project.pbxproj', mobileRoot);
const scenePath = new URL('ios/App/App/SceneDelegate.swift', mobileRoot);
const originals = [projectPath, scenePath].map(path => [path, readFileSync(path, 'utf8')]);
let device;
mkdirSync(new URL('build/widget-smoke/', mobileRoot), { recursive: true });
try {
  const scene = readFileSync(scenePath, 'utf8').replace('FlightBridgeViewController()', 'FlightActivitySmokeController()');
  writeFileSync(scenePath, scene + '\n' + readFileSync(new URL('../test/ios/FlightActivitySmokeController.swift', import.meta.url), 'utf8'));
  const project = xcode.project(fileURLToPath(projectPath)); project.parseSync();
  const o = project.hash.project.objects;
  const app = project.getFirstTarget();
  const test = project.addTarget('FlightActivityUITests', 'unit_test_bundle', 'FlightActivityUITests', appConfig.appId + '.uitests');
  const target = o.PBXNativeTarget[test.uuid];
  target.productType = '"com.apple.product-type.bundle.ui-testing"';
  o.PBXFileReference[target.productReference].path = 'FlightActivityUITests.xctest';
  // addTarget incorrectly makes App depend on its tests. Tests depend on App instead.
  o.PBXNativeTarget[app.uuid].dependencies = o.PBXNativeTarget[app.uuid].dependencies.filter(ref => o.PBXTargetDependency[ref.value]?.target !== test.uuid);
  project.addTargetDependency(test.uuid, [app.uuid]);
  project.addBuildPhase(['../../test/ios/FlightActivityUITests.swift'], 'PBXSourcesBuildPhase', 'Sources', test.uuid);
  project.addBuildPhase([], 'PBXFrameworksBuildPhase', 'Frameworks', test.uuid);
  project.addBuildPhase([], 'PBXResourcesBuildPhase', 'Resources', test.uuid);
  for (const ref of o.XCConfigurationList[target.buildConfigurationList].buildConfigurations) {
    const settings = o.XCBuildConfiguration[ref.value].buildSettings;
    delete settings.INFOPLIST_FILE;
    Object.assign(settings, { GENERATE_INFOPLIST_FILE: 'YES', TEST_TARGET_NAME: 'App', SWIFT_VERSION: '5.0', IPHONEOS_DEPLOYMENT_TARGET: '16.2', TARGETED_DEVICE_FAMILY: '1', CODE_SIGNING_ALLOWED: 'NO' });
  }
  function clean(value) {
    if (!value || typeof value !== 'object') return;
    for (const key of Object.keys(value)) { if (value[key] === undefined || value[key] === 'undefined') delete value[key]; else clean(value[key]); }
  }
  clean(o); writeFileSync(projectPath, project.writeSync());
  const reference = (id, name, product) => `<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="${id}" BuildableName="${product}" BlueprintName="${name}" ReferencedContainer="container:App.xcodeproj"/>`;
  writeFileSync(new URL('ios/App/App.xcodeproj/xcshareddata/xcschemes/FlightActivitySmoke.xcscheme', mobileRoot), `<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2600" version="1.3"><BuildAction buildImplicitDependencies="YES"><BuildActionEntries>
<BuildActionEntry buildForTesting="YES" buildForRunning="NO" buildForProfiling="NO" buildForArchiving="NO" buildForAnalyzing="YES">${reference(test.uuid, 'FlightActivityUITests', 'FlightActivityUITests.xctest')}</BuildActionEntry>
</BuildActionEntries></BuildAction><TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB"><Testables><TestableReference skipped="NO">${reference(test.uuid, 'FlightActivityUITests', 'FlightActivityUITests.xctest')}</TestableReference></Testables></TestAction></Scheme>`);
  const available = JSON.parse(run('xcrun', ['simctl', 'list', 'devices', 'available', '-j']));
  const candidates = Object.entries(available.devices).filter(([runtime]) => runtime.includes('iOS')).flatMap(([runtime, devices]) => devices.filter(d => d.name.includes('iPhone')).map(d => ({ ...d, runtime })));
  const choice = candidates.find(d => d.name.includes('iPhone 17 Pro')) || candidates.find(d => d.name.includes('Pro')) || candidates[0];
  if (!choice) throw new Error('No available iPhone Simulator');
  device = choice.udid;
  console.log(`Testing actual App + Widget on ${choice.name}, ${choice.runtime}`);
  if (choice.state !== 'Booted') run('xcrun', ['simctl', 'boot', device]);
  run('xcrun', ['simctl', 'bootstatus', device, '-b']);
  run('xcodebuild', ['-project', 'ios/App/App.xcodeproj', '-scheme', 'FlightActivitySmoke', '-destination', `platform=iOS Simulator,id=${device}`, '-derivedDataPath', 'build/SmokeDerivedData', '-resultBundlePath', 'build/widget-smoke/Result.xcresult', '-parallel-testing-enabled', 'NO', 'CODE_SIGNING_ALLOWED=NO', 'test'], { stdio: 'inherit' });
} finally {
  if (device) {
    const logs = spawnSync('xcrun', ['simctl', 'spawn', device, 'log', 'show', '--last', '15m', '--style', 'compact', '--info', '--debug', '--predicate', 'process == "FlightActivity" OR process == "liveactivitiesd" OR process == "chronod" OR process == "App"'], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
    writeFileSync(new URL('build/widget-smoke/system.log', mobileRoot), logs.stdout || logs.stderr || 'No system logs');
    spawnSync('xcrun', ['simctl', 'io', device, 'screenshot', fileURLToPath(new URL('build/widget-smoke/screen.png', mobileRoot))]);
  }
  for (const [path, content] of originals) writeFileSync(path, content);
}
