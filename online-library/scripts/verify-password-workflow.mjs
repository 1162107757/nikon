// Explicit live verification only. This file is never imported by the HTTP runtime or default tests.
// Only a randomly named synthetic ordinary account is created; its password stays in stdin/memory
// and the ignored recovery file. Never print API bodies, subprocess diagnostics or credentials.
import { randomUUID, randomBytes } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { readFile, writeFile, mkdir, rename } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { ENV_ID, PUBLIC_ORIGIN, executeSql, sqlRows, quoteSql } from './cloud-tools.mjs';
import { sha256, verifyPassword } from '../server/auth.mjs';

const ROOT = new URL('../', import.meta.url), PROJECT = fileURLToPath(new URL('../../', import.meta.url));
const LOCAL = new URL('.local/', ROOT), BUILD = new URL('build/', ROOT);
const STATE_FILE = new URL('password-workflow-verification-state.json', LOCAL);
const REPORT_FILE = new URL('password-workflow-verification.json', BUILD);
const BASE = PUBLIC_ORIGIN + '/preset-library';
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
const ANY_UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const options = new Set(process.argv.slice(2));
if (options.has('--help')) {
  console.log('node scripts/verify-password-workflow.mjs [--cleanup-only]\nExplicit live synthetic App account verification with exact ownership checks and recoverable cleanup. Credentials are never printed.');
  process.exit(0);
}
if ([...options].some(value => value !== '--cleanup-only')) {
  console.error('Unsupported verification option. Use --help.'); process.exit(1);
}

class VerificationError extends Error { }
let state = null, stage = 'initialization', adminCookie = '', adminCsrf = '';
let fixtureCleanupSucceeded = false, administratorCleanupSucceeded = false;
const checks = [], errors = [];
const ensure = (condition, name) => { if (!condition) throw new VerificationError(name); };
const passed = name => checks.push({ name, passed: true });
const check = (condition, name) => { ensure(condition, name); passed(name); };
const emptyCleanup = () => ({ users: 0, credentials: 0, sessions: 0, attempts: 0, verifiedAbsent: false });
function fail(error, label) {
  errors.push({ name: error instanceof VerificationError ? error.message : `${label}: operation failed; sensitive details suppressed`, passed: false });
}
async function readJson(file) { return JSON.parse(await readFile(file, 'utf8')); }
async function saveState() {
  await mkdir(LOCAL, { recursive: true });
  const temporary = new URL('password-workflow-verification-state.json.new', LOCAL);
  await writeFile(temporary, JSON.stringify(state, null, 2) + '\n', { mode: 0o600 });
  await rename(temporary, STATE_FILE);
}
async function saveReport(report) {
  await mkdir(BUILD, { recursive: true });
  const temporary = new URL('password-workflow-verification.json.new', BUILD);
  await writeFile(temporary, JSON.stringify(report, null, 2) + '\n'); await rename(temporary, REPORT_FILE);
}
function initialState(runId) {
  const now = new Date();
  return { schemaVersion: 1, verifier: 'nikon-password-workflow', environmentId: ENV_ID, runId,
    username: 'qa_auth_' + runId.replaceAll('-', '').slice(0, 24), password: randomBytes(32).toString('base64url'),
    startedAt: now.toISOString(), registrationNotAfter: new Date(now.getTime() + 15 * 60000).toISOString(),
    registrationAttempted: false, ownedUserId: null, ownedCreatedAt: null, ownedCredentialCreatedAt: null,
    workflowVerified: false, cleanupComplete: false, nativeProbe: null, cleanup: emptyCleanup() };
}
function validateState(value) {
  ensure(value?.schemaVersion === 1 && value.verifier === 'nikon-password-workflow' && value.environmentId === ENV_ID
    && UUID.test(value.runId || ''), 'Recovery file is not owned by this verifier and environment');
  ensure(value.username === 'qa_auth_' + value.runId.replaceAll('-', '').slice(0, 24)
    && /^qa_auth_[a-f0-9]{24}$/.test(value.username), 'Recovery account name does not match its random marker');
  ensure(typeof value.password === 'string' && /^[A-Za-z0-9_-]{43}$/.test(value.password), 'Recovery synthetic password is invalid');
  ensure(Number.isFinite(Date.parse(value.startedAt)) && Number.isFinite(Date.parse(value.registrationNotAfter))
    && Date.parse(value.registrationNotAfter) === Date.parse(value.startedAt) + 15 * 60000,
  'Recovery registration window is invalid');
  ensure(value.ownedUserId === null || ANY_UUID.test(value.ownedUserId || ''), 'Recovery user ID is invalid');
  ensure((value.ownedCreatedAt === null || Number.isFinite(Date.parse(value.ownedCreatedAt)))
    && (value.ownedCredentialCreatedAt === null || Number.isFinite(Date.parse(value.ownedCredentialCreatedAt))),
  'Recovery creation timestamps are invalid');
  ensure(typeof value.registrationAttempted === 'boolean' && typeof value.cleanupComplete === 'boolean'
    && typeof value.workflowVerified === 'boolean' && value.cleanup
    && ['users','credentials','sessions','attempts'].every(key => Number.isSafeInteger(value.cleanup[key]) && value.cleanup[key] >= 0),
  'Recovery workflow or cleanup flags are invalid');
  return value;
}
function oneJson(sql, field) {
  const rows = sqlRows(executeSql(sql));
  ensure(rows.length === 1, 'Cloud SQL did not return one bounded verification result');
  const raw = rows[0][field]; return typeof raw === 'string' ? JSON.parse(raw) : raw;
}
function attemptKeys() {
  const key = sha256('app-password:' + state.username);
  return [`password-login:${key}`, `password-register:${key}`];
}
function attemptArray() { return `ARRAY[${attemptKeys().map(quoteSql).join(',')}]::text[]`; }
function userCandidates(alias) {
  return `(${alias}.nickname=${quoteSql(state.username)} OR ${state.ownedUserId ? `${alias}.id=${quoteSql(state.ownedUserId)}::uuid OR ` : ''}
    EXISTS(SELECT 1 FROM public.nikon_password_credentials owned_c WHERE owned_c.username=${quoteSql(state.username)} AND owned_c.user_id=${alias}.id))`;
}
function snapshot() {
  return oneJson(`SELECT jsonb_build_object(
    'credentials',coalesce((SELECT jsonb_agg(jsonb_build_object('username',c.username,'userId',c.user_id,
      'passwordHash',c.password_hash,'createdAt',c.created_at)) FROM public.nikon_password_credentials c
      WHERE c.username=${quoteSql(state.username)} ${state.ownedUserId ? `OR c.user_id=${quoteSql(state.ownedUserId)}::uuid` : ''}),'[]'::jsonb),
    'users',coalesce((SELECT jsonb_agg(jsonb_build_object('id',u.id,'nickname',u.nickname,'avatarUrl',u.avatar_url,
      'status',u.status,'createdAt',u.created_at,'updatedAt',u.updated_at,'loginCount',u.login_count))
      FROM public.nikon_users u WHERE ${userCandidates('u')}),'[]'::jsonb),
    'identities',(SELECT count(*) FROM public.nikon_user_identities i WHERE i.user_id IN(SELECT u.id FROM public.nikon_users u WHERE ${userCandidates('u')})),
    'sessions',(SELECT count(*) FROM public.nikon_user_sessions s WHERE s.user_id IN(SELECT u.id FROM public.nikon_users u WHERE ${userCandidates('u')})
      ${state.ownedUserId ? `OR s.user_id=${quoteSql(state.ownedUserId)}::uuid` : ''}),
    'attempts',(SELECT count(*) FROM public.nikon_login_attempts a WHERE a.attempt_key=ANY(${attemptArray()}))
  ) AS ownership;`, 'ownership');
}
async function verifyOwnership(value) {
  ensure(value && Array.isArray(value.credentials) && Array.isArray(value.users)
    && value.credentials.length <= 1 && value.users.length <= 1 && Number(value.identities) === 0,
  'Cleanup refused: ambiguous user, credential or QQ identity binding');
  const credential = value.credentials[0], user = value.users[0];
  if (!credential && !user) {
    ensure(Number(value.sessions) === 0, 'Cleanup refused: unbound synthetic sessions remain'); return null;
  }
  ensure(state.registrationAttempted === true && !state.cleanupComplete && credential && user
    && credential.username === state.username && ANY_UUID.test(user.id || '') && credential.userId === user.id
    && (!state.ownedUserId || state.ownedUserId === user.id), 'Cleanup refused: account does not match the attempted synthetic registration');
  ensure(user.nickname === state.username && user.avatarUrl === '' && ['active','disabled'].includes(user.status)
    && Number(user.loginCount) >= 1 && Number(user.loginCount) <= 50,
  'Cleanup refused: synthetic profile marker or login metadata changed unexpectedly');
  const minimum = Date.parse(state.startedAt) - 5 * 60000, maximum = Date.parse(state.registrationNotAfter);
  for (const timestamp of [user.createdAt, credential.createdAt]) {
    ensure(Number.isFinite(Date.parse(timestamp)) && Date.parse(timestamp) >= minimum && Date.parse(timestamp) <= maximum,
      'Cleanup refused: account creation is outside this run registration window');
  }
  ensure((!state.ownedCreatedAt || state.ownedCreatedAt === user.createdAt)
    && (!state.ownedCredentialCreatedAt || state.ownedCredentialCreatedAt === credential.createdAt),
  'Cleanup refused: recorded creation timestamps no longer match');
  // Prove ownership using the unique random password without sending plaintext to SQL or logs.
  ensure(await verifyPassword(state.password, credential.passwordHash), 'Cleanup refused: synthetic credential password ownership did not verify');
  state.ownedUserId = user.id; state.ownedCreatedAt = user.createdAt; state.ownedCredentialCreatedAt = credential.createdAt;
  await saveState();
  return { user, credential };
}
function noOwnedRows(value) {
  return value.credentials.length === 0 && value.users.length === 0 && Number(value.identities) === 0
    && Number(value.sessions) === 0 && Number(value.attempts) === 0;
}
async function cleanupOwned() {
  stage = 'exact synthetic account cleanup'; validateState(state);
  const before = snapshot(), owned = await verifyOwnership(before);
  const username = quoteSql(state.username), attempts = attemptArray();
  ensure(state.registrationAttempted || noOwnedRows(before),
    'Cleanup refused: this run never attempted registration and therefore owns no existing records or counters');
  if (state.cleanupComplete) {
    ensure(noOwnedRows(before), 'Cleanup refused: rows reappeared after confirmed cleanup');
    passed('Previously completed synthetic cleanup is still absent'); return;
  }
  if (owned) {
    const userId = `${quoteSql(owned.user.id)}::uuid`;
    executeSql(`DO $password_workflow_cleanup$
    DECLARE account public.nikon_users%ROWTYPE; credential public.nikon_password_credentials%ROWTYPE; removed integer;
    BEGIN
      PERFORM pg_advisory_xact_lock(hashtextextended('password:' || ${username},0));
      -- Same lock order as login. Locking the user also excludes concurrent FK/session/identity attachment.
      SELECT * INTO credential FROM public.nikon_password_credentials WHERE username=${username} FOR UPDATE;
      IF NOT FOUND OR credential.user_id IS DISTINCT FROM ${userId}
        OR credential.password_hash IS DISTINCT FROM ${quoteSql(owned.credential.passwordHash)}
        OR credential.created_at IS DISTINCT FROM ${quoteSql(owned.credential.createdAt)}::timestamptz THEN
        RAISE EXCEPTION 'SYNTHETIC_CREDENTIAL_OWNERSHIP_CHANGED'; END IF;
      SELECT * INTO account FROM public.nikon_users WHERE id=${userId} FOR UPDATE;
      IF NOT FOUND OR account.nickname IS DISTINCT FROM ${username} OR account.avatar_url IS DISTINCT FROM ''
        OR account.created_at IS DISTINCT FROM ${quoteSql(owned.user.createdAt)}::timestamptz
        OR account.updated_at IS DISTINCT FROM ${quoteSql(owned.user.updatedAt)}::timestamptz
        OR account.status NOT IN('active','disabled') OR account.login_count NOT BETWEEN 1 AND 50 THEN
        RAISE EXCEPTION 'SYNTHETIC_USER_OWNERSHIP_CHANGED'; END IF;
      IF EXISTS(SELECT 1 FROM public.nikon_user_identities WHERE user_id=${userId})
        OR EXISTS(SELECT 1 FROM public.nikon_password_credentials WHERE user_id=${userId} AND username<>${username})
        OR EXISTS(SELECT 1 FROM public.nikon_users WHERE nickname=${username} AND id<>${userId}) THEN
        RAISE EXCEPTION 'SYNTHETIC_ACCOUNT_HAS_FOREIGN_BINDING'; END IF;
      DELETE FROM public.nikon_user_sessions WHERE user_id=${userId};
      DELETE FROM public.nikon_password_credentials WHERE username=${username} AND user_id=${userId};
      GET DIAGNOSTICS removed=ROW_COUNT; IF removed<>1 THEN RAISE EXCEPTION 'SYNTHETIC_CREDENTIAL_COUNT_CHANGED'; END IF;
      DELETE FROM public.nikon_users WHERE id=${userId} AND nickname=${username};
      GET DIAGNOSTICS removed=ROW_COUNT; IF removed<>1 THEN RAISE EXCEPTION 'SYNTHETIC_USER_COUNT_CHANGED'; END IF;
      DELETE FROM public.nikon_login_attempts WHERE attempt_key=ANY(${attempts});
    END;
    $password_workflow_cleanup$;`);
    state.cleanup.users += 1; state.cleanup.credentials += 1; state.cleanup.sessions += Number(before.sessions);
    state.cleanup.attempts += Number(before.attempts);
  } else if (state.registrationAttempted || Number(before.attempts)) {
    // A failed/unknown registration can still leave only this exact random name's attempt counters.
    executeSql(`DO $password_attempt_cleanup$
    BEGIN
      PERFORM pg_advisory_xact_lock(hashtextextended('password:' || ${username},0));
      IF EXISTS(SELECT 1 FROM public.nikon_password_credentials WHERE username=${username})
        OR EXISTS(SELECT 1 FROM public.nikon_users u WHERE ${userCandidates('u')}) THEN
        RAISE EXCEPTION 'SYNTHETIC_ACCOUNT_REAPPEARED'; END IF;
      DELETE FROM public.nikon_login_attempts WHERE attempt_key=ANY(${attempts});
    END;
    $password_attempt_cleanup$;`);
    state.cleanup.attempts += Number(before.attempts);
  }
  const after = snapshot();
  ensure(noOwnedRows(after), 'Exact synthetic cleanup was not confirmed; recovery state is retained');
  state.cleanup.verifiedAbsent = true; state.cleanupComplete = true; await saveState();
  passed('Only the password-verified owned account, sessions, credential and exact account attempt keys are removed');
  passed('Cloud SQL confirms no owned records remain; global counters and every other account are preserved');
}

async function request(endpoint, { method = 'GET', body, bearer = '', administrator = false } = {}) {
  const headers = { Accept: 'application/json', 'Accept-Encoding': 'identity',
    ...(method === 'POST' ? { Origin: PUBLIC_ORIGIN, 'Content-Type': 'application/json' } : {}),
    ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
    ...(administrator && adminCookie ? { Cookie: adminCookie } : {}),
    ...(administrator && method === 'POST' ? { 'X-CSRF-Token': adminCsrf } : {}) };
  return fetch(BASE + endpoint, { method, redirect: 'manual', signal: AbortSignal.timeout(45000), headers,
    body: body === undefined ? undefined : JSON.stringify(body) });
}
async function responseJson(response, status, name) {
  check(response.status === status, `${name}: expected HTTP status`); return response.json();
}
function safeUser(user) {
  return user && user.id === state.ownedUserId && user.username === state.username && user.provider === 'password'
    && user.nickname === state.username && user.avatarUrl === '' && ['active','disabled'].includes(user.status)
    && !/password_hash|scrypt:|accessToken|token_hash|provider_subject/.test(JSON.stringify(user));
}
function sameExpiryInstant(first, second) {
  // Node emits UTC "Z"; PostgreSQL jsonb emits an offset and may trim fractional zeroes.
  // Compare the represented instant while still rejecting missing or invalid timestamps.
  const valid = value => typeof value === 'string'
    && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$/.test(value)
    && Number.isFinite(Date.parse(value));
  return valid(first) && valid(second) && Date.parse(first) === Date.parse(second);
}
async function passwordLogin() {
  const result = await responseJson(await request('/auth/login', { method: 'POST', body: { username: state.username, password: state.password } }), 200, 'Synthetic password login');
  ensure(result.tokenType === 'Bearer' && /^[A-Za-z0-9_-]{43}$/.test(result.accessToken || '') && safeUser(result.user)
    && Number.isFinite(Date.parse(result.expiresAt)), 'Password login did not return a safe ordinary session');
  return result;
}
async function loginAdministrator() {
  const initial = await readJson(new URL('initial-admin.json', LOCAL));
  ensure(typeof initial.username === 'string' && typeof initial.password === 'string', 'Local administrator handoff is unavailable');
  const response = await request('/api/login', { method: 'POST', body: { username: initial.username, password: initial.password } });
  ensure(response.status === 200, 'Administrator login failed');
  const header = response.headers.get('set-cookie') || '';
  const token = header.match(/(?:^|,\s*)__Host-nikon_session=([A-Za-z0-9_-]{43})(?:;|$)/)?.[1];
  ensure(Boolean(token), 'Administrator session Cookie was not returned'); adminCookie = `__Host-nikon_session=${token}`;
  const result = await response.json();
  ensure(result.authenticated === true && /^[A-Za-z0-9_-]{43}$/.test(result.csrfToken || ''), 'Administrator session is incomplete');
  adminCsrf = result.csrfToken;
  passed('Existing administrator can inspect ordinary accounts using its separate secure Cookie session');
}
async function runNativeProbe() {
  stage = 'production Java account HTTPS probe';
  state.registrationAttempted = true; await saveState();
  const jdk = process.env.NIKON_VERIFY_JDK || 'D:/dev/Java/jdk-17.0.19';
  const androidJar = process.env.NIKON_VERIFY_ANDROID_JAR || 'D:/dev/android-sdk/platforms/android-35/android.jar';
  const classes = path.join(PROJECT, 'tests/.build/account/classes');
  const result = spawnSync(path.join(jdk, 'bin', process.platform === 'win32' ? 'java.exe' : 'java'),
    ['-cp', classes + path.delimiter + androidJar, 'com.nikonpreset.usbprobe.account.AccountLiveProbe'],
    { cwd: PROJECT, input: JSON.stringify({ action: 'register', username: state.username, password: state.password }),
      encoding: 'utf8', windowsHide: true, timeout: 240000, maxBuffer: 64 * 1024 });
  ensure(!result.error && result.status === 0, 'Production Java account probe did not finish; diagnostics are suppressed and cleanup will recover by random username');
  let output;
  try { output = JSON.parse(String(result.stdout || '').trim()); } catch { throw new VerificationError('Production Java account probe returned an invalid non-sensitive report'); }
  ensure(output?.passed === true && output.checks === 9 && ANY_UUID.test(output.userId || '') && output.username === state.username
    && output.transport === 'production Android account HTTPS client on host JDK', 'Production Java account probe reported unexpected verification metadata');
  state.ownedUserId = output.userId;
  state.nativeProbe = { passed: true, checks: 9, transport: output.transport, storage: 'in-memory test store; Android Keystore is not exercised' };
  await saveState(); await verifyOwnership(snapshot());
  passed('Production Android HTTPS client completes register, me, store restoration, logout replay, login and logout (9 native checks)');
}
async function runWorkflow() {
  stage = 'synthetic account collision check';
  const initial = snapshot();
  ensure(initial.credentials.length === 0 && initial.users.length === 0 && Number(initial.sessions) === 0 && Number(initial.attempts) === 0,
    'Random verification account marker collides with an existing record; no registration was attempted');
  passed('Random account marker has no existing user, credential, session or attempt records');
  await runNativeProbe();

  stage = 'public account validation';
  const duplicate = await responseJson(await request('/auth/register', { method: 'POST',
    body: { username: state.username.toUpperCase(), password: state.password } }), 409, 'Duplicate normalized username');
  check(duplicate.error?.code === 'USERNAME_TAKEN', 'Duplicate username returns the explicit account conflict code');
  const wrong = await responseJson(await request('/auth/login', { method: 'POST',
    body: { username: state.username, password: randomBytes(32).toString('base64url') } }), 401, 'Wrong synthetic password');
  check(wrong.error?.code === 'USER_LOGIN_FAILED', 'Wrong password returns the generic login failure code');
  const session = await passwordLogin();
  const me = await responseJson(await request('/auth/me', { bearer: session.accessToken }), 200, 'Bearer user lookup');
  check(safeUser(me.user), 'Bearer lookup returns the same safe password user without credentials');
  check(sameExpiryInstant(me.expiresAt, session.expiresAt), 'Bearer lookup preserves the exact expiry instant across PostgreSQL timestamp serialization');
  const isolated = await request('/api/users', { bearer: session.accessToken });
  check(isolated.status === 401, 'Ordinary bearer token cannot read administrator user management');

  stage = 'administrator password user visibility'; await loginAdministrator();
  const page = await responseJson(await request('/api/users?q=' + encodeURIComponent(state.username), { administrator: true }), 200, 'Admin username search');
  check(page.total === 1 && Array.isArray(page.items) && page.items.length === 1 && safeUser(page.items[0]),
    'Admin username search returns only this synthetic password account with correct provider');
  check(!/password_hash|scrypt:|accessToken|token_hash|provider_subject/.test(JSON.stringify(page)), 'Admin user listing does not disclose credential hashes or tokens');
  const detail = await responseJson(await request(`/api/users/${state.ownedUserId}`, { administrator: true }), 200, 'Admin user detail');
  check(safeUser(detail.user) && detail.user.qqOpenIdMasked === '', 'Password user detail is safe and has no QQ identity');

  stage = 'disable and enable ordinary account';
  const disabled = await responseJson(await request(`/api/users/${state.ownedUserId}/status`, { method: 'POST', administrator: true,
    body: { status: 'disabled', expectedUpdatedAt: detail.user.updatedAt } }), 200, 'Disable synthetic account');
  check(safeUser(disabled.user) && disabled.user.status === 'disabled', 'Disabling preserves password user profile and changes account status');
  check((await request('/auth/me', { bearer: session.accessToken })).status === 401, 'Disabling atomically revokes the active ordinary bearer token');
  const denied = await responseJson(await request('/auth/login', { method: 'POST',
    body: { username: state.username, password: state.password } }), 403, 'Correct password on disabled account');
  check(denied.error?.code === 'USER_DISABLED', 'Disabled account rejects fresh password sessions');
  const enabled = await responseJson(await request(`/api/users/${state.ownedUserId}/status`, { method: 'POST', administrator: true,
    body: { status: 'active', expectedUpdatedAt: disabled.user.updatedAt } }), 200, 'Enable synthetic account');
  check(safeUser(enabled.user) && enabled.user.status === 'active', 'Synthetic account can be explicitly re-enabled');
  check((await request('/auth/me', { bearer: session.accessToken })).status === 401, 'Re-enabling never restores a previously revoked token');
  const fresh = await passwordLogin();
  check(fresh.accessToken !== session.accessToken, 'Fresh login after enabling issues a different ordinary token');
  await responseJson(await request('/auth/logout', { method: 'POST', bearer: fresh.accessToken, body: {} }), 200, 'Ordinary user logout');
  check((await request('/auth/me', { bearer: fresh.accessToken })).status === 401, 'Ordinary logout prevents bearer replay through the live database');
  await verifyOwnership(snapshot());
  state.workflowVerified = true; await saveState();
}
async function logoutAdministrator() {
  if (!adminCookie) return;
  stage = 'administrator session cleanup';
  if (!adminCsrf) {
    const response = await request('/api/session', { administrator: true });
    ensure(response.status === 200, 'Administrator session recovery failed');
    const result = await response.json();
    ensure(result.authenticated === true && /^[A-Za-z0-9_-]{43}$/.test(result.csrfToken || ''), 'Administrator logout token could not be recovered');
    adminCsrf = result.csrfToken;
  }
  ensure((await request('/api/logout', { method: 'POST', administrator: true, body: {} })).status === 200, 'Administrator verification session logout failed');
  check((await request('/api/users', { administrator: true })).status === 401, 'Administrator verification Cookie is revoked after logout');
  adminCookie = ''; adminCsrf = '';
}

try {
  await mkdir(BUILD, { recursive: true });
  if (options.has('--cleanup-only')) {
    state = validateState(await readJson(STATE_FILE));
  } else {
    let previous;
    try { previous = await readJson(STATE_FILE); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    ensure(!previous || validateState(previous).cleanupComplete === true,
      'An unfinished synthetic account verification exists; use --cleanup-only before starting another run');
    state = initialState(randomUUID()); await saveState();
  }
  await saveReport({ startedAt: state.startedAt, finishedAt: null, passed: false, mode: options.has('--cleanup-only') ? 'cleanup-only' : 'full-workflow',
    checks: [], nativeProbe: null, cleanup: state.cleanup });
  if (!options.has('--cleanup-only')) await runWorkflow();
} catch (error) { fail(error, stage); }
finally {
  if (state) {
    try {
      await cleanupOwned();
      fixtureCleanupSucceeded = state.cleanupComplete === true && state.cleanup.verifiedAbsent === true;
    } catch (error) { fail(error, 'synthetic account cleanup'); }
  }
  try { await logoutAdministrator(); administratorCleanupSucceeded = true; } catch (error) { fail(error, 'administrator session cleanup'); }
  const cleanupOnly = options.has('--cleanup-only');
  const cleanupSucceeded = fixtureCleanupSucceeded && administratorCleanupSucceeded;
  const successful = errors.length === 0 && cleanupSucceeded && (cleanupOnly || state?.workflowVerified === true);
  const report = { startedAt: state?.startedAt ?? new Date().toISOString(), finishedAt: new Date().toISOString(),
    mode: cleanupOnly ? 'cleanup-only' : 'full-workflow', passed: successful && !cleanupOnly,
    cleanupSucceeded,
    checks: [...(cleanupOnly ? [{ name: 'Cleanup-only recovery does not certify a full account workflow', passed: false }] : []), ...checks, ...errors],
    nativeProbe: cleanupOnly ? null : state?.nativeProbe ?? null, cleanup: state?.cleanup ?? emptyCleanup() };
  try { await saveReport(report); } catch { errors.push({ name: 'Non-sensitive verification report could not be saved', passed: false }); }
  if (successful && errors.length === 0) {
    console.log(`${cleanupOnly ? 'Owned synthetic account cleanup completed (full workflow not certified)' : 'Synthetic password account workflow passed'}: ${checks.length} checks; ${state.cleanup.users} users, ${state.cleanup.credentials} credentials, ${state.cleanup.sessions} sessions and ${state.cleanup.attempts} exact account attempt keys removed.`);
  } else {
    console.error('Account verification incomplete. Inspect the non-sensitive report and ignored recovery state; this run is not certified.');
    process.exitCode = 1;
  }
}
