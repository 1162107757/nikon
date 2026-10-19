// Live acceptance without creating QQ accounts, identities or user sessions.
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { ENV_ID, PUBLIC_ORIGIN } from './cloud-tools.mjs';
const root = new URL('../', import.meta.url), base = PUBLIC_ORIGIN + '/preset-library';
const args = process.argv.slice(2);
if (args.some(arg => arg !== '--qq-configured')) throw new Error('仅支持 --qq-configured 参数。');
const qqConfigured = args.includes('--qq-configured');
const initial = JSON.parse(await readFile(new URL('.local/initial-admin.json', root), 'utf8'));
const checks = []; let cookie = '', csrfToken = '';
function passed(name) { checks.push({ name, passed: true }); }
async function request(path, { method = 'GET', body, anonymous = false, native = false, headers = {} } = {}) {
  return fetch(base + path, { method, redirect: 'manual', signal: AbortSignal.timeout(45000),
    headers: { Accept: 'application/json', 'Accept-Encoding': 'identity',
      ...(!anonymous && cookie ? { Cookie: cookie } : {}),
      ...(method === 'GET' ? {} : { 'Content-Type': 'application/json', ...(native ? {} : { Origin: PUBLIC_ORIGIN, 'X-CSRF-Token': csrfToken }) }), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body) });
}
try {
  const page = await request('/admin/', { anonymous: true }); assert.equal(page.status, 200);
  const html = await page.text(); assert.match(html, /id="sidebar"/); assert.match(html, /data-page="users"/);
  passed('deployed management page includes the sidebar and users destination');
  assert.equal((await request('/api/users', { anonymous: true })).status, 401); passed('user administration requires an administrator session');
  assert.equal((await request('/auth/me', { anonymous: true })).status, 401); passed('ordinary profile requires its separate Bearer session');
  const login = await request('/api/login', { method: 'POST', body: { username: initial.username, password: initial.password } });
  assert.equal(login.status, 200);
  const match = login.headers.get('set-cookie')?.match(/__Host-nikon_session=([A-Za-z0-9_-]{43})/);
  assert.ok(Boolean(match), 'Administrator Cookie was not issued'); cookie = `__Host-nikon_session=${match[1]}`;
  csrfToken = (await login.json()).csrfToken; assert.ok(/^[A-Za-z0-9_-]{43}$/.test(csrfToken)); passed('administrator session established');
  const listing = await request('/api/users?page=1&pageSize=20&status=all'); assert.equal(listing.status, 200);
  const data = await listing.json();
  assert.ok(Array.isArray(data.items)); assert.ok(Number.isInteger(data.total) && data.total >= 0);
  assert.equal(data.page, 1); assert.equal(data.pageSize, 20); passed('persistent user list supports server pagination');
  assert.ok(['total', 'active', 'disabled'].every(key => Number.isInteger(data.summary?.[key]) && data.summary[key] >= 0));
  assert.equal(data.summary.total, data.summary.active + data.summary.disabled); passed('user summary is internally consistent');
  assert.equal(data.qqLogin?.configured, qqConfigured); passed(qqConfigured ? 'QQ server configuration is enabled' : 'QQ integration honestly remains unconfigured');
  const empty = await request('/api/users?q=' + encodeURIComponent('synthetic-read-only-check-' + randomUUID()));
  assert.equal(empty.status, 200); assert.equal((await empty.json()).total, 0); passed('bounded user search is connected to the database');
  assert.equal((await request('/api/users?status=invalid')).status, 400); passed('invalid user filter is rejected');
  const missingId = randomUUID(); assert.equal((await request(`/api/users/${missingId}`)).status, 404); passed('unknown user detail returns a bounded not-found response');
  assert.equal((await request(`/api/users/${missingId}/status`, { method: 'POST',
    body: { status: 'disabled', expectedUpdatedAt: '2026-01-01T00:00:00.000Z' }, headers: { 'X-CSRF-Token': 'invalid' } })).status, 403);
  passed('user status mutation requires administrator CSRF protection');
  assert.equal((await request('/auth/me')).status, 401); passed('administrator Cookie cannot authenticate as an App user');
  assert.equal((await request('/auth/me', { anonymous: true, headers: { Authorization: 'Bearer ' + 'a'.repeat(43) } })).status, 401);
  passed('unknown App Bearer token cannot read a profile');
  const qq = await request('/auth/qq', { method: 'POST', anonymous: true, native: true, body: { accessToken: '' } });
  if (qqConfigured) {
    assert.equal(qq.status, 400); assert.equal((await qq.json()).error?.code, 'qq_invalid_token');
    passed('configured QQ login rejects a malformed token before Tencent or registration');
  } else {
    assert.equal(qq.status, 503); assert.equal((await qq.json()).error?.code, 'qq_not_configured');
    passed('unconfigured mobile QQ login fails explicitly without fake registration');
  }
} finally {
  if (cookie && csrfToken) {
    const logout = await request('/api/logout', { method: 'POST', body: {} }); assert.equal(logout.status, 200);
    passed('verification administrator session logged out');
  }
}
assert.equal((await request('/api/users')).status, 401); passed('logged-out administrator Cookie cannot read users');
await writeFile(new URL('build/user-management-verification.json', root), JSON.stringify({
  environmentId: ENV_ID, verifiedAt: new Date().toISOString(), passed: true, checks,
  qqConfigured, qqLiveLogin: 'not exercised: real mobile authorization still required', userDataCreated: false }, null, 2) + '\n');
console.log(`User administration acceptance passed: ${checks.length} checks; no QQ users or App sessions created.`);
