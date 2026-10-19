#!/usr/bin/env node
'use strict';

// Real framed TCP regression for the local-only fixture control path; no Android device.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');

const u16 = value => { const b = Buffer.alloc(2); b.writeUInt16LE(value); return b; };
const u32 = value => { const b = Buffer.alloc(4); b.writeUInt32LE(value); return b; };
const packet = (type, body = Buffer.alloc(0)) => Buffer.concat([u32(body.length + 8), u32(type), body]);
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
let checks = 0;
function equal(actual, expected) { assert.deepEqual(actual, expected); checks++; }
async function until(condition, message, timeout = 5000) {
  const deadline = Date.now() + timeout;
  while (!condition()) { if (Date.now() >= deadline) throw new Error(message); await pause(10); }
}
function channel(port) {
  const socket = net.connect(port, '127.0.0.1'), frames = [];
  let pending = Buffer.alloc(0);
  socket.on('data', chunk => {
    pending = Buffer.concat([pending, chunk]);
    while (pending.length >= 8 && pending.length >= pending.readUInt32LE(0)) {
      const length = pending.readUInt32LE(0);
      assert.ok(length >= 8 && length <= 16 * 1024 * 1024);
      frames.push({ type: pending.readUInt32LE(4), body: Buffer.from(pending.subarray(8, length)) });
      pending = pending.subarray(length);
    }
  });
  return { socket, frames, async next() { await until(() => frames.length || socket.destroyed, 'frame timed out'); assert.ok(frames.length); return frames.shift(); } };
}
async function freePort() {
  const server = net.createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
async function runScenario(storageSynthetic = false, baselineCount = 3) {
  const previousChecks = checks;
  const fixtures = fs.mkdtempSync(path.join(os.tmpdir(), 'album-live-server-'));
  const original = Buffer.from([255, 216, 11, 22, 33, 44, 55, 66, 255, 217]);
  for (let handle = 1; handle <= 49; handle++) {
    const suffix = String(handle).padStart(4, '0') + '.JPG';
    for (const prefix of ['TEST_', 'THUMB_', 'LARGE_']) fs.writeFileSync(path.join(fixtures, prefix + suffix), original);
  }
  const port = await freePort(), logFile = path.join(fixtures, 'server.log');
  const child = spawn(process.execPath, [path.join(__dirname, 'album-camera-server.js'), '--photo-live-synthetic', ...(storageSynthetic ? ['--live-storage-synthetic'] : []), ...(baselineCount !== 3 ? [`--live-baseline-count=${baselineCount}`] : []), `--fixtures=${fixtures}`, `--port=${port}`, '--seconds=30'], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = ''; child.stdout.on('data', b => { output += b; }); child.stderr.on('data', b => { output += b; });
  let command, event;
  try {
    await until(() => output.includes(' READY '), 'server did not become ready');
    command = channel(port);
    command.socket.write(packet(1, Buffer.concat([Buffer.alloc(16), Buffer.from('Live Test\0', 'utf16le'), u32(0x10000)])));
    const init = await command.next(); equal(init.type, 2); const session = init.body.readUInt32LE(0);
    event = channel(port); event.socket.write(packet(3, u32(session))); equal((await event.next()).type, 4);
    let transaction = 0;
    async function operation(code, parameters = []) {
      const tx = transaction; if (code === 0x1002 || transaction > 0) transaction++;
      command.socket.write(packet(6, Buffer.concat([u32(1), u16(code), u32(tx), ...parameters.map(u32)])));
      const parts = []; let response;
      do {
        response = await command.next();
        if (response.type === 10 || response.type === 12) { equal(response.body.readUInt32LE(0), tx); parts.push(response.body.subarray(4)); }
      } while (response.type !== 7);
      equal(response.body.readUInt32LE(2), tx);
      return { code: response.body.readUInt16LE(0), data: Buffer.concat(parts) };
    }
    await operation(0x1001); equal((await operation(0x1002, [1])).code, 0x2001);
    async function handles(storageId = 0x10001) {
      const result = await operation(0x1007, [storageId, 0x3801, 0]); equal(result.code, 0x2001);
      return Array.from({ length: result.data.readUInt32LE(0) }, (_, i) => result.data.readUInt32LE(4 + i * 4));
    }
    const control = value => fs.writeFileSync(path.join(fixtures, 'live-control.json'), JSON.stringify(value));
    const waitForLog = message => until(() => fs.readFileSync(logFile, 'utf8').includes(message), `missing server log: ${message}`);
    if (baselineCount > 3) {
      const storageId = storageSynthetic ? 0x20001 : 0x10001;
      const ids = await operation(0x1004); equal(ids.code, 0x2001);
      equal(ids.data, Buffer.concat([u32(storageSynthetic ? 2 : 1), u32(0x10001), ...(storageSynthetic ? [u32(0x20001)] : [])]));
      if (storageSynthetic) equal((await operation(0x1007, [0x10001, 0x3801, 0])).code, 0x2013);
      const initial = await handles(storageId);
      equal(initial.length, baselineCount); equal(initial.slice(0, 4), [1, 2, 3, 100]); equal(initial[initial.length - 1], baselineCount + 96);
      equal(new Set(initial).size, baselineCount); equal(initial.some(handle => handle >= 4 && handle <= 49), false);
      for (const handle of [100, baselineCount + 96]) {
        const result = await operation(0x1008, [handle]); equal(result.code, 0x2001); equal(result.data.readUInt32LE(0), storageId);
        const length = result.data[52];
        equal(result.data.subarray(53, 53 + (length - 1) * 2).toString('utf16le'), `BASE_${String(handle).padStart(5, '0')}.JPG`);
        equal(result.data.readUInt32LE(8), original.length);
      }
      equal(fs.readdirSync(fixtures).filter(file => file.endsWith('.JPG')).length, 147);
      const baselineLog = fs.readFileSync(logFile, 'utf8');
      equal((baselineLog.match(/op=0x1009 /g) || []).length, 0); equal((baselineLog.match(/ ORIGINAL /g) || []).length, 0);
      equal(baselineLog.includes(`initialPhotos=${baselineCount}`), true); equal(baselineLog.includes('additionalNetworkListeners=0'), true);

      control({ revision: 1, add: [{ handle: 4, events: 2 }] });
      await until(() => event.frames.length === 2, 'large-baseline ObjectAdded events missing');
      equal(event.frames.splice(0).map(frame => frame.body.readUInt32LE(6)), [4, 4]);
      const after = await handles(storageId); equal(after.length, baselineCount + 1); equal(after.slice(0, -1), initial); equal(after[after.length - 1], 4);
      equal((await operation(0x1008, [4])).data.readUInt32LE(0), storageId);
      equal((await operation(0x90c4, [4])).data, original); equal((await operation(0x1009, [4])).data, original);
      const log = fs.readFileSync(logFile, 'utf8');
      equal((log.match(/ ORIGINAL /g) || []).length, 1); equal((log.match(/ ORIGINAL session=\d+ handle=4 /g) || []).length, 1);
      equal((log.match(/ INIT_COMMAND /g) || []).length, 1); equal((log.match(/ OPEN_SESSION /g) || []).length, 1);
      equal((log.match(/op=0x1003 /g) || []).length, 0);
      console.log(`PASS: ${checks - previousChecks} large-baseline (${baselineCount} photos) synthetic server checks`);
      return;
    }
    if (storageSynthetic) {
      const first = 0x10001, second = 0x20001;
      const ids = await operation(0x1004); equal(ids.code, 0x2001);
      equal(ids.data, Buffer.concat([u32(2), u32(first), u32(second)]));
      const unavailable = await operation(0x1007, [first, 0x3801, 0]);
      equal(unavailable.code, 0x2013); equal(unavailable.data.length, 0);
      equal(await handles(second), [1, 2, 3]);
      equal((await operation(0x1008, [1])).data.readUInt32LE(0), second);

      // An available but empty slot differs from a physically unavailable advertised slot.
      control({ revision: 1, storages: [{ id: first, available: true }] });
      await waitForLog(`PHOTO_LIVE_STORAGE revision=1 storage=${first} available=true`);
      equal(await handles(first), []); equal(await handles(second), [1, 2, 3]);

      // Both slots may become unavailable without closing the PTP session. Preserve old
      // handles so the app can baseline the restored card rather than importing history.
      control({ revision: 2, storages: [{ id: first, available: false }, { id: second, available: false }], add: [{ handle: 4, events: 1 }] });
      await waitForLog('PHOTO_LIVE_ADD revision=2');
      const notification = await event.next(); equal(notification.type, 8); equal(notification.body.readUInt16LE(0), 0x4002); equal(notification.body.readUInt32LE(6), 4);
      equal((await operation(0x1007, [first, 0x3801, 0])).code, 0x2013);
      equal((await operation(0x1007, [second, 0x3801, 0])).code, 0x2013);
      for (const op of [0x1008, 0x90c4, 0x1009]) equal((await operation(op, [4])).code, 0x2013);
      control({ revision: 3, storages: [{ id: second, available: true }] });
      await waitForLog(`PHOTO_LIVE_STORAGE revision=3 storage=${second} available=true`);
      equal(await handles(second), [1, 2, 3, 4]);
      equal((await operation(0x1008, [4])).data.readUInt32LE(0), second);

      // A new, missed-event photo becomes visible only after the successful restored
      // inventory. A later photo can belong to the other slot, with duplicate events.
      control({ revision: 4, add: [{ handle: 5, events: 0 }] }); await waitForLog('PHOTO_LIVE_ADD revision=4');
      equal(await handles(second), [1, 2, 3, 4, 5]); equal(event.frames.length, 0);
      equal((await operation(0x90c4, [5])).data, original); equal((await operation(0x1009, [5])).data, original);
      control({ revision: 5, storages: [{ id: first, available: true }], add: [{ handle: 6, storageId: first, events: 2 }] });
      await until(() => event.frames.length === 2, 'dual-slot duplicate events missing');
      equal(event.frames.splice(0).map(frame => frame.body.readUInt32LE(6)), [6, 6]);
      equal(await handles(first), [6]); equal(await handles(second), [1, 2, 3, 4, 5]);
      equal((await operation(0x1008, [6])).data.readUInt32LE(0), first);
      equal((await operation(0x90c4, [6])).data, original); equal((await operation(0x1009, [6])).data, original);
      control({ revision: 6, storages: [{ id: second, available: false }] });
      await waitForLog(`PHOTO_LIVE_STORAGE revision=6 storage=${second} available=false`);
      equal(await handles(first), [6]); equal((await operation(0x1008, [5])).code, 0x2013);
      equal((await operation(0x1001)).code, 0x2001);

      // Invalid controls must not partly restore slots or admit any new handles.
      control({ revision: 7, storages: [{ id: second, available: true }, { id: second, available: false }], add: [{ handle: 7 }] });
      await waitForLog('PHOTO_LIVE_CONTROL_REJECTED live storages');
      equal((await operation(0x1007, [second, 0x3801, 0])).code, 0x2013); equal(await handles(first), [6]);
      equal(fs.readFileSync(logFile, 'utf8').includes('PHOTO_LIVE_ADD revision=7'), false);
      control({ revision: 7, storages: [{ id: second, available: true }] });
      await waitForLog(`PHOTO_LIVE_STORAGE revision=7 storage=${second} available=true`); equal(await handles(second), [1, 2, 3, 4, 5]);
      control({ revision: 8, add: [{ handle: 7, storageId: 0x30001 }] }); await waitForLog('PHOTO_LIVE_CONTROL_REJECTED live storageId');
      equal(await handles(first), [6]); equal(await handles(second), [1, 2, 3, 4, 5]);
      control({ revision: 8, add: [{ handle: 7, storageId: first, events: 0 }] }); await waitForLog('PHOTO_LIVE_ADD revision=8');
      equal(await handles(first), [6, 7]); equal((await operation(0x1008, [7])).data.readUInt32LE(0), first);
      const log = fs.readFileSync(logFile, 'utf8');
      equal((log.match(/ ORIGINAL /g) || []).length, 2);
      equal((log.match(/ INIT_COMMAND /g) || []).length, 1); equal((log.match(/ OPEN_SESSION /g) || []).length, 1);
      equal((log.match(/op=0x1003 /g) || []).length, 0); equal((log.match(/ ORIGINAL session=\d+ handle=[1-4] /g) || []).length, 0);
      console.log(`PASS: ${checks - previousChecks} dual-storage photo-live synthetic server checks`);
      return;
    }
    equal(await handles(), [1, 2, 3]);
    control({ revision: 1, add: [{ handle: 4, events: 2, unreadyInfo: 1, busyOriginal: 1 }, { handle: 5, events: 0 }] });
    await until(() => event.frames.length === 2, 'duplicate ObjectAdded events were not emitted');
    for (const notification of event.frames.splice(0)) {
      equal(notification.type, 8); equal(notification.body.readUInt16LE(0), 0x4002); equal(notification.body.readUInt32LE(2), 0xffffffff); equal(notification.body.readUInt32LE(6), 4);
    }
    equal(await handles(), [1, 2, 3, 4, 5]);
    equal((await operation(0x1008, [4])).code, 0x2009);
    equal((await operation(0x1008, [4])).code, 0x2001);
    equal((await operation(0x1009, [4])).code, 0x2019);
    equal((await operation(0x1009, [4])).data, original);
    const beforeInvalid = fs.readFileSync(logFile, 'utf8');
    control({ revision: 2, add: [{ handle: 6, events: 1 }, { handle: 99, events: 1 }] });
    await until(() => fs.readFileSync(logFile, 'utf8').includes('PHOTO_LIVE_CONTROL_REJECTED'), 'invalid local control was not reported');
    equal(await handles(), [1, 2, 3, 4, 5]); equal(event.frames.length, 0);
    equal(fs.readFileSync(logFile, 'utf8').includes('PHOTO_LIVE_ADD revision=2'), false);
    assert.ok(beforeInvalid.includes('PHOTO_LIVE_ADD revision=1')); checks++;
    control({ revision: 2, add: [{ handle: 6, events: 0 }] });
    await until(() => fs.readFileSync(logFile, 'utf8').includes('PHOTO_LIVE_ADD revision=2'), 'corrected control did not apply');
    equal(await handles(), [1, 2, 3, 4, 5, 6]); equal(event.frames.length, 0);
    equal((await operation(0x1009, [6])).data, original);
    const log = fs.readFileSync(logFile, 'utf8');
    equal((log.match(/ ORIGINAL /g) || []).length, 2);
    equal((log.match(/ INIT_COMMAND /g) || []).length, 1); equal((log.match(/ OPEN_SESSION /g) || []).length, 1);
    equal((log.match(/ OBJECT_ADDED_EVENT /g) || []).length, 2);
    console.log(`PASS: ${checks - previousChecks} photo-live synthetic server checks`);
  } finally {
    if (command) command.socket.destroy(); if (event) event.socket.destroy();
    child.kill(); await new Promise(resolve => child.exitCode !== null ? resolve() : child.once('exit', resolve));
    fs.rmSync(fixtures, { recursive: true, force: true });
  }
}
function baselineArgumentChecks() {
  const previousChecks = checks, script = path.join(__dirname, 'album-camera-server.js');
  for (const value of ['2', '19001', '3.5', 'NaN']) {
    const result = spawnSync(process.execPath, [script, '--photo-live-synthetic', `--live-baseline-count=${value}`], { windowsHide: true, encoding: 'utf8' });
    equal(result.status, 1); equal(result.stderr.includes('live-baseline-count must be 3..19000'), true);
  }
  const result = spawnSync(process.execPath, [script, '--live-baseline-count=10000'], { windowsHide: true, encoding: 'utf8' });
  equal(result.status, 1); equal(result.stderr.includes('--live-baseline-count requires --photo-live-synthetic'), true);
  console.log(`PASS: ${checks - previousChecks} large-baseline argument checks`);
}
async function main() { await runScenario(); await runScenario(true); await runScenario(true, 10000); baselineArgumentChecks(); }
main().catch(error => { console.error(error); process.exitCode = 1; });
