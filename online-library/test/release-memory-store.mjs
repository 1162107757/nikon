import { randomUUID } from 'node:crypto';
import { MemoryStore } from './memory-store.mjs';
export class ReleaseMemoryStore extends MemoryStore {
  constructor(){super();this.releaseRows=[];this.uploads=new Map();this.releaseAudits=[];this.releaseBudgetDenied=false;this.uploadBudgetBytes=0;this.uploadBudgetRequests=0;}
  async rest(path){
    const url=new URL(path,'https://test.invalid/'),params=url.searchParams;
    if(url.pathname==='nikon_app_release_audit'||url.pathname==='/nikon_app_release_audit')return this.releaseAudits.filter(row=>row.release_id===params.get('release_id')?.slice(3));
    if(url.pathname.endsWith('nikon_app_releases')){
      let rows=this.releaseRows.filter(row=>(!params.has('id')||row.id===params.get('id').slice(3))&&(!params.has('channel')||row.channel===params.get('channel').slice(3))&&(!params.has('package_name')||row.package_name===params.get('package_name').slice(3))&&(!params.has('status')||['published','withdrawn'].includes(row.status)));
      return rows.sort((a,b)=>b.version_code-a.version_code).slice(0,Number(params.get('limit')||200)).map(row=>({...row}));
    }throw new Error(`unexpected REST ${path}`);
  }
  async rpc(name,p){
    const error=error=>({error});
    if(name==='nikon_release_budget_atomic')return this.releaseBudgetDenied?error('BUDGET'):{allowed:true};
    if(!this.admins.has(p.p_actor)||this.admins.get(p.p_actor).disabled)return error('FORBIDDEN');
    if(name==='nikon_app_upload_budget'){
      if((p.p_count_request&&this.uploadBudgetRequests>=256)||this.uploadBudgetBytes+p.p_bytes>67108864)return error('BUDGET');
      this.uploadBudgetRequests+=p.p_count_request?1:0;this.uploadBudgetBytes+=p.p_bytes;return{allowed:true};
    }
    if(name==='nikon_app_upload_begin'){const id=randomUUID();this.uploads.set(id,{id,actor:p.p_actor,byteLength:p.p_size,notes:p.p_notes,channel:p.p_channel,status:'uploading',chunks:[]});return{uploadId:id,expiresAt:new Date(Date.now()+3600000).toISOString()};}
    const upload=this.uploads.get(p.p_id||p.p_upload_id);
    if(name.startsWith('nikon_app_upload_')){
      if(!upload||upload.actor!==p.p_actor||upload.status!=='uploading')return error('UPLOAD_EXPIRED');
      if(name==='nikon_app_upload_check')return p.p_size===Math.min(524288,upload.byteLength-p.p_index*524288)?{allowed:true}:error('INVALID');
      if(['nikon_app_upload_chunk','nikon_app_upload_reserve'].includes(name)){
        if(p.p_size!==Math.min(524288,upload.byteLength-p.p_index*524288))return error('INVALID');
        const existing=upload.chunks[p.p_index];if(existing&&existing.sha256!==p.p_sha256)return error('CONFLICT');
        upload.chunks[p.p_index]={index:p.p_index,size:p.p_size,sha256:p.p_sha256,path:p.p_path,ready:name==='nikon_app_upload_chunk'||existing?.ready===true};return{uploaded:true};
      }
      if(name==='nikon_app_upload_claim'){if(upload.chunks.filter(c=>c?.ready).reduce((sum,c)=>sum+c.size,0)!==upload.byteLength)return error('INCOMPLETE');upload.status='processing';return upload;}
    }
    if(name==='nikon_app_release_create'){
      const m=p.p_metadata;if(this.releaseRows.some(row=>row.version_code===m.versionCode||row.sha256===m.sha256))return error('DUPLICATE');
      if(!upload||upload.actor!==p.p_actor||upload.status!=='processing')return error('UPLOAD_EXPIRED');upload.status='complete';
      const row={id:randomUUID(),package_name:m.packageName,version_name:m.versionName,version_code:m.versionCode,min_sdk:m.minSdk,byte_length:m.byteLength,sha256:m.sha256,signer_sha256:m.signerSha256,storage_path:p.p_path,channel:upload.channel,notes:upload.notes,status:'draft',revision:1,created_at:new Date().toISOString(),published_at:null,withdrawn_at:null};
      this.releaseRows.push(row);this.releaseAudits.push({release_id:row.id,actor:p.p_actor,action:'upload_verified'});return{...row};
    }
    if(name==='nikon_app_release_action'){
      const row=this.releaseRows.find(row=>row.id===p.p_id);if(!row)return error('NOT_FOUND');if(p.p_revision!==row.revision)return error('CONFLICT');
      if(p.p_action==='publish'){
        if(row.status!=='draft')return error('CONFLICT');if(p.p_confirmation!==`PUBLISH ${row.version_code} ${row.sha256}`)return error('CONFIRMATION');
        if(this.releaseRows.some(other=>other.channel===row.channel&&['published','withdrawn'].includes(other.status)&&other.version_code>=row.version_code))return error('DOWNGRADE');
        row.status='published';row.published_at=new Date().toISOString();
      }else if(p.p_action==='withdraw'){if(row.status!=='published')return error('CONFLICT');if(p.p_confirmation!==`WITHDRAW ${row.version_code}`)return error('CONFIRMATION');row.status='withdrawn';row.withdrawn_at=new Date().toISOString();}
      else if(p.p_action==='edit'){if(row.status!=='draft')return error('INVALID');row.notes=p.p_notes;}else return error('INVALID');
      row.revision++;this.releaseAudits.push({release_id:row.id,actor:p.p_actor,action:p.p_action});return{...row};
    }
    throw new Error(`unexpected RPC ${name}`);
  }
}
