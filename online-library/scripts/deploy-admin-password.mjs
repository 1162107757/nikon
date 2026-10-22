import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { cloudApi, executeSql, sqlRows, ENV_ID, FUNCTION_NAME } from './cloud-tools.mjs';
if (process.argv[2] !== '--deploy') throw new Error('Use --deploy only after approval of the password feature rollout.');
const build = new URL('../build/', import.meta.url), digest = bytes => createHash('sha256').update(bytes).digest('hex');
const variant = process.argv.includes('--minimum-eight') ? 'admin-password-8' : 'admin-password';
const audit = JSON.parse(await readFile(new URL(variant + '-deploy-audit.json', build), 'utf8'));
const archive = await readFile(new URL('nikon-preset-library.zip', build));
if (!audit.scoped || audit.environment !== ENV_ID || digest(archive) !== audit.nextArchiveSha256) throw new Error('Package does not match the scoped audit.');
const ready = sqlRows(executeSql("SELECT to_regprocedure('public.nikon_sign_in_admin(text,text,text,text,timestamptz)') IS NOT NULL AND to_regprocedure('public.nikon_change_admin_password(text,text,text,text)') IS NOT NULL AS ready"));
if (ready.length !== 1 || ![true, 't', 'true'].includes(ready[0].ready)) throw new Error('Apply migration 013 before publishing code.');
let current = cloudApi('scf', 'GetFunction', { Namespace: ENV_ID, FunctionName: FUNCTION_NAME, ShowCode: 'FALSE' });
const configuration = JSON.stringify(current.Environment?.Variables);
if (current.Status !== 'Active' || digest(configuration) !== audit.configurationSha256) throw new Error('Function state/configuration changed since inspection.');
const address = cloudApi('scf', 'GetFunctionAddress', { Namespace: ENV_ID, FunctionName: FUNCTION_NAME });
const url = new URL(address.Url);
if (url.protocol !== 'https:' || !/(?:\.myqcloud\.com|\.tencentcos\.cn)$/.test(url.hostname)) throw new Error('Unexpected archive origin.');
const previous = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(45000) });
if (!previous.ok || digest(Buffer.from(await previous.arrayBuffer())) !== audit.previousArchiveSha256) throw new Error('Production code changed; inspect again before deployment.');
cloudApi('scf', 'UpdateFunctionCode', { Namespace: ENV_ID, FunctionName: FUNCTION_NAME, ZipFile: archive.toString('base64') });
for (let attempt = 0; attempt < 30; attempt++) {
  await new Promise(resolve => setTimeout(resolve, 5000));
  current = cloudApi('scf', 'GetFunction', { Namespace: ENV_ID, FunctionName: FUNCTION_NAME, ShowCode: 'FALSE' });
  if (current.Status === 'Active') {
    if (JSON.stringify(current.Environment?.Variables) !== configuration) throw new Error('Function configuration changed during publication.');
    const result = { environment: ENV_ID, functionName: FUNCTION_NAME, status: current.Status, deployedAt: new Date().toISOString(),
      archiveSha256: audit.nextArchiveSha256, previousArchiveSha256: audit.previousArchiveSha256, changedFiles: audit.changes.map(row => row.path), configurationPreserved: true };
    await writeFile(new URL(variant + '-deployment.json', build), JSON.stringify(result, null, 2) + '\n');
    console.log(JSON.stringify(result, null, 2)); process.exit(0);
  }
  if (!['Updating', 'Publishing', 'Creating'].includes(current.Status)) throw new Error('Unexpected cloud deployment state.');
}
throw new Error('Publication pending; inspect status before retrying.');
