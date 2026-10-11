import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

test('native activity coordinator serializes creation and scopes old journey cleanup', { skip: process.platform !== 'darwin' }, () => {
  const build = fileURLToPath(new URL('../build/', import.meta.url));
  mkdirSync(build, { recursive: true });
  const binary = build + 'flight-activity-test';
  const files = ['../ios/App/Shared/FlightActivityAttributes.swift', '../ios/App/App/FlightLiveActivityPlugin.swift',
    'swift/ActivityKitDouble.swift', 'swift/ActivityLifecycleTests.swift'].map(path => fileURLToPath(new URL(path, import.meta.url)));
  const compile = spawnSync('swiftc', ['-parse-as-library', '-D', 'FLIGHT_STATE_TEST', '-D', 'FLIGHT_ACTIVITY_TEST', ...files, '-o', binary], { encoding: 'utf8' });
  assert.equal(compile.status, 0, compile.stderr);
  const run = spawnSync(binary, [], { encoding: 'utf8' });
  assert.equal(run.status, 0, run.stderr);
});
