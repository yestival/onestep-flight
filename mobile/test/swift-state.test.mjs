import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
test('Swift Codable matches the exact shared Java/JS fixture and legacy Date epoch', {skip:process.platform!=='darwin'},()=>{
 const build=fileURLToPath(new URL('../build/',import.meta.url)); mkdirSync(build,{recursive:true});
 const binary=build+'flight-state-test';
 const compile=spawnSync('swiftc',['-D','FLIGHT_STATE_TEST',fileURLToPath(new URL('../ios/App/Shared/FlightActivityAttributes.swift',import.meta.url)),fileURLToPath(new URL('swift/main.swift',import.meta.url)),'-o',binary],{encoding:'utf8'});
 assert.equal(compile.status,0,compile.stderr);
 const run=spawnSync(binary,[fileURLToPath(new URL('../../src/test/fixtures/flight-display-state.json',import.meta.url))],{encoding:'utf8'});
 assert.equal(run.status,0,run.stderr);
});
