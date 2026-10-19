import { readdir } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
const tests = (await readdir(new URL('../test/', import.meta.url))).filter(file => file.endsWith('.test.mjs')).map(file => `test/${file}`);
const result = spawnSync(process.execPath, ['--experimental-vm-modules', '--test', ...tests], { cwd: root, stdio: 'inherit' });
process.exitCode = result.status ?? 1;
