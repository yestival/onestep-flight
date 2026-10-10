import { readFileSync } from 'node:fs';
export const mobileRoot = new URL('../', import.meta.url);
export const appConfig = JSON.parse(readFileSync(new URL('app.config.json', mobileRoot), 'utf8'));
export function publicBase(value, name) {
  if (!value || /(?:example\.|your[-_.]|placeholder|change[-_]?me|<|>)/i.test(value)) throw new Error(`${name} must be a real public HTTPS origin`);
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || url.pathname !== '/'
      || /^(localhost|127(?:\.\d+){3}|0\.0\.0\.0|\[::1\])$/i.test(url.hostname)
      || /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(url.hostname)
      || /\.(localhost|local|test|invalid|example)$/i.test(url.hostname) || !url.hostname.includes('.')) throw new Error(`${name} must be a public HTTPS origin without a path`);
  return url.origin;
}
export function buildConfig(env = process.env) {
  return Object.fromEntries(['API_BASE_URL', 'PUBLIC_WEB_BASE_URL'].map(key => [key, publicBase(env[key] ?? appConfig[key], key)]));
}
