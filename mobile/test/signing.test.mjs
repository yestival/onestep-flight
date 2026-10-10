import test from 'node:test';
import assert from 'node:assert/strict';
import { iosVersion } from '../scripts/version.mjs';
import { validateSigning, validateSigningProfile } from '../scripts/signing.mjs';

test('both targets use the project-wide build counter with an explicit override', () => {
  assert.equal(iosVersion({ PROJECT_BUILD_NUMBER: '0', BUILD_NUMBER: '8' }).buildNumber, '1');
  assert.equal(iosVersion({ PROJECT_BUILD_NUMBER: '25', BUILD_NUMBER: '2' }).buildNumber, '26');
  assert.equal(iosVersion({ PROJECT_BUILD_NUMBER: '25', APP_BUILD_NUMBER: '4.12.3' }).buildNumber, '4.12.3');
  assert.equal(iosVersion({}).buildNumber, '1');
  for (const value of ['0', '-1', '10000', '1.123', '1.2.3.4', 'release']) {
    assert.throws(() => iosVersion({ APP_BUILD_NUMBER: value }), /CFBundleVersion/);
  }
  assert.throws(() => iosVersion({ PROJECT_BUILD_NUMBER: '-1' }), /nonnegative/);
});

const teamId = 'ABCDEFGHIJ';
const bundleId = 'com.selene.onestepflight';
const now = Date.parse('2026-10-10T00:00:00Z');
function profile(bundle = bundleId) {
  return {
    ApplicationIdentifierPrefix: ['LEGACYPREFIX'], TeamIdentifier: [teamId],
    Entitlements: { 'application-identifier': `LEGACYPREFIX.${bundle}`, 'get-task-allow': false },
    ExpirationDate: '2027-10-10T00:00:00Z'
  };
}

test('App and Widget require separate matching App Store profiles, allowing legacy app prefixes', () => {
  for (const bundle of [bundleId, `${bundleId}.FlightActivity`]) {
    assert.doesNotThrow(() => validateSigningProfile(profile(bundle), { teamId, bundleId: bundle, now }));
  }
  assert.throws(() => validateSigningProfile(profile(), { teamId: 'OTHERTEAM1', bundleId, now }), /match/);
  assert.throws(() => validateSigningProfile(profile(), { teamId, bundleId: `${bundleId}.FlightActivity`, now }), /match/);
  assert.throws(() => validateSigningProfile(profile('*'), { teamId, bundleId, now }), /match/);
});

test('signing rejects missing inputs, development, device, enterprise and expired profiles', () => {
  assert.throws(() => validateSigning({}), /APPLE_TEAM_ID/);
  assert.throws(() => validateSigning({ APPLE_TEAM_ID: teamId }), /APP_PROFILE_PATH/);
  for (const override of [
    { ExpirationDate: '2025-10-10T00:00:00Z' }, { ExpirationDate: 'invalid' }, { ExpirationDate: undefined },
    { ProvisionedDevices: [] }, { ProvisionsAllDevices: true },
    { Entitlements: { ...profile().Entitlements, 'get-task-allow': true } }
  ]) {
    assert.throws(() => validateSigningProfile({ ...profile(), ...override }, { teamId, bundleId, now }), /distribution profile/);
  }
});
