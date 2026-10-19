import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
export const ENV_ID = 'nikon-d8girdjsr252fd90e';
export const PUBLIC_ORIGIN = 'https://nikon-d8girdjsr252fd90e-1252602548.ap-shanghai.app.tcloudbase.com';
export const FUNCTION_NAME = 'nikon-preset-library';
export function cloudApi(service, action, body) {
  const result = spawnSync(process.execPath, [fileURLToPath(new URL('tcb-api.mjs', import.meta.url)), service, action], {
    input: JSON.stringify(body), encoding: 'utf8', maxBuffer: 12 * 1024 * 1024, windowsHide: true,
    cwd: fileURLToPath(new URL('../', import.meta.url)), timeout: 150000
  });
  // CLI banners precede JSON; capture all output and never forward it (API responses may contain secrets).
  const output = String(result.stdout || '').replace(/\x1b\[[0-9;]*m/g, '');
  let parsed;
  try { parsed = JSON.parse(output.slice(output.indexOf('{'))); } catch {}
  const response = parsed?.data || parsed?.Response || parsed;
  if (result.status !== 0 || !response || response.Error || response.SCFErrorCode) {
    const code = response?.Error?.Code || response?.SCFErrorCode || 'CLOUD_API_FAILED';
    const error = new Error(`${action} 未完成（${/^[A-Za-z0-9_.-]+$/.test(code) ? code : 'CLOUD_API_FAILED'}）。`);
    error.code = code;
    throw error;
  }
  return response;
}
export function executeSql(sql) { return cloudApi('tcb', 'ExecutePGSql', { EnvId: ENV_ID, Sql: sql }); }
export function sqlRows(result) {
  return (result.Rows || []).map(row => { const values = typeof row === 'string' ? JSON.parse(row) : row;
    if (Array.isArray(values)) return Object.fromEntries(result.Columns.map((name, index) => [typeof name === 'string' ? name : name.Name,
      result.ColumnTypes?.[index] === 'bool' && ['true', 'false'].includes(values[index]) ? values[index] === 'true' : values[index]]));
    return values;
  });
}
export function quoteSql(value) { return "'" + String(value).replaceAll("'", "''") + "'"; }
