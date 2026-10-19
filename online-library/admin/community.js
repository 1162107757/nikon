const labels={submissions:'投稿审核',comments:'评论管理',reports:'举报处理',settings:'风控规则',audit:'操作审计'};
const statusNames={draft:'草稿',pending:'待审核',published:'已通过',rejected:'已退回',withdrawn:'已下架',visible:'可见',hidden:'已隐藏',deleted:'已删除',open:'待处理',resolved:'已处理',dismissed:'已忽略',all:'全部状态'};
const statusOptions={submissions:['pending','published','rejected','withdrawn','draft','all'],comments:['pending','visible','hidden','deleted','all'],reports:['open','resolved','dismissed','all']};
const decisions={approve:'通过审核',reject:'退回修改',withdraw:'下架投稿',hide:'隐藏评论',restore:'恢复评论',resolve:'确认违规',dismiss:'忽略举报'};
const settingsLabels={newDaySubmissions:'新用户每日投稿',dailySubmissions:'普通用户每日投稿',pendingSubmissions:'每人待审核上限',newDayComments:'新用户每日评论',dailyComments:'普通用户每日评论',commentIntervalSeconds:'评论最小间隔（秒）',newDayUploads:'新用户每日上传',dailyUploads:'普通用户每日上传'};
const auditActions={adminReviewSubmission:'审核投稿',adminReviewComment:'审核评论',adminReviewReport:'处理举报',adminSetRestrictions:'修改用户限制',adminSetSettings:'修改风控规则'};
const UUID=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const date=value=>value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'尚未记录';
export function mountCommunity(container,{api,onError=()=>{}}={}){
  const request=api.request.bind(api),controller=new AbortController();let active='submissions',page=1,status='pending',serial=0,destroyed=false;
  const node=(tag,className='',text='')=>{const item=document.createElement(tag);item.className=className;item.textContent=text;return item;};
  const button=(text,action,style='button secondary')=>{const item=node('button',style,text);item.type='button';item.addEventListener('click',action,{signal:controller.signal});return item;};
  const shell=node('section','community-admin'),heading=node('div','page-heading'),copy=node('div');copy.append(node('h1','','社区管理'),node('p','','审核用户投稿与评论，处理举报，并维护社区发布规则。'));heading.append(copy,button('刷新',()=>load()));shell.append(heading);
  const tabs=node('nav','community-tabs');tabs.setAttribute('aria-label','社区管理栏目');
  for(const [key,label]of Object.entries(labels)){const tab=button(label,()=>{active=key;page=1;status=statusOptions[key]?.[0]||'all';load();},'community-tab');tab.dataset.tab=key;tabs.append(tab);}shell.append(tabs);
  const error=node('div','error-banner');error.hidden=true;error.setAttribute('role','alert');const body=node('div','community-body');shell.append(error,body);container.replaceChildren(shell);
  function report(err){if(destroyed)return;error.hidden=false;error.textContent=err.message||'暂时无法读取，请重试。';onError(err);}
  function assetUrl(id){return /^(ncp|preview)-[a-f0-9]{64}$/.test(id||'')?new URL(`../api/community/assets/${id}/content`,import.meta.url).href:'';}
  function authorName(item){return item.author?.nickname||'未命名用户';}
  function authorButton(item){const name=node('span','community-author',authorName(item));if(item.author?.id) return button(authorName(item),()=>restrictions(item.author.id),'community-author-link');return name;}
  async function load(){
    const ticket=++serial;error.hidden=true;body.replaceChildren(node('p','muted','正在读取…'));for(const tab of tabs.children)tab.setAttribute('aria-current',tab.dataset.tab===active?'page':'false');
    try{
      if(active==='settings'){const result=await request('/api/community/settings');if(ticket===serial&&!destroyed)renderSettings(result.settings);return;}
      const result=await request(`/api/community/${active}?status=${status}&page=${page}&pageSize=20`);if(ticket!==serial||destroyed)return;renderList(result);
    }catch(err){if(ticket!==serial||destroyed)return;body.replaceChildren();report(err);}
  }
  function renderList(result){
    body.replaceChildren();const toolbar=node('div','community-toolbar');
    if(statusOptions[active]){const select=node('select');select.setAttribute('aria-label','筛选状态');for(const value of statusOptions[active]){const option=node('option','',statusNames[value]);option.value=value;select.append(option);}select.value=status;select.addEventListener('change',()=>{status=select.value;page=1;load();},{signal:controller.signal});toolbar.append(select);}
    const total=Number(result.total)||0;toolbar.append(node('p','muted',`共 ${total} 条`));body.append(toolbar);
    const list=node('div','community-list');
    if(!result.items?.length){const empty=node('div','empty-state');empty.append(node('h2','','当前没有待处理内容'),node('p','','可以切换状态查看历史记录。'));list.append(empty);}
    for(const item of result.items||[]){
      const row=node('article','community-row'),main=node('div','community-row-main');
      if(active==='submissions'){
        const preview=assetUrl(item.previewAssetId||item.preview?.id);if(preview){const img=node('img','community-thumb');img.src=preview;img.alt=`${item.name} 效果图`;img.loading='lazy';img.addEventListener('error',()=>img.remove(),{once:true});row.append(img);}
        main.append(node('h2','',item.name||'未命名投稿'),node('p','community-description',item.description||'暂无简介'));
        const meta=node('div','community-meta');meta.append(authorButton(item),node('span','',`修订 ${item.revision} · ${date(item.updatedAt)}`));main.append(meta);
        const tags=node('div','community-tags');tags.append(node('span',`status ${item.status==='published'?'published':''}`,statusNames[item.status]||item.status));if(item.hasUnpublishedChanges)tags.append(node('span','muted','含未审核修改'));main.append(tags);row.append(main,button('查看并审核',()=>submission(item),'row-edit'));
      }else if(active==='comments'){
        main.append(node('p','community-comment',item.text||'内容已删除'));const meta=node('div','community-meta');meta.append(authorButton(item),node('span','',date(item.created_at||item.createdAt)),node('span','',statusNames[item.status]||item.status));main.append(meta);if(item.parent_id||item.parentId)main.append(node('p','muted','回复评论'));if(item.reason)main.append(node('p','community-reason',`处理说明：${item.reason}`));row.append(main);
        if(item.status==='pending'){
          const actions=node('div','community-row-actions');
          actions.append(button('通过审核',()=>review('comments',item,'restore'),'row-edit'),button('不予展示',()=>review('comments',item,'hide'),'row-edit'));
          row.append(actions);
        }else if(item.status==='visible'||item.status==='hidden')row.append(button(item.status==='visible'?'隐藏':'恢复',()=>review('comments',item,item.status==='visible'?'hide':'restore'),'row-edit'));
      }else if(active==='reports'){
        main.append(node('h2','',item.reason||'用户举报'),node('p','community-comment',item.comment?.text||'被举报的评论已不可用'));
        const meta=node('div','community-meta');meta.append(node('span','',`${statusNames[item.status]} · ${date(item.created_at)}`));if(item.comment?.author?.id)meta.append(authorButton(item.comment));main.append(meta);if(item.review_reason)main.append(node('p','community-reason',`处理说明：${item.review_reason}`));row.append(main);
        if(item.status==='open'){const actions=node('div','community-row-actions');actions.append(button('确认违规',()=>review('reports',item,'resolve'),'row-edit'),button('忽略',()=>review('reports',item,'dismiss'),'row-edit'));row.append(actions);}
      }else{
        main.append(node('h2','',auditActions[item.action]||item.action),node('p','community-description',`${item.actor} · ${date(item.created_at)}`));if(item.target)main.append(node('p','community-id',item.target));
        const detail=item.detail||{};if(detail.decision)main.append(node('p','',decisions[detail.decision]||detail.decision));if(detail.reason)main.append(node('p','community-reason',detail.reason));
        if(detail.after){for(const [key,value]of Object.entries(detail.after)){if(detail.before?.[key]!==value)main.append(node('p','community-reason',`${settingsLabels[key]||key}：${detail.before?.[key]??'未设置'} → ${value}`));}}
        row.append(main);
      }
      list.append(row);
    }
    body.append(list);const pages=Math.max(1,Math.ceil(total/20));
    if(pages>1){const pagination=node('nav','pagination');pagination.setAttribute('aria-label','社区列表分页');const previous=button('上一页',()=>{page--;load();}),next=button('下一页',()=>{page++;load();});previous.disabled=page<=1;next.disabled=page>=pages;pagination.append(previous,node('span','muted',`${page} / ${pages}`),next);body.append(pagination);}
  }
  function dialog(title){const box=node('dialog','community-dialog'),heading=node('div','community-dialog-heading'),h=node('h2','',title);h.id=`community-dialog-${Date.now()}`;box.setAttribute('aria-labelledby',h.id);const close=button('关闭',()=>box.close(),'button quiet');heading.append(h,close);box.append(heading);box.addEventListener('close',()=>box.remove(),{once:true});shell.append(box);box.showModal();close.focus();return box;}
  async function submission(item){
    const box=dialog('查看投稿'),content=node('div');content.textContent='正在读取最新内容…';box.append(content);
    try{
      const current=await request(`/api/community/submissions/${item.id}`);if(!box.isConnected)return;content.replaceChildren();
      const title=node('h3','',current.name||'未命名投稿'),preview=assetUrl(current.previewAssetId||current.preview?.id);content.append(title);
      if(preview){const img=node('img','community-detail-preview');img.src=preview;img.alt=`${current.name} 效果图`;content.append(img);}
      const meta=node('p','community-detail-meta',`${statusNames[current.status]} · 修订 ${current.revision} · ${current.category||'未分类'}`);content.append(meta,node('p','community-detail-description',current.description||'暂无简介'));
      const models=Array.isArray(current.compatibleModels)?current.compatibleModels:[];if(models.length)content.append(node('p','muted',`兼容机型：${models.join('、')}`));
      const download=assetUrl(current.fileAssetId||current.file?.id);if(download){const link=node('a','community-file','下载投稿 NCP');link.href=download;link.download='';content.append(link);}
      const author=node('div','community-meta');author.append(node('span','','投稿人：'),authorButton(current));content.append(author);
      if(current.reason)content.append(node('p','community-reason',`上次处理说明：${current.reason}`));
      const actions=node('div','community-dialog-actions');
      if(current.status==='pending')actions.append(button('通过审核',()=>review('submissions',current,'approve',box),'button'),button('退回修改',()=>review('submissions',current,'reject',box)));
      if(current.publishedRevision||current.status==='published')actions.append(button('下架投稿',()=>review('submissions',current,'withdraw',box),'button danger'));
      content.append(actions);
      const versions=node('details','community-versions');versions.append(node('summary','','历史版本与本次审核修订'));const versionBody=node('div');versions.append(versionBody);content.append(versions);
      versions.addEventListener('toggle',async()=>{if(!versions.open||versionBody.childNodes.length)return;versionBody.textContent='正在读取…';try{const result=await request(`/api/community/submissions/${current.id}/versions`);versionBody.replaceChildren();for(const version of result.items||[]){const entry=node('div','community-version');entry.append(node('strong','',`修订 ${version.revision}${version.revision===current.publishedRevision?' · 当前公开':''}${version.revision===current.revision?' · 当前稿件':''}`),node('p','muted',date(version.createdAt)),node('p','',version.snapshot?.name||''),node('p','muted',version.snapshot?.description||''));versionBody.append(entry);}}catch(err){versionBody.textContent=err.message;}},{signal:controller.signal});
    }catch(err){content.textContent=err.message;onError(err);}
  }
  function review(resource,item,decision,parent=null){
    const caption=resource==='comments'&&item.status==='pending'?(decision==='restore'?'通过评论审核':'不予展示评论'):decisions[decision];
    const box=dialog(caption),form=node('form'),description=resource==='submissions'?`审核修订 ${item.revision}：${item.name}`:resource==='comments'?item.text:item.reason;
    form.append(node('p','community-review-description',description||''));
    if(resource==='reports'&&decision==='resolve')form.append(node('p','muted','确认违规会隐藏被举报评论，并记录处理结果。'));
    const reason=node('textarea');reason.rows=4;reason.maxLength=500;reason.required=decision!=='approve';reason.id=`review-reason-${item.id}`;const field=node('div','field'),label=node('label','',decision==='approve'?'审核备注（选填）':'处理说明');label.htmlFor=reason.id;field.append(label,reason);form.append(field);
    const feedback=node('p','field-error');feedback.setAttribute('role','alert');const submit=node('button',decision==='approve'||decision==='restore'?'button':'button danger',`确认${caption}`);submit.type='submit';form.append(feedback,submit);box.append(form);
    form.addEventListener('submit',async event=>{event.preventDefault();if(!form.reportValidity())return;submit.disabled=true;feedback.textContent='';try{await request(`/api/community/${resource}/${item.id}/review`,{method:'POST',body:{decision,expectedRevision:item.revision,reason:reason.value.trim()}});box.close();parent?.close();load();}catch(err){feedback.textContent=err.message;onError(err);submit.disabled=false;}},{signal:controller.signal});reason.focus();
  }
  function renderSettings(settings){
    body.replaceChildren();const form=node('form','community-settings');form.append(node('h2','','发布与互动限额'),node('p','muted','新用户指注册未满 24 小时的账号。次数按服务端规则累计；上传失败也会消耗上传名额。'));
    const fields=node('div','community-settings-grid'),inputs={};
    for(const [key,label]of Object.entries(settingsLabels)){const field=node('div','field'),labelNode=node('label','',label),input=node('input');input.type='number';input.min='0';input.max='1000';input.step='1';input.required=true;input.value=String(settings[key]);input.id=`setting-${key}`;labelNode.htmlFor=input.id;field.append(labelNode,input);fields.append(field);inputs[key]=input;}form.append(fields);
    const feedback=node('p','field-error');feedback.setAttribute('role','status');const save=node('button','button','保存规则');save.type='submit';form.append(feedback,save);body.append(form);
    form.addEventListener('submit',async event=>{event.preventDefault();if(!form.reportValidity())return;save.disabled=true;feedback.textContent='';feedback.dataset.state='';try{await request('/api/community/settings',{method:'PUT',body:{settings:Object.fromEntries(Object.entries(inputs).map(([key,input])=>[key,Number(input.value)]))}});feedback.dataset.state='success';feedback.textContent='规则已保存，新的请求立即生效。';}catch(err){feedback.dataset.state='error';feedback.textContent=err.message;onError(err);}finally{save.disabled=false;}},{signal:controller.signal});
    const lookup=node('form','community-restriction-lookup'),id=node('input');id.type='text';id.placeholder='输入用户 ID';id.required=true;id.setAttribute('aria-label','用户 ID');const lookupButton=node('button','button secondary','查看用户限制');lookupButton.type='submit';lookup.append(node('h2','','用户限制'),node('p','muted','可在投稿或评论中点击用户名，查看并调整该账号的禁言与投稿限制。'),id,lookupButton);body.append(lookup);
    lookup.addEventListener('submit',event=>{event.preventDefault();if(!UUID.test(id.value.trim())){report(new Error('请填写完整的用户 ID。'));return;}restrictions(id.value.trim());},{signal:controller.signal});
  }
  async function restrictions(userId){
    const box=dialog('用户社区限制'),content=node('div');content.textContent='正在读取…';box.append(content);
    try{
      const current=await request(`/api/community/users/${userId}/restrictions`);if(!box.isConnected)return;content.replaceChildren(node('p','community-id',userId));
      const form=node('form'),fields={};
      for(const [key,label]of [['mutedUntil','禁言至'],['submissionBlockedUntil','禁止投稿至']]){const field=node('div','field'),labelNode=node('label','',label),input=node('input');input.type='datetime-local';input.id=`restriction-${key}`;labelNode.htmlFor=input.id;if(current[key]){const dt=new Date(current[key]);dt.setMinutes(dt.getMinutes()-dt.getTimezoneOffset());input.value=dt.toISOString().slice(0,16);}fields[key]=input;field.append(labelNode,input,node('small','','留空表示解除这项限制。'));form.append(field);}
      const field=node('div','field'),reason=node('textarea'),label=node('label','','处理说明');reason.required=true;reason.maxLength=500;reason.rows=3;reason.value=current.reason||'';reason.id='restriction-reason';label.htmlFor=reason.id;field.append(label,reason);form.append(field);
      const feedback=node('p','field-error');feedback.setAttribute('role','alert');const submit=node('button','button','保存用户限制');submit.type='submit';form.append(feedback,submit);content.append(form);
      form.addEventListener('submit',async event=>{event.preventDefault();if(!form.reportValidity())return;submit.disabled=true;try{await request(`/api/community/users/${userId}/restrictions`,{method:'POST',body:{mutedUntil:fields.mutedUntil.value?new Date(fields.mutedUntil.value).toISOString():null,submissionBlockedUntil:fields.submissionBlockedUntil.value?new Date(fields.submissionBlockedUntil.value).toISOString():null,reason:reason.value.trim()}});box.close();load();}catch(err){feedback.textContent=err.message;onError(err);submit.disabled=false;}},{signal:controller.signal});
    }catch(err){content.textContent=err.message;onError(err);}
  }
  load();return{refresh:load,destroy(){destroyed=true;serial++;controller.abort();for(const box of shell.querySelectorAll('dialog'))box.close();shell.remove();}};
}
