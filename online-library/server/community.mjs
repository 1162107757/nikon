import { ServiceError } from './cloudbase.mjs';
import { sha256 } from './auth.mjs';
import { classifyComment } from './comment-moderation.mjs';
import { validatePresetFile, presetFilename, validatePreview, validatePresetInput } from './validation.mjs';

const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const ASSET = /^(ncp|preview)-[a-f0-9]{64}$/;
function fail(status, code, message) { throw new ServiceError(status, code, message); }
function id(value) { if (typeof value !== 'string' || !UUID.test(value)) fail(404, 'NOT_FOUND', '内容不存在。'); return value; }
function plain(value, maximum, required = true, allowLinks = false) {
  if (typeof value !== 'string' || /[\x00-\x08\x0b-\x1f\x7f-\x9f]/u.test(value)
    || /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(value)) fail(400, 'INVALID_TEXT', '请填写有效的纯文字。');
  const text = value.normalize('NFC').trim();
  if ((required && !text) || [...text].length > maximum) fail(400, 'INVALID_TEXT', `文字须为 1 至 ${maximum} 个字符。`);
  // No markup, URLs, contact links or executable schemes in first-version comments.
  if (/<[^>]*>/i.test(text) || !allowLinks && /(?:https?:\/\/|www\.|javascript:|data:)/i.test(text)) fail(400, 'INVALID_TEXT', '评论暂不支持 HTML 或无效链接。');
  return text;
}
function key(body, req) {
  const value = body.idempotencyKey ?? req.headers['idempotency-key'];
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{8,96}$/.test(value)) fail(400, 'IDEMPOTENCY_REQUIRED', '请提供有效的请求标识后重试。');
  return value;
}
function expected(body) {
  if (!Number.isSafeInteger(body.expectedRevision) || body.expectedRevision < 1) fail(400, 'REVISION_REQUIRED', '请刷新内容后重试。');
  return body.expectedRevision;
}
function pagination(url) {
  const p = url.searchParams.get('page') || '1', n = url.searchParams.get('pageSize') || '20';
  if (!/^[1-9]\d{0,4}$/.test(p) || !/^[1-9]\d?$/.test(n) || Number(n) > 50) fail(400, 'INVALID_QUERY', '分页参数不正确。');
  return { page: Number(p), pageSize: Number(n) };
}
function bearer(req) {
  if (req.rawHeaders?.filter((v, i) => i % 2 === 0 && v.toLowerCase() === 'authorization').length > 1) return '';
  return /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization || '')?.[1] || '';
}

export function createCommunityRoutes({ store, storage, publicBaseUrl, basePath = '/preset-library', abuseService }) {
  const origin = new URL(publicBaseUrl).origin, prefix = basePath.replace(/\/$/, '');
  const rpc = (action, userId = null, contentId = null, body = {}) => store.community(action, userId, contentId, body);
  async function user(req, scope = 'community-write') {
    if (req.headers.origin && req.headers.origin !== origin) fail(403, 'ORIGIN_REJECTED', '请从 App 发起操作。');
    const token = bearer(req), session = token && await store.userSession(sha256(token));
    if (!session?.user || session.user.status !== 'active') fail(401, 'USER_LOGIN_REQUIRED', '登录已失效，请重新登录。');
    if (!['GET', 'HEAD'].includes(req.method) && abuseService) await abuseService.protect(req, req.headers['x-installation-id'], scope);
    return session.user;
  }
  async function sendAsset(req, res, asset) {
    if (req.method !== 'GET') fail(405, 'METHOD_NOT_ALLOWED', '请使用 GET 下载文件。');
    if (!asset || !/^(ncp|preview)\/[a-f0-9]{64}\.(ncp|np3|jpg|png|gif|webp)$/.test(asset.storagePath || '')) fail(404, 'FILE_NOT_FOUND', '文件不存在或尚未通过审核。');
    const data = await storage.get(asset.storagePath, asset.kind === 'ncp' ? 1048576 : 4194304);
    if (data.length !== asset.byteLength || sha256(data) !== asset.sha256) fail(503, 'FILE_CHANGED', '文件校验失败。');
    // Always recheck publication. Revocation must also revoke an old community URL.
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Content-Type', asset.kind === 'ncp' ? 'application/octet-stream' : asset.mimeType);
    res.setHeader('Content-Disposition', `${asset.kind === 'ncp' ? 'attachment' : 'inline'}; filename*=UTF-8''${encodeURIComponent(asset.originalName)}`);
    res.setHeader('Content-Length', data.length); res.writeHead(200); res.end(data);
  }
  async function publicRoute(req, res, { path, url, json, readJson, readBody }) {
    if (!path.startsWith('/community/')) return false;
    let match;
    if (path === '/community/feed' && req.method === 'GET') {
      const category = url.searchParams.get('category') || '';
      if ([...category].length > 24) fail(400, 'INVALID_QUERY', '分类过长。');
      json(res, await rpc('feed', null, null, { ...pagination(url), category })); return true;
    }
    if ((match = path.match(/^\/community\/comments\/([^/]+)\/replies$/)) && req.method === 'GET') {
      json(res, await rpc('replies', null, id(match[1]), pagination(url))); return true;
    }
    if ((match = path.match(/^\/community\/files\/((?:ncp|preview)-[a-f0-9]{64})\.(ncp|np3|jpg|png|gif|webp)$/))) {
      const asset = await rpc('publicAsset', null, null, { assetId: match[1] });
      if (asset && match[2] !== (asset.kind === 'ncp' ? (asset.fileFormat === 'NP3' ? 'np3' : 'ncp') : asset.extension)) fail(404, 'FILE_NOT_FOUND', '文件不存在。');
      await sendAsset(req, res, asset); return true;
    }
    if ((match = path.match(/^\/community\/presets\/([^/]+)(?:\/(comments))?$/))) {
      const presetId = id(match[1]);
      if (!match[2] && req.method === 'GET') { json(res, { preset: await rpc('detail', null, presetId) }); return true; }
      if (match[2] && req.method === 'GET') {
        const focus = url.searchParams.get('focusCommentId');
        json(res, await rpc('comments', null, presetId, { ...pagination(url), ...(focus ? { focusCommentId: id(focus) } : {}) })); return true;
      }
      if (match[2] && req.method === 'POST') {
        const account = await user(req, 'community-comment'), body = await readJson(req, 8 * 1024);
        const parentId = body.parentId ? id(body.parentId) : null;
        const replyToId = body.replyToId ? id(body.replyToId) : null;
        if (replyToId && !parentId) fail(400, 'INVALID_REPLY_TARGET', '请重新选择要回复的评论。');
        const commentText = plain(body.text, 500, true, true), moderation = classifyComment(commentText);
        json(res, { comment: await rpc('comment', account.id, presetId, { text: commentText, moderation, parentId,
          ...(replyToId ? { replyToId } : {}), idempotencyKey: key(body, req) }) }, 201); return true;
      }
    }
    const account = await user(req, path === '/community/uploads' ? 'community-upload' : 'community-write');
    if (path === '/community/uploads' && req.method === 'POST') {
      const kind = url.searchParams.get('kind');
      if (!['ncp', 'preview'].includes(kind)) fail(400, 'INVALID_UPLOAD', '请选择 NCP、NP3 或图片。');
      // Reservation commits before reading/uploading bytes; failed transfers still consume the abuse budget.
      await rpc('reserveUpload', account.id, null, {});
      const data = await readBody(req, kind === 'ncp' ? 1048576 : 4194304);
      const details = kind === 'ncp' ? validatePresetFile(data) : validatePreview(data, req.headers['content-type']);
      const assetId = `${kind}-${details.sha256}`, objectPath = `${kind}/${details.sha256}.${details.extension}`;
      const originalName = kind === 'ncp' ? presetFilename(url.searchParams.get('filename'), details.fileFormat)
        : (url.searchParams.get('filename') || 'preview').replace(/[\\/\x00-\x1f\x7f]/g, '_').slice(0, 255);
      await storage.put(objectPath, data, kind === 'ncp' ? 'application/octet-stream' : details.mimeType);
      const asset = { id: assetId, kind, ...details, originalName, storagePath: objectPath,
        url: `${origin}${prefix}/community/files/${assetId}.${details.extension}` };
      json(res, { asset: await rpc('addAsset', account.id, null, { asset }) }, 201); return true;
    }
    if (path === '/community/submissions' && req.method === 'GET') { json(res, await rpc('mine', account.id, null, pagination(url))); return true; }
    match = path.match(/^\/community\/submissions\/([^/]+)(?:\/(submit|versions|assets\/(?:ncp|preview)))?$/);
    if (match && !match[2] && req.method === 'GET') { json(res, { submission: await rpc('ownDetail', account.id, id(match[1])) }); return true; }
    if (match?.[2] === 'versions' && req.method === 'GET') { json(res, await rpc('versions', account.id, id(match[1]), pagination(url))); return true; }
    if ((path === '/community/submissions' && req.method === 'POST') || (match && !match[2] && req.method === 'PUT')) {
      const body = await readJson(req), contentId = match ? id(match[1]) : null;
      const metadata = validatePresetInput(body);
      if (metadata.category === '__uncategorized__') fail(400, 'INVALID_PRESET', '请选择有效的预设分类。');
      if (body.rightsConfirmed !== true) fail(400, 'RIGHTS_CONFIRMATION_REQUIRED', '请确认你有权公开分发该预设与图片。');
      if (typeof body.fileAssetId !== 'string' || !ASSET.test(body.fileAssetId) || !body.fileAssetId.startsWith('ncp-')) fail(400, 'NCP_REQUIRED', '请先上传有效的 NCP 或 NP3 文件。');
      if (body.previewAssetId != null && (!ASSET.test(body.previewAssetId) || !body.previewAssetId.startsWith('preview-'))) fail(400, 'INVALID_PREVIEW', '请重新选择图片。');
      const submission = await rpc('save', account.id, contentId, { ...metadata, rightsConfirmed: true, fileAssetId: body.fileAssetId, previewAssetId: body.previewAssetId || null,
        expectedRevision: contentId ? expected(body) : null, submit: body.submit === true, idempotencyKey: key(body, req) });
      json(res, { submission }, contentId ? 200 : 201); return true;
    }
    if (match?.[2] === 'submit' && req.method === 'POST') {
      const body = await readJson(req); json(res, { submission: await rpc('submit', account.id, id(match[1]), { expectedRevision: expected(body), idempotencyKey: key(body, req) }) }); return true;
    }
    if ((match = path.match(/^\/community\/comments\/([^/]+)(?:\/(report))?$/))) {
      const commentId = id(match[1]);
      if (!match[2] && req.method === 'DELETE') { json(res, await rpc('deleteComment', account.id, commentId)); return true; }
      if (match[2] && req.method === 'POST') { const body = await readJson(req); json(res, await rpc('report', account.id, commentId, { reason: plain(body.reason, 500), idempotencyKey: key(body, req) }), 201); return true; }
    }
    if (path === '/community/messages' && req.method === 'GET') { json(res, await rpc('messages', account.id, null, pagination(url))); return true; }
    if (path === '/community/messages/unread' && req.method === 'GET') { json(res, await rpc('unread', account.id)); return true; }
    if (path === '/community/messages/read-all' && req.method === 'POST') { json(res, await rpc('readAll', account.id)); return true; }
    if (path === '/community/messages/read-batch' && req.method === 'POST') {
      const body = await readJson(req, 8192);
      if (!Array.isArray(body.ids) || !body.ids.length || body.ids.length > 50) fail(400, 'INVALID_QUERY', '请重新打开消息后重试。');
      json(res, await rpc('readBatch', account.id, null, { ids: [...new Set(body.ids.map(id))] })); return true;
    }
    if ((match = path.match(/^\/community\/messages\/([^/]+)\/read$/)) && req.method === 'POST') { json(res, await rpc('read', account.id, id(match[1]))); return true; }
    if ((match = path.match(/^\/community\/assets\/((?:ncp|preview)-[a-f0-9]{64})\/content$/)) && req.method === 'GET') {
      await sendAsset(req, res, await rpc('ownAsset', account.id, null, { assetId: match[1] })); return true;
    }
    fail(404, 'NOT_FOUND', '社区操作不存在。');
  }
  async function adminRoute(req, res, { path, url, session, json, readJson }) {
    if (!path.startsWith('/api/community/')) return false;
    let match;
    const context = { actor: session.username };
    if (path === '/api/community/security-source' && req.method === 'GET') {
      const probes = url.searchParams.getAll('probeHops');
      if (probes.length > 1 || (probes.length && !/^[1-8]$/.test(probes[0]))) fail(400, 'INVALID_PROBE_HOPS', '诊断代理跳数须为 1 至 8。');
      json(res, abuseService.inspect(req, probes.length ? Number(probes[0]) : undefined)); return true;
    }
    if (path === '/api/community/settings') {
      if (req.method === 'GET') { json(res, await rpc('adminSettings')); return true; }
      if (req.method === 'PUT') { const body = await readJson(req); json(res, await rpc('adminSetSettings', null, null, { ...context, settings: body.settings || body })); return true; }
    }
    for (const resource of ['submissions', 'comments', 'reports', 'audit']) {
      if (path === `/api/community/${resource}` && req.method === 'GET') {
        const status = url.searchParams.get('status') || 'all';
        if (!/^[a-z]{1,16}$/.test(status)) fail(400, 'INVALID_QUERY', '筛选参数不正确。');
        json(res, await rpc(`admin${resource[0].toUpperCase()}${resource.slice(1)}`, null, null, { ...pagination(url), status })); return true;
      }
    }
    if ((match = path.match(/^\/api\/community\/(submissions|comments|reports)\/([^/]+)(?:\/(review|versions))?$/))) {
      const contentId = id(match[2]), resource = match[1];
      if (req.method === 'GET') {
        json(res, await rpc(match[3] === 'versions' ? 'adminVersions' : `admin${resource === 'submissions' ? 'Submission' : resource === 'comments' ? 'Comment' : 'Report'}`, null, contentId, pagination(url))); return true;
      }
      if (match[3] === 'review' && req.method === 'POST') {
        const body = await readJson(req);
        const allowed = resource === 'submissions' ? ['approve', 'reject', 'withdraw'] : resource === 'comments' ? ['hide', 'restore'] : ['resolve', 'dismiss'];
        if (!allowed.includes(body.decision)) fail(400, 'INVALID_DECISION', '请选择有效的审核操作。');
        json(res, await rpc(`adminReview${resource === 'submissions' ? 'Submission' : resource === 'comments' ? 'Comment' : 'Report'}`, null, contentId,
          { ...context, decision: body.decision, reason: plain(body.reason || '', 500, false), expectedRevision: resource === 'submissions' ? expected(body) : null })); return true;
      }
    }
    if ((match = path.match(/^\/api\/community\/users\/([^/]+)\/restrictions$/)) && ['POST', 'GET'].includes(req.method)) {
      const userId = id(match[1]);
      if (req.method === 'GET') { json(res, await rpc('adminRestrictions', null, userId)); return true; }
      const body = await readJson(req), values = {};
      for (const field of ['mutedUntil', 'submissionBlockedUntil']) {
        if (body[field] != null && (typeof body[field] !== 'string' || !Number.isFinite(Date.parse(body[field])))) fail(400, 'INVALID_RESTRICTION', '限制时间格式不正确。');
        values[field] = body[field] || null;
      }
      json(res, await rpc('adminSetRestrictions', null, userId, { ...context, ...values, reason: plain(body.reason || '', 500, false) })); return true;
    }
    if ((match = path.match(/^\/api\/community\/assets\/((?:ncp|preview)-[a-f0-9]{64})\/content$/)) && req.method === 'GET') {
      await sendAsset(req, res, await rpc('adminAsset', null, null, { assetId: match[1] })); return true;
    }
    fail(404, 'NOT_FOUND', '社区管理操作不存在。');
  }
  return { publicRoute, adminRoute };
}
