import assert from 'node:assert/strict';import{readFile,readdir}from'node:fs/promises';
import{PGlite}from'../../.community-pg-test/node_modules/@electric-sql/pglite/dist/index.js';
const db=new PGlite();try{await db.exec('CREATE ROLE anon;CREATE ROLE authenticated;CREATE ROLE service_role BYPASSRLS;');for(const f of(await readdir(new URL('../database/',import.meta.url))).filter(n=>/^\d{3}.*\.sql$/.test(n)).sort())await db.exec(await readFile(new URL('../database/'+f,import.meta.url),'utf8'));
 await db.exec(await readFile(new URL('../database/011-personal-presets.sql',import.meta.url),'utf8'));
 await db.exec(await readFile(new URL('./personal-acceptance.sql',import.meta.url),'utf8'));
 assert.equal((await db.query('SELECT count(*)::int n FROM nikon_users')).rows[0].n,0);console.log('PASS: personal library account isolation, replay, conflicts, tombstones, limits, private grants, rollback.');
}catch(e){console.error(e.message,e.where||'');process.exitCode=1;}finally{await db.close();}
