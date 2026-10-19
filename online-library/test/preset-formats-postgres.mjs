// Isolated local PostgreSQL WASM acceptance; no cloud access or credentials.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { PGlite } from '../../.community-pg-test/node_modules/@electric-sql/pglite/dist/index.js';
const db = new PGlite();
try {
  await db.exec('CREATE ROLE anon; CREATE ROLE authenticated; CREATE ROLE service_role BYPASSRLS;');
  for (const file of ['001-library.sql','002-users.sql','003-password-accounts.sql','004-preset-categories.sql','005-community.sql','006-app-releases.sql','007-user-avatar.sql']) {
    await db.exec(await readFile(new URL(`../database/${file}`, import.meta.url), 'utf8'));
  }
  const definition = async name => (await db.query('SELECT pg_get_functiondef($1::regprocedure) AS source', [name])).rows[0].source;
  const frozenFunctions = ['nikon_community_author(uuid)', 'nikon_sign_in_qq(text,text,text,text,text,timestamp with time zone)',
    'nikon_avatar_reserve(uuid,uuid,timestamp with time zone,text,text)', 'nikon_avatar_set(uuid,uuid,timestamp with time zone,jsonb)'];
  const frozen = await Promise.all(frozenFunctions.map(definition));
  const dispatcher = await definition('nikon_community(text,uuid,uuid,jsonb)');
  const migration = await readFile(new URL('../database/008-preset-formats.sql', import.meta.url), 'utf8');
  await db.exec(migration); await db.exec(migration);
  for (let i = 0; i < frozenFunctions.length; i++) assert.equal(await definition(frozenFunctions[i]), frozen[i], `preserves ${frozenFunctions[i]}`);
  const changed = await definition('nikon_community(text,uuid,uuid,jsonb)');
  assert.equal(changed, dispatcher.replace("OR asset->>'ncpVersion'<>'0100'", 'OR NOT public.nikon_preset_metadata_valid(asset)'), 'community change is limited to the preset format predicate');
  await db.exec(await readFile(new URL('./preset-formats-database-acceptance.sql', import.meta.url), 'utf8'));
  assert.equal((await db.query('SELECT count(*)::integer AS n FROM nikon_users')).rows[0].n, 0);
  console.log('Preset formats PostgreSQL upgrade/rerun, catalog/feed/detail metadata, legacy defaults, strict format/version pairs, frozen avatar/comment definitions and rollback passed.');
} finally { await db.close(); }
