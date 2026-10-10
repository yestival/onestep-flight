import { readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
const roots = [new URL('../../src/test/js/', import.meta.url), new URL('../test/', import.meta.url)];
const files = roots.flatMap(root => readdirSync(root).filter(f => /\.test\.(js|mjs)$/.test(f)).map(f => fileURLToPath(new URL(f, root))));
const result = spawnSync(process.execPath, ['--test', ...files], { stdio: 'inherit' });
process.exit(result.status ?? 1);
