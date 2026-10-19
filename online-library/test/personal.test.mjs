import test from 'node:test';import assert from 'node:assert/strict';
import {createPersonalRoutes} from '../server/personal.mjs';import {sha256} from '../server/auth.mjs';
const user='11111111-1111-4111-8111-111111111111',other='22222222-2222-4222-8222-222222222222';
function ncp(){const b=Buffer.alloc(52);b.write('NCP');b.writeUInt32BE(1,4);b.writeUInt32BE(36,8);b.write('0100',12);return b;}
function fixture(){const calls=[],objects=new Map();let current=user;
 const store={userSession:async()=>({user:{id:current,status:'active'}}),personal:async(action,owner,hash,body)=>{calls.push({action,owner,hash,body});if(action==='get')return{byteLength:52};return action==='list'?{items:[]}:{revision:1,ok:true};}};
 const storage={put:async(path,bytes)=>objects.set(path,bytes),get:async(path)=>objects.get(path)||Buffer.alloc(0)};
 const route=createPersonalRoutes({store,storage,publicBaseUrl:'https://fixture.invalid',abuseService:{protect:async()=>{calls.push({action:'protected'});}}});
 async function request(path,method='GET',body={},auth=true,headers={}){let result;const responseHeaders={},url=new URL(path,'https://fixture.invalid');await route.publicRoute({method,headers:{...(auth?{authorization:'Bearer '+'a'.repeat(43)}:{}),...headers},rawHeaders:auth?['Authorization','Bearer '+'a'.repeat(43)]:[]},{setHeader:(k,v)=>responseHeaders[k]=v},{path:url.pathname,url,json:(_,value)=>result=value,readJson:async(_,max)=>{assert.ok(max<=1638400);return body;}});return{result,responseHeaders};}
 return{request,calls,objects,store,setUser:value=>current=value};
}
const payload=()=>({expectedRevision:0,displayName:'胶片',originalFileName:'film.NCP',description:'',category:'风景',fileBase64:ncp().toString('base64')});
test('personal library requires user bearer, rejects duplicate headers and hostile write origins',async()=>{const f=fixture();await assert.rejects(f.request('/personal/presets','GET',{},false),e=>e.status===401);await assert.rejects(f.request('/personal/presets/'+sha256(ncp()),'PUT',payload(),true,{origin:'https://hostile.invalid'}),e=>e.status===403);assert.equal(f.objects.size,0);});
test('personal files and metadata bind to session owner, stay private and validate original bytes',async()=>{const f=fixture(),hash=sha256(ncp()),p=payload();p.userId=other;p.fileFormat='NP3';p.url='https://public.invalid';
 await f.request('/personal/presets/'+hash,'PUT',p);const put=f.calls.find(c=>c.action==='put');assert.equal(put.owner,user);assert.equal(put.body.metadata.fileFormat,'NCP');assert.equal(put.body.metadata.byteLength,52);assert.ok(!('url'in put.body.metadata));assert.deepEqual([...f.objects.keys()],[`personal/${user}/${hash}.preset`]);
 const r=await f.request('/personal/presets/'+hash);assert.equal(r.result.fileBase64,p.fileBase64);assert.equal(r.responseHeaders['Cache-Control'],'no-store');
 f.setUser(other);await assert.rejects(f.request('/personal/presets/'+hash),e=>e.status===503);assert.equal(f.calls.at(-1).owner,other);
});
test('bad content, digest, filename or revision never writes a private object',async()=>{for(const [hash,change]of[['a'.repeat(64),{}],[sha256(ncp()),{fileBase64:'!!'}],[sha256(ncp()),{originalFileName:'../../x.NCP'}],[sha256(ncp()),{originalFileName:'x.NP3'}],[sha256(ncp()),{expectedRevision:-1}]]){const f=fixture();await assert.rejects(f.request('/personal/presets/'+hash,'PUT',{...payload(),...change}));assert.equal(f.objects.size,0);}});
test('private deletion is owner and revision bound; lists are cursor bounded',async()=>{const f=fixture(),hash=sha256(ncp());await f.request('/personal/presets/'+hash,'DELETE',{expectedRevision:8,userId:other});assert.deepEqual(f.calls.at(-1),{action:'delete',owner:user,hash,body:{expectedRevision:8}});await assert.rejects(f.request('/personal/presets?after=bad'),e=>e.status===400);await f.request('/personal/presets');assert.equal(f.calls.at(-1).action,'list');});
