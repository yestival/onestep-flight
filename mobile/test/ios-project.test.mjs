import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import xcode from 'xcode';
import { appConfig, mobileRoot } from '../scripts/config.mjs';
test('Capacitor detects SPM before sync on a fresh checkout', () => {
  for (const file of ['Package.swift', 'Sources/CapApp-SPM/CapApp-SPM.swift']) {
    assert.ok(existsSync(new URL('ios/App/CapApp-SPM/' + file, mobileRoot)), `Missing SPM source: ${file}`);
  }
  // Exercise the installed CLI's package-manager selection, not our own heuristic.
  const result = spawnSync(process.execPath, ['--input-type=commonjs', '-e',
    "require('@capacitor/cli/dist/config.js').loadConfig().then(async config => console.log(await config.ios.packageManager))"],
  { cwd: fileURLToPath(mobileRoot), encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr || result.error?.message);
  assert.equal(result.stdout.trim(), 'SPM');
});
test('Xcode graph includes and embeds the Widget with shared attributes, private resources and matching versions', () => {
  const project = xcode.project(fileURLToPath(new URL('ios/App/App.xcodeproj/project.pbxproj', mobileRoot)));
  project.parseSync();
  const o = project.hash.project.objects;
  const targets = Object.entries(o.PBXNativeTarget).filter(([, item]) => item.isa === 'PBXNativeTarget');
  assert.equal(targets.length, 2);
  const [appId, app] = targets.find(([, value]) => value.name.replaceAll('"', '') === 'App');
  const [widgetId, widget] = targets.find(([, value]) => value.name.replaceAll('"', '') === 'FlightActivity');
  assert.ok(app.dependencies.some(item => o.PBXTargetDependency[item.value].target === widgetId));
  const files = (target, phase) => target.buildPhases.flatMap(item => o[phase]?.[item.value]?.files || [])
    .map(item => {
      const ref = o.PBXBuildFile[item.value].fileRef;
      return o.PBXFileReference[ref] || { path: o.PBXVariantGroup[ref].name };
    });
  assert.ok(files(app, 'PBXCopyFilesBuildPhase').some(file => file.path.replaceAll('"', '') === 'FlightActivity.appex'));
  const versions = [];
  for (const [id, target, bundle] of [[appId, app, appConfig.appId], [widgetId, widget, appConfig.appId + appConfig.widgetSuffix]]) {
    const sources = files(target, 'PBXSourcesBuildPhase').map(file => file.path.replaceAll('"', ''));
    assert.ok(sources.includes('Shared/FlightActivityAttributes.swift'));
    for (const source of sources.filter(p => p.includes('/'))) assert.ok(existsSync(new URL('ios/App/' + source, mobileRoot)), source);
    assert.ok(files(target, 'PBXResourcesBuildPhase').some(file => file.path.includes('PrivacyInfo.xcprivacy')));
    for (const cfg of o.XCConfigurationList[target.buildConfigurationList].buildConfigurations) {
      const settings = o.XCBuildConfiguration[cfg.value].buildSettings;
      assert.equal(String(settings.TARGETED_DEVICE_FAMILY).replaceAll('"', ''), '1');
      assert.equal(settings.SUPPORTS_MACCATALYST, 'NO');
      assert.equal(settings.PRODUCT_BUNDLE_IDENTIFIER.replaceAll('"', ''), bundle);
      versions.push(String(settings.CURRENT_PROJECT_VERSION) + '/' + settings.MARKETING_VERSION);
    }
  }
  assert.equal(new Set(versions).size, 1);
});
