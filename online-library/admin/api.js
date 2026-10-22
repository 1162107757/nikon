/** Same-origin server session only. Never put CloudBase/service credentials here. */
export class ApiError extends Error {
  constructor(message, code = 'REQUEST_FAILED', status = 0) { super(message); this.name = 'ApiError'; this.code = code; this.status = status; }
}

let csrfToken = '';
// Works both at /admin/ and behind a gateway route such as /preset-library/admin/.
const basePath = new URL('../', import.meta.url).pathname.replace(/\/$/, '');
export async function request(path, { method = 'GET', body, raw = false, type, signal } = {}) {
  const headers = { Accept: 'application/json' };
  if (method !== 'GET' && path !== '/api/login') {
    if (!csrfToken) throw new ApiError('登录状态尚未确认，请重新登录。', 'SESSION_REQUIRED', 401);
    headers['X-CSRF-Token'] = csrfToken;
  }
  if (body !== undefined) headers['Content-Type'] = raw ? type : 'application/json';
  let response;
  try { response = await fetch(basePath + path, { method, headers, credentials: 'same-origin', cache: 'no-store', body: body === undefined ? undefined : raw ? body : JSON.stringify(body), signal }); }
  catch (error) { if (error.name === 'AbortError') throw error; throw new ApiError('无法连接管理服务，请检查网络后重试。', 'NETWORK_ERROR'); }
  let data;
  try { data = await response.json(); }
  catch { throw new ApiError(response.status === 404 ? '管理 API 尚未配置，请将此页面与管理服务部署在同一站点。' : '管理服务返回了无法读取的响应，请稍后重试。', response.status === 404 ? 'NOT_CONFIGURED' : 'INVALID_RESPONSE', response.status); }
  if (!response.ok) throw new ApiError(data?.error?.message || (response.status === 401 ? '登录已过期，请重新登录。' : '操作未完成，请重试。'), data?.error?.code || 'REQUEST_FAILED', response.status);
  if (!data || typeof data !== 'object') throw new ApiError('管理服务响应格式不正确。', 'INVALID_RESPONSE', response.status);
  return data;
}
function session(data) { csrfToken = typeof data.csrfToken === 'string' ? data.csrfToken : ''; return data; }
export const api = {
  request,
  health: () => request('/health'),
  session: async () => session(await request('/api/session')),
  login: async (username, password) => session(await request('/api/login', { method: 'POST', body: { username, password } })),
  logout: async () => { const result = await request('/api/logout', { method: 'POST', body: {} }); csrfToken = ''; return result; },
  changePassword: async (currentPassword, newPassword, confirmPassword) => {
    const result = await request('/api/password', { method: 'POST', body: { currentPassword, newPassword, confirmPassword } });
    csrfToken = ''; return result;
  },
  presets: () => request('/api/presets'),
  users: ({ q = '', status = 'all', page = 1, pageSize = 20 } = {}, { signal } = {}) => request(`/api/users?q=${encodeURIComponent(q)}&status=${encodeURIComponent(status)}&page=${encodeURIComponent(page)}&pageSize=${encodeURIComponent(pageSize)}`, { signal }),
  user: (id, { signal } = {}) => request(`/api/users/${encodeURIComponent(id)}`, { signal }),
  setUserStatus: (id, status, expectedUpdatedAt) => request(`/api/users/${encodeURIComponent(id)}/status`, { method: 'POST', body: { status, expectedUpdatedAt } }),
  createPreset: body => request('/api/presets', { method: 'POST', body }),
  updatePreset: (id, body) => request(`/api/presets/${encodeURIComponent(id)}`, { method: 'PUT', body }),
  publish: (id, expectedRevision) => request(`/api/presets/${encodeURIComponent(id)}/publish`, { method: 'POST', body: { expectedRevision } }),
  unpublish: (id, expectedRevision) => request(`/api/presets/${encodeURIComponent(id)}/unpublish`, { method: 'POST', body: { expectedRevision } }),
  versions: id => request(`/api/presets/${encodeURIComponent(id)}/versions`),
  upload: (kind, file) => {
    if (!['ncp', 'preview'].includes(kind)) throw new ApiError('文件类型不受支持。', 'INVALID_FILE');
    const limit = kind === 'ncp' ? 1024 * 1024 : 4 * 1024 * 1024;
    if (!(file instanceof File) || !file.size || file.size > limit) throw new ApiError(kind === 'ncp' ? '请选择不超过 1 MiB 的 NCP 文件。' : '请选择不超过 4 MiB 的效果图。', 'INVALID_FILE');
    return request(`/api/uploads?kind=${kind}&filename=${encodeURIComponent(file.name)}`, { method: 'POST', raw: true, type: file.type || 'application/octet-stream', body: file });
  },
};
