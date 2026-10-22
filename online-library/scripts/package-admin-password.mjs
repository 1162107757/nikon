// Rebuild from the inspected production baseline, replacing only the reviewed feature/policy files.
import { readFile, writeFile, mkdir, cp, copyFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
const root = new URL('../', import.meta.url), build = new URL('build/', root);
const minimumEight = process.argv.includes('--minimum-eight');
const variant = minimumEight ? 'admin-password-8' : 'admin-password';
const baseline = new URL(variant + '-before/', build), stage = new URL(variant + '-release/', build);
const audit = JSON.parse(await readFile(new URL(variant + '-deploy-audit.json', build), 'utf8'));
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
if (digest(await readFile(new URL(variant + '-before.zip', build))) !== audit.previousArchiveSha256) throw new Error('Baseline archive changed.');
await mkdir(stage, { recursive: true });
await cp(baseline, stage, { recursive: true });
const paths = minimumEight ? ['server/auth.mjs', 'server/app.mjs', 'admin/app.js']
  : ['admin/api.js', 'admin/app.js', 'admin/index.html', 'admin/styles.css', 'server/app.mjs', 'server/cloudbase.mjs'];
for (const path of paths) await copyFile(new URL(path, root), new URL(path, stage));
await mkdir(new URL('scripts/', stage), { recursive: true });
await copyFile(new URL('scripts/package.mjs', root), new URL('scripts/package.mjs', stage));
await cp(new URL('test/', root), new URL('test/', stage), { recursive: true });
for (const args of [
  ['--experimental-vm-modules', '--test', 'test/admin-password.test.mjs', 'test/admin-api.test.mjs', 'test/auth.test.mjs', 'test/http.test.mjs', 'test/password-auth.test.mjs'],
  ['scripts/package.mjs']
]) {
  const result = spawnSync(process.execPath, args, { cwd: fileURLToPath(stage), encoding: 'utf8' });
  if (result.status !== 0) { process.stderr.write(result.stderr || result.stdout || 'Scoped package validation failed'); process.exit(1); }
}
const manifest = JSON.parse(await readFile(new URL('build/manifest.json', stage), 'utf8'));
const changes = [];
for (const file of manifest.files) {
  const oldHash = digest(await readFile(new URL(file.path, baseline)));
  if (oldHash !== file.sha256) changes.push({ path: file.path, before: oldHash, after: file.sha256 });
}
if (manifest.files.length !== audit.previousFiles || changes.length !== paths.length || changes.some(row => !paths.includes(row.path))) {
  throw new Error('Scoped package has an unexpected difference.');
}
await copyFile(new URL('build/nikon-preset-library.zip', stage), new URL('nikon-preset-library.zip', build));
await copyFile(new URL('build/manifest.json', stage), new URL('manifest.json', build));
const report = { ...audit, inspectedAt: new Date().toISOString(), source: 'production-baseline-plus-password-only',
  nextArchiveSha256: manifest.sha256, nextFiles: manifest.files.length, changes, scoped: true,
  excludedWorkspaceChanges: audit.changes.filter(row => !paths.includes(row.path)).map(row => row.path),
  isolatedRuntimeTestsPassed: true };
await writeFile(new URL(variant + '-deploy-audit.json', build), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
