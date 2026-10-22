// Public HTTP verification only: no admin session, cloud credentials, or writes.
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { PUBLIC_ORIGIN, ENV_ID } from './cloud-tools.mjs';
const directory = resolve(process.argv[2] || '.local/category-reclassification-20261020-resumed');
const plan = JSON.parse(await readFile(resolve(directory, 'plan.json'), 'utf8'));
if (plan.environment !== ENV_ID) throw Error('Unexpected verification target.');
async function get(path) {
  const response = await fetch(PUBLIC_ORIGIN + '/preset-library' + path, { redirect: 'error', signal: AbortSignal.timeout(45000) });
  if (!response.ok) throw Error('Public HTTP verification failed: ' + response.status);
  return response;
}
const catalog = await (await get('/catalog.json')).json();
if (catalog.version !== 1 || catalog.presets?.length !== plan.total) throw Error('Public catalog count changed.');
for (const item of plan.assignments) {
  const current = catalog.presets.find(row => row.id === item.id);
  if (!current || current.category !== item.category || current.sha256 !== item.sha256) throw Error('Public catalog category mismatch.');
}
const categories = {};
for (const category of Object.keys(plan.afterCounts)) {
  const expected = plan.assignments.filter(item => item.category === category);
  const found = new Map();
  let total;
  for (let page = 1; page <= 100; page++) {
    const query = new URLSearchParams({ category, page: String(page), pageSize: '50' });
    const result = await (await get('/community/feed?' + query)).json();
    if (!Array.isArray(result.items) || result.items.some(row => row.category !== category) || !result.categories?.includes(category)) throw Error('Community category filter failed.');
    total = result.total;
    for (const row of result.items) found.set(row.id, row);
    if (result.items.length < 50) break;
  }
  for (const item of expected) if (found.get(item.id)?.sha256 !== item.sha256) throw Error('Classified preset missing from community category query.');
  // One immutable public file per category provides a transport checksum spot check after metadata changes.
  const sample = catalog.presets.find(row => row.id === expected[0].id);
  const url = new URL(sample.fileUrl);
  if (url.origin !== PUBLIC_ORIGIN) throw Error('Unexpected file origin.');
  const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(45000) });
  const bytes = Buffer.from(await response.arrayBuffer());
  if (!response.ok || bytes.length !== sample.byteLength || createHash('sha256').update(bytes).digest('hex') !== sample.sha256) throw Error('Immutable public sample checksum changed.');
  categories[category] = { expected: expected.length, matched: expected.length, publicTotal: total, publicFileSampleVerified: true };
}
const report = { environment: ENV_ID, verifiedAt: new Date().toISOString(), anonymousOnly: true,
  catalogCount: catalog.presets.length, categories, allClassifiedPresetsInFilteredFeed: true, publicFileSamples: 6 };
await writeFile(resolve(directory, 'public-verification.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
console.log(JSON.stringify(report));
