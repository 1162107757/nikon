import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHmac, randomUUID } from 'node:crypto';
import { createHandler } from '../server/app.mjs';
import { createChallengeService, rasterChallenge } from '../server/challenge.mjs';
import { createAbuseContext } from '../server/abuse.mjs';
import { validatePreview } from '../server/validation.mjs';
import { randomToken, sha256 } from '../server/auth.mjs';
import { CloudBaseStore, ServiceError } from '../server/cloudbase.mjs';
import { MemoryStore } from './memory-store.mjs';

const secret = 'synthetic-local-challenge-secret-32-plus';
const install = '22222222-2222-4222-8222-222222222222';
const requestSource = (ip = '127.0.0.1', forwarded) => ({ socket: { remoteAddress: ip }, headers: forwarded ? { 'x-forwarded-for': forwarded } : {} });
class ChallengeStore extends MemoryStore {
  tickets = new Map(); protection = [];
  async abuseAllowed(scope, sourceHash, installationHash) { this.protection.push({ scope, sourceHash, installationHash }); return true; }
  async issueChallenge(ticket) { this.tickets.set(ticket.id, { ...ticket }); return true; }
  async consumeChallenge(value) {
    const ticket = this.tickets.get(value.id); this.tickets.delete(value.id);
    return Boolean(ticket && ticket.answerHash === value.answerHash && ticket.sourceHash === value.sourceHash
      && ticket.installationHash === value.installationHash && Date.parse(ticket.expiresAt) > Date.now());
  }
}
async function fixture(t, { store = new ChallengeStore(), verifier } = {}) {
  const server = createServer(); await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const origin = `http://127.0.0.1:${server.address().port}`;
  const adminToken = randomToken(), csrf = randomToken(); store.admins.set('rootmain', { username: 'rootmain', disabled: false });
  await store.createSession({ token_hash: sha256(adminToken), username: 'rootmain', csrf_token: csrf, expires_at: new Date(Date.now() + 60000).toISOString() });
  const calls = [];
  store.community = async (action, userId, id, body) => { calls.push({ action, userId, id, body }); return { items: [], total: 0, ok: true }; };
  const storage = { put: async () => {}, get: async () => Buffer.alloc(0) };
  server.on('request', createHandler({ store, storage, environmentId: 'local-test', publicBaseUrl: origin,
    secureCookies: false, challengeSecret: secret, qqVerifier: verifier }));
  const request = (path, { method = 'GET', body, token, admin = false, headers = {}, raw } = {}) => fetch(origin + '/preset-library' + path, {
    method, headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(admin ? { Cookie: `nikon_session=${adminToken}`, Origin: origin, 'X-CSRF-Token': csrf } : {}), ...headers },
    body: raw ?? (body === undefined ? undefined : JSON.stringify(body))
  });
  return { store, calls, request, origin, csrf, adminToken };
}
test('challenge is a valid raster PNG, short lived and exposes no answer or hash', async () => {
  const store = new ChallengeStore(), service = createChallengeService({ store, secret });
  const value = await service.issue(requestSource(), install);
  assert.deepEqual(Object.keys(value).sort(), ['expiresAt', 'id', 'image']);
  assert.ok(Date.parse(value.expiresAt) > Date.now() + 110000);
  assert.match(value.image, /^data:image\/png;base64,/);
  const pixels = Buffer.from(value.image.split(',')[1], 'base64');
  assert.equal(validatePreview(pixels, 'image/png').mimeType, 'image/png');
  assert.ok(!pixels.includes(Buffer.from('<svg'))); assert.ok(!pixels.includes(Buffer.from('tEXt')));
  assert.equal(validatePreview(rasterChallenge('ABCDE')).mimeType, 'image/png');
  assert.match(store.tickets.get(value.id).answerHash, /^[a-f0-9]{64}$/);
  assert.ok(!JSON.stringify(value).includes(store.tickets.get(value.id).answerHash));
});
test('challenge binds source and installation and is consumed once even on a wrong answer', async () => {
  const store = new ChallengeStore(), service = createChallengeService({ store, secret }), source = requestSource();
  const issueKnown = async () => {
    const value = await service.issue(source, install), ticket = store.tickets.get(value.id);
    ticket.answerHash = createHmac('sha256', secret).update(`challenge:${value.id}:ABCDE`).digest('hex');
    return { challengeId: value.id, challengeAnswer: 'abcde', installationId: install };
  };
  let body = await issueKnown(); assert.equal(await service.consume(source, body), true);
  await assert.rejects(service.consume(source, body), { code: 'CHALLENGE_INVALID' });
  body = await issueKnown(); await assert.rejects(service.consume(source, { ...body, challengeAnswer: 'wrong' }), { code: 'CHALLENGE_INVALID' });
  await assert.rejects(service.consume(source, body), { code: 'CHALLENGE_INVALID' });
  body = await issueKnown(); await assert.rejects(service.consume(requestSource('127.0.0.2'), body), { code: 'CHALLENGE_INVALID' });
  body = await issueKnown(); await assert.rejects(service.consume(source, { ...body, installationId: randomUUID() }), { code: 'CHALLENGE_INVALID' });
  await assert.rejects(service.consume(source, {}), { status: 428, code: 'CHALLENGE_REQUIRED' });
});
test('abuse identity ignores forged XFF by default and trusts only configured valid hops; IPv6 /64 groups rotate-resistant', () => {
  const service = createAbuseContext({ store: {}, secret });
  assert.deepEqual(service.context(requestSource('127.0.0.1', '1.1.1.1'), install), service.context(requestSource('127.0.0.1', '8.8.8.8'), install));
  assert.equal(service.context(requestSource('2001:db8::1'), install).sourceHash, service.context(requestSource('2001:db8:0:0:1::2'), install).sourceHash);
  const proxy = createAbuseContext({ store: {}, secret, trustedProxyHops: 1 });
  assert.equal(proxy.context(requestSource('127.0.0.1', '8.8.8.8'), install).sourceHash, service.context(requestSource('8.8.8.8'), install).sourceHash);
  assert.throws(() => proxy.context(requestSource(), install), { code: 'INVALID_PROXY_SOURCE' });
  assert.throws(() => proxy.context(requestSource('127.0.0.1', 'forged'), install), { code: 'INVALID_PROXY_SOURCE' });
  assert.throws(() => proxy.context(requestSource('8.8.8.8', '1.1.1.1'), install), { code: 'INVALID_PROXY_SOURCE' });
  const custom = createAbuseContext({ store: {}, secret, trustedProxyHops: 1, trustedProxyAddresses: ['10.2.3.4'] });
  assert.equal(custom.context(requestSource('10.2.3.4', '8.8.8.8'), install).sourceHash, service.context(requestSource('8.8.8.8'), install).sourceHash);
  const diagnostic = proxy.inspect(requestSource('127.0.0.1', 'forged, 8.8.8.8'));
  assert.deepEqual(Object.keys(diagnostic).sort(), ['effectiveSourceHash','forwardedCount','remoteIsLoopback','rightmostSourceHash','trustedProxyHops']);
  assert.equal(diagnostic.effectiveSourceHash, ''); assert.match(diagnostic.rightmostSourceHash, /^[a-f0-9]{64}$/);
  assert.equal(diagnostic.remoteIsLoopback, true); assert.equal(diagnostic.forwardedCount, 2);
  assert.ok(!JSON.stringify(diagnostic).includes('8.8.8.8'));
});
test('optional hop probes hash one diagnostic XFF position without changing the effective production identity', () => {
  const service = createAbuseContext({ store: {}, secret });
  const chain = '203.0.113.20, 10.0.0.1, 10.0.0.2, 10.0.0.3';
  const source = requestSource('127.0.0.1', chain), before = service.context(source, install), base = service.inspect(source);
  const probe = service.inspect(source, 4);
  assert.equal(probe.probeHops, 4);
  assert.equal(probe.probeSourceHash, service.context(requestSource('203.0.113.20'), install).sourceHash);
  assert.equal(probe.effectiveSourceHash, base.effectiveSourceHash); assert.equal(probe.trustedProxyHops, 0);
  assert.deepEqual(service.context(source, install), before);
  assert.deepEqual(service.inspect(source), base); assert.equal(Object.keys(base).length, 5);
  const spoofed = requestSource('127.0.0.1', '1.1.1.1, ' + chain);
  assert.equal(service.inspect(spoofed, 4).probeSourceHash, probe.probeSourceHash);
  assert.equal(service.inspect(spoofed, 5).probeSourceHash, service.context(requestSource('1.1.1.1'), install).sourceHash);
  assert.equal(service.inspect(source, 5).probeSourceHash, '');
  const configured = createAbuseContext({ store: {}, secret, trustedProxyHops: 1 });
  assert.equal(configured.inspect(source, 4).effectiveSourceHash, configured.context(source).sourceHash);
  assert.equal(configured.inspect(source, 4).trustedProxyHops, 1);
  for (const bad of [0, 9, -1, 1.5, '4', null]) assert.throws(() => service.inspect(source, bad), { code: 'INVALID_PROBE_HOPS' });
});
test('registration fails before credential hashing/account creation without a valid persistent challenge', async t => {
  const { request, store } = await fixture(t);
  const missing = await request('/auth/register', { method: 'POST', body: { username: 'localuser', password: 'local password', installationId: install } });
  assert.equal(missing.status, 428); assert.equal((await missing.json()).error.code, 'CHALLENGE_REQUIRED');
  assert.equal(store.passwordCredentials.size, 0); assert.equal(store.userRows.size, 0);
  const response = await request(`/auth/challenge?installationId=${install}`), value = await response.json();
  assert.equal(response.status, 200); assert.match(value.image, /^data:image\/png;base64,/);
  const invalid = await request('/auth/register', { method: 'POST', body: { username: 'localuser', password: 'local password', installationId: install, challengeId: value.id, challengeAnswer: 'wrong' } });
  assert.equal(invalid.status, 400); assert.equal(store.userRows.size, 0);
  store.abuseAllowed = async () => { throw new ServiceError(503, 'CLOUD_UNAVAILABLE', 'offline'); };
  assert.equal((await request('/auth/register', { method: 'POST', body: { username: 'localuser', password: 'local password' } })).status, 503);
  assert.equal((await request('/community/feed')).status, 200);
});
test('valid account-create challenge completes registration and cannot create a second account on replay', async t => {
  const { request, store } = await fixture(t);
  const value = await (await request(`/auth/challenge?installationId=${install}`)).json();
  // The adapter represents a human reading the raster in this test; only an HMAC is persisted.
  store.tickets.get(value.id).answerHash = createHmac('sha256', secret).update(`challenge:${value.id}:ABCDE`).digest('hex');
  const body = { username: 'verified_user', password: 'synthetic local password', installationId: install, challengeId: value.id, challengeAnswer: 'abcde' };
  const response = await request('/auth/register', { method: 'POST', body });
  assert.equal(response.status, 201); const account = await response.json(); assert.match(account.accessToken, /^[A-Za-z0-9_-]{43}$/);
  assert.equal((await request('/auth/me', { token: account.accessToken })).status, 200);
  assert.equal((await request('/auth/register', { method: 'POST', body: { ...body, username: 'replay_user' } })).status, 400);
  assert.equal(store.userRows.size, 1);
});
test('QQ requires a challenge only before first account creation; existing identity keeps its opaque session flow', async t => {
  const identity = { appId: '123456789', openid: 'ABCDEF0123456789ABCDEF0123456789', nickname: 'Local QQ', avatarUrl: '' };
  const verifier = { configured: true, status: () => ({ configured: true }), verify: async () => identity };
  const { request, store } = await fixture(t, { verifier });
  const body = { accessToken: 'synthetic-qq-token-for-local-tests', installationId: install };
  assert.equal((await request('/auth/qq', { method: 'POST', body })).status, 428);
  assert.equal(store.userRows.size, 0);
  await store.signInQq(identity, sha256(randomToken()), new Date(Date.now() + 60000).toISOString());
  const response = await request('/auth/qq', { method: 'POST', body });
  assert.equal(response.status, 200); assert.match((await response.json()).accessToken, /^[A-Za-z0-9_-]{43}$/);
});
test('community anonymous reads, bearer ownership and admin CSRF remain separate', async t => {
  const { request, store, calls, origin, adminToken } = await fixture(t), userToken = randomToken();
  const account = await store.registerPassword('community_user', 'scrypt:local-test-only', sha256(userToken), new Date(Date.now() + 60000).toISOString());
  assert.equal((await request('/community/feed?page=2&pageSize=5&category=Portrait')).status, 200);
  assert.equal(calls.at(-1).action, 'feed'); assert.equal(calls.at(-1).userId, null);
  assert.equal((await request('/community/submissions')).status, 401);
  assert.equal((await request('/community/submissions', { token: userToken })).status, 200); assert.equal(calls.at(-1).userId, account.id);
  assert.equal((await request('/api/community/submissions', { token: userToken })).status, 401);
  assert.equal((await request('/api/community/security-source')).status, 401);
  const source = await (await request('/api/community/security-source', { admin: true, headers: { 'X-Forwarded-For': '1.1.1.1, 8.8.8.8' } })).json();
  assert.deepEqual(Object.keys(source).sort(), ['effectiveSourceHash','forwardedCount','remoteIsLoopback','rightmostSourceHash','trustedProxyHops']);
  assert.equal(source.forwardedCount, 2); assert.equal(source.trustedProxyHops, 0); assert.ok(!JSON.stringify(source).includes('8.8.8.8'));
  const probed = await (await request('/api/community/security-source?probeHops=2', { admin: true, headers: { 'X-Forwarded-For': '1.1.1.1, 8.8.8.8' } })).json();
  assert.equal(probed.probeHops, 2); assert.match(probed.probeSourceHash, /^[a-f0-9]{64}$/);
  assert.equal(probed.effectiveSourceHash, source.effectiveSourceHash); assert.equal(probed.trustedProxyHops, 0); assert.equal(Object.keys(probed).length, 7);
  assert.equal((await request('/api/community/security-source?probeHops=4')).status, 401);
  for (const bad of ['0', '9', '1.5', '01', '4&probeHops=5']) assert.equal((await request('/api/community/security-source?probeHops=' + bad, { admin: true })).status, 400);
  assert.equal((await request('/api/community/submissions', { admin: true })).status, 200);
  const path = `/api/community/submissions/${randomUUID()}/review`, body = { decision: 'approve', expectedRevision: 1, reason: '' };
  assert.equal((await request(path, { method: 'POST', body, headers: { Cookie: `nikon_session=${adminToken}`, Origin: origin } })).status, 403);
  assert.equal((await request(path, { method: 'POST', body, admin: true })).status, 200);
  assert.equal(calls.at(-1).action, 'adminReviewSubmission'); assert.equal(calls.at(-1).body.actor, 'rootmain');
});
test('reply target is passed separately from the thread root and validated before persistence', async t => {
  const { request, store, calls } = await fixture(t), token = randomToken();
  await store.registerPassword('reply_user', 'local-synthetic-hash', sha256(token), new Date(Date.now() + 60000).toISOString());
  const path = `/community/presets/${randomUUID()}/comments`, parentId = randomUUID(), replyToId = randomUUID();
  const body = { text: 'reply to the selected author', parentId, replyToId, idempotencyKey: 'reply-target-local' };
  assert.equal((await request(path, { method: 'POST', token, body })).status, 201);
  assert.equal(calls.at(-1).body.parentId, parentId); assert.equal(calls.at(-1).body.replyToId, replyToId);
  const count = calls.length;
  assert.equal((await request(path, { method: 'POST', token, body: { ...body, parentId: null } })).status, 400);
  for (const invalid of ['invalid', [replyToId], { id: replyToId }])
    assert.equal((await request(path, { method: 'POST', token, body: { ...body, replyToId: invalid } })).status, 404);
  assert.equal(calls.length, count);
  const legacy = { text: 'old client root reply', parentId, idempotencyKey: 'legacy-root-local' };
  assert.equal((await request(path, { method: 'POST', token, body: legacy })).status, 201);
  assert.equal('replyToId' in calls.at(-1).body, false); // Preserve old idempotency hashes.
});
test('community validates Unicode/plaintext, idempotency, rights and transport bounds before persistence', async t => {
  const { request, store, calls } = await fixture(t), token = randomToken();
  await store.registerPassword('community_user', 'local-synthetic-hash', sha256(token), new Date(Date.now() + 60000).toISOString());
  const comments = `/community/presets/${randomUUID()}/comments`;
  for (const text of ['x'.repeat(501), '<img src=x>', '\u0000', '\ud800']) {
    assert.equal((await request(comments, { token, method: 'POST', body: { text, idempotencyKey: 'comment-local-key' } })).status, 400);
  }
  assert.equal((await request(comments, { token, method: 'POST', body: { text: '🙂'.repeat(500), idempotencyKey: 'comment-local-key' } })).status, 201);
  assert.equal(calls.at(-1).body.text, '🙂'.repeat(500));
  assert.equal((await request(comments, { token, method: 'POST', body: { text: 'valid' } })).status, 400);
  assert.equal((await request('/community/submissions', { token, method: 'POST', body: { name: 'local', fileAssetId: 'ncp-' + 'a'.repeat(64), idempotencyKey: 'local-save-key' } })).status, 400);
  assert.equal((await request('/community/uploads?kind=ncp', { token, method: 'POST', raw: Buffer.from('fake') })).status, 400);
  assert.equal((await request('/community/uploads?kind=preview', { token, method: 'POST', raw: Buffer.alloc(4194305) })).status, 413);
});
test('every comment is classified server-side and client moderation flags cannot bypass review', async t => {
 const {request,store,calls}=await fixture(t),token=randomToken();await store.registerPassword('moderation_user','local-test-hash',sha256(token),new Date(Date.now()+60000).toISOString());
 const path=`/community/presets/${randomUUID()}/comments`;
 assert.equal((await request(path,{method:'POST',token,body:{text:'我用Z6II拍人像，效果不错',idempotencyKey:'normal-moderation-1',moderation:{decision:'review'}}})).status,201);
 assert.equal(calls.at(-1).body.moderation.decision,'allow');
 assert.equal((await request(path,{method:'POST',token,body:{text:'加我微信abc123',idempotencyKey:'review-moderation-1',moderation:{decision:'allow'}}})).status,201);
 assert.equal(calls.at(-1).body.moderation.decision,'review');assert.match(calls.at(-1).body.moderation.reason,/引流/);
 assert.equal((await request(path,{method:'POST',token,body:{text:'https://spam.invalid',idempotencyKey:'link-moderation-1'}})).status,201);assert.equal(calls.at(-1).body.moderation.decision,'review');
});
test('message read-batch validates IDs and binds the authenticated owner', async t => {
 const {request,store,calls}=await fixture(t),token=randomToken(),message=randomUUID();const user=await store.registerPassword('readbatch_user','local-test-hash',sha256(token),new Date(Date.now()+60000).toISOString());
 assert.equal((await request('/community/messages/read-batch',{method:'POST',body:{ids:[message]}})).status,401);
 for(const ids of [[],Array(51).fill(message),[123],['invalid']])assert.ok([400,404].includes((await request('/community/messages/read-batch',{method:'POST',token,body:{ids}})).status));
 assert.equal((await request('/community/messages/read-batch',{method:'POST',token,body:{ids:[message,message],userId:randomUUID()}})).status,200);
 assert.equal(calls.at(-1).action,'readBatch');assert.equal(calls.at(-1).userId,user.id);assert.deepEqual(calls.at(-1).body.ids,[message]);
});
test('HTTP preserves queued comment status and the existing administrator review contract', async t => {
  const { request, store } = await fixture(t), token = randomToken(), presetId = randomUUID(), commentId = randomUUID();
  await store.registerPassword('queued_user', 'local-synthetic-hash', sha256(token), new Date(Date.now() + 60000).toISOString());
  const pending = { id: commentId, presetId, parentId: null, status: 'pending', text: '' };
  store.community = async (action, userId, id, body) => {
    if (action === 'comment') return pending;
    if (action === 'adminComments') { assert.equal(body.status, 'pending'); return { items: [pending], total: 1 }; }
    if (action === 'adminReviewComment') { assert.equal(id, commentId); assert.equal(body.decision, 'restore'); assert.equal(body.actor, 'rootmain'); return { ...pending, status: 'visible', text: 'queued local comment' }; }
    throw new Error('Unexpected community action');
  };
  const response = await request(`/community/presets/${presetId}/comments`, { token, method: 'POST', body: { text: 'queued local comment', idempotencyKey: 'pending-http-local' } });
  assert.equal(response.status, 201); assert.deepEqual((await response.json()).comment, pending);
  assert.equal((await request('/api/community/comments?status=pending', { admin: true })).status, 200);
  const approval = await request(`/api/community/comments/${commentId}/review`, { admin: true, method: 'POST', body: { decision: 'restore', reason: 'approved' } });
  assert.equal(approval.status, 200); assert.equal((await approval.json()).status, 'visible');
});
test('CloudBase community and challenge adapters send service-only RPC JSON with hashed identities', async () => {
  const calls = [], store = new CloudBaseStore({ environmentId: 'local', serviceKey: 'local-service-secret', fetchImpl: async (url, options) => {
    calls.push({ url: new URL(url), body: JSON.parse(options.body) }); return new Response('true', { status: 200 });
  } });
  await store.community('feed', null, null, { page: 1, pageSize: 20 });
  assert.equal(calls.at(-1).url.pathname, '/v1/rdb/rest/rpc/nikon_community');
  await store.issueChallenge({ id: randomUUID(), answerHash: 'a'.repeat(64), sourceHash: 'b'.repeat(64), installationHash: 'c'.repeat(64), expiresAt: new Date().toISOString() });
  assert.equal(calls.at(-1).url.pathname, '/v1/rdb/rest/rpc/nikon_issue_challenge');
  assert.ok(!Object.keys(calls.at(-1).body).some(key => /password|token$|answer$/.test(key)));
});
