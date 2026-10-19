// Disposable PostgreSQL; never touches online accounts or messages.
import assert from 'node:assert/strict';
import {readFile,readdir} from 'node:fs/promises';
import {PGlite} from '../../.community-pg-test/node_modules/@electric-sql/pglite/dist/index.js';
const db=new PGlite();
try {
 await db.exec('CREATE ROLE anon; CREATE ROLE authenticated; CREATE ROLE service_role BYPASSRLS;');
 for(const file of (await readdir(new URL('../database/',import.meta.url))).filter(n=>/^\d{3}.*\.sql$/.test(n)).sort())
  await db.exec(await readFile(new URL('../database/'+file,import.meta.url),'utf8'));
 await db.exec(await readFile(new URL('../database/010-comment-moderation-and-read.sql',import.meta.url),'utf8'));
 await db.exec(await readFile(new URL('./comment-moderation-acceptance.sql',import.meta.url),'utf8'));
 // Keep the reply recipient regression under the new server classification contract.
 await db.exec(`CREATE FUNCTION pg_temp.classified_community(a text,u uuid,p uuid,b jsonb DEFAULT '{}'::jsonb) RETURNS jsonb LANGUAGE sql AS $$ SELECT public.nikon_community(a,u,p,CASE WHEN a='comment' AND NOT b ? 'moderation' THEN b||'{"moderation":{"decision":"allow"}}'::jsonb ELSE b END) $$;`);
 await db.exec((await readFile(new URL('./comment-target-acceptance.sql',import.meta.url),'utf8')).replaceAll("nikon_community('","pg_temp.classified_community('"));
 assert.equal((await db.query('SELECT count(*)::int AS n FROM nikon_users')).rows[0].n,0);
 console.log('PASS: per-comment moderation, legacy replay, read ownership/page isolation, recipients, grants and rollback.');
} catch(e){console.error(e.message,e.where||'');process.exitCode=1;} finally{await db.close();}
