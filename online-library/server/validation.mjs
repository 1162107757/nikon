import { createHash } from 'node:crypto';

const MAX_NCP = 1024 * 1024;
const MAX_PREVIEW = 8 * 1024 * 1024;
const MAX_CATALOG = 512 * 1024;
const MIME_EXTENSIONS = new Map([
  ['image/png', 'png'], ['image/jpeg', 'jpg'], ['image/gif', 'gif'], ['image/webp', 'webp'],
]);

export class ValidationError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'ValidationError';
    this.status = 400;
    this.code = code;
  }
}

function fail(code, message) { throw new ValidationError(code, message); }
function bytes(value, maximum, code, description) {
  if (!(value instanceof Uint8Array)) fail(code, `${description}必须是完整的二进制文件。`);
  if (!value.byteLength || value.byteLength > maximum) fail(code, `${description}为空或超过大小上限。`);
  return Buffer.from(value);
}
function sha256(value) { return createHash('sha256').update(value).digest('hex'); }

/** Same NCP 0100 envelope checks as the Android NcpFile validator; no filename-based inference. */
export function validateNcp(value) {
  const data = bytes(value, MAX_NCP, 'invalid_ncp', 'NCP 文件');
  if (data.length < 8) fail('invalid_ncp', 'NCP 文件为空、过短或超过 1 MB。');
  if (!data.subarray(0, 4).equals(Buffer.from([0x4e, 0x43, 0x50, 0]))) {
    fail('invalid_ncp', '文件不是已支持的 NCP 格式（签名不符）。');
  }
  let offset = 4;
  let version;
  while (offset < data.length) {
    if (data.length - offset < 4) fail('invalid_ncp', 'NCP 记录被截断。');
    const id = data.readUInt32BE(offset); offset += 4;
    if (id === 0) {
      if (offset !== data.length) fail('invalid_ncp', '暂不支持终止标记后有附加数据的 NCP。');
      if (!version) fail('invalid_ncp', 'NCP 缺少预设记录。');
      return { sha256: sha256(data), byteLength: data.length, ncpVersion: version };
    }
    if (data.length - offset < 4) fail('invalid_ncp', 'NCP 记录缺少长度。');
    const length = data.readUInt32BE(offset); offset += 4;
    if (length > data.length - offset) fail('invalid_ncp', 'NCP 记录长度超出文件范围。');
    if (id === 1) {
      if (version || length < 36) fail('invalid_ncp', '暂不支持此 NCP 预设记录结构。');
      // Compare exact bytes. ASCII decoding must not mask the high bit of unknown signatures.
      if (!data.subarray(offset, offset + 4).equals(Buffer.from('0100'))) {
        fail('unsupported_ncp_version', '暂不支持此 NCP 版本，仅支持 0100。');
      }
      version = '0100';
    }
    offset += length;
  }
  fail('invalid_ncp', 'NCP 缺少终止标记。');
}

/** Recognizes researched envelopes only; NP3 parameters are preserved, never converted to NCP. */
export function validatePresetFile(value) {
  const data = bytes(value, MAX_NCP, 'invalid_preset_file', '预设文件（上限 1 MiB）');
  // Preserve every existing NCP 0100 check, including its error codes and extensible records.
  if (data.length < 8 || data.readUInt32BE(4) !== 0x100) {
    return { ...validateNcp(data), fileFormat: 'NCP', extension: 'ncp' };
  }
  const invalid = message => fail('invalid_np3', message);
  if (!data.subarray(0, 4).equals(Buffer.from([0x4e, 0x43, 0x50, 0]))) invalid('NP3 文件签名不符。');
  let offset = 4, baseIndex = 0, note = false, curve = false;
  while (offset < data.length) {
    if (data.length - offset < 4) invalid('NP3 记录被截断。');
    const id = data.readUInt32BE(offset); offset += 4;
    if (id === 0) {
      if (offset !== data.length) invalid('暂不支持终止标记后有附加数据的 NP3。');
      if (baseIndex !== 32) invalid('NP3 缺少必要的预设记录。');
      return { sha256: sha256(data), byteLength: data.length, ncpVersion: '0310', fileFormat: 'NP3', extension: 'np3' };
    }
    if (data.length - offset < 4) invalid('NP3 记录缺少长度。');
    const length = data.readUInt32BE(offset); offset += 4;
    if (length > data.length - offset) invalid('NP3 记录长度超出文件范围。');
    if (baseIndex < 32) {
      const expectedId = (baseIndex + 1) * 0x100;
      const expectedLength = baseIndex === 0 ? 4 : baseIndex === 1 || baseIndex === 31 ? 20 : baseIndex === 30 ? 28 : 2;
      if (id !== expectedId || length !== expectedLength) invalid('暂不支持此 NP3 预设记录结构、顺序或长度。');
      if (baseIndex === 0 && !data.subarray(offset, offset + 4).equals(Buffer.from('0310'))) {
        fail('unsupported_np3_version', '暂不支持此 NP3 版本，仅支持 0310。');
      }
      baseIndex++;
    } else if (id === 0x10100 && !note && !curve && length >= 16 && length <= 248) {
      // The optional creator note is UTF-8; its bytes remain part of the immutable source file.
      try { new TextDecoder('utf-8', { fatal: true }).decode(data.subarray(offset, offset + length)); }
      catch { invalid('NP3 备注记录不是有效的 UTF-8 文字。'); }
      note = true;
    } else if (id === 2 && !curve && length === 578) {
      curve = true;
    } else invalid('NP3 含未知、重复或不受支持的附加记录。');
    offset += length;
  }
  invalid('NP3 缺少终止标记。');
}

/** Keep download filenames aligned with the detected bytes, including extensionless uploads. */
export function presetFilename(value, fileFormat) {
  const clean = (value || 'preset').replace(/[\\/\x00-\x1f\x7f]/g, '_').slice(0, 251);
  return `${clean.replace(/\.(?:ncp|np3)$/i, '') || 'preset'}.${fileFormat === 'NP3' ? 'NP3' : 'NCP'}`;
}

function invalidImage(message = '样片不是完整有效的 PNG、JPEG、GIF 或 WebP。') { fail('invalid_preview', message); }
function dimensions(width, height) {
  if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1
      || width > 16384 || height > 16384 || width * height > 64_000_000) {
    invalidImage('样片尺寸无效或过大，最长边为 16384，最多 6400 万像素。');
  }
}

const CRC_TABLE = Array.from({ length: 256 }, (_, value) => {
  let crc = value;
  for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  return crc >>> 0;
});
function crc32(data) {
  let crc = 0xffffffff;
  for (const value of data) crc = CRC_TABLE[(crc ^ value) & 255] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function png(data) {
  let offset = 8, header = false, image = false, afterImage = false, palette = false, color, depth;
  let imageBytes = 0;
  const prefix = [];
  while (offset < data.length) {
    if (data.length - offset < 12) invalidImage('PNG 数据块被截断。');
    const length = data.readUInt32BE(offset);
    if (length > data.length - offset - 12) invalidImage('PNG 数据块长度超出文件范围。');
    const type = data.toString('latin1', offset + 4, offset + 8);
    if (!/^[A-Za-z]{4}$/.test(type) || data[offset + 6] & 0x20) invalidImage('PNG 数据块名称无效。');
    const body = data.subarray(offset + 8, offset + 8 + length);
    if (crc32(data.subarray(offset + 4, offset + 8 + length)) !== data.readUInt32BE(offset + 8 + length)) invalidImage('PNG 数据块校验失败。');
    offset += length + 12;
    if (!header && type !== 'IHDR') invalidImage('PNG 缺少首部。');
    if (type === 'IHDR') {
      if (header || length !== 13) invalidImage('PNG 首部无效。');
      header = true; dimensions(body.readUInt32BE(0), body.readUInt32BE(4));
      depth = body[8]; color = body[9];
      const depths = { 0: [1, 2, 4, 8, 16], 2: [8, 16], 3: [1, 2, 4, 8], 4: [8, 16], 6: [8, 16] };
      if (!depths[color]?.includes(depth) || body[10] !== 0 || body[11] !== 0 || body[12] > 1) invalidImage('PNG 编码参数无效。');
    } else if (type === 'PLTE') {
      if (palette || image || color === 0 || color === 4 || !length || length > 768 || length % 3 || color === 3 && length / 3 > 2 ** depth) invalidImage('PNG 色表无效。');
      palette = true;
    } else if (type === 'IDAT') {
      if (afterImage || color === 3 && !palette) invalidImage('PNG 图像数据顺序无效。');
      image = true; imageBytes += length;
      for (let i = 0; i < body.length && prefix.length < 2; i++) prefix.push(body[i]);
    } else if (type === 'IEND') {
      if (length || !image || imageBytes < 6 || offset !== data.length) invalidImage('PNG 结束标记或图像数据无效。');
      if ((prefix[0] & 15) !== 8 || (prefix[0] >>> 4) > 7 || ((prefix[0] << 8) + prefix[1]) % 31 || prefix[1] & 32) invalidImage('PNG 压缩流首部无效。');
      return;
    } else {
      if (image) afterImage = true;
      if (!(data[offset - length - 8] & 0x20)) invalidImage('PNG 包含不支持的关键数据块。');
    }
  }
  invalidImage('PNG 缺少完整结束标记。');
}

const SOF = new Set([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf]);
function jpeg(data) {
  let offset = 2, frame = false, scan = false, entropy = 0;
  const components = new Set();
  while (offset < data.length) {
    if (data[offset++] !== 0xff) invalidImage('JPEG 标记无效。');
    while (offset < data.length && data[offset] === 0xff) offset++;
    if (offset >= data.length) invalidImage('JPEG 标记被截断。');
    const marker = data[offset++];
    if (marker === 0xd9) {
      if (!frame || !scan || !entropy || offset !== data.length) invalidImage('JPEG 图像数据或结束标记无效。');
      return;
    }
    if (marker === 0 || marker === 0xd8 || marker >= 0xd0 && marker <= 0xd7) invalidImage('JPEG 标记顺序无效。');
    if (marker === 0x01) continue;
    if (data.length - offset < 2) invalidImage('JPEG 数据段被截断。');
    const length = data.readUInt16BE(offset);
    if (length < 2 || length > data.length - offset) invalidImage('JPEG 数据段长度无效。');
    const body = data.subarray(offset + 2, offset + length); offset += length;
    if (SOF.has(marker)) {
      if (frame || body.length < 6 || ![8, 12, 16].includes(body[0]) || body[5] < 1 || body[5] > 4 || body.length !== 6 + body[5] * 3) invalidImage('JPEG 帧首部无效。');
      dimensions(body.readUInt16BE(3), body.readUInt16BE(1)); frame = true;
      for (let i = 0; i < body[5]; i++) {
        const at = 6 + i * 3, sampling = body[at + 1];
        if (components.has(body[at]) || !(sampling & 15) || (sampling & 15) > 4 || !(sampling >>> 4) || (sampling >>> 4) > 4 || body[at + 2] > 3) invalidImage('JPEG 颜色分量无效。');
        components.add(body[at]);
      }
    } else if (marker === 0xda) {
      if (!frame || !body.length || body[0] < 1 || body[0] > components.size || body.length !== 4 + body[0] * 2) invalidImage('JPEG 扫描首部无效。');
      const selected = new Set();
      for (let i = 0; i < body[0]; i++) {
        const id = body[1 + i * 2], table = body[2 + i * 2];
        if (!components.has(id) || selected.has(id) || (table & 15) > 3 || (table >>> 4) > 3) invalidImage('JPEG 扫描分量无效。');
        selected.add(id);
      }
      scan = true;
      while (offset < data.length) {
        if (data[offset] !== 0xff) { entropy++; offset++; continue; }
        let next = offset + 1;
        while (next < data.length && data[next] === 0xff) next++;
        if (next >= data.length) invalidImage('JPEG 扫描数据被截断。');
        if (data[next] === 0 || data[next] >= 0xd0 && data[next] <= 0xd7) { entropy++; offset = next + 1; continue; }
        break;
      }
    }
  }
  invalidImage('JPEG 缺少完整结束标记。');
}

function gifSubBlocks(data, position) {
  let length = 0, offset = position;
  while (offset < data.length) {
    const count = data[offset++];
    if (!count) {
      // A hostile file can contain millions of one-byte blocks. Allocate their payload once,
      // rather than retaining one Buffer and array entry for every tiny block.
      const payload = Buffer.alloc(length); let input = position, written = 0;
      while (data[input]) {
        const size = data[input++]; data.copy(payload, written, input, input + size); input += size; written += size;
      }
      return { offset, data: payload };
    }
    if (count > data.length - offset) invalidImage('GIF 数据块被截断。');
    length += count; offset += count;
  }
  invalidImage('GIF 缺少数据块结束标记。');
}
function gifPixels(data, minimum, expected, colors) {
  if (minimum < 2 || minimum > 8 || !data.length) invalidImage('GIF 图像编码无效。');
  const clear = 1 << minimum, end = clear + 1;
  const lengths = new Uint32Array(4096); lengths.fill(1, 0, clear);
  let bit = 0, bits = minimum + 1, next = end + 1, previous = -1, pixels = 0, started = false;
  while (bit + bits <= data.length * 8) {
    let code = 0;
    for (let i = 0; i < bits; i++) code |= ((data[(bit + i) >>> 3] >>> ((bit + i) & 7)) & 1) << i;
    bit += bits;
    if (code === clear) { bits = minimum + 1; next = end + 1; previous = -1; started = true; continue; }
    if (!started) invalidImage('GIF 图像缺少字典重置标记。');
    if (code === end) {
      if (pixels !== expected || Math.ceil(bit / 8) !== data.length) invalidImage('GIF 像素数据不完整或有附加内容。');
      return;
    }
    if (code < clear && code >= colors || code > next || code === next && previous < 0 || code >= 4096) invalidImage('GIF 压缩字典无效。');
    const count = code === next ? lengths[previous] + 1 : lengths[code];
    if (!count || (pixels += count) > expected) invalidImage('GIF 像素数据超过声明尺寸。');
    if (previous >= 0 && next < 4096) {
      lengths[next++] = lengths[previous] + 1;
      if (next === 1 << bits && bits < 12) bits++;
    }
    previous = code;
  }
  invalidImage('GIF 图像缺少编码结束标记。');
}
function gif(data) {
  if (data.length < 14) invalidImage('GIF 首部被截断。');
  const width = data.readUInt16LE(6), height = data.readUInt16LE(8); dimensions(width, height);
  const globalColors = data[10] & 128 ? 1 << ((data[10] & 7) + 1) : 0;
  let offset = 13 + globalColors * 3, frames = 0;
  if (offset > data.length) invalidImage('GIF 色表被截断。');
  while (offset < data.length) {
    const marker = data[offset++];
    if (marker === 0x3b) { if (!frames || offset !== data.length) invalidImage('GIF 图像或结束标记无效。'); return; }
    if (marker === 0x21) {
      if (offset >= data.length) invalidImage('GIF 扩展被截断。');
      const type = data[offset++];
      if (type === 0xf9) {
        if (data.length - offset < 6 || data[offset] !== 4 || data[offset + 5] !== 0 || data[offset + 1] & 0xe0 || ((data[offset + 1] >>> 2) & 7) > 3) invalidImage('GIF 图形控制扩展无效。');
        offset += 6;
      } else {
        if (type === 0xff || type === 0x01) {
          const size = type === 0xff ? 11 : 12;
          if (data[offset++] !== size || size > data.length - offset) invalidImage('GIF 扩展首部无效。');
          offset += size;
        } else if (type !== 0xfe) invalidImage('GIF 扩展类型不受支持。');
        offset = gifSubBlocks(data, offset).offset;
      }
    } else if (marker === 0x2c) {
      if (data.length - offset < 9) invalidImage('GIF 图像描述被截断。');
      const left = data.readUInt16LE(offset), top = data.readUInt16LE(offset + 2);
      const w = data.readUInt16LE(offset + 4), h = data.readUInt16LE(offset + 6), flags = data[offset + 8]; offset += 9;
      dimensions(w, h);
      if (left + w > width || top + h > height || flags & 0x18) invalidImage('GIF 图像范围无效。');
      const colors = flags & 128 ? 1 << ((flags & 7) + 1) : globalColors;
      if (!colors) invalidImage('GIF 图像缺少色表。');
      if (flags & 128) offset += colors * 3;
      if (offset >= data.length) invalidImage('GIF 图像数据被截断。');
      const minimum = data[offset++], blocks = gifSubBlocks(data, offset); offset = blocks.offset;
      gifPixels(blocks.data, minimum, w * h, colors); frames++;
    } else invalidImage('GIF 数据标记无效。');
  }
  invalidImage('GIF 缺少完整结束标记。');
}

function little24(data, offset) { return data[offset] | data[offset + 1] << 8 | data[offset + 2] << 16; }
function webpImage(type, body) {
  if (type === 'VP8 ') {
    if (body.length < 11 || body[0] & 1 || !body.subarray(3, 6).equals(Buffer.from([0x9d, 1, 0x2a]))) invalidImage('WebP VP8 图像首部无效。');
    const tag = body.readUIntLE(0, 3);
    if (((tag >>> 1) & 7) > 3 || !(tag & 16) || !(tag >>> 5) || (tag >>> 5) > body.length - 10) invalidImage('WebP VP8 压缩分区无效。');
    const width = body.readUInt16LE(6) & 0x3fff, height = body.readUInt16LE(8) & 0x3fff; dimensions(width, height);
    return { width, height };
  }
  if (body.length < 6 || body[0] !== 0x2f || body[4] & 0xe0) invalidImage('WebP VP8L 图像首部无效。');
  const packed = body.readUInt32LE(1), width = (packed & 0x3fff) + 1, height = ((packed >>> 14) & 0x3fff) + 1;
  dimensions(width, height); return { width, height };
}
function webpAlpha(body, width, height) {
  if (body.length < 2 || body[0] & 0xc0 || (body[0] & 3) > 1 || ((body[0] >>> 4) & 3) > 1
      || !(body[0] & 3) && body.length !== 1 + width * height) invalidImage('WebP 透明数据无效。');
}
function* riffChunks(data, start, finish) {
  let offset = start;
  while (offset < finish) {
    if (finish - offset < 8) invalidImage('WebP 数据块被截断。');
    const type = data.toString('latin1', offset, offset + 4), length = data.readUInt32LE(offset + 4); offset += 8;
    if (!/^[\x20-\x7e]{4}$/.test(type) || length > finish - offset || (length & 1) > finish - offset - length) invalidImage('WebP 数据块长度无效。');
    yield { type, body: data.subarray(offset, offset + length) }; offset += length;
    if (length & 1) { if (data[offset] !== 0) invalidImage('WebP 数据块填充无效。'); offset++; }
  }
}
function webp(data) {
  if (data.length < 20 || data.readUInt32LE(4) !== data.length - 8) invalidImage('WebP 容器长度无效。');
  let canvas, animation = false, control = false, frames = 0, image = false, alpha = false;
  let index = 0;
  for (const { type, body } of riffChunks(data, 12, data.length)) {
    if (type === 'VP8X') {
      if (canvas || index !== 0 || body.length !== 10 || body[0] & 0xc1 || body[1] || body[2] || body[3]) invalidImage('WebP 扩展首部无效。');
      canvas = { width: little24(body, 4) + 1, height: little24(body, 7) + 1 };
      dimensions(canvas.width, canvas.height); animation = Boolean(body[0] & 2);
    } else if (type === 'ANIM') {
      if (!canvas || !animation || control || frames || body.length !== 6) invalidImage('WebP 动画首部无效。');
      control = true;
    } else if (type === 'ANMF') {
      if (!control || image || body.length < 16 || body[15] & 0xfc) invalidImage('WebP 动画帧无效。');
      const x = little24(body, 0) * 2, y = little24(body, 3) * 2, width = little24(body, 6) + 1, height = little24(body, 9) + 1;
      dimensions(width, height);
      if (x + width > canvas.width || y + height > canvas.height) invalidImage('WebP 动画帧超出画布。');
      let frameImage = false, frameAlpha = false;
      for (const part of riffChunks(body, 16, body.length)) {
        if (part.type === 'ALPH') { if (frameImage || frameAlpha) invalidImage('WebP 透明数据重复或顺序无效。'); webpAlpha(part.body, width, height); frameAlpha = true; }
        else if (part.type === 'VP8 ' || part.type === 'VP8L') {
          if (frameImage || frameAlpha && part.type === 'VP8L') invalidImage('WebP 帧图像重复或无效。');
          const size = webpImage(part.type, part.body);
          if (size.width !== width || size.height !== height) invalidImage('WebP 帧尺寸不一致。');
          frameImage = true;
        } else invalidImage('WebP 动画帧包含无效数据。');
      }
      if (!frameImage) invalidImage('WebP 动画帧缺少图像。'); frames++;
    } else if (type === 'ALPH') {
      if (!canvas || animation || image || alpha) invalidImage('WebP 透明数据重复或顺序无效。');
      webpAlpha(body, canvas.width, canvas.height); alpha = true;
    } else if (type === 'VP8 ' || type === 'VP8L') {
      if (image || animation || alpha && type === 'VP8L') invalidImage('WebP 图像重复或无效。');
      const size = webpImage(type, body);
      if (canvas && (size.width !== canvas.width || size.height !== canvas.height)) invalidImage('WebP 图像尺寸与画布不一致。');
      image = true;
    } else if (['ICCP', 'EXIF', 'XMP '].includes(type) && !canvas) invalidImage('WebP 元数据缺少扩展首部。');
    index++;
  }
  if (!image && !(control && frames)) invalidImage('WebP 缺少完整图像。');
}

/** Bounded container/structure validation, not a replacement for an isolated image codec. */
export function validatePreview(value, contentType) {
  const data = bytes(value, MAX_PREVIEW, 'invalid_preview', '样片（上限 8 MiB）');
  let mimeType;
  if (data.length >= 8 && data.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) { png(data); mimeType = 'image/png'; }
  else if (data.length >= 2 && data[0] === 0xff && data[1] === 0xd8) { jpeg(data); mimeType = 'image/jpeg'; }
  else if (data.length >= 6 && ['GIF87a', 'GIF89a'].includes(data.toString('latin1', 0, 6))) { gif(data); mimeType = 'image/gif'; }
  else if (data.length >= 12 && data.toString('latin1', 0, 4) === 'RIFF' && data.toString('latin1', 8, 12) === 'WEBP') { webp(data); mimeType = 'image/webp'; }
  else invalidImage();
  if (contentType !== undefined && contentType !== null && contentType !== '') {
    if (typeof contentType !== 'string' || contentType.length > 200 || contentType.split(';', 1)[0].trim().toLowerCase() !== mimeType) invalidImage('样片声明的文件类型与实际内容不符。');
  }
  return { sha256: sha256(data), byteLength: data.length, mimeType, extension: MIME_EXTENSIONS.get(mimeType) };
}

function object(value, description, code) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)
      || ![Object.prototype, null].includes(Object.getPrototypeOf(value))) fail(code, `${description}必须是对象。`);
  return value;
}
function text(value, label, maximum, required, code) {
  if ((value === undefined || value === null) && !required) return '';
  if (typeof value !== 'string' || /[\x00-\x1f\x7f]/.test(value)) fail(code, `${label}必须是无控制字符的文字。`);
  const clean = value.trim();
  if (clean.length > maximum || required && !clean) fail(code, `${label}${required ? '不能为空且' : ''}不能超过 ${maximum} 个字符。`);
  if (/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(clean)) fail(code, `${label}包含无效字符。`);
  return clean;
}

export function validatePresetInput(input) {
  object(input, '预设信息', 'invalid_preset');
  const name = text(input.name, '预设名称', 160, true, 'invalid_preset');
  const description = text(input.description, '预设说明', 2000, false, 'invalid_preset');
  const category = validateCategory(input.category);
  const models = input.compatibleModels ?? [];
  if (!Array.isArray(models) || models.length > 32) fail('invalid_preset', '适用机型必须是不超过 32 项的数组。');
  const compatibleModels = [...new Set(models.map(model => text(model, '机型名称', 80, true, 'invalid_preset')))];
  const sortOrder = input.sortOrder === undefined ? 0 : input.sortOrder;
  if (!Number.isSafeInteger(sortOrder) || Math.abs(sortOrder) > 1_000_000) fail('invalid_preset', '排序必须为 -1000000 至 1000000 的整数。');
  return { name, description, category, compatibleModels, sortOrder };
}

/** One optional category; compare/store NFC text and count Unicode code points, not UTF-16 units. */
export function validateCategory(value) {
  if (value === undefined || value === null) return '';
  if (typeof value !== 'string' || /[\x00-\x1f\x7f-\x9f]/u.test(value)
      || /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(value)) {
    fail('invalid_preset', '分类必须是无控制字符的有效文字。');
  }
  const category = value.normalize('NFC').trim();
  if ([...category].length > 24) fail('invalid_preset', '分类不能超过 24 个字符。');
  if (category === '全部' || category === '未分类') fail('invalid_preset', '请填写具体分类，留空表示未分类。');
  return category;
}

function publicUrl(value, label) {
  const address = text(value, label, 4096, true, 'invalid_catalog');
  let url;
  try { url = new URL(address); } catch { fail('invalid_catalog', `${label}必须是公开的完整 HTTPS 地址。`); }
  const authority = address.match(/^https:\/\/([^/]+)/i)?.[1];
  const hostname = url.hostname.replace(/\.$/, '');
  if (address !== value || !authority || authority.includes('@') || !/^[\x21-\x7e]+$/.test(address)
      || /[\s\\]/u.test(address) || /%(?![0-9a-f]{2})/i.test(address)
      || url.protocol !== 'https:' || !hostname || !hostname.startsWith('[') && !hostname.split('.').every(label => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(label)) || url.username || url.password
      || url.hash || url.search || address.includes('?') || address.includes('#') || url.port === '0') {
    fail('invalid_catalog', `${label}须使用不含账号、签名查询或片段的公开 HTTPS 地址。`);
  }
  return address;
}
function hashField(value) {
  if (typeof value !== 'string' || !/^[0-9a-f]{64}$/i.test(value)) fail('invalid_catalog', '文件 SHA-256 格式无效。');
  return value.toLowerCase();
}
function fileMetadata(value, preview = false) {
  object(value, preview ? '样片文件' : '预设文件', 'invalid_catalog');
  const url = publicUrl(value.url, preview ? '样片地址' : '预设文件地址');
  const sha = hashField(value.sha256), length = value.byteLength;
  if (!Number.isSafeInteger(length) || length < (preview ? 4 : 8) || length > (preview ? MAX_PREVIEW : MAX_NCP)) fail('invalid_catalog', '文件大小元数据无效或超过上限。');
  const fileFormat = value.fileFormat ?? 'NCP';
  if (!preview && !((fileFormat === 'NCP' && value.ncpVersion === '0100' && (value.extension == null || value.extension === 'ncp'))
    || (fileFormat === 'NP3' && value.ncpVersion === '0310' && value.extension === 'np3'))) {
    fail('invalid_catalog', '仅可发布已验证的 NCP 0100 或 NP3 0310 文件。');
  }
  if (preview && (!MIME_EXTENSIONS.has(value.mimeType) || value.extension !== MIME_EXTENSIONS.get(value.mimeType))) fail('invalid_catalog', '样片格式元数据不符。');
  return { url, sha256: sha, byteLength: length, ...(preview ? {} : { fileFormat }) };
}

/** Only immutable published snapshots may enter the public Android-compatible manifest. */
export function buildCatalog(items) {
  if (!Array.isArray(items)) fail('invalid_catalog', '在线目录条目必须是数组。');
  const entries = [], ids = new Set();
  for (const item of items) {
    object(item, '在线目录条目', 'invalid_catalog');
    if (item.status !== 'published') continue;
    const id = text(item.id, '预设 ID', 128, true, 'invalid_catalog');
    if (ids.has(id)) fail('invalid_catalog', '在线目录包含重复预设 ID。');
    ids.add(id);
    if (ids.size > 256) fail('invalid_catalog', '在线目录最多发布 256 项预设。');
    const published = object(item.published, '已发布预设快照', 'invalid_catalog');
    const metadata = validatePresetInput(published), file = fileMetadata(published.file);
    const preview = published.preview == null ? null : fileMetadata(published.preview, true);
    entries.push({ sortOrder: metadata.sortOrder, preset: {
      id, name: metadata.name, description: metadata.description, category: metadata.category, fileUrl: file.url,
      sha256: file.sha256, byteLength: file.byteLength, fileFormat: file.fileFormat, previewUrl: preview?.url ?? '',
      compatibleModels: metadata.compatibleModels,
    } });
  }
  entries.sort((a, b) => a.sortOrder - b.sortOrder || (a.preset.id < b.preset.id ? -1 : a.preset.id > b.preset.id ? 1 : 0));
  const catalog = { version: 1, presets: entries.map(entry => entry.preset) };
  if (Buffer.byteLength(JSON.stringify(catalog), 'utf8') > MAX_CATALOG) fail('invalid_catalog', '在线目录超过 512 KiB，请减少条目或缩短说明。');
  return catalog;
}
