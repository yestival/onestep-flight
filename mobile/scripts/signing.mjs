import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { appConfig } from './config.mjs';
export function validateSigning() {
  for (const [key, bundle] of [['APP_PROFILE_PATH', appConfig.appId], ['WIDGET_PROFILE_PATH', appConfig.appId + appConfig.widgetSuffix]]) {
    const file = process.env[key];
    if (!file || !existsSync(file)) throw new Error(`Set ${key} to the installed App Store provisioning profile for ${bundle}`);
    const decoded = spawnSync('security', ['cms', '-D', '-i', file], { encoding: 'utf8' });
    if (decoded.status !== 0) throw new Error(`Cannot decode ${key}`);
    const converted = spawnSync('plutil', ['-convert', 'json', '-o', '-', '-'], { input: decoded.stdout, encoding: 'utf8' });
    if (converted.status !== 0) throw new Error(`Cannot parse ${key}`);
    const profile = JSON.parse(converted.stdout);
    if (profile.Entitlements?.['application-identifier'] !== `${process.env.APPLE_TEAM_ID}.${bundle}`
        || profile.TeamIdentifier?.[0] !== process.env.APPLE_TEAM_ID) throw new Error(`${key} does not match the configured Team ID and bundle ID`);
    if (profile.ProvisionedDevices || profile.ProvisionsAllDevices || profile.Entitlements?.['get-task-allow']
        || Date.parse(profile.ExpirationDate) <= Date.now()) throw new Error(`${key} must be a valid, unexpired App Store profile`);
  }
}
