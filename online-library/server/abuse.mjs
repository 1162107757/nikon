import { createHmac } from 'node:crypto';
import { isIP } from 'node:net';
import { ServiceError } from './cloudbase.mjs';

function normalizedAddress(value) {
  let ip = String(value || '').toLowerCase();
  if (ip.startsWith('::ffff:') && isIP(ip.slice(7)) === 4) ip = ip.slice(7);
  if (isIP(ip) !== 6) return ip;
  const [left, right = ''] = ip.split('::'), a = left ? left.split(':') : [], b = right ? right.split(':') : [];
  const expanded = ip.includes('::') ? [...a, ...Array(8-a.length-b.length).fill('0'), ...b] : a;
  return expanded.map(x => parseInt(x,16).toString(16)).join(':');
}
function sourceBucket(ip) {
  const normalized = normalizedAddress(ip);
  return isIP(normalized) === 6 ? normalized.split(':').slice(0,4).join(':') + '::/64' : normalized;
}
export function createAbuseContext({ store, secret, trustedProxyHops = 0, trustedProxyAddresses = ['127.0.0.1', '::1'] }) {
  const key = secret || store.serviceKey;
  if (!Number.isInteger(trustedProxyHops) || trustedProxyHops < 0 || trustedProxyHops > 8) throw new Error('Invalid trusted proxy configuration');
  if (!Array.isArray(trustedProxyAddresses) || trustedProxyAddresses.some(ip => typeof ip !== 'string' || !isIP(ip))) throw new Error('Invalid trusted proxy address allowlist');
  const trusted = new Set(trustedProxyAddresses.map(normalizedAddress));
  const hash = value => {
    if (typeof key !== 'string' || key.length < 24) throw new ServiceError(503, 'CHALLENGE_NOT_CONFIGURED', '注册验证尚未配置，请稍后重试。');
    return createHmac('sha256', key).update(value).digest('hex');
  };
  function context(req, installationId) {
    let ip = req.socket?.remoteAddress || 'unknown';
    if (trustedProxyHops) {
      if (!trusted.has(normalizedAddress(ip))) throw new ServiceError(403, 'INVALID_PROXY_SOURCE', '请求来源未被配置为可信代理。');
      const forwarded = String(req.headers['x-forwarded-for'] || '').split(',').map(x => x.trim());
      const chain = [...forwarded, ip];
      if (chain.length <= trustedProxyHops || chain.some(x => !isIP(x))) throw new ServiceError(400, 'INVALID_PROXY_SOURCE', '请求来源无法验证。');
      ip = chain[chain.length - 1 - trustedProxyHops];
    }
    // IPv6 allocations commonly rotate host addresses. Bucket the /64 rather than treating each host as new.
    ip = sourceBucket(ip);
    const valid = typeof installationId === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(installationId);
    return { sourceHash: hash('source:' + ip), installationHash: hash('installation:' + (valid ? installationId.toLowerCase() : 'unknown:' + ip)) };
  }
  return { hash, context, inspect(req, probeHops) {
    if (probeHops !== undefined && (!Number.isInteger(probeHops) || probeHops < 1 || probeHops > 8)) throw new ServiceError(400, 'INVALID_PROBE_HOPS', '诊断代理跳数须为 1 至 8。');
    const remote = normalizedAddress(req.socket?.remoteAddress || 'unknown');
    const forwarded = String(req.headers['x-forwarded-for'] || '').split(',').map(x=>x.trim()).filter(Boolean);
    const last = [...forwarded].reverse().find(value=>isIP(value));
    let effectiveSourceHash = ''; try { effectiveSourceHash = context(req).sourceHash; } catch (error) { if (error.code !== 'INVALID_PROXY_SOURCE') throw error; }
    const result = { remoteIsLoopback: remote==='0:0:0:0:0:0:0:1' || /^127\./.test(remote), forwardedCount: forwarded.length,
      rightmostSourceHash: last ? hash('source:' + sourceBucket(last)) : '', effectiveSourceHash, trustedProxyHops };
    // Probe indexes only diagnostic XFF entries. It never changes context() or the configured trust boundary.
    if (probeHops !== undefined) {
      const candidate = forwarded[forwarded.length - probeHops];
      result.probeHops = probeHops;
      result.probeSourceHash = candidate && isIP(candidate) ? hash('source:' + sourceBucket(candidate)) : '';
    }
    return result;
  }, async protect(req, installationId, scope) {
    const values = context(req, installationId);
    if (!await store.abuseAllowed(scope, values.sourceHash, values.installationHash)) throw new ServiceError(429, 'ABUSE_THROTTLED', '操作较频繁，请稍后重试。');
    return values;
  } };
}
