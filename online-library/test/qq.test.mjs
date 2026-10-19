import test from 'node:test';
import assert from 'node:assert/strict';
import { createQqVerifier, QqError, QqIdentityProvider } from '../server/qq.mjs';

const appId = '101234567';
const openid = '0123456789ABCDEF0123456789ABCDEF';
const token = 'synthetic-qq-access-token-for-tests';
const identity = { client_id: appId, openid };
const profile = { ret: 0, nickname: '  摄影爱好者  ', figureurl_qq_2: `https://q.qlogo.cn/qqapp/${appId}/${openid}/100` };
const json = (body, init) => new Response(JSON.stringify(body), init);

function queue(...values) {
  const calls = [];
  const fetchImpl = async (address, options) => {
    calls.push({ url: new URL(address), options });
    const value = values.shift();
    if (value instanceof Error) throw value;
    assert.ok(value instanceof Response, 'Synthetic QQ response is provided');
    return value;
  };
  return { fetchImpl, calls };
}

async function fails(promise, status, code) {
  await assert.rejects(promise, error => {
    assert.ok(error instanceof QqError);
    assert.equal(error.status, status);
    assert.equal(error.code, code);
    assert.equal(error.message.includes(token), false);
    assert.equal(error.message.includes(openid), false);
    assert.equal(error.message.includes('graph.qq.com'), false);
    assert.equal(error.cause, undefined);
    return true;
  });
}

test('QQ stays explicitly unavailable without an actual AppID; no network or synthetic login fallback', async () => {
  for (const badId of ['', undefined, null, 101234567, 'bad-app', '1234', '1'.repeat(33)]) {
    let called = false;
    const verifier = createQqVerifier({ appId: badId, fetchImpl: () => { called = true; } });
    assert.equal(verifier.configured, false);
    assert.deepEqual(verifier.status(), { provider: 'qq', configured: false, message: 'QQ 登录尚未开通，请完成 QQ 互联移动应用申请。' });
    await fails(verifier.verify({ accessToken: token }), 503, 'qq_not_configured');
    assert.equal(called, false);
  }
});

test('QQ derives identity from Tencent, validates AppID, and retrieves only the verified openid profile', async () => {
  const fake = queue(json(identity), json(profile));
  const verifier = createQqVerifier({ appId, fetchImpl: fake.fetchImpl });
  assert.ok(verifier instanceof QqIdentityProvider);
  assert.equal(verifier.status().configured, true);
  const result = await verifier.verify({ accessToken: token, openid: 'client-forged', nickname: 'client-forged' });
  assert.deepEqual(result, { appId, openid, nickname: '摄影爱好者', avatarUrl: profile.figureurl_qq_2 });
  assert.equal(fake.calls.length, 2);
  assert.equal(fake.calls[0].url.origin, 'https://graph.qq.com');
  assert.equal(fake.calls[0].url.pathname, '/oauth2.0/me');
  assert.equal(fake.calls[0].url.searchParams.get('fmt'), 'json');
  assert.equal(fake.calls[0].url.searchParams.get('unionid'), null, 'UnionID permission is not assumed');
  assert.equal(fake.calls[0].url.searchParams.get('access_token'), token);
  assert.equal(fake.calls[1].url.pathname, '/user/get_user_info');
  assert.equal(fake.calls[1].url.searchParams.get('openid'), openid);
  assert.equal(fake.calls[1].url.searchParams.get('oauth_consumer_key'), appId);
  for (const call of fake.calls) {
    assert.equal(call.options.redirect, 'error');
    assert.equal(call.options.credentials, 'omit');
    assert.equal(call.options.cache, 'no-store');
    assert.equal(call.options.headers['Accept-Encoding'], 'identity');
    assert.equal(call.options.signal, fake.calls[0].options.signal, 'both calls share one deadline');
  }
  assert.equal(JSON.stringify(result).includes(token), false);
});

test('QQ validates opaque token bounds and encodes reserved characters before transport', async () => {
  const fake = queue(json(identity), json(profile));
  const verifier = createQqVerifier({ appId, fetchImpl: fake.fetchImpl });
  for (const accessToken of ['', null, undefined, 1, 'a'.repeat(15), 'a'.repeat(2049), 'has a space in token', 'token\nwith-newline', '密钥测试'.repeat(10)]) {
    await fails(verifier.verify({ accessToken }), 400, 'qq_invalid_token');
  }
  await fails(verifier.verify(null), 400, 'qq_invalid_token');
  assert.equal(fake.calls.length, 0);
  const opaque = 'opaque-token&openid=forged?+/%value';
  await verifier.verifyAccessToken(opaque);
  assert.equal(fake.calls[0].url.searchParams.size, 2);
  assert.equal(fake.calls[0].url.searchParams.get('access_token'), opaque);
});

test('QQ rejects tokens for another AppID and provider identity errors before profile lookup', async () => {
  for (const body of [
    { ...identity, client_id: '109999999' }, { ...identity, client_id: Number(appId) },
    { error: 100016, error_description: token }, { ...identity, error: 0 },
    { ...identity, error_description: token }, { ...identity, ret: 1 }
  ]) {
    const fake = queue(json(body));
    const verifier = createQqVerifier({ appId, fetchImpl: fake.fetchImpl });
    await fails(verifier.verify({ accessToken: token }), 401, 'qq_verification_failed');
    assert.equal(fake.calls.length, 1);
  }
  for (const value of ['', null, undefined, 'Z'.repeat(32), 'A'.repeat(33), ['A'.repeat(32)]]) {
    const verifier = createQqVerifier({ appId, fetchImpl: queue(json({ ...identity, openid: value })).fetchImpl });
    await fails(verifier.verify({ accessToken: token }), 502, 'qq_provider_unavailable');
  }
});

test('QQ never accepts a failed or partially lost profile and excludes additional personal attributes', async () => {
  for (const body of [{ ret: 1002, msg: token }, { ret: '0', nickname: 'User' }, { ...profile, error: token }, { ...profile, is_lost: 1 }, { ...profile, is_lost: '1' }]) {
    const verifier = createQqVerifier({ appId, fetchImpl: queue(json(identity), json(body)).fetchImpl });
    await fails(verifier.verify({ accessToken: token }), body.is_lost ? 502 : 401, body.is_lost ? 'qq_provider_unavailable' : 'qq_verification_failed');
  }
  const verifier = createQqVerifier({ appId, fetchImpl: queue(json(identity), json({ ...profile, gender: '男', city: '某城市', year: '2000', unionid: 'not-requested' })).fetchImpl });
  assert.deepEqual(Object.keys(await verifier.verify({ accessToken: token })).sort(), ['appId', 'avatarUrl', 'nickname', 'openid']);
});

test('QQ nickname validation rejects control characters, invalid Unicode, oversized or missing profile values', async () => {
  for (const value of ['', '  ', null, undefined, 123, 'a'.repeat(161), 'bad\u0000nickname', '\ud800', token]) {
    const verifier = createQqVerifier({ appId, fetchImpl: queue(json(identity), json({ ...profile, nickname: value })).fetchImpl });
    await fails(verifier.verify({ accessToken: token }), 502, 'qq_provider_unavailable');
  }
  const verifier = createQqVerifier({ appId, fetchImpl: queue(json(identity), json({ ...profile, nickname: '摄影📷' })).fetchImpl });
  assert.equal((await verifier.verify({ accessToken: token })).nickname, '摄影📷');
});

test('QQ avatar selection only permits HTTPS qlogo URLs with a safe HTTP upgrade and small-avatar fallback', async () => {
  const validSmall = `https://q.qlogo.cn/qqapp/${appId}/${openid}/40`;
  for (const bad of [
    'https://qlogo.cn.evil.example/a', 'https://notqlogo.cn/a', 'https://evil.example/qlogo.cn/a',
    'javascript:alert(1)', 'data:image/png;base64,abc', '//q.qlogo.cn/a',
    'https://user:password@q.qlogo.cn/a', 'https://q.qlogo.cn:8443/a', 'https://q.qlogo.cn/a#fragment',
    'https://q.qlogo.cn/a?access_token=untrusted', `https://q.qlogo.cn/${token}`, `https://q.qlogo.cn/${'a'.repeat(2049)}`,
    'https://q.qlogo.cn/a\n', ''
  ]) {
    const verifier = createQqVerifier({ appId, fetchImpl: queue(json(identity), json({ ...profile, figureurl_qq_2: bad, figureurl_qq_1: validSmall })).fetchImpl });
    assert.equal((await verifier.verify({ accessToken: token })).avatarUrl, validSmall);
  }
  const upgraded = createQqVerifier({ appId, fetchImpl: queue(json(identity), json({ ...profile, figureurl_qq_2: 'http://q.qlogo.cn/qqapp/avatar/100' })).fetchImpl });
  assert.equal((await upgraded.verify({ accessToken: token })).avatarUrl, 'https://q.qlogo.cn/qqapp/avatar/100');
  const absent = createQqVerifier({ appId, fetchImpl: queue(json(identity), json({ ret: 0, nickname: 'User' })).fetchImpl });
  assert.equal((await absent.verify({ accessToken: token })).avatarUrl, '');
});

test('QQ rejects redirects, failing HTTP statuses, malformed JSON/UTF-8, and excessive response sizes', async () => {
  const responses = [
    new Response('', { status: 302, headers: { Location: `https://evil.example/?access_token=${token}` } }),
    new Response(token, { status: 500 }), new Response('callback({});'),
    new Response('{"client_id":'), json([]), json(null), json('not-an-object'),
    new Response(new Uint8Array([0xff, 0xfe])),
    new Response('small', { headers: { 'content-length': '65537' } }),
    new Response('small', { headers: { 'content-length': 'not-a-number' } }),
    new Response('a'.repeat(65537))
  ];
  for (const response of responses) {
    const verifier = createQqVerifier({ appId, fetchImpl: queue(response).fetchImpl });
    await fails(verifier.verify({ accessToken: token }), 502, 'qq_provider_unavailable');
  }
  for (const status of [400, 401, 403]) {
    const verifier = createQqVerifier({ appId, fetchImpl: queue(new Response(token, { status })).fetchImpl });
    await fails(verifier.verify({ accessToken: token }), 401, 'qq_verification_failed');
  }
});

test('QQ sanitizes transport exceptions and bounds both the request and a slow streamed response', async () => {
  const failing = createQqVerifier({ appId, fetchImpl: () => { throw new Error(`failed https://graph.qq.com/?access_token=${token}&openid=${openid}`); } });
  await fails(failing.verify({ accessToken: token }), 502, 'qq_provider_unavailable');
  const neverFetch = async (_url, { signal }) => new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(new Error(token)), { once: true }));
  const slow = createQqVerifier({ appId, timeoutMs: 10, fetchImpl: neverFetch });
  await fails(slow.verify({ accessToken: token }), 504, 'qq_timeout');
  const streamed = createQqVerifier({ appId, timeoutMs: 10, fetchImpl: async (_url, { signal }) => new Response(new ReadableStream({
    start(controller) { signal.addEventListener('abort', () => controller.error(new Error(token)), { once: true }); }
  })) });
  await fails(streamed.verify({ accessToken: token }), 504, 'qq_timeout');
});
