import { readdir, readFile, mkdir, writeFile } from 'node:fs/promises';
import { deflateRawSync } from 'node:zlib';
import { createHash } from 'node:crypto';
const root = new URL('../', import.meta.url);
const output = new URL('build/', root);
await mkdir(output, { recursive: true });
const entries = [];
async function collect(folder) {
  for (const entry of await readdir(new URL(folder, root), { withFileTypes: true })) {
    const name = folder + entry.name;
    if (entry.isDirectory()) await collect(name + '/');
    else entries.push({ name, data: await readFile(new URL(name, root)), mode: 0o100644 });
  }
}
await collect('server/'); await collect('admin/'); await collect('download/');
entries.push({ name: 'package.json', data: await readFile(new URL('package.json', root)), mode: 0o100644 });
entries.push({ name: 'scf_bootstrap', data: Buffer.from((await readFile(new URL('scf_bootstrap', root), 'utf8')).replace(/\r\n/g, '\n')), mode: 0o100755 });
if (entries.some(entry => /(?:\.env|\.local|node_modules|test|scripts|database)/.test(entry.name))) throw new Error('Unexpected packaged path');
// Write a small ZIP with explicit Unix executable mode, including on Windows.
function crc32(data) {
  let crc = 0xffffffff;
  for (const value of data) { crc ^= value; for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0); }
  return (crc ^ 0xffffffff) >>> 0;
}
const parts = [], central = []; let offset = 0;
for (const entry of entries) {
  const name = Buffer.from(entry.name), compressed = deflateRawSync(entry.data), crc = crc32(entry.data);
  const header = Buffer.alloc(30); header.writeUInt32LE(0x04034b50); header.writeUInt16LE(20, 4); header.writeUInt16LE(0x800, 6);
  header.writeUInt16LE(8, 8); header.writeUInt32LE(crc, 14); header.writeUInt32LE(compressed.length, 18); header.writeUInt32LE(entry.data.length, 22); header.writeUInt16LE(name.length, 26);
  const directory = Buffer.alloc(46); directory.writeUInt32LE(0x02014b50); directory.writeUInt16LE(0x0314, 4); directory.writeUInt16LE(20, 6);
  directory.writeUInt16LE(0x800, 8); directory.writeUInt16LE(8, 10); directory.writeUInt32LE(crc, 16); directory.writeUInt32LE(compressed.length, 20);
  directory.writeUInt32LE(entry.data.length, 24); directory.writeUInt16LE(name.length, 28); directory.writeUInt32LE((entry.mode << 16) >>> 0, 38); directory.writeUInt32LE(offset, 42);
  parts.push(header, name, compressed); central.push(directory, name); offset += header.length + name.length + compressed.length;
}
const centralBytes = Buffer.concat(central), end = Buffer.alloc(22);
end.writeUInt32LE(0x06054b50); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10); end.writeUInt32LE(centralBytes.length, 12); end.writeUInt32LE(offset, 16);
const archive = Buffer.concat([...parts, centralBytes, end]);
await writeFile(new URL('nikon-preset-library.zip', output), archive);
const manifest = { environmentId: 'nikon-d8girdjsr252fd90e', createdAt: new Date().toISOString(), sha256: createHash('sha256').update(archive).digest('hex'),
  files: entries.map(entry => ({ path: entry.name, bytes: entry.data.length, mode: entry.mode.toString(8), sha256: createHash('sha256').update(entry.data).digest('hex') })) };
await writeFile(new URL('manifest.json', output), JSON.stringify(manifest, null, 2) + '\n');
console.log(`Packaged ${entries.length} files, ${archive.length} bytes, SHA-256 ${manifest.sha256}`);
