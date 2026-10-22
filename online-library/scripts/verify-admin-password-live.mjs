// Explicit live smoke test: only a uniquely named temporary administrator is changed and cleaned up.
// Never print credentials, cookies, provider bodies or SQL diagnostics.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { randomBytes, randomInt } from 'node:crypto';
import { hashPassword, sha256 } from '../server/auth.mjs';
import { ENV_ID, PUBLIC_ORIGIN, executeSql, sqlRows, quoteSql } from './cloud-tools.mjs';

const root = new URL('../', import.meta.url), stateFile = new URL('.local/admin-password-verification-state.json', root);
const minimumEight = process.argv.includes('--minimum-eight');
const reportFile = new URL(minimumEight ? 'build/admin-password-8-live-verification.json' : 'build/admin-password-live-verification.json', root), origin = 'https://huevia.cc', base = origin + '/preset-library';
const checks = []; let state, cleanupComplete = false, failed = false, stage = 'initialization';
class VerificationError extends Error {}
const ensure = (condition, message) => { if (!condition) throw new VerificationError(message); };
const pass = name => checks.push({ name, passed: true });
async function saveState() { await writeFile(stateFile, JSON.stringify(state, null, 2) + '\n', { mode: 0o600 }); }
function validateOwner() {
  ensure(state?.environment === ENV_ID && /^qa_admin_pwd_[a-f0-9]{24}$/.test(state.username || '')
    && Array.isArray(state.hashes) && state.hashes.length === 2 && state.hashes.every(hash => /^scrypt:32768:8:1:[a-f0-9]{32}:[a-f0-9]{128}$/.test(hash)), 'Temporary account recovery marker is invalid.');
}
async function cleanup() {
  validateOwner(); stage = 'temporary administrator cleanup';
  const name = quoteSql(state.username);
  const ownedRows = sqlRows(executeSql(`SELECT password_hash,created_at::text AS created_at FROM public.nikon_admins WHERE username=${name}`));
  ensure(ownedRows.length <= 1, 'Unexpected temporary administrator count.');
  let ownership = 'false';
  if (ownedRows.length) {
    const row = ownedRows[0], created = Date.parse(row.created_at), started = Date.parse(state.startedAt);
    // The server generates a fresh salt when changing a password, so the new hash differs
    // from the precomputed test hash. Bind cleanup to this random name and exact creation time.
    const knownCreation = state.createdAt ? row.created_at === state.createdAt
      : Number.isFinite(created) && Number.isFinite(started) && Math.abs(created - started) <= 120000;
    ensure(knownCreation || state.hashes.includes(row.password_hash), 'Temporary administrator ownership could not be established.');
    ensure(/^scrypt:32768:8:1:[a-f0-9]{32}:[a-f0-9]{128}$/.test(row.password_hash), 'Temporary credential format is invalid.');
    ownership = `created_at=${quoteSql(row.created_at)}::timestamptz AND password_hash=${quoteSql(row.password_hash)}`;
    state.createdAt = row.created_at; await saveState();
  }
  const keys = [sha256(state.username), sha256('admin-password:' + state.username)].map(quoteSql).join(',');
  executeSql(`DO $admin_password_cleanup$ BEGIN
    IF EXISTS (SELECT 1 FROM public.nikon_admins WHERE username=${name} AND NOT (${ownership})) THEN
      RAISE EXCEPTION 'VERIFICATION_OWNER_MISMATCH';
    END IF;
    DELETE FROM public.nikon_sessions WHERE username=${name};
    DELETE FROM public.nikon_login_attempts WHERE attempt_key IN (${keys});
    DELETE FROM public.nikon_admins WHERE username=${name} AND (${ownership});
  END $admin_password_cleanup$;`);
  const rows = sqlRows(executeSql(`SELECT
    (SELECT count(*)::int FROM public.nikon_admins WHERE username=${name}) AS admins,
    (SELECT count(*)::int FROM public.nikon_sessions WHERE username=${name}) AS sessions,
    (SELECT count(*)::int FROM public.nikon_login_attempts WHERE attempt_key IN (${keys})) AS attempts`));
  ensure(rows.length === 1 && Object.values(rows[0]).every(value => Number(value) === 0), 'Temporary account cleanup did not verify.');
  state.cleanupComplete = true; await saveState(); cleanupComplete = true; pass('Temporary administrator, sessions and attempts removed and verified absent');
}
async function cleanupWithRetry() {
  for (let attempt = 0; attempt < 3; attempt++) {
    try { await cleanup(); return; }
    catch (error) {
      if (error instanceof VerificationError || attempt === 2) throw error;
      // The control API can fail transiently after a committed request; cleanup is idempotent.
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
  }
}
async function request(path, { method = 'GET', body, session, headers = {} } = {}) {
  return fetch(base + path, { method, redirect: 'manual', signal: AbortSignal.timeout(45000),
    headers: { ...(session ? { Cookie: session.cookie, 'X-CSRF-Token': session.csrfToken } : {}),
      ...(method !== 'GET' ? { Origin: origin, 'Content-Type': 'application/json' } : {}), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body) });
}
async function login(password) {
  const response = await request('/api/login', { method: 'POST', body: { username: state.username, password } });
  if (response.status !== 200) {
    const data = await response.json(); return { status: response.status, code: data?.error?.code };
  }
  const header = response.headers.get('set-cookie'), data = await response.json();
  const cookie = header?.match(/__Host-nikon_session=[A-Za-z0-9_-]{43}/)?.[0];
  ensure(cookie && data.authenticated && /^[A-Za-z0-9_-]{43}$/.test(data.csrfToken), 'Temporary administrator login did not return a valid session.');
  ensure(/HttpOnly/i.test(header) && /Secure/i.test(header) && /SameSite=Strict/i.test(header) && !/Domain=/i.test(header), 'Secure host-only cookie attributes changed.');
  return { status: 200, cookie, csrfToken: data.csrfToken };
}
try {
  await mkdir(new URL('.local/', root), { recursive: true });
  if (process.argv[2] === '--cleanup-only') {
    state = JSON.parse(await readFile(stateFile, 'utf8')); await cleanupWithRetry();
  } else {
    ensure(process.argv[2] === '--verify', 'Use --verify for the approved live smoke test.');
    let prior; try { prior = JSON.parse(await readFile(stateFile, 'utf8')); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    ensure(!prior || prior.cleanupComplete, 'A previous temporary administrator needs --cleanup-only first.');
    for (const host of [origin, PUBLIC_ORIGIN]) {
      const response = await fetch(host + '/preset-library/health', { signal: AbortSignal.timeout(20000) });
      ensure(response.status === 200, 'Production health endpoint failed.');
      const health = await response.json(); ensure(health.configured && health.environmentId === ENV_ID, 'Production environment does not match.');
    }
    pass('Primary and legacy HTTPS origins report the configured target environment');
    for (const path of ['admin/index.html', 'admin/app.js', 'admin/api.js', 'admin/styles.css']) {
      const response = await request('/' + path);
      ensure(response.status === 200 && sha256(Buffer.from(await response.arrayBuffer())) === sha256(await readFile(new URL(path, root))), 'Published password UI differs from the reviewed files.');
    }
    pass('All four published UI files exactly match the reviewed source');
    const catalog = await request('/catalog.json'); ensure(catalog.status === 200, 'Public catalog failed.');
    const catalogBytes = Buffer.from(await catalog.arrayBuffer()), catalogHash = sha256(catalogBytes);
    const manifest = JSON.parse(catalogBytes); ensure(manifest.version === 1 && manifest.presets.length > 0, 'Public catalog is invalid.');
    ensure((await request('/api/password', { method: 'POST', body: {} })).status === 401, 'Anonymous password changes were not rejected.');
    pass('Anonymous password changes require administrator authentication');
    const firstPassword = randomBytes(32).toString('base64url'), nextPassword = minimumEight
      ? String(randomInt(10000000, 100000000)) : '  ' + randomBytes(24).toString('base64url') + ' 自选  ';
    state = { environment: ENV_ID, username: 'qa_admin_pwd_' + randomBytes(12).toString('hex'),
      hashes: [await hashPassword(firstPassword), await hashPassword(nextPassword)], startedAt: new Date().toISOString(), cleanupComplete: false };
    await saveState();
    stage = 'temporary administrator creation';
    executeSql(`INSERT INTO public.nikon_admins(username,password_hash) VALUES (${quoteSql(state.username)},${quoteSql(state.hashes[0])})`);
    // ExecutePGSql does not expose INSERT ... RETURNING rows consistently; confirm through a separate SELECT.
    const inserted = sqlRows(executeSql(`SELECT created_at::text AS created_at FROM public.nikon_admins WHERE username=${quoteSql(state.username)} AND password_hash=${quoteSql(state.hashes[0])}`));
    ensure(inserted.length === 1 && Number.isFinite(Date.parse(inserted[0].created_at)), 'Temporary administrator was not created.');
    state.createdAt = inserted[0].created_at; await saveState();
    stage = 'temporary administrator login';
    const first = await login(firstPassword), second = await login(firstPassword);
    ensure(first.status === 200 && second.status === 200, `Temporary login failed: ${first.status}/${first.code || 'none'}, ${second.status}/${second.code || 'none'}.`);
    ensure(first.status === 200 && second.status === 200 && first.cookie !== second.cookie, 'Two distinct temporary sessions were not created.');
    pass('Temporary administrator signs in with distinct secure sessions');
    const body = { currentPassword: firstPassword, newPassword: nextPassword, confirmPassword: nextPassword };
    ensure((await request('/api/password', { method: 'POST', session: first, body, headers: { Origin: 'https://foreign.invalid' } })).status === 403, 'Foreign Origin was not rejected.');
    ensure((await request('/api/password', { method: 'POST', session: first, body, headers: { 'X-CSRF-Token': '' } })).status === 403, 'Missing CSRF was not rejected.');
    pass('Foreign origins and missing CSRF cannot change passwords');
    for (const [override, code] of [
      [{ currentPassword: 'incorrect-synthetic-only' }, 'CURRENT_PASSWORD_INCORRECT'],
      [{ newPassword: '1234567', confirmPassword: '1234567' }, 'INVALID_PASSWORD'],
      [{ confirmPassword: 'different-synthetic-confirmation' }, 'PASSWORD_MISMATCH']
    ]) {
      const response = await request('/api/password', { method: 'POST', session: first, body: { ...body, ...override } });
      ensure(response.status === 400 && (await response.json()).error.code === code, 'Password validation did not match the reviewed behavior.');
    }
    ensure((await request('/api/presets', { session: first })).status === 200, 'Validation error unexpectedly ended the session.');
    pass('Wrong current password, short new password and mismatched confirmation retain the current session');
    const changed = await request('/api/password', { method: 'POST', session: first, body });
    ensure(changed.status === 200 && (await changed.json()).ok === true && /Max-Age=0/.test(changed.headers.get('set-cookie') || ''), 'Live password change failed.');
    for (const session of [first, second]) ensure((await request('/api/presets', { session })).status === 401, 'An old administrator session survived password change.');
    pass('Successful password change expires the cookie and revokes both old sessions');
    ensure((await login(firstPassword)).status === 401, 'Old temporary password still works.');
    if (nextPassword !== nextPassword.trim()) ensure((await login(nextPassword.trim())).status === 401, 'Password whitespace was incorrectly normalized.');
    const fresh = await login(nextPassword); ensure(fresh.status === 200, 'Exact new temporary password did not sign in.');
    ensure((await request('/api/presets', { session: fresh })).status === 200, 'Fresh session cannot read management data.');
    pass(minimumEight ? 'Exactly eight digits are accepted without character-combination rules; old password fails and the new password signs in'
      : 'Old password and trimmed password fail; exact new password signs in and reads administration');
    ensure((await request('/api/logout', { method: 'POST', session: fresh, body: {} })).status === 200, 'Temporary verification logout failed.');
    pass('Fresh verification session logs out');
    await cleanupWithRetry();
    const after = await request('/catalog.json'); ensure(after.status === 200 && sha256(Buffer.from(await after.arrayBuffer())) === catalogHash, 'Public catalog changed during the password smoke test.');
    pass('Public preset catalog remains byte-for-byte unchanged');
  }
} catch (error) {
  failed = true; checks.push({ name: error instanceof VerificationError ? error.message : 'Verification did not complete; sensitive details suppressed', stage, passed: false });
} finally {
  if (state && !cleanupComplete && !state.cleanupComplete) {
    try { await cleanupWithRetry(); } catch { failed = true; checks.push({ name: 'Temporary administrator cleanup requires the saved recovery marker', passed: false }); }
  }
  const report = { environment: ENV_ID, origin, verifiedAt: new Date().toISOString(), passed: !failed, cleanupComplete, checks };
  await writeFile(reportFile, JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
  if (failed) process.exitCode = 1;
}
