import { randomBytes, scrypt as scryptCallback, timingSafeEqual, createHash } from 'node:crypto';
import { promisify } from 'node:util';

const scrypt = promisify(scryptCallback);
export const sha256 = value => createHash('sha256').update(value).digest('hex');
export const randomToken = () => randomBytes(32).toString('base64url');

export async function hashPassword(password) {
  if (typeof password !== 'string' || password.length < 16 || password.length > 256) {
    throw new Error('管理员密码须为 16 至 256 个字符。');
  }
  return derivePassword(password);
}

/** App passwords have their own policy; the administrator policy above stays unchanged. */
export async function hashUserPassword(password) {
  if (typeof password !== 'string' || password.length < 8 || password.length > 128) {
    throw new Error('密码须为 8 至 128 个字符。');
  }
  return derivePassword(password);
}

async function derivePassword(password) {
  const salt = randomBytes(16).toString('hex');
  const digest = await scrypt(password, salt, 64, { N: 32768, r: 8, p: 1, maxmem: 64 * 1024 * 1024 });
  return `scrypt:32768:8:1:${salt}:${digest.toString('hex')}`;
}

const dummyHash = 'scrypt:32768:8:1:00000000000000000000000000000000:' + '00'.repeat(64);
export async function verifyPassword(password, encoded) {
  const value = typeof encoded === 'string' ? encoded : dummyHash;
  const parts = value.split(':');
  const valid = parts.length === 6 && parts[0] === 'scrypt' && parts[1] === '32768' && parts[2] === '8'
    && parts[3] === '1' && /^[a-f0-9]{32}$/.test(parts[4]) && /^[a-f0-9]{128}$/.test(parts[5]);
  const selected = valid ? parts : dummyHash.split(':');
  const candidate = typeof password === 'string' && password.length <= 256 ? password : '';
  const digest = await scrypt(candidate, selected[4], 64, { N: 32768, r: 8, p: 1, maxmem: 64 * 1024 * 1024 });
  return Boolean(encoded && valid && candidate && timingSafeEqual(digest, Buffer.from(selected[5], 'hex')));
}

export function readCookie(header, name) {
  const values = String(header || '').split(';').map(value => value.trim()).filter(value => value.startsWith(name + '='));
  // Reject ambiguous cookies instead of choosing an attacker-supplied duplicate.
  if (values.length !== 1) return '';
  const token = values[0].slice(name.length + 1);
  return /^[A-Za-z0-9_-]{43}$/.test(token) ? token : '';
}

export function equalToken(first, second) {
  if (typeof first !== 'string' || typeof second !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(first) || !/^[A-Za-z0-9_-]{43}$/.test(second)) return false;
  return timingSafeEqual(Buffer.from(first), Buffer.from(second));
}

/** Bounded per-instance guard; persistent account limits are additionally enforced in PostgreSQL. */
export class LoginGate {
  constructor({ maximum = 40, concurrent = 2, windowMs = 60000, now = Date.now } = {}) {
    this.maximum = maximum; this.concurrent = concurrent; this.windowMs = windowMs; this.now = now;
    this.startedAt = now(); this.attempts = 0; this.active = 0;
  }
  acquire() {
    const now = this.now();
    if (now - this.startedAt >= this.windowMs) { this.startedAt = now; this.attempts = 0; }
    if (++this.attempts > this.maximum || this.active >= this.concurrent) return null;
    this.active++;
    let released = false;
    return () => { if (!released) { this.active--; released = true; } };
  }
}
