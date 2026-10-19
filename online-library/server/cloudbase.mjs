export class ServiceError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}

const databaseErrors = {
  NOT_FOUND: [404, 'NOT_FOUND', '这份预设不存在，请刷新列表。'],
  REVISION_CONFLICT: [409, 'REVISION_CONFLICT', '预设已被更新，请刷新后再保存。'],
  CATALOG_FULL: [409, 'CATALOG_FULL', '在线库最多上架 256 份预设，请先下架部分内容。'],
  CATALOG_TOO_LARGE: [409, 'CATALOG_TOO_LARGE', '在线目录超过容量限制，请缩短简介或减少上架数量。'],
  INVALID_FILE: [400, 'INVALID_FILE', '请先上传有效的 NCP 或 NP3 文件。'],
  INVALID_PREVIEW: [400, 'INVALID_PREVIEW', '效果图未上传完成，请重新上传。'],
  INVALID_PRESET: [400, 'INVALID_PRESET', '预设信息不完整。'],
  PERSONAL_LIBRARY_FULL: [409, 'PERSONAL_LIBRARY_FULL', '个人云端预设空间已达上限，请联系管理员。'],
  USER_NOT_FOUND: [404, 'USER_NOT_FOUND', '这位用户不存在，请刷新列表。'],
  USER_CONFLICT: [409, 'USER_CONFLICT', '用户信息已更新，请刷新后再操作。'],
  USER_DISABLED: [403, 'USER_DISABLED', '账号已被停用，请联系管理员。'],
  INVALID_USER_QUERY: [400, 'INVALID_USER_QUERY', '用户查询条件不正确。'],
  INVALID_USER_STATUS: [400, 'INVALID_USER_STATUS', '请选择正常或已停用状态。'],
  USERNAME_TAKEN: [409, 'USERNAME_TAKEN', '这个用户名已被使用，请换一个。'],
  INVALID_PASSWORD_ACCOUNT: [400, 'INVALID_PASSWORD_ACCOUNT', '账号信息不完整，请检查后重试。'],
  USER_LOGIN_FAILED: [401, 'USER_LOGIN_FAILED', '用户名或密码不正确。'],
  INVALID_QQ_IDENTITY: [400, 'INVALID_QQ_IDENTITY', 'QQ 登录信息不完整，请重新授权。']
};
Object.assign(databaseErrors, {
  INVALID_AVATAR: [400, 'INVALID_AVATAR', '头像图片不完整，请重新选择。'],
  AVATAR_RESERVATION_EXPIRED: [409, 'AVATAR_RESERVATION_EXPIRED', '头像上传已过期，请重新选择。'],
  AVATAR_NOT_FOUND: [404, 'AVATAR_NOT_FOUND', '头像不存在。']
});
Object.assign(databaseErrors, {
  USER_LOGIN_REQUIRED: [401, 'USER_LOGIN_REQUIRED', '请先登录。'],
  USER_MUTED: [403, 'USER_MUTED', '账号处于禁言期间。'],
  SUBMISSION_BLOCKED: [403, 'SUBMISSION_BLOCKED', '账号处于禁止投稿期间。'],
  IDEMPOTENCY_REQUIRED: [400, 'IDEMPOTENCY_REQUIRED', '缺少请求标识。'],
  IDEMPOTENCY_CONFLICT: [409, 'IDEMPOTENCY_CONFLICT', '请求标识已用于其他内容，请重新操作。'],
  INVALID_COMMUNITY: [400, 'INVALID_COMMUNITY', '社区请求参数无效。'],
  INVALID_CHALLENGE: [400, 'INVALID_CHALLENGE', '验证请求无效。'],
  INVALID_TEXT: [400, 'INVALID_TEXT', '评论内容须为 1 至 500 字。'],
  COMMENT_UNAVAILABLE: [404, 'COMMENT_UNAVAILABLE', '评论不存在或暂时不可回复。'],
  COMMENT_QUOTA: [429, 'COMMENT_QUOTA', '今日评论次数已用完，请明天再来。'],
  COMMENT_TOO_FAST: [429, 'COMMENT_TOO_FAST', '评论过于频繁，请稍后重试。'],
  SUBMISSION_QUOTA: [429, 'SUBMISSION_QUOTA', '今日投稿次数已用完，请明天再来。'],
  UPLOAD_QUOTA: [429, 'UPLOAD_QUOTA', '今日上传次数已用完，请明天再来。'],
  PENDING_QUOTA: [409, 'PENDING_QUOTA', '待审核投稿已达到上限。'],
  DRAFT_QUOTA: [409, 'DRAFT_QUOTA', '未发布草稿已达到上限，请先处理现有投稿。'],
  DUPLICATE_SUBMISSION: [409, 'DUPLICATE_SUBMISSION', '你已投稿相同的预设文件，请编辑原投稿。'],
  REPORT_QUOTA: [429, 'REPORT_QUOTA', '今日举报次数已用完。'],
  ALREADY_PENDING: [409, 'ALREADY_PENDING', '这份投稿正在审核中。'],
  NOT_PENDING: [409, 'NOT_PENDING', '投稿已被处理，请刷新列表。']
});

export class CloudBaseStore {
  constructor({ environmentId, serviceKey, apiOrigin, fetchImpl = fetch }) {
    this.environmentId = environmentId;
    this.serviceKey = serviceKey;
    this.origin = apiOrigin || `https://${environmentId}.api.tcloudbasegateway.com`;
    this.fetch = fetchImpl;
  }
  get configured() { return Boolean(this.serviceKey && this.environmentId); }

  async request(path, { method = 'GET', body, headers = {}, raw = false } = {}) {
    if (!this.configured) throw new ServiceError(503, 'NOT_CONFIGURED', '后台尚未完成云端配置，请联系管理员。');
    let response;
    try {
      response = await this.fetch(new URL(path, this.origin), {
        method,
        headers: { Authorization: `Bearer ${this.serviceKey}`, ...(raw ? {} : { 'Content-Type': 'application/json' }), ...headers },
        body: body === undefined ? undefined : (raw ? body : JSON.stringify(body)),
        signal: AbortSignal.timeout(25000), redirect: 'error'
      });
    } catch { throw new ServiceError(503, 'CLOUD_UNAVAILABLE', '云端暂时无法连接，请稍后重试。'); }
    let result;
    try { result = await response.json(); } catch { result = null; }
    if (!response.ok) {
      const known = databaseErrors[result?.message];
      if (known) throw new ServiceError(...known);
      // Never surface provider errors or credentials in browser responses/logs.
      throw new ServiceError(503, 'CLOUD_REQUEST_FAILED', '云端请求未完成，请稍后重试。');
    }
    return result;
  }
  rest(path, options) { return this.request(`/v1/rdb/rest/${path}`, options); }
  rpc(name, body) { return this.rest(`rpc/${name}`, { method: 'POST', body }); }
  async findAdmin(username) {
    const rows = await this.rest(`nikon_admins?username=eq.${encodeURIComponent(username)}&select=username,password_hash,disabled&limit=1`);
    return rows[0] || null;
  }
  loginAllowed(username) { return this.rpc('nikon_login_allowed', { p_key: username }); }
  async clearAttempts(username) { await this.rest(`nikon_login_attempts?attempt_key=eq.${encodeURIComponent(username)}`, { method: 'DELETE' }); }
  async createSession(session) {
    await this.rest('nikon_sessions', { method: 'POST', body: session });
    await this.rest(`nikon_sessions?expires_at=lt.${encodeURIComponent(new Date().toISOString())}`, { method: 'DELETE' });
  }
  async session(tokenHash) {
    const rows = await this.rest(`nikon_sessions?token_hash=eq.${tokenHash}&expires_at=gt.${encodeURIComponent(new Date().toISOString())}&select=username,csrf_token,expires_at,nikon_admins!inner(disabled)&limit=1`);
    const row = rows[0];
    return row && !row.nikon_admins?.disabled ? row : null;
  }
  async deleteSession(hash) { await this.rest(`nikon_sessions?token_hash=eq.${hash}`, { method: 'DELETE' }); }
  users({ q, status, page, pageSize }) {
    return this.rpc('nikon_users_page', { p_q: q, p_status: status, p_page: page, p_page_size: pageSize });
  }
  user(id) { return this.rpc('nikon_user_detail', { p_id: id }); }
  setUserStatus(id, status, expectedUpdatedAt) {
    return this.rpc('nikon_set_user_status', { p_id: id, p_status: status, p_expected_updated_at: expectedUpdatedAt });
  }
  signInQq(identity, tokenHash, expiresAt) {
    return this.rpc('nikon_sign_in_qq', { p_app_id: identity.appId, p_openid: identity.openid,
      p_nickname: identity.nickname, p_avatar_url: identity.avatarUrl, p_token_hash: tokenHash, p_expires_at: expiresAt });
  }
  async qqIdentityExists(identity) {
    const rows = await this.rest(`nikon_user_identities?provider=eq.qq&app_id=eq.${encodeURIComponent(identity.appId)}&provider_subject=eq.${encodeURIComponent(identity.openid.toUpperCase())}&select=user_id&limit=1`);
    return rows.length > 0;
  }
  community(action, userId = null, id = null, body = {}) {
    return this.rpc('nikon_community', { p_action: action, p_user_id: userId, p_id: id, p_body: body });
  }
  personal(action,userId,hash=null,body={}) { return this.rpc('nikon_personal_library',{p_action:action,p_user_id:userId,p_hash:hash,p_body:body}); }
  abuseAllowed(scope, sourceHash, installationHash) {
    return this.rpc('nikon_abuse_allowed', { p_scope: scope, p_source_hash: sourceHash, p_installation_hash: installationHash });
  }
  issueChallenge({ id, answerHash, sourceHash, installationHash, expiresAt }) {
    return this.rpc('nikon_issue_challenge', { p_id: id, p_answer_hash: answerHash, p_source_hash: sourceHash, p_installation_hash: installationHash, p_expires_at: expiresAt });
  }
  consumeChallenge({ id, answerHash, sourceHash, installationHash, purpose }) {
    return this.rpc('nikon_consume_challenge', { p_id: id, p_answer_hash: answerHash, p_source_hash: sourceHash, p_installation_hash: installationHash, p_purpose: purpose });
  }
  userSession(hash) { return this.rpc('nikon_user_session', { p_token_hash: hash }); }
  reserveAvatar(userId, id, expectedUpdatedAt, { sourceHash, installationHash }) {
    return this.rpc('nikon_avatar_reserve', { p_user_id: userId, p_id: id, p_expected_updated_at: expectedUpdatedAt,
      p_source_hash: sourceHash, p_installation_hash: installationHash });
  }
  setAvatar(userId, id, expectedUpdatedAt, avatar) {
    return this.rpc('nikon_avatar_set', { p_user_id: userId, p_id: id, p_expected_updated_at: expectedUpdatedAt, p_avatar: avatar });
  }
  resetAvatar(userId, expectedUpdatedAt) {
    return this.rpc('nikon_avatar_reset', { p_user_id: userId, p_expected_updated_at: expectedUpdatedAt });
  }
  avatarAsset(id) { return this.rpc('nikon_avatar_asset', { p_id: id }); }
  passwordAuthAllowed(key, registration) {
    return this.rpc('nikon_password_auth_allowed', { p_key: key, p_registration: registration });
  }
  async clearPasswordAttempts(key) {
    await this.rest(`nikon_login_attempts?attempt_key=eq.${encodeURIComponent('password-login:' + key)}`, { method: 'DELETE' });
  }
  async passwordCredential(username) {
    const rows = await this.rest(`nikon_password_credentials?username=eq.${encodeURIComponent(username)}&select=username,user_id,password_hash&limit=1`);
    return rows[0] || null;
  }
  registerPassword(username, passwordHash, tokenHash, expiresAt) {
    return this.rpc('nikon_register_password', { p_username: username, p_password_hash: passwordHash,
      p_token_hash: tokenHash, p_expires_at: expiresAt });
  }
  signInPassword(username, verifiedHash, tokenHash, expiresAt) {
    return this.rpc('nikon_sign_in_password', { p_username: username, p_verified_hash: verifiedHash,
      p_token_hash: tokenHash, p_expires_at: expiresAt });
  }
  async deleteUserSession(hash) { await this.rest(`nikon_user_sessions?token_hash=eq.${hash}`, { method: 'DELETE' }); }
  presets() { return this.rest('nikon_presets?select=*&order=updated_at.desc&limit=5000'); }
  catalogRows() { return this.rest('nikon_presets?status=eq.published&select=id,status,published&limit=257'); }
  async preset(id) {
    const rows = await this.rest(`nikon_presets?id=eq.${id}&select=*&limit=1`);
    if (!rows[0]) throw new ServiceError(404, 'NOT_FOUND', '这份预设不存在，请刷新列表。');
    return rows[0];
  }
  async asset(id) {
    if (!/^(ncp|preview)-[a-f0-9]{64}$/.test(id || '')) throw new ServiceError(400, 'INVALID_ASSET', '请先完成文件上传。');
    const rows = await this.rest(`nikon_assets?id=eq.${id}&select=payload&limit=1`);
    return rows[0]?.payload || null;
  }
  async publishedAsset(id) {
    const rows = await this.rest(`nikon_assets?id=eq.${id}&ever_published=eq.true&select=payload&limit=1`);
    return rows[0]?.payload || null;
  }
  async addAsset(asset) {
    await this.rest('nikon_assets?on_conflict=id', { method: 'POST', headers: { Prefer: 'resolution=ignore-duplicates' }, body: { id: asset.id, payload: asset } });
    return this.asset(asset.id);
  }
  savePreset(id, expectedRevision, draft) {
    return this.rpc('nikon_save_preset', { p_id: id, p_expected_revision: expectedRevision, p_draft: draft });
  }
  publish(id, expectedRevision, publish) {
    return this.rpc('nikon_publish_preset', { p_id: id, p_expected_revision: expectedRevision, p_publish: publish });
  }
  versions(id) {
    return this.rest(`nikon_preset_versions?preset_id=eq.${id}&select=*&order=revision.desc&limit=1000`);
  }
}

export class CloudBaseStorage {
  constructor(store, bucket = 'nikon-presets') { this.store = store; this.bucket = bucket; }
  publicUrl(path) {
    return `${this.store.origin}/v1/storages/object/public/${encodeURIComponent(this.bucket)}/${path.split('/').map(encodeURIComponent).join('/')}`;
  }
  async put(path, data, mimeType) {
    await this.store.request(`/v1/storages/object/${encodeURIComponent(this.bucket)}/${path}`, {
      method: 'POST', raw: true, body: data,
      headers: { 'Content-Type': mimeType, 'Cache-Control': 'no-store', 'x-upsert': 'true' }
    });
    return this.publicUrl(path);
  }
  async get(path, maximum) {
    let response;
    try {
      response = await this.store.fetch(new URL(`/v1/storages/object/authenticated/${encodeURIComponent(this.bucket)}/${path}`, this.store.origin), {
        headers: { Authorization: `Bearer ${this.store.serviceKey}` }, signal: AbortSignal.timeout(25000), redirect: 'error'
      });
      if (!response.ok) throw new Error();
      const chunks = []; let size = 0;
      for await (const chunk of response.body) {
        size += chunk.length;
        if (size > maximum) throw new Error();
        chunks.push(chunk);
      }
      return Buffer.concat(chunks, size);
    } catch { throw new ServiceError(503, 'FILE_UNAVAILABLE', '文件暂时无法读取，请稍后重试。'); }
  }
}
