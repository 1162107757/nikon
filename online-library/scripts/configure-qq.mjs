import { cloudApi, ENV_ID, FUNCTION_NAME } from './cloud-tools.mjs';
import { withQqAppId } from './qq-config.mjs';

const args = process.argv.slice(2);
if (!args.length || args.includes('--help')) {
  console.log('node scripts/configure-qq.mjs --app-id 1105984855 [--apply]\n' +
    '默认仅核对现有函数；--apply 只更新 QQ_APP_ID 并保留其他环境变量，不读取本地凭据文件或部署代码。');
  process.exit(0);
}
let appId = '', apply = false;
for (let index = 0; index < args.length; index++) {
  if (args[index] === '--app-id' && !appId) appId = args[++index] || '';
  else if (args[index] === '--apply' && !apply) apply = true;
  else throw new Error('参数不正确，请使用 --help 查看用法。');
}
// Validate before requesting any cloud configuration.
withQqAppId([], appId);
const current = cloudApi('scf', 'GetFunction', { Namespace: ENV_ID, FunctionName: FUNCTION_NAME, ShowCode: 'FALSE' });
if (current.FunctionName !== FUNCTION_NAME || current.Status !== 'Active') {
  throw new Error('目标云函数尚未就绪，已停止更新。');
}
const planned = withQqAppId(current.Environment?.Variables, appId);
if (apply && planned.changed) {
  cloudApi('scf', 'UpdateFunctionConfiguration', { Namespace: ENV_ID, FunctionName: FUNCTION_NAME,
    Environment: { Variables: planned.variables } });
}
// No service keys, other environment values, or raw API responses reach output.
console.log(JSON.stringify({ environmentId: ENV_ID, functionName: FUNCTION_NAME, appId,
  action: apply && planned.changed ? 'configuration update requested' : planned.changed ? 'preview only' : 'already configured',
  codeDeployed: false, remaining: 'Wait for Active, then verify the public endpoint and real mobile authorization.' }, null, 2));
