// Disposable PostgreSQL only. No credentials, network, production users or comments.
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { PGlite } from '../../.community-pg-test/node_modules/@electric-sql/pglite/dist/index.js';
const db = new PGlite();
try {
  await db.exec('CREATE ROLE anon; CREATE ROLE authenticated; CREATE ROLE service_role BYPASSRLS;');
  for (const file of (await readdir(new URL('../database/',import.meta.url))).filter(n=>/^\d{3}.*\.sql$/.test(n)&&n.slice(0,3)<='009').sort())
    await db.exec(await readFile(new URL('../database/'+file,import.meta.url),'utf8'));
  await db.exec(await readFile(new URL('../database/009-comment-reply-targets.sql',import.meta.url),'utf8')); // idempotent
  await db.exec(await readFile(new URL('./comment-target-acceptance.sql',import.meta.url),'utf8'));
  assert.equal((await db.query('SELECT count(*)::int AS n FROM nikon_users')).rows[0].n,0);
  // Re-run the older community acceptance against the new dispatcher, not a stub.
  await db.exec(await readFile(new URL('./community-database-acceptance.sql',import.meta.url),'utf8'));
  assert.equal((await db.query('SELECT count(*)::int AS n FROM nikon_users')).rows[0].n,0);
  console.log('PASS: PostgreSQL reply targets, recipients, moderation, legacy behavior and rollback.');
} catch (error) { console.error(error.message,error.where||''); process.exitCode=1; }
finally { await db.close(); }
