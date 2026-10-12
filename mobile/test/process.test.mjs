import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync,writeFileSync,rmSync,mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { runSync } from '../scripts/process.mjs';

test('native process exit status is preserved',()=>{
  assert.equal(runSync(process.execPath,['-e','process.exit(23)']).status,23);
});
test('Windows wrappers accept spaces and Chinese paths and preserve failure status',{skip:process.platform!=='win32'},()=>{
  const build=path.resolve(fileURLToPath(new URL('../build/',import.meta.url)));mkdirSync(build,{recursive:true});
  const directory=mkdtempSync(path.join(build,'构建 空格-'));
  try {
    const wrapper=path.join(directory,'wrapper test.cmd');
    writeFileSync(wrapper,'@echo off\r\necho %~1\r\nexit /b 17\r\n');
    const result=runSync(wrapper,['argument with spaces'],{encoding:'utf8'});
    assert.equal(result.status,17,result.stderr);assert.equal(result.stdout.trim(),'argument with spaces');
  } finally {
    if(!path.resolve(directory).startsWith(build+path.sep)) throw new Error('Unexpected test directory');
    rmSync(directory,{recursive:true,force:true});
  }
});
