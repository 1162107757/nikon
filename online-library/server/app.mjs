import { readFile } from 'node:fs/promises';
import { resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomToken, sha256, readCookie, equalToken, verifyPassword, hashPassword, hashUserPassword, LoginGate } from './auth.mjs';
import { ServiceError } from './cloudbase.mjs';
import { validatePresetFile, presetFilename, validatePreview, validatePresetInput, buildCatalog } from './validation.mjs';
import { createCommunityRoutes } from './community.mjs';
import { createPersonalRoutes } from './personal.mjs';
import { createChallengeService } from './challenge.mjs';
import { createReleaseRoutes } from './releases.mjs';
import { createProfileRoutes } from './profile.mjs';

const ADMIN_DIR = fileURLToPath(new URL('../admin/', import.meta.url));
const MAX_UPLOAD = 4 * 1024 * 1024;
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const staticTypes = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.svg': 'image/svg+xml', '.json': 'application/json; charset=utf-8' };

export async function readBody(req, maximum) {
  const declared = req.headers['content-length'];
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > maximum)) {
    throw new ServiceError(413, 'BODY_TOO_LARGE', '文件或请求过大，请缩小后重试。');
  }
  const chunks = []; let length = 0;
  for await (const chunk of req) {
    length += chunk.length;
    if (length > maximum) throw new ServiceError(413, 'BODY_TOO_LARGE', '文件或请求过大，请缩小后重试。');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks, length);
}
async function readJson(req, maximum = 20 * 1024) {
  if (!String(req.headers['content-type'] || '').toLowerCase().startsWith('application/json')) {
    throw new ServiceError(415, 'JSON_REQUIRED', '请求格式不正确，请刷新页面。');
  }
  try {
    const body = JSON.parse((await readBody(req, maximum)).toString('utf8'));
    if (!body || Array.isArray(body) || typeof body !== 'object') throw new Error();
    return body;
  } catch (error) {
    if (error.status) throw error;
    throw new ServiceError(400, 'INVALID_JSON', '请求内容不完整，请重试。');
  }
}
function json(res, value, status = 200) {
  const data = JSON.stringify(value);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(data) });
  res.end(data);
}
function revisionOf(body) {
  if (!Number.isSafeInteger(body.expectedRevision) || body.expectedRevision < 1) {
    throw new ServiceError(400, 'REVISION_REQUIRED', '请刷新后再操作这份预设。');
  }
  return body.expectedRevision;
}
export function presetView(row) {
  return { id: row.id, revision: row.revision, ...row.draft, category: row.draft.category ?? '', fileFormat: row.draft.file?.fileFormat ?? 'NCP', status: row.status,
    hasUnpublishedChanges: row.status === 'published' && row.published_revision !== row.revision,
    publishedRevision: row.published_revision, publishedAt: row.published_at,
    createdAt: row.created_at, updatedAt: row.updated_at, versionCount: row.revision };
}

const USER_FIELDS = ['id', 'username', 'nickname', 'avatarUrl', 'avatarSource', 'provider', 'status', 'createdAt', 'lastLoginAt', 'loginCount', 'updatedAt'];
function userView(row, detail = false) {
  const value = Object.fromEntries(USER_FIELDS.map(key => [key, row[key]]));
  if (detail) value.qqOpenIdMasked = row.qqOpenIdMasked || '';
  return value;
}
function userQuery(params) {
  const q = (params.get('q') || '').trim(), status = params.get('status') || 'all';
  const pageText = params.get('page') || '1', sizeText = params.get('pageSize') || '20';
  if (q.length > 80 || /[\x00-\x1f\x7f]/.test(q) || !['all', 'active', 'disabled'].includes(status)
    || !/^[1-9][0-9]{0,5}$/.test(pageText) || !/^[1-9][0-9]{0,2}$/.test(sizeText)
    || Number(pageText) > 100000 || Number(sizeText) > 100) {
    throw new ServiceError(400, 'INVALID_USER_QUERY', '用户查询条件不正确，请缩短关键词或重置筛选。');
  }
  return { q, status, page: Number(pageText), pageSize: Number(sizeText) };
}
function bearerToken(req) {
  const authorization = req.headers.authorization;
  const headerCount = req.rawHeaders?.filter((value, index) => index % 2 === 0 && value.toLowerCase() === 'authorization').length;
  if (headerCount > 1 || typeof authorization !== 'string') return '';
  return /^Bearer ([A-Za-z0-9_-]{43})$/.exec(authorization)?.[1] || '';
}

function browserOrigins(publicOrigin, additionalOrigins) {
  if (!Array.isArray(additionalOrigins)) throw new TypeError('ALLOWED_BROWSER_ORIGINS must be a list of exact HTTPS origins.');
  const origins = new Set([publicOrigin]);
  for (const value of additionalOrigins) {
    let url;
    try { url = new URL(value); } catch { /* Invalid configuration must stop startup. */ }
    if (typeof value !== 'string' || !url || url.protocol !== 'https:' || url.origin !== value || url.hostname.includes('*')) {
      throw new TypeError('ALLOWED_BROWSER_ORIGINS entries must be exact HTTPS origins without credentials, paths or wildcards.');
    }
    origins.add(value);
  }
  return origins;
}

export function createHandler({ store, storage, environmentId, publicBaseUrl, allowedBrowserOrigins = [], basePath = '/preset-library', secureCookies = true, adminDir = ADMIN_DIR, qqVerifier = null, challengeService = null, challengeSecret, trustedProxyHops = 0, trustedProxyAddresses }) {
  const prefix = basePath.replace(/\/$/, '');
  const publicBase = new URL(publicBaseUrl);
  // Explicit browser entrypoints do not change canonical asset URLs or trust request Host headers.
  const allowedOrigins = browserOrigins(publicBase.origin, allowedBrowserOrigins);
  const cookieName = secureCookies ? '__Host-nikon_session' : 'nikon_session';
  const cookie = (value, expired = false) => `${cookieName}=${value}; Path=/; HttpOnly; SameSite=Strict${secureCookies ? '; Secure' : ''}; Max-Age=${expired ? 0 : 28800}`;
  const catalogUrl = `${publicBase.origin}${prefix}/catalog.json`;
  const loginGate = new LoginGate();
  const qqLoginGate = new LoginGate({ maximum: 40, concurrent: 4 });
  // Shared by account creation and login so registration cannot bypass the KDF concurrency cap.
  const passwordGate = new LoginGate({ maximum: 40, concurrent: 2 });
  const challenge = challengeService || createChallengeService({ store, secret: challengeSecret, trustedProxyHops, trustedProxyAddresses });
  const communityRoutes = createCommunityRoutes({ store, storage, publicBaseUrl, basePath, abuseService: challenge });
  const personalRoutes = createPersonalRoutes({store,storage,publicBaseUrl,abuseService:challenge});
  const releaseRoutes = createReleaseRoutes({ store, storage, publicBaseUrl, basePath, trustedProxyHops, trustedProxyAddresses });
  const profileRoutes = createProfileRoutes({ store, storage, publicBaseUrl, basePath, abuseService: challenge });
  const qqStatus = () => qqVerifier?.status() || { configured: false, provider: 'qq', message: 'QQ 登录尚未配置，请先申请 QQ 互联移动应用并配置 AppID。' };
  const sessionView = session => ({ authenticated: Boolean(session), user: session ? { username: session.username } : null,
    csrfToken: session?.csrf_token || '', catalogUrl, environmentId });
  function requireAssetGet(req, res) {
    // The deployed HTTP gateway rewrites HEAD entity length to zero. Do not advertise incorrect metadata.
    if (req.method === 'HEAD') {
      res.setHeader('Allow', 'GET');
      throw new ServiceError(405, 'METHOD_NOT_ALLOWED', '请使用 GET 下载文件。');
    }
  }
  async function sendAsset(res, asset, isPublic) {
    if (!asset || !/^(ncp|preview)\/[a-f0-9]{64}\.(ncp|np3|jpg|png|gif|webp)$/.test(asset.storagePath || '')) {
      throw new ServiceError(404, 'FILE_NOT_FOUND', '文件不存在或尚未上架。');
    }
    const data = await storage.get(asset.storagePath, asset.kind === 'ncp' ? 1024 * 1024 : MAX_UPLOAD);
    if (data.length !== asset.byteLength || sha256(data) !== asset.sha256) throw new ServiceError(503, 'FILE_CHANGED', '文件校验未通过，请联系管理员。');
    if (isPublic) res.setHeader('Cache-Control', 'public, max-age=31536000, immutable');
    res.setHeader('Content-Type', asset.kind === 'ncp' ? 'application/octet-stream' : asset.mimeType);
    res.setHeader('Content-Length', data.length);
    res.setHeader('ETag', `"${asset.sha256}"`);
    res.setHeader('Content-Disposition', `${asset.kind === 'ncp' ? 'attachment' : 'inline'}; filename*=UTF-8''${encodeURIComponent(asset.originalName)}`);
    res.writeHead(200); res.end(data);
  }

  return async (req, res) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Frame-Options', 'DENY');
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' https: blob: data:; connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'");
    if (secureCookies) res.setHeader('Strict-Transport-Security', 'max-age=31536000');
    let path;
    try {
      const url = new URL(req.url, publicBase.origin);
      path = url.pathname;
      // CloudBase gateways may preserve the route prefix or strip it.
      if (prefix && (path === prefix || path.startsWith(prefix + '/'))) path = path.slice(prefix.length) || '/';
      const routeContext = { path, url, session: null, json, readBody, readJson };
      if (await profileRoutes.publicRoute(req, res, routeContext)) return;
      if (await releaseRoutes.publicRoute(req, res, routeContext)) return;
      if (await communityRoutes.publicRoute(req, res, routeContext)) return;
      if (await personalRoutes.publicRoute(req, res, routeContext)) return;
      if (path === '/catalog.json' && req.method === 'GET') {
        const manifest = buildCatalog(await store.catalogRows());
        res.setHeader('Access-Control-Allow-Origin', '*');
        return json(res, manifest);
      }
      if (path === '/health' && req.method === 'GET') return json(res, { ok: true, configured: store.configured, environmentId });
      if (path.startsWith('/auth/')) {
        // Android SDK requests have no Origin. Browser callers need an explicitly configured origin.
        if (req.headers.origin && !allowedOrigins.has(req.headers.origin)) {
          throw new ServiceError(403, 'ORIGIN_REJECTED', '请从 App 发起登录操作。');
        }
        if (path === '/auth/challenge' && req.method === 'GET') return json(res, await challenge.issue(req, url.searchParams.get('installationId')));
        if (['/auth/register', '/auth/login'].includes(path) && req.method === 'POST') {
          const release = passwordGate.acquire();
          if (!release) throw new ServiceError(429, 'USER_AUTH_BUSY', '登录或注册请求较多，请稍后再试。');
          try {
            const body = await readJson(req, 4 * 1024), registration = path === '/auth/register';
            await challenge.protect(req, body.installationId, registration ? 'register' : 'password-login');
            if (registration) await challenge.consume(req, body, 'register');
            // Passwords are deliberately never trimmed or normalized.
            const username = typeof body.username === 'string' ? body.username.toLowerCase() : '';
            const validUsername = typeof body.username === 'string' && /^[A-Za-z][A-Za-z0-9_]{2,31}$/.test(body.username);
            const validPassword = typeof body.password === 'string' && body.password.length >= 8 && body.password.length <= 128;
            const attemptKey = sha256('app-password:' + username);
            if (!await store.passwordAuthAllowed(attemptKey, registration)) {
              throw new ServiceError(429, 'USER_AUTH_THROTTLED', '尝试次数较多，请 15 分钟后再试。');
            }
            if (registration && !validUsername) {
              throw new ServiceError(400, 'INVALID_USERNAME', '用户名须为 3 至 32 位，以英文字母开头，仅含字母、数字或下划线。');
            }
            if (registration && !validPassword) throw new ServiceError(400, 'INVALID_PASSWORD', '密码须为 8 至 128 个字符。');
            const accessToken = randomToken(), expiresAt = new Date(Date.now() + 7 * 86400000).toISOString();
            let user;
            if (registration) {
              const passwordHash = await hashUserPassword(body.password);
              user = await store.registerPassword(username, passwordHash, sha256(accessToken), expiresAt);
            } else {
              const credential = validUsername && validPassword ? await store.passwordCredential(username) : null;
              // Missing users, malformed credentials and wrong passwords all perform the same scrypt work.
              const matches = await verifyPassword(validPassword ? body.password : '', credential?.password_hash);
              if (!matches || !credential) throw new ServiceError(401, 'USER_LOGIN_FAILED', '用户名或密码不正确。');
              // The RPC locks the account and confirms this exact hash is still current before issuing a session.
              user = await store.signInPassword(username, credential.password_hash, sha256(accessToken), expiresAt);
              await store.clearPasswordAttempts(attemptKey);
            }
            return json(res, { tokenType: 'Bearer', accessToken, expiresAt, user: userView(user) }, registration ? 201 : 200);
          } finally { release(); }
        }
        if (path === '/auth/qq' && req.method === 'POST') {
          if (!qqVerifier?.configured) throw new ServiceError(503, 'qq_not_configured', qqStatus().message);
          const release = qqLoginGate.acquire();
          if (!release) throw new ServiceError(429, 'qq_login_busy', '登录请求较多，请稍后再试。');
          try {
            const body = await readJson(req, 4 * 1024);
            await challenge.protect(req, body.installationId, 'qq-login');
            if (typeof body.accessToken !== 'string' || body.accessToken.length < 16 || body.accessToken.length > 2048
              || /[\x00-\x20\x7f]/.test(body.accessToken)) {
              throw new ServiceError(400, 'qq_invalid_token', 'QQ 登录信息不完整，请重新授权。');
            }
            // Persist only a one-way attempt key, never the incoming QQ access token.
            if (!await store.loginAllowed(sha256('qq-login:' + body.accessToken))) {
              throw new ServiceError(429, 'qq_login_throttled', '重复登录次数较多，请稍后重新授权。');
            }
            const identity = await qqVerifier.verify({ accessToken: body.accessToken });
            if (!await store.qqIdentityExists(identity)) {
              await challenge.protect(req, body.installationId, 'register');
              await challenge.consume(req, body, 'qq_register');
            }
            const accessToken = randomToken(), expiresAt = new Date(Date.now() + 7 * 86400000).toISOString();
            const user = await store.signInQq(identity, sha256(accessToken), expiresAt);
            return json(res, { tokenType: 'Bearer', accessToken, expiresAt, user: userView(user) });
          } finally { release(); }
        }
        if ((path === '/auth/me' && req.method === 'GET') || (path === '/auth/logout' && req.method === 'POST')) {
          const token = bearerToken(req);
          const session = token ? await store.userSession(sha256(token)) : null;
          if (!session) throw new ServiceError(401, 'USER_LOGIN_REQUIRED', '登录已失效，请重新登录。');
          if (path === '/auth/logout') {
            await store.deleteUserSession(sha256(token));
            return json(res, { ok: true });
          }
          return json(res, { user: userView(session.user), expiresAt: session.expiresAt });
        }
        throw new ServiceError(404, 'NOT_FOUND', '此登录操作不存在。');
      }
      const publicFile = path.match(/^\/files\/((ncp|preview)-[a-f0-9]{64})\.(ncp|np3|jpg|png|gif|webp)$/);
      if (publicFile && ['GET', 'HEAD'].includes(req.method)) {
        requireAssetGet(req, res);
        const asset = await store.publishedAsset(publicFile[1]);
        if (asset && `${publicFile[1]}.${asset.kind === 'ncp' ? (asset.fileFormat === 'NP3' ? 'np3' : 'ncp') : asset.extension}` !== path.slice('/files/'.length)) {
          throw new ServiceError(404, 'FILE_NOT_FOUND', '文件不存在。');
        }
        return await sendAsset(res, asset, true);
      }
      if (path.startsWith('/api/')) {
        const mutating = !['GET', 'HEAD', 'OPTIONS'].includes(req.method);
        if (mutating && !allowedOrigins.has(req.headers.origin)) {
          throw new ServiceError(403, 'ORIGIN_REJECTED', '请从后台页面发起操作。');
        }
        const token = readCookie(req.headers.cookie, cookieName);
        const session = token ? await store.session(sha256(token)) : null;
        if (path === '/api/session' && req.method === 'GET') return json(res, sessionView(session));
        if (path === '/api/login' && req.method === 'POST') {
          const release = loginGate.acquire();
          if (!release) throw new ServiceError(429, 'LOGIN_BUSY', '登录尝试较多，请稍后再试。');
          try {
          const body = await readJson(req);
          const username = typeof body.username === 'string' ? body.username.trim().toLowerCase() : '';
          if (!/^[a-z0-9_.-]{1,64}$/.test(username) || typeof body.password !== 'string' || body.password.length > 256) {
            throw new ServiceError(401, 'LOGIN_FAILED', '账号或密码不正确。');
          }
          if (!await store.loginAllowed(sha256(username))) throw new ServiceError(429, 'LOGIN_THROTTLED', '尝试次数较多，请 15 分钟后再试。');
          const admin = await store.findAdmin(username);
          const passwordMatches = await verifyPassword(body.password, admin?.password_hash);
          if (!passwordMatches || admin.disabled) throw new ServiceError(401, 'LOGIN_FAILED', '账号或密码不正确。');
          if (token) await store.deleteSession(sha256(token));
          const freshToken = randomToken();
          const nextSession = { token_hash: sha256(freshToken), username, csrf_token: randomToken(), expires_at: new Date(Date.now() + 28800000).toISOString() };
          await store.signInAdmin(nextSession, admin.password_hash);
          await store.clearAttempts(sha256(username));
          res.setHeader('Set-Cookie', cookie(freshToken));
          return json(res, sessionView(nextSession));
          } finally { release(); }
        }
        if (!session) throw new ServiceError(401, 'LOGIN_REQUIRED', '登录已失效，请重新登录。');
        if (mutating && !equalToken(session.csrf_token, req.headers['x-csrf-token'])) {
          throw new ServiceError(403, 'CSRF_REJECTED', '页面已失效，请刷新后重试。');
        }
        if (path === '/api/password' && req.method === 'POST') {
          // Login and password changes share the same bounded scrypt concurrency budget.
          const release = loginGate.acquire();
          if (!release) throw new ServiceError(429, 'LOGIN_BUSY', '密码操作较多，请稍后再试。');
          try {
            const body = await readJson(req, 4 * 1024);
            if (typeof body.currentPassword !== 'string' || !body.currentPassword.length || body.currentPassword.length > 256) {
              throw new ServiceError(400, 'CURRENT_PASSWORD_REQUIRED', '请输入当前密码，最多 256 个字符。');
            }
            if (typeof body.newPassword !== 'string' || body.newPassword.length < 8 || body.newPassword.length > 256) {
              throw new ServiceError(400, 'INVALID_PASSWORD', '新密码须为 8 至 256 个字符。');
            }
            if (body.newPassword !== body.confirmPassword) throw new ServiceError(400, 'PASSWORD_MISMATCH', '两次输入的新密码不一致，请重新输入。');
            if (body.newPassword === body.currentPassword) throw new ServiceError(400, 'PASSWORD_UNCHANGED', '新密码不能与当前密码相同。');
            if (!await store.loginAllowed(sha256('admin-password:' + session.username))) {
              throw new ServiceError(429, 'PASSWORD_THROTTLED', '尝试次数较多，请 15 分钟后再试。');
            }
            const admin = await store.findAdmin(session.username);
            const matches = await verifyPassword(body.currentPassword, admin?.password_hash);
            if (!admin || admin.disabled) throw new ServiceError(401, 'LOGIN_REQUIRED', '登录已失效，请重新登录。');
            if (!matches) throw new ServiceError(400, 'CURRENT_PASSWORD_INCORRECT', '当前密码不正确，请重新输入。');
            const passwordHash = await hashPassword(body.newPassword);
            // Recheck the session and verified hash, update the password and revoke all sessions in one transaction.
            await store.changeAdminPassword(session.username, sha256(token), admin.password_hash, passwordHash);
            res.setHeader('Set-Cookie', cookie('', true));
            return json(res, { ok: true });
          } finally { release(); }
        }
        const adminContext = { ...routeContext, session };
        if (await releaseRoutes.adminRoute(req, res, adminContext)) return;
        if (await communityRoutes.adminRoute(req, res, adminContext)) return;
        const adminFile = path.match(/^\/api\/assets\/((ncp|preview)-[a-f0-9]{64})\/content$/);
        if (adminFile && ['GET', 'HEAD'].includes(req.method)) {
          requireAssetGet(req, res);
          return await sendAsset(res, await store.asset(adminFile[1]), false);
        }
        if (path === '/api/logout' && req.method === 'POST') {
          await store.deleteSession(sha256(token));
          res.setHeader('Set-Cookie', cookie('', true));
          return json(res, { ok: true });
        }
        if (path === '/api/users' && req.method === 'GET') {
          const result = await store.users(userQuery(url.searchParams));
          return json(res, { ...result, items: result.items.map(row => userView(row)), qqLogin: qqStatus() });
        }
        const userMatch = path.match(/^\/api\/users\/([^/]+)(?:\/(status))?$/);
        if (userMatch) {
          const id = userMatch[1];
          if (!UUID.test(id)) throw new ServiceError(404, 'USER_NOT_FOUND', '这位用户不存在，请刷新列表。');
          if (!userMatch[2] && req.method === 'GET') return json(res, { user: userView(await store.user(id), true) });
          if (userMatch[2] === 'status' && req.method === 'POST') {
            const body = await readJson(req);
            if (!['active', 'disabled'].includes(body.status)) throw new ServiceError(400, 'INVALID_USER_STATUS', '请选择正常或已停用状态。');
            if (typeof body.expectedUpdatedAt !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$/.test(body.expectedUpdatedAt)
              || !Number.isFinite(Date.parse(body.expectedUpdatedAt))) {
              throw new ServiceError(400, 'USER_VERSION_REQUIRED', '请刷新用户信息后再操作。');
            }
            const user = await store.setUserStatus(id, body.status, body.expectedUpdatedAt);
            return json(res, { user: userView(user, true) });
          }
        }
        if (path === '/api/presets' && req.method === 'GET') {
          const rows = await store.presets();
          return json(res, { items: rows.map(presetView), total: rows.length });
        }
        if (path === '/api/uploads' && req.method === 'POST') {
          const kind = url.searchParams.get('kind');
          if (!['ncp', 'preview'].includes(kind)) throw new ServiceError(400, 'INVALID_UPLOAD', '请选择预设文件或效果图。');
          const data = await readBody(req, kind === 'ncp' ? 1024 * 1024 : MAX_UPLOAD);
          const details = kind === 'ncp' ? validatePresetFile(data) : validatePreview(data, req.headers['content-type']);
          const id = `${kind}-${details.sha256}`;
          const found = await store.asset(id);
          if (found) return json(res, { asset: found });
          const originalName = kind === 'ncp' ? presetFilename(url.searchParams.get('filename'), details.fileFormat)
            : (url.searchParams.get('filename') || 'preview').replace(/[\\/\x00-\x1f\x7f]/g, '_').slice(0, 255);
          const objectPath = `${kind}/${details.sha256}.${details.extension}`;
          await storage.put(objectPath, data, kind === 'ncp' ? 'application/octet-stream' : details.mimeType);
          const assetUrl = `${publicBase.origin}${prefix}/files/${id}.${details.extension}`;
          const adminUrl = `${publicBase.origin}${prefix}/api/assets/${id}/content`;
          const asset = await store.addAsset({ id, kind, url: assetUrl, adminUrl, storagePath: objectPath, originalName, ...details });
          return json(res, { asset }, 201);
        }
        let presetId = null;
        const match = path.match(/^\/api\/presets\/([^/]+)(?:\/(publish|unpublish|versions))?$/);
        if (match) {
          if (!UUID.test(match[1])) throw new ServiceError(404, 'NOT_FOUND', '这份预设不存在。');
          presetId = match[1];
        }
        if ((path === '/api/presets' && req.method === 'POST') || (match && !match[2] && req.method === 'PUT')) {
          const body = await readJson(req);
          const metadata = validatePresetInput(body);
          const current = presetId ? await store.preset(presetId) : null;
          const expectedRevision = current ? revisionOf(body) : null;
          const file = await store.asset(body.fileAssetId || current?.draft.file?.id);
          if (!file || file.kind !== 'ncp') throw new ServiceError(400, 'NCP_REQUIRED', '请先上传有效的 NCP 或 NP3 文件。');
          let preview = current?.draft.preview || null;
          if (Object.hasOwn(body, 'previewAssetId')) {
            preview = body.previewAssetId === null ? null : await store.asset(body.previewAssetId);
            if (body.previewAssetId !== null && (!preview || preview.kind !== 'preview')) {
              throw new ServiceError(400, 'PREVIEW_REQUIRED', '效果图未上传完成，请重新上传。');
            }
          }
          const row = await store.savePreset(presetId, expectedRevision, { ...metadata, file, preview });
          return json(res, { preset: presetView(row) }, current ? 200 : 201);
        }
        if (match?.[2] === 'versions' && req.method === 'GET') {
          const current = await store.preset(presetId);
          const versions = await store.versions(presetId);
          return json(res, { items: versions.map(row => ({ revision: row.revision, ...row.snapshot, category: row.snapshot.category ?? '', fileFormat: row.snapshot.file?.fileFormat ?? 'NCP',
            createdAt: row.created_at, isPublished: current.status === 'published' && row.revision === current.published_revision })) });
        }
        if (match && ['publish', 'unpublish'].includes(match[2]) && req.method === 'POST') {
          const body = await readJson(req); const expectedRevision = revisionOf(body);
          const current = await store.preset(presetId);
          if (current.revision !== expectedRevision) throw new ServiceError(409, 'REVISION_CONFLICT', '预设已被更新，请刷新后再操作。');
          const publish = match[2] === 'publish';
          if (publish) {
            const rows = (await store.catalogRows()).filter(row => row.id !== presetId);
            rows.push({ id: presetId, status: 'published', published: current.draft });
            buildCatalog(rows);
          }
          const row = await store.publish(presetId, expectedRevision, publish);
          return json(res, { preset: presetView(row) });
        }
        throw new ServiceError(404, 'NOT_FOUND', '操作不存在，请刷新页面。');
      }
      if (req.method !== 'GET' && req.method !== 'HEAD') throw new ServiceError(405, 'METHOD_NOT_ALLOWED', '此操作不受支持。');
      if (path === '/' || path === '/admin') {
        res.writeHead(302, { Location: `${prefix}/admin/` }); return res.end();
      }
      if (!path.startsWith('/admin/')) throw new ServiceError(404, 'NOT_FOUND', '页面不存在。');
      const relative = path.slice('/admin/'.length) || 'index.html';
      if (!/^[a-zA-Z0-9_./-]+$/.test(relative) || relative.split('/').includes('..')) throw new ServiceError(404, 'NOT_FOUND', '页面不存在。');
      const absolute = resolve(adminDir, relative);
      if (!absolute.startsWith(resolve(adminDir) + '/'.replace('/', process.platform === 'win32' ? '\\' : '/'))) throw new ServiceError(404, 'NOT_FOUND', '页面不存在。');
      let file;
      try { file = await readFile(absolute); } catch { throw new ServiceError(404, 'NOT_FOUND', '页面不存在。'); }
      res.writeHead(200, { 'Content-Type': staticTypes[extname(absolute)] || 'application/octet-stream', 'Content-Length': file.length });
      return res.end(req.method === 'HEAD' ? undefined : file);
    } catch (error) {
      if (res.headersSent) { res.destroy(); return; }
      const status = Number.isInteger(error.status) && error.status >= 400 && error.status <= 599 ? error.status : 500;
      if (status === 429) res.setHeader('Retry-After', '900');
      json(res, { error: { code: typeof error.code === 'string' ? error.code : 'INTERNAL_ERROR',
        message: status === 500 ? '操作未完成，请稍后重试。' : error.message } }, status);
    }
  };
}
