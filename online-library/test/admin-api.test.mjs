import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { SourceTextModule, createContext } from 'node:vm';
import { File } from 'node:buffer';

// Runs the actual browser adapter in a VM. No DOM, browser, socket, or cloud call.
// Command: node --experimental-vm-modules --test test/admin-api.test.mjs
const source = await readFile(new URL('../admin/api.js', import.meta.url), 'utf8');

async function fixture(prefix) {
  const calls = [], replies = [];
  const defaultSession = { authenticated: true, user: { username: 'synthetic-qa' }, csrfToken: 'csrf-first', catalogUrl: `http://localhost${prefix}/catalog.json`, environmentId: 'local-only' };
  const context = createContext({ URL, File, Error, fetch: async (path, options) => {
    calls.push({ path, options });
    const response = replies.shift() || { data: defaultSession };
    const status = response.status || 200;
    return { ok: status >= 200 && status < 300, status, json: async () => {
      if (response.invalidJson) throw new SyntaxError('synthetic non-JSON response');
      return response.data;
    } };
  } });
  const module = new SourceTextModule(source, { context, initializeImportMeta(meta) { meta.url = `http://localhost${prefix}/admin/api.js`; } });
  await module.link(() => { throw new Error('The adapter must not load external modules'); });
  await module.evaluate();
  return { api: module.namespace.api, calls, reply: (data, status = 200) => replies.push({ data, status }), invalidJson: status => replies.push({ invalidJson: true, status }), defaultSession };
}

for (const prefix of ['/preset-library', '']) {
  const deployment = prefix || 'site-root';
  test(`${deployment}: changing password uses CSRF, preserves exact text and clears authority only on success`, async () => {
    const f = await fixture(prefix); await f.api.session();
    const current = '  current password  ', next = '  my own new password  ';
    f.reply({ error: { code: 'CURRENT_PASSWORD_INCORRECT', message: '当前密码不正确' } }, 400);
    await assert.rejects(f.api.changePassword(current, next, next), error => error.code === 'CURRENT_PASSWORD_INCORRECT');
    assert.equal(f.calls.at(-1).options.headers['X-CSRF-Token'], 'csrf-first');
    f.reply({ ok: true }); await f.api.changePassword(current, next, next);
    const call = f.calls.at(-1);
    assert.equal(call.path, `${prefix}/api/password`); assert.equal(call.options.method, 'POST');
    assert.deepEqual(JSON.parse(call.options.body), { currentPassword: current, newPassword: next, confirmPassword: next });
    const before = f.calls.length;
    await assert.rejects(f.api.publish('id', 1), error => error.code === 'SESSION_REQUIRED');
    assert.equal(f.calls.length, before);
  });
  test(`${deployment}: session/login use the deployment route and login bypasses CSRF`, async () => {
    const f = await fixture(prefix);
    await f.api.session();
    assert.equal(f.calls.at(-1).path, `${prefix}/api/session`);
    assert.equal(f.calls.at(-1).options.cache, 'no-store');
    await f.api.login('synthetic-qa', 'only-a-test-password');
    const call = f.calls.at(-1);
    assert.equal(call.path, `${prefix}/api/login`);
    assert.equal(call.options.method, 'POST');
    assert.equal(call.options.credentials, 'same-origin');
    assert.equal(call.options.headers['X-CSRF-Token'], undefined);
    assert.deepEqual(JSON.parse(call.options.body), { username: 'synthetic-qa', password: 'only-a-test-password' });
    f.reply({ configured: false });
    assert.equal((await f.api.health()).configured, false);
    assert.equal(f.calls.at(-1).path, `${prefix}/health`);
  });

  test(`${deployment}: replacing draft assets preserves revision and uses the reauthenticated CSRF`, async () => {
    const f = await fixture(prefix);
    await f.api.login('synthetic-qa', 'first-test-password');
    const draft = { name: 'Synthetic tone', description: 'local only', category: '胶片', compatibleModels: [], sortOrder: -1, fileAssetId: 'ncp-new', previewAssetId: 'preview-new', expectedRevision: 7 };
    await f.api.updatePreset('id/with-space', draft);
    let call = f.calls.at(-1);
    assert.equal(call.path, `${prefix}/api/presets/id%2Fwith-space`);
    assert.equal(call.options.method, 'PUT');
    assert.equal(call.options.headers['X-CSRF-Token'], 'csrf-first');
    assert.deepEqual(JSON.parse(call.options.body), draft);
    f.reply({ ...f.defaultSession, csrfToken: 'csrf-after-login' });
    await f.api.login('synthetic-qa', 'second-test-password');
    await f.api.updatePreset('id', { ...draft, expectedRevision: 8 });
    call = f.calls.at(-1);
    assert.equal(call.options.headers['X-CSRF-Token'], 'csrf-after-login');
    assert.equal(JSON.parse(call.options.body).expectedRevision, 8);
    await f.api.publish('id', 8);
    assert.equal(f.calls.at(-1).path, `${prefix}/api/presets/id/publish`);
    assert.deepEqual(JSON.parse(f.calls.at(-1).options.body), { expectedRevision: 8 });
    await f.api.unpublish('id', 8);
    assert.equal(f.calls.at(-1).path, `${prefix}/api/presets/id/unpublish`);
  });

  test(`${deployment}: uploads use raw files and preserve private preview metadata`, async () => {
    const f = await fixture(prefix);
    await f.api.session();
    const ncp = new File(['synthetic bytes, not a real NCP'], '色彩 preset.NCP');
    f.reply({ asset: { id: 'ncp-synthetic' } });
    assert.equal((await f.api.upload('ncp', ncp)).asset.id, 'ncp-synthetic');
    let call = f.calls.at(-1);
    assert.equal(call.path, `${prefix}/api/uploads?kind=ncp&filename=${encodeURIComponent(ncp.name)}`);
    assert.equal(call.options.body, ncp);
    assert.equal(call.options.headers['Content-Type'], 'application/octet-stream');
    assert.equal(call.options.headers['X-CSRF-Token'], 'csrf-first');
    const preview = new File(['synthetic bytes, not a decoded image'], 'preview.png', { type: 'image/png' });
    const asset = { id: 'preview-synthetic', adminUrl: `${prefix}/api/assets/preview-synthetic`, url: `https://public.invalid${prefix}/files/preview-synthetic.png` };
    f.reply({ asset });
    const result = await f.api.upload('preview', preview);
    call = f.calls.at(-1);
    assert.equal(call.options.body, preview);
    assert.equal(call.options.headers['Content-Type'], 'image/png');
    assert.equal(result.asset.adminUrl, asset.adminUrl);
    assert.equal(result.asset.url, asset.url);
  });

  test(`${deployment}: file bounds reject before network and the exact preview bound is allowed`, async () => {
    const f = await fixture(prefix);
    await f.api.session(); const before = f.calls.length;
    for (const [kind, file] of [
      ['unknown', new File(['x'], 'x')],
      ['ncp', new File([], 'empty.NCP')],
      ['ncp', new File([new Uint8Array(1024 * 1024 + 1)], 'large.NCP')],
      ['preview', new File([new Uint8Array(4 * 1024 * 1024 + 1)], 'large.png', { type: 'image/png' })],
    ]) assert.throws(() => f.api.upload(kind, file), error => error.code === 'INVALID_FILE');
    assert.equal(f.calls.length, before);
    const file = new File([new Uint8Array(4 * 1024 * 1024)], 'bound.png', { type: 'image/png' });
    await f.api.upload('preview', file);
    assert.equal(f.calls.at(-1).options.body, file);
  });

  test(`${deployment}: logout drops mutation authority and backend/config failures stay explicit`, async () => {
    const f = await fixture(prefix);
    await f.api.session(); await f.api.logout();
    assert.equal(f.calls.at(-1).path, `${prefix}/api/logout`);
    assert.equal(f.calls.at(-1).options.headers['X-CSRF-Token'], 'csrf-first');
    const before = f.calls.length;
    await assert.rejects(f.api.publish('id', 1), error => error.code === 'SESSION_REQUIRED' && error.status === 401);
    assert.equal(f.calls.length, before);
    f.reply({ error: { code: 'LOGIN_REQUIRED', message: '登录已失效' } }, 401);
    await assert.rejects(f.api.presets(), error => error.code === 'LOGIN_REQUIRED' && error.status === 401);
    f.invalidJson(404);
    await assert.rejects(f.api.session(), error => error.code === 'NOT_CONFIGURED');
    f.reply({ error: { code: 'NOT_CONFIGURED', message: '后台尚未配置' } }, 503);
    await assert.rejects(f.api.session(), error => error.code === 'NOT_CONFIGURED' && error.message === '后台尚未配置');
  });

  test(`${deployment}: users preserve query boundaries, cancellation and status concurrency`, async () => {
    const f = await fixture(prefix);
    await f.api.session();
    const signal = { localSyntheticSignal: true };
    f.reply({ items: [], total: 0, page: 2, pageSize: 20 });
    await f.api.users({ q: '昵称 & ? # /', status: 'disabled', page: 2, pageSize: 20 }, { signal });
    let call = f.calls.at(-1);
    assert.equal(call.path, `${prefix}/api/users?q=${encodeURIComponent('昵称 & ? # /')}&status=disabled&page=2&pageSize=20`);
    assert.equal(call.options.signal, signal);
    assert.equal(call.options.method, 'GET');
    assert.equal(call.options.headers['X-CSRF-Token'], undefined);
    f.reply({ user: { id: 'id/with?query', status: 'active' } });
    await f.api.user('id/with?query', { signal });
    call = f.calls.at(-1);
    assert.equal(call.path, `${prefix}/api/users/id%2Fwith%3Fquery`);
    assert.equal(call.options.signal, signal);
    const expectedUpdatedAt = '2026-10-18T01:00:00.000Z';
    f.reply({ user: { id: 'user-id', status: 'disabled' } });
    await f.api.setUserStatus('user-id', 'disabled', expectedUpdatedAt);
    call = f.calls.at(-1);
    assert.equal(call.path, `${prefix}/api/users/user-id/status`);
    assert.equal(call.options.method, 'POST');
    assert.equal(call.options.headers['X-CSRF-Token'], 'csrf-first');
    assert.deepEqual(JSON.parse(call.options.body), { status: 'disabled', expectedUpdatedAt });
    f.reply({ error: { code: 'USER_CONFLICT', message: '用户资料已更新' } }, 409);
    await assert.rejects(f.api.setUserStatus('user-id', 'active', expectedUpdatedAt), error => error.status === 409 && error.code === 'USER_CONFLICT');
  });
}
