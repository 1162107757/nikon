import { cloudApi, ENV_ID, FUNCTION_NAME } from './cloud-tools.mjs';
const result = cloudApi('scf', 'GetFunction', { Namespace: ENV_ID, FunctionName: FUNCTION_NAME, ShowCode: 'FALSE' });
console.log(JSON.stringify({ functionName: result.FunctionName, status: result.Status, runtime: result.Runtime, type: result.Type,
  requestId: result.RequestId }, null, 2));
