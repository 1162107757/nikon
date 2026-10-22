import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { SourceTextModule, createContext } from 'node:vm';

const source = await readFile(new URL('../download/download.js', import.meta.url), 'utf8');
const legacyOrigin = 'https://nikon-d8girdjsr252fd90e-1252602548.ap-shanghai.app.tcloudbase.com';
const id = '12345678-1234-4234-8234-123456789abc';
function release(overrides = {}) {
  return { id, downloadUrl: `${legacyOrigin}/preset-library/releases/${id}/file`, sha256: 'a'.repeat(64),
    signerSha256: 'b'.repeat(64), byteLength: 3400000, versionCode: 74, versionName: 'fixture',
    minSdk: 26, publishedAt: '2026-10-21T00:00:00.000Z', notes: 'Synthetic release',
    packageName: 'com.nikonpreset.usbprobe', ...overrides };
}
async function fixture(moduleUrl, selectedRelease = release()) {
  const nodes = new Map(), calls = [];
  const byId = id => {
    if (!nodes.has(id)) nodes.set(id, { hidden: false, textContent: '', href: '', addEventListener() {}, replaceChildren() {} });
    return nodes.get(id);
  };
  const location = new URL('./?channel=beta', moduleUrl);
  const context = createContext({ URL, location, Intl, document: { getElementById: byId, querySelectorAll: () => [] },
    history: { replaceState() {} }, navigator: { clipboard: { writeText: async () => {} } },
    fetch: async (url, options) => { calls.push({ url: String(url), options }); return { ok: true,
      json: async () => ({ release: selectedRelease, reason: selectedRelease ? 'available' : 'no_release' }) }; } });
  const module = new SourceTextModule(source, { context, initializeImportMeta(meta) { meta.url = moduleUrl; } });
  await module.link(() => { throw new Error('The download page must not import external modules'); });
  await module.evaluate();
  await new Promise(resolve => setImmediate(resolve));
  return { helper: module.namespace.releaseDownloadUrl, nodes, calls };
}

for (const origin of [legacyOrigin, 'https://custom.example.test']) {
  test(`${origin}: real download page keeps legacy metadata and renders a current-origin APK link`, async () => {
    const f = await fixture(origin + '/preset-library/download/download.js');
    assert.equal(f.nodes.get('release').hidden, false);
    assert.equal(f.nodes.get('empty').hidden, true);
    assert.equal(f.nodes.get('download-button').href, `${origin}/preset-library/releases/${id}/file`);
    assert.equal(f.calls[0].url, origin + '/preset-library/releases/latest?channel=beta&packageName=cc.huevia');
    assert.equal(f.calls[0].options.credentials, 'omit');
    assert.equal(f.nodes.get('sha256').textContent, 'a'.repeat(64));
    assert.equal(f.nodes.get('signer').textContent, 'b'.repeat(64));
  });
}

test('download routes support root deployment and canonical URLs already using the custom domain', async () => {
  const f = await fixture('https://custom.example.test/download/download.js');
  assert.equal(f.nodes.get('download-button').href, `https://custom.example.test/releases/${id}/file`);
  const base = 'https://custom.example.test/preset-library/';
  const expected = `${base}releases/${id}/file`;
  assert.equal(f.helper(release({ downloadUrl: expected }), base).href, expected);
});

test('download page rejects arbitrary external links, mismatched paths, credentials and invalid release IDs', async () => {
  const f = await fixture('https://custom.example.test/preset-library/download/download.js');
  const base = 'https://custom.example.test/preset-library/';
  const valid = release();
  for (const downloadUrl of [`https://attacker.example.test/preset-library/releases/${id}/file`,
    `https://custom.example.test/preset-library/releases/${id}/file/extra`,
    `${legacyOrigin}/preset-library/releases/12345678-1234-4234-8234-123456789def/file`,
    `${legacyOrigin}/preset-library/releases/${id}/file?token=forged`,
    `${legacyOrigin}/preset-library/releases/${id}/file?`,
    `${legacyOrigin}/preset-library/releases/${id}/file#fragment`,
    `${legacyOrigin}/preset-library/releases/${id}/file#`,
    `${legacyOrigin.replace('https://', 'https://user:secret@')}/preset-library/releases/${id}/file`,
    `http://${new URL(legacyOrigin).host}/preset-library/releases/${id}/file`,
    `/preset-library/releases/${id}/file`, 'javascript:alert(1)', 'https://attacker.example.test/collect']) {
    assert.throws(() => f.helper({ ...valid, downloadUrl }, base), /下载地址校验失败/);
  }
  for (const invalidId of [null, 1, [id], id.toUpperCase(), 'not-a-uuid', id + '/../../auth/logout', id + '?token=1']) {
    assert.throws(() => f.helper({ ...valid, id: invalidId }, base), /下载地址校验失败/);
  }
});

test('download page retains bounded byte lengths, versions and SHA checks and hides failed releases', async () => {
  const f = await fixture('https://custom.example.test/preset-library/download/download.js');
  for (const invalid of [{ sha256: 'bad' }, { signerSha256: 'bad' }, { byteLength: 0 }, { byteLength: -1 },
    { byteLength: 4 * 1024 * 1024 + 1 }, { byteLength: '3400000' }, { versionCode: -1 }, { versionCode: 1.5 }]) {
    assert.throws(() => f.helper(release(invalid), 'https://custom.example.test/preset-library/'), /版本信息不完整/);
  }
  const invalidPage = await fixture('https://custom.example.test/preset-library/download/download.js',
    release({ downloadUrl: 'https://attacker.example.test/collect' }));
  assert.equal(invalidPage.nodes.get('release').hidden, true);
  assert.equal(invalidPage.nodes.get('empty').hidden, false);
  assert.match(invalidPage.nodes.get('empty-copy').textContent, /下载地址校验失败/);
  assert.equal(invalidPage.nodes.get('download-button')?.href || '', '');
});
