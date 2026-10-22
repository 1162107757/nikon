// Read-only deployment audit. Save the existing cloud archive for recovery and compare every packaged file.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { inflateRawSync } from 'node:zlib';
import { createHash } from 'node:crypto';
import { cloudApi, ENV_ID, FUNCTION_NAME } from './cloud-tools.mjs';

const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const build = new URL('../build/', import.meta.url);
const minimumEight = process.argv.includes('--minimum-eight');
const variant = minimumEight ? 'admin-password-8' : 'admin-password';
const current = cloudApi('scf', 'GetFunction', { Namespace: ENV_ID, FunctionName: FUNCTION_NAME, ShowCode: 'FALSE' });
if (current.Status !== 'Active') throw new Error('Function must be Active before deployment inspection.');
const address = cloudApi('scf', 'GetFunctionAddress', { Namespace: ENV_ID, FunctionName: FUNCTION_NAME });
const url = new URL(address.Url);
if (url.protocol !== 'https:' || !/(?:\.myqcloud\.com|\.tencentcos\.cn)$/.test(url.hostname)) throw new Error('Unexpected archive origin.');
const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(45000) });
if (!response.ok) throw new Error('Could not retrieve the current cloud code archive.');
const previous = Buffer.from(await response.arrayBuffer());
if (previous.length > 16 * 1024 * 1024) throw new Error('Unexpected archive size.');
await mkdir(build, { recursive: true });
await writeFile(new URL(variant + '-before.zip', build), previous);
const next = await readFile(new URL('nikon-preset-library.zip', build));
function entries(zip) {
  let end = -1;
  for (let offset = zip.length - 22; offset >= Math.max(0, zip.length - 65557); offset--) {
    if (zip.readUInt32LE(offset) === 0x06054b50) { end = offset; break; }
  }
  if (end < 0) throw new Error('Invalid cloud ZIP.');
  const count = zip.readUInt16LE(end + 10), rows = new Map(); let offset = zip.readUInt32LE(end + 16);
  for (let index = 0; index < count; index++) {
    if (zip.readUInt32LE(offset) !== 0x02014b50) throw new Error('Invalid cloud ZIP directory.');
    const nameLength = zip.readUInt16LE(offset + 28), extraLength = zip.readUInt16LE(offset + 30), commentLength = zip.readUInt16LE(offset + 32);
    const name = zip.subarray(offset + 46, offset + 46 + nameLength).toString('utf8');
    const local = zip.readUInt32LE(offset + 42), method = zip.readUInt16LE(offset + 10), compressed = zip.readUInt32LE(offset + 20);
    const start = local + 30 + zip.readUInt16LE(local + 26) + zip.readUInt16LE(local + 28);
    const bytes = zip.subarray(start, start + compressed);
    if (!name.endsWith('/')) {
      if (![0, 8].includes(method) || rows.has(name)) throw new Error('Unsupported cloud ZIP entry.');
      rows.set(name, digest(method === 8 ? inflateRawSync(bytes, { maxOutputLength: 16 * 1024 * 1024 }) : bytes));
    }
    offset += 46 + nameLength + extraLength + commentLength;
  }
  return rows;
}
const before = entries(previous), after = entries(next), changes = [];
for (const name of new Set([...before.keys(), ...after.keys()])) {
  if (before.get(name) !== after.get(name)) changes.push({ path: name, before: before.get(name) || null, after: after.get(name) || null });
}
const expected = new Set(minimumEight ? ['server/auth.mjs', 'server/app.mjs', 'admin/app.js']
  : ['admin/api.js', 'admin/app.js', 'admin/index.html', 'admin/styles.css', 'server/app.mjs', 'server/cloudbase.mjs']);
const report = { environment: ENV_ID, functionName: FUNCTION_NAME, inspectedAt: new Date().toISOString(), status: current.Status,
  configurationSha256: digest(JSON.stringify(current.Environment?.Variables)), previousArchiveSha256: digest(previous),
  nextArchiveSha256: digest(next), previousFiles: before.size, nextFiles: after.size, changes,
  scoped: changes.length === expected.size && changes.every(row => expected.has(row.path) && row.before && row.after) };
await writeFile(new URL(variant + '-deploy-audit.json', build), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
if (!report.scoped) throw new Error('The package contains differences outside the password feature; reconcile before deployment.');
