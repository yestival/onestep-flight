import { appConfig } from './config.mjs';

export function iosVersion(env = process.env) {
  const version = appConfig.version;
  if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error('Set version to three numeric components in app.config.json');
  let buildNumber = env.APP_BUILD_NUMBER;
  // Codemagic BUILD_NUMBER is per workflow; PROJECT_BUILD_NUMBER spans all workflows.
  if (!buildNumber && env.PROJECT_BUILD_NUMBER !== undefined) {
    if (!/^\d+$/.test(env.PROJECT_BUILD_NUMBER)) throw new Error('PROJECT_BUILD_NUMBER must be a nonnegative integer');
    buildNumber = String(Number(env.PROJECT_BUILD_NUMBER) + 1);
  }
  buildNumber ||= env.BUILD_NUMBER || '1';
  if (!/^[1-9]\d{0,3}(?:\.\d{1,2}){0,2}$/.test(buildNumber)) {
    throw new Error('Set APP_BUILD_NUMBER to a positive CFBundleVersion (up to 4.2.2 digits)');
  }
  return { version, buildNumber };
}

export function nextTestFlightBuildNumber(projectCounter, existingBuildNumbers) {
  if (!/^\d+$/.test(projectCounter ?? '') || !Number.isSafeInteger(Number(projectCounter))) {
    throw new Error('PROJECT_BUILD_NUMBER must be a nonnegative integer');
  }
  const counter = Number(projectCounter);
  const major = Math.max(counter + 1, ...existingBuildNumbers.map(value => {
    // Refuse unknown formats instead of guessing and risking a duplicate upload.
    iosVersion({ APP_BUILD_NUMBER: value });
    return Number(value.split('.')[0]) + 1;
  }));
  // The project counter disambiguates builds even when Apple's listing lags behind
  // an upload or two builds read the same latest number. Canceled builds leave gaps.
  const buildNumber = `${major}.${Math.floor(counter / 100)}.${counter % 100}`;
  return iosVersion({ APP_BUILD_NUMBER: buildNumber }).buildNumber;
}
