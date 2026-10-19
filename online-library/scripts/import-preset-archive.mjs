// Uses the authenticated admin workflow; resumes by raw SHA and never replaces existing presets.
import { readFile, writeFile, rename } from 'node:fs/promises';
import { resolve, dirname, sep } from 'node:path';
import { createHash } from 'node:crypto';
import { validatePresetFile, validatePresetInput, buildCatalog } from '../server/validation.mjs';
import { PUBLIC_ORIGIN } from './cloud-tools.mjs';

const args = process.argv.slice(2), publish = args.includes('--publish'), verify = args.includes('--verify');
const input = args.find(arg => !arg.startsWith('--'));
if (!input || (publish && verify)) throw Error('Usage: node scripts/import-preset-archive.mjs <manifest.json> [--publish|--verify|--preflight]');
const manifestPath = resolve(input), manifestDir = dirname(manifestPath);
const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
if (manifest.version !== 1 || !Array.isArray(manifest.items) || manifest.items.length < 1 || manifest.items.length > 256) throw Error('Invalid archive manifest.');
const seen = new Set(), files = [], counts = {};
for (const entry of manifest.items) {
  const path = resolve(manifestDir, entry.filePath);
  if (!path.startsWith(manifestDir + sep)) throw Error('Preset file must stay within the manifest directory.');
  const data = await readFile(path), details = validatePresetFile(data);
  if (seen.has(details.sha256)) throw Error('Duplicate file SHA in archive manifest.');
  seen.add(details.sha256);
  if (details.sha256 !== entry.sha256 || details.byteLength !== entry.byteLength || details.fileFormat !== entry.fileFormat
      || !entry.originalName.toLowerCase().endsWith('.' + details.extension)) throw Error('Archive manifest does not match original file bytes.');
  validatePresetInput({ ...entry, compatibleModels: [], sortOrder: 0 });
  counts[details.fileFormat] = (counts[details.fileFormat] || 0) + 1;
  files.push({ entry, data, details });
}
console.log(JSON.stringify({ phase: 'local-validated', files: files.length, formats: counts, archiveSha256: manifest.archiveSha256 }));
if (!publish && !verify && !args.includes('--preflight')) process.exit(0);
const base = PUBLIC_ORIGIN + '/preset-library';
const session = JSON.parse(await readFile(new URL('../.local/community-admin-session.json', import.meta.url), 'utf8'));
const statePath = resolve(manifestDir, 'publication.json');
let state;
try { state = JSON.parse(await readFile(statePath, 'utf8')); }
catch (error) { if (error.code !== 'ENOENT') throw error; state = { version: 1, archiveSha256: manifest.archiveSha256, items: {} }; }
if (state.archiveSha256 !== manifest.archiveSha256) throw Error('Publication state belongs to another archive.');
async function save() {
  await writeFile(statePath + '.tmp', JSON.stringify(state, null, 2), { mode: 0o600 });
  await rename(statePath + '.tmp', statePath);
}

async function request(path, method = 'GET', body) {
  const raw = Buffer.isBuffer(body);
  const response = await fetch(base + path, { method, signal: AbortSignal.timeout(45000),
    headers: { Cookie: session.cookie, Origin: PUBLIC_ORIGIN, 'X-CSRF-Token': session.csrfToken,
      ...(body === undefined ? {} : { 'Content-Type': raw ? 'application/octet-stream' : 'application/json' }) },
    body: body === undefined ? undefined : raw ? body : JSON.stringify(body) });
  const result = await response.json();
  if (!response.ok) throw Error('Preset API ' + response.status + ' ' + (result.error?.code || 'REQUEST_FAILED'));
  return result;
}
let listing = await request('/api/presets');
if (!Array.isArray(listing.items)) throw Error('Preset listing was incomplete.');
const bySha = new Map(listing.items.map(row => [row.file?.sha256, row]));
const additional = files.filter(file => !bySha.has(file.details.sha256)).length;
const publishedCount = listing.items.filter(row => row.status === 'published').length;
if (publishedCount + files.filter(file => bySha.get(file.details.sha256)?.status !== 'published').length > 256) throw Error('Publishing would exceed the 256-preset catalog capacity.');
const candidateRows = listing.items.filter(row => row.status === 'published' && !seen.has(row.file?.sha256))
  .map(row => ({ id: row.id, status: 'published', published: row }));
for (const { entry, details } of files) candidateRows.push({ id: details.sha256, status: 'published', published: {
  ...entry, compatibleModels: [], sortOrder: 0, preview: null,
  file: { ...details, url: base + '/files/ncp-' + details.sha256 + '.' + details.extension }
} });
buildCatalog(candidateRows);
console.log(JSON.stringify({ phase: 'preflight', existing: listing.items.length, published: publishedCount, additional }));
if (!publish && !verify) process.exit(0);
if (publish && !state.baselineItems) {
  state.baselineItems = listing.items.map(row => ({ id: row.id, status: row.status, revision: row.revision,
    publishedRevision: row.publishedRevision, sha256: row.file.sha256 }));
  state.baselinePublished = listing.items.filter(row => row.status === 'published').map(row => ({ id: row.id, sha256: row.file.sha256 }));
  await save();
}
const baselineIds = new Set((state.baselineItems || []).map(row => row.id));

for (let index = 0; index < files.length; index++) {
  const { entry, data, details } = files[index];
  let row = bySha.get(details.sha256);
  if (publish && !row) {
    const uploaded = await request('/api/uploads?kind=ncp&filename=' + encodeURIComponent(entry.originalName), 'POST', data);
    if (uploaded.asset?.sha256 !== details.sha256 || uploaded.asset?.fileFormat !== details.fileFormat) throw Error('Uploaded file format or checksum mismatch.');
    state.items[details.sha256] = { owned: true, stage: 'creating' }; await save();
    const created = await request('/api/presets', 'POST', { name: entry.name, category: entry.category,
      description: entry.description, compatibleModels: [], sortOrder: 1000 + index, fileAssetId: uploaded.asset.id, previewAssetId: null });
    row = created.preset;
    if (!row?.id || row.file?.sha256 !== details.sha256) throw Error('Saved preset did not match uploaded file.');
    bySha.set(details.sha256, row);
    state.items[details.sha256] = { owned: true, stage: 'draft', id: row.id, revision: row.revision }; await save();
  }
  if (!row) throw Error('Expected preset has not been uploaded: ' + entry.name);
  if (publish && row.status !== 'published') {
    const owned = state.items[details.sha256];
    const matchesExpected = row.name === entry.name && row.category === entry.category
      && row.description === entry.description && row.sortOrder === 1000 + index && row.compatibleModels?.length === 0 && row.preview == null;
    const recoveringCreate = owned?.stage === 'creating' && row.revision === 1;
    if (row.status !== 'draft' || baselineIds.has(row.id) || !owned?.owned || !matchesExpected
      || !(owned.id === row.id && owned.revision === row.revision || recoveringCreate)) throw Error('Existing preset state changed; review instead of republishing implicitly.');
    row = (await request('/api/presets/' + row.id + '/publish', 'POST', { expectedRevision: row.revision })).preset;
    bySha.set(details.sha256, row);
  }
  if (row.status !== 'published' || row.hasUnpublishedChanges || row.file?.sha256 !== details.sha256 || (row.fileFormat || row.file?.fileFormat || 'NCP') !== details.fileFormat) throw Error('Published preset metadata mismatch.');
  state.items[details.sha256] = { ...state.items[details.sha256], stage: 'published', id: row.id, name: row.name, fileFormat: details.fileFormat, url: row.file.url, status: row.status };
  if (publish) await save();
  if ((index + 1) % 20 === 0 || index + 1 === files.length) console.log(JSON.stringify({ phase: publish ? 'published' : 'matched', completed: index + 1, total: files.length }));
}
// Verify both public directory contracts and every immutable source file.
const catalogResponse = await fetch(base + '/catalog.json', { signal: AbortSignal.timeout(45000) });
const catalog = await catalogResponse.json();
if (!catalogResponse.ok || !Array.isArray(catalog.presets)) throw Error('Public catalog unavailable.');
const publicById = new Map(catalog.presets.map(row => [row.id, row]));
const finalListing = await request('/api/presets');
for (const original of state.baselineItems || []) {
  const current = finalListing.items?.find(row => row.id === original.id);
  if (!current || current.status !== original.status || current.revision !== original.revision
      || current.publishedRevision !== original.publishedRevision || current.file.sha256 !== original.sha256) throw Error('An existing preset changed during the import.');
}
for (const original of state.baselinePublished || []) {
  if (publicById.get(original.id)?.sha256 !== original.sha256) throw Error('An existing published preset changed during the import.');
}
for (let index = 0; index < files.length; index++) {
  const { details } = files[index], item = state.items[details.sha256], remote = publicById.get(item.id);
  if (!remote || remote.sha256 !== details.sha256 || remote.fileFormat !== details.fileFormat || !remote.fileUrl.endsWith('.' + details.extension)) throw Error('Public catalog format mismatch.');
  const response = await fetch(remote.fileUrl, { signal: AbortSignal.timeout(45000) });
  const data = Buffer.from(await response.arrayBuffer());
  if (!response.ok || data.length !== details.byteLength || createHash('sha256').update(data).digest('hex') !== details.sha256)
    throw Error(`Public download bytes mismatch at ${index + 1}: ${item.name}; HTTP ${response.status}; expected ${details.byteLength} bytes, received ${data.length}.`);
  if ((index + 1) % 40 === 0 || index + 1 === files.length) console.log(JSON.stringify({ phase: 'download-verified', completed: index + 1, total: files.length }));
}
const feedById = new Map();
for (let page = 1; page <= 100; page++) {
  const feedResponse = await fetch(base + '/community/feed?page=' + page + '&pageSize=50', { signal: AbortSignal.timeout(45000) });
  const feed = await feedResponse.json();
  if (!feedResponse.ok || !Array.isArray(feed.items) || !feed.items.every(item => ['NCP', 'NP3'].includes(item.fileFormat))) throw Error('Community feed fileFormat contract missing.');
  for (const item of feed.items) feedById.set(item.id, item);
  if (feed.items.length < 50) break;
}
for (const { details } of files) {
  const remote = feedById.get(state.items[details.sha256].id);
  if (remote?.sha256 !== details.sha256 || remote?.fileFormat !== details.fileFormat) throw Error('Imported preset missing or mismatched in paginated community feed.');
}
state.verifiedAt = new Date().toISOString(); state.publicBytesVerified = true; state.publicCatalogCount = catalog.presets.length;
await save();
console.log(JSON.stringify({ phase: 'complete', published: files.length, formats: counts, publicCatalogCount: catalog.presets.length, publicBytesVerified: true }));
