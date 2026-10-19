import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { inspectApk, APK_SIGNER_SHA256, MAX_APK_BYTES } from '../server/apk.mjs';

const apkPath = new URL('../../dist/nikon-usb-0.9.14-beta-debug.apk', import.meta.url);
let apk;
try { apk = await readFile(apkPath); } catch { /* Existing build artifact is optional in a source-only checkout. */ }
function signingBlocks(buffer) {
  let end = buffer.length-22; while(buffer.readUInt32LE(end)!==0x06054b50)end--;
  const central=buffer.readUInt32LE(end+16), start=central-Number(buffer.readBigUInt64LE(central-24))-8, blocks=new Map();
  for(let at=start+8;at<central-24;){const size=Number(buffer.readBigUInt64LE(at));at+=8;blocks.set(buffer.readUInt32LE(at),{at,size});at+=size;}
  return {end,central,start,blocks};
}
test('existing signed APK yields server-parsed manifest, pinned identity, full-file SHA and both verified schemes', {skip:!apk},()=>{
  const result=inspectApk(apk);
  assert.equal(result.packageName,'com.nikonpreset.usbprobe');assert.equal(result.versionName,'0.9.14-beta');assert.equal(result.versionCode,53);assert.equal(result.minSdk,26);
  assert.equal(result.signerSha256,APK_SIGNER_SHA256);assert.deepEqual(result.signatureSchemes,[2,3]);assert.equal(result.byteLength,apk.length);
  assert.equal(result.sha256,createHash('sha256').update(apk).digest('hex'));
});
test('valid certificate is insufficient: modifications to protected content, v2 signature and v3 signature fail', {skip:!apk},()=>{
  const geometry=signingBlocks(apk);
  for(const mutation of [buffer=>{buffer[500]^=1;},buffer=>{buffer[geometry.end+20]^=1;},...Array.from(geometry.blocks).filter(([id])=>[0x7109871a,0xf05368c0].includes(id)).map(([id,block])=>buffer=>{
    let cursor=block.at+4+8;const signedSize=buffer.readUInt32LE(cursor);cursor+=4+signedSize;if(id===0xf05368c0)cursor+=8;
    cursor+=4;const recordSize=buffer.readUInt32LE(cursor);buffer[cursor+4+recordSize-1]^=1;
  })]){const changed=Buffer.from(apk);mutation(changed);assert.throws(()=>inspectApk(changed),error=>error.code==='INVALID_APK');}
});
test('v3 stripping, unpinned signer, wrong package, oversized input and truncated archives are rejected', {skip:!apk},()=>{
  assert.throws(()=>inspectApk(apk,{signerSha256:'0'.repeat(64)}),/签名与现有/);
  assert.throws(()=>inspectApk(apk,{packageName:'not.huevia'}),/包名/);
  const stripped=Buffer.from(apk),block=signingBlocks(apk).blocks.get(0xf05368c0);stripped.writeUInt32LE(0x12345678,block.at);
  assert.throws(()=>inspectApk(stripped),/不支持|v3 签名被移除/);
  const extra=Buffer.from(apk),padding=signingBlocks(apk).blocks.get(0x42726577);extra.writeUInt32LE(0x12345678,padding.at);
  assert.throws(()=>inspectApk(extra),/不支持的签名或附加块/,'an injected unknown higher-scheme block cannot alter Android verifier selection');
  assert.throws(()=>inspectApk(Buffer.alloc(MAX_APK_BYTES+1)),/4 MiB/);
  for(const length of [0,8,100,apk.length-1,apk.length-22])assert.throws(()=>inspectApk(apk.subarray(0,length)),error=>error.code==='INVALID_APK');
});
