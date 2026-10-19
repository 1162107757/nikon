// Optional local PostgreSQL WASM runner: npm install --prefix .community-pg-test @electric-sql/pglite
// Then: node online-library/test/community-postgres.mjs (from repository root).
import { readFile } from 'node:fs/promises';
import { PGlite } from '../../.community-pg-test/node_modules/@electric-sql/pglite/dist/index.js';
const db = new PGlite();
try {
  await db.exec('CREATE ROLE anon; CREATE ROLE authenticated; CREATE ROLE service_role BYPASSRLS;');
  for (const name of ['001-library.sql','002-users.sql','003-password-accounts.sql','004-preset-categories.sql','005-community.sql']) {
    if (name === '005-community.sql') {
      // Reproduce the already deployed original three-state table, including a public deleted tombstone.
      await db.exec(`CREATE TABLE public.nikon_community_comments (
        id uuid PRIMARY KEY DEFAULT gen_random_uuid(),preset_id uuid NOT NULL,user_id uuid NOT NULL REFERENCES public.nikon_users(id),
        parent_id uuid REFERENCES public.nikon_community_comments(id),text text NOT NULL CHECK(length(text) BETWEEN 1 AND 500),
        status text NOT NULL DEFAULT 'visible' CHECK(status IN('visible','deleted','hidden')),reason text NOT NULL DEFAULT '',
        created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now());
        INSERT INTO nikon_users(id,nickname) VALUES('11111111-1111-4111-8111-111111111111','local-upgrade-fixture');
        INSERT INTO nikon_community_comments(id,preset_id,user_id,text,status) VALUES('22222222-2222-4222-8222-222222222222',gen_random_uuid(),'11111111-1111-4111-8111-111111111111','[deleted]','deleted');`);
    }
    await db.exec(await readFile(new URL(`../database/${name}`, import.meta.url), 'utf8'));
  }
  await db.exec(`INSERT INTO nikon_community_comments(id,preset_id,user_id,text) VALUES('33333333-3333-4333-8333-333333333333',gen_random_uuid(),'11111111-1111-4111-8111-111111111111','local new default pending');`);
  await db.exec(await readFile(new URL('../database/005-community.sql', import.meta.url), 'utf8'));
  const { rows: upgraded } = await db.query('SELECT id,status,was_public FROM nikon_community_comments ORDER BY id');
  if (upgraded[0].status !== 'deleted' || !upgraded[0].was_public || upgraded[1].status !== 'pending' || upgraded[1].was_public) throw new Error('Idempotent legacy comment migration changed visibility');
  await db.exec("DELETE FROM nikon_community_comments; DELETE FROM nikon_users WHERE id='11111111-1111-4111-8111-111111111111';");
  await db.exec(await readFile(new URL('./community-database-acceptance.sql', import.meta.url), 'utf8'));
  const { rows } = await db.query('SELECT count(*)::integer AS count FROM nikon_users');
  if (rows[0].count !== 0) throw new Error('Acceptance records did not roll back');
  console.log('Community PostgreSQL migrations, transactional acceptance and rollback passed.');
} catch (error) {
  console.error(error.message, error.where || ''); process.exitCode = 1;
} finally { await db.close(); }
