import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHandler } from '../server/app.mjs';
import { randomToken, sha256 } from '../server/auth.mjs';
import { CloudBaseStore } from '../server/cloudbase.mjs';
import { MemoryStore } from './memory-store.mjs';

const identity = { appId: '123456789', openid: 'ABCDEF0123456789ABCDEF0123456789', nickname: '合成用户', avatarUrl: 'https://q.qlogo.cn/fixture' };
const qqToken = 'synthetic-qq-sdk-token-only-123456';
async function fixture(t, verifier) {
  const store = new MemoryStore(), adminToken = randomToken(), csrf = randomToken();
  store.admins.set('admin', { username: 'admin', disabled: false });
  await store.createSession({ token_hash: sha256(adminToken), username: 'admin', csrf_token: csrf, expires_at: new Date(Date.now() + 60000).toISOString() });
  const server = createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const origin = `http://127.0.0.1:${server.address().port}`;
  server.on('request', createHandler({ store, storage: {}, environmentId: 'test-only', publicBaseUrl: origin,
    secureCookies: false, qqVerifier: verifier,
    challengeService: { protect: async () => {}, consume: async () => true } }));
  const request = (path, { method = 'GET', body, admin = false, token, headers = {} } = {}) => fetch(origin + '/preset-library' + path, {
    method, headers: { ...(admin ? { Cookie: `nikon_session=${adminToken}`, Origin: origin, 'X-CSRF-Token': csrf } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  return { store, request, adminToken, csrf, origin };
}
function verifiedProvider(onVerify = () => {}) {
  return { configured: true, status: () => ({ configured: true, provider: 'qq', message: 'QQ 登录已配置。' }),
    verify: async input => { onVerify(input); return { ...identity }; } };
}

test('unconfigured QQ is explicitly unavailable and empty real-user administration stays protected', async t => {
  const { request, store } = await fixture(t);
  assert.equal((await request('/api/users')).status, 401);
  const unavailable = await request('/auth/qq', { method: 'POST', body: { accessToken: qqToken } });
  assert.equal(unavailable.status, 503); assert.equal((await unavailable.json()).error.code, 'qq_not_configured');
  const response = await request('/api/users', { admin: true });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { items: [], total: 0, page: 1, pageSize: 20,
    summary: { total: 0, active: 0, disabled: 0 }, qqLogin: { configured: false, provider: 'qq', message: 'QQ 登录尚未配置，请先申请 QQ 互联移动应用并配置 AppID。' } });
  assert.equal(store.userRows.size, 0);
});

test('QQ identity comes only from verifier and user bearer sessions cannot become administrator sessions', async t => {
  let verifiedInput;
  const { request, store, adminToken } = await fixture(t, verifiedProvider(input => { verifiedInput = input; }));
  const response = await request('/auth/qq', { method: 'POST', body: { accessToken: qqToken, openid: 'forged', nickname: '管理员', avatarUrl: 'javascript:alert(1)' } });
  assert.equal(response.status, 200);
  const login = await response.json();
  assert.deepEqual(verifiedInput, { accessToken: qqToken });
  assert.equal(login.user.nickname, identity.nickname); assert.equal(login.user.loginCount, 1);
  assert.match(login.accessToken, /^[A-Za-z0-9_-]{43}$/);
  assert.equal(store.userSessions.has(sha256(login.accessToken)), true);
  assert.equal(store.userSessions.has(login.accessToken), false);
  assert.equal(JSON.stringify([...store.userRows.values()]).includes(qqToken), false);
  assert.equal(JSON.stringify([...store.userSessions.values()]).includes(qqToken), false);
  assert.equal(JSON.stringify(login).includes(identity.openid), false);
  assert.equal((await request('/auth/me', { token: login.accessToken })).status, 200);
  assert.equal((await request('/api/users', { token: login.accessToken })).status, 401);
  assert.equal((await request('/auth/me', { token: adminToken, admin: true })).status, 401);
  assert.equal((await request('/auth/me', { admin: true })).status, 401);
  assert.equal((await request('/api/users', { headers: { Cookie: `nikon_session=${login.accessToken}` } })).status, 401);
  const repeat = await (await request('/auth/qq', { method: 'POST', body: { accessToken: qqToken } })).json();
  assert.equal(repeat.user.id, login.user.id); assert.equal(repeat.user.loginCount, 2);
  assert.equal(store.userRows.size, 1); assert.equal(store.userIdentities.size, 1);
  const detail = await (await request(`/api/users/${login.user.id}`, { admin: true })).json();
  assert.equal(detail.user.qqOpenIdMasked, 'ABCD…6789'); assert.equal(JSON.stringify(detail).includes(identity.openid), false);
  const list = await (await request('/api/users', { admin: true })).json();
  assert.equal(Object.hasOwn(list.items[0], 'qqOpenIdMasked'), false);
});

test('disabling a user uses version and CSRF checks, revokes all sessions and blocks future QQ login', async t => {
  const { request, store, origin } = await fixture(t, verifiedProvider());
  const first = await (await request('/auth/qq', { method: 'POST', body: { accessToken: qqToken } })).json();
  const second = await (await request('/auth/qq', { method: 'POST', body: { accessToken: qqToken } })).json();
  const path = `/api/users/${first.user.id}/status`, current = second.user.updatedAt;
  assert.equal((await request(path, { method: 'POST', admin: true, body: { status: 'disabled', expectedUpdatedAt: current }, headers: { 'X-CSRF-Token': 'wrong' } })).status, 403);
  assert.equal((await request(path, { method: 'POST', admin: true, body: { status: 'disabled', expectedUpdatedAt: current }, headers: { Origin: 'https://other.test' } })).status, 403);
  assert.equal((await request(path, { method: 'POST', admin: true, body: { status: 'disabled', expectedUpdatedAt: first.user.updatedAt } })).status, 409);
  assert.equal((await request(path, { method: 'POST', admin: true, body: { status: 'admin', expectedUpdatedAt: current } })).status, 400);
  assert.equal((await request(path, { method: 'POST', admin: true, body: { status: 'disabled', expectedUpdatedAt: '2026-01-01' } })).status, 400);
  const disabled = await (await request(path, { method: 'POST', admin: true, body: { status: 'disabled', expectedUpdatedAt: current } })).json();
  assert.equal(disabled.user.status, 'disabled'); assert.equal(store.userSessions.size, 0);
  assert.equal((await request('/auth/me', { token: first.accessToken })).status, 401);
  assert.equal((await request('/auth/me', { token: second.accessToken })).status, 401);
  assert.equal((await request('/auth/qq', { method: 'POST', body: { accessToken: qqToken } })).status, 403);
  assert.equal(store.userRows.get(first.user.id).loginCount, 2);
  const enabledResponse = await request(path, { method: 'POST', admin: true, body: { status: 'active', expectedUpdatedAt: disabled.user.updatedAt } });
  assert.equal(enabledResponse.status, 200);
  assert.equal((await request('/auth/me', { token: first.accessToken })).status, 401, 'reenabling never revives old bearer tokens');
  assert.equal((await request('/auth/qq', { method: 'POST', body: { accessToken: qqToken }, headers: { Origin: origin } })).status, 200);
});

test('user search is literal, pagination bounded and totals distinguish filtered matches from global summary', async t => {
  const { request, store } = await fixture(t, verifiedProvider());
  for (let index = 0; index < 3; index++) {
    const row = await store.signInQq({ ...identity, openid: index.toString(16).padStart(32, '0'), nickname: index === 0 ? 'A_%(name)' : `User ${index}` }, sha256('fixture-' + index), new Date(Date.now() + 60000).toISOString());
    if (index === 2) await store.setUserStatus(row.id, 'disabled', row.updatedAt);
  }
  const result = await (await request('/api/users?status=active&page=1&pageSize=1', { admin: true })).json();
  assert.equal(result.items.length, 1); assert.equal(result.total, 2);
  assert.deepEqual(result.summary, { total: 3, active: 2, disabled: 1 });
  const literal = await (await request('/api/users?q=' + encodeURIComponent('_%('), { admin: true })).json();
  assert.equal(literal.total, 1); assert.equal(literal.items[0].nickname, 'A_%(name)');
  const next = await (await request('/api/users?page=2&pageSize=2', { admin: true })).json();
  assert.equal(next.items.length, 1); assert.equal(next.total, 3);
  for (const query of ['page=0', 'pageSize=101', 'page=100001', 'page=1.5', 'status=admin', 'q=' + 'a'.repeat(81)]) {
    assert.equal((await request('/api/users?' + query, { admin: true })).status, 400);
  }
  assert.equal((await request('/api/users/not-a-user', { admin: true })).status, 404);
  assert.equal((await request('/api/users/00000000-0000-0000-0000-000000000000', { admin: true })).status, 404);
});

test('normal user logout/expiry and QQ body/attempt bounds never affect admin login', async t => {
  const { request, store } = await fixture(t, verifiedProvider());
  const first = await (await request('/auth/qq', { method: 'POST', body: { accessToken: qqToken } })).json();
  assert.equal((await request('/auth/logout', { method: 'POST', token: first.accessToken })).status, 200);
  assert.equal((await request('/auth/me', { token: first.accessToken })).status, 401);
  assert.equal((await request('/auth/logout', { method: 'POST', token: first.accessToken })).status, 401);
  const second = await (await request('/auth/qq', { method: 'POST', body: { accessToken: qqToken } })).json();
  store.userSessions.get(sha256(second.accessToken)).expiresAt = new Date(Date.now() - 1000).toISOString();
  assert.equal((await request('/auth/me', { token: second.accessToken })).status, 401);
  assert.equal((await request('/auth/me', { headers: { Authorization: 'Bearer malformed, Bearer duplicate' } })).status, 401);
  const invalid = await request('/auth/qq', { method: 'POST', body: { accessToken: '' } });
  assert.equal(invalid.status, 400); assert.equal((await invalid.json()).error.code, 'qq_invalid_token');
  assert.equal((await request('/auth/qq', { method: 'POST', body: { accessToken: qqToken, padding: 'a'.repeat(4096) } })).status, 413);
  for (let index = 0; index < 6; index++) assert.equal((await request('/auth/qq', { method: 'POST', body: { accessToken: qqToken } })).status, 200);
  const throttled = await request('/auth/qq', { method: 'POST', body: { accessToken: qqToken } });
  assert.equal(throttled.status, 429); assert.equal((await throttled.json()).error.code, 'qq_login_throttled');
  assert.equal((await request('/api/users', { admin: true })).status, 200);
});

test('CloudBase user adapter sends bounded queries as RPC JSON and preserves microsecond conflict values', async () => {
  const calls = [];
  const store = new CloudBaseStore({ environmentId: 'synthetic', serviceKey: 'server-test-only', fetchImpl: async (url, options) => {
    calls.push({ url: url.href, body: options.body === undefined ? null : JSON.parse(options.body) });
    return Response.json({});
  } });
  await store.users({ q: 'name,or(id.eq.any)', status: 'all', page: 2, pageSize: 20 });
  assert.match(calls[0].url, /\/rpc\/nikon_users_page$/); assert.equal(calls[0].body.p_q, 'name,or(id.eq.any)');
  const timestamp = '2026-10-18T01:02:03.123456+00:00';
  await store.setUserStatus('00000000-0000-0000-0000-000000000000', 'disabled', timestamp);
  assert.equal(calls[1].body.p_expected_updated_at, timestamp);
  await store.signInQq(identity, sha256('synthetic'), '2026-10-19T00:00:00.000Z');
  assert.match(calls[2].url, /\/rpc\/nikon_sign_in_qq$/); assert.equal(calls[2].body.p_openid, identity.openid);
  assert.equal(Object.hasOwn(calls[2].body, 'accessToken'), false);
});
