import { readFile } from 'node:fs/promises';
import { ENV_ID } from './cloud-tools.mjs';
const credential = JSON.parse(await readFile(new URL('../.local/service-credential.json', import.meta.url), 'utf8'));
if (credential.environmentId !== ENV_ID) throw new Error('Unexpected environment');
const base = `https://${ENV_ID}.api.tcloudbasegateway.com`;
for (const path of ['/v1/rdb/rest/nikon_presets?select=id&limit=1', '/v1/storages/bucket/']) {
  const response = await fetch(base + path, { headers: { Authorization: `Bearer ${credential.serviceKey}` }, signal: AbortSignal.timeout(20000) });
  let data; try { data = await response.json(); } catch { data = {}; }
  const output = { path, status: response.status, code: data.code || data.error?.code || data.Error?.Code, message: data.message || data.error?.message || data.Error?.Message,
    rows: Array.isArray(data) ? data.length : undefined };
  if (/eyJ[A-Za-z0-9_-]{20}/.test(JSON.stringify(output))) throw new Error('Provider response redacted');
  console.log(JSON.stringify(output));
}
