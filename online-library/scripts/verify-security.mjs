import assert from 'node:assert/strict';
import { executeSql, sqlRows, ENV_ID } from './cloud-tools.mjs';
const rows = sqlRows(executeSql(`SELECT c.relname AS name,c.relrowsecurity AS rls,
  has_table_privilege('anon',c.oid,'SELECT,INSERT,UPDATE,DELETE') AS anon_access,
  has_table_privilege('authenticated',c.oid,'SELECT,INSERT,UPDATE,DELETE') AS user_access
  FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname='public' AND c.relname IN ('nikon_admins','nikon_sessions','nikon_login_attempts','nikon_assets','nikon_presets','nikon_preset_versions') ORDER BY c.relname;`));
assert.equal(rows.length, 6);
for (const row of rows) { assert.equal(row.rls, true); assert.equal(row.anon_access, false); assert.equal(row.user_access, false); }
const functions = sqlRows(executeSql(`SELECT p.proname AS name,
  has_function_privilege('anon',p.oid,'EXECUTE') AS anon_access,
  has_function_privilege('authenticated',p.oid,'EXECUTE') AS user_access,
  has_function_privilege('service_role',p.oid,'EXECUTE') AS server_access
  FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
  WHERE n.nspname='public' AND p.proname IN ('nikon_login_allowed','nikon_save_preset','nikon_catalog_manifest','nikon_publish_preset');`));
assert.equal(functions.length, 4);
for (const fn of functions) { assert.equal(fn.anon_access, false); assert.equal(fn.user_access, false); assert.equal(fn.server_access, true); }
const response = await fetch(`https://${ENV_ID}.api.tcloudbasegateway.com/v1/rdb/rest/nikon_admins?select=username&limit=1`, { signal: AbortSignal.timeout(20000) });
assert.ok([401, 403, 404].includes(response.status));
console.log(`Cloud security verified: ${rows.length} RLS tables and ${functions.length} RPC functions are server-only; anonymous direct data access denied (${response.status}).`);
