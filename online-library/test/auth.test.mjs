import test from 'node:test';
import assert from 'node:assert/strict';
import { hashPassword, verifyPassword, readCookie, randomToken, equalToken, LoginGate } from '../server/auth.mjs';

test('scrypt uses random salts and treats missing/corrupt/incorrect credentials uniformly', async () => {
  const password = 'Test-Only-Long-Password-2807';
  const first = await hashPassword(password), second = await hashPassword(password);
  assert.notEqual(first, second);
  assert.equal(await verifyPassword(password, first), true);
  assert.equal(await verifyPassword('incorrect', first), false);
  assert.equal(await verifyPassword(password, null), false);
  assert.equal(await verifyPassword(password, 'scrypt:1:2:3:broken'), false);
  await assert.rejects(hashPassword('short'));
  await assert.rejects(hashPassword('1234567'));
  assert.equal(await verifyPassword('12345678', await hashPassword('12345678')), true);
});
test('login work is bounded even when attackers keep changing usernames', () => {
  let now = 0;
  const gate = new LoginGate({ maximum: 3, concurrent: 1, windowMs: 1000, now: () => now });
  const release = gate.acquire();
  assert.equal(typeof release, 'function');
  assert.equal(gate.acquire(), null, 'concurrent KDF work is capped');
  release(); release(); assert.equal(gate.active, 0);
  const again = gate.acquire(); again();
  assert.equal(gate.acquire(), null, 'new usernames share the same bounded attempt window');
  now = 1001;
  const next = gate.acquire(); assert.equal(typeof next, 'function'); next();
});
test('session cookies reject duplicate/malformed tokens and CSRF requires an exact match', () => {
  const token = randomToken();
  assert.equal(readCookie(`other=value; nikon_session=${token}`, 'nikon_session'), token);
  assert.equal(readCookie(`nikon_session=${token}; nikon_session=${token}`, 'nikon_session'), '');
  assert.equal(readCookie('nikon_session=../../path', 'nikon_session'), '');
  assert.equal(equalToken(token, token), true); assert.equal(equalToken(token, randomToken()), false);
  assert.equal(equalToken('', undefined), false);
  assert.equal(equalToken(token, 'é' + 'a'.repeat(42)), false);
});
