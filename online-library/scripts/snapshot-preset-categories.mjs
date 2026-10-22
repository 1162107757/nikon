// Read-only production snapshot. No credentials, user rows, or private identity data are exported.
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { executeSql, sqlRows, ENV_ID, PUBLIC_ORIGIN } from './cloud-tools.mjs';

const output = process.argv[2];
if (!output) throw Error('Usage: node scripts/snapshot-preset-categories.mjs <backup-directory>');
const directory = resolve(output);
await mkdir(directory, { recursive: true });
const rows = sqlRows(executeSql('SELECT id::text,revision,draft,published,published_revision,status,created_at::text,updated_at::text,published_at::text FROM nikon_presets ORDER BY id'));
for (const row of rows) for (const field of ['draft', 'published']) if (typeof row[field] === 'string') row[field] = JSON.parse(row[field]);
const versions = sqlRows(executeSql('SELECT preset_id::text,revision,snapshot,created_at::text FROM nikon_preset_versions ORDER BY preset_id,revision'));
for (const row of versions) if (typeof row.snapshot === 'string') row.snapshot = JSON.parse(row.snapshot);
const response = await fetch(PUBLIC_ORIGIN + '/preset-library/catalog.json', { signal: AbortSignal.timeout(45000) });
if (!response.ok) throw Error('Cannot read public catalog.');
const catalog = await response.json();
if (!Array.isArray(catalog.presets) || catalog.presets.length !== rows.filter(row => row.status === 'published').length) throw Error('Catalog and database counts differ.');
for (const row of rows.filter(row => row.status === 'published')) {
  const entry = catalog.presets.find(preset => preset.id === row.id);
  if (!entry || entry.sha256 !== row.published?.file?.sha256 || (entry.category || '') !== (row.published?.category || '')) throw Error('Catalog and database snapshots differ.');
}
const categories = Object.fromEntries([...new Set(rows.map(row => row.published?.category || ''))].sort().map(category => [category || '未分类', rows.filter(row => (row.published?.category || '') === category).length]));
const backup = { version: 1, environment: ENV_ID, capturedAt: new Date().toISOString(), rows, versions };
await writeFile(resolve(directory, 'before.json'), JSON.stringify(backup, null, 2), { flag: 'wx', mode: 0o600 });
await writeFile(resolve(directory, 'catalog-before.json'), JSON.stringify(catalog, null, 2), { flag: 'wx', mode: 0o600 });
console.log(JSON.stringify({ environment: ENV_ID, rows: rows.length, versions: versions.length, categories,
  unpublishedDrafts: rows.filter(row => JSON.stringify(row.draft) !== JSON.stringify(row.published)).length, directory }));
