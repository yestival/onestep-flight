import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import xcode from 'xcode';
import { appConfig, mobileRoot } from './config.mjs';
import { iosVersion } from './version.mjs';

export function configureIOS(env = process.env) {
  const projectPath = fileURLToPath(new URL('ios/App/App.xcodeproj/project.pbxproj', mobileRoot));
  const project = xcode.project(projectPath);
  project.parseSync();
  const objects = project.hash.project.objects;
  const app = project.getFirstTarget();
  const targets = objects.PBXNativeTarget;
  let widgetId = Object.keys(targets).find(key => targets[key]?.name?.replaceAll('"', '') === 'FlightActivity');
  if (!widgetId) widgetId = project.addTarget('FlightActivity', 'app_extension', 'FlightActivity', appConfig.appId + appConfig.widgetSuffix).uuid;
  const widget = targets[widgetId];
  objects.PBXTargetDependency ||= {};
  objects.PBXContainerItemProxy ||= {};
  if (!targets[app.uuid].dependencies.some(ref => objects.PBXTargetDependency[ref.value]?.target === widgetId)) {
    project.addTargetDependency(app.uuid, [widgetId]);
  }
  for (const [kind, title] of [['PBXSourcesBuildPhase', 'Sources'], ['PBXResourcesBuildPhase', 'Resources'], ['PBXFrameworksBuildPhase', 'Frameworks']]) {
    if (!widget.buildPhases.some(ref => objects[kind]?.[ref.value])) project.addBuildPhase([], kind, title, widgetId);
  }
  let groupId = Object.keys(objects.PBXGroup).find(key => objects.PBXGroup[key]?.name === 'FlightNative');
  if (!groupId) {
    const group = project.addPbxGroup([], 'FlightNative');
    groupId = group.uuid;
    objects.PBXGroup[project.getFirstProject().firstProject.mainGroup].children.push({ value: groupId, comment: 'FlightNative' });
  }
  function member(path, targetId, phaseType) {
    let ref = Object.keys(objects.PBXFileReference).find(key => objects.PBXFileReference[key]?.path?.replaceAll('"', '') === path);
    if (!ref) ref = project.addFile(path, groupId).fileRef;
    if (path.endsWith('.xcprivacy')) objects.PBXFileReference[ref].lastKnownFileType = 'text.xml';
    const target = targets[targetId];
    const phase = target.buildPhases.map(item => objects[phaseType]?.[item.value]).find(Boolean);
    if (!phase) throw new Error(`Missing phase ${phaseType}`);
    if (!phase.files.some(item => objects.PBXBuildFile[item.value]?.fileRef === ref)) {
      const id = project.generateUuid();
      objects.PBXBuildFile[id] = { isa: 'PBXBuildFile', fileRef: ref, fileRef_comment: path };
      objects.PBXBuildFile[id + '_comment'] = path;
      phase.files.push({ value: id, comment: path });
    }
  }
  for (const name of ['FlightBridgeViewController', 'FlightNativePlugin', 'FlightLiveActivityPlugin']) member(`App/${name}.swift`, app.uuid, 'PBXSourcesBuildPhase');
  for (const id of [app.uuid, widgetId]) member('Shared/FlightActivityAttributes.swift', id, 'PBXSourcesBuildPhase');
  member('FlightActivity/FlightActivityWidget.swift', widgetId, 'PBXSourcesBuildPhase');
  member('App/PrivacyInfo.xcprivacy', app.uuid, 'PBXResourcesBuildPhase');
  member('FlightActivity/PrivacyInfo.xcprivacy', widgetId, 'PBXResourcesBuildPhase');
  const { version, buildNumber } = iosVersion(env);
  for (const [id, bundle] of [[app.uuid, appConfig.appId], [widgetId, appConfig.appId + appConfig.widgetSuffix]]) {
    for (const item of objects.XCConfigurationList[targets[id].buildConfigurationList].buildConfigurations) {
      const settings = objects.XCBuildConfiguration[item.value].buildSettings;
      Object.assign(settings, { IPHONEOS_DEPLOYMENT_TARGET: appConfig.minimumIOSVersion,
        TARGETED_DEVICE_FAMILY: '1', SUPPORTS_MACCATALYST: 'NO', SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD: 'NO',
        PRODUCT_BUNDLE_IDENTIFIER: bundle, MARKETING_VERSION: version, CURRENT_PROJECT_VERSION: buildNumber,
        SWIFT_VERSION: '5.0', CODE_SIGN_STYLE: 'Automatic' });
      if (env.APPLE_TEAM_ID) settings.DEVELOPMENT_TEAM = env.APPLE_TEAM_ID;
      if (id === widgetId) Object.assign(settings, { INFOPLIST_FILE: 'FlightActivity/Info.plist',
        APPLICATION_EXTENSION_API_ONLY: 'YES', GENERATE_INFOPLIST_FILE: 'NO', SKIP_INSTALL: 'YES' });
      else settings.OTHER_SWIFT_FLAGS = '"$(inherited)"';
    }
  }
  for (const value of Object.values(objects.XCBuildConfiguration)) {
    if (value?.buildSettings?.IPHONEOS_DEPLOYMENT_TARGET) value.buildSettings.IPHONEOS_DEPLOYMENT_TARGET = appConfig.minimumIOSVersion;
  }
  for (const phase of Object.values(objects.PBXCopyFilesBuildPhase || {})) {
    if (!phase?.files) continue;
    for (const ref of phase.files) {
      const build = objects.PBXBuildFile[ref.value];
      if (build?.fileRef === widget.productReference) build.settings = { ATTRIBUTES: ['RemoveHeadersOnCopy'] };
    }
  }
  // node-xcode's writer emits absent optional properties as the literal `undefined`.
  function clean(value) {
    if (!value || typeof value !== 'object') return;
    for (const key of Object.keys(value)) {
      if (value[key] === undefined || value[key] === 'undefined') delete value[key];
      else clean(value[key]);
    }
  }
  clean(objects);
  writeFileSync(projectPath, project.writeSync());
  // Pin the Filesystem plugin's otherwise open-ended native dependency as well.
  const spmPath = new URL('ios/App/CapApp-SPM/Package.swift', mobileRoot);
  let spm = readFileSync(spmPath, 'utf8');
  if (!spm.includes('ion-ios-filesystem.git')) spm = spm.replace('    dependencies: [',
    '    dependencies: [\n        .package(url: "https://github.com/ionic-team/ion-ios-filesystem.git", exact: "2.0.0"),');
  writeFileSync(spmPath, spm);
  const storyboardPath = new URL('ios/App/App/Base.lproj/Main.storyboard', mobileRoot);
  writeFileSync(storyboardPath, readFileSync(storyboardPath, 'utf8').replace('customClass="CAPBridgeViewController" customModule="Capacitor"', 'customClass="FlightBridgeViewController" customModule="App" customModuleProvider="target"'));
  const scenePath = new URL('ios/App/App/SceneDelegate.swift', mobileRoot);
  writeFileSync(scenePath, readFileSync(scenePath, 'utf8').replace('CAPBridgeViewController()', 'FlightBridgeViewController()'));
  const infoPath = new URL('ios/App/App/Info.plist', mobileRoot);
  let info = readFileSync(infoPath, 'utf8').replace(/\s*<key>UISupportedInterfaceOrientations~ipad<\/key>\s*<array>[\s\S]*?<\/array>/, '')
    .replace(/\s*<string>UIInterfaceOrientationLandscape(?:Left|Right)<\/string>/g, '')
    .replace('<string>armv7</string>', '<string>arm64</string>');
  if (!info.includes('<key>NSSupportsLiveActivities</key>')) info = info.replace(/<\/dict>\s*<\/plist>\s*$/, `
  <key>NSSupportsLiveActivities</key><true/>
  <key>CFBundleURLTypes</key><array><dict><key>CFBundleURLSchemes</key><array><string>onestepflight</string></array></dict></array>
  <key>CFBundleLocalizations</key><array><string>en</string><string>zh-Hans</string><string>es</string></array>
  <key>NSCameraUsageDescription</key><string>Photograph a travel document to prepare your flight guidance.</string>
  <key>ITSAppUsesNonExemptEncryption</key><false/>
</dict></plist>\n`);
  writeFileSync(infoPath, info);
  const schemes = new URL('ios/App/App.xcodeproj/xcshareddata/xcschemes/', mobileRoot);
  mkdirSync(schemes, { recursive: true });
  const reference = (id, name, product) => `<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="${id}" BuildableName="${product}" BlueprintName="${name}" ReferencedContainer="container:App.xcodeproj"/>`;
  writeFileSync(new URL('App.xcscheme', schemes), `<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2600" version="1.3">
<BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries>
<BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">${reference(app.uuid, 'App', 'App.app')}</BuildActionEntry>
<BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">${reference(widgetId, 'FlightActivity', 'FlightActivity.appex')}</BuildActionEntry>
</BuildActionEntries></BuildAction>
<TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB"/>
<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="NO"><BuildableProductRunnable runnableDebuggingMode="0">${reference(app.uuid, 'App', 'App.app')}</BuildableProductRunnable></LaunchAction>
<ProfileAction buildConfiguration="Release"><BuildableProductRunnable runnableDebuggingMode="0">${reference(app.uuid, 'App', 'App.app')}</BuildableProductRunnable></ProfileAction>
<AnalyzeAction buildConfiguration="Debug"/>
<ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>\n`);
  return { app: app.uuid, widget: widgetId };
}
