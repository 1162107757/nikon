// Two exact, short-lived test identities. No existing user records or public presets are changed.
import assert from'node:assert/strict';import{readFile,writeFile,mkdir}from'node:fs/promises';import{randomUUID}from'node:crypto';
import{CloudBaseStore,CloudBaseStorage}from'../server/cloudbase.mjs';import{randomToken,sha256}from'../server/auth.mjs';
import{executeSql,sqlRows,quoteSql,ENV_ID,PUBLIC_ORIGIN}from'./cloud-tools.mjs';
if(process.argv[2]!=='--verify')throw Error('Use --verify for owned isolated live fixtures.');
const key=JSON.parse(await readFile(new URL('../.local/service-credential.json',import.meta.url),'utf8'));assert.equal(key.environmentId,ENV_ID);
const store=new CloudBaseStore({environmentId:ENV_ID,serviceKey:key.serviceKey}),storage=new CloudBaseStorage(store);
const a=randomUUID(),b=randomUUID(),tokenA=randomToken(),tokenB=randomToken(),installation=randomUUID(),base=PUBLIC_ORIGIN+'/preset-library';
const bytes=Buffer.alloc(52);bytes.write('NCP');bytes.writeUInt32BE(1,4);bytes.writeUInt32BE(36,8);bytes.write('0100',12);const hash=sha256(bytes),path=`personal/${a}/${hash}.preset`,checks=[];let created=false;
async function request(method,path,token,body,status=200){const r=await fetch(base+path,{method,signal:AbortSignal.timeout(35000),headers:{...(token?{Authorization:'Bearer '+token}:{}),'X-Installation-Id':installation,...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined});assert.equal(r.status,status,method+' '+path+' status');const j=await r.json();checks.push({method,path,status:r.status});return j;}
try{
 executeSql(`DO $owned_personal_qa$ BEGIN INSERT INTO nikon_users(id,nickname) VALUES(${quoteSql(a)}::uuid,'Personal storage QA'),(${quoteSql(b)}::uuid,'Personal isolation QA');INSERT INTO nikon_user_sessions(token_hash,user_id,expires_at)VALUES(${quoteSql(sha256(tokenA))},${quoteSql(a)}::uuid,now()+interval '10 minutes'),(${quoteSql(sha256(tokenB))},${quoteSql(b)}::uuid,now()+interval '10 minutes');END $owned_personal_qa$;`);created=true;
 await request('GET','/personal/presets',null,undefined,401);
 const payload={displayName:'Owned private fixture',originalFileName:'fixture.NCP',category:'',description:'',fileBase64:bytes.toString('base64'),expectedRevision:0};
 const saved=await request('PUT','/personal/presets/'+hash,tokenA,payload);assert.equal(saved.revision,1);
 const own=await request('GET','/personal/presets/'+hash,tokenA);assert.deepEqual(Buffer.from(own.fileBase64,'base64'),bytes);
 assert.equal((await request('GET','/personal/presets',tokenB)).items.length,0);
 await request('GET','/personal/presets/'+hash,tokenB,undefined,404);
 const anonymous=await fetch(storage.publicUrl(path),{signal:AbortSignal.timeout(20000),redirect:'error'});assert.ok([401,403,404].includes(anonymous.status));checks.push({kind:'anonymous bucket object',status:anonymous.status});
 assert.equal((await request('PUT','/personal/presets/'+hash,tokenA,payload)).revision,1);
 await request('DELETE','/personal/presets/'+hash,tokenA,{expectedRevision:1});await request('GET','/personal/presets/'+hash,tokenA,undefined,404);
 const list=await request('GET','/personal/presets',tokenA);assert.equal(list.items[0].deleted,true);
}finally{
 if(created){try{await store.request('/v1/storages/object/nikon-presets/'+path,{method:'DELETE'});}finally{executeSql(`DO $owned_personal_cleanup$ BEGIN DELETE FROM nikon_personal_uploads WHERE user_id IN(${quoteSql(a)}::uuid,${quoteSql(b)}::uuid);DELETE FROM nikon_personal_presets WHERE user_id IN(${quoteSql(a)}::uuid,${quoteSql(b)}::uuid);DELETE FROM nikon_user_sessions WHERE user_id IN(${quoteSql(a)}::uuid,${quoteSql(b)}::uuid);DELETE FROM nikon_users WHERE id IN(${quoteSql(a)}::uuid,${quoteSql(b)}::uuid);END $owned_personal_cleanup$;`);}
 assert.equal(String(sqlRows(executeSql(`SELECT count(*) n FROM nikon_users WHERE id IN(${quoteSql(a)}::uuid,${quoteSql(b)}::uuid)`))[0].n),'0');}
}
await mkdir(new URL('../../docs/validation/personal-presets-61/',import.meta.url),{recursive:true});await writeFile(new URL('../../docs/validation/personal-presets-61/live.json',import.meta.url),JSON.stringify({environment:ENV_ID,verifiedAt:new Date().toISOString(),checks,exactFixtureIdentitiesRemoved:true,exactPrivateObjectRemoved:true},null,2));
console.log(`PASS: ${checks.length} live personal storage/isolation checks, owned identities and private object removed.`);
