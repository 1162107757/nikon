import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { ENV_ID, PUBLIC_ORIGIN } from './cloud-tools.mjs';

// Credentials are sent only to this explicitly configured project domain after its health check.
const origin = 'https://huevia.cc', prefix = '/preset-library', base = origin + prefix;
const checks = [];
let cookie = '', csrf = '';
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const passed = (name, details = {}) => checks.push({ name, passed: true, ...details });
async function request(path, { method = 'GET', headers = {}, body } = {}) {
  return fetch(base + path, { method, redirect: 'manual', signal: AbortSignal.timeout(45000),
    headers: { ...(cookie ? { Cookie: cookie } : {}), ...headers }, body });
}
async function verify() {
  for (const host of [origin, PUBLIC_ORIGIN]) {
    const response = await fetch(host + prefix + '/health', { signal: AbortSignal.timeout(20000) });
    assert.equal(response.status, 200);
    const health = await response.json();
    assert.equal(health.environmentId, ENV_ID); assert.equal(health.configured, true);
    passed('HTTPS service healthy', { origin: host });
  }
  const http = await fetch('http://huevia.cc/', { redirect: 'manual', signal: AbortSignal.timeout(20000) });
  assert.ok([301, 302, 307, 308].includes(http.status)); assert.equal(http.headers.get('location'), origin + '/');
  passed('HTTP redirects to HTTPS');
  const root = await fetch(origin + '/', { redirect: 'manual', signal: AbortSignal.timeout(20000) });
  assert.equal(root.status, 302); assert.equal(root.headers.get('location'), prefix + '/admin/');
  passed('Domain root reaches management entry');
  for (const path of ['/admin/', '/download/', '/download/download.js', '/admin/assets.js']) {
    const response = await request(path); assert.equal(response.status, 200);
    if (path.endsWith('download.js')) assert.match(await response.text(), /releaseDownloadUrl/);
    passed('Page or module accessible', { path });
  }
  const catalogResponse = await request('/catalog.json'); assert.equal(catalogResponse.status, 200);
  const catalog = await catalogResponse.json(); assert.equal(catalog.version, 1); assert.ok(catalog.presets.length > 0);
  const legacyCatalog = await (await fetch(PUBLIC_ORIGIN + prefix + '/catalog.json')).json();
  assert.deepEqual(catalog, legacyCatalog);
  passed('New and legacy catalogs match', { presets: catalog.presets.length });
  assert.equal((await request('/api/presets')).status, 401); passed('Anonymous administration denied');
  for (const badOrigin of [undefined, 'https://huevia.cc.evil.example']) {
    const response = await request('/api/login', { method: 'POST', headers: {
      'Content-Type': 'application/json', ...(badOrigin ? { Origin: badOrigin } : {}) }, body: '{}' });
    assert.equal(response.status, 403); assert.equal((await response.json()).error.code, 'ORIGIN_REJECTED');
  }
  passed('Missing and foreign browser origins rejected');
  const auth = await request('/auth/me', { headers: { Origin: origin } });
  assert.equal(auth.status, 401); assert.equal((await auth.json()).error.code, 'USER_LOGIN_REQUIRED');
  passed('New origin accepted without granting authentication');
  for (const channel of ['stable', 'beta']) {
    const response = await request('/releases/latest?channel=' + channel); assert.equal(response.status, 200);
    const metadata = await response.json(), release = metadata.release;
    if (!release) { passed('Release channel available', { channel, release: null }); continue; }
    assert.equal(new URL(release.downloadUrl).origin, PUBLIC_ORIGIN);
    assert.match(release.id, /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/);
    const apk = await request(`/releases/${release.id}/file`); assert.equal(apk.status, 200);
    const bytes = Buffer.from(await apk.arrayBuffer());
    assert.equal(bytes.length, release.byteLength); assert.equal(digest(bytes), release.sha256);
    passed('Same-origin APK download matches published metadata', { channel, versionCode: release.versionCode,
      bytes: bytes.length, sha256: release.sha256, legacyMetadataPreserved: true });
  }
  const credentials = JSON.parse(await readFile(new URL('../.local/initial-admin.json', import.meta.url), 'utf8'));
  const login = await request('/api/login', { method: 'POST', headers: { Origin: origin, 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: credentials.username, password: credentials.password }) });
  assert.equal(login.status, 200);
  const header = login.headers.get('set-cookie');
  cookie = header?.match(/__Host-nikon_session=[A-Za-z0-9_-]{43}/)?.[0] || '';
  csrf = (await login.json()).csrfToken;
  assert.ok(cookie && csrf); assert.match(header, /HttpOnly/); assert.match(header, /SameSite=Strict/);
  assert.match(header, /Secure/); assert.doesNotMatch(header, /Domain=/i);
  passed('Administrator login uses secure host-only session');
  try {
    const response = await request('/api/presets'); assert.equal(response.status, 200);
    const { items } = await response.json(); assert.ok(Array.isArray(items));
    passed('Authenticated management read works', { count: items.length });
    const preview = items.find(item => /^preview-[a-f0-9]{64}$/.test(item.preview?.id || ''))?.preview;
    if (preview) {
      const response = await request(`/api/assets/${preview.id}/content`); assert.equal(response.status, 200);
      const bytes = Buffer.from(await response.arrayBuffer());
      assert.equal(bytes.length, preview.byteLength); assert.equal(digest(bytes), preview.sha256);
      passed('Private preview uses the new domain session');
    }
    const rejected = await request('/api/logout', { method: 'POST', headers: { Origin: origin } });
    assert.equal(rejected.status, 403); assert.equal((await rejected.json()).error.code, 'CSRF_REJECTED');
    passed('Authenticated writes still require CSRF');
  } finally {
    const logout = await request('/api/logout', { method: 'POST', headers: { Origin: origin, 'X-CSRF-Token': csrf } });
    assert.equal(logout.status, 200); passed('Verification session logged out');
  }
  assert.equal((await request('/api/presets')).status, 401); passed('Revoked session cannot be reused');
}
let failure;
try { await verify(); } catch (error) { failure = error; }
await writeFile(new URL('../build/domain-verification.json', import.meta.url), JSON.stringify({
  origin, environmentId: ENV_ID, verifiedAt: new Date().toISOString(), passed: !failure,
  checks, ...(failure ? { failure: { message: failure.message, code: failure.code } } : {})
}, null, 2) + '\n');
if (failure) throw failure;
console.log(`Domain verification passed: ${checks.length} checks; verification session revoked.`);
