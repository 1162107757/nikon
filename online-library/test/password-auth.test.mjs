import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHandler } from '../server/app.mjs';
import { hashPassword, hashUserPassword, verifyPassword, randomToken, sha256 } from '../server/auth.mjs';
import { CloudBaseStore } from '../server/cloudbase.mjs';
import { MemoryStore } from './memory-store.mjs';

const password = '  Secret Password 9  ';
async function fixture(t) {
  const store = new MemoryStore(), adminToken = randomToken(), csrf = randomToken();
  store.admins.set('admin', { username: 'admin', disabled: false });
  await store.createSession({ token_hash: sha256(adminToken), username: 'admin', csrf_token: csrf,
    expires_at: new Date(Date.now() + 60000).toISOString() });
  const server = createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const origin = `http://127.0.0.1:${server.address().port}`;
  // These credential regression tests isolate password behavior. Real challenge enforcement has separate HTTP/PG coverage.
  server.on('request', createHandler({ store, storage: {}, environmentId: 'fixture', publicBaseUrl: origin, secureCookies: false,
    challengeService: { protect: async () => {}, consume: async () => true } }));
  const request = (path, { method = 'GET', body, token, admin = false, headers = {}, raw } = {}) => fetch(origin + '/preset-library' + path, {
    method, headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(admin ? { Cookie: `nikon_session=${adminToken}`, Origin: origin, 'X-CSRF-Token': csrf } : {}), ...headers },
    body: raw ?? (body === undefined ? undefined : JSON.stringify(body))
  });
  const register = username => request('/auth/register', { method: 'POST', body: { username, password } });
  const login = (username, selectedPassword = password) => request('/auth/login', { method: 'POST', body: { username, password: selectedPassword } });
  return { store, request, register, login, origin, adminToken };
}

test('ordinary password hashing has an independent policy and preserves every password character', async () => {
  const first = await hashUserPassword('12345678'), second = await hashUserPassword('12345678');
  assert.match(first, /^scrypt:32768:8:1:[a-f0-9]{32}:[a-f0-9]{128}$/); assert.notEqual(first, second);
  assert.equal(await verifyPassword('12345678', first), true);
  await assert.rejects(hashUserPassword('1234567')); await assert.rejects(hashUserPassword('x'.repeat(129)));
  await assert.rejects(hashPassword('1234567'), /管理员密码/);
  const spaced = await hashUserPassword(password);
  assert.equal(await verifyPassword(password, spaced), true);
  assert.equal(await verifyPassword(password.trim(), spaced), false);
});

test('registration signs in a lowercase username and never exposes or stores plaintext passwords/tokens', async t => {
  const { register, request, store } = await fixture(t);
  const response = await register('Photo_User9'); assert.equal(response.status, 201);
  const result = await response.json();
  assert.equal(result.tokenType, 'Bearer'); assert.match(result.accessToken, /^[A-Za-z0-9_-]{43}$/);
  assert.equal(result.user.username, 'photo_user9'); assert.equal(result.user.nickname, 'photo_user9');
  assert.equal(result.user.provider, 'password'); assert.equal(result.user.status, 'active'); assert.equal(result.user.loginCount, 1);
  assert.ok(Date.parse(result.expiresAt) - Date.now() > 6.99 * 86400000);
  assert.equal(store.passwordCredentials.size, 1); assert.equal(store.userRows.size, 1); assert.equal(store.userIdentities.size, 0);
  assert.ok(store.userSessions.has(sha256(result.accessToken))); assert.ok(!store.userSessions.has(result.accessToken));
  assert.ok(!JSON.stringify([...store.passwordCredentials.values()]).includes(password));
  assert.ok(!JSON.stringify(result).includes('scrypt:')); assert.ok(!Object.hasOwn(result.user, 'qqOpenIdMasked'));
  const me = await (await request('/auth/me', { token: result.accessToken })).json();
  assert.equal(me.user.id, result.user.id); assert.equal(me.expiresAt, result.expiresAt);
  assert.ok(!JSON.stringify(me).includes('password_hash'));
});

test('case-insensitive duplicate registration is atomic and does not create extra profiles or sessions', async t => {
  const { register, store } = await fixture(t);
  const responses = await Promise.all([register('Case_User'), register('case_user')]);
  assert.deepEqual(responses.map(row => row.status).sort(), [201, 409]);
  assert.equal((await responses.find(row => row.status === 409).json()).error.code, 'USERNAME_TAKEN');
  assert.equal(store.passwordCredentials.size, 1); assert.equal(store.userRows.size, 1); assert.equal(store.userSessions.size, 1);
});

test('registration validates username/password without trimming and rejects oversized or foreign requests', async t => {
  const { request, store } = await fixture(t);
  for (const username of ['ab', '9abc', '_abc', 'a.b', ' account', 'account ', '中文名称', 'Kelvin', 'a'.repeat(33)]) {
    const result = await request('/auth/register', { method: 'POST', body: { username, password } });
    assert.equal(result.status, 400); assert.equal((await result.json()).error.code, 'INVALID_USERNAME');
  }
  for (const selected of ['1234567', 'x'.repeat(129), null]) {
    const response = await request('/auth/register', { method: 'POST', body: { username: 'account', password: selected } });
    assert.equal(response.status, 400); assert.equal((await response.json()).error.code, 'INVALID_PASSWORD');
  }
  assert.equal((await request('/auth/register', { method: 'POST', body: { username: 'account', password, padding: 'x'.repeat(4096) } })).status, 413);
  assert.equal((await request('/auth/register', { method: 'POST', raw: '{}', headers: { 'Content-Type': 'text/plain' } })).status, 415);
  assert.equal((await request('/auth/register', { method: 'POST', body: { username: 'account', password }, headers: { Origin: 'https://other.invalid' } })).status, 403);
  assert.equal(store.userRows.size, 0);
});

test('unknown user and wrong password share one error while a successful login rotates token and increments count', async t => {
  const { register, login, store } = await fixture(t);
  const first = await (await register('My_Account')).json();
  const unknown = await login('missing_user'), wrong = await login('my_account', 'incorrect-password');
  assert.equal(unknown.status, 401); assert.equal(wrong.status, 401); assert.deepEqual(await unknown.json(), await wrong.json());
  assert.equal((await login('my_account', password.trim())).status, 401);
  assert.equal((await login('my_account', 'short')).status, 401);
  assert.equal((await login(' invalid ', password)).status, 401);
  const response = await login('MY_ACCOUNT'); assert.equal(response.status, 200);
  const second = await response.json();
  assert.equal(second.user.id, first.user.id); assert.equal(second.user.loginCount, 2); assert.notEqual(second.accessToken, first.accessToken);
  assert.equal(store.userSessions.size, 2);
  assert.equal(store.passwordAttempts.has('password-login:' + sha256('app-password:my_account')), false);
});

test('disabled accounts conceal status for wrong passwords and revoke every old bearer session', async t => {
  const { request, register, login } = await fixture(t);
  const first = await (await register('account')).json();
  const disabled = await (await request(`/api/users/${first.user.id}/status`, { method: 'POST', admin: true,
    body: { status: 'disabled', expectedUpdatedAt: first.user.updatedAt } })).json();
  assert.equal(disabled.user.status, 'disabled'); assert.equal((await request('/auth/me', { token: first.accessToken })).status, 401);
  const wrong = await login('account', 'incorrect-password'); assert.equal(wrong.status, 401); assert.equal((await wrong.json()).error.code, 'USER_LOGIN_FAILED');
  const correct = await login('account'); assert.equal(correct.status, 403); assert.equal((await correct.json()).error.code, 'USER_DISABLED');
  const enabled = await request(`/api/users/${first.user.id}/status`, { method: 'POST', admin: true,
    body: { status: 'active', expectedUpdatedAt: disabled.user.updatedAt } });
  assert.equal(enabled.status, 200); assert.equal((await request('/auth/me', { token: first.accessToken })).status, 401);
  assert.equal((await login('account')).status, 200);
});

test('password verification cannot mint a session when the credential hash changes during verification', async t => {
  const { register, login, store } = await fixture(t);
  await register('account');
  const changedHash = await hashUserPassword('New-Password-Only-123'), original = store.passwordCredential.bind(store);
  store.passwordCredential = async username => {
    const previous = await original(username);
    store.passwordCredentials.get(username).password_hash = changedHash;
    return previous;
  };
  assert.equal((await login('account')).status, 401); assert.equal(store.userSessions.size, 1);
});

test('ordinary sessions are capped at ten, expire, log out and cannot authorize administrator routes', async t => {
  const { register, login, request, store, adminToken } = await fixture(t);
  const first = await (await register('account')).json(); let latest;
  for (let i = 0; i < 11; i++) latest = await (await login('account')).json();
  assert.equal(store.userSessions.size, 10);
  assert.equal((await request('/auth/me', { token: latest.accessToken })).status, 200);
  assert.equal((await request('/api/users', { token: latest.accessToken })).status, 401);
  assert.equal((await request('/api/users', { headers: { Cookie: `nikon_session=${latest.accessToken}` } })).status, 401);
  assert.equal((await request('/auth/me', { token: adminToken, admin: true })).status, 401);
  assert.equal((await request('/auth/logout', { method: 'POST', token: latest.accessToken })).status, 200);
  assert.equal((await request('/auth/me', { token: latest.accessToken })).status, 401);
  const fresh = await (await login('account')).json();
  store.userSessions.get(sha256(fresh.accessToken)).expiresAt = new Date(Date.now() - 1000).toISOString();
  assert.equal((await request('/auth/me', { token: fresh.accessToken })).status, 401);
  assert.equal(first.user.id, fresh.user.id);
});

test('administrator search matches username and safe user views exclude credentials from every response', async t => {
  const { register, request, store } = await fixture(t);
  const first = await (await register('Search_Me')).json();
  store.userRows.get(first.user.id).nickname = '照片用户';
  const page = await (await request('/api/users?q=SEARCH_ME', { admin: true })).json();
  assert.equal(page.total, 1); assert.equal(page.items[0].provider, 'password'); assert.equal(page.items[0].username, 'search_me');
  const detail = await (await request(`/api/users/${first.user.id}`, { admin: true })).json();
  assert.equal(detail.user.qqOpenIdMasked, '');
  for (const value of [page, detail]) {
    assert.ok(!JSON.stringify(value).includes('scrypt:')); assert.ok(!JSON.stringify(value).includes('password_hash'));
    assert.ok(!JSON.stringify(value).includes(first.accessToken));
  }
});

test('persistent per-account throttling applies to registrations and login without consuming admin attempts', async t => {
  const { login, register, request, store } = await fixture(t);
  for (let i = 0; i < 8; i++) assert.equal((await login('missing')).status, 401);
  const limited = await login('missing'); assert.equal(limited.status, 429); assert.equal(limited.headers.get('retry-after'), '900');
  assert.equal((await limited.json()).error.code, 'USER_AUTH_THROTTLED');
  for (let i = 0; i < 8; i++) assert.equal((await register('no')).status, 400);
  assert.equal((await register('no')).status, 429);
  assert.equal(store.attempts.size, 0); assert.equal((await request('/api/users', { admin: true })).status, 200);
});

test('new usernames share the same per-instance attempt budget', async t => {
  const { request } = await fixture(t);
  for (let i = 0; i < 40; i++) {
    assert.equal((await request('/auth/register', { method: 'POST', body: { username: `9invalid${i}`, password } })).status, 400);
  }
  const next = await request('/auth/register', { method: 'POST', body: { username: 'validname', password } });
  assert.equal(next.status, 429); assert.equal((await next.json()).error.code, 'USER_AUTH_BUSY');
});

test('registration and login share a two-operation concurrency cap', async t => {
  const { register, store } = await fixture(t);
  let entered = 0, signal, unblock;
  const bothEntered = new Promise(resolve => { signal = resolve; }), blocker = new Promise(resolve => { unblock = resolve; });
  const original = store.registerPassword.bind(store);
  store.registerPassword = async (...args) => { if (++entered === 2) signal(); await blocker; return original(...args); };
  const first = register('first_account'), second = register('second_account');
  await bothEntered;
  const rejected = await register('third_account'); assert.equal(rejected.status, 429);
  assert.equal((await rejected.json()).error.code, 'USER_AUTH_BUSY');
  unblock(); assert.equal((await first).status, 201); assert.equal((await second).status, 201);
});

test('CloudBase password adapter only queries server credentials and sends opaque hashes to transactional RPCs', async () => {
  const calls = [];
  const store = new CloudBaseStore({ environmentId: 'fixture', serviceKey: 'synthetic-server-key', fetchImpl: async (url, options) => {
    calls.push({ url: url.href, method: options.method, body: options.body === undefined ? null : JSON.parse(options.body) });
    return Response.json(url.pathname.endsWith('/nikon_password_credentials') ? [] : {});
  } });
  assert.equal(await store.passwordCredential('test_user'), null);
  assert.match(calls[0].url, /nikon_password_credentials\?username=eq\.test_user&select=username,user_id,password_hash&limit=1$/);
  await store.passwordAuthAllowed(sha256('fixture'), false);
  assert.match(calls[1].url, /rpc\/nikon_password_auth_allowed$/); assert.equal(calls[1].body.p_registration, false);
  await store.registerPassword('test_user', 'encoded-fixture-hash', sha256('fixture-token'), '2026-10-25T00:00:00Z');
  await store.signInPassword('test_user', 'encoded-fixture-hash', sha256('fixture-token-2'), '2026-10-25T00:00:00Z');
  assert.match(calls[2].url, /rpc\/nikon_register_password$/); assert.match(calls[3].url, /rpc\/nikon_sign_in_password$/);
  assert.equal(calls[3].body.p_verified_hash, 'encoded-fixture-hash'); assert.ok(!Object.hasOwn(calls[3].body, 'password'));
  await store.clearPasswordAttempts(sha256('fixture'));
  assert.equal(calls[4].method, 'DELETE'); assert.match(calls[4].url, /password-login%3A/);
});
