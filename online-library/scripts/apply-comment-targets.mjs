import {readFile} from 'node:fs/promises';
import {executeSql,sqlRows,ENV_ID} from './cloud-tools.mjs';
if(process.argv[2]!=='--apply')throw Error('Use --apply to apply only comment target migration 009.');
const migration=(await readFile(new URL('../database/009-comment-reply-targets.sql',import.meta.url),'utf8'))
 .replace(/^BEGIN;\r?\n/m,'').replace(/^COMMIT;\s*$/m,'');
executeSql(`DO $comment_target_migration$ BEGIN\n${migration}\nEND $comment_target_migration$;`);
const rows=sqlRows(executeSql(`SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='nikon_community_comments' AND column_name='reply_to_id') AS column_ready,
 strpos(pg_get_functiondef('public.nikon_community(text,uuid,uuid,jsonb)'::regprocedure),'VALUES(reply_target.user_id,new_id,target_parent)')>0 AS recipient_ready,
 strpos(pg_get_functiondef('public.nikon_community_comment_view(uuid)'::regprocedure),'''replyTo''')>0 AS projection_ready`));
if(rows.length!==1||Object.values(rows[0]).some(v=>v!==true&&v!=='t'&&v!=='true'))throw Error('Live comment target schema verification failed.');
console.log(JSON.stringify({environment:ENV_ID,migration:'009-comment-reply-targets',verified:true}));
