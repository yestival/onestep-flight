import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { runSync } from './process.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const source = path.join(root, 'android/offline-speech');
const output = path.join(root, 'build/offline-speech');
const lock = JSON.parse(readFileSync(path.join(source, 'speech.lock.json'), 'utf8'));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');

export function prepareOfflineSpeech(env = process.env) {
  const sdk = env.ANDROID_HOME || env.ANDROID_SDK_ROOT || path.join(root, 'build/toolchain/sdk');
  const windows = process.platform === 'win32';
  const cmake = path.join(sdk, 'cmake', lock.cmake, 'bin', windows ? 'cmake.exe' : 'cmake');
  const ninja = path.join(sdk, 'cmake', lock.cmake, 'bin', windows ? 'ninja.exe' : 'ninja');
  const ndk = path.join(sdk, 'ndk', lock.ndk);
  const cmakePath = value => path.resolve(value).replaceAll('\\', '/');
  function run(command, args) {
    const result = runSync(command, args, { env, stdio: 'inherit', windowsHide: true });
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(path.basename(command) + ' failed while preparing offline speech (' + result.status + ')');
  }
  if (!existsSync(cmake) || !existsSync(path.join(ndk, 'source.properties'))) {
    const manager = path.join(sdk, 'cmdline-tools/latest/bin', windows ? 'sdkmanager.bat' : 'sdkmanager');
    if (!existsSync(manager)) throw new Error('Install Android command-line tools to prepare bundled offline speech');
    run(manager, ['--sdk_root=' + sdk, 'ndk;' + lock.ndk, 'cmake;' + lock.cmake]);
  }
  const archive = path.join(source, 'espeak-ng-' + lock.version + '-source.tar.gz');
  const data = path.join(source, 'espeak-ng-data.zip');
  if (hash(readFileSync(archive)) !== lock.sourceSha256 || hash(readFileSync(data)) !== lock.dataSha256) {
    throw new Error('Bundled offline speech source or voice data checksum mismatch');
  }
  mkdirSync(output, { recursive: true });
  const vendor = path.join(output, 'espeak-ng-' + lock.version);
  const extractedMarker = path.join(output, 'verified-source');
  if (!existsSync(extractedMarker) || readFileSync(extractedMarker, 'utf8') !== lock.sourceSha256) {
    run(windows ? 'tar.exe' : 'tar', ['-xf', archive, '--exclude=espeak-ng-' + lock.version + '/src/include/espeak/speak_lib.h', '-C', output]);
    // The upstream compatibility header is a symlink. Materialize its exact
    // contents on Windows without requiring Developer Mode or admin privileges.
    mkdirSync(path.join(vendor, 'src/include/espeak'), { recursive: true });
    copyFileSync(path.join(vendor, 'src/include/espeak-ng/speak_lib.h'), path.join(vendor, 'src/include/espeak/speak_lib.h'));
    writeFileSync(extractedMarker, lock.sourceSha256);
  }
  const inputHash = hash(Buffer.concat([readFileSync(path.join(source, 'CMakeLists.txt')), Buffer.from(JSON.stringify(lock))]));
  for (const abi of lock.abis) {
    const build = path.join(output, 'native', abi);
    run(cmake, ['-S', source, '-B', build, '-G', 'Ninja', '-DCMAKE_MAKE_PROGRAM=' + cmakePath(ninja),
      '-DCMAKE_TOOLCHAIN_FILE=' + cmakePath(path.join(ndk, 'build/cmake/android.toolchain.cmake')), '-DANDROID_ABI=' + abi,
      '-DANDROID_PLATFORM=android-' + lock.androidApi, '-DCMAKE_BUILD_TYPE=Release', '-DESPEAK_SOURCE_DIR=' + cmakePath(vendor)]);
    run(cmake, ['--build', build, '--target', 'flight_espeak', '--parallel', '4']);
    const libraries = path.join(output, 'jniLibs', abi); mkdirSync(libraries, { recursive: true });
    copyFileSync(path.join(build, 'libflight_espeak.so'), path.join(libraries, 'libflight_espeak.so'));
  }
  const assets = path.join(output, 'assets/offline-speech'); mkdirSync(assets, { recursive: true });
  copyFileSync(data, path.join(assets, 'espeak-ng-data.zip'));
  // Corresponding component source, build recipe and notices travel in every
  // APK, so obtaining them does not require a website, account or network.
  for (const [from, name] of [[archive, 'espeak-ng-source.tar.gz'], [path.join(source, 'COPYING.GPL3'), 'COPYING.GPL3'],
    [path.join(source, 'CMakeLists.txt'), 'CMakeLists.txt'], [path.join(source, 'speech.lock.json'), 'speech.lock.json'],
    [fileURLToPath(import.meta.url), 'offline-speech.mjs'], [path.join(root, 'scripts/process.mjs'), 'process.mjs']]) copyFileSync(from, path.join(assets, name));
  writeFileSync(path.join(assets, 'version'), inputHash);
  console.log('Bundled offline speech prepared for ' + lock.abis.join(', ') + '; no first-launch voice download.');
}
if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) prepareOfflineSpeech();
