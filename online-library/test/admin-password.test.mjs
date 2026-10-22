import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHandler } from '../server/app.mjs';
import { CloudBaseStore } from '../server/cloudbase.mjs';
import { hashPassword, verifyPassword, sha256 } from '../server/auth.mjs';
import { MemoryStore } from './memory-store.mjs';

const password = 'Synthetic-original-password-29';
const newPassword = '  自选新密码 with spaces 42  ';
const initialHash = await hashPassword(password);
const changedHash = await hashPassword(newPassword);

async function fixture(t) {
  const store = new MemoryStore();
  for (const username of ['admin', 'other']) store.admins.set(username, { username, password_hash: initialHash });
  const server = createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const origin = `http://127.0.0.1:${server.address().port}`;
  server.on('request', createHandler({ store, storage: {}, environmentId: 'local-only', publicBaseUrl: origin, secureCookies: false }));
  const request = (path, { method = 'GET', body, session, headers = {} } = {}) => fetch(origin + '/preset-library' + path, {
    method, headers: { ...(session ? { Cookie: session.cookie, 'X-CSRF-Token': session.csrfToken } : {}),
      ...(method !== 'GET' ? { Origin: origin, 'Content-Type': 'application/json' } : {}), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const login = async (username = 'admin', selectedPassword = password) => {
    const response = await request('/api/login', { method: 'POST', body: { username, password: selectedPassword } });
    return { response, cookie: response.headers.get('set-cookie')?.split(';')[0], ...await response.json() };
  };
  const change = (session, body = {}, headers = {}) => request('/api/password', { method: 'POST', session,
    body: { currentPassword: password, newPassword, confirmPassword: newPassword, ...body }, headers });
  return { store, request, login, change };
}

test('admin password change persists only a hash, preserves whitespace and revokes every own session', async t => {
  const { store, request, login, change } = await fixture(t);
  const first = await login(), second = await login(), other = await login('other');
  const response = await change(first, { username: 'other' });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true });
  assert.match(response.headers.get('set-cookie'), /nikon_session=;.*HttpOnly;.*Max-Age=0/);
  const saved = store.admins.get('admin').password_hash;
  assert.notEqual(saved, initialHash);
  assert.equal(await verifyPassword(newPassword, saved), true);
  assert.equal(await verifyPassword(newPassword.trim(), saved), false);
  assert.equal(store.admins.get('other').password_hash, initialHash);
  for (const session of [first, second]) assert.equal((await request('/api/presets', { session })).status, 401);
  assert.equal((await request('/api/presets', { session: other })).status, 200);
  assert.equal((await login('admin', password)).response.status, 401);
  assert.equal((await login('admin', newPassword)).response.status, 200);
});

test('admin password changes require a live admin cookie, allowed Origin and exact CSRF', async t => {
  const { store, login, change } = await fixture(t);
  const session = await login();
  assert.equal((await change(null)).status, 401);
  assert.equal((await change(session, {}, { Origin: 'https://foreign.invalid' })).status, 403);
  assert.equal((await change(session, {}, { 'X-CSRF-Token': '' })).status, 403);
  assert.equal((await change(session, {}, { 'X-CSRF-Token': 'x'.repeat(43) })).status, 403);
  store.admins.get('admin').disabled = true;
  assert.equal((await change(session)).status, 401);
  store.admins.get('admin').disabled = false;
  store.sessions.get(sha256(session.cookie.split('=')[1])).expires_at = new Date(0).toISOString();
  assert.equal((await change(session)).status, 401);
  assert.equal(store.admins.get('admin').password_hash, initialHash);
});

test('exactly eight digits are enough for an administrator password; seven characters are rejected', async t => {
  const { login, change } = await fixture(t);
  const session = await login();
  const short = await change(session, { newPassword: '1234567', confirmPassword: '1234567' });
  assert.equal(short.status, 400); assert.equal((await short.json()).error.code, 'INVALID_PASSWORD');
  const changed = await change(session, { newPassword: '12345678', confirmPassword: '12345678' });
  assert.equal(changed.status, 200);
  assert.equal((await login('admin', '12345678')).response.status, 200);
});

test('password policy, confirmation and body bounds fail without changing credentials or logging out', async t => {
  const { store, request, login, change } = await fixture(t);
  const session = await login();
  for (const [body, code] of [
    [{ currentPassword: '' }, 'CURRENT_PASSWORD_REQUIRED'],
    [{ currentPassword: 'x'.repeat(257) }, 'CURRENT_PASSWORD_REQUIRED'],
    [{ newPassword: 'short', confirmPassword: 'short' }, 'INVALID_PASSWORD'],
    [{ newPassword: 'x'.repeat(257), confirmPassword: 'x'.repeat(257) }, 'INVALID_PASSWORD'],
    [{ newPassword: null }, 'INVALID_PASSWORD'],
    [{ confirmPassword: 'different' }, 'PASSWORD_MISMATCH'],
    [{ confirmPassword: undefined }, 'PASSWORD_MISMATCH'],
    [{ newPassword: password, confirmPassword: password }, 'PASSWORD_UNCHANGED'],
    [{ currentPassword: 'incorrect' }, 'CURRENT_PASSWORD_INCORRECT']
  ]) {
    const response = await change(session, body);
    assert.equal(response.status, 400); assert.equal((await response.json()).error.code, code);
  }
  assert.equal((await change(session, { padding: 'x'.repeat(4096) })).status, 413);
  assert.equal((await change(session, {}, { 'Content-Type': 'text/plain' })).status, 415);
  assert.equal(store.admins.get('admin').password_hash, initialHash);
  assert.equal((await request('/api/presets', { session })).status, 200);
});

test('current-password guessing is persistently limited across sessions, independently of admin login', async t => {
  const { store, login, change } = await fixture(t);
  const first = await login(), second = await login();
  for (let index = 0; index < 8; index++) assert.equal((await change(index % 2 ? first : second, { currentPassword: 'incorrect' })).status, 400);
  const limited = await change(second);
  assert.equal(limited.status, 429); assert.equal(limited.headers.get('retry-after'), '900');
  assert.equal((await limited.json()).error.code, 'PASSWORD_THROTTLED');
  assert.equal((await login()).response.status, 200);
  assert.equal(store.admins.get('admin').password_hash, initialHash);
});

test('a login verified against a replaced credential cannot issue a fresh admin session', async t => {
  const { store, login } = await fixture(t);
  const original = store.findAdmin.bind(store);
  store.findAdmin = async username => {
    const snapshot = await original(username);
    store.admins.get(username).password_hash = changedHash;
    return snapshot;
  };
  assert.equal((await login()).response.status, 401);
  assert.equal(store.sessions.size, 0);
});

test('a password change cannot overwrite concurrent credentials or proceed with a revoked session', async t => {
  const { store, login, change } = await fixture(t);
  const session = await login(), original = store.changeAdminPassword.bind(store);
  store.changeAdminPassword = async (...args) => {
    store.admins.get('admin').password_hash = changedHash;
    return original(...args);
  };
  assert.equal((await change(session)).status, 409);
  assert.equal(store.admins.get('admin').password_hash, changedHash);
  store.admins.get('admin').password_hash = initialHash;
  store.changeAdminPassword = async (...args) => { store.sessions.clear(); return original(...args); };
  assert.equal((await change(session)).status, 401);
  assert.equal(store.admins.get('admin').password_hash, initialHash);
});

test('CloudBase admin password RPCs send only hashes and map transactional conflicts safely', async () => {
  const calls = []; let failure;
  const store = new CloudBaseStore({ environmentId: 'synthetic', serviceKey: 'local-only-fixture', fetchImpl: async (url, options) => {
    calls.push({ path: url.pathname, body: JSON.parse(options.body) });
    return Response.json(failure ? { message: failure } : { ok: true }, { status: failure ? 400 : 200 });
  } });
  await store.signInAdmin({ username: 'admin', token_hash: 'token-hash', csrf_token: 'csrf', expires_at: 'expiry' }, initialHash);
  await store.changeAdminPassword('admin', 'session-hash', initialHash, changedHash);
  assert.match(calls[0].path, /rpc\/nikon_sign_in_admin$/);
  assert.deepEqual(calls[1].body, { p_username: 'admin', p_session_hash: 'session-hash', p_verified_hash: initialHash, p_password_hash: changedHash });
  assert.match(calls[1].path, /rpc\/nikon_change_admin_password$/);
  assert.ok(!JSON.stringify(calls).includes(newPassword)); assert.ok(!JSON.stringify(calls).includes(password));
  for (const [message, status] of [['PASSWORD_CONFLICT', 409], ['LOGIN_REQUIRED', 401], ['LOGIN_FAILED', 401]]) {
    failure = message;
    await assert.rejects(store.changeAdminPassword('admin', 'session-hash', initialHash, changedHash), error => error.code === message && error.status === status);
  }
});
