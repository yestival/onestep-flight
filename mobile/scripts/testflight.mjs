import { appendFileSync, readFileSync } from 'node:fs';
import { createPrivateKey, sign } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { setTimeout as sleep } from 'node:timers/promises';
import { appConfig } from './config.mjs';
import { iosVersion, nextTestFlightBuildNumber } from './version.mjs';

const apiOrigin = 'https://api.appstoreconnect.apple.com';
const internalGroupName = 'OneStep Flight Internal';

function integrationValue(name, env, readKeyFile) {
  let value = env[name];
  for (let depth = 0; depth < 5 && value; depth++) {
    if (value.startsWith('@env:')) {
      value = env[value.slice(5)];
    } else if (value.startsWith('@file:')) {
      // Match Codemagic CLI EnvironmentArgumentValue, including paths such as
      // @file:$HOME/private_keys/AuthKey_....p8. Never print resolved values/paths.
      const path = value.slice(6).replace(/^~(?=[/\\])/, env.HOME || '')
        .replace(/\$(?:\{([^}]+)\}|([A-Za-z_][A-Za-z0-9_]*))/g, (match, braced, plain) => env[braced || plain] ?? match);
      try { value = readKeyFile(path, 'utf8').trim(); } catch { break; }
    } else return value;
  }
  throw new Error(`Cannot resolve ${name} from the existing Apple integration`);
}

// Codemagic injects these from the existing App Store Connect integration.
// Keys and JWTs stay in memory and never enter artifacts or command-line arguments.
export function createAppleClient(env = process.env, fetchImpl = fetch, wait = sleep, readKeyFile = readFileSync) {
  const values = {};
  for (const name of ['APP_STORE_CONNECT_ISSUER_ID', 'APP_STORE_CONNECT_KEY_IDENTIFIER', 'APP_STORE_CONNECT_PRIVATE_KEY']) {
    if (!env[name]) throw new Error(`Existing Apple integration must provide ${name}`);
    values[name] = integrationValue(name, env, readKeyFile);
  }
  let key;
  try {
    key = createPrivateKey(values.APP_STORE_CONNECT_PRIVATE_KEY);
    if (key.asymmetricKeyDetails?.namedCurve !== 'prime256v1') throw new Error();
  } catch {
    throw new Error('Existing Apple integration must provide a valid ES256 private key');
  }
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  return async (path, { method = 'GET', body } = {}) => {
    const url = new URL(path, apiOrigin);
    if (url.origin !== apiOrigin || !url.pathname.startsWith('/v1/')) throw new Error('Refusing a non-Apple API URL');
    for (let attempt = 0; attempt < 3; attempt++) {
      const now = Math.floor(Date.now() / 1000);
      const unsigned = `${encode({ alg: 'ES256', kid: values.APP_STORE_CONNECT_KEY_IDENTIFIER, typ: 'JWT' })}.${encode({
        iss: values.APP_STORE_CONNECT_ISSUER_ID, iat: now - 30, exp: now + 600, aud: 'appstoreconnect-v1'
      })}`;
      const jwt = `${unsigned}.${sign('sha256', Buffer.from(unsigned), { key, dsaEncoding: 'ieee-p1363' }).toString('base64url')}`;
      let response;
      try {
        response = await fetchImpl(url, {
          method, headers: { Authorization: `Bearer ${jwt}`, Accept: 'application/json', 'Content-Type': 'application/json' },
          ...(body ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(20000), redirect: 'error'
        });
      } catch {
        if (attempt === 2) throw new Error('Apple API network request failed; publishing stopped');
      }
      if (response?.ok) return response.status === 204 ? null : response.json();
      if (response && response.status !== 429 && response.status < 500) {
        throw new Error(`Apple API ${method} ${url.pathname} failed (HTTP ${response.status}); publishing stopped`);
      }
      if (attempt === 2) throw new Error(`Apple API unavailable (HTTP ${response?.status ?? 'network'}); publishing stopped`);
      await wait(1000 * (attempt + 1));
    }
  };
}

async function list(request, path) {
  const items = [];
  while (path) {
    const page = await request(path);
    if (!Array.isArray(page.data)) throw new Error('Invalid Apple API list response');
    items.push(...page.data);
    path = page.links?.next;
  }
  return items;
}

async function appRecord(request) {
  const query = new URLSearchParams({ 'filter[bundleId]': appConfig.appId, 'fields[apps]': 'bundleId', limit: '200' });
  const apps = (await list(request, `/v1/apps?${query}`)).filter(app => app.attributes?.bundleId === appConfig.appId);
  if (apps.length !== 1 || !/^\d+$/.test(apps[0].id)) throw new Error('Expected exactly one Apple app record matching app.config.json');
  return apps[0];
}

async function internalGroup(request, appId) {
  const query = new URLSearchParams({ 'fields[betaGroups]': 'name,isInternalGroup,hasAccessToAllBuilds', limit: '200' });
  const groups = (await list(request, `/v1/apps/${appId}/betaGroups?${query}`)).filter(group => group.attributes?.name === internalGroupName);
  if (groups.length !== 1 || groups[0].attributes.isInternalGroup !== true) {
    throw new Error(`Create an INTERNAL TestFlight group named "${internalGroupName}"; external groups cannot receive automatic builds`);
  }
  return groups[0];
}

export async function prepareTestFlight(request, env = process.env) {
  const app = await appRecord(request);
  await internalGroup(request, app.id);
  const query = new URLSearchParams({ 'filter[app]': app.id, 'fields[builds]': 'version', limit: '200' });
  // Include expired builds and every marketing version so numbers are never reused.
  const builds = await list(request, `/v1/builds?${query}`);
  return { appId: app.id, buildNumber: nextTestFlightBuildNumber(env.PROJECT_BUILD_NUMBER, builds.map(build => build.attributes.version)) };
}

export async function distributeInternal(request, env = process.env, { wait = sleep, attempts = 40, log = console.log } = {}) {
  const app = await appRecord(request);
  let group = await internalGroup(request, app.id);
  if (group.attributes.hasAccessToAllBuilds === true) {
    log('Apple automatic distribution is enabled for OneStep Flight Internal; new builds appear after processing.');
    return;
  }
  const { version, buildNumber } = iosVersion(env);
  const query = new URLSearchParams({
    'filter[app]': app.id, 'filter[version]': buildNumber, 'fields[builds]': 'version,processingState,expired,preReleaseVersion',
    include: 'preReleaseVersion', 'fields[preReleaseVersions]': 'version,platform', limit: '200'
  });
  for (let attempt = 0; attempt < attempts; attempt++) {
    const page = await request(`/v1/builds?${query}`);
    const builds = page.data.filter(build => build.attributes.version === buildNumber && page.included?.some(release =>
      release.id === build.relationships?.preReleaseVersion?.data?.id && release.attributes.version === version && release.attributes.platform === 'IOS'));
    if (builds.length > 1) throw new Error('Ambiguous uploaded build; internal distribution stopped');
    const build = builds[0];
    if (build?.attributes.expired || ['FAILED', 'INVALID'].includes(build?.attributes.processingState)) {
      throw new Error('Apple rejected or expired this build; internal distribution stopped');
    }
    if (build?.attributes.processingState === 'VALID') {
      // Check group type again immediately before the only distribution mutation.
      group = await internalGroup(request, app.id);
      await request(`/v1/betaGroups/${encodeURIComponent(group.id)}/relationships/builds`, {
        method: 'POST', body: { data: [{ type: 'builds', id: build.id }] }
      });
      log(`Build ${buildNumber} is available to OneStep Flight Internal.`);
      return;
    }
    if (attempt + 1 < attempts) await wait(15000);
  }
  throw new Error('Apple is still processing the uploaded build. Add it to OneStep Flight Internal in App Store Connect; do not re-upload it.');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const request = createAppleClient();
    if (process.argv[2] === 'prepare') {
      if (!process.env.CM_ENV) throw new Error('Codemagic CM_ENV is required');
      const { buildNumber } = await prepareTestFlight(request);
      appendFileSync(process.env.CM_ENV, `APP_BUILD_NUMBER=${buildNumber}\n`);
      console.log(`App and Widget will use version ${appConfig.version} (${buildNumber}).`);
    } else if (process.argv[2] === 'distribute') {
      await distributeInternal(request);
    } else throw new Error('Expected prepare or distribute');
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
