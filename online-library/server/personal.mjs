import {ServiceError} from './cloudbase.mjs';
import {sha256} from './auth.mjs';
import {validatePresetFile} from './validation.mjs';
const fail=(status,code,message)=>{throw new ServiceError(status,code,message);};
const text=(value,max,required=false)=>{
 if(typeof value!=='string'||/[\x00-\x1f\x7f-\x9f]/u.test(value)||[...value].length>max||required&&!value.trim())fail(400,'INVALID_PRESET','预设信息无效。');
 return value.normalize('NFC').trim();
};
export function createPersonalRoutes({store,storage,publicBaseUrl,abuseService}){
 return {async publicRoute(req,res,{path,url,json,readJson}){
  if(!path.startsWith('/personal/'))return false;
  const count=req.rawHeaders?.filter((v,i)=>i%2===0&&v.toLowerCase()==='authorization').length||0;
  const token=count<=1?/^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization||'')?.[1]:null;
  const session=token&&await store.userSession(sha256(token));
  if(!session?.user||session.user.status!=='active')fail(401,'USER_LOGIN_REQUIRED','请登录后同步我的预设。');
  const owner=session.user.id;
  if(!['GET','HEAD'].includes(req.method)){
   if(req.headers.origin&&req.headers.origin!==new URL(publicBaseUrl).origin)fail(403,'ORIGIN_REJECTED','请从 App 发起操作。');
   if(abuseService)await abuseService.protect(req,req.headers['x-installation-id'],'community-write');
  }
  res.setHeader('Cache-Control','no-store');
  if(path==='/personal/presets'&&req.method==='GET'){
   const after=url.searchParams.get('after')||'';
   if(after&&!/^[a-f0-9]{64}$/.test(after))fail(400,'INVALID_PRESET','分页标识无效。');
   json(res,await store.personal('list',owner,null,{after}));return true;
  }
  const match=/^\/personal\/presets\/([a-f0-9]{64})$/.exec(path);
  if(!match)fail(404,'NOT_FOUND','预设不存在。');
  const hash=match[1],objectPath=`personal/${owner}/${hash}.preset`;
  if(req.method==='GET'){
   const preset=await store.personal('get',owner,hash),data=await storage.get(objectPath,1048576);
   if(data.length!==preset.byteLength||sha256(data)!==hash)fail(503,'FILE_CHANGED','云端文件校验失败，请稍后重试。');
   json(res,{preset,fileBase64:data.toString('base64')});return true;
  }
  const body=await readJson(req,1600*1024);
  if(!Number.isSafeInteger(body.expectedRevision)||body.expectedRevision<0)fail(400,'REVISION_REQUIRED','请先同步云端版本。');
  if(req.method==='DELETE'){json(res,await store.personal('delete',owner,hash,{expectedRevision:body.expectedRevision}));return true;}
  if(req.method!=='PUT')fail(405,'METHOD_NOT_ALLOWED','操作不支持。');
  if(typeof body.fileBase64!=='string'||body.fileBase64.length>1398104||!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(body.fileBase64))fail(400,'INVALID_FILE','预设文件不完整。');
  const data=Buffer.from(body.fileBase64,'base64'),details=validatePresetFile(data);
  if(sha256(data)!==hash||data.toString('base64')!==body.fileBase64)fail(400,'INVALID_FILE','预设文件校验不一致。');
  const metadata={displayName:text(body.displayName,240,true),originalFileName:text(body.originalFileName,240,true),category:text(body.category,24),description:text(body.description,2000),
   fileFormat:details.fileFormat,ncpVersion:details.ncpVersion,byteLength:data.length};
  if(!metadata.originalFileName.toLowerCase().endsWith('.'+details.extension)||/[\\/]/.test(metadata.originalFileName))fail(400,'INVALID_FILE','文件名与实际预设格式不符。');
  await store.personal('reserve',owner,hash,{byteLength:data.length});
  await storage.put(objectPath,data,'application/octet-stream');
  json(res,await store.personal('put',owner,hash,{expectedRevision:body.expectedRevision,metadata}));return true;
 }};
}
