import test from 'node:test';
import assert from 'node:assert/strict';
import { adminPreviewUrl, communityAssetUrl } from '../admin/assets.js';

const previewId = 'preview-' + 'a'.repeat(64);
const presetId = 'ncp-' + 'b'.repeat(64);

for (const prefix of ['', '/preset-library']) {
  test(`${prefix || 'site-root'}: saved legacy asset links use the current admin origin and deployment path`, () => {
    const asset = { id: previewId, url: `https://legacy.example.test/preset-library/files/${previewId}.png`,
      adminUrl: `https://legacy.example.test/preset-library/api/assets/${previewId}/content` };
    const currentModule = `https://custom.example.test${prefix}/admin/assets.js`;
    assert.equal(adminPreviewUrl(asset, currentModule), `https://custom.example.test${prefix}/api/assets/${previewId}/content`);
    assert.equal(communityAssetUrl(previewId, currentModule), `https://custom.example.test${prefix}/api/community/assets/${previewId}/content`);
    assert.equal(communityAssetUrl(presetId, currentModule), `https://custom.example.test${prefix}/api/community/assets/${presetId}/content`);
    assert.equal(asset.url, `https://legacy.example.test/preset-library/files/${previewId}.png`, 'public asset metadata is unchanged');
    assert.equal(asset.adminUrl, `https://legacy.example.test/preset-library/api/assets/${previewId}/content`);
  });
}

test('stored URLs never choose the private preview destination, even when malicious or malformed', () => {
  const currentModule = 'https://custom.example.test/preset-library/admin/assets.js';
  const expected = `https://custom.example.test/preset-library/api/assets/${previewId}/content`;
  for (const url of ['https://attacker.example.test/collect', '//attacker.example.test/collect',
    'javascript:alert(1)', 'data:image/svg+xml,malicious', 'https://user:password@attacker.example.test', '\\attacker\\share', null]) {
    assert.equal(adminPreviewUrl({ id: previewId, url, adminUrl: url }, currentModule), expected);
  }
  const asset = { id: previewId, get url() { throw new Error('Stored URLs must not be consulted'); },
    get adminUrl() { throw new Error('Stored URLs must not be consulted'); } };
  assert.equal(adminPreviewUrl(asset, currentModule), expected);
});

test('invalid asset IDs cannot fall back to external URLs or escape either private API route', () => {
  const currentModule = 'https://custom.example.test/preset-library/admin/assets.js';
  for (const id of [undefined, null, 1, {}, [previewId], '', previewId.toUpperCase(), 'preview-short',
    previewId + '/../../auth/logout', '../' + previewId, previewId + '?external=1', previewId + '#fragment',
    previewId + '%2fcontent', 'https://attacker.example.test/' + previewId]) {
    assert.equal(adminPreviewUrl({ id, url: 'https://attacker.example.test', adminUrl: 'https://attacker.example.test' }, currentModule), '');
    assert.equal(communityAssetUrl(id, currentModule), '');
  }
  assert.equal(adminPreviewUrl(null, currentModule), '');
  assert.equal(adminPreviewUrl(undefined, currentModule), '');
});

test('preview URLs permit local HTTP review and reject malformed or credential-bearing module bases', () => {
  assert.equal(adminPreviewUrl({ id: previewId }, 'http://localhost:9000/admin/assets.js'),
    `http://localhost:9000/api/assets/${previewId}/content`);
  for (const base of ['not-a-url', 'file:///tmp/admin/assets.js', 'ftp://custom.example.test/admin/assets.js',
    'https://user:secret@custom.example.test/admin/assets.js']) {
    assert.equal(adminPreviewUrl({ id: previewId }, base), '');
    assert.equal(communityAssetUrl(previewId, base), '');
  }
});
