// Optional isolated local PostgreSQL engine. Never reads cloud credentials or contacts the live DB.
// Reuses the temporary test runtime installed for community-postgres.mjs.
import { readFile } from 'node:fs/promises';
import { PGlite } from '../../.community-pg-test/node_modules/@electric-sql/pglite/dist/index.js';
const db=new PGlite();
try{
  await db.exec('CREATE ROLE anon; CREATE ROLE authenticated; CREATE ROLE service_role BYPASSRLS;');
  for(const file of ['001-library.sql','002-users.sql','003-password-accounts.sql','004-preset-categories.sql','005-community.sql','006-app-releases.sql'])await db.exec(await readFile(new URL(`../database/${file}`,import.meta.url),'utf8'));
  // Recreate the old deployed constraints/schema, then upgrade twice to prove that
  // a rerun replaces the rootmain policy and adds readiness without a fresh DB.
  await db.exec("ALTER TABLE nikon_app_release_audit DROP CONSTRAINT nikon_app_release_audit_actor_check; ALTER TABLE nikon_app_release_audit ADD CONSTRAINT nikon_app_release_audit_actor_check CHECK(actor='rootmain'); ALTER TABLE nikon_app_uploads DROP CONSTRAINT nikon_app_uploads_actor_check; ALTER TABLE nikon_app_uploads ADD CONSTRAINT nikon_app_uploads_actor_check CHECK(actor='rootmain'); ALTER TABLE nikon_app_upload_chunks DROP COLUMN ready;");
  const releaseMigration=await readFile(new URL('../database/006-app-releases.sql',import.meta.url),'utf8');
  await db.exec(releaseMigration);await db.exec(releaseMigration);
  await db.exec(await readFile(new URL('./release-database-acceptance.sql',import.meta.url),'utf8'));
  const {rows}=await db.query('SELECT (SELECT count(*) FROM nikon_app_releases)::integer AS releases,(SELECT count(*) FROM nikon_app_uploads)::integer AS uploads,(SELECT count(*) FROM nikon_app_release_audit)::integer AS audit');
  if(Object.values(rows[0]).some(value=>value!==0))throw new Error('Release acceptance did not roll back');
  console.log('Release PostgreSQL migrations, immutable lifecycle, audit, budget and rollback passed.');
}finally{await db.close();}
