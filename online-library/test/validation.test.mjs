import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { ValidationError, validateNcp, validatePreview, validatePresetInput, buildCatalog } from '../server/validation.mjs';

// Encoded 1x1 solid-color fixtures, generated locally; no real photograph or external service.
const IMAGES = {
  png: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGOQ8KgAAAFUANnsm43hAAAAAElFTkSuQmCC',
  jpg: '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDz6iiivcPGP//Z',
  gif: 'R0lGODdhAQABAIEAABhIeAAAAAAAAAAAACwAAAAAAQABAAAIBAABBAQAOw==',
  webp: 'UklGRjYAAABXRUJQVlA4ICoAAACwAQCdASoBAAEAAUAmJZgCdLoABDAAAP725gf+Ic6HOhzMZ/3HYG67xAA=',
};
const IMAGE_BUFFERS = Object.fromEntries(Object.entries(IMAGES).map(([type, value]) => [type, Buffer.from(value, 'base64')]));
const HASH = 'a'.repeat(64);
const digest = value => createHash('sha256').update(value).digest('hex');
function rejects(action, code) {
  assert.throws(action, error => error instanceof ValidationError && error.status === 400
    && typeof error.code === 'string' && (!code || error.code === code) && /[\u3400-\u9fff]/u.test(error.message));
}
function record(id, body = Buffer.alloc(0)) {
  const header = Buffer.alloc(8); header.writeUInt32BE(id); header.writeUInt32BE(body.length, 4);
  return Buffer.concat([header, body]);
}
function ncp(...extra) {
  const preset = Buffer.alloc(36); preset.write('0100');
  return Buffer.concat([Buffer.from([78, 67, 80, 0]), record(1, preset), ...extra, Buffer.alloc(4)]);
}
function snapshot(id = 'portrait', changes = {}) {
  return { id, status: 'published', draft: { name: 'SECRET DRAFT', file: { url: 'cloud://secret' } }, published: {
    name: ' 人像 ', description: '柔和曲线', compatibleModels: [' Z 6II '], sortOrder: 0,
    file: { url: `https://assets.example.com/${id}.ncp`, sha256: HASH, byteLength: 52, ncpVersion: '0100' }, ...changes,
  } };
}
// Fixture construction follows PNG's wire format; mutations re-sign their chunk so semantic checks are exercised.
function pngChunk(type, body) {
  const framed = Buffer.alloc(body.length + 12); framed.writeUInt32BE(body.length); framed.write(type, 4); body.copy(framed, 8);
  let sum = -1;
  for (const byte of framed.subarray(4, framed.length - 4)) {
    sum ^= byte;
    for (let i = 0; i < 8; i++) sum = (sum >>> 1) ^ ((sum & 1) ? 0xedb88320 : 0);
  }
  framed.writeUInt32BE((sum ^ -1) >>> 0, framed.length - 4); return framed;
}
function resizedPng(width, height) {
  const original = IMAGE_BUFFERS.png, header = Buffer.from(original.subarray(16, 29));
  header.writeUInt32BE(width); header.writeUInt32BE(height, 4);
  return Buffer.concat([original.subarray(0, 8), pngChunk('IHDR', header), original.subarray(33)]);
}
function riff(type, body) {
  const head = Buffer.alloc(8); head.write(type); head.writeUInt32LE(body.length, 4);
  return Buffer.concat([head, body, Buffer.alloc(body.length & 1)]);
}
function webp(...chunks) {
  const payload = Buffer.concat([Buffer.from('WEBP'), ...chunks]), header = Buffer.alloc(8);
  header.write('RIFF'); header.writeUInt32LE(payload.length, 4); return Buffer.concat([header, payload]);
}

test('NCP envelope agrees with supported 0100 structure and hashes the exact uploaded bytes', () => {
  const file = ncp(record(91, Buffer.from([1, 2, 3])), record(92));
  assert.deepEqual(validateNcp(file), { sha256: digest(file), byteLength: file.length, ncpVersion: '0100' });
  assert.deepEqual(validateNcp(new Uint8Array(file)), validateNcp(file));
  const maximum = ncp(record(2, Buffer.alloc(1024 * 1024 - 60)));
  assert.equal(maximum.length, 1024 * 1024); assert.equal(validateNcp(maximum).byteLength, maximum.length);
});

test('unknown NCP signatures and versions never default to a supported preset', () => {
  for (const value of [null, '', Buffer.alloc(0), Buffer.from('NCP'), Buffer.from('NCPX0000'), Buffer.from('<html>NCP</html>'), Buffer.alloc(1024 * 1024 + 1)]) rejects(() => validateNcp(value));
  const other = ncp(); other.write('0200', 12); rejects(() => validateNcp(other), 'unsupported_ncp_version');
  other[12] = 0xb0; rejects(() => validateNcp(other), 'unsupported_ncp_version');
});

test('NCP record truncation, duplicate preset, lengths, missing terminator and trailing bytes are rejected', () => {
  const good = ncp(), shortPreset = Buffer.alloc(35); shortPreset.write('0100');
  const invalid = [good.subarray(0, 5), good.subarray(0, 10), good.subarray(0, good.length - 1),
    Buffer.concat([good, Buffer.from([0])]), Buffer.from([78, 67, 80, 0, 0, 0, 0, 0]),
    ncp(record(1, Buffer.alloc(36))), Buffer.concat([good.subarray(0, 4), record(1, shortPreset), Buffer.alloc(4)])];
  const badLength = Buffer.from(good); badLength.writeUInt32BE(0xffffffff, 8); invalid.push(badLength);
  for (const value of invalid) rejects(() => validateNcp(value), 'invalid_ncp');
});

for (const [extension, file] of Object.entries(IMAGE_BUFFERS)) {
  test(`complete synthetic ${extension} preview is recognized by its bytes and yields immutable metadata`, () => {
    const mimeType = `image/${extension === 'jpg' ? 'jpeg' : extension}`;
    const before = Buffer.from(file);
    assert.deepEqual(validatePreview(file, `${mimeType}; charset=binary`), { sha256: digest(file), byteLength: file.length, mimeType, extension });
    assert.deepEqual(file, before);
    rejects(() => validatePreview(file, 'text/html'), 'invalid_preview');
    rejects(() => validatePreview(file.subarray(0, file.length - 1)), 'invalid_preview');
    rejects(() => validatePreview(Buffer.concat([file, Buffer.from('trailing') ])), 'invalid_preview');
  });
}

test('preview size, body type, forged magic and dimension abuse fail before publication', () => {
  for (const value of [null, '', Buffer.alloc(0), Buffer.alloc(8 * 1024 * 1024 + 1), Buffer.from('<svg/>'), Buffer.from([255, 216, 255, 217]),
    Buffer.from('GIF89a<html>;'), Buffer.from('RIFF0000WEBP'), Buffer.concat([IMAGE_BUFFERS.png.subarray(0, 8), Buffer.from('<html>')])]) rejects(() => validatePreview(value));
  for (const [w, h] of [[0, 1], [16385, 1], [8001, 8000], [65535, 65535]]) rejects(() => validatePreview(resizedPng(w, h)), 'invalid_preview');
  const jpeg = Buffer.from(IMAGE_BUFFERS.jpg), sof = jpeg.indexOf(Buffer.from([255, 192]));
  jpeg.writeUInt16BE(16385, sof + 7); rejects(() => validatePreview(jpeg));
  const gif = Buffer.from(IMAGE_BUFFERS.gif); gif.writeUInt16LE(0, 6); rejects(() => validatePreview(gif));
});

test('the 8 MiB preview boundary is exact and does not substitute the smaller HTTP transport limit', () => {
  const original = IMAGE_BUFFERS.jpg, segments = []; let remaining = 8 * 1024 * 1024 - original.length;
  while (remaining) {
    let size = Math.min(remaining, 65537);
    if (remaining - size > 0 && remaining - size < 4) size -= 4;
    const segment = Buffer.alloc(size); segment[0] = 255; segment[1] = 226; segment.writeUInt16BE(size - 2, 2);
    segments.push(segment); remaining -= size;
  }
  const file = Buffer.concat([original.subarray(0, 2), ...segments, original.subarray(2)]);
  assert.equal(validatePreview(file).byteLength, 8 * 1024 * 1024);
  rejects(() => validatePreview(Buffer.concat([file, Buffer.from([0])])));
});

test('PNG integrity includes CRC, chunk ordering, palette requirements and exact IEND', () => {
  const file = IMAGE_BUFFERS.png, corrupt = Buffer.from(file); corrupt[45] ^= 1; rejects(() => validatePreview(corrupt));
  const header = Buffer.from(file.subarray(16, 29)); header[9] = 3;
  rejects(() => validatePreview(Buffer.concat([file.subarray(0, 8), pngChunk('IHDR', header), file.subarray(33)])));
  rejects(() => validatePreview(Buffer.concat([file.subarray(0, 8), pngChunk('ABCD', Buffer.alloc(0)), file.subarray(8)])));
  rejects(() => validatePreview(Buffer.concat([file.subarray(0, -12), pngChunk('IEND', Buffer.from([0]))])));
  rejects(() => validatePreview(Buffer.concat([file.subarray(0, 33), pngChunk('ABCD', Buffer.alloc(0)), file.subarray(33)])));
  const malformed = Buffer.from(file); malformed.writeUInt32BE(0xffffffff, 33); rejects(() => validatePreview(malformed));
});

test('JPEG needs bounded frame/scans rather than a signature around arbitrary data', () => {
  const file = Buffer.from(IMAGE_BUFFERS.jpg), sof = file.indexOf(Buffer.from([255, 192])), scan = file.indexOf(Buffer.from([255, 218]));
  const noScan = Buffer.concat([file.subarray(0, scan), Buffer.from([255, 217])]); rejects(() => validatePreview(noScan));
  const length = Buffer.from(file); length.writeUInt16BE(1, sof + 2); rejects(() => validatePreview(length));
  const invalidComponents = Buffer.from(file); invalidComponents[sof + 9] = 7; rejects(() => validatePreview(invalidComponents));
  rejects(() => validatePreview(Buffer.from([255, 216, 0, 1, 2, 3, 255, 217])));
});

test('GIF requires complete color tables and pixel-counted LZW frames', () => {
  const file = IMAGE_BUFFERS.gif, frame = file.indexOf(0x2c, 13);
  const noImage = Buffer.concat([file.subarray(0, frame), Buffer.from([0x3b])]); rejects(() => validatePreview(noImage));
  const tooWide = Buffer.from(file); tooWide.writeUInt16LE(2, frame + 5); rejects(() => validatePreview(tooWide));
  const wrongMinimum = Buffer.from(file); wrongMinimum[frame + 10] = 1; rejects(() => validatePreview(wrongMinimum));
  const corrupt = Buffer.from(file); corrupt[frame + 12] = 0xff; rejects(() => validatePreview(corrupt));
});

test('WebP container lengths, extension canvas and animation frame dimensions are checked', () => {
  const file = IMAGE_BUFFERS.webp, bitstream = file.subarray(20, 62);
  const canvas = Buffer.alloc(10), extended = webp(riff('VP8X', canvas), riff('VP8 ', bitstream));
  assert.equal(validatePreview(extended).mimeType, 'image/webp');
  const animatedCanvas = Buffer.from(canvas); animatedCanvas[0] = 2;
  const frame = Buffer.alloc(16);
  const animation = webp(riff('VP8X', animatedCanvas), riff('ANIM', Buffer.alloc(6)), riff('ANMF', Buffer.concat([frame, riff('VP8 ', bitstream)])));
  assert.equal(validatePreview(animation).extension, 'webp');
  const badLength = Buffer.from(file); badLength.writeUInt32LE(0xffffffff, 4); rejects(() => validatePreview(badLength));
  canvas[4] = 1; rejects(() => validatePreview(webp(riff('VP8X', canvas), riff('VP8 ', bitstream))));
  frame[6] = 1; rejects(() => validatePreview(webp(riff('VP8X', animatedCanvas), riff('ANIM', Buffer.alloc(6)), riff('ANMF', Buffer.concat([frame, riff('VP8 ', bitstream)])))));
  rejects(() => validatePreview(webp(riff('EXIF', Buffer.from('x')))));
  const lossless = Buffer.from('UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAAAdQpGIUr/+BiOh/AAA=', 'base64');
  assert.equal(validatePreview(lossless).mimeType, 'image/webp');
  const wrongVersion = Buffer.from(lossless); wrongVersion[24] |= 0x20; rejects(() => validatePreview(wrongVersion));
  const noPartition = Buffer.from(file); noPartition.fill(0, 20, 23); rejects(() => validatePreview(noPartition));
});

test('preset metadata is normalized with client-compatible limits and no mass-assigned fields', () => {
  assert.deepEqual(validatePresetInput({ name: ' 人像 ', description: ' 柔和 ', compatibleModels: [' Z 6 ', 'Z 6', 'Z 7'], sortOrder: -5, status: 'published', fileUrl: 'secret' }),
    { name: '人像', description: '柔和', category: '', compatibleModels: ['Z 6', 'Z 7'], sortOrder: -5 });
  assert.deepEqual(validatePresetInput({ name: '预设' }), { name: '预设', description: '', category: '', compatibleModels: [], sortOrder: 0 });
  assert.equal(validatePresetInput({ name: '字'.repeat(160), description: '字'.repeat(2000), compatibleModels: ['字'.repeat(80)], sortOrder: 1_000_000 }).name.length, 160);
  for (const value of [null, [], {}, { name: ' ' }, { name: 1 }, { name: 'x'.repeat(161) }, { name: 'x\n' }, { name: '\ud800' },
    { name: 'x', description: 'x'.repeat(2001) }, { name: 'x', description: 'a\nb' }, { name: 'x', compatibleModels: 'Z 6' },
    { name: 'x', compatibleModels: Array(33).fill('Z 6') }, { name: 'x', compatibleModels: [''] }, { name: 'x', compatibleModels: ['x'.repeat(81)] },
    ...[1.1, '1', Infinity, NaN, null, 1_000_001].map(sortOrder => ({ name: 'x', sortOrder }))]) rejects(() => validatePresetInput(value), 'invalid_preset');
});

test('catalog uses published snapshots only, strips administration data, and sorts deterministically', () => {
  const first = snapshot('b', { sortOrder: -1 }), second = snapshot('a', { sortOrder: -1 }), last = snapshot('c');
  const preview = validatePreview(IMAGE_BUFFERS.png);
  second.published.preview = { ...preview, url: 'https://assets.example.com/sample.png' };
  second.published.file.sha256 = HASH.toUpperCase();
  const catalog = buildCatalog([last, first, { id: 'draft', status: 'draft', published: { name: 'DO NOT EXPOSE' } }, second]);
  assert.deepEqual(catalog.presets.map(item => item.id), ['a', 'b', 'c']); assert.equal(catalog.version, 1);
  assert.deepEqual(catalog.presets[0], { id: 'a', name: '人像', description: '柔和曲线', category: '', fileUrl: 'https://assets.example.com/a.ncp', sha256: HASH,
    byteLength: 52, fileFormat: 'NCP', previewUrl: 'https://assets.example.com/sample.png', compatibleModels: ['Z 6II'] });
  assert.equal(catalog.presets[1].previewUrl, '');
  assert.ok(!JSON.stringify(catalog).includes('SECRET')); assert.ok(!JSON.stringify(catalog).includes('DO NOT EXPOSE'));
  assert.ok(!JSON.stringify(catalog).includes('sortOrder')); assert.deepEqual(buildCatalog([]), { version: 1, presets: [] });
  catalog.presets[0].compatibleModels.push('changed'); assert.deepEqual(second.published.compatibleModels, [' Z 6II ']);
});

test('publishing checks duplicate IDs, count and actual UTF-8 manifest byte limit', () => {
  rejects(() => buildCatalog([snapshot('same'), snapshot('same')]), 'invalid_catalog');
  assert.equal(buildCatalog(Array.from({ length: 256 }, (_, i) => snapshot(`p${i}`))).presets.length, 256);
  rejects(() => buildCatalog(Array.from({ length: 257 }, (_, i) => snapshot(`p${i}`))), 'invalid_catalog');
  rejects(() => buildCatalog(Array.from({ length: 100 }, (_, i) => snapshot(`p${i}`, { description: '字'.repeat(2000) }))), 'invalid_catalog');
  rejects(() => buildCatalog([null])); rejects(() => buildCatalog({})); rejects(() => buildCatalog([{ id: 'x', status: 'published' }]));
});

test('catalog rejects unpublished/unsupported file metadata and preview mismatches', () => {
  for (const file of [null, {}, { url: 'https://a.example/p.ncp', sha256: 'bad', byteLength: 52, ncpVersion: '0100' },
    ...[7, 1_048_577, 52.5, '52'].map(byteLength => ({ url: 'https://a.example/p.ncp', sha256: HASH, byteLength, ncpVersion: '0100' })),
    { url: 'https://a.example/p.ncp', sha256: HASH, byteLength: 52, ncpVersion: '0200' }]) rejects(() => buildCatalog([snapshot('x', { file })]));
  for (const preview of [{ url: 'https://a.example/a.png' }, { url: 'https://a.example/a.png', sha256: HASH, byteLength: 20, mimeType: 'image/jpeg', extension: 'png' },
    { url: 'https://a.example/a.png', sha256: HASH, byteLength: 8_388_609, mimeType: 'image/png', extension: 'png' }]) rejects(() => buildCatalog([snapshot('x', { preview })]));
});

test('catalog requires stable credential-free HTTPS URLs the Android parser can read', () => {
  for (const url of ['cloud://bucket/p.ncp', 'http://assets.example/p.ncp', 'https:assets.example/p.ncp', '//assets.example/p.ncp',
    'https://user:pass@assets.example/p.ncp', 'https://@assets.example/p.ncp', 'https://assets.example/p.ncp?sign=secret', 'https://assets.example/p.ncp?',
    'https://assets.example/p.ncp#secret', 'https://assets.example/p.ncp#', 'https://assets.example:0/p.ncp', 'https://assets.example:65536/p.ncp',
    'https://assets.example/a b.ncp', 'https://assets.example/%zz', 'https://例子.测试/p.ncp', 'https://-invalid.example/p.ncp',
    'https://assets.example\\@evil.example/p.ncp', `https://assets.example/${'a'.repeat(4096)}`]) {
    const value = snapshot(); value.published.file.url = url; rejects(() => buildCatalog([value]), 'invalid_catalog');
  }
  const value = snapshot(); value.published.file.url = 'https://assets.example:8443/portrait%20one.ncp';
  assert.equal(buildCatalog([value]).presets[0].fileUrl, value.published.file.url);
});
