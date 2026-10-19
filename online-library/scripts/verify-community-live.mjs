// Explicit owned-fixture acceptance. Credentials remain in ignored .local; no test content is published.
import {readFile,writeFile} from 'node:fs/promises';
import {randomUUID,createHash} from 'node:crypto';
import {PUBLIC_ORIGIN,executeSql,sqlRows,quoteSql} from './cloud-tools.mjs';
const base=PUBLIC_ORIGIN+'/preset-library', local=new URL('../.local/',import.meta.url);
const state=JSON.parse(await readFile(new URL('community-live-qa.json',local),'utf8'));
const admin=JSON.parse(await readFile(new URL('community-admin-session.json',local),'utf8'));
if(!/^qa_community_[a-f0-9]{16}$/.test(state.username)||!state.userId)throw Error('Owned fixture marker missing');
const checks=[];
const assert=(value,label)=>{if(!value)throw Error(label);checks.push(label);};
const save=()=>writeFile(new URL('community-live-qa.json',local),JSON.stringify(state,null,2),{mode:0o600});
async function request(path,{method='GET',body,raw,auth=true,manager=false}={}){
 const response=await fetch(base+path,{method,headers:{Accept:'application/json',...(body===undefined&&raw===undefined?{}:{'Content-Type':raw?'application/octet-stream':'application/json'}),
  ...(auth?{Authorization:'Bearer '+state.accessToken,'X-Installation-Id':state.installationId}:{}),...(manager?{Cookie:admin.cookie,Origin:PUBLIC_ORIGIN,'X-CSRF-Token':admin.csrfToken}:{})},body:raw??(body===undefined?undefined:JSON.stringify(body))});
 const value=await response.json();return {status:response.status,value};
}
if(process.argv.includes('--cleanup')){
 // Only exact random QA identities owned by this run may be removed. Never purge shared objects.
 const id=quoteSql(state.userId),name=quoteSql(state.username);
 const result=executeSql(`DO $owned_cleanup$ DECLARE uid uuid; BEGIN SELECT u.id INTO uid FROM public.nikon_users u JOIN public.nikon_password_credentials c ON c.user_id=u.id WHERE u.id=${id}::uuid AND u.nickname=${name} AND c.username=${name} FOR UPDATE; IF uid IS NULL THEN RETURN; END IF; DELETE FROM public.nikon_community_messages WHERE user_id=uid; DELETE FROM public.nikon_community_reports WHERE user_id=uid; DELETE FROM public.nikon_community_versions WHERE preset_id IN(SELECT id FROM public.nikon_community_submissions WHERE user_id=uid); DELETE FROM public.nikon_community_idempotency WHERE user_id=uid; DELETE FROM public.nikon_community_activity WHERE user_id=uid; DELETE FROM public.nikon_community_submissions WHERE user_id=uid; DELETE FROM public.nikon_community_assets WHERE user_id=uid; DELETE FROM public.nikon_user_sessions WHERE user_id=uid; DELETE FROM public.nikon_password_credentials WHERE user_id=uid AND username=${name}; DELETE FROM public.nikon_users WHERE id=uid; END $owned_cleanup$; SELECT count(*)::int AS remaining FROM public.nikon_users WHERE id=${id}::uuid;`);
 if(sqlRows(result).some(row=>Number(row.remaining)!==0))throw Error('Fixture cleanup incomplete');
 state.cleanedAt=new Date().toISOString();delete state.password;delete state.accessToken;await save();
 console.log('Owned community QA account and private draft records removed.');process.exit(0);
}
let r=await request('/auth/me');assert(r.status===200&&r.value.user.id===state.userId,'Registered account session works');
r=await request('/auth/register',{auth:false,method:'POST',body:{...state,challengeAnswer:'7XH5F'}});assert(r.status===400&&r.value.error?.code==='CHALLENGE_INVALID','Consumed challenge cannot be replayed');
r=await request('/community/messages',{auth:false});assert(r.status===401,'Guest cannot read another account messages');
r=await request('/community/messages/unread');assert(r.status===200&&r.value.unread===0,'New account unread messages are empty');
const preset=Buffer.alloc(36);preset.write('0100');Buffer.from(state.installationId.replaceAll('-','').slice(0,20)).copy(preset,8);
const head=Buffer.alloc(8);head.writeUInt32BE(1);head.writeUInt32BE(preset.length,4);
const ncp=Buffer.concat([Buffer.from([78,67,80,0]),head,preset,Buffer.alloc(4)]);
r=await request('/community/uploads?kind=ncp&filename=owned-qa.NCP',{method:'POST',raw:ncp});assert(r.status===201&&r.value.asset?.id,'Valid NCP upload accepted into private storage');
state.assetId=r.value.asset.id;state.assetUrl=r.value.asset.url;await save();
let file=await fetch(state.assetUrl);assert(file.status===404,'Unreviewed uploaded file has no public download');
const body={name:'Owned QA '+state.username,description:'Private deployment acceptance; never published.',category:'测试',compatibleModels:[],rightsConfirmed:true,fileAssetId:state.assetId,previewAssetId:null,idempotencyKey:state.saveKey||randomUUID()};
state.saveKey=body.idempotencyKey;await save();
r=await request('/community/submissions',{method:'POST',body});assert(r.status===201&&r.value.submission?.status==='draft','Draft persists server-side');
state.submissionId=r.value.submission.id;state.revision=r.value.submission.revision;await save();
r=await request('/community/submissions',{method:'POST',body});assert(r.status===201&&r.value.submission?.id===state.submissionId,'Same idempotency key returns the same draft');
r=await request('/community/submissions',{method:'POST',body:{...body,idempotencyKey:randomUUID()}});assert(r.status===409&&r.value.error?.code==='DUPLICATE_SUBMISSION','Same preset under another key cannot create duplicate drafts');
const submit={expectedRevision:state.revision,idempotencyKey:state.submitKey||randomUUID()};state.submitKey=submit.idempotencyKey;await save();
r=await request('/community/submissions/'+state.submissionId+'/submit',{method:'POST',body:submit});assert(r.status===200&&r.value.submission?.status==='pending','Submit enters pending review');
r=await request('/community/feed?page=1&pageSize=20',{auth:false});assert(r.status===200&&!r.value.items.some(row=>row.id===state.submissionId),'Pending submission excluded from public feed');
r=await request('/api/community/submissions/'+state.submissionId+'/review',{method:'POST',manager:true,body:{decision:'reject',expectedRevision:state.revision,reason:'Owned deployment acceptance, never publish.'}});assert(r.status===200,'Administrator can review private owned submission');
r=await request('/community/messages');assert(r.status===200&&r.value.items.some(row=>row.submissionId===state.submissionId),'Review creates a persistent recipient message');
r=await request('/community/messages/read-all',{method:'POST',body:{}});assert(r.status===200,'Account can mark all review messages read');
r=await request('/community/messages/unread');assert(r.status===200&&r.value.unread===0,'Read state persists in backend');
state.verifiedAt=new Date().toISOString();state.checks=checks;await save();
console.log(JSON.stringify({scope:'Live CloudBase; one exact owned random QA account, private draft only, no publication',checks},null,2));
