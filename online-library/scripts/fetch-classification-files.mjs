// Only download immutable, already-public preset files; bytes are never rewritten.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { validatePresetFile } from '../server/validation.mjs';
import { PUBLIC_ORIGIN } from './cloud-tools.mjs';
const directory = resolve(process.argv[2] || '.local/category-reclassification-20261020');
const catalog = JSON.parse(await readFile(resolve(directory, 'catalog-before.json'), 'utf8'));
await mkdir(resolve(directory, 'files'), { recursive: true });
let index = 0;
for (const entry of catalog.presets) {
  const url = new URL(entry.fileUrl);
  if (url.origin !== PUBLIC_ORIGIN || !url.pathname.startsWith('/preset-library/files/')) throw Error('Unexpected preset file origin.');
  const path = resolve(directory, 'files', entry.sha256 + '.' + (entry.fileFormat === 'NP3' ? 'np3' : 'ncp'));
  let bytes;
  try { bytes = await readFile(path); }
  catch (error) {
    if (error.code !== 'ENOENT') throw error;
    const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(45000) });
    if (!response.ok) throw Error('Public preset download failed.');
    bytes = Buffer.from(await response.arrayBuffer());
  }
  if (bytes.length !== entry.byteLength || createHash('sha256').update(bytes).digest('hex') !== entry.sha256) throw Error('Preset checksum mismatch.');
  validatePresetFile(bytes);
  await writeFile(path, bytes, { mode: 0o600 });
  if (++index % 40 === 0 || index === catalog.presets.length) console.log(JSON.stringify({ downloaded: index, total: catalog.presets.length, hashesVerified: true }));
}
