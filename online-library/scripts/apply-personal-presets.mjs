import {readFile} from 'node:fs/promises';import{executeSql,sqlRows,ENV_ID}from'./cloud-tools.mjs';
if(process.argv[2]!=='--apply')throw Error('Use --apply for additive personal library migration011.');
const sql=(await readFile(new URL('../database/011-personal-presets.sql',import.meta.url),'utf8')).replace(/^BEGIN;\r?\n/m,'').replace(/^COMMIT;\s*$/m,'');
executeSql(`DO $personal_migration$ BEGIN\n${sql}\nEND $personal_migration$;`);
const r=sqlRows(executeSql("SELECT to_regclass('public.nikon_personal_presets') IS NOT NULL AS ready,NOT has_table_privilege('anon','public.nikon_personal_presets','SELECT') AS private,NOT has_function_privilege('authenticated','public.nikon_personal_library(text,uuid,text,jsonb)','EXECUTE') AS service_only"));
if(r.length!==1||Object.values(r[0]).some(v=>v!==true&&v!=='true'&&v!=='t'))throw Error('Personal schema verification failed');console.log(JSON.stringify({environment:ENV_ID,migration:'011-personal-presets',verified:true}));
