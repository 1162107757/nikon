import { randomUUID } from 'node:crypto';
import { ServiceError } from './cloudbase.mjs';
import { sha256, LoginGate } from './auth.mjs';
import { validatePreview } from './validation.mjs';

const MAX_AVATAR = 1024 * 1024;
const UUID = '[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}';
const AVATAR_PATH = new RegExp(`^/avatars/(${UUID})\\.jpg$`);
const FIELDS = ['id', 'username', 'nickname', 'avatarUrl', 'avatarSource', 'provider', 'status', 'createdAt', 'lastLoginAt', 'loginCount', 'updatedAt'];
function fail(status, code, message) { throw new ServiceError(status, code, message); }
function view(user) { return Object.fromEntries(FIELDS.map(field => [field, user[field]])); }
function revision(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$/.test(value)
    || !Number.isFinite(Date.parse(value))) fail(400, 'USER_VERSION_REQUIRED', '请刷新账号信息后重试。');
  return value;
}
function bearer(req) {
  if (req.rawHeaders?.filter((value, index) => index % 2 === 0 && value.toLowerCase() === 'authorization').length > 1) return '';
  return /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization || '')?.[1] || '';
}

/** The native client decodes, center-crops and re-encodes the selected image before upload. */
export function validateAvatar(data, contentType) {
  if (!Buffer.isBuffer(data) || !data.length || data.length > MAX_AVATAR) fail(400, 'INVALID_AVATAR', '头像须为不超过 1 MiB 的 JPEG 图片。');
  if (typeof contentType !== 'string' || contentType.split(';', 1)[0].trim().toLowerCase() !== 'image/jpeg'
    || data[0] !== 0xff || data[1] !== 0xd8) fail(400, 'INVALID_AVATAR', '头像须为 JPEG 图片。');
  let details;
  try { details = validatePreview(data, 'image/jpeg'); }
  catch { fail(400, 'INVALID_AVATAR', '头像图片不完整，请重新选择。'); }
  // validatePreview has already verified every segment and bounded the whole container.
  let offset = 2, width = 0, height = 0;
  while (offset < data.length) {
    while (data[offset] === 0xff) offset++;
    const marker = data[offset++];
    if (marker === 0xd9 || marker === 0xda) break;
    if (marker === 0x01) continue;
    const length = data.readUInt16BE(offset);
    if ([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf].includes(marker)) {
      height = data.readUInt16BE(offset + 3); width = data.readUInt16BE(offset + 5); break;
    }
    offset += length;
  }
  if (width !== height || width < 64 || width > 1024) fail(400, 'INVALID_AVATAR', '头像须为 64 至 1024 像素的正方形图片。');
  return { ...details, width, height };
}

export function createProfileRoutes({ store, storage, publicBaseUrl, basePath = '/preset-library', abuseService }) {
  const origin = new URL(publicBaseUrl).origin, prefix = basePath.replace(/\/$/, '');
  const uploadGate = new LoginGate({ maximum: 40, concurrent: 3 });
  async function account(req) {
    if (req.headers.origin && req.headers.origin !== origin) fail(403, 'ORIGIN_REJECTED', '请从 App 发起操作。');
    const token = bearer(req), session = token && await store.userSession(sha256(token));
    if (!session?.user || session.user.status !== 'active') fail(401, 'USER_LOGIN_REQUIRED', '登录已失效，请重新登录。');
    return session.user;
  }
  return { async publicRoute(req, res, { path, url, json, readBody, readJson }) {
    const match = path.match(AVATAR_PATH);
    if (match) {
      if (req.method !== 'GET') { res.setHeader('Allow', 'GET'); fail(405, 'METHOD_NOT_ALLOWED', '请使用 GET 读取头像。'); }
      // Public media URLs contain only an opaque asset ID; reject credential-bearing query URLs.
      if (url.search) fail(400, 'INVALID_AVATAR_URL', '头像地址格式不正确。');
      const asset = await store.avatarAsset(match[1]);
      if (!asset || asset.storagePath !== `avatars/${match[1]}.jpg` || asset.mimeType !== 'image/jpeg'
        || !Number.isSafeInteger(asset.byteLength) || asset.byteLength < 1 || asset.byteLength > MAX_AVATAR
        || !/^[a-f0-9]{64}$/.test(asset.sha256)) fail(404, 'AVATAR_NOT_FOUND', '头像不存在。');
      const data = await storage.get(asset.storagePath, MAX_AVATAR);
      if (data.length !== asset.byteLength || sha256(data) !== asset.sha256) fail(503, 'FILE_CHANGED', '头像校验失败，请稍后重试。');
      res.setHeader('Content-Type', 'image/jpeg'); res.setHeader('Content-Length', data.length);
      res.setHeader('Content-Disposition', 'inline; filename="avatar.jpg"');
      res.setHeader('Content-Security-Policy', "default-src 'none'; sandbox");
      res.setHeader('Cross-Origin-Resource-Policy', 'cross-origin');
      res.setHeader('Cache-Control', 'public, max-age=300');
      res.setHeader('ETag', `"${asset.sha256}"`); res.writeHead(200); res.end(data); return true;
    }
    if (!path.startsWith('/profile/')) return false;
    const user = await account(req);
    if (path === '/profile/avatar' && req.method === 'PUT') {
      const release = uploadGate.acquire();
      if (!release) fail(429, 'AVATAR_BUSY', '头像上传较多，请稍后再试。');
      try {
        const expectedUpdatedAt = revision(req.headers['x-expected-updated-at']);
        const context = abuseService.context(req, req.headers['x-installation-id']);
        const id = randomUUID();
        // This RPC commits the account/source/installation/global counters and full byte reservation
        // before any body is read. Failed transfers and malformed bytes cannot refund their budget.
        const reservation = await store.reserveAvatar(user.id, id, expectedUpdatedAt, context);
        if (!reservation?.allowed) fail(429, 'AVATAR_QUOTA', '头像修改次数较多，请稍后再试。');
        const data = await readBody(req, MAX_AVATAR), details = validateAvatar(data, req.headers['content-type']);
        const storagePath = `avatars/${id}.jpg`;
        await storage.put(storagePath, data, 'image/jpeg');
        const updated = await store.setAvatar(user.id, id, expectedUpdatedAt,
          { ...details, url: `${origin}${prefix}/avatars/${id}.jpg`, storagePath });
        json(res, { user: view(updated) }); return true;
      } finally { release(); }
    }
    if (path === '/profile/avatar/reset' && req.method === 'POST') {
      const body = await readJson(req, 1024);
      // JSON never accepts a URL, account ID, asset ID, or a provider/source selection.
      if (Object.keys(body).some(key => key !== 'expectedUpdatedAt')) fail(400, 'INVALID_PROFILE', '头像操作参数不正确。');
      const expectedUpdatedAt = revision(body.expectedUpdatedAt);
      await abuseService.protect(req, req.headers['x-installation-id'], 'community-write');
      json(res, { user: view(await store.resetAvatar(user.id, expectedUpdatedAt)) }); return true;
    }
    fail(404, 'NOT_FOUND', '账号操作不存在。');
  } };
}
