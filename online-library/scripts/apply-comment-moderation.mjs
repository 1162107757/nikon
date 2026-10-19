import {readFile} from 'node:fs/promises';
import {executeSql,sqlRows,ENV_ID} from './cloud-tools.mjs';
if(process.argv[2]!=='--apply')throw Error('Use --apply to apply only migration 010.');
const migration=(await readFile(new URL('../database/010-comment-moderation-and-read.sql',import.meta.url),'utf8'))
 .replace(/^BEGIN;\r?\n/m,'').replace(/^COMMIT;\s*$/m,'');
executeSql(`DO $comment_moderation_migration$ BEGIN\n${migration}\nEND $comment_moderation_migration$;`);
const rows=sqlRows(executeSql(`SELECT
 strpos(pg_get_functiondef('public.nikon_community(text,uuid,uuid,jsonb)'::regprocedure),'nikon_community_read_messages')>0 AS batch_ready,
 strpos(pg_get_functiondef('public.nikon_community(text,uuid,uuid,jsonb)'::regprocedure),'''decision''=''allow''')>0 AS moderation_ready,
 NOT has_function_privilege('anon','public.nikon_community_read_messages(uuid,jsonb)','EXECUTE') AS private_ready`));
if(rows.length!==1||Object.values(rows[0]).some(v=>v!==true&&v!=='t'&&v!=='true'))throw Error('Live moderation/read schema verification failed.');
console.log(JSON.stringify({environment:ENV_ID,migration:'010-comment-moderation-and-read',verified:true}));
