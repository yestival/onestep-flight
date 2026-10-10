import { writeFile, mkdir } from 'node:fs/promises';
import sharp from 'sharp';
import { mobileRoot } from './config.mjs';
// The three rising, skewed bars are the existing .brand-mark geometry and colors.
const mark = '<g transform="translate(380 370) scale(10) skewX(-18)"><rect x="0" y="15" width="6" height="12"/><rect x="9" y="7" width="6" height="20"/><rect x="18" y="0" width="6" height="27"/></g>';
const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024"><rect width="1024" height="1024" fill="#F3F4F7"/><g fill="#2C648E">${mark}</g></svg>`;
export async function makeAssets() {
  const icon = new URL('ios/App/App/Assets.xcassets/AppIcon.appiconset/', mobileRoot);
  await mkdir(icon, { recursive: true });
  await sharp(Buffer.from(svg)).png().toFile(new URL('AppIcon-512@2x.png', icon).pathname.replace(/^\/(?=[A-Za-z]:)/, ''));
  const splash = new URL('ios/App/App/Assets.xcassets/Splash.imageset/', mobileRoot);
  await mkdir(splash, { recursive: true });
  const launch = `<svg xmlns="http://www.w3.org/2000/svg" width="2732" height="2732"><rect width="2732" height="2732" fill="#F3F4F7"/><g transform="translate(854 854)" fill="#2C648E">${mark}</g></svg>`;
  await sharp(Buffer.from(launch)).png().toFile(new URL('splash-2732x2732.png', splash).pathname.replace(/^\/(?=[A-Za-z]:)/, ''));
  await writeFile(new URL('Contents.json', splash), JSON.stringify({ images: [{ idiom: 'universal', filename: 'splash-2732x2732.png' }], info: { version: 1, author: 'xcode' } }, null, 2) + '\n');
}
