// Isolated local PostgreSQL WASM acceptance. Never contacts cloud services or reads credentials.
// Reuses the optional test runtime: npm install --prefix .community-pg-test @electric-sql/pglite
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { PGlite } from '../../.community-pg-test/node_modules/@electric-sql/pglite/dist/index.js';
const db = new PGlite();
try {
  await db.exec('CREATE ROLE anon; CREATE ROLE authenticated; CREATE ROLE service_role BYPASSRLS;');
  for (const file of ['001-library.sql', '002-users.sql', '003-password-accounts.sql', '004-preset-categories.sql', '005-community.sql', '006-app-releases.sql']) {
    await db.exec(await readFile(new URL(`../database/${file}`, import.meta.url), 'utf8'));
  }
  const legacy = (await db.query("SELECT nikon_sign_in_qq('123456789',repeat('A',32),'legacy fixture','https://q.qlogo.cn/legacy',repeat('a',64),now()+interval '1 day') AS u")).rows[0].u;
  const migration = await readFile(new URL('../database/007-user-avatar.sql', import.meta.url), 'utf8');
  await db.exec(migration); await db.exec(migration);
  const migrated = (await db.query('SELECT nikon_user_detail($1::uuid) AS u', [legacy.id])).rows[0].u;
  assert.equal(migrated.avatarUrl, legacy.avatarUrl); assert.equal(migrated.avatarSource, 'qq');
  await db.exec('BEGIN');
  const id = '77777777-7777-4777-8777-777777777777', url = `https://avatar.example/preset-library/avatars/${id}.jpg`;
  await db.query('SELECT nikon_avatar_reserve($1::uuid,$2::uuid,$3::timestamptz,$4,$5)', [legacy.id, id, migrated.updatedAt, '1'.repeat(64), '2'.repeat(64)]);
  await db.query('SELECT nikon_avatar_set($1::uuid,$2::uuid,$3::timestamptz,$4::jsonb)', [legacy.id, id, migrated.updatedAt,
    JSON.stringify({ byteLength: 2048, width: 512, height: 512, sha256: '3'.repeat(64), mimeType: 'image/jpeg', extension: 'jpg', storagePath: `avatars/${id}.jpg`, url })]);
  await db.exec(migration); await db.exec(migration);
  const rerun = (await db.query('SELECT nikon_user_detail($1::uuid) AS u', [legacy.id])).rows[0].u;
  assert.equal(rerun.avatarUrl, url); assert.equal(rerun.avatarSource, 'custom');
  assert.equal((await db.query('SELECT qq_avatar_url FROM nikon_users WHERE id=$1::uuid', [legacy.id])).rows[0].qq_avatar_url, legacy.avatarUrl);
  await db.exec('ROLLBACK');
  await db.exec(await readFile(new URL('./avatar-database-acceptance.sql', import.meta.url), 'utf8'));
  // Only the pre-existing upgrade fixture remains after the acceptance rollback.
  assert.equal((await db.query('SELECT count(*)::integer AS n FROM nikon_users')).rows[0].n, 1);
  assert.equal((await db.query('SELECT count(*)::integer AS n FROM nikon_avatar_uploads')).rows[0].n, 0);
  assert.equal((await db.query('SELECT reserved_bytes::integer AS n FROM nikon_avatar_storage_budget')).rows[0].n, 0);
  console.log('Avatar PostgreSQL upgrade/rerun, ownership, provider/default reset, CAS, budget, permissions and rollback passed.');
} finally { await db.close(); }
