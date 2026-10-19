import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHandler } from '../server/app.mjs';
import { hashPassword } from '../server/auth.mjs';
import { MemoryStore, syntheticNcp } from './memory-store.mjs';

test('HTTP category updates require admin/CSRF/revision and publish only explicit immutable snapshots', async t => {
  const store = new MemoryStore(), password = 'Synthetic-category-test-password';
  store.publicAssetOrigin = 'https://files.example.test';
  store.admins.set('admin', { username: 'admin', password_hash: await hashPassword(password) });
  const server = createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const origin = `http://127.0.0.1:${server.address().port}`;
  server.on('request', createHandler({ store, storage: { put: async () => {} }, environmentId: 'test-only', publicBaseUrl: origin, secureCookies: false }));
  let cookie = '', csrf = '';
  const request = (path, { method = 'GET', body, raw = false, headers = {} } = {}) => fetch(`${origin}/preset-library${path}`, {
    method, headers: { ...(cookie ? { Cookie: cookie } : {}), ...(method !== 'GET' ? { Origin: origin, 'X-CSRF-Token': csrf, 'Content-Type': raw ? 'application/octet-stream' : 'application/json' } : {}), ...headers },
    body: body === undefined ? undefined : raw ? body : JSON.stringify(body), redirect: 'manual',
  });
  assert.equal((await request('/api/presets', { method: 'POST', body: { name: 'Guest', category: '风景' } })).status, 401);
  const login = await request('/api/login', { method: 'POST', body: { username: 'admin', password } });
  assert.equal(login.status, 200); cookie = login.headers.get('set-cookie').split(';')[0]; csrf = (await login.json()).csrfToken;
  const upload = await request('/api/uploads?kind=ncp', { method: 'POST', body: syntheticNcp(), raw: true });
  assert.equal(upload.status, 201); const asset = (await upload.json()).asset;
  const legacyDraft = { name: 'Legacy category fixture', description: '', compatibleModels: [], sortOrder: 0, file: asset, preview: null };
  const legacy = await store.savePreset(null, null, legacyDraft);
  const id = legacy.id;
  const catalogItem = async () => (await (await request('/catalog.json')).json()).presets.find(item => item.id === id);
  const save = (revision, category, headers = {}) => request(`/api/presets/${id}`, { method: 'PUT', body: { ...legacyDraft, fileAssetId: asset.id, expectedRevision: revision, category }, headers });
  const publish = revision => request(`/api/presets/${id}/publish`, { method: 'POST', body: { expectedRevision: revision } });
  assert.equal((await (await request('/api/presets')).json()).items[0].category, '');
  assert.equal((await (await request(`/api/presets/${id}/versions`)).json()).items[0].category, '');
  assert.equal(await catalogItem(), undefined);
  assert.equal((await publish(1)).status, 200);
  assert.equal((await catalogItem()).category, '');
  assert.equal((await catalogItem()).previewUrl, '');
  const badCsrf = await save(1, '胶片', { 'X-CSRF-Token': 'invalid' });
  assert.equal(badCsrf.status, 403); assert.equal((await store.preset(id)).revision, 1);
  for (const invalid of ['全部', ' 未分类 ', 'x'.repeat(25), '🌅'.repeat(25), '\n人像', '人\u0085像', '\ud800', 1, []]) {
    const response = await save(1, invalid);
    assert.equal(response.status, 400); assert.equal((await response.json()).error.code, 'invalid_preset');
  }
  assert.equal((await store.versions(id)).length, 1, 'invalid category edits cannot create saved versions');
  const updated = await save(1, ' Cafe\u0301 ');
  assert.equal(updated.status, 200); assert.equal((await updated.json()).preset.category, 'Café');
  assert.equal((await catalogItem()).category, '', 'draft category cannot leak through the catalog');
  assert.equal((await publish(1)).status, 409);
  assert.equal((await catalogItem()).category, '');
  const history = (await (await request(`/api/presets/${id}/versions`)).json()).items;
  assert.deepEqual(history.map(version => version.category), ['Café', '']);
  assert.deepEqual(history.map(version => version.isPublished), [false, true]);
  assert.equal((await publish(2)).status, 200);
  assert.equal((await catalogItem()).category, 'Café');
  assert.equal((await save(2, '夜景')).status, 200);
  assert.equal((await catalogItem()).category, 'Café');
  assert.equal((await save(2, '人像')).status, 409, 'stale category writes do not replace a newer draft');
  assert.equal((await publish(3)).status, 200);
  assert.equal((await catalogItem()).category, '夜景');
  const cleared = await save(3, null); assert.equal(cleared.status, 200); assert.equal((await cleared.json()).preset.category, '');
  assert.equal((await catalogItem()).category, '夜景');
  assert.equal((await publish(4)).status, 200); assert.equal((await catalogItem()).category, '');
  assert.deepEqual((await (await request(`/api/presets/${id}/versions`)).json()).items.map(version => version.category), ['', '夜景', 'Café', '']);
  assert.equal(Object.hasOwn((await store.versions(id)).at(-1).snapshot, 'category'), false, 'legacy saved snapshots remain unchanged');
  const unchanged = await save(4, ''); assert.equal(unchanged.status, 200); assert.equal((await unchanged.json()).preset.revision, 4);
  const unpublished = await request(`/api/presets/${id}/unpublish`, { method: 'POST', body: { expectedRevision: 4 } });
  assert.equal(unpublished.status, 200); assert.equal(await catalogItem(), undefined);
});
