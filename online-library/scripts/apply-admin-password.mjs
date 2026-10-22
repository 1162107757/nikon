import { readFile } from 'node:fs/promises';
import { executeSql, sqlRows, ENV_ID } from './cloud-tools.mjs';

if (process.argv[2] !== '--apply') throw new Error('Use --apply only after approval to apply migration 013.');
const migration = await readFile(new URL('../database/013-admin-password.sql', import.meta.url), 'utf8');
// One transaction adds only the two functions and their grants; it never reads or changes account credentials.
executeSql(`DO $admin_password_migration$ BEGIN\n${migration}\nEND $admin_password_migration$;`);
const rows = sqlRows(executeSql(`SELECT
  to_regprocedure('public.nikon_sign_in_admin(text,text,text,text,timestamptz)') IS NOT NULL AS login_ready,
  to_regprocedure('public.nikon_change_admin_password(text,text,text,text)') IS NOT NULL AS password_ready,
  NOT has_function_privilege('anon','public.nikon_sign_in_admin(text,text,text,text,timestamptz)','EXECUTE') AS login_anon_denied,
  NOT has_function_privilege('authenticated','public.nikon_sign_in_admin(text,text,text,text,timestamptz)','EXECUTE') AS login_user_denied,
  NOT has_function_privilege('anon','public.nikon_change_admin_password(text,text,text,text)','EXECUTE') AS password_anon_denied,
  NOT has_function_privilege('authenticated','public.nikon_change_admin_password(text,text,text,text)','EXECUTE') AS password_user_denied,
  has_function_privilege('service_role','public.nikon_sign_in_admin(text,text,text,text,timestamptz)','EXECUTE') AS login_service_ready,
  has_function_privilege('service_role','public.nikon_change_admin_password(text,text,text,text)','EXECUTE') AS password_service_ready`));
if (rows.length !== 1 || Object.values(rows[0]).some(value => ![true, 't', 'true'].includes(value))) {
  throw new Error('Administrator password schema verification failed; do not deploy code.');
}
console.log(JSON.stringify({ environment: ENV_ID, migration: '013-admin-password', verified: true }));
