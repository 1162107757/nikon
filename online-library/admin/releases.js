/** The host supplies its authenticated, CSRF-protected api.request function. */
export function mountReleases(container, { api, onError = () => {} } = {}) {
  if (typeof api?.request !== 'function') throw new Error('版本模块需要 api.request。');
  const request = api.request.bind(api), controller = new AbortController();
  const node = (tag, className = '', text = '') => { const value = document.createElement(tag); value.className = className; value.textContent = text; return value; };
  const button = (text, callback, className = 'button secondary') => { const value = node('button', className, text); value.type = 'button'; value.addEventListener('click', callback, { signal: controller.signal }); return value; };
  const formatSize = size => `${(size / 1048576).toFixed(2)} MiB`;
  const time = value => value ? new Date(value).toLocaleString('zh-CN', {hour12:false}) : '尚未上架';
  const statusLabels = {draft:'草稿', published:'已上架', withdrawn:'已撤回'};
  let data = null, busy = false, destroyed = false;
  const shell = node('section', 'releases'), heading = node('div', 'page-heading'), headingCopy = node('div');
  headingCopy.append(node('h1', '', 'App 版本'), node('p', '', '上传已签名 APK，核对真实版本信息，再上架供用户下载。'));
  const uploadButton = button('上传新版本', () => { uploadForm.hidden = !uploadForm.hidden; if (!uploadForm.hidden) file.focus(); }, 'button');
  heading.append(headingCopy, uploadButton); shell.append(heading);
  const error = node('div', 'error-banner'); error.hidden = true; error.setAttribute('role', 'alert'); shell.append(error);
  const message = node('p', 'release-message'); message.setAttribute('role','status'); shell.append(message);
  const uploadForm = node('form', 'release-upload'); uploadForm.hidden = true;
  const fileField = node('div','field'), fileLabel = node('label','','已签名 APK'), file = node('input'); file.type='file'; file.accept='.apk,application/vnd.android.package-archive'; file.id='release-apk'; fileLabel.htmlFor=file.id; file.required=true;
  fileField.append(fileLabel,file,node('small','','最大 4 MiB。包名、版本号、最低 Android 版本和签名由服务器读取并校验。'));
  const channelField = node('div','field'), channelLabel = node('label','','发布渠道'), channel = node('select'); channel.id='release-channel'; channelLabel.htmlFor=channel.id;
  for(const [value,label] of [['stable','正式版'],['beta','测试版']]){ const option=node('option','',label);option.value=value;channel.append(option); }
  channelField.append(channelLabel,channel);
  const notesField = node('div','field'), notesLabel=node('label','','更新说明'), notes=node('textarea'); notes.id='release-notes';notesLabel.htmlFor=notes.id;notes.required=true;notes.maxLength=6000;notes.rows=5;notes.placeholder='写下本次版本的改进，以及用户需要了解的变化。';notesField.append(notesLabel,notes);
  const submit=node('button','button','上传并校验');submit.type='submit'; const cancel=button('取消',()=>{uploadForm.hidden=true;},'button secondary');
  const formActions=node('div','release-actions');formActions.append(submit,cancel);uploadForm.append(fileField,channelField,notesField,formActions);shell.append(uploadForm);
  const toolbar=node('div','release-toolbar'), downloadLink=node('a','','打开下载页');downloadLink.target='_blank';downloadLink.rel='noopener';
  toolbar.append(node('p','muted','上架后的安装包不可覆盖。撤回会立即停止新的下载。'),downloadLink);shell.append(toolbar);
  const list=node('div','release-list'); shell.append(list);container.replaceChildren(shell);
  function report(err){if(destroyed)return;error.hidden=false;error.textContent=err.message||'操作未完成，请重试。';onError(err);}
  async function refresh(){
    error.hidden=true;message.textContent='正在读取版本…';
    try{const result=await request('/api/releases');if(destroyed)return;data=result;downloadLink.href=new URL('../download/',import.meta.url).href;renderList();message.textContent='';}
    catch(err){message.textContent='';report(err);}
  }
  function renderList(){
    list.replaceChildren();
    if(!data.items.length){const empty=node('div','empty-state');empty.append(node('h2','','还没有 App 版本'),node('p','','上传安装包后，先保存为草稿。只有经过确认上架的版本才会出现在下载页。'));list.append(empty);return;}
    for(const release of data.items){
      const row=node('article','release-row'),main=node('div','release-row-copy'),name=node('h2','',`Huevia ${release.versionName}`);
      const meta=node('p','muted',`${release.channel==='stable'?'正式版':'测试版'} · versionCode ${release.versionCode} · ${formatSize(release.byteLength)} · Android API ${release.minSdk}+`);
      main.append(name,meta,node('p','release-row-time',release.status==='withdrawn'?`撤回于 ${time(release.withdrawnAt)}`:`上架时间 ${time(release.publishedAt)}`));
      const status=node('span',`status ${release.status==='published'?'published':''}`,statusLabels[release.status]);
      row.append(main,status,button('查看版本',()=>openRelease(release),'row-edit'));list.append(row);
    }
  }
  uploadForm.addEventListener('submit',async event=>{
    event.preventDefault();if(busy)return; const apk=file.files[0];
    if(!apk||!apk.name.toLowerCase().endsWith('.apk')||apk.size<1||apk.size>4194304){report(new Error('请选择不超过 4 MiB 的 APK。'));return;}
    if(!notes.value.trim()){notes.focus();return;}
    busy=true;submit.disabled=true;uploadButton.disabled=true;cancel.disabled=true;error.hidden=true;
    try{
      const task=await request('/api/releases/uploads',{method:'POST',body:{byteLength:apk.size,channel:channel.value,notes:notes.value.trim()}});
      for(let offset=0,index=0;offset<apk.size;offset+=task.chunkBytes,index++){
        message.textContent=`正在上传 ${Math.floor(offset/apk.size*100)}%…`;
        await request(`/api/releases/uploads/${task.uploadId}/chunks/${index}`,{method:'PUT',raw:true,type:'application/octet-stream',body:apk.slice(offset,offset+task.chunkBytes)});
      }
      message.textContent='正在校验 APK 签名、版本与完整性…';
      const result=await request(`/api/releases/uploads/${task.uploadId}/complete`,{method:'POST',body:{}});
      uploadForm.reset();uploadForm.hidden=true;await refresh();openRelease(result.release);message.textContent='APK 已校验并保存为草稿。';
    }catch(err){message.textContent='';report(err);}finally{busy=false;submit.disabled=false;uploadButton.disabled=false;cancel.disabled=false;}
  },{signal:controller.signal});
  function openRelease(initial){
    let release=initial;
    const dialog=node('dialog','release-dialog'),title=node('h2','',`Huevia ${release.versionName}`),close=button('关闭',()=>dialog.close(),'button quiet');
    title.id=`release-title-${release.id}`;dialog.setAttribute('aria-labelledby',title.id);
    const header=node('div','release-dialog-heading');header.append(title,close);dialog.append(header);
    const status=node('p','release-dialog-status',`${release.channel==='stable'?'正式版':'测试版'} · ${statusLabels[release.status]}`);dialog.append(status);
    const details=node('dl','release-facts');
    for(const [label,value] of [['包名',release.packageName],['versionCode',release.versionCode],['最低 Android',`API ${release.minSdk}`],['大小',`${formatSize(release.byteLength)}（${release.byteLength.toLocaleString()} 字节）`],['SHA-256',release.sha256],['签名 SHA-256',release.signerSha256]])details.append(node('dt','',label),node('dd','',String(value)));
    dialog.append(details);
    const draftNotes=node('textarea');draftNotes.value=release.notes;draftNotes.maxLength=6000;draftNotes.rows=5;draftNotes.disabled=release.status!=='draft';draftNotes.id=`notes-${release.id}`;
    const field=node('div','field'),label=node('label','','更新说明');label.htmlFor=draftNotes.id;field.append(label,draftNotes);dialog.append(field);
    const dialogError=node('p','field-error');dialogError.setAttribute('role','alert');dialog.append(dialogError);
    const actions=node('div','release-actions');dialog.append(actions);
    const confirmArea=node('div','release-confirm');confirmArea.hidden=true;dialog.append(confirmArea);
    async function run(action,body){
      dialogError.textContent='';for(const item of dialog.querySelectorAll('button'))item.disabled=true;
      try{const result=await request(`/api/releases/${release.id}${action==='edit'?'':`/${action}`}`,{method:action==='edit'?'PUT':'POST',body:{expectedRevision:release.revision,...body}});release=result.release;await refresh();dialog.close();openRelease(release);}
      catch(err){dialogError.textContent=err.message;onError(err);for(const item of dialog.querySelectorAll('button'))item.disabled=false;}
    }
    function showConfirmation(action){
      if(action==='publish'&&draftNotes.value!==release.notes){dialogError.textContent='更新说明尚未保存，请先保存草稿。';return;}
      confirmArea.hidden=false;confirmArea.replaceChildren();
      const text=action==='publish'?`即将上架${release.channel==='stable'?'正式版':'测试版'} ${release.versionName}（${release.versionCode}）。确认安装包、签名和更新说明无误后再继续。`:`撤回后，这个版本立即停止新的下载。客户端不会自动降级到旧版本；修复时需上传更高 versionCode。`;
      confirmArea.append(node('p','',text));const checkLabel=node('label','release-check'),check=node('input');check.type='checkbox';checkLabel.append(check,document.createTextNode(action==='publish'?'我已复核这个版本的信息':'我确认撤回这个版本'));confirmArea.append(checkLabel);
      const confirm=button(action==='publish'?'确认上架':'确认撤回',()=>run(action,{confirmation:action==='publish'?`PUBLISH ${release.versionCode} ${release.sha256}`:`WITHDRAW ${release.versionCode}`}),action==='publish'?'button':'button danger');confirm.disabled=true;
      check.addEventListener('change',()=>{confirm.disabled=!check.checked;},{signal:controller.signal});confirmArea.append(confirm);check.focus();
    }
    if(release.status==='draft'){actions.append(button('保存说明',()=>run('edit',{notes:draftNotes.value.trim()}),'button secondary'),button('上架版本',()=>showConfirmation('publish'),'button'));}
    if(release.status==='published')actions.append(button('撤回版本',()=>showConfirmation('withdraw'),'button danger'));
    const audit=node('details','release-audit');audit.append(node('summary','','操作记录'));const auditBody=node('div');audit.append(auditBody);dialog.append(audit);
    audit.addEventListener('toggle',async()=>{if(!audit.open||auditBody.childNodes.length)return;auditBody.textContent='正在读取…';try{const result=await request(`/api/releases/${release.id}/audit`);auditBody.replaceChildren();for(const item of result.items)auditBody.append(node('p','',`${time(item.created_at)} · ${item.actor} · ${{upload_verified:'上传并验签',edit:'保存说明',publish:'上架',withdraw:'撤回'}[item.action]||item.action}`));}catch(err){auditBody.textContent=err.message;}},{signal:controller.signal});
    dialog.addEventListener('close',()=>dialog.remove(),{once:true});shell.append(dialog);dialog.showModal();close.focus();
  }
  refresh();
  return {refresh,destroy(){destroyed=true;controller.abort();for(const dialog of shell.querySelectorAll('dialog'))dialog.close();shell.remove();}};
}
