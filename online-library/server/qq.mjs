const GRAPH_ORIGIN = 'https://graph.qq.com';
const MAX_RESPONSE_BYTES = 64 * 1024;
const APP_ID = /^[0-9]{5,32}$/;
const OPEN_ID = /^[A-Fa-f0-9]{32}$/;
const MESSAGES = Object.freeze({
  qq_not_configured: 'QQ 登录尚未开通，请完成 QQ 互联移动应用申请。',
  qq_invalid_token: 'QQ 登录凭据格式不正确，请重新授权。',
  qq_verification_failed: 'QQ 授权验证未通过，请重新登录。',
  qq_provider_unavailable: '暂时无法验证 QQ 用户信息，请稍后重试。',
  qq_timeout: 'QQ 授权验证超时，请稍后重试。'
});

/** Never retain the provider body, token, request URL, or original error as a cause. */
export class QqError extends Error {
  constructor(status, code) {
    super(MESSAGES[code] || MESSAGES.qq_provider_unavailable);
    this.name = 'QqError';
    this.status = status;
    this.code = Object.hasOwn(MESSAGES, code) ? code : 'qq_provider_unavailable';
  }
}

function providerError() { return new QqError(502, 'qq_provider_unavailable'); }
function rejectedToken() { return new QqError(401, 'qq_verification_failed'); }
function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }

function validToken(value) {
  // Treat the access token as opaque. URLSearchParams encodes it before transport.
  return typeof value === 'string' && value.length >= 16 && value.length <= 2048
    && /^[\x21-\x7e]+$/.test(value);
}

function nickname(value, token) {
  if (typeof value !== 'string') throw providerError();
  const selected = value.trim();
  if (!selected || selected.length > 160 || /[\u0000-\u001f\u007f-\u009f]/u.test(selected)
      || Array.from(selected).some(character => character.length === 1 && /[\uD800-\uDFFF]/u.test(character))
      || selected.includes(token)) throw providerError();
  return selected;
}

function avatarUrl(profile, token) {
  for (const field of ['figureurl_qq_2', 'figureurl_qq_1', 'figureurl_2', 'figureurl_1', 'figureurl']) {
    const candidate = profile[field];
    if (typeof candidate !== 'string' || candidate.length > 2048 || /[\u0000-\u0020\u007f]/u.test(candidate)
        || candidate.includes(token)) continue;
    try {
      const url = new URL(candidate);
      const hostname = url.hostname.toLowerCase();
      if (hostname !== 'qlogo.cn' && !hostname.endsWith('.qlogo.cn')) continue;
      if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.port || url.hash) continue;
      if ([...url.searchParams.keys()].some(key => /^(access_token|token|openid)$/i.test(key))) continue;
      // QQ's documentation still includes HTTP qlogo avatars; only upgrade this allowlisted origin.
      url.protocol = 'https:';
      return url.href;
    } catch { /* A missing or invalid avatar does not invalidate an otherwise verified QQ identity. */ }
  }
  return '';
}

async function boundedJson(response) {
  const length = response.headers.get('content-length');
  if (length !== null && (!/^[0-9]+$/.test(length) || Number(length) > MAX_RESPONSE_BYTES)) {
    await response.body?.cancel().catch(() => {});
    throw providerError();
  }
  if (!response.body || typeof response.body.getReader !== 'function') throw providerError();
  const reader = response.body.getReader();
  const chunks = [];
  let bytes = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      if (!(value instanceof Uint8Array)) throw providerError();
      bytes += value.byteLength;
      if (bytes > MAX_RESPONSE_BYTES) throw providerError();
      chunks.push(value);
    }
  } catch (error) {
    await reader.cancel().catch(() => {});
    throw error;
  } finally { reader.releaseLock(); }
  const all = new Uint8Array(bytes);
  let position = 0;
  for (const chunk of chunks) { all.set(chunk, position); position += chunk.byteLength; }
  const parsed = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(all));
  if (!object(parsed)) throw providerError();
  return parsed;
}

/** Validate an Android QQ SDK access token directly with QQ before trusting identity/profile. */
export class QqIdentityProvider {
  #appId;
  #fetch;
  #timeoutMs;

  constructor({ appId = '', fetchImpl = globalThis.fetch, timeoutMs = 8000 } = {}) {
    this.#appId = typeof appId === 'string' ? appId.trim() : '';
    this.#fetch = fetchImpl;
    this.#timeoutMs = Number.isInteger(timeoutMs) && timeoutMs >= 1 && timeoutMs <= 30000 ? timeoutMs : 8000;
  }

  get configured() { return APP_ID.test(this.#appId) && typeof this.#fetch === 'function'; }

  status() {
    return {
      provider: 'qq', configured: this.configured,
      message: this.configured ? 'QQ 登录已配置，使用手机 App 授权后登录。' : MESSAGES.qq_not_configured
    };
  }

  async #request(path, parameters, signal) {
    const url = new URL(path, GRAPH_ORIGIN);
    for (const [key, value] of Object.entries(parameters)) url.searchParams.set(key, value);
    const response = await this.#fetch(url, {
      method: 'GET', redirect: 'error', credentials: 'omit', cache: 'no-store', signal,
      headers: { Accept: 'application/json', 'Accept-Encoding': 'identity' }
    });
    if (response.redirected || response.status !== 200) {
      await response.body?.cancel().catch(() => {});
      if ([400, 401, 403].includes(response.status)) throw rejectedToken();
      throw providerError();
    }
    return boundedJson(response);
  }

  async verifyAccessToken(accessToken) {
    if (!this.configured) throw new QqError(503, 'qq_not_configured');
    if (!validToken(accessToken)) throw new QqError(400, 'qq_invalid_token');
    const controller = new AbortController();
    // One deadline bounds both QQ calls, including reading and validating response bodies.
    const timer = setTimeout(() => controller.abort(), this.#timeoutMs);
    try {
      const identity = await this.#request('/oauth2.0/me', { access_token: accessToken, fmt: 'json' }, controller.signal);
      if (Object.hasOwn(identity, 'error') || Object.hasOwn(identity, 'error_description')
          || (Object.hasOwn(identity, 'ret') && identity.ret !== 0)) throw rejectedToken();
      if (identity.client_id !== this.#appId) throw rejectedToken();
      if (typeof identity.openid !== 'string' || !OPEN_ID.test(identity.openid)) throw providerError();
      const profile = await this.#request('/user/get_user_info', {
        access_token: accessToken, oauth_consumer_key: this.#appId, openid: identity.openid
      }, controller.signal);
      if (Object.hasOwn(profile, 'error') || profile.ret !== 0) throw rejectedToken();
      if (profile.is_lost === 1 || profile.is_lost === '1') throw providerError();
      return {
        appId: this.#appId, openid: identity.openid,
        nickname: nickname(profile.nickname, accessToken), avatarUrl: avatarUrl(profile, accessToken)
      };
    } catch (error) {
      if (controller.signal.aborted) throw new QqError(504, 'qq_timeout');
      if (error instanceof QqError) throw error;
      throw providerError();
    } finally { clearTimeout(timer); }
  }

  async verify(input) { return this.verifyAccessToken(object(input) ? input.accessToken : undefined); }
}

export function createQqVerifier(options = {}) { return new QqIdentityProvider(options); }
