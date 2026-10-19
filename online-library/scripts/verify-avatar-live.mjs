// Explicit live acceptance for one random owned password account and its avatar only.
// No admin login, real-user lookup, presets, comments or publications. Recovery stays ignored.
import { readFile, writeFile, mkdir, rename } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { ENV_ID, PUBLIC_ORIGIN, executeSql, sqlRows, quoteSql } from './cloud-tools.mjs';
import { randomToken, sha256, hashUserPassword } from '../server/auth.mjs';
import { validateAvatar } from '../server/profile.mjs';

const LOCAL = new URL('../.local/', import.meta.url), STATE = new URL('avatar-live-state.json', LOCAL);
const REPORT = new URL('../build/avatar-live-verification.json', import.meta.url);
const BASE = PUBLIC_ORIGIN + '/preset-library', API = `https://${ENV_ID}.api.tcloudbasegateway.com`, BUCKET = 'nikon-presets';
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
const JPEG = Buffer.from('/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCABAAEADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDxSiiiv10+KCiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKAP/Z', 'base64');
const checks = [], failures = [];
let state, credential, stage = 'preparation';
class AssertionError extends Error {}
function ensure(value, label) { if (!value) throw new AssertionError(label); }
function check(value, label) { ensure(value, label); checks.push(label); }
function sqlJson(sql, column = 'result') {
  const rows = sqlRows(executeSql(sql)); ensure(rows.length === 1, 'SQL result is not one bounded row');
  return typeof rows[0][column] === 'string' ? JSON.parse(rows[0][column]) : rows[0][column];
}
function validateState(value) {
  ensure(value?.version === 1 && value.verifier === 'owned-avatar-live' && value.environmentId === ENV_ID && UUID.test(value.runId || ''), 'Recovery ownership marker invalid');
  ensure(value.username === 'qa_avatar_' + value.runId.replaceAll('-', '').slice(0, 20), 'Recovery account marker invalid');
  ensure(value.userId === null || UUID.test(value.userId), 'Recovery user ID invalid');
  ensure(Number.isFinite(Date.parse(value.startedAt)) && Date.parse(value.notAfter) === Date.parse(value.startedAt) + 900000, 'Recovery ownership window invalid');
  ensure(value.jpegSha256 === sha256(JPEG) && typeof value.cleanupComplete === 'boolean' && typeof value.registrationAttempted === 'boolean', 'Recovery state invalid');
  if (!value.cleanupComplete) ensure(/^[A-Za-z0-9_-]{43}$/.test(value.accessToken || '') && /^scrypt:32768:8:1:[a-f0-9]{32}:[a-f0-9]{128}$/.test(value.passwordHash || ''), 'Recovery credential ownership invalid');
  return value;
}
async function save() {
  await mkdir(LOCAL, { recursive: true });
  const temporary = new URL('avatar-live-state.json.new', LOCAL);
  await writeFile(temporary, JSON.stringify(state, null, 2), { mode: 0o600 }); await rename(temporary, STATE);
}
const q = value => quoteSql(value);
function ownWhere() {
  return `c.username=${q(state.username)} AND c.password_hash=${q(state.passwordHash)} AND u.nickname=${q(state.username)}
    AND u.created_at BETWEEN ${q(state.startedAt)}::timestamptz-interval '5 minutes' AND ${q(state.notAfter)}::timestamptz
    ${state.userId ? `AND u.id=${q(state.userId)}::uuid` : ''}`;
}
async function owned() {
  // Filter by the secret credential proof before returning any account information.
  const value = sqlJson(`SELECT coalesce((SELECT jsonb_build_object('id',u.id,'createdAt',u.created_at,'credentialCreatedAt',c.created_at,
    'avatarAssetId',u.avatar_asset_id,'avatarUrl',u.avatar_url,'updatedAt',u.updated_at,'status',u.status,'loginCount',u.login_count)
    FROM public.nikon_password_credentials c JOIN public.nikon_users u ON u.id=c.user_id WHERE ${ownWhere()} LIMIT 1),'null'::jsonb) AS result;`);
  if (!value) {
    const counts = sqlJson(`SELECT jsonb_build_object('credential',(SELECT count(*) FROM public.nikon_password_credentials WHERE username=${q(state.username)}),
      'user',(SELECT count(*) FROM public.nikon_users WHERE ${state.userId ? `id=${q(state.userId)}::uuid` : `nickname=${q(state.username)}`})) AS result;`);
    ensure(Number(counts.credential) === 0 && Number(counts.user) === 0, 'Cleanup refused: ownership proof no longer matches');
    return null;
  }
  ensure(state.registrationAttempted && value.status === 'active' && Number(value.loginCount) === 1 && UUID.test(value.id), 'Cleanup refused: owned account metadata changed');
  ensure(!state.userId || value.id === state.userId, 'Cleanup refused: owned account ID changed');
  ensure(!state.createdAt || value.createdAt === state.createdAt, 'Cleanup refused: owned account creation changed');
  state.userId = value.id; state.createdAt = value.createdAt; await save();
  const id = `${q(value.id)}::uuid`;
  const bindings = sqlJson(`SELECT jsonb_build_object('foreign',
    EXISTS(SELECT 1 FROM public.nikon_user_identities WHERE user_id=${id})
    OR EXISTS(SELECT 1 FROM public.nikon_password_credentials WHERE user_id=${id} AND username<>${q(state.username)})
    OR EXISTS(SELECT 1 FROM public.nikon_community_comments WHERE user_id=${id})
    OR EXISTS(SELECT 1 FROM public.nikon_community_submissions WHERE user_id=${id})
    OR EXISTS(SELECT 1 FROM public.nikon_community_assets WHERE user_id=${id})
    OR EXISTS(SELECT 1 FROM public.nikon_community_messages WHERE user_id=${id})
    OR EXISTS(SELECT 1 FROM public.nikon_community_reports WHERE user_id=${id})
    OR EXISTS(SELECT 1 FROM public.nikon_community_restrictions WHERE user_id=${id})
    OR EXISTS(SELECT 1 FROM public.nikon_community_activity WHERE user_id=${id})
    OR EXISTS(SELECT 1 FROM public.nikon_community_idempotency WHERE user_id=${id}),
    'uploads',coalesce((SELECT jsonb_agg(jsonb_build_object('id',a.id,'createdAt',a.created_at,'payload',a.payload)) FROM public.nikon_avatar_uploads a WHERE a.user_id=${id}),'[]'::jsonb)) AS result;`);
  ensure(bindings.foreign === false && Array.isArray(bindings.uploads) && bindings.uploads.length <= 2, 'Cleanup refused: unexpected bindings or upload count');
  for (const upload of bindings.uploads) {
    ensure(UUID.test(upload.id) && Date.parse(upload.createdAt) >= Date.parse(state.startedAt) - 300000 && Date.parse(upload.createdAt) <= Date.parse(state.notAfter), 'Cleanup refused: upload ownership window mismatch');
    if (upload.payload) ensure(upload.payload.sha256 === sha256(JPEG) && upload.payload.byteLength === JPEG.length
      && upload.payload.mimeType === 'image/jpeg' && upload.payload.storagePath === `avatars/${upload.id}.jpg`, 'Cleanup refused: upload is not exact synthetic JPEG');
  }
  return { ...value, uploads: bindings.uploads };
}
async function bytes(response, maximum) {
  let length = 0; const chunks = [];
  for await (const part of response.body) { length += part.length; ensure(length <= maximum, 'Unexpected response byte length'); chunks.push(part); }
  return Buffer.concat(chunks, length);
}
async function request(path, { method = 'GET', raw, json, revision, auth = true } = {}) {
  ensure(path.startsWith('/') && !path.startsWith('//'), 'Request path escaped expected service');
  return fetch(BASE + path, { method, redirect: 'error', signal: AbortSignal.timeout(45000),
    headers: { 'Accept-Encoding': 'identity', ...(auth ? { Authorization: `Bearer ${state.accessToken}`, 'X-Installation-Id': state.installationId } : {}),
      ...(raw ? { 'Content-Type': 'image/jpeg' } : json ? { 'Content-Type': 'application/json' } : {}), ...(revision ? { 'X-Expected-Updated-At': revision } : {}) },
    body: raw || (json ? JSON.stringify(json) : undefined) });
}
async function jsonResponse(response, status, label) {
  check(response.status === status, label); return JSON.parse((await bytes(response, 65536)).toString('utf8'));
}
async function object(path, method = 'GET') {
  ensure(/^avatars\/[a-f0-9-]{36}\.jpg$/.test(path), 'Object path is outside owned avatar namespace');
  return fetch(API + `/v1/storages/object/${method === 'GET' ? 'authenticated/' : ''}${BUCKET}/${path}`, {
    method, redirect: 'error', signal: AbortSignal.timeout(30000), headers: { Authorization: `Bearer ${credential.serviceKey}`, 'Accept-Encoding': 'identity' } });
}
async function cleanup() {
  stage = 'exact owned cleanup'; validateState(state);
  const account = state.cleanupComplete ? null : await owned();
  if (account) {
    const id = `${q(account.id)}::uuid`;
    // Stop public access and revoke this account's session before removing its private object.
    executeSql(`DO $$ BEGIN
      IF NOT EXISTS(SELECT 1 FROM public.nikon_password_credentials c JOIN public.nikon_users u ON u.id=c.user_id WHERE ${ownWhere()}) THEN RAISE EXCEPTION 'OWNERSHIP_CHANGED'; END IF;
      UPDATE public.nikon_users SET avatar_asset_id=NULL,avatar_url='',avatar_source='default' WHERE id=${id};
      DELETE FROM public.nikon_user_sessions WHERE user_id=${id} AND token_hash=${q(sha256(state.accessToken))};
    END $$;`);
    for (const upload of account.uploads) {
      const path = `avatars/${upload.id}.jpg`, current = await object(path);
      if (current.status === 200) {
        const body = await bytes(current, JPEG.length);
        ensure(body.equals(JPEG) && sha256(body) === state.jpegSha256, 'Cleanup refused: object byte ownership mismatch');
        const refreshed = await owned(); ensure(refreshed?.uploads.some(value => value.id === upload.id), 'Cleanup refused: upload ownership changed');
        const removed = await object(path, 'DELETE'); ensure([200, 204].includes(removed.status), 'Owned object delete was not confirmed'); await removed.body?.cancel();
      } else { ensure(current.status === 404, 'Owned object cannot be safely inspected for deletion'); await current.body?.cancel(); }
      const after = await object(path); ensure(after.status === 404, 'Owned object remains after deletion'); await after.body?.cancel();
      state.cleanedObjectIds = [...new Set([...(state.cleanedObjectIds || []), upload.id])]; await save();
    }
    const current = await owned(); ensure(current && current.uploads.length === account.uploads.length, 'Cleanup refused: upload list changed');
    executeSql(`DO $$ DECLARE uid uuid; BEGIN
      PERFORM pg_advisory_xact_lock(hashtextextended('password:'||${q(state.username)},0));
      SELECT u.id INTO uid FROM public.nikon_password_credentials c JOIN public.nikon_users u ON u.id=c.user_id WHERE ${ownWhere()} FOR UPDATE OF c,u;
      IF uid IS NULL THEN RAISE EXCEPTION 'OWNERSHIP_CHANGED'; END IF;
      IF EXISTS(SELECT 1 FROM public.nikon_user_sessions WHERE user_id=uid) OR EXISTS(SELECT 1 FROM public.nikon_user_identities WHERE user_id=uid)
        OR EXISTS(SELECT 1 FROM public.nikon_users WHERE avatar_asset_id IN(SELECT id FROM public.nikon_avatar_uploads WHERE user_id=uid)) THEN RAISE EXCEPTION 'OWNERSHIP_BINDING_CHANGED'; END IF;
      DELETE FROM public.nikon_avatar_uploads WHERE user_id=uid;
      DELETE FROM public.nikon_password_credentials WHERE user_id=uid AND username=${q(state.username)} AND password_hash=${q(state.passwordHash)};
      DELETE FROM public.nikon_users WHERE id=uid AND nickname=${q(state.username)};
    END $$;`);
  }
  const absent = sqlJson(`SELECT jsonb_build_object('credential',(SELECT count(*) FROM public.nikon_password_credentials WHERE username=${q(state.username)}),
    'user',(SELECT count(*) FROM public.nikon_users WHERE ${state.userId ? `id=${q(state.userId)}::uuid` : `nickname=${q(state.username)}`}),
    'uploads',${state.userId ? `(SELECT count(*) FROM public.nikon_avatar_uploads WHERE user_id=${q(state.userId)}::uuid)` : '0'},
    'session',(SELECT count(*) FROM public.nikon_user_sessions WHERE token_hash=${q(sha256(state.accessToken || 'completed'))})) AS result;`);
  check(Object.values(absent).every(value => Number(value) === 0), 'Exact owned QA account, credential, session and avatar reservations are absent');
  state.cleanupComplete = true; state.cleanedAt = new Date().toISOString(); delete state.passwordHash; delete state.accessToken; await save();
  checks.push('Only byte-verified owned avatar objects removed; durable abuse counters and lifetime byte budget retained');
}
async function workflow() {
  stage = 'random account collision check';
  const collision = sqlJson(`SELECT jsonb_build_object('credential',(SELECT count(*) FROM public.nikon_password_credentials WHERE username=${q(state.username)}),
    'user',(SELECT count(*) FROM public.nikon_users WHERE nickname=${q(state.username)})) AS result;`);
  ensure(Number(collision.credential) === 0 && Number(collision.user) === 0, 'Random QA marker collides; no registration attempted');
  state.registrationAttempted = true; await save();
  stage = 'create owned synthetic password account';
  const user = sqlJson(`SELECT public.nikon_register_password(${q(state.username)},${q(state.passwordHash)},${q(sha256(state.accessToken))},now()+interval '30 minutes') AS result;`);
  ensure(UUID.test(user.id) && user.username === state.username, 'Synthetic SQL registration identity mismatch');
  state.userId = user.id; state.createdAt = user.createdAt; await save(); await owned();
  stage = 'public avatar acceptance';
  const me = await jsonResponse(await request('/auth/me'), 200, 'Owned bearer session resolves through deployed auth/me');
  check(me.user?.id === state.userId && me.user.avatarUrl === '' && me.user.avatarSource === 'default', 'New password account has the native default avatar');
  const initialRevision = me.user.updatedAt;
  const changed = await jsonResponse(await request('/profile/avatar', { method: 'PUT', raw: JPEG, revision: initialRevision }), 200, 'Deployed raw JPEG avatar upload succeeds');
  const uri = new URL(changed.user?.avatarUrl || '', BASE);
  check(changed.user?.id === state.userId && changed.user.avatarSource === 'custom' && changed.user.updatedAt !== initialRevision
    && uri.origin === PUBLIC_ORIGIN && /^\/preset-library\/avatars\/[a-f0-9-]{36}\.jpg$/.test(uri.pathname) && !uri.search && !uri.hash, 'Avatar response has an owned changing UUID URL and monotonic revision');
  state.avatarUrl = uri.href; state.avatarId = uri.pathname.split('/').at(-1).slice(0, -4); await save();
  const publicImage = await request(uri.pathname.replace('/preset-library', ''), { auth: false });
  check(publicImage.status === 200 && publicImage.headers.get('content-type') === 'image/jpeg'
    && publicImage.headers.get('x-content-type-options') === 'nosniff', 'Anonymous avatar GET returns JPEG with nosniff');
  const downloaded = await bytes(publicImage, JPEG.length);
  check(downloaded.equals(JPEG) && sha256(downloaded) === state.jpegSha256, 'Public avatar bytes match the synthetic JPEG SHA-256');
  const stale = await jsonResponse(await request('/profile/avatar', { method: 'PUT', raw: JPEG, revision: initialRevision }), 409, 'Stale avatar revision is rejected before a second object write');
  check(stale.error?.code === 'USER_CONFLICT', 'Stale revision returns USER_CONFLICT');
  const reset = await jsonResponse(await request('/profile/avatar/reset', { method: 'POST', json: { expectedUpdatedAt: changed.user.updatedAt } }), 200, 'Avatar reset succeeds');
  check(reset.user?.id === state.userId && reset.user.avatarUrl === '' && reset.user.avatarSource === 'default', 'Password avatar reset restores native default');
  const oldImage = await request(uri.pathname.replace('/preset-library', ''), { auth: false });
  check(oldImage.status === 404, 'Old avatar URL is revoked after reset'); await oldImage.body?.cancel();
  state.workflowPassed = true; await save();
}

const mode = process.argv[2] || '--prepare';
ensure(process.argv.length <= 3 && ['--prepare', '--run', '--cleanup-only'].includes(mode), 'Use --prepare, --run, or --cleanup-only');
validateAvatar(JPEG, 'image/jpeg');
if (mode === '--prepare') {
  console.log(JSON.stringify({ prepared: true, jpegSha256: sha256(JPEG), byteLength: JPEG.length, liveRequestsMade: false }));
} else {
  try {
    credential = JSON.parse(await readFile(new URL('service-credential.json', LOCAL), 'utf8'));
    ensure(credential.environmentId === ENV_ID && typeof credential.serviceKey === 'string' && credential.serviceKey.length > 24, 'Exact environment object cleanup credential unavailable');
    if (mode === '--cleanup-only') state = validateState(JSON.parse(await readFile(STATE, 'utf8')));
    else {
      let previous; try { previous = JSON.parse(await readFile(STATE, 'utf8')); } catch (error) { if (error.code !== 'ENOENT') throw error; }
      ensure(!previous || validateState(previous).cleanupComplete, 'Unfinished owned avatar verification exists; use --cleanup-only');
      const runId = randomUUID(), startedAt = new Date().toISOString();
      state = { version: 1, verifier: 'owned-avatar-live', environmentId: ENV_ID, runId,
        username: 'qa_avatar_' + runId.replaceAll('-', '').slice(0, 20), startedAt, notAfter: new Date(Date.parse(startedAt) + 900000).toISOString(),
        installationId: randomUUID(), accessToken: randomToken(), passwordHash: await hashUserPassword(randomToken()),
        userId: null, registrationAttempted: false, workflowPassed: false, cleanupComplete: false, jpegSha256: sha256(JPEG) };
      await save(); await workflow();
    }
  } catch (error) { failures.push(error instanceof AssertionError ? error.message : `${stage}: operation failed; sensitive details suppressed`); }
  finally {
    if (state && credential) try { await cleanup(); } catch (error) { failures.push(error instanceof AssertionError ? error.message : `${stage}: operation failed; recovery retained`); }
    const report = { passed: failures.length === 0 && Boolean(state?.cleanupComplete) && (mode === '--cleanup-only' || state?.workflowPassed),
      mode, verifiedAt: new Date().toISOString(), jpegSha256: sha256(JPEG), checks, failures, cleanupComplete: Boolean(state?.cleanupComplete) };
    await mkdir(new URL('../build/', import.meta.url), { recursive: true }); await writeFile(REPORT, JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report)); if (!report.passed) process.exitCode = 1;
  }
}
