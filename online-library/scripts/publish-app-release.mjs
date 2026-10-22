// Explicit signed-artifact publication through the same authenticated admin API used by the UI.
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {inspectApk} from '../server/apk.mjs';
import {PUBLIC_ORIGIN} from './cloud-tools.mjs';
const options=process.argv.slice(2),channel=options.includes('--stable')?'stable':'beta';
if(!options.includes('--publish')||!options[0]?.endsWith('.apk'))throw Error('Usage: node scripts/publish-app-release.mjs <signed.apk> --publish [--stable]');
const apk=await readFile(options[0]),metadata=inspectApk(apk),base=PUBLIC_ORIGIN+'/preset-library';
const notesIndex=options.indexOf('--notes-file');
const releaseNotes=notesIndex>=0?(await readFile(options[notesIndex+1],'utf8')).trim():'照片套用和直播自动套用显示真实进度；导出可选 JPG 或相机原始 NEF，支持 RAW 文件浏览、原字节保存和失败重试。直播主图与照片整页滚动，新增返回顶部。在线预设按当前模板生成近似效果，支持 NCP 和可解析的 NP3；浏览不自动加入我的预设。预设下载显示加载状态，我的预设支持删除并检查项目默认引用。NP3 仍不支持导入相机。';
if(!releaseNotes||releaseNotes.length>2000)throw Error('Release notes must contain 1–2000 characters.');
const session=JSON.parse(await readFile(new URL('../.local/community-admin-session.json',import.meta.url),'utf8'));
const stateFile=new URL('../.local/app-release-publication-'+metadata.versionCode+'.json',import.meta.url);
let state;try{state=JSON.parse(await readFile(stateFile,'utf8'));}catch{state={};}
if(state.sha256&&state.sha256!==metadata.sha256)throw Error('Existing publication state belongs to another APK; review before starting a new publication.');
async function request(path,method='GET',body){
 const raw=Buffer.isBuffer(body);let response=await fetch(base+path,{method,headers:{Cookie:session.cookie,Origin:PUBLIC_ORIGIN,'X-CSRF-Token':session.csrfToken,
  ...(body===undefined?{}:{'Content-Type':raw?'application/octet-stream':'application/json'})},body:body===undefined?undefined:raw?body:JSON.stringify(body)});
 let result=await response.json();if(!response.ok)throw Error('Release API '+response.status+' '+result.error?.code);return result;
}
const save=()=>writeFile(stateFile,JSON.stringify(state,null,2),{mode:0o600});
state={...state,sha256:metadata.sha256,packageName:metadata.packageName,versionCode:metadata.versionCode,versionName:metadata.versionName,channel};await save();
let listing=await request('/api/releases'),existing=listing.items.find(row=>row.sha256===metadata.sha256);
if(existing){state.release=existing;await save();}
if(!state.release){
 if(!state.uploadId){const task=await request('/api/releases/uploads','POST',{byteLength:apk.length,channel,notes:releaseNotes});state.uploadId=task.uploadId;state.chunkBytes=task.chunkBytes;state.nextChunk=0;await save();}
 for(let index=state.nextChunk||0;index<Math.ceil(apk.length/state.chunkBytes);index++){
  await request('/api/releases/uploads/'+state.uploadId+'/chunks/'+index,'PUT',apk.subarray(index*state.chunkBytes,Math.min(apk.length,(index+1)*state.chunkBytes)));
  state.nextChunk=index+1;await save();console.log('Signed APK chunk '+(index+1)+' uploaded.');
 }
 state.release=(await request('/api/releases/uploads/'+state.uploadId+'/complete','POST',{})).release;await save();
}
if(state.release.status==='draft'){
 state.release=(await request('/api/releases/'+state.release.id+'/publish','POST',{expectedRevision:state.release.revision,confirmation:'PUBLISH '+metadata.versionCode+' '+metadata.sha256})).release;await save();
}
if(state.release.status!=='published')throw Error('Release is withdrawn; never republish it implicitly.');
const publicResponse=await fetch(state.release.downloadUrl),file=Buffer.from(await publicResponse.arrayBuffer());
if(!publicResponse.ok||file.length!==apk.length||createHash('sha256').update(file).digest('hex')!==metadata.sha256)throw Error('Public APK bytes do not match signed artifact.');
const latest=await(await fetch(base+'/releases/latest?channel='+channel+'&versionCode=53&sdk=35&packageName='+encodeURIComponent(metadata.packageName))).json();
if(!latest.updateAvailable||latest.release?.sha256!==metadata.sha256||latest.release?.packageName!==metadata.packageName)throw Error('Published update metadata mismatch.');
state.verifiedAt=new Date().toISOString();state.publicBytesVerified=true;await save();
console.log(JSON.stringify({published:true,packageName:metadata.packageName,version:metadata.versionName,versionCode:metadata.versionCode,channel,bytes:apk.length,sha256:metadata.sha256,downloadPage:base+'/download/'},null,2));
