#!/usr/bin/env node
'use strict';

/**
 * Loopback-only, read-only PTP/IP responder for Android emulator UI verification.
 * Generate fixtures and run from the repository root:
 *   javac -d app/build/album-ui-fixtures tests/tools/SyntheticAlbumPhotos.java
 *   java -Djava.awt.headless=true -cp app/build/album-ui-fixtures SyntheticAlbumPhotos
 *   node tests/tools/album-camera-server.js
 * For independent preset capability checks, without photo fixtures:
 *   node tests/tools/album-camera-server.js --picture-control-readonly --fixtures=app/build/picture-control-ui-fixtures
 * In the emulator connect the album to 10.0.2.2:15740.
 * Logs: app/build/album-ui-fixtures/server.log. Stops after 600 seconds by default.
 * --picture-control-import-synthetic enables one in-memory virtual-slot registration.
 * --picture-control-album-import-synthetic also provides an empty read-only album for handoff tests.
 * --picture-control-overwrite-synthetic additionally seeds C1 and permits guarded in-memory overwrite.
 * --post-import-heartbeat-error-once returns a single GeneralError after the first virtual write's exact readback.
 * Add --keep-import-session to permit up to nine explicit registrations into distinct empty virtual slots.
 * Album fixtures with every LARGE_*.JPG advertise Nikon GetLargeThumb; --large-thumb-reject exercises its explicit rejection fallback.
 * This service has no camera/device access. The default and read-only modes reject uploads.
 */
const net = require('node:net');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const options = { port: 15740, seconds: 600, fixtures: 'app/build/album-ui-fixtures', pictureControlReadonly: false, pictureControlImportSynthetic: false, pictureControlAlbumImportSynthetic: false, pictureControlOverwriteSynthetic: false, keepImportSession: false, largeThumbReject: false, postImportHeartbeatErrorOnce: false };
let selectedPictureControlMode = '';
for (const argument of process.argv.slice(2)) {
  if (argument === '--keep-import-session') { options.keepImportSession = true; continue; }
  if (argument === '--large-thumb-reject') { options.largeThumbReject = true; continue; }
  if (argument === '--post-import-heartbeat-error-once') { options.postImportHeartbeatErrorOnce = true; continue; }
  if (['--picture-control-readonly', '--picture-control-import-synthetic', '--picture-control-album-import-synthetic', '--picture-control-overwrite-synthetic'].includes(argument)) {
    if (selectedPictureControlMode && selectedPictureControlMode !== argument) throw new Error('Picture Control modes are mutually exclusive');
    selectedPictureControlMode = argument;
    options.pictureControlReadonly = argument === '--picture-control-readonly';
    options.pictureControlOverwriteSynthetic = argument === '--picture-control-overwrite-synthetic';
    options.pictureControlAlbumImportSynthetic = argument === '--picture-control-album-import-synthetic' || options.pictureControlOverwriteSynthetic;
    options.pictureControlImportSynthetic = argument !== '--picture-control-readonly';
    continue;
  }
  const match = /^--(port|seconds|fixtures)=(.+)$/.exec(argument);
  if (!match) throw new Error(`Unknown argument: ${argument}`);
  options[match[1]] = match[1] === 'fixtures' ? match[2] : Number(match[2]);
}
if (options.pictureControlReadonly && options.pictureControlImportSynthetic) throw new Error('Picture Control modes are mutually exclusive');
if (options.keepImportSession && !options.pictureControlImportSynthetic) throw new Error('--keep-import-session requires a synthetic Picture Control import mode');
if (options.postImportHeartbeatErrorOnce && !options.pictureControlImportSynthetic) throw new Error('--post-import-heartbeat-error-once requires a synthetic Picture Control import mode');
const pictureControl = options.pictureControlReadonly || options.pictureControlImportSynthetic;
const maxVirtualWrites = options.keepImportSession ? 9 : 1;
if (!Number.isInteger(options.port) || options.port < 1 || options.port > 65535
    || !Number.isInteger(options.seconds) || options.seconds < 1 || options.seconds > 3600) {
  throw new Error('port must be 1..65535; seconds must be 1..3600');
}
const directory = path.resolve(options.fixtures);
if (pictureControl) fs.mkdirSync(directory, { recursive: true });
const logFile = path.join(directory, 'server.log');
const photos = new Map();
for (let handle = 1; handle <= (pictureControl ? 0 : 49); handle++) {
  const suffix = String(handle).padStart(4, '0') + '.JPG';
  const filename = `TEST_${suffix}`;
  const original = fs.readFileSync(path.join(directory, filename));
  const thumbnail = fs.readFileSync(path.join(directory, `THUMB_${suffix}`));
  const largePath = path.join(directory, `LARGE_${suffix}`);
  const large = fs.existsSync(largePath) ? fs.readFileSync(largePath) : null;
  if (original.readUInt16BE(0) !== 0xffd8 || thumbnail.readUInt16BE(0) !== 0xffd8) {
    throw new Error(`Invalid synthetic JPEG: ${filename}`);
  }
  if (large && (large.length > 8 * 1024 * 1024 || large.readUInt16BE(0) !== 0xffd8)) throw new Error(`Invalid synthetic large preview: ${filename}`);
  photos.set(handle, { filename, original, thumbnail, large });
}
const largeThumbnailAvailable = !pictureControl && photos.size > 0 && [...photos.values()].every(photo => photo.large);
fs.writeFileSync(logFile, '');
const allowed = new Set(pictureControl
  ? [0x1001, 0x1002, 0x1003, 0x90cc, 0x90cf, ...(options.pictureControlImportSynthetic ? [0x90cd] : []),
    ...(options.pictureControlAlbumImportSynthetic ? [0x1004, 0x1007, 0x1008, 0x1009, 0x100a] : [])]
  : [0x1001, 0x1002, 0x1003, 0x1004, 0x1007, 0x1008, 0x1009, 0x100a, ...(largeThumbnailAvailable ? [0x90c4] : [])]);
const pictureControlReadPlan = [0x1001, 0x1002, 0x90cf, ...Array(10).fill(0x90cc), 0x1003];
const storageId = 0x00010001;
const connections = new Map();
const sockets = new Set();
const virtualSlots = new Map(Array.from({ length: 9 }, (_, index) => [201 + index, Buffer.alloc(36)]));
if (options.pictureControlOverwriteSynthetic) {
  const existing = Buffer.alloc(36); existing.writeUInt16LE(1); existing.write('SYNTHETIC OLD', 2, 'ascii');
  virtualSlots.set(201, existing);
}
const attemptedTargets = new Set();
let connectionNumber = 0, sequence = 0, originals = 0, largePreviews = 0, closing = false;
let virtualWrites = 0, openCount = 0, closeCount = 0, heartbeatCount = 0, acceptingInit = true;
function log(message) {
  const line = `${new Date().toISOString()} ${String(++sequence).padStart(4, '0')} ${message}`;
  fs.appendFileSync(logFile, line + '\n');
  process.stdout.write(line + '\n');
}
function u16(value) { const result = Buffer.alloc(2); result.writeUInt16LE(value); return result; }
function u32(value) { const result = Buffer.alloc(4); result.writeUInt32LE(value); return result; }
function u64(value) { const result = Buffer.alloc(8); result.writeBigUInt64LE(BigInt(value)); return result; }
function str(value) {
  return value.length === 0 ? Buffer.from([0])
    : Buffer.concat([Buffer.from([value.length + 1]), Buffer.from(value + '\0', 'utf16le')]);
}
function a16(values) { return Buffer.concat([u32(values.length), ...values.map(u16)]); }
function a32(values) { return Buffer.concat([u32(values.length), ...values.map(u32)]); }
function frame(type, body) { return Buffer.concat([u32(body.length + 8), u32(type), body]); }
function send(socket, type, body = Buffer.alloc(0)) { if (!socket.destroyed) socket.write(frame(type, body)); }
function response(socket, transaction, code = 0x2001) { send(socket, 7, Buffer.concat([u16(code), u32(transaction)])); }
function transfer(socket, transaction, data) {
  send(socket, 9, Buffer.concat([u32(transaction), u64(data.length)]));
  for (let offset = 0; offset < data.length; offset += 32768) {
    const end = Math.min(offset + 32768, data.length);
    send(socket, end === data.length ? 12 : 10, Buffer.concat([u32(transaction), data.subarray(offset, end)]));
  }
  if (data.length === 0) send(socket, 12, u32(transaction));
  response(socket, transaction);
}
const deviceInfo = Buffer.concat([
  u16(100), u32(10), u16(100), str(pictureControl ? (options.pictureControlAlbumImportSynthetic ? 'SYNTHETIC TEST ONLY - empty album and virtual in-memory Picture Control import' : options.pictureControlImportSynthetic ? 'SYNTHETIC TEST ONLY - virtual in-memory Picture Control import' : 'SYNTHETIC TEST ONLY - read-only Picture Control fixture') : 'Synthetic read-only album fixture'), u16(0),
  a16([...allowed]), a16([]), a16([]), a16([]), a16(pictureControl && !options.pictureControlAlbumImportSynthetic ? [] : [0x3801]),
  str('Nikon'), str(pictureControl ? 'Z6II' : 'Z6II (SYNTHETIC TEST)'), str('TEST ONLY'), str('SYNTHETIC-0000')
]);
function objectInfo(handle, photo) {
  return Buffer.concat([
    u32(storageId), u16(0x3801), u16(0), u32(photo.original.length),
    u16(0x3801), u32(photo.thumbnail.length), u32(320), u32(200),
    u32(1600), u32(1000), u32(24), u32(0), u16(0), u32(0), u32(handle),
    str(photo.filename), str('20261016T120000'), str('20261016T120000'), str('SYNTHETIC TEST')
  ]);
}
function reject(socket, transaction, code, reason) {
  log(`REJECT session=${socket.session?.id || '?'} tx=${transaction} ${reason}`);
  response(socket, transaction, code);
}
function syntheticSummary(session, state) {
  log(`PC_IMPORT_SYNTHETIC_SUMMARY session=${session.id} state=${state} connections=${connectionNumber} opens=${openCount} closes=${closeCount} heartbeats=${heartbeatCount} standardReads=${session.standardReads} virtualWrites=${virtualWrites} commands=${session.commands.length} keepImportSession=${options.keepImportSession} attemptedTargets=[${[...attemptedTargets].join(',')}]`);
}
function validateSyntheticRegistration(data) {
  // Exactly the current NCP converter's 36-byte header plus 578-byte custom curve.
  if (data.length !== 614 || ![1, 2, 3, 5, 6].includes(data.readUInt16LE(0))) throw new Error('Invalid synthetic registration base/length');
  const nameEnd = data.indexOf(0, 2);
  if (nameEnd < 3 || nameEnd > 21 || !data.subarray(2, nameEnd).every(value => value >= 32 && value <= 126)
      || !data.subarray(2, nameEnd).toString('ascii').trim()
      || !data.subarray(nameEnd, 22).every(value => value === 0)) throw new Error('Invalid synthetic registration name');
  if (![22, 23, 24, 28, 29, 32, 33, 34].every(index => data[index] === 0)
      || data[25] > 36 || data[25] % 4 !== 0 || data[35] !== 1
      || Math.abs(data.readInt8(26)) > 20 || Math.abs(data.readInt8(27)) > 20
      || Math.abs(data.readInt8(30)) > 12 || Math.abs(data.readInt8(31)) > 12 || data.readInt8(30) % 4 !== 0 || data.readInt8(31) % 4 !== 0
      || data[36] !== 0x49 || data[37] !== 0x30 || data[42] > 20 || data[43] > 100 || data[44] < 2 || data[44] > 20
      || !data.subarray(85, 100).every(value => value === 0)) throw new Error('Invalid synthetic registration parameters/curve');
  for (let offset = 100; offset < 614; offset += 2) if (data.readUInt16BE(offset) > 32767) throw new Error('Invalid synthetic curve sample');
}
function syntheticDataOut(socket, type, body) {
  const session = socket.session, pending = session.pendingWrite;
  if (!pending || body.length < 4 || body.readUInt32LE(0) !== pending.transaction) throw new Error('Unexpected or mismatched data-out transaction');
  if (type === 9) {
    if (pending.started || body.length !== 12 || body.readBigUInt64LE(4) !== 614n) throw new Error('Invalid synthetic StartData');
    pending.started = true;
    log(`DATA_OUT_START session=${session.id} tx=${pending.transaction} expected=614`);
    return;
  }
  if (!pending.started || (type !== 10 && type !== 12) || body.length > 618
      || (type === 10 && body.length === 4)) throw new Error('Out-of-sequence synthetic data-out');
  const part = Buffer.from(body.subarray(4));
  pending.parts.push(part); pending.received += part.length;
  if (pending.received > 614) throw new Error('Synthetic data-out exceeds announced length');
  if (type === 10) return;
  if (pending.received !== 614) throw new Error('Incomplete synthetic EndData');
  const data = Buffer.concat(pending.parts, 614);
  validateSyntheticRegistration(data);
  virtualSlots.set(pending.target, data);
  session.awaitingImportReadback = { target: pending.target, sha256: crypto.createHash('sha256').update(data).digest('hex') };
  virtualWrites++;
  session.pendingWrite = null;
  if (options.keepImportSession) {
    // Later registrations need their own fresh read evidence, even on this same connection.
    session.freshImportInfo = false; session.capabilityRead = false; session.slotReads.clear(); session.slotReadProof.clear();
  }
  log(`VIRTUAL_WRITE session=${session.id} tx=${pending.transaction} target=${pending.target} bytes=614 sha256=${crypto.createHash('sha256').update(data).digest('hex')} virtualWrites=${virtualWrites} hardwareAccess=false`);
  return response(socket, pending.transaction);
}
function command(socket, body) {
  if (body.length < 10 || (body.length - 10) % 4 !== 0 || body.length > 30) throw new Error('Malformed operation');
  const phase = body.readUInt32LE(0), operation = body.readUInt16LE(4), transaction = body.readUInt32LE(6);
  const parameters = [];
  for (let index = 10; index < body.length; index += 4) parameters.push(body.readUInt32LE(index));
  const session = socket.session;
  session.commands.push(operation);
  log(`CMD session=${session.id} tx=${transaction} op=0x${operation.toString(16)} phase=${phase} params=[${parameters.join(',')}]`);
  const previousCommand = session.lastCommand;
  session.lastCommand = { operation, transaction, parameters };
  const syntheticWrite = options.pictureControlImportSynthetic && operation === 0x90cd;
  if (phase !== (syntheticWrite ? 2 : 1) || !allowed.has(operation)) return reject(socket, transaction, 0x2005, 'write or non-whitelisted operation');
  if (options.pictureControlImportSynthetic) {
    if (!session.event || session.pendingWrite) throw new Error('Command before event handshake or during data-out');
    if (session.ended || (session.open ? transaction <= session.lastTransaction : transaction !== 0)) {
      return reject(socket, transaction, 0x201d, 'synthetic session transaction order');
    }
    if (session.open) session.lastTransaction = transaction;
  }
  if (operation === 0x1001) {
    if (parameters.length) return reject(socket, transaction, 0x201d, 'GetDeviceInfo parameters');
    session.deviceInfoRead = true;
    session.freshImportInfo = true;
    if (options.pictureControlImportSynthetic && session.open) {
      session.capabilityRead = false; session.slotReads.clear(); session.slotReadProof.clear();
    }
    if (options.pictureControlImportSynthetic && session.open) log(`DEVICE_INFO_RECHECK session=${session.id} tx=${transaction}`);
    return transfer(socket, transaction, deviceInfo);
  }
  if (operation === 0x1002) {
    if (parameters.length !== 1 || parameters[0] !== 1) return reject(socket, transaction, 0x201d, 'OpenSession parameters');
    if (session.open) return reject(socket, transaction, 0x201e, 'session already open');
    if (options.pictureControlImportSynthetic && !session.deviceInfoRead) return reject(socket, transaction, 0x2003, 'GetDeviceInfo required before open');
    session.open = true;
    openCount++;
    if (options.pictureControlImportSynthetic) log(`OPEN_SESSION session=${session.id} tx=${transaction} opens=${openCount}`);
    return response(socket, transaction);
  }
  if (!session.open) return reject(socket, transaction, 0x2003, 'session not open');
  if (operation === 0x1003) {
    if (parameters.length) return reject(socket, transaction, 0x201d, 'CloseSession parameters');
    session.open = false;
    closeCount++;
    if (options.pictureControlImportSynthetic) {
      session.ended = true; acceptingInit = false;
      syntheticSummary(session, 'closed');
      log('SIMULATED_AP_CLOSED rejectNewInit=true loopbackListenerRetained=true');
    }
    if (options.pictureControlReadonly) {
      const matched = session.commands.length === pictureControlReadPlan.length
        && session.commands.every((value, index) => value === pictureControlReadPlan[index]);
      log(`PC_READONLY_SUMMARY session=${session.id} commands=${session.commands.length} expected=14 planMatched=${matched} uploads=0`);
    }
    return response(socket, transaction);
  }
  if (operation === 0x90cf) {
    if (parameters.length !== 1 || parameters[0] !== 1) {
      return reject(socket, transaction, 0x201d, 'GetPicCtrlCapability parameters');
    }
    session.capabilityRead = true;
    return transfer(socket, transaction, Buffer.alloc(28));
  }
  if (operation === 0x90cc) {
    if (parameters.length !== 2 || parameters[1] !== 0
        || !(parameters[0] === 1 || (parameters[0] >= 201 && parameters[0] <= 209))) {
      return reject(socket, transaction, 0x201d, 'GetPicCtrlData parameters');
    }
    let data = Buffer.alloc(36);
    if (parameters[0] === 1) {
      data[0] = 1;
      if (options.pictureControlImportSynthetic) {
        session.standardReads++;
        if (session.standardReads > 1) heartbeatCount++;
        log(`STANDARD_READ session=${session.id} tx=${transaction} count=${session.standardReads} heartbeats=${heartbeatCount}`);
        if (options.postImportHeartbeatErrorOnce && session.postImportHeartbeatErrorPending && !session.postImportHeartbeatErrorSent) {
          session.postImportHeartbeatErrorPending = false;
          session.postImportHeartbeatErrorSent = true;
          log(`SIMULATED_POST_IMPORT_HEARTBEAT_ERROR session=${session.id} tx=${transaction} code=0x2002 matchedTransaction=true exactReadbackBeforeError=true hardwareAccess=false`);
          return response(socket, transaction, 0x2002);
        }
      }
    } else if (options.pictureControlImportSynthetic) {
      session.slotReads.add(parameters[0]);
      data = virtualSlots.get(parameters[0]);
      const hash = crypto.createHash('sha256').update(data).digest('hex');
      if (session.awaitingImportReadback?.target === parameters[0] && session.awaitingImportReadback.sha256 === hash) {
        if (options.postImportHeartbeatErrorOnce && !session.postImportHeartbeatErrorSent) session.postImportHeartbeatErrorPending = true;
        session.awaitingImportReadback = null;
      }
      const earlier = session.slotReadProof.get(parameters[0]);
      session.slotReadProof.set(parameters[0], { firstHash: earlier?.firstHash || hash, lastHash: hash, count: (earlier?.count || 0) + 1 });
      if (data.length === 614) log(`VIRTUAL_READBACK session=${session.id} tx=${transaction} target=${parameters[0]} bytes=${data.length} sha256=${crypto.createHash('sha256').update(data).digest('hex')}`);
    }
    return transfer(socket, transaction, data);
  }
  if (syntheticWrite) {
    const target = parameters[0], current = virtualSlots.get(target);
    const occupied = current && current.readUInt16LE(0) !== 0;
    const proof = session.slotReadProof.get(target);
    const confirmedOverwrite = options.pictureControlOverwriteSynthetic && occupied && proof?.count >= 2
      && proof.firstHash === proof.lastHash && proof.lastHash === crypto.createHash('sha256').update(current).digest('hex');
    if (parameters.length !== 2 || parameters[0] < 201 || parameters[0] > 209 || parameters[1] !== 0
        || !session.capabilityRead || session.standardReads < 1 || session.slotReads.size !== 9
        || (!options.keepImportSession && session.writeAttempted) || virtualWrites >= maxVirtualWrites
        || (!options.pictureControlOverwriteSynthetic && attemptedTargets.has(parameters[0])) || (occupied && !confirmedOverwrite)
        || ((options.keepImportSession || options.pictureControlOverwriteSynthetic) && (!session.freshImportInfo || previousCommand?.operation !== 0x90cc
          || previousCommand.parameters.length !== 2 || previousCommand.parameters[0] !== parameters[0] || previousCommand.parameters[1] !== 0))) {
      return reject(socket, transaction, 0x201d, 'invalid synthetic registration target/order or duplicate write');
    }
    session.writeAttempted = true;
    attemptedTargets.add(parameters[0]);
    if (confirmedOverwrite) log(`VIRTUAL_OVERWRITE_CONFIRMED session=${session.id} tx=${transaction} target=${target} previousSha256=${proof.lastHash} previousName=${current.subarray(2, 22).toString('ascii').replace(/\0.*$/, '')} hardwareAccess=false`);
    session.pendingWrite = { transaction, target: parameters[0], started: false, parts: [], received: 0 };
    return;
  }
  if (operation === 0x1004) {
    if (parameters.length) return reject(socket, transaction, 0x201d, 'GetStorageIDs parameters');
    if (options.pictureControlAlbumImportSynthetic) log(`EMPTY_ALBUM_STORAGE session=${session.id} tx=${transaction} synthetic=true`);
    return transfer(socket, transaction, a32([storageId]));
  }
  if (operation === 0x1007) {
    if (parameters.length !== 3 || parameters[0] !== storageId
        || ![0, 0x3801].includes(parameters[1]) || parameters[2] !== 0) {
      return reject(socket, transaction, 0x201d, 'GetObjectHandles parameters');
    }
    if (options.pictureControlAlbumImportSynthetic) log(`EMPTY_ALBUM_HANDLES session=${session.id} tx=${transaction} photos=0 synthetic=true`);
    return transfer(socket, transaction, a32([...photos.keys()]));
  }
  if (parameters.length !== 1) return reject(socket, transaction, 0x201d, 'photo parameters');
  const handle = parameters[0], photo = photos.get(handle);
  if (!photo) return reject(socket, transaction, 0x2009, 'unknown synthetic handle');
  if (operation === 0x1008) return transfer(socket, transaction, objectInfo(handle, photo));
  if (operation === 0x100a) return transfer(socket, transaction, photo.thumbnail);
  if (operation === 0x90c4) {
    if (options.largeThumbReject) return reject(socket, transaction, 0x2005, 'synthetic GetLargeThumb explicit rejection');
    largePreviews++;
    log(`LARGE_PREVIEW session=${session.id} handle=${handle} bytes=${photo.large.length} totalLargePreviews=${largePreviews} source=0x90c4 originalTransferred=false`);
    return transfer(socket, transaction, photo.large);
  }
  originals++;
  log(`ORIGINAL session=${session.id} handle=${handle} bytes=${photo.original.length} totalOriginalTransfers=${originals}`);
  return transfer(socket, transaction, photo.original);
}
function packet(socket, type, body) {
  if (socket.channel === undefined) {
    if (type === 1 && body.length >= 22) {
      if (options.pictureControlImportSynthetic) {
        let nameEnd = 16;
        while (nameEnd + 1 < body.length - 4 && body.readUInt16LE(nameEnd) !== 0) nameEnd += 2;
        if (body.length % 2 !== 0 || nameEnd === 16 || nameEnd > 176 || nameEnd + 6 !== body.length
            || body.readUInt32LE(body.length - 4) !== 0x00010000) throw new Error('Malformed synthetic InitCommand');
      }
      if (options.pictureControlImportSynthetic && (!acceptingInit || connections.size !== 0)) {
        log(`INIT_REJECTED reason=${acceptingInit ? 'synthetic session already owned' : 'simulated AP closed'} connections=${connectionNumber} virtualWrites=${virtualWrites}`);
        send(socket, 5, u32(1)); socket.end(); return;
      }
      const session = { id: ++connectionNumber, command: socket, event: null, open: false, commands: [], lastTransaction: 0, ended: false,
        deviceInfoRead: false, capabilityRead: false, standardReads: 0, slotReads: new Set(), slotReadProof: new Map(), pendingWrite: null, writeAttempted: false,
        awaitingImportReadback: null, postImportHeartbeatErrorPending: false, postImportHeartbeatErrorSent: false };
      connections.set(session.id, session);
      socket.session = session; socket.channel = 'command';
      log(`INIT_COMMAND session=${session.id}`);
      return send(socket, 2, Buffer.concat([u32(session.id), Buffer.from(pictureControl ? 'SYNTHETIC-PC-RO!' : 'SYNTHETIC-ALBUM!', 'ascii'),
        Buffer.from('Nikon Synthetic\0', 'utf16le'), u32(0x00010000)]));
    }
    if (type === 3 && body.length === 4) {
      const session = connections.get(body.readUInt32LE(0));
      if (!session || session.event) throw new Error('Unknown event connection');
      session.event = socket; socket.session = session; socket.channel = 'event';
      log(`INIT_EVENT session=${session.id}`);
      return send(socket, 4);
    }
    throw new Error('Handshake required');
  }
  if (type === 13 && body.length === 0) return send(socket, 14);
  if (options.pictureControlImportSynthetic && socket.channel === 'command' && [9, 10, 12].includes(type)) return syntheticDataOut(socket, type, body);
  if (socket.channel !== 'command' || type !== 6) throw new Error('Only read commands are accepted');
  return command(socket, body);
}
const server = net.createServer(socket => {
  sockets.add(socket);
  socket.setNoDelay(true);
  let pending = Buffer.alloc(0);
  socket.on('data', chunk => {
    try {
      pending = Buffer.concat([pending, chunk]);
      if (pending.length > 65536) throw new Error('Inbound buffer limit');
      while (pending.length >= 8) {
        const length = pending.readUInt32LE(0), type = pending.readUInt32LE(4);
        if (length < 8 || length > 4096) throw new Error('Inbound frame length limit');
        if (pending.length < length) break;
        const body = pending.subarray(8, length);
        pending = pending.subarray(length);
        packet(socket, type, body);
      }
    } catch (error) {
      log(`CONNECTION_REJECTED ${error.message}`);
      socket.destroy();
    }
  });
  socket.on('error', error => log(`SOCKET_ERROR ${error.code || error.message}`));
  socket.on('close', () => {
    sockets.delete(socket);
    const session = socket.session;
    if (session && socket.channel === 'command') {
      connections.delete(session.id);
      if (session.event) session.event.destroy();
      if (options.pictureControlImportSynthetic) syntheticSummary(session, session.ended ? 'closed' : 'transport_closed');
      log(`CLOSED session=${session.id}`);
    }
  });
});
function stop(reason) {
  if (closing) return;
  closing = true;
  log(`STOP ${reason} totalOriginalTransfers=${originals} totalLargePreviews=${largePreviews}${options.pictureControlImportSynthetic ? ` virtualWrites=${virtualWrites} connections=${connectionNumber} opens=${openCount} closes=${closeCount} heartbeats=${heartbeatCount}` : ''}`);
  for (const socket of sockets) socket.destroy();
  server.close();
}
const timer = setTimeout(() => stop('automatic timeout'), options.seconds * 1000);
server.on('close', () => clearTimeout(timer));
server.on('error', error => { clearTimeout(timer); log(`SERVER_ERROR ${error.message}`); process.exitCode = 1; });
process.on('SIGINT', () => stop('SIGINT'));
process.on('SIGTERM', () => stop('SIGTERM'));
server.listen(options.port, '127.0.0.1', () => {
  log(`READY loopback=127.0.0.1:${options.port} emulator=10.0.2.2:${options.port} photos=${photos.size} ttl=${options.seconds}s log=${logFile}`);
  if (!pictureControl) log(`SYNTHETIC_TEST largeThumbnailAdvertised=${largeThumbnailAvailable} largeThumbnailRejected=${options.largeThumbReject} largePreviewSource=0x90c4 maxLargePreviewBytes=8388608`);
  if (options.pictureControlReadonly) log('SYNTHETIC_TEST mode=picture-control-readonly model=Z6II registrationAdvertised=false expectedCommands=14');
  if (options.pictureControlImportSynthetic) log(`SYNTHETIC_TEST mode=${options.pictureControlAlbumImportSynthetic ? 'picture-control-album-import-synthetic' : 'picture-control-import-synthetic'} model=Z6II registrationAdvertised=true memoryOnly=true expectedNcpDatasetBytes=614 maxVirtualWrites=${maxVirtualWrites} keepImportSession=${options.keepImportSession} rejectInitAfterClose=true emptyAlbum=${options.pictureControlAlbumImportSynthetic}`);
  if (options.pictureControlOverwriteSynthetic) log(`SYNTHETIC_TEST overwriteEnabled=true seededTarget=201 seededName=SYNTHETIC_OLD seededSha256=${crypto.createHash('sha256').update(virtualSlots.get(201)).digest('hex')} twoMatchingTargetReadsRequired=true`);
  if (options.postImportHeartbeatErrorOnce) log('SYNTHETIC_TEST postImportHeartbeatErrorOnce=true responseCode=0x2002 requiresExactReadback=true hardwareAccess=false');
});
