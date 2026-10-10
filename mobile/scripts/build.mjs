import { copyFile, mkdir, readFile, writeFile, rm, lstat } from 'node:fs/promises';
import path from 'node:path';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { appConfig, mobileRoot, buildConfig } from './config.mjs';
const source = new URL('../../src/main/resources/static/', import.meta.url);
const output = new URL('www/', mobileRoot);
const outputPath = path.resolve(fileURLToPath(output));
if (outputPath !== path.join(path.resolve(fileURLToPath(mobileRoot)), 'www')) throw new Error('Unexpected output directory');
if ((await lstat(outputPath).catch(() => null))?.isSymbolicLink()) throw new Error('Build output must not be a symlink');
await rm(outputPath, { recursive: true, force: true });
await mkdir(output, { recursive: true });
// Explicit allowlist: no server config, raw documents, fixtures, logs or source maps.
for (const file of ['index.html', 'styles.css', 'locale.js', 'platform.js', 'live-activity.js', 'app.js']) {
  await copyFile(new URL(file, source), new URL(file, output));
}
let html = await readFile(new URL('index.html', output), 'utf8');
html = html.replace('<script src="platform.js', '<script src="mobile-config.js"></script>\n  <script src="native.js"></script>\n  <script src="platform.js');
await writeFile(new URL('index.html', output), html);
await writeFile(new URL('mobile-config.js', output), `window.OneStepMobileConfig = Object.freeze(${JSON.stringify(buildConfig())});\n`);
await build({ entryPoints: [fileURLToPath(new URL('src/native-entry.js', mobileRoot))],
  outfile: fileURLToPath(new URL('native.js', output)), bundle: true, format: 'iife', platform: 'browser', target: 'safari16.2', minify: true });
const capPath = new URL('capacitor.config.json', mobileRoot);
const cap = JSON.parse(await readFile(capPath, 'utf8'));
cap.appId = appConfig.appId;
cap.appName = appConfig.appName;
if (cap.server.url || cap.server.allowNavigation) throw new Error('Packaged startup must not allow remote navigation');
await writeFile(capPath, JSON.stringify(cap, null, 2) + '\n');
console.log('Packaged frontend built in mobile/www (native compilation requires macOS/Xcode).');
