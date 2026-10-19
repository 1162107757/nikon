import { createHash, verify, X509Certificate, constants } from 'node:crypto';
import { inflateRawSync } from 'node:zlib';
import { ServiceError } from './cloudbase.mjs';

export const MAX_APK_BYTES = 4 * 1024 * 1024;
export const APK_PACKAGE = 'com.nikonpreset.usbprobe';
export const APK_SIGNER_SHA256 = '04db0f9d3d76c9831a0778cfabc1a0aaab984e05398da16eb644950ccfa0ae31';
const ANDROID_NS = 'http://schemas.android.com/apk/res/android';
const fail = (message = 'APK 结构或签名校验未通过，请上传完整的已签名安装包。') => { throw new ServiceError(400, 'INVALID_APK', message); };
const hash = (data, algorithm = 'sha256') => createHash(algorithm).update(data).digest();
function u32(buffer, offset) { if (offset < 0 || offset + 4 > buffer.length) fail(); return buffer.readUInt32LE(offset); }
function u64(buffer, offset) { if (offset < 0 || offset + 8 > buffer.length) fail(); const value = buffer.readBigUInt64LE(offset); if (value > BigInt(MAX_APK_BYTES)) fail(); return Number(value); }
class Reader {
  constructor(data) { this.data = data; this.offset = 0; }
  uint() { const value = u32(this.data, this.offset); this.offset += 4; return value; }
  bytes() { const size = this.uint(); if (size > this.data.length - this.offset) fail(); const value = this.data.subarray(this.offset, this.offset + size); this.offset += size; return value; }
  done() { if (this.offset !== this.data.length) fail(); }
  list() { const result = []; while (this.offset < this.data.length) { if (result.length >= 64) fail(); result.push(this.bytes()); } return result; }
}
function records(data) { return new Reader(data).list(); }
const algorithms = new Map([
  [0x0101, ['sha256', constants.RSA_PKCS1_PSS_PADDING, 32]], [0x0102, ['sha512', constants.RSA_PKCS1_PSS_PADDING, 64]],
  [0x0103, ['sha256', constants.RSA_PKCS1_PADDING]], [0x0104, ['sha512', constants.RSA_PKCS1_PADDING]],
  [0x0201, ['sha256']], [0x0202, ['sha512']], [0x0301, ['sha256']]
]);
function contentDigest(apk, start, central, eocd, algorithm) {
  const tail = Buffer.from(apk.subarray(eocd)); tail.writeUInt32LE(start, 16);
  const chunks = [];
  for (const section of [apk.subarray(0, start), apk.subarray(central, eocd), tail]) {
    for (let offset = 0; offset < section.length; offset += 1024 * 1024) {
      const chunk = section.subarray(offset, offset + 1024 * 1024), header = Buffer.alloc(5);
      header[0] = 0xa5; header.writeUInt32LE(chunk.length, 1); chunks.push(hash(Buffer.concat([header, chunk]), algorithm));
    }
  }
  const header = Buffer.alloc(5); header[0] = 0x5a; header.writeUInt32LE(chunks.length, 1);
  return hash(Buffer.concat([header, ...chunks]), algorithm);
}
function signatures(apk, blocks, geometry) {
  if (!blocks.has(0x7109871a)) fail('仅接受包含 APK v2 完整签名的安装包。');
  // Rotation and SDK-targeted v3.1+ need a separately reviewed trust policy. Never silently fall back.
  if (blocks.has(0x1b93ad61)) fail('暂不支持签名轮换或 APK v3.1 签名，请沿用当前签名。');
  // The signing-block container itself is not protected by v2/v3 content digests. A newly injected
  // higher-scheme/source-stamp block must not change Android's verifier selection behind our back.
  for (const id of blocks.keys()) if (![0x7109871a, 0xf05368c0, 0x42726577].includes(id)) fail('APK 包含暂不支持的签名或附加块。');
  let signerHash = '', strippingRequiresV3 = false;
  const schemes = [];
  for (const [blockId, scheme] of [[0x7109871a, 2], [0xf05368c0, 3]]) {
    if (!blocks.has(blockId)) continue;
    const root = new Reader(blocks.get(blockId)); const signerList = records(root.bytes()); root.done();
    if (signerList.length !== 1) fail('仅接受沿用当前单一签名的安装包。');
    for (const raw of signerList) {
      const reader = new Reader(raw), signed = reader.bytes();
      const outerMin = scheme === 3 ? reader.uint() : 0, outerMax = scheme === 3 ? reader.uint() : 0;
      const sigRecords = records(reader.bytes()), publicKey = reader.bytes(); reader.done();
      if (!sigRecords.length) fail();
      const sigs = sigRecords.map(value => { const r = new Reader(value), id = r.uint(), signature = r.bytes(); r.done(); return { id, signature }; });
      if (new Set(sigs.map(v => v.id)).size !== sigs.length) fail();
      // Verify signed-data before reading metadata from it. Reject unsupported algorithms rather than guessing.
      for (const { id, signature } of sigs) {
        const config = algorithms.get(id); if (!config) fail('APK 使用了暂不支持的签名算法。');
        const [algorithm, padding, saltLength] = config;
        const options = { key: publicKey, format: 'der', type: 'spki', ...(padding ? { padding } : {}), ...(saltLength ? { saltLength } : {}) };
        if (!verify(algorithm, signed, options, signature)) fail('APK 签名无效，文件可能已被修改。');
      }
      const data = new Reader(signed), digestRecords = records(data.bytes()), certs = records(data.bytes());
      if (!certs.length) fail();
      const cert = new X509Certificate(certs[0]);
      if (!cert.publicKey.export({ format: 'der', type: 'spki' }).equals(publicKey)) fail();
      const currentHash = hash(certs[0]).toString('hex');
      if (signerHash && signerHash !== currentHash) fail('APK v2 / v3 签名不一致。');
      signerHash = currentHash;
      if (scheme === 3) {
        const min = data.uint(), max = data.uint();
        if (min !== outerMin || max !== outerMax || min > max || min > 28 || max !== 0x7fffffff) fail('APK v3 签名的 Android 版本范围不受支持。');
      }
      for (const attribute of records(data.bytes())) {
        const id = u32(attribute, 0);
        if (id === 0x3ba06f8c || id === 0x559f8b02 || id === 0xbf940529 || id === 0x9f06b79c) fail('暂不支持签名轮换，请沿用当前签名。');
        if (scheme === 2 && id === 0xbeeff00d && u32(attribute, 4) === 3) strippingRequiresV3 = true;
      }
      // Current AOSP apksig writes one trailing length-prefixed empty field in v2 signed-data.
      if (scheme === 2 && data.offset < data.data.length && data.bytes().length !== 0) fail();
      data.done();
      const digests = digestRecords.map(value => { const r = new Reader(value), id = r.uint(), digest = r.bytes(); r.done(); return { id, digest }; });
      if (digests.length !== sigs.length || digests.some((v, i) => v.id !== sigs[i].id)) fail();
      for (const { id, digest } of digests) {
        if (!contentDigest(apk, ...geometry, algorithms.get(id)[0]).equals(digest)) fail('APK 内容摘要不一致，文件可能已被修改。');
      }
    }
    schemes.push(scheme);
  }
  if (strippingRequiresV3 && !schemes.includes(3)) fail('APK v3 签名被移除。');
  return { signerSha256: signerHash, signatureSchemes: schemes };
}
function zip(apk) {
  let eocd = -1;
  for (let i = apk.length - 22; i >= Math.max(0, apk.length - 65557); i--) {
    if (u32(apk, i) === 0x06054b50 && i + 22 + apk.readUInt16LE(i + 20) === apk.length) { eocd = i; break; }
  }
  if (eocd < 0 || apk.readUInt16LE(eocd + 4) || apk.readUInt16LE(eocd + 6)) fail();
  const count = apk.readUInt16LE(eocd + 10), central = u32(apk, eocd + 16), centralSize = u32(apk, eocd + 12);
  if (!count || count > 4096 || count !== apk.readUInt16LE(eocd + 8) || central + centralSize !== eocd || central < 32) fail();
  if (apk.subarray(central - 16, central).toString('ascii') !== 'APK Sig Block 42') fail('APK 缺少完整的 v2 / v3 签名。');
  const size = u64(apk, central - 24), start = central - size - 8;
  if (size < 24 || start < 0 || u64(apk, start) !== size) fail();
  const blocks = new Map(); let offset = start + 8;
  while (offset < central - 24) {
    const length = u64(apk, offset); offset += 8;
    if (length < 4 || offset + length > central - 24) fail();
    const id = u32(apk, offset); if (blocks.has(id)) fail();
    blocks.set(id, apk.subarray(offset + 4, offset + length)); offset += length;
  }
  if (offset !== central - 24) fail();
  const entries = new Map(); let totalSize = 0; offset = central;
  const ranges = [];
  for (let i = 0; i < count; i++) {
    if (offset + 46 > eocd || u32(apk, offset) !== 0x02014b50) fail();
    const flags = apk.readUInt16LE(offset + 8), method = apk.readUInt16LE(offset + 10), compressed = u32(apk, offset + 20), expanded = u32(apk, offset + 24);
    const nameLength = apk.readUInt16LE(offset + 28), extra = apk.readUInt16LE(offset + 30), comment = apk.readUInt16LE(offset + 32), local = u32(apk, offset + 42);
    if ((flags & 1) || ![0, 8].includes(method) || offset + 46 + nameLength + extra + comment > eocd || local + 30 > start) fail();
    const nameBytes = apk.subarray(offset + 46, offset + 46 + nameLength), name = new TextDecoder('utf-8', { fatal: true }).decode(nameBytes);
    if (!name || name.length > 1024 || /(^\/|\\|\x00|(?:^|\/)\.\.(?:\/|$))/.test(name) || entries.has(name)) fail();
    if (u32(apk, local) !== 0x04034b50 || apk.readUInt16LE(local + 6) !== flags || apk.readUInt16LE(local + 8) !== method) fail();
    const localNameLength = apk.readUInt16LE(local + 26), localExtra = apk.readUInt16LE(local + 28), dataStart = local + 30 + localNameLength + localExtra;
    if (!apk.subarray(local + 30, local + 30 + localNameLength).equals(nameBytes) || dataStart + compressed > start) fail();
    ranges.push([local, dataStart + compressed]);
    totalSize += expanded; if (totalSize > 128 * 1024 * 1024) fail('APK 解压后的体积过大。');
    entries.set(name, { method, data: apk.subarray(dataStart, dataStart + compressed), expanded });
    offset += 46 + nameLength + extra + comment;
  }
  ranges.sort((a, b) => a[0] - b[0]);
  if (offset !== eocd || ranges.some((v, i) => i > 0 && v[0] < ranges[i - 1][1])) fail();
  const manifest = entries.get('AndroidManifest.xml');
  if (!manifest || manifest.expanded > 1024 * 1024) fail('APK 缺少有效的 AndroidManifest.xml。');
  const data = manifest.method === 8 ? inflateRawSync(manifest.data, { maxOutputLength: 1024 * 1024 }) : manifest.data;
  if (data.length !== manifest.expanded) fail();
  return { manifest: data, signature: signatures(apk, blocks, [start, central, eocd]) };
}
function binaryManifest(data) {
  if (data.length < 8 || data.readUInt16LE(0) !== 3 || data.readUInt16LE(2) !== 8 || u32(data, 4) !== data.length) fail();
  let offset = 8, strings = null, depth = 0, manifestCount = 0, sdkCount = 0;
  const elementStack = [];
  const result = {};
  const stringAt = index => { if (!strings || index >= strings.length) fail(); return strings[index]; };
  while (offset < data.length) {
    if (offset + 8 > data.length) fail();
    const type = data.readUInt16LE(offset), header = data.readUInt16LE(offset + 2), size = u32(data, offset + 4);
    if (header < 8 || size < header || offset + size > data.length) fail();
    const chunk = data.subarray(offset, offset + size);
    if (type === 1) {
      if (strings || header < 28) fail();
      const count = u32(chunk, 8), flags = u32(chunk, 16), start = u32(chunk, 20);
      if (count > 65536 || header + count * 4 > size || start < header + count * 4 || start >= size) fail();
      strings = []; let decodedBytes = 0;
      for (let i = 0; i < count; i++) {
        let cursor = start + u32(chunk, header + i * 4);
        const readLength = utf8 => {
          if (utf8) { if (cursor >= size) fail(); let n = chunk[cursor++]; if (n & 128) { if (cursor >= size) fail(); n = ((n & 127) << 8) | chunk[cursor++]; } return n; }
          if (cursor + 2 > size) fail(); let n = chunk.readUInt16LE(cursor); cursor += 2;
          if (n & 32768) { if (cursor + 2 > size) fail(); n = ((n & 32767) * 65536) + chunk.readUInt16LE(cursor); cursor += 2; } return n;
        };
        const utf8 = Boolean(flags & 256); let length = readLength(utf8); if (utf8) length = readLength(true);
        const bytes = length * (utf8 ? 1 : 2);
        decodedBytes += bytes; if (decodedBytes > 2 * 1024 * 1024) fail('APK 字符串表超过解析预算。');
        if (bytes > 65536 || cursor + bytes + (utf8 ? 1 : 2) > size || chunk[cursor + bytes] !== 0 || (!utf8 && chunk[cursor + bytes + 1] !== 0)) fail();
        strings.push(new TextDecoder(utf8 ? 'utf-8' : 'utf-16le', { fatal: true }).decode(chunk.subarray(cursor, cursor + bytes)));
      }
    } else if (type === 0x0102) {
      if (header !== 16 || size < 36 || ++depth > 64) fail();
      const name = stringAt(u32(chunk, 20)), attributeStart = chunk.readUInt16LE(24) + 16, attributeSize = chunk.readUInt16LE(26), count = chunk.readUInt16LE(28);
      elementStack.push([u32(chunk,16),u32(chunk,20)]);
      if (attributeSize !== 20 || count > 512 || attributeStart < 36 || attributeStart + count * 20 > size) fail();
      const attrs = new Map();
      for (let i = 0; i < count; i++) {
        const at = attributeStart + i * 20, ns = u32(chunk, at), key = `${ns === 0xffffffff ? '' : stringAt(ns)}:${stringAt(u32(chunk, at + 4))}`;
        if (attrs.has(key) || chunk.readUInt16LE(at + 12) !== 8) fail();
        const valueType = chunk[at + 15], value = u32(chunk, at + 16);
        attrs.set(key, valueType === 3 ? stringAt(value) : [0x10, 0x11].includes(valueType) ? value : null);
      }
      if (name === 'manifest') {
        if (depth !== 1 || ++manifestCount !== 1) fail();
        result.packageName = attrs.get(':package'); result.versionName = attrs.get(`${ANDROID_NS}:versionName`);
        if (attrs.has(':split') || attrs.has(`${ANDROID_NS}:isFeatureSplit`)) fail('请上传完整的单一 APK，暂不支持分包。');
        result.versionCode = attrs.get(`${ANDROID_NS}:versionCode`);
        if (attrs.get(`${ANDROID_NS}:versionCodeMajor`)) fail('暂不支持超长 versionCode。');
      }
      if (name === 'uses-sdk') {
        if (depth !== 2 || ++sdkCount !== 1) fail(); result.minSdk = attrs.get(`${ANDROID_NS}:minSdkVersion`);
      }
    } else if (type === 0x0103) {
      if (header !== 16 || size !== 24 || --depth < 0) fail();
      const start = elementStack.pop();
      if (!start || start[0] !== u32(chunk,16) || start[1] !== u32(chunk,20)) fail();
    }
    offset += size;
  }
  if (depth !== 0 || manifestCount !== 1 || sdkCount !== 1 || typeof result.packageName !== 'string' || typeof result.versionName !== 'string'
    || !result.versionName || result.versionName.length > 100 || /[\x00-\x1f\x7f]/.test(result.versionName)
    || !Number.isSafeInteger(result.versionCode) || result.versionCode < 1 || result.versionCode > 2147483647
    || !Number.isInteger(result.minSdk) || result.minSdk < 24 || result.minSdk > 100) fail('APK 版本信息无效，最低支持 Android 7.0。');
  return result;
}

/** Bounded ZIP + binary XML reader and v2/v3 full-content signature verification.
 * Format: https://source.android.com/docs/security/features/apksigning/v2 and /v3.
 * Intentionally accepts one pinned signer, no key rotation, v1-only or split APKs.
 */
export function inspectApk(input, { packageName = APK_PACKAGE, signerSha256 = APK_SIGNER_SHA256 } = {}) {
  try {
    const apk = Buffer.isBuffer(input) ? input : Buffer.from(input);
    if (!apk.length || apk.length > MAX_APK_BYTES) fail('请选择不超过 4 MiB 的 APK 安装包。');
    const parsed = zip(apk), metadata = binaryManifest(parsed.manifest);
    if (metadata.packageName !== packageName) fail('APK 包名与现有 Huevia 不兼容。');
    if (parsed.signature.signerSha256 !== signerSha256) fail('APK 签名与现有 Huevia 不兼容，无法覆盖升级。');
    return { ...metadata, ...parsed.signature, byteLength: apk.length, sha256: hash(apk).toString('hex') };
  } catch (error) { if (error instanceof ServiceError) throw error; fail(); }
}
