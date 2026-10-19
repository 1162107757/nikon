// Public, credential-free checks. No provider token, user account or session is created.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { ENV_ID, PUBLIC_ORIGIN } from './cloud-tools.mjs';

const args = process.argv.slice(2);
if (args.length !== 1 || !['--expect-configured', '--expect-unconfigured'].includes(args[0])) {
  throw new Error('请明确传入 --expect-configured 或 --expect-unconfigured。');
}
const configured = args[0] === '--expect-configured';
const base = PUBLIC_ORIGIN + '/preset-library', checks = [];
function passed(name) { checks.push({ name, passed: true }); }
async function request(path, { method = 'GET', body, headers = {} } = {}) {
  return fetch(base + path, { method, redirect: 'manual', signal: AbortSignal.timeout(20000),
    headers: { Accept: 'application/json', 'Accept-Encoding': 'identity',
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body) });
}
const health = await request('/health'); assert.equal(health.status, 200);
const metadata = await health.json(); assert.equal(metadata.environmentId, ENV_ID); assert.equal(metadata.configured, true);
passed('expected environment and backend ready');
const invalid = await request('/auth/qq', { method: 'POST', body: { accessToken: '' } });
assert.equal(invalid.status, configured ? 400 : 503);
assert.equal((await invalid.json()).error?.code, configured ? 'qq_invalid_token' : 'qq_not_configured');
passed(configured ? 'QQ configured; malformed token rejected before Tencent or account creation' : 'QQ explicitly unconfigured');
const foreign = await request('/auth/qq', { method: 'POST', body: { accessToken: '' }, headers: { Origin: 'https://foreign.example.test' } });
assert.equal(foreign.status, 403); assert.equal((await foreign.json()).error?.code, 'ORIGIN_REJECTED');
passed('foreign browser origin rejected');
assert.equal((await request('/auth/me')).status, 401); passed('App profile still requires its separate Bearer session');
assert.equal((await request('/api/users')).status, 401); passed('user administration still requires an administrator session');
const output = new URL('../build/', import.meta.url); await mkdir(output, { recursive: true });
await writeFile(new URL('qq-verification.json', output), JSON.stringify({ environmentId: ENV_ID,
  verifiedAt: new Date().toISOString(), passed: true, qqConfigured: configured, checks,
  qqLiveLogin: 'not exercised: real mobile authorization still required', userDataCreated: false }, null, 2) + '\n');
console.log(`QQ public endpoint acceptance passed: ${checks.length} checks; no user data or sessions created; mobile authorization not exercised.`);
