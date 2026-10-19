const APP_ID = /^[0-9]{5,32}$/;

/** Preserve the existing environment as opaque values; never print or persist it. */
export function withQqAppId(variables, appId) {
  if (typeof appId !== 'string' || !APP_ID.test(appId)) throw new Error('请提供有效的 QQ AppID。');
  if (!Array.isArray(variables)) throw new Error('无法核对现有云函数配置，已停止更新。');
  const names = new Set();
  const copied = variables.map(item => {
    if (!item || typeof item.Key !== 'string' || !item.Key || typeof item.Value !== 'string' || names.has(item.Key)) {
      throw new Error('现有云函数环境变量格式异常，已停止更新。');
    }
    names.add(item.Key);
    return { Key: item.Key, Value: item.Value };
  });
  const current = copied.find(item => item.Key === 'QQ_APP_ID');
  const changed = current?.Value !== appId;
  if (current) current.Value = appId;
  else copied.push({ Key: 'QQ_APP_ID', Value: appId });
  return { variables: copied, changed };
}
