import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
import { hashPassword, verifyPassword } from '../server/auth.mjs';
import { cloudApi, executeSql, sqlRows, quoteSql, ENV_ID, PUBLIC_ORIGIN, FUNCTION_NAME } from './cloud-tools.mjs';

const options = new Set(process.argv.slice(2));
if (options.has('--help') || !options.size) {
  console.log('node scripts/setup-cloud.mjs --migrate | --bootstrap-admin | --deploy --allow-service-credential [--configure-qq]\n' +
    'migrate: initialize this environment only; bootstrap-admin: create initial admin without resetting an existing account;\n' +
    'deploy: requires exact user approval to create/read/save the backend service_role credential.');
  process.exit(0);
}
const root = new URL('../', import.meta.url), local = new URL('.local/', root);
await mkdir(local, { recursive: true });
async function readLocal(name) { try { return JSON.parse(await readFile(new URL(name, local), 'utf8')); } catch (error) { if (error.code === 'ENOENT') return null; throw error; } }
async function saveLocal(name, value) { await writeFile(new URL(name, local), JSON.stringify(value, null, 2) + '\n', { mode: 0o600 }); }

if (options.has('--migrate')) {
  const migrations = (await readdir(new URL('database/', root))).filter(name => /^\d{3}-[a-z0-9-]+\.sql$/.test(name)).sort();
  if (!migrations.length) throw new Error('未找到数据库迁移，已停止。');
  for (const name of migrations) {
    let migration = await readFile(new URL('database/' + name, root), 'utf8');
    // ExecutePGSql supplies one atomic DO block; remove a migration's standalone transaction.
    migration = migration.replace(/^BEGIN;\r?\n/m, '').replace(/^COMMIT;\s*$/m, '');
    executeSql(`DO $nikon_migration$ BEGIN\n${migration}\nEND $nikon_migration$;`);
    console.log(`Database migration applied: ${name}`);
  }
  executeSql("INSERT INTO storage.buckets(id,name,public,file_size_limit,allowed_mime_types) VALUES ('nikon-presets','nikon-presets',false,4194304,ARRAY['application/octet-stream','image/png','image/jpeg','image/gif','image/webp']) ON CONFLICT(id) DO NOTHING;");
  executeSql("UPDATE storage.buckets SET allowed_mime_types=ARRAY['application/octet-stream','application/vnd.android.package-archive','image/png','image/jpeg','image/gif','image/webp'] WHERE id='nikon-presets' AND public=false;");
  const buckets = sqlRows(executeSql("SELECT id,public FROM storage.buckets WHERE id='nikon-presets'"));
  if (buckets.length !== 1 || buckets[0].public !== false) throw new Error('预设文件桶不是预期的私有桶，已停止部署。');
  console.log(`Database schema and private bucket ready: ${ENV_ID}`);
}
if (options.has('--bootstrap-admin')) {
  const existing = sqlRows(executeSql("SELECT username,password_hash FROM public.nikon_admins WHERE username='admin'"));
  let initial = await readLocal('initial-admin.json');
  if (existing.length) {
    if (!initial || !await verifyPassword(initial.password, existing[0].password_hash)) throw new Error('管理员已存在；保留原密码，请使用现有账号。');
    console.log('Existing initial administrator preserved.');
  } else {
    if (!initial) { initial = { username: 'admin', password: randomBytes(24).toString('base64url'), loginUrl: PUBLIC_ORIGIN + '/preset-library/admin/' }; await saveLocal('initial-admin.json', initial); }
    const hash = await hashPassword(initial.password);
    executeSql(`INSERT INTO public.nikon_admins(username,password_hash) VALUES ('admin',${quoteSql(hash)}) ON CONFLICT(username) DO NOTHING;`);
    console.log('Initial administrator created; credentials are in ignored .local/initial-admin.json (not printed).');
  }
}
if (options.has('--deploy')) {
  if (!options.has('--allow-service-credential')) throw new Error('部署需先获得用户对后台服务密钥操作的明确授权，再传 --allow-service-credential。');
  let credential = await readLocal('service-credential.json');
  if (!credential) {
    const created = cloudApi('tcb', 'CreateApiKey', { EnvId: ENV_ID, KeyType: 'api_key', KeyName: 'nikon-preset-library-server' });
    if (!created.ApiKey || !created.KeyId) throw new Error('未返回服务密钥；不要重复创建，请先核对云端密钥状态。');
    credential = { environmentId: ENV_ID, keyId: created.KeyId, serviceKey: created.ApiKey, createdAt: created.CreateAt };
    await saveLocal('service-credential.json', credential);
  }
  if (credential.environmentId !== ENV_ID) throw new Error('部署凭据不属于目标环境。');
  const archive = await readFile(new URL('build/nikon-preset-library.zip', root));
  const variables = Object.entries({ CLOUDBASE_ENV_ID: ENV_ID, CLOUDBASE_SERVICE_KEY: credential.serviceKey,
    CLOUDBASE_STORAGE_BUCKET: 'nikon-presets', PUBLIC_BASE_URL: PUBLIC_ORIGIN, BASE_PATH: '/preset-library' }).map(([Key, Value]) => ({ Key, Value }));
  let security = await readLocal('community-security.json');
  if (!security) { security = { challengeSecret: randomBytes(48).toString('base64url'), trustedProxyHops: 0, trustedProxyAddresses: ['127.0.0.1','::1'] }; await saveLocal('community-security.json', security); }
  if (typeof security.challengeSecret !== 'string' || security.challengeSecret.length < 24
      || !Number.isInteger(security.trustedProxyHops) || security.trustedProxyHops < 0 || security.trustedProxyHops > 8
      || !Array.isArray(security.trustedProxyAddresses)) throw new Error('本地防刷配置无效，已停止部署。');
  variables.push({ Key: 'CHALLENGE_SECRET', Value: security.challengeSecret },
    { Key: 'TRUSTED_PROXY_HOPS', Value: String(security.trustedProxyHops) },
    { Key: 'TRUSTED_PROXY_ADDRESSES', Value: security.trustedProxyAddresses.join(',') });
  if (options.has('--configure-qq')) {
    if (!/^\d{5,32}$/.test(process.env.QQ_APP_ID || '')) throw new Error('请先设置真实 QQ 互联应用的 QQ_APP_ID。');
    variables.push({ Key: 'QQ_APP_ID', Value: process.env.QQ_APP_ID });
  }
  const state = await readLocal('deployment-state.json');
  if (!state?.functionCreated) {
    cloudApi('tcb', 'CreateFunction', { EnvId: ENV_ID, FunctionName: FUNCTION_NAME, Runtime: 'Nodejs20.19', Type: 'HTTP',
      MemorySize: 256, Timeout: 60, InitTimeout: 30, InstallDependency: 'FALSE', Description: 'Nikon preset library administration and public catalog',
      Code: { ZipFile: archive.toString('base64') }, Environment: { Variables: variables } });
    await saveLocal('deployment-state.json', { functionCreated: true, functionName: FUNCTION_NAME, deployedAt: new Date().toISOString() });
  } else {
    // Configuration updates replace the full variable list. Preserve separately configured provider settings.
    const current = cloudApi('scf', 'GetFunction', { Namespace: ENV_ID, FunctionName: FUNCTION_NAME, ShowCode: 'FALSE' });
    if (!Array.isArray(current.Environment?.Variables)) throw new Error('无法核对现有云函数配置，已停止更新。');
    const merged = new Map(current.Environment.Variables.map(item => [item.Key, item.Value]));
    for (const { Key, Value } of variables) merged.set(Key, Value);
    cloudApi('scf', 'UpdateFunctionCode', { Namespace: ENV_ID, FunctionName: FUNCTION_NAME, ZipFile: archive.toString('base64') });
    cloudApi('scf', 'UpdateFunctionConfiguration', { Namespace: ENV_ID, FunctionName: FUNCTION_NAME,
      Environment: { Variables: [...merged].map(([Key, Value]) => ({ Key, Value })) }, MemorySize: 256, Timeout: 60 });
  }
  console.log('Cloud function code/configuration deployed. HTTP gateway routing and live acceptance still need verification.');
}
