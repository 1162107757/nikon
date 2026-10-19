import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { createHandler } from '../server/app.mjs';
import { validateAvatar } from '../server/profile.mjs';
import { randomToken, sha256 } from '../server/auth.mjs';
import { CloudBaseStore, ServiceError } from '../server/cloudbase.mjs';
import { MemoryStore } from './memory-store.mjs';

// Locally encoded 64x64 solid-color JPEG; contains no photograph or provider credentials.
const JPEG = Buffer.from('/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCABAAEADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDxSiiiv10+KCiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKAP/Z', 'base64');
const INSTALL = '22222222-2222-4222-8222-222222222222';
class ProfileStore extends MemoryStore {
  reserved = []; avatarRows = new Map(); quota = true; protections = [];
  async abuseAllowed(...values) { this.protections.push(values); return true; }
  async reserveAvatar(userId, id, expectedUpdatedAt, context) {
    const user = this.userRows.get(userId);
    if (user.updatedAt !== expectedUpdatedAt) throw new ServiceError(409, 'USER_CONFLICT', '请刷新后重试。');
    this.reserved.push({ userId, id, expectedUpdatedAt, context }); return { allowed: this.quota };
  }
  async setAvatar(userId, id, expectedUpdatedAt, avatar) {
    const user = this.userRows.get(userId);
    if (user.status !== 'active') throw new ServiceError(401, 'USER_LOGIN_REQUIRED', '请重新登录。');
    if (user.updatedAt !== expectedUpdatedAt) throw new ServiceError(409, 'USER_CONFLICT', '请刷新后重试。');
    assert.ok(this.reserved.some(value => value.userId === userId && value.id === id));
    this.avatarRows.set(id, { ...avatar, userId });
    user.avatarUrl = avatar.url; user.avatarSource = 'custom'; user.avatarAssetId = id;
    user.updatedAt = new Date(Date.parse(user.updatedAt) + 1).toISOString(); return this.user(userId);
  }
  async avatarAsset(id) {
    const asset = this.avatarRows.get(id), user = this.userRows.get(asset?.userId);
    return user?.status === 'active' && user.avatarAssetId === id ? asset : null;
  }
  async resetAvatar(userId, expectedUpdatedAt) {
    const user = this.userRows.get(userId);
    if (user.updatedAt !== expectedUpdatedAt) throw new ServiceError(409, 'USER_CONFLICT', '请刷新后重试。');
    user.avatarUrl = ''; user.avatarSource = 'default'; user.avatarAssetId = null;
    user.updatedAt = new Date(Date.parse(user.updatedAt) + 1).toISOString(); return this.user(userId);
  }
}
async function fixture(t) {
  const store = new ProfileStore(), objects = new Map(), calls = [];
  const storage = { put: async (path, bytes, type) => { calls.push({ path, type }); objects.set(path, bytes); }, get: async path => objects.get(path) };
  const server = createServer(); await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const origin = `http://127.0.0.1:${server.address().port}`;
  server.on('request', createHandler({ store, storage, environmentId: 'local', publicBaseUrl: origin,
    secureCookies: false, challengeSecret: 'synthetic-local-avatar-protection-secret-32' }));
  const token = randomToken(), secondToken = randomToken(), expiresAt = new Date(Date.now() + 60000).toISOString();
  const user = await store.registerPassword('localavatar', 'unused', sha256(token), expiresAt);
  const second = await store.registerPassword('secondavatar', 'unused', sha256(secondToken), expiresAt);
  const request = (path, { method = 'GET', raw, json, bearer = token, expectedUpdatedAt = user.updatedAt, headers = {} } = {}) => fetch(origin + '/preset-library' + path, {
    method, headers: { ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
      'X-Installation-Id': INSTALL, 'X-Expected-Updated-At': expectedUpdatedAt,
      ...(json ? { 'Content-Type': 'application/json' } : raw ? { 'Content-Type': 'image/jpeg' } : {}), ...headers },
    body: json ? JSON.stringify(json) : raw
  });
  return { store, storage, calls, objects, request, origin, user, second, secondToken };
}

test('avatar validation rejects other MIME types, truncated/trailing files, non-square and huge dimensions', () => {
  assert.equal(validateAvatar(JPEG, 'image/jpeg').width, 64);
  for (const [data, type] of [[JPEG, 'image/png'], [Buffer.from('<svg/>'), 'image/jpeg'], [JPEG.subarray(0, -1), 'image/jpeg'],
    [Buffer.concat([JPEG, Buffer.from('trailing')]), 'image/jpeg'], [Buffer.alloc(1048577), 'image/jpeg']]) {
    assert.throws(() => validateAvatar(data, type), { code: 'INVALID_AVATAR' });
  }
  const sof = JPEG.indexOf(Buffer.from([255, 192]));
  for (const [width, height] of [[63, 63], [65, 64], [1025, 1025], [65535, 65535]]) {
    const data = Buffer.from(JPEG); data.writeUInt16BE(height, sof + 5); data.writeUInt16BE(width, sof + 7);
    assert.throws(() => validateAvatar(data, 'image/jpeg'), { code: 'INVALID_AVATAR' });
  }
  const segments = []; let remaining = 1048576 - JPEG.length;
  while (remaining) {
    let size = Math.min(remaining, 65537);
    if (remaining > size && remaining - size < 4) size -= 4 - (remaining - size);
    const segment = Buffer.alloc(size); segment[0] = 255; segment[1] = 224; segment.writeUInt16BE(size - 2, 2);
    segments.push(segment); remaining -= size;
  }
  const boundary = Buffer.concat([JPEG.subarray(0, 2), ...segments, JPEG.subarray(2)]);
  assert.equal(boundary.length, 1048576); assert.equal(validateAvatar(boundary, 'image/jpeg').byteLength, 1048576);
});

test('upload authenticates account, commits owned UUID path and serves only that avatar with safe media headers', async t => {
  const { store, calls, request, origin, user, second } = await fixture(t);
  const response = await request('/profile/avatar?userId=' + second.id, { method: 'PUT', raw: JPEG });
  assert.equal(response.status, 200); const { user: updated } = await response.json();
  assert.equal(updated.id, user.id); assert.equal(updated.avatarSource, 'custom'); assert.ok(updated.updatedAt > user.updatedAt);
  assert.match(updated.avatarUrl, new RegExp('^' + origin + '/preset-library/avatars/[a-f0-9-]+\\.jpg$'));
  assert.equal(store.userRows.get(second.id).avatarUrl, ''); assert.ok(!('qqOpenIdMasked' in updated));
  assert.equal(calls.length, 1); assert.equal(calls[0].type, 'image/jpeg');
  assert.deepEqual(Object.keys(store.reserved[0].context).sort(), ['installationHash', 'sourceHash']);
  const path = new URL(updated.avatarUrl).pathname.replace('/preset-library', '');
  const media = await request(path, { bearer: '' });
  assert.equal(media.status, 200); assert.equal(media.headers.get('content-type'), 'image/jpeg');
  assert.equal(media.headers.get('x-content-type-options'), 'nosniff');
  assert.equal(media.headers.get('content-security-policy'), "default-src 'none'; sandbox");
  assert.deepEqual(Buffer.from(await media.arrayBuffer()), JPEG);
  assert.equal((await request(path + '?access_token=secret', { bearer: '' })).status, 400);
  assert.equal((await request(path, { method: 'HEAD', bearer: '' })).status, 405);
  assert.equal((await request('/avatars/' + randomUUID() + '.jpg', { bearer: '' })).status, 404);
  const reset = await request('/profile/avatar/reset', { method: 'POST', expectedUpdatedAt: updated.updatedAt, json: { expectedUpdatedAt: updated.updatedAt } });
  assert.equal(reset.status, 200); const resetUser = (await reset.json()).user;
  assert.equal(resetUser.avatarUrl, ''); assert.equal(resetUser.avatarSource, 'default');
  assert.equal((await request(path, { bearer: '' })).status, 404);
});

test('invalid auth/origin/revision and JSON URL injection never reach object storage', async t => {
  const { request, calls, store, user } = await fixture(t);
  assert.equal((await request('/profile/avatar', { method: 'PUT', raw: JPEG, bearer: '' })).status, 401);
  assert.equal((await request('/profile/avatar', { method: 'PUT', raw: JPEG, headers: { Origin: 'https://other.invalid' } })).status, 403);
  assert.equal((await request('/profile/avatar', { method: 'PUT', raw: JPEG, expectedUpdatedAt: 'bad' })).status, 400);
  assert.equal((await request('/profile/avatar', { method: 'PUT', raw: JPEG, expectedUpdatedAt: '2000-01-01T00:00:00Z' })).status, 409);
  assert.equal((await request('/profile/avatar/reset', { method: 'POST', json: { expectedUpdatedAt: user.updatedAt, avatarUrl: 'https://evil.invalid/a.svg' } })).status, 400);
  assert.equal(store.reserved.length, 0); assert.equal(calls.length, 0);
});

test('failed and oversized uploads consume reservation while quota failures precede storage', async t => {
  const { request, store, calls, storage } = await fixture(t);
  assert.equal((await request('/profile/avatar', { method: 'PUT', raw: Buffer.from('not an image') })).status, 400);
  assert.equal(store.reserved.length, 1); assert.equal(calls.length, 0);
  assert.equal((await request('/profile/avatar', { method: 'PUT', raw: Buffer.alloc(1048577) })).status, 413);
  assert.equal(store.reserved.length, 2); assert.equal(calls.length, 0);
  store.quota = false;
  assert.equal((await request('/profile/avatar', { method: 'PUT', raw: JPEG })).status, 429);
  assert.equal(calls.length, 0);
  store.quota = true; storage.put = async () => { throw new ServiceError(503, 'FILE_UNAVAILABLE', 'offline'); };
  assert.equal((await request('/profile/avatar', { method: 'PUT', raw: JPEG })).status, 503);
  assert.equal(store.reserved.length, 4);
});

test('a concurrent profile change or disable rejects upload commit without publishing an orphan', async t => {
  const { request, storage, store, user } = await fixture(t);
  storage.put = async () => { store.userRows.get(user.id).updatedAt = '2099-01-01T00:00:00Z'; };
  assert.equal((await request('/profile/avatar', { method: 'PUT', raw: JPEG })).status, 409);
  assert.equal(store.avatarRows.size, 0); assert.equal(store.userRows.get(user.id).avatarUrl, '');
  store.userRows.get(user.id).updatedAt = user.updatedAt;
  storage.put = async () => { store.userRows.get(user.id).status = 'disabled'; };
  assert.equal((await request('/profile/avatar', { method: 'PUT', raw: JPEG })).status, 401);
  assert.equal(store.avatarRows.size, 0);
});

test('CloudBase avatar RPC parameters keep identity separate from untrusted body metadata', async () => {
  const store = new CloudBaseStore({ environmentId: 'local', serviceKey: 'unused' }), calls = [];
  store.rpc = async (name, body) => { calls.push({ name, body }); return { allowed: true }; };
  await store.reserveAvatar('user', 'asset', 'time.123456Z', { sourceHash: 'source', installationHash: 'installation' });
  await store.setAvatar('user', 'asset', 'time.123456Z', { mimeType: 'image/jpeg' });
  await store.resetAvatar('user', 'time.123456Z'); await store.avatarAsset('asset');
  assert.deepEqual(calls[0], { name: 'nikon_avatar_reserve', body: { p_user_id: 'user', p_id: 'asset', p_expected_updated_at: 'time.123456Z', p_source_hash: 'source', p_installation_hash: 'installation' } });
  assert.deepEqual(calls.map(value => value.name), ['nikon_avatar_reserve', 'nikon_avatar_set', 'nikon_avatar_reset', 'nikon_avatar_asset']);
});
