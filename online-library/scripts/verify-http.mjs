import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { ENV_ID, PUBLIC_ORIGIN } from './cloud-tools.mjs';
const root = new URL('../', import.meta.url), base = PUBLIC_ORIGIN + '/preset-library';
const initial = JSON.parse(await readFile(new URL('.local/initial-admin.json', root), 'utf8'));
const checks = []; let cookie = '', csrfToken = '';
async function request(path, { method = 'GET', body, headers = {} } = {}) {
  return fetch(base + path, { method, redirect: 'manual', signal: AbortSignal.timeout(45000),
    headers: { Accept: 'application/json', 'Accept-Encoding': 'identity', ...(cookie ? { Cookie: cookie } : {}),
      ...(method === 'GET' ? {} : { Origin: PUBLIC_ORIGIN, 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken }), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body) });
}
function passed(name, extra = {}) { checks.push({ name, passed: true, ...extra }); }
try {
  const health = await request('/health'); assert.equal(health.status, 200);
  const metadata = await health.json(); assert.equal(metadata.environmentId, ENV_ID); assert.equal(metadata.configured, true); passed('cloud function ready');
  const html = await request('/admin/'); assert.equal(html.status, 200); assert.match(await html.text(), /管理后台/); passed('management page served');
  const anonymous = await request('/api/presets'); assert.equal(anonymous.status, 401); passed('management data requires login');
  const catalog = await request('/catalog.json'); assert.equal(catalog.status, 200);
  assert.ok(!catalog.headers.get('content-encoding') || catalog.headers.get('content-encoding') === 'identity');
  const manifest = await catalog.json(); assert.equal(manifest.version, 1); assert.ok(Array.isArray(manifest.presets)); passed('native-compatible anonymous catalog', { count: manifest.presets.length });
  const badOrigin = await request('/api/login', { method: 'POST', body: { username: initial.username, password: initial.password }, headers: { Origin: 'https://unauthorized.example.test' } });
  assert.equal(badOrigin.status, 403); passed('foreign origin blocked before login');
  const login = await request('/api/login', { method: 'POST', body: { username: initial.username, password: initial.password } }); assert.equal(login.status, 200);
  const setCookie = login.headers.get('set-cookie');
  const match = setCookie?.match(/__Host-nikon_session=([A-Za-z0-9_-]{43})/); assert.ok(match);
  cookie = `__Host-nikon_session=${match[1]}`;
  const session = await login.json(); csrfToken = session.csrfToken;
  assert.ok(/HttpOnly/i.test(setCookie), 'Session cookie must be HttpOnly');
  assert.ok(/Secure/i.test(setCookie), 'Session cookie must be Secure');
  assert.ok(/SameSite=Strict/i.test(setCookie), 'Session cookie must be SameSite=Strict');
  assert.equal(session.authenticated, true); passed('secure administrator session created');
  const presets = await request('/api/presets'); assert.equal(presets.status, 200); assert.ok(Array.isArray((await presets.json()).items)); passed('authenticated database access');
  const denied = await request('/api/logout', { method: 'POST', body: {}, headers: { 'X-CSRF-Token': 'invalid' } }); assert.equal(denied.status, 403); passed('CSRF required on mutations');
} finally {
  if (cookie && csrfToken) { const logout = await request('/api/logout', { method: 'POST', body: {} }); assert.equal(logout.status, 200); passed('verification session logged out'); }
}
const revoked = await request('/api/presets'); assert.equal(revoked.status, 401); passed('logged-out cookie cannot be replayed');
const revokedSession = await (await request('/api/session')).json(); assert.equal(revokedSession.authenticated, false); passed('logged-out database session is gone');
cookie = ''; const after = await request('/api/presets'); assert.equal(after.status, 401); passed('anonymous management access stays denied');
await writeFile(new URL('build/http-verification.json', root), JSON.stringify({ environmentId: ENV_ID, verifiedAt: new Date().toISOString(), checks }, null, 2) + '\n');
console.log(`Public HTTP acceptance passed: ${checks.length} checks; no presets or user files created.`);
