import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createHandler } from '../server/app.mjs';
import { hashPassword, randomToken, sha256 } from '../server/auth.mjs';
import { syntheticNcp } from './memory-store.mjs';
import { ReleaseMemoryStore } from './release-memory-store.mjs';
import { adminPreviewUrl } from '../admin/assets.js';

const legacyOrigin = 'https://legacy.example.test';
const customOrigin = 'https://library.example.test';
const password = 'Synthetic-domain-test-password';
const passwordHash = await hashPassword(password);

async function fixture(t, allowedBrowserOrigins) {
  const store = new ReleaseMemoryStore(), objects = new Map();
  store.admins.set('admin', { username: 'admin', password_hash: passwordHash });
  const handler = createHandler({ store, storage: { put: async (path, data) => objects.set(path, data), get: async path => objects.get(path) },
    environmentId: 'local-domain-test', publicBaseUrl: legacyOrigin, allowedBrowserOrigins,
    challengeService: { issue: async () => ({ fixture: 'domain-origin-check' }) } });
  const server = createServer(handler);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const localOrigin = `http://127.0.0.1:${server.address().port}`;
  const request = (path, { method = 'GET', origin, headers = {}, body, raw = false } = {}) => fetch(localOrigin + '/preset-library' + path, {
    method, redirect: 'manual', headers: { ...(origin === undefined ? {} : { Origin: origin }),
      ...(body === undefined ? {} : { 'Content-Type': raw ? 'application/octet-stream' : 'application/json' }), ...headers },
    body: body === undefined ? undefined : raw ? body : JSON.stringify(body)
  });
  async function adminSession() {
    const token = randomToken(), csrf = randomToken();
    await store.createSession({ token_hash: sha256(token), username: 'admin', csrf_token: csrf,
      expires_at: new Date(Date.now() + 60000).toISOString() });
    return { token, csrf, cookie: `__Host-nikon_session=${token}` };
  }
  return { store, request, adminSession };
}

async function rejectedOrigin(response) {
  assert.equal(response.status, 403);
  assert.equal((await response.json()).error.code, 'ORIGIN_REJECTED');
}

test('default browser policy still trusts only the canonical public origin', async t => {
  const { request } = await fixture(t);
  assert.equal((await request('/auth/challenge', { origin: legacyOrigin })).status, 200);
  await rejectedOrigin(await request('/auth/challenge', { origin: customOrigin }));
  await rejectedOrigin(await request('/api/login', { method: 'POST', origin: customOrigin,
    body: { username: 'admin', password } }));
});

test('explicit custom and legacy browser origins can log in with unchanged secure cookies and canonical URLs', async t => {
  const { request, store } = await fixture(t, [customOrigin]);
  for (const origin of [legacyOrigin, customOrigin]) {
    const response = await request('/api/login', { method: 'POST', origin, body: { username: 'admin', password } });
    assert.equal(response.status, 200);
    const cookie = response.headers.get('set-cookie');
    assert.match(cookie, /^__Host-nikon_session=[A-Za-z0-9_-]{43}; Path=\/; HttpOnly; SameSite=Strict; Secure; Max-Age=28800$/);
    assert.doesNotMatch(cookie, /Domain=/i);
    const session = await response.json();
    assert.equal(session.authenticated, true);
    assert.equal(session.catalogUrl, legacyOrigin + '/preset-library/catalog.json');
    assert.match(session.csrfToken, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(response.headers.get('access-control-allow-origin'), null);
  }
  assert.equal(store.sessions.size, 2);
  const page = await request('/admin/', { origin: customOrigin });
  assert.equal(page.status, 200);
  assert.match(page.headers.get('content-security-policy'), /connect-src 'self'/);
  assert.match(page.headers.get('strict-transport-security'), /max-age=31536000/);
});

test('browser origins match exactly and cannot be admitted through Host or forwarding headers', async t => {
  const { request } = await fixture(t, [customOrigin]);
  for (const origin of ['https://evil.library.example.test', customOrigin + '.evil.test',
    'https://library-example.test', 'http://library.example.test', customOrigin + ':8443',
    customOrigin + '/', 'null', legacyOrigin + '.evil.test']) {
    const headers = { Host: 'library.example.test', 'X-Forwarded-Host': 'library.example.test',
      Forwarded: 'host=library.example.test;proto=https' };
    await rejectedOrigin(await request('/api/login', { method: 'POST', origin, headers, body: { username: 'admin', password } }));
    await rejectedOrigin(await request('/auth/challenge', { origin, headers }));
  }
});

test('custom browser entrypoints still require authenticated sessions and exact CSRF for admin writes', async t => {
  const { request, store, adminSession } = await fixture(t, [customOrigin]);
  assert.equal((await request('/api/logout', { method: 'POST', origin: customOrigin })).status, 401);
  const session = await adminSession();
  for (const csrf of [undefined, 'incorrect-csrf-token']) {
    const response = await request('/api/logout', { method: 'POST', origin: customOrigin,
      headers: { Cookie: session.cookie, ...(csrf ? { 'X-CSRF-Token': csrf } : {}) } });
    assert.equal(response.status, 403);
    assert.equal((await response.json()).error.code, 'CSRF_REJECTED');
    assert.equal(store.sessions.size, 1);
  }
  await rejectedOrigin(await request('/api/logout', { method: 'POST',
    headers: { Cookie: session.cookie, 'X-CSRF-Token': session.csrf, Host: 'library.example.test' } }));
  assert.equal(store.sessions.size, 1);
  const response = await request('/api/logout', { method: 'POST', origin: customOrigin,
    headers: { Cookie: session.cookie, 'X-CSRF-Token': session.csrf } });
  assert.equal(response.status, 200);
  assert.equal(store.sessions.size, 0);
  assert.match(response.headers.get('set-cookie'), /SameSite=Strict; Secure; Max-Age=0$/);
});

test('native authentication without Origin remains supported alongside both configured browser origins', async t => {
  const { request, store } = await fixture(t, [customOrigin]);
  const id = '12345678-1234-4234-8234-123456789abc';
  store.userRows.set(id, { id, status: 'active', username: 'fixture-user' });
  for (const origin of [undefined, legacyOrigin, customOrigin]) {
    assert.equal((await request('/auth/challenge', { origin })).status, 200);
    const token = randomToken();
    store.issueUserSession(id, sha256(token), new Date(Date.now() + 60000).toISOString());
    const response = await request('/auth/logout', { method: 'POST', origin,
      headers: { Authorization: `Bearer ${token}` } });
    assert.equal(response.status, 200);
    assert.equal(store.userSessions.has(sha256(token)), false);
  }
});

test('domain onboarding preserves canonical upload and APK update URLs for existing Android clients', async t => {
  const { request, store, adminSession } = await fixture(t, [customOrigin]);
  const session = await adminSession();
  const response = await request('/api/uploads?kind=ncp&filename=domain.NCP', { method: 'POST', origin: customOrigin,
    headers: { Cookie: session.cookie, 'X-CSRF-Token': session.csrf }, body: syntheticNcp(), raw: true });
  assert.equal(response.status, 201);
  const { asset } = await response.json();
  assert.equal(new URL(asset.url).origin, legacyOrigin);
  assert.equal(new URL(asset.adminUrl).origin, legacyOrigin);
  const id = '12345678-1234-4234-8234-123456789abc';
  store.releaseRows.push({ id, status: 'published', channel: 'beta', version_code: 99, min_sdk: 26,
    package_name: 'com.nikonpreset.usbprobe', version_name: 'fixture', byte_length: 123,
    sha256: 'a'.repeat(64), signer_sha256: 'b'.repeat(64), notes: 'Domain compatibility fixture' });
  const updates = await request('/releases/latest?channel=beta&versionCode=1&sdk=36', { origin: customOrigin });
  assert.equal(updates.status, 200);
  const metadata = await updates.json();
  assert.equal(metadata.updateAvailable, true);
  assert.equal(metadata.release.downloadUrl, `${legacyOrigin}/preset-library/releases/${id}/file`);
});

test('custom-domain sessions preview draft and published assets through their own private API route', async t => {
  const { request, store, adminSession } = await fixture(t, [customOrigin]);
  const session = await adminSession();
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGOQ8KgAAAFUANnsm43hAAAAAElFTkSuQmCC', 'base64');
  const uploaded = await request('/api/uploads?kind=preview&filename=domain-preview.png', { method: 'POST', origin: customOrigin,
    headers: { Cookie: session.cookie, 'X-CSRF-Token': session.csrf, 'Content-Type': 'image/png' }, body: png, raw: true });
  assert.equal(uploaded.status, 201);
  const { asset } = await uploaded.json();
  assert.equal(new URL(asset.adminUrl).origin, legacyOrigin);
  const privateUrl = new URL(adminPreviewUrl(asset, customOrigin + '/preset-library/admin/assets.js'));
  assert.equal(privateUrl.origin, customOrigin);
  const privatePath = privateUrl.pathname.slice('/preset-library'.length);
  for (const published of [false, true]) {
    if (published) store.publishedAssets.add(asset.id);
    assert.equal((await request(privatePath)).status, 401, 'private previews always require the current host session');
    const preview = await request(privatePath, { origin: customOrigin, headers: { Cookie: session.cookie } });
    assert.equal(preview.status, 200);
    assert.equal(preview.headers.get('content-type'), 'image/png');
    assert.equal(preview.headers.get('cache-control'), 'no-store');
    assert.deepEqual(Buffer.from(await preview.arrayBuffer()), png);
    assert.equal(new URL(asset.url).origin, legacyOrigin, 'published URLs remain unchanged');
  }
});

test('invalid custom-origin configuration fails closed before creating a request handler', () => {
  const configuration = { store: {}, storage: {}, publicBaseUrl: legacyOrigin };
  for (const value of [null, customOrigin, {}, [null], [123], [''], ['*'], ['https://*.example.test'],
    ['http://library.example.test'], ['https://user:secret@library.example.test'], [customOrigin + '/'],
    [customOrigin + '/preset-library'], [customOrigin + '?allow=1'], [customOrigin + '#fragment'],
    [customOrigin + ':443'], [' https://library.example.test'], ['https://LIBRARY.example.test']]) {
    assert.throws(() => createHandler({ ...configuration, allowedBrowserOrigins: value }),
      { name: 'TypeError', message: /ALLOWED_BROWSER_ORIGINS/ });
  }
  assert.doesNotThrow(() => createHandler({ ...configuration, allowedBrowserOrigins: [customOrigin, legacyOrigin, customOrigin] }));
});

test('environment origin lists reject malformed entries and trailing commas during startup', () => {
  for (const value of [customOrigin + '/', customOrigin + ',', customOrigin + ',https://*.example.test']) {
    const child = spawnSync(process.execPath, [fileURLToPath(new URL('../server/index.mjs', import.meta.url))], {
      env: { ...process.env, ALLOWED_BROWSER_ORIGINS: value, PORT: '0' }, encoding: 'utf8', timeout: 5000
    });
    assert.equal(child.error, undefined);
    assert.equal(child.status, 1);
    assert.match(child.stderr, /ALLOWED_BROWSER_ORIGINS/);
    assert.doesNotMatch(child.stdout, /listening/);
  }
});
