import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHandler } from '../server/app.mjs';
import { validateNcp, validatePresetFile, presetFilename, buildCatalog, ValidationError } from '../server/validation.mjs';
import { randomToken, sha256 } from '../server/auth.mjs';
import { MemoryStore, syntheticNcp } from './memory-store.mjs';

function record(id, body) {
  const header = Buffer.alloc(8); header.writeUInt32BE(id); header.writeUInt32BE(body.length, 4);
  return Buffer.concat([header, body]);
}
function baseRecords() {
  return Array.from({ length: 32 }, (_, index) => record((index + 1) * 0x100,
    index === 0 ? Buffer.from('0310') : Buffer.alloc(index === 1 || index === 31 ? 20 : index === 30 ? 28 : 2)));
}
function np3(extras = [], records = baseRecords()) {
  return Buffer.concat([Buffer.from('NCP\0'), ...records, ...extras, Buffer.alloc(4)]);
}
function invalid(file) { assert.throws(() => validatePresetFile(file), error => error instanceof ValidationError && error.status === 400); }

test('preset byte detection preserves exact NCP 0100 and every audited NP3 0310 record combination', () => {
  const ncp = syntheticNcp();
  assert.deepEqual(validatePresetFile(ncp), { ...validateNcp(ncp), fileFormat: 'NCP', extension: 'ncp' });
  const note = record(0x10100, Buffer.from('Creator note: synthetic local fixture.'));
  const curve = record(2, Buffer.alloc(578));
  for (const extras of [[], [note], [curve], [note, curve]]) {
    const file = np3(extras), before = Buffer.from(file);
    assert.deepEqual(validatePresetFile(new Uint8Array(file)), { fileFormat: 'NP3', extension: 'np3', ncpVersion: '0310', byteLength: file.length, sha256: sha256(file) });
    assert.deepEqual(file, before);
    assert.throws(() => validateNcp(file), ValidationError, 'legacy converter boundary still rejects NP3');
  }
  assert.equal(np3().length, 392);
  assert.equal(presetFilename('wrong.NCP', 'NP3'), 'wrong.NP3');
  assert.equal(presetFilename('extensionless', 'NCP'), 'extensionless.NCP');
  assert.equal(presetFilename('../unsafe\0.NP3', 'NCP'), '.._unsafe_.NCP');
  assert.ok(presetFilename('x'.repeat(300), 'NP3').length <= 255);
});

test('NP3 strict structure rejects truncation, duplicate/missing/unknown records and malformed lengths/versions/notes', () => {
  const file = np3();
  for (let end = 0; end < file.length; end++) invalid(file.subarray(0, end));
  invalid(Buffer.concat([file, Buffer.from([0])]));
  invalid(np3([], baseRecords().slice(0, 31)));
  const duplicate = baseRecords(); duplicate[2] = duplicate[1]; invalid(np3([], duplicate));
  const wrongOrder = baseRecords(); [wrongOrder[2], wrongOrder[3]] = [wrongOrder[3], wrongOrder[2]]; invalid(np3([], wrongOrder));
  const wrongLength = baseRecords(); wrongLength[2] = record(0x300, Buffer.alloc(3)); invalid(np3([], wrongLength));
  const overflow = Buffer.from(file); overflow.writeUInt32BE(0xffffffff, 8); invalid(overflow);
  for (const version of [Buffer.from('0300'), Buffer.from([0xb0, 0x33, 0x31, 0x30])]) {
    const records = baseRecords(); records[0] = record(0x100, version); invalid(np3([], records));
  }
  invalid(np3([record(0x2100, Buffer.alloc(2))]));
  invalid(np3([record(2, Buffer.alloc(577))]));
  invalid(np3([record(2, Buffer.alloc(578)), record(2, Buffer.alloc(578))]));
  const note = record(0x10100, Buffer.alloc(16));
  invalid(np3([note, note])); invalid(np3([record(2, Buffer.alloc(578)), note]));
  for (const length of [0, 15, 249]) invalid(np3([record(0x10100, Buffer.alloc(length))]));
  const badUtf8 = Buffer.alloc(16); badUtf8[0] = 0xff; invalid(np3([record(0x10100, badUtf8)]));
  invalid(Buffer.alloc(1048577));
});

test('catalog reports detected format, defaults legacy files to NCP and rejects mismatched metadata', () => {
  const make = file => ({ id: 'fixture', status: 'published', published: { name: 'Fixture', file: { url: 'https://files.example/fixture.np3', ...file } } });
  const metadata = validatePresetFile(np3());
  assert.equal(buildCatalog([make(metadata)]).presets[0].fileFormat, 'NP3');
  assert.equal(buildCatalog([make(validateNcp(syntheticNcp()))]).presets[0].fileFormat, 'NCP');
  for (const bad of [{ fileFormat: 'NCP' }, { ncpVersion: '0300' }, { extension: 'ncp' }, { fileFormat: 'UNKNOWN' }]) {
    assert.throws(() => buildCatalog([make({ ...metadata, ...bad })]), ValidationError);
  }
});

async function fixture(t, community = false) {
  const store = new MemoryStore(); store.publicAssetOrigin = 'https://files.example.test';
  const token = randomToken(), csrf = randomToken(), userToken = randomToken();
  store.admins.set('formatadmin', { username: 'formatadmin', disabled: false });
  await store.createSession({ token_hash: sha256(token), username: 'formatadmin', csrf_token: csrf, expires_at: new Date(Date.now() + 60000).toISOString() });
  const objects = new Map(), calls = [], assets = new Map(); let publicAsset = false;
  if (community) {
    store.userSession = async hash => hash === sha256(userToken) ? { user: { id: '11111111-1111-4111-8111-111111111111', status: 'active' } } : null;
    store.community = async (action, user, id, body) => {
      calls.push({ action, body });
      if (action === 'addAsset') { assets.set(body.asset.id, body.asset); return body.asset; }
      if (action === 'ownAsset' || action === 'publicAsset' && publicAsset) return assets.get(body.assetId) || null;
      return { ok: true };
    };
  }
  const server = createServer(); await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const origin = `http://127.0.0.1:${server.address().port}`;
  server.on('request', createHandler({ store, storage: { put: async (path, data) => objects.set(path, Buffer.from(data)), get: async path => objects.get(path) },
    environmentId: 'format-test', publicBaseUrl: origin, secureCookies: false, challengeService: { protect: async () => {} } }));
  const request = (path, { method = 'GET', body, raw = false } = {}) => fetch(origin + '/preset-library' + path, {
    method, headers: { Cookie: `nikon_session=${token}`, Origin: origin, 'X-CSRF-Token': csrf, Authorization: `Bearer ${userToken}`,
      'Content-Type': raw ? 'application/octet-stream' : 'application/json' }, body: body === undefined ? undefined : raw ? body : JSON.stringify(body)
  });
  return { request, objects, calls, publish: () => { publicAsset = true; } };
}

test('admin uploads, publishes and downloads exact NP3 bytes with canonical extension and format metadata', async t => {
  const { request, objects } = await fixture(t), bytes = np3();
  const upload = await request('/api/uploads?kind=ncp&filename=misnamed.NCP', { method: 'POST', body: bytes, raw: true });
  assert.equal(upload.status, 201); const asset = (await upload.json()).asset;
  assert.equal(asset.kind, 'ncp'); assert.equal(asset.fileFormat, 'NP3'); assert.equal(asset.originalName, 'misnamed.NP3');
  assert.match(asset.storagePath, /^ncp\/[a-f0-9]{64}\.np3$/); assert.match(asset.url, /\.np3$/);
  assert.deepEqual(objects.get(asset.storagePath), bytes);
  assert.equal((await request(new URL(asset.url).pathname.replace('/preset-library', ''))).status, 404);
  const saved = await request('/api/presets', { method: 'POST', body: { name: 'NP3 fixture', fileAssetId: asset.id } });
  assert.equal(saved.status, 201); const preset = (await saved.json()).preset; assert.equal(preset.fileFormat, 'NP3');
  assert.equal((await request(`/api/presets/${preset.id}/publish`, { method: 'POST', body: { expectedRevision: 1 } })).status, 200);
  assert.equal((await (await request('/catalog.json')).json()).presets[0].fileFormat, 'NP3');
  const download = await request(new URL(asset.url).pathname.replace('/preset-library', ''));
  assert.equal(download.status, 200); assert.match(download.headers.get('content-disposition'), /misnamed.NP3/);
  assert.deepEqual(Buffer.from(await download.arrayBuffer()), bytes);
  assert.equal((await request(new URL(asset.url).pathname.replace('/preset-library', '').replace(/\.np3$/, '.ncp'))).status, 404);
  assert.equal((await (await request(`/api/presets/${preset.id}/versions`)).json()).items[0].fileFormat, 'NP3');
  const ncp = await request('/api/uploads?kind=ncp&filename=extensionless', { method: 'POST', body: syntheticNcp(), raw: true });
  assert.equal((await ncp.json()).asset.originalName, 'extensionless.NCP');
  const before = objects.size;
  assert.equal((await request('/api/uploads?kind=ncp&filename=fake.NP3', { method: 'POST', body: Buffer.from('fake'), raw: true })).status, 400);
  assert.equal(objects.size, before);
});

test('community NP3 upload retains legacy asset kind, checks actual bytes and serves only the canonical approved URL', async t => {
  const { request, calls, publish, objects } = await fixture(t, true), bytes = np3();
  const result = await request('/community/uploads?kind=ncp&filename=source', { method: 'POST', body: bytes, raw: true });
  assert.equal(result.status, 201); const asset = (await result.json()).asset;
  assert.equal(calls[0].action, 'reserveUpload'); assert.equal(asset.fileFormat, 'NP3'); assert.equal(asset.extension, 'np3');
  assert.equal(asset.originalName, 'source.NP3'); assert.equal(asset.kind, 'ncp'); assert.deepEqual(objects.get(asset.storagePath), bytes);
  const publicPath = new URL(asset.url).pathname.replace('/preset-library', '');
  assert.equal((await request(publicPath)).status, 404);
  assert.equal((await request(`/community/assets/${asset.id}/content`)).status, 200);
  publish(); const downloaded = await request(publicPath);
  assert.equal(downloaded.status, 200); assert.deepEqual(Buffer.from(await downloaded.arrayBuffer()), bytes);
  assert.equal((await request(publicPath.replace(/\.np3$/, '.ncp'))).status, 404);
});
