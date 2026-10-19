import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { ServiceError } from './cloudbase.mjs';
import { inspectApk, MAX_APK_BYTES, APK_PACKAGE, APK_SIGNER_SHA256 } from './apk.mjs';
import { createAbuseContext } from './abuse.mjs';

export const APK_CHUNK_BYTES = 512 * 1024;
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const digest = value => createHash('sha256').update(value).digest('hex');
const ERRORS = {
  FORBIDDEN: [403, '请使用有效的后台管理员账号管理 App 版本。'],
  CONFLICT: [409, '版本已改变，请刷新后重试。'], DUPLICATE: [409, '这个 versionCode 或安装包已存在，版本不可重复或覆盖。'],
  INVALID: [400, '版本信息不完整，请检查后重试。'], NOT_FOUND: [404, '版本或上传任务不存在。'],
  UPLOAD_EXPIRED: [409, '上传任务已过期或已完成，请重新选择安装包。'],
  INCOMPLETE: [409, '安装包尚未传完，请重新上传。'], BUDGET: [429, '请求次数或今日流量达到上限，请稍后重试。'],
  DOWNGRADE: [409, '不能发布比本渠道已发布版本更旧的版本。'], CONFIRMATION: [400, '请先复核并确认本次发布。']
};
export class ReleaseStore {
  constructor(store) { this.store = store; }
  async rpc(name, body) {
    const result = await this.store.rpc(name, body);
    if (result?.error) { const [status, message] = ERRORS[result.error] || [503, '版本服务暂时不可用，请稍后重试。']; throw new ServiceError(status, `RELEASE_${result.error}`, message); }
    return result;
  }
  async rows() { return this.store.rest('nikon_app_releases?select=*&order=version_code.desc&limit=200'); }
  async row(id) { return (await this.store.rest(`nikon_app_releases?id=eq.${id}&select=*&limit=1`))[0] || null; }
  async latest(channel) {
    // A withdrawal is a high-water mark. Never substitute an older release silently.
    return (await this.store.rest(`nikon_app_releases?channel=eq.${channel}&status=in.(published,withdrawn)&select=*&order=version_code.desc&limit=1`))[0] || null;
  }
  async audit(id) { return this.store.rest(`nikon_app_release_audit?release_id=eq.${id}&select=id,action,actor,created_at,details&order=created_at.desc&limit=100`); }
}
function channelOf(value) { if (!['stable', 'beta'].includes(value)) throw new ServiceError(400, 'INVALID_CHANNEL', '请选择正式版或测试版。'); return value; }
function notesOf(value) {
  if (typeof value !== 'string' || value.trim().length < 1 || value.length > 6000 || /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(value)) throw new ServiceError(400, 'INVALID_NOTES', '请填写 1–6000 字的更新说明。');
  return value.trim();
}
function integerQuery(params, key, fallback) {
  const value = params.get(key); if (value === null) return fallback;
  if (!/^\d{1,10}$/.test(value) || Number(value) > 2147483647) throw new ServiceError(400, 'INVALID_VERSION_QUERY', 'Android 或版本参数不正确。');
  return Number(value);
}
function revision(body) { if (!Number.isSafeInteger(body.expectedRevision) || body.expectedRevision < 1) throw new ServiceError(400, 'REVISION_REQUIRED', '请刷新后再操作这个版本。'); return body.expectedRevision; }
function rowView(row, base) {
  return { id: row.id, packageName: row.package_name, versionName: row.version_name, versionCode: row.version_code,
    minSdk: row.min_sdk, byteLength: row.byte_length, sha256: row.sha256, signerSha256: row.signer_sha256,
    channel: row.channel, notes: row.notes, publishedAt: row.published_at,
    downloadUrl: `${base}/releases/${row.id}/file` };
}
function adminView(row, base) { return { ...rowView(row, base), status: row.status, revision: row.revision, createdAt: row.created_at, withdrawnAt: row.withdrawn_at }; }
export function selectRelease(row, { channel = 'stable', versionCode = 0, sdk = 2147483647, base = '' } = {}) {
  let reason = 'no_release', release = null;
  if (row?.status === 'withdrawn') reason = 'withdrawn';
  else if (row?.status === 'published') {
    release = rowView(row, base);
    reason = sdk < row.min_sdk ? 'incompatible' : versionCode > row.version_code ? 'client_newer' : versionCode === row.version_code ? 'current' : 'available';
  }
  return { schemaVersion: 1, channel, release, updateAvailable: reason === 'available', reason };
}
export function createReleaseRoutes({ store, storage, publicBaseUrl, basePath = '/preset-library', trustedProxyHops = 0, trustedProxyAddresses }) {
  const releases = new ReleaseStore(store), prefix = basePath.replace(/\/$/, ''), base = new URL(publicBaseUrl).origin + prefix;
  const source = createAbuseContext({ store, secret: store.serviceKey || 'app-releases-local-source-budget-v1', trustedProxyHops, trustedProxyAddresses });
  const staticFiles = { 'index.html': 'text/html; charset=utf-8', 'download.js': 'text/javascript; charset=utf-8', 'download.css': 'text/css; charset=utf-8', 'tokens.css': 'text/css; charset=utf-8', 'icon.png': 'image/png', 'qr.js': 'text/javascript; charset=utf-8', 'preview-community.png':'image/png', 'preview-submission.png':'image/png', 'preview-messages.png':'image/png' };
  async function budget(req, kind, bytes = 0) {
    // Proxy identity is used only with explicitly configured trusted hops; IPv6 rotates within a /64 bucket.
    const key = source.context(req).sourceHash;
    await releases.rpc('nikon_release_budget_atomic', { p_key: key, p_kind: kind, p_bytes: bytes });
  }
  async function publicRoute(req, res, { path, url, json }) {
    if (path === '/download') { res.writeHead(302, { Location: `${prefix}/download/` }); res.end(); return true; }
    if (path.startsWith('/download/')) {
      if (!['GET', 'HEAD'].includes(req.method)) throw new ServiceError(405, 'METHOD_NOT_ALLOWED', '请使用 GET 打开下载页。');
      const name = path.slice('/download/'.length) || 'index.html';
      if (!Object.hasOwn(staticFiles, name)) throw new ServiceError(404, 'NOT_FOUND', '页面不存在。');
      const file = await readFile(new URL(`../download/${name}`, import.meta.url));
      res.writeHead(200, { 'Content-Type': staticFiles[name], 'Content-Length': file.length, 'Cache-Control': 'no-cache', 'X-Content-Type-Options': 'nosniff' });
      res.end(req.method === 'HEAD' ? undefined : file); return true;
    }
    if (path === '/releases/latest') {
      if (req.method !== 'GET') throw new ServiceError(405, 'METHOD_NOT_ALLOWED', '请使用 GET 查询版本。');
      const channel = channelOf(url.searchParams.get('channel') || 'stable');
      const versionCode = integerQuery(url.searchParams, 'versionCode', 0), sdk = integerQuery(url.searchParams, 'sdk', 2147483647);
      await budget(req, 'metadata');
      res.setHeader('Cache-Control', 'no-store');
      json(res, selectRelease(await releases.latest(channel), { channel, versionCode, sdk, base })); return true;
    }
    const file = /^\/releases\/([a-f0-9-]+)\/file$/.exec(path);
    if (!file) return false;
    if (req.method !== 'GET') { res.setHeader('Allow', 'GET'); throw new ServiceError(405, 'METHOD_NOT_ALLOWED', '请使用 GET 下载安装包。'); }
    if (!UUID.test(file[1])) throw new ServiceError(404, 'RELEASE_NOT_FOUND', '版本不存在或已下架。');
    res.setHeader('Cache-Control', 'no-store, max-age=0');
    const row = await releases.row(file[1]);
    if (row?.status !== 'published') throw new ServiceError(404, 'RELEASE_NOT_FOUND', '版本不存在或已下架。');
    await budget(req, 'download', row.byte_length);
    // Status and budget checks precede conditional response, so withdrawn files never return 304.
    const etag = `"sha256-${row.sha256}"`;
    if (req.headers['if-none-match'] === etag) { res.writeHead(304, { ETag: etag }); res.end(); return true; }
    if (!/^app-releases\/[a-f0-9]{64}\.apk$/.test(row.storage_path)) throw new ServiceError(503, 'RELEASE_FILE_INVALID', '安装包位置校验失败。');
    const data = await storage.get(row.storage_path, MAX_APK_BYTES);
    if (data.length !== row.byte_length || digest(data) !== row.sha256) throw new ServiceError(503, 'RELEASE_FILE_CHANGED', '安装包校验失败，请联系管理员。');
    // Re-check after remote I/O so withdrawal during a read prevents a new download response.
    if ((await releases.row(row.id))?.status !== 'published') throw new ServiceError(404, 'RELEASE_NOT_FOUND', '这个版本已下架。');
    res.writeHead(200, { 'Content-Type': 'application/vnd.android.package-archive', 'Content-Length': data.length,
      ETag: etag, 'X-Content-SHA256': row.sha256, 'X-Content-Type-Options': 'nosniff',
      'Content-Disposition': `attachment; filename="Huevia-${row.version_code}-${row.channel}.apk"` });
    res.end(data); return true;
  }
  async function adminRoute(req, res, { path, url, session, json, readBody, readJson }) {
    if (!path.startsWith('/api/releases')) return false;
    const administrator = session?.username ? await store.findAdmin(session.username) : null;
    if (!administrator || administrator.disabled) throw new ServiceError(403, 'RELEASE_FORBIDDEN', '请使用有效的后台管理员账号管理 App 版本。');
    const actor = session.username;
    const uploadBudget = (bytes = 0, countRequest = false) => releases.rpc('nikon_app_upload_budget', { p_actor: actor, p_bytes: bytes, p_count_request: countRequest });
    if (!['GET', 'HEAD', 'OPTIONS'].includes(req.method)) await uploadBudget(0, true);
    if (path === '/api/releases' && req.method === 'GET') {
      json(res, { items: (await releases.rows()).map(row => adminView(row, base)), downloadPageUrl: `${base}/download/`,
        limits: { maxApkBytes: MAX_APK_BYTES, chunkBytes: APK_CHUNK_BYTES }, packageName: APK_PACKAGE, signerSha256: APK_SIGNER_SHA256 }); return true;
    }
    if (path === '/api/releases/uploads' && req.method === 'POST') {
      const body = await readJson(req);
      if (!Number.isInteger(body.byteLength) || body.byteLength < 1 || body.byteLength > MAX_APK_BYTES) throw new ServiceError(400, 'INVALID_APK_SIZE', '请选择不超过 4 MiB 的 APK。');
      const result = await releases.rpc('nikon_app_upload_begin', { p_actor: actor, p_size: body.byteLength, p_channel: channelOf(body.channel), p_notes: notesOf(body.notes) });
      json(res, { ...result, chunkBytes: APK_CHUNK_BYTES, maxApkBytes: MAX_APK_BYTES }, 201); return true;
    }
    const upload = /^\/api\/releases\/uploads\/([a-f0-9-]+)(?:\/(chunks\/([0-7])|complete))?$/.exec(path);
    if (upload && UUID.test(upload[1])) {
      const uploadId = upload[1];
      if (upload[2]?.startsWith('chunks/') && req.method === 'PUT') {
        const index = Number(upload[3]), data = await readBody(req, APK_CHUNK_BYTES);
        // Reserve the actual bytes before a storage write or a conflicting retry. The
        // independent request counter also bounds empty and otherwise invalid requests.
        await uploadBudget(data.length);
        if (!data.length) throw new ServiceError(400, 'EMPTY_CHUNK', '上传分块为空。');
        const sha256 = digest(data), storagePath = `app-upload/${uploadId}/${index}-${sha256}.part`;
        await releases.rpc('nikon_app_upload_reserve', { p_actor: actor, p_id: uploadId, p_index: index, p_size: data.length, p_sha256: sha256, p_path: storagePath });
        await storage.put(storagePath, data, 'application/octet-stream');
        await releases.rpc('nikon_app_upload_chunk', { p_actor: actor, p_id: uploadId, p_index: index, p_size: data.length, p_sha256: sha256, p_path: storagePath });
        json(res, { uploaded: true, index }); return true;
      }
      if (upload[2] === 'complete' && req.method === 'POST') {
        await readJson(req);
        const task = await releases.rpc('nikon_app_upload_claim', { p_actor: actor, p_id: uploadId });
        const chunks = [];
        for (const chunk of task.chunks) {
          const data = await storage.get(chunk.path, APK_CHUNK_BYTES);
          if (data.length !== chunk.size || digest(data) !== chunk.sha256) throw new ServiceError(400, 'UPLOAD_CHANGED', '上传分块校验失败，请重新上传。');
          chunks.push(data);
        }
        const apk = Buffer.concat(chunks), metadata = inspectApk(apk), storagePath = `app-releases/${metadata.sha256}.apk`;
        if (metadata.byteLength !== task.byteLength) throw new ServiceError(400, 'UPLOAD_SIZE_MISMATCH', 'APK 大小不一致。');
        await uploadBudget(apk.length);
        await storage.put(storagePath, apk, 'application/vnd.android.package-archive');
        const row = await releases.rpc('nikon_app_release_create', { p_actor: actor, p_upload_id: uploadId, p_metadata: metadata, p_path: storagePath });
        json(res, { release: adminView(row, base) }, 201); return true;
      }
    }
    const match = /^\/api\/releases\/([a-f0-9-]+)(?:\/(publish|withdraw|audit))?$/.exec(path);
    if (match && UUID.test(match[1])) {
      if (match[2] === 'audit' && req.method === 'GET') { json(res, { items: await releases.audit(match[1]) }); return true; }
      if (['PUT', 'POST'].includes(req.method)) {
        const body = await readJson(req), action = match[2] || 'edit';
        if ((action === 'edit' && req.method !== 'PUT') || (action !== 'edit' && req.method !== 'POST')) return false;
        const row = await releases.rpc('nikon_app_release_action', { p_actor: actor, p_id: match[1], p_revision: revision(body), p_action: action,
          p_notes: action === 'edit' ? notesOf(body.notes) : '', p_confirmation: typeof body.confirmation === 'string' ? body.confirmation : '' });
        json(res, { release: adminView(row, base) }); return true;
      }
    }
    throw new ServiceError(404, 'RELEASE_ROUTE_NOT_FOUND', '版本操作不存在。');
  }
  return { publicRoute, adminRoute };
}
