import { appendFileSync, copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { runSync } from './process.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const directory = path.join(root, 'build/downloads');
const stateFile = path.join(directory, 'publication.json');
const marker = /<!-- onestep-android-build (\{[^\r\n]*\}) -->/;

export function releaseState(body = '') {
  const match = marker.exec(body);
  if (!match) return null;
  const state = JSON.parse(match[1]);
  if (!Number.isSafeInteger(state.versionCode) || state.versionCode < 1 || state.versionCode > 2100000000 ||
      !Number.isSafeInteger(state.runNumber) || state.runNumber < 1 ||
      !/^[0-9a-f]{64}$/i.test(state.certificateSha256 || '')) {
    throw new Error('The published Android build metadata is invalid');
  }
  return state;
}

export function nextVersion(runNumber, previous) {
  const run = Number(runNumber);
  if (!Number.isSafeInteger(run) || run < 1) throw new Error('GITHUB_RUN_NUMBER must be a positive integer');
  if (previous && run < previous.runNumber) throw new Error('An older workflow run cannot replace a newer Android download');
  const version = Math.max(run + 1, (previous?.versionCode || 1) + 1);
  if (version > 2100000000) throw new Error('Android versionCode is exhausted');
  return version;
}

export async function latestRelease(repository, token, request = fetch) {
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository) || repository.split('/').some(part => part === '.' || part === '..')) {
    throw new Error('Invalid Android downloads repository');
  }
  if (!token) throw new Error('Set the ANDROID_DOWNLOADS_TOKEN Actions secret in the source repository');
  const response = await request(`https://api.github.com/repos/${repository}/releases/latest`, {
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' },
    signal: AbortSignal.timeout(30000),
    redirect: 'error'
  });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`Cannot read Android releases (HTTP ${response.status}); check the repository and token permissions`);
  return response.json();
}

export function inspectApk(apk, state, tools, runner = runSync) {
  const options = { encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 };
  const signature = runner(tools.apksigner, ['verify', '--print-certs', apk], options);
  if (signature.status !== 0) throw new Error('Android download APK has no valid signing certificate');
  const certificate = /Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})/i.exec(signature.stdout || '')?.[1].toLowerCase();
  if (!certificate) throw new Error('Cannot read the APK signing certificate');
  if (state.previous?.certificateSha256 && certificate !== state.previous.certificateSha256.toLowerCase()) {
    throw new Error('The signing key changed; refusing to publish an APK that cannot update the existing app');
  }
  const metadata = runner(tools.aapt, ['dump', 'badging', apk], options);
  if (metadata.status !== 0) throw new Error('Cannot read APK metadata');
  const packageInfo = /package: name='([^']+)' versionCode='(\d+)'/.exec(metadata.stdout || '');
  if (!packageInfo || packageInfo[1] !== 'com.selene.onestepflight' || Number(packageInfo[2]) !== state.versionCode) {
    throw new Error('APK package or version does not match the Android publication');
  }
  if (/^application-debuggable/m.test(metadata.stdout)) throw new Error('Public automatic downloads require a release APK');
  return certificate;
}

function sdkTools() {
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || path.join(root, 'build/toolchain/sdk');
  const buildTools = path.join(sdk, 'build-tools');
  const windows = process.platform === 'win32';
  for (const version of readdirSync(buildTools).sort((a, b) => b.localeCompare(a, undefined, { numeric: true }))) {
    const folder = path.join(buildTools, version);
    const apksigner = path.join(folder, windows ? 'apksigner.bat' : 'apksigner');
    const aapt = path.join(folder, windows ? 'aapt2.exe' : 'aapt2');
    if (existsSync(apksigner) && existsSync(aapt)) return { apksigner, aapt };
  }
  throw new Error('Android build-tools with apksigner and aapt2 are required');
}

async function main(action) {
  mkdirSync(directory, { recursive: true });
  if (action === 'prepare') {
    for (const name of ['ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD', 'GITHUB_ENV']) {
      if (!process.env[name]) throw new Error(`Set the ${name} Actions secret or environment variable`);
    }
    const repository = process.env.ANDROID_DOWNLOADS_REPOSITORY || 'yestival/onestep-flight-android-downloads';
    if (repository.toLowerCase() === process.env.GITHUB_REPOSITORY?.toLowerCase()) {
      throw new Error('Use the separate public downloads repository, not the source repository');
    }
    const encodedKey = (process.env.ANDROID_KEYSTORE_BASE64 || '').replace(/\s/g, '');
    if (!encodedKey || !/^[A-Za-z0-9+/]+={0,2}$/.test(encodedKey) || encodedKey.length % 4 !== 0) {
      throw new Error('Set ANDROID_KEYSTORE_BASE64 to the base64-encoded existing release keystore');
    }
    const release = await latestRelease(repository, process.env.ANDROID_DOWNLOADS_TOKEN);
    const previous = releaseState(release?.body);
    const versionCode = nextVersion(process.env.GITHUB_RUN_NUMBER, previous);
    const signingPath = path.join(directory, 'signing.jks');
    writeFileSync(signingPath, Buffer.from(encodedKey, 'base64'), { mode: 0o600 });
    const attempt = Number(process.env.GITHUB_RUN_ATTEMPT || '1');
    if (!Number.isSafeInteger(attempt) || attempt < 1) throw new Error('Invalid GitHub workflow attempt');
    // A failed upload may leave an unpublished draft. A retry uses a new tag
    // without deleting that draft or touching the currently downloadable APK.
    const variables = { ANDROID_KEYSTORE_PATH: signingPath, ANDROID_VERSION_CODE: String(versionCode), ANDROID_DOWNLOAD_TAG: `android-${versionCode}-${attempt}` };
    for (const [name, value] of Object.entries(variables)) {
      if (/[\r\n]/.test(value)) throw new Error('Invalid CI environment value');
      appendFileSync(process.env.GITHUB_ENV, `${name}=${value}\n`);
    }
    writeFileSync(stateFile, JSON.stringify({ versionCode, runNumber: Number(process.env.GITHUB_RUN_NUMBER), previous }));
  } else if (action === 'package') {
    const state = JSON.parse(readFileSync(stateFile, 'utf8'));
    const apk = path.join(root, 'android/app/build/outputs/apk/release/app-release.apk');
    const certificateSha256 = inspectApk(apk, state, sdkTools());
    copyFileSync(apk, path.join(directory, 'OneStep-Flight-Android.apk'));
    const metadata = { versionCode: state.versionCode, runNumber: state.runNumber, certificateSha256 };
    const body = [
      'OneStep Flight is a multilingual flight journey assistant.',
      '',
      'Download **OneStep-Flight-Android.apk** under **Assets** below.',
      '',
      '1. Open the downloaded APK on your Android device.',
      '2. If prompted, allow your browser or file manager to install apps.',
      '3. Complete installation and open OneStep Flight.',
      '',
      'Requires Android 7.0 or later. Remote flight alerts depend on push configuration and system notification settings.',
      '',
      `Build: ${state.versionCode}`,
      '',
      `<!-- onestep-android-build ${JSON.stringify(metadata)} -->`,
      ''
    ].join('\n');
    writeFileSync(path.join(directory, 'release-body.txt'), body);
  } else throw new Error('Use prepare or package');
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main(process.argv[2]).catch(error => { console.error(error.message); process.exitCode = 1; });
}
