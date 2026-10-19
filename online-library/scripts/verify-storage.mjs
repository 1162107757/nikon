import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { CloudBaseStore, CloudBaseStorage } from '../server/cloudbase.mjs';
import { validateNcp, validatePreview } from '../server/validation.mjs';
import { ENV_ID } from './cloud-tools.mjs';
const root = new URL('../', import.meta.url);
const credential = JSON.parse(await readFile(new URL('.local/service-credential.json', root), 'utf8'));
if (credential.environmentId !== ENV_ID) throw new Error('Unexpected environment');
const store = new CloudBaseStore({ environmentId: ENV_ID, serviceKey: credential.serviceKey, fetchImpl: async (url, options) => {
  const response = await fetch(url, options);
  if (!response.ok) {
    let detail; try { detail = await response.clone().json(); } catch { detail = {}; }
    const safe = { method: options.method || 'GET', path: new URL(url).pathname, status: response.status,
      code: detail.code || detail.error?.code || detail.Error?.Code, message: detail.message || detail.error?.message || detail.Error?.Message };
    if (!/eyJ[A-Za-z0-9_-]{20}/.test(JSON.stringify(safe))) console.error(JSON.stringify(safe));
  }
  return response;
} });
const storage = new CloudBaseStorage(store);
const ncp = Buffer.alloc(52); ncp.write('NCP\0'); ncp.writeUInt32BE(1, 4); ncp.writeUInt32BE(36, 8); ncp.write('0100', 12);
const preview = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGOQ8KgAAAFUANnsm43hAAAAAElFTkSuQmCC', 'base64');
const marker = `verification/${randomUUID()}`;
const created = [], checks = [];
try {
  for (const [name, body, mime, details] of [['fixture.ncp', ncp, 'application/octet-stream', validateNcp(ncp)], ['fixture.png', preview, 'image/png', validatePreview(preview)]]) {
    const path = `${marker}/${name}`;
    await storage.put(path, body, mime); created.push(path);
    const downloaded = await storage.get(path, 1024 * 1024);
    assert.deepEqual(downloaded, body);
    const anonymous = await fetch(storage.publicUrl(path), { signal: AbortSignal.timeout(20000), redirect: 'error' });
    assert.ok([401, 403, 404].includes(anonymous.status), `private bucket exposed a ${mime} object anonymously`);
    checks.push({ name, byteLength: details.byteLength, sha256: details.sha256, authenticatedRead: true, anonymousStatus: anonymous.status });
  }
} finally {
  for (const path of created) await store.request(`/v1/storages/object/nikon-presets/${path}`, { method: 'DELETE' });
}
await writeFile(new URL('build/storage-verification.json', root), JSON.stringify({ environmentId: ENV_ID, verifiedAt: new Date().toISOString(), checks, ownFixturesRemoved: created.length }, null, 2) + '\n');
console.log(`Cloud storage verified: ${checks.length} synthetic files, authenticated byte-for-byte reads, anonymous access denied, own fixtures removed.`);
