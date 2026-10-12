import { chmodSync, copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { runSync } from './process.mjs';

export const sourceRepository = 'yestival/onestep-flight';
export const downloadsRepository = 'yestival/onestep-flight-android-downloads';

export function verifiedCandidate(sha, mainSha, run) {
  if (!/^[0-9a-f]{40}$/.test(sha)) throw new Error('Invalid Android source commit');
  if (sha !== mainSha) return false;
  if (run.head_sha !== sha || run.head_branch !== 'main' || run.head_repository?.full_name !== sourceRepository ||
      !['push', 'workflow_dispatch'].includes(run.event) || run.status !== 'completed' || run.conclusion !== 'success' ||
      run.path !== '.github/workflows/android.yml') {
    throw new Error('Android downloads require successful main-branch frontend, backend and native Android regression');
  }
  return true;
}

export async function github(endpoint, options = {}, request = fetch) {
  const response = await request(`https://api.github.com/${endpoint}`, {
    ...options,
    headers: { Accept: 'application/vnd.github+json', Authorization: `Bearer ${process.env.GH_TOKEN}`, ...options.headers },
    signal: AbortSignal.timeout(30000), redirect: 'error'
  });
  if (!response.ok) throw new Error(`GitHub request failed (HTTP ${response.status})`);
  return response.status === 204 ? null : response.json();
}

async function main() {
  const sha = process.env.ANDROID_SOURCE_SHA;
  const runId = process.env.ANDROID_REGRESSION_RUN_ID;
  if (!process.env.GH_TOKEN || !process.env.ANDROID_DOWNLOADS_DEPLOY_KEY || !/^\d+$/.test(runId || '')) {
    throw new Error('Android distribution requires GH_TOKEN, ANDROID_DOWNLOADS_DEPLOY_KEY and a regression run ID');
  }
  const [branch, run, host] = await Promise.all([
    github(`repos/${sourceRepository}/branches/main`),
    github(`repos/${sourceRepository}/actions/runs/${runId}`),
    github('meta')
  ]);
  if (!verifiedCandidate(sha, branch.commit.sha, run)) {
    console.log('A newer main commit exists; this obsolete Android publication was skipped.');
    return;
  }
  const directory = fileURLToPath(new URL('../build/download-request/', import.meta.url));
  mkdirSync(directory, { recursive: true });
  const key = path.join(directory, 'deploy-key');
  const hosts = path.join(directory, 'known-hosts');
  writeFileSync(key, process.env.ANDROID_DOWNLOADS_DEPLOY_KEY.trim() + '\n', { mode: 0o600 });
  chmodSync(key, 0o600);
  writeFileSync(hosts, host.ssh_keys.map(value => `github.com ${value}\n`).join(''));
  const env = { ...process.env, GIT_SSH_COMMAND: `ssh -i "${key}" -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile="${hosts}"` };
  delete env.ANDROID_DOWNLOADS_DEPLOY_KEY;
  const checkout = path.join(directory, 'repository');
  function git(args, cwd = directory) {
    const result = runSync('git', args, { cwd, env, stdio: 'inherit' });
    if (result.status !== 0) throw new Error('Cannot update the Android downloads repository');
  }
  try {
    git(['clone', '--depth=1', `git@github.com:${downloadsRepository}.git`, checkout]);
    const file = path.join(checkout, 'build-request.json');
    if (existsSync(file) && JSON.parse(readFileSync(file, 'utf8')).sha === sha) {
      console.log('The current main commit already has an Android publication request.');
      return;
    }
    writeFileSync(file, JSON.stringify({ repository: sourceRepository, sha, regressionRunId: Number(runId) }, null, 2) + '\n');
    mkdirSync(path.join(checkout, '.github/workflows'), { recursive: true });
    copyFileSync(new URL('../ci/android-download-publish.yml', import.meta.url), path.join(checkout, '.github/workflows/publish.yml'));
    git(['config', 'user.name', 'OneStep Flight builds'], checkout);
    git(['config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com'], checkout);
    git(['add', 'build-request.json', '.github/workflows/publish.yml'], checkout);
    git(['commit', '-m', `build(android): publish verified source ${sha.slice(0, 12)}`], checkout);
    git(['push', 'origin', 'HEAD:main'], checkout);
    console.log(`Android release requested for ${sha}.`);
  } finally {
    rmSync(key, { force: true });
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
