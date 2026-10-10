import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { appConfig } from './config.mjs';
export function validateSigningProfile(profile, { teamId, bundleId, now = Date.now() }) {
  const prefix = profile.ApplicationIdentifierPrefix?.[0];
  if (!prefix || profile.TeamIdentifier?.[0] !== teamId
      || profile.Entitlements?.['application-identifier'] !== `${prefix}.${bundleId}`) {
    throw new Error('Provisioning profile does not match the configured Team ID and bundle ID');
  }
  const expiration = Date.parse(profile.ExpirationDate);
  if (!Number.isFinite(expiration) || expiration <= now || profile.ProvisionedDevices
      || profile.ProvisionsAllDevices || profile.Entitlements?.['get-task-allow']) {
    throw new Error('Provisioning profile must be a valid, unexpired App Store distribution profile');
  }
}

export function validateSigning(env = process.env) {
  if (!/^[A-Z0-9]{10}$/.test(env.APPLE_TEAM_ID || '')) throw new Error('Set APPLE_TEAM_ID in the secure ios_signing environment group');
  for (const [key, bundle] of [['APP_PROFILE_PATH', appConfig.appId], ['WIDGET_PROFILE_PATH', appConfig.appId + appConfig.widgetSuffix]]) {
    const file = env[key];
    if (!file || !existsSync(file)) throw new Error(`Set ${key} to the installed App Store provisioning profile for ${bundle}`);
    const decoded = spawnSync('security', ['cms', '-D', '-i', file], { encoding: 'utf8' });
    if (decoded.status !== 0) throw new Error(`Cannot decode ${key}`);
    // Profiles contain plist dates and binary certificates, which plutil cannot serialize to JSON.
    // Emit only validation fields; never log the profile or its certificates.
    const converted = spawnSync('python3', ['-c', `
import datetime, json, plistlib, sys
profile = plistlib.loads(sys.stdin.buffer.read())
if isinstance(profile.get('ExpirationDate'), datetime.datetime):
    profile['ExpirationDate'] = profile['ExpirationDate'].replace(tzinfo=datetime.timezone.utc).isoformat()
fields = ['ApplicationIdentifierPrefix', 'TeamIdentifier', 'Entitlements', 'ExpirationDate', 'ProvisionedDevices', 'ProvisionsAllDevices']
print(json.dumps({key: profile[key] for key in fields if key in profile}, default=str))
`], { input: decoded.stdout, encoding: 'utf8' });
    if (converted.status !== 0) throw new Error(`Cannot parse ${key}`);
    const profile = JSON.parse(converted.stdout);
    try {
      validateSigningProfile(profile, { teamId: env.APPLE_TEAM_ID, bundleId: bundle });
    } catch (error) {
      throw new Error(`${key}: ${error.message}`);
    }
  }
}
