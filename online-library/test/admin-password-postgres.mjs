// Disposable local PostgreSQL semantics; no CloudBase access or real credentials.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { PGlite } from '../../.community-pg-test/node_modules/@electric-sql/pglite/dist/index.js';

const db = new PGlite(); let checks = 0;
const oldHash = 'scrypt:32768:8:1:' + '1'.repeat(32) + ':' + '1'.repeat(128);
const newHash = 'scrypt:32768:8:1:' + '2'.repeat(32) + ':' + '2'.repeat(128);
const first = 'a'.repeat(64), second = 'b'.repeat(64), other = 'c'.repeat(64), fresh = 'd'.repeat(64), csrf = 'x'.repeat(43);
const equal = (actual, expected) => { assert.deepEqual(actual, expected); checks++; };
const fails = async (operation, pattern) => { await assert.rejects(operation(), pattern); checks++; };
const value = async (sql, args = []) => (await db.query(sql, args)).rows[0]?.value;
const login = (hash, token = fresh, username = 'admin') => db.query(
  "SELECT nikon_sign_in_admin($1,$2,$3,$4,clock_timestamp()+interval '1 hour')", [username, hash, token, csrf]);
const change = (session = first, verified = oldHash, next = newHash, username = 'admin') => db.query(
  'SELECT nikon_change_admin_password($1,$2,$3,$4)', [username, session, verified, next]);
try {
  await db.exec('CREATE ROLE anon; CREATE ROLE authenticated; CREATE ROLE service_role BYPASSRLS;');
  await db.exec(await readFile(new URL('../database/001-library.sql', import.meta.url), 'utf8'));
  const migration = await readFile(new URL('../database/013-admin-password.sql', import.meta.url), 'utf8');
  // Match the cloud apply script's single-transaction wrapper, then verify replay.
  await db.exec(`DO $admin_password_migration$ BEGIN\n${migration}\nEND $admin_password_migration$;`);
  await db.exec(migration);
  await db.query('INSERT INTO nikon_admins(username,password_hash) VALUES ($1,$3),($2,$3)', ['admin', 'other', oldHash]);
  await login(oldHash, first); await login(oldHash, second); await login(oldHash, other, 'other');
  equal(await value('SELECT count(*)::int AS value FROM nikon_sessions'), 3);
  await fails(() => change(other), /LOGIN_REQUIRED/);
  await fails(() => change(first, newHash), /PASSWORD_CONFLICT/);
  await fails(() => change(first, oldHash, 'plain text'), /PASSWORD_CONFLICT/);
  await fails(() => change(first, oldHash, oldHash), /PASSWORD_CONFLICT/);
  await fails(() => change(first, oldHash, null), /PASSWORD_CONFLICT/);
  equal(await value("SELECT password_hash AS value FROM nikon_admins WHERE username='admin'"), oldHash);
  equal(await value('SELECT count(*)::int AS value FROM nikon_sessions'), 3);
  await db.query('UPDATE nikon_sessions SET expires_at=clock_timestamp()-interval \'1 second\' WHERE token_hash=$1', [first]);
  await fails(() => change(), /LOGIN_REQUIRED/);
  await db.query('UPDATE nikon_sessions SET expires_at=clock_timestamp()+interval \'1 hour\' WHERE token_hash=$1', [first]);
  await db.exec("UPDATE nikon_admins SET disabled=true WHERE username='admin'");
  await fails(() => change(), /LOGIN_REQUIRED/);
  await fails(() => login(oldHash), /LOGIN_FAILED/);
  await db.exec("UPDATE nikon_admins SET disabled=false WHERE username='admin'");
  await fails(() => login(newHash), /LOGIN_FAILED/);
  await fails(() => login(oldHash, 'malformed'), /LOGIN_FAILED/);
  await fails(() => db.query("SELECT nikon_sign_in_admin('admin',$1,$2,$3,clock_timestamp()-interval '1 second')", [oldHash, fresh, csrf]), /LOGIN_FAILED/);
  await fails(() => db.query("SELECT nikon_sign_in_admin('admin',$1,$2,$3,clock_timestamp()+interval '9 hours')", [oldHash, fresh, csrf]), /LOGIN_FAILED/);

  // Force session revocation to fail: the credential update must roll back with it.
  await db.exec("CREATE FUNCTION pg_temp.reject_revoke() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'SYNTHETIC_REVOKE_FAILURE'; END $$; CREATE TRIGGER reject_revoke BEFORE DELETE ON nikon_sessions FOR EACH ROW EXECUTE FUNCTION pg_temp.reject_revoke();");
  await fails(() => change(), /SYNTHETIC_REVOKE_FAILURE/);
  equal(await value("SELECT password_hash AS value FROM nikon_admins WHERE username='admin'"), oldHash);
  equal(await value('SELECT count(*)::int AS value FROM nikon_sessions'), 3);
  await db.exec('DROP TRIGGER reject_revoke ON nikon_sessions');

  await db.exec('SET ROLE service_role');
  await change();
  equal(await value("SELECT password_hash AS value FROM nikon_admins WHERE username='admin'"), newHash);
  equal(await value("SELECT count(*)::int AS value FROM nikon_sessions WHERE username='admin'"), 0);
  equal(await value("SELECT password_hash AS value FROM nikon_admins WHERE username='other'"), oldHash);
  equal(await value('SELECT count(*)::int AS value FROM nikon_sessions WHERE token_hash=$1', [other]), 1);
  await fails(() => login(oldHash), /LOGIN_FAILED/);
  await fails(() => change(second), /LOGIN_REQUIRED/);
  await login(newHash);
  equal(await value('SELECT count(*)::int AS value FROM nikon_sessions WHERE token_hash=$1', [fresh]), 1);
  await fails(() => change(fresh, oldHash), /PASSWORD_CONFLICT/);
  await db.exec('RESET ROLE');
  await db.exec(migration);
  equal(await value("SELECT password_hash AS value FROM nikon_admins WHERE username='admin'"), newHash);
  equal(await value('SELECT count(*)::int AS value FROM nikon_sessions WHERE token_hash=$1', [fresh]), 1);
  for (const signature of ['nikon_sign_in_admin(text,text,text,text,timestamptz)', 'nikon_change_admin_password(text,text,text,text)']) {
    for (const role of ['anon', 'authenticated']) equal(await value('SELECT has_function_privilege($1,$2,\'EXECUTE\') AS value', [role, signature]), false);
    equal(await value('SELECT has_function_privilege(\'service_role\',$1,\'EXECUTE\') AS value', [signature]), true);
  }
  console.log(`PASS ${checks}: admin password PostgreSQL transactions, rollback, stale credentials, session revocation, role grants and repeat migration (isolated local database).`);
} finally { await db.close(); }
