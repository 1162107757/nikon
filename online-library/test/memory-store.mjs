import { randomUUID } from 'node:crypto';
import { ServiceError } from '../server/cloudbase.mjs';

// Test-only adapter. Production never imports this file or falls back to local data.
export class MemoryStore {
  configured = true;
  admins = new Map(); sessions = new Map(); attempts = new Map(); assets = new Map(); rows = new Map(); histories = new Map();
  publishedAssets = new Set();
  publicAssetOrigin = '';
  userRows = new Map(); userIdentities = new Map(); userSessions = new Map();
  passwordCredentials = new Map(); passwordAttempts = new Map();
  async findAdmin(username) { return structuredClone(this.admins.get(username) || null); }
  async loginAllowed(key) { const count = (this.attempts.get(key) || 0) + 1; this.attempts.set(key, count); return count <= 8; }
  async clearAttempts(key) { this.attempts.delete(key); }
  async createSession(session) { this.sessions.set(session.token_hash, structuredClone(session)); }
  async signInAdmin(session, verifiedHash) {
    const admin = this.admins.get(session.username);
    if (!admin || admin.disabled || admin.password_hash !== verifiedHash) throw new ServiceError(401, 'LOGIN_FAILED', '账号或密码不正确。');
    this.sessions.set(session.token_hash, structuredClone(session));
    return { ok: true };
  }
  async changeAdminPassword(username, sessionHash, verifiedHash, passwordHash) {
    const admin = this.admins.get(username), session = this.sessions.get(sessionHash);
    if (!admin || admin.disabled || !session || session.username !== username || Date.parse(session.expires_at) <= Date.now()) {
      throw new ServiceError(401, 'LOGIN_REQUIRED', '登录已失效，请重新登录。');
    }
    if (admin.password_hash !== verifiedHash) throw new ServiceError(409, 'PASSWORD_CONFLICT', '密码已被修改，请重新登录后再试。');
    admin.password_hash = passwordHash;
    for (const [hash, row] of this.sessions) if (row.username === username) this.sessions.delete(hash);
    return { ok: true };
  }
  async session(hash) {
    const row = this.sessions.get(hash);
    return row && Date.parse(row.expires_at) > Date.now() && !this.admins.get(row.username)?.disabled ? structuredClone(row) : null;
  }
  async deleteSession(hash) { this.sessions.delete(hash); }
  async users({ q, status, page, pageSize }) {
    const rows = [...this.userRows.values()];
    const found = rows.filter(row => (status === 'all' || row.status === status)
      && (!q || row.nickname.toLowerCase().includes(q.toLowerCase()) || row.id.includes(q.toLowerCase())
        || (row.username || '').includes(q.toLowerCase())))
      .sort((a, b) => b.lastLoginAt.localeCompare(a.lastLoginAt) || a.id.localeCompare(b.id));
    return { items: structuredClone(found.slice((page - 1) * pageSize, page * pageSize)), total: found.length, page, pageSize,
      summary: { total: rows.length, active: rows.filter(row => row.status === 'active').length, disabled: rows.filter(row => row.status === 'disabled').length } };
  }
  async user(id) {
    const row = this.userRows.get(id);
    if (!row) throw new ServiceError(404, 'USER_NOT_FOUND', '这位用户不存在，请刷新列表。');
    const identity = [...this.userIdentities.entries()].find(([, userId]) => userId === id)?.[0].split(':').at(-1) || '';
    return { ...structuredClone(row), qqOpenIdMasked: identity ? identity.slice(0, 4) + '…' + identity.slice(-4) : '' };
  }
  async setUserStatus(id, status, expectedUpdatedAt) {
    const row = this.userRows.get(id);
    if (!row) throw new ServiceError(404, 'USER_NOT_FOUND', '这位用户不存在，请刷新列表。');
    if (row.updatedAt !== expectedUpdatedAt) throw new ServiceError(409, 'USER_CONFLICT', '用户信息已更新，请刷新后再操作。');
    if (row.status !== status) {
      row.status = status;
      row.updatedAt = new Date(Math.max(Date.now(), Date.parse(row.updatedAt) + 1)).toISOString();
    }
    if (status === 'disabled') for (const [hash, session] of this.userSessions) if (session.userId === id) this.userSessions.delete(hash);
    return this.user(id);
  }
  async signInQq(identity, tokenHash, expiresAt) {
    const key = `qq:${identity.appId}:${identity.openid.toUpperCase()}`;
    const now = new Date().toISOString();
    let id = this.userIdentities.get(key), row = this.userRows.get(id);
    if (row) {
      if (row.status === 'disabled') throw new ServiceError(403, 'USER_DISABLED', '账号已被停用，请联系管理员。');
      row.nickname = identity.nickname; row.avatarUrl = identity.avatarUrl; row.loginCount++;
      row.lastLoginAt = now; row.updatedAt = new Date(Math.max(Date.now(), Date.parse(row.updatedAt) + 1)).toISOString();
    } else {
      id = randomUUID(); row = { id, username: '', nickname: identity.nickname, avatarUrl: identity.avatarUrl, provider: 'qq', status: 'active',
        createdAt: now, lastLoginAt: now, loginCount: 1, updatedAt: now };
      this.userRows.set(id, row); this.userIdentities.set(key, id);
    }
    this.issueUserSession(id, tokenHash, expiresAt);
    return this.user(id);
  }
  async qqIdentityExists(identity) { return this.userIdentities.has(`qq:${identity.appId}:${identity.openid.toUpperCase()}`); }
  async passwordAuthAllowed(key, registration) {
    const keys = [registration ? 'password-register-global' : 'password-login-global',
      `${registration ? 'password-register:' : 'password-login:'}${key}`];
    const counts = keys.map(key => {
      const old = this.passwordAttempts.get(key), now = Date.now();
      const next = old && old.windowStart >= now - 900000 ? { ...old, attempts: old.attempts + 1 } : { windowStart: now, attempts: 1 };
      this.passwordAttempts.set(key, next); return next.attempts;
    });
    return counts[0] <= (registration ? 120 : 600) && counts[1] <= 8;
  }
  async clearPasswordAttempts(key) { this.passwordAttempts.delete('password-login:' + key); }
  async passwordCredential(username) { return structuredClone(this.passwordCredentials.get(username) || null); }
  async registerPassword(username, passwordHash, tokenHash, expiresAt) {
    if (this.passwordCredentials.has(username)) throw new ServiceError(409, 'USERNAME_TAKEN', '这个用户名已被使用，请换一个。');
    const id = randomUUID(), now = new Date().toISOString();
    const row = { id, username, nickname: username, avatarUrl: '', provider: 'password', status: 'active',
      createdAt: now, lastLoginAt: now, loginCount: 1, updatedAt: now };
    this.userRows.set(id, row);
    this.passwordCredentials.set(username, { username, user_id: id, password_hash: passwordHash });
    this.issueUserSession(id, tokenHash, expiresAt);
    return this.user(id);
  }
  async signInPassword(username, verifiedHash, tokenHash, expiresAt) {
    const credential = this.passwordCredentials.get(username), row = this.userRows.get(credential?.user_id);
    if (!credential || credential.password_hash !== verifiedHash || !row) {
      throw new ServiceError(401, 'USER_LOGIN_FAILED', '用户名或密码不正确。');
    }
    if (row.status === 'disabled') throw new ServiceError(403, 'USER_DISABLED', '账号已被停用，请联系管理员。');
    row.loginCount++; row.lastLoginAt = new Date().toISOString();
    row.updatedAt = new Date(Math.max(Date.now(), Date.parse(row.updatedAt) + 1)).toISOString();
    this.issueUserSession(row.id, tokenHash, expiresAt);
    return this.user(row.id);
  }
  issueUserSession(userId, tokenHash, expiresAt) {
    this.userSessions.set(tokenHash, { userId, expiresAt, createdAt: Date.now() });
    for (const [hash, session] of this.userSessions) if (Date.parse(session.expiresAt) <= Date.now()) this.userSessions.delete(hash);
    const previous = [...this.userSessions].filter(([hash, row]) => row.userId === userId && hash !== tokenHash)
      .sort(([firstHash, first], [secondHash, second]) => second.createdAt - first.createdAt || firstHash.localeCompare(secondHash));
    for (const [hash] of previous.slice(9)) this.userSessions.delete(hash);
  }
  async userSession(hash) {
    const session = this.userSessions.get(hash), row = session ? this.userRows.get(session.userId) : null;
    return session && Date.parse(session.expiresAt) > Date.now() && row?.status === 'active'
      ? { user: structuredClone(row), expiresAt: session.expiresAt } : null;
  }
  async deleteUserSession(hash) { this.userSessions.delete(hash); }
  async presets() { return [...this.rows.values()].map(structuredCloneSafe); }
  async catalogRows() { return (await this.presets()).filter(row => row.status === 'published'); }
  async preset(id) {
    const row = this.rows.get(id);
    if (!row) throw new ServiceError(404, 'NOT_FOUND', '这份预设不存在，请刷新列表。');
    return structuredClone(row);
  }
  async asset(id) { return this.assets.get(id) || null; }
  async publishedAsset(id) { return this.publishedAssets.has(id) ? this.asset(id) : null; }
  async addAsset(asset) {
    const value = structuredClone(asset);
    // Local HTTP QA still needs HTTPS catalog URLs, like the real deployment.
    if (this.publicAssetOrigin) value.url = this.publicAssetOrigin + new URL(value.url).pathname;
    if (!this.assets.has(asset.id)) this.assets.set(asset.id, value);
    return this.asset(asset.id);
  }
  async savePreset(id, expectedRevision, draft) {
    const now = new Date().toISOString();
    let row;
    if (id) {
      row = await this.preset(id);
      if (row.revision !== expectedRevision) throw new ServiceError(409, 'REVISION_CONFLICT', '预设已被更新，请刷新后再保存。');
      if (JSON.stringify(row.draft) === JSON.stringify(draft)) return row;
      row.revision++; row.draft = structuredClone(draft); row.updated_at = now;
    } else {
      id = randomUUID(); row = { id, revision: 1, draft: structuredClone(draft), published: null,
        published_revision: null, status: 'draft', created_at: now, updated_at: now, published_at: null };
    }
    this.rows.set(id, row);
    const history = this.histories.get(id) || [];
    history.unshift({ preset_id: id, revision: row.revision, snapshot: structuredClone(draft), created_at: now });
    this.histories.set(id, history);
    return structuredClone(row);
  }
  async publish(id, revision, publish) {
    const row = await this.preset(id);
    if (row.revision !== revision) throw new ServiceError(409, 'REVISION_CONFLICT', '预设已被更新，请刷新后再操作。');
    if (publish) { row.status = 'published'; row.published = structuredClone(row.draft); row.published_revision = row.revision; row.published_at = new Date().toISOString(); this.publishedAssets.add(row.draft.file.id); if (row.draft.preview) this.publishedAssets.add(row.draft.preview.id); }
    else row.status = row.published ? 'unpublished' : 'draft';
    this.rows.set(id, row);
    return structuredClone(row);
  }
  async versions(id) { return structuredClone(this.histories.get(id) || []); }
}
function structuredCloneSafe(value) { return structuredClone(value); }

export function syntheticNcp() {
  const data = Buffer.alloc(52);
  data.write('NCP\0', 0, 'ascii'); data.writeUInt32BE(1, 4); data.writeUInt32BE(36, 8); data.write('0100', 12, 'ascii');
  return data;
}
