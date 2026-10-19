import { readdir, readFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
async function walk(path) {
  const result = [];
  for (const entry of await readdir(new URL(path, import.meta.url), { withFileTypes: true })) {
    const child = path + entry.name;
    if (entry.isDirectory()) result.push(...await walk(child + '/'));
    else if (/\.(m?js)$/.test(entry.name)) result.push(fileURLToPath(new URL(child, import.meta.url)));
  }
  return result;
}
let checked = 0;
for (const file of [...await walk('../admin/'), ...await walk('../download/'), ...await walk('../server/'), ...await walk('./')]) {
  const result = spawnSync(process.execPath, ['--check', file], { cwd: root, encoding: 'utf8' });
  if (result.status) { process.stderr.write(result.stderr || 'Syntax check failed'); process.exit(1); }
  const source = await readFile(file, 'utf8');
  if (/eyJ[A-Za-z0-9_-]{30,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}/.test(source)) throw new Error('Credential-like JWT in tracked source');
  checked++;
}
console.log(`Syntax/source checks passed: ${checked} JavaScript modules`);
