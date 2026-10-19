const $=(q,root=document)=>root.querySelector(q), $$=(q,root=document)=>[...root.querySelectorAll(q)];
const icon=(name,extra='')=>`<svg class="icon ${extra}" aria-hidden="true"><use href="#i-${name}"/></svg>`;
const photo=(index=0,label='示意照片')=>`<svg class="photograph" viewBox="0 0 512 512" preserveAspectRatio="xMidYMid slice" role="img" aria-label="${label}"><image href="assets/photo-atlas.png" x="${-(index%3)*512}" y="${-Math.floor(index%6/3)*512}" width="1536" height="1024"/></svg>`;
const names=['雾色湖畔','雨后街角','山野日光','海岸','林间','雪山'];
const projects=['山间拍摄','周末漫游','新建拍摄项目'];
const presets=['Bluesky','Agfa Optima','不使用预设'];
let state='receiving',percentage=68,timer=null,toastTimer=null,viewIndex=0,activePhone=null,chosen=null,savedCount=12,completedCurrent=false;
const stateSelect=$('#state-choice'),demoButton=$('#demo-button');
function commonHeader(){return `<div class="system-bar"><span>12:40</span><div class="system-right">${icon('wifi')}<span class="battery"></span></div></div><header class="app-head"><div class="app-title-row"><h3>相册</h3><span class="connection">已连接</span></div><p>拍完，照片就到手机。</p></header><nav class="album-tabs" aria-label="相册分类"><button data-action="camera">相机照片</button><button class="current" aria-current="page" data-action="live">照片直播</button><button data-action="downloads">已下载</button></nav>`}
function projectRow(){return `<div class="project-row"><button class="project-choice" data-action="project"><span class="project-name">山间拍摄</span>${icon('down')}</button><button class="icon-button" data-action="info" aria-label="查看项目详情">${icon('info')}</button></div>`}
function subrow(){return `<div class="project-subrow"><button class="preset-button" data-action="preset"><span class="preset-dot"></span><span>默认预设 · <span class="preset-name">Bluesky</span></span></button><button class="text-button" data-action="history">接收记录</button></div>`}
function transfer(){return `<section class="transfer" aria-label="当前原图接收进度"><div class="transfer-top"><div class="transfer-title"><i class="state-mark"></i><span data-field="title">正在接收原图</span></div><span class="percent" data-field="percent">68%</span></div><div class="transfer-bottom"><span class="transfer-detail" data-field="detail">DSC_0128.JPG · 12.4 / 18.2 MB</span><button class="transfer-action" data-action="transfer">停止</button></div><div class="progress-track" role="progressbar" aria-label="当前原图" aria-valuemin="0" aria-valuemax="100" aria-valuenow="68"><div class="progress-fill"></div></div></section>`}
function heading(studio=false){return `<div class="photo-heading"><h4>${studio?'最近照片':'项目照片'} <span data-field="count">· 12</span></h4><div class="photo-tools">${studio?`<button class="text-button" data-action="all">全部照片 ${icon('arrow')}</button>`:`<button class="filter-button" data-action="filter">筛选 ${icon('down')}</button><button class="text-button" data-action="select">选择</button>`}</div></div>`}
function thumbs(film=false){return `<div class="${film?'filmstrip':'photo-grid'}">${Array.from({length:film?6:9},(_,i)=>`<button class="thumbnail" data-photo="${i%6}" data-photo-id="${i}" aria-label="查看${names[i%6]}示意照片">${photo(i%6)}${i%3===1?`<span class="thumb-star">${icon('star')}</span>`:''}${i===0?'<span class="thumb-progress" data-field="thumb-percent">68%</span>':''}<span class="check-badge">${icon('check')}</span></button>`).join('')}<div class="empty-grid">按下相机快门，新照片会出现在这里。</div></div>`}
function hero(){return `<button class="photo-hero" data-photo="0" aria-label="查看最新照片">${photo(0)}<span class="hero-badge">最新照片</span><span class="hero-expand">${icon('expand')}</span><span class="empty-overlay" hidden>${icon('image')}<span>新照片会显示在这里</span></span></button><div class="photo-caption"><span>DSC_0128.JPG</span><span data-field="hero-state">原图接收中</span></div>`}
function nav(){return `<div class="selection-tray"><span data-field="selected-count">已选 0 张</span><button data-action="apply">套用预设</button></div><nav class="nav-bar" aria-label="应用导航"><button data-action="presets">${icon('image')}<span>预设</span></button><button class="current" aria-current="page" data-action="album">${icon('album')}<span>相册</span></button><button data-action="settings">${icon('settings')}<span>设置</span></button></nav><div class="home-indicator"></div>`}
function mount(){
 for(const phone of $$('.device')){
  const type=phone.dataset.phone;phone.dataset.theme=document.documentElement.dataset.theme;phone.dataset.project='山间拍摄';phone.dataset.preset='Bluesky';
  let content='';
  if(type==='a')content=projectRow()+subrow()+transfer()+hero()+heading()+thumbs();
  if(type==='b'){phone.classList.add('workbench');content=`<section class="project-summary">${projectRow()}<p class="project-date">10 月 18 日创建 · Nikon Z6II</p>${subrow()}<div class="project-metrics"><span><strong data-field="saved-count">12</strong> 已保存</span><span><strong data-field="queue-count">2</strong> 待接收</span></div></section>`+transfer()+heading()+thumbs()+`<p class="grid-note">原图自动保存到系统相册</p>`;}
  if(type==='c'){phone.classList.add('studio');content=projectRow()+`<div class="studio-canvas"><button class="studio-photo" data-photo="0" aria-label="查看最新照片">${photo(0)}</button><div class="empty-overlay" hidden>${icon('image')}<span>新照片会显示在这里</span></div><div class="studio-shade"></div><button class="studio-preset" data-action="preset"><span class="preset-dot"></span><span class="preset-name">Bluesky</span>${icon('down')}</button><span class="hero-expand">${icon('expand')}</span><div class="studio-overlay">${transfer()}</div></div>`+heading(true)+thumbs(true)+`<div class="studio-hint"><span data-field="saved-summary">已保存 12 张 · 待接收 2 张</span><button class="text-button" data-action="history">接收记录</button></div>`;}
  phone.innerHTML=commonHeader()+`<div class="screen-main">${content}</div>`+nav();
 }
}
const snapshots={
 receiving:{title:'正在接收原图',percent:()=>percentage+'%',detail:()=>`DSC_0128.JPG · ${(18.2*percentage/100).toFixed(1)} / 18.2 MB`,action:'停止',hero:'原图接收中'},
 waiting:{title:'直播中 · 等待拍摄',percent:'等待中',detail:()=>`已保存 ${savedCount} 张 · 按下相机快门即可`,action:'停止',hero:'原图已保存'},
 preparing:{title:'正在准备直播',percent:'准备中',detail:'正在确认相机存储卡…',action:'取消',hero:'最近保存'},
 unknown:{title:'正在接收原图',percent:'接收中',detail:'DSC_0128.JPG · 正在确认原图大小',action:'停止',hero:'原图接收中'},
 saving:{title:'接收完成，正在保存',percent:'保存中',detail:'DSC_0128.JPG · 18.2 / 18.2 MB',action:'停止',hero:'正在保存到相册'},
 processing:{title:'原图已保存 · 预设处理中',percent:'已保存',detail:'DSC_0128.JPG · 效果图将另存',action:'停止',hero:'原图已保存'},
 saved:{title:'已保存到相册',percent:'已保存',detail:'DSC_0128.JPG · 继续等待新照片',action:'停止',hero:'原图已保存'},
 error:{title:'1 张照片待重试',percent:'待重试',detail:'原图未接收完整 · 其他照片继续接收',action:'重试',hero:'保留上一张照片'},
 disconnected:{title:'相机连接已中断',percent:'未连接',detail:'已保存的照片保留在本项目',action:'去连接',hero:'最近保存'},
 stopped:{title:'直播已停止',percent:'已停止',detail:()=>`已保存 ${savedCount} 张 · 可继续接收此项目`,action:'继续',hero:'原图已保存'},
 idle:{title:'准备好，开始拍摄',percent:'未开始',detail:'开启后，新拍的 JPEG 原图自动保存',action:'开始',hero:'等待新照片'}
};
function update(next=state){if(next==='receiving'&&state!=='receiving')completedCurrent=false;if(['processing','saved'].includes(next)&&!completedCurrent){savedCount++;completedCurrent=true;}state=next;stateSelect.value=next;const s=snapshots[next];for(const phone of $$('.device')){
 for(const key of Object.keys(snapshots))phone.classList.toggle('is-'+key,key===next);
 for(const [key,val]of Object.entries({title:s.title,percent:typeof s.percent==='function'?s.percent():s.percent,detail:typeof s.detail==='function'?s.detail():s.detail,'hero-state':s.hero,count:next==='idle'?'· 0':'· '+(savedCount+(['receiving','saving','unknown'].includes(next)?2:0)),'saved-count':next==='idle'?'0':String(savedCount),'queue-count':['receiving','saving','unknown'].includes(next)?'2':'0','saved-summary':next==='idle'?'原图自动保存到相册':`已保存 ${savedCount} 张 · ${['receiving','saving','unknown'].includes(next)?'待接收 2 张':next==='error'?'1 张待重试':next==='stopped'?'直播已停止':'等待新照片'}`,'thumb-percent':next==='receiving'?percentage+'%':next==='error'?'待重试':next==='saving'?'保存中':next==='unknown'?'接收中':''})){
  for(const element of $$(`[data-field="${key}"]`,phone))element.textContent=val;
 }
 $('.transfer-action',phone).textContent=s.action;
 const track=$('.progress-track',phone),fill=$('.progress-fill',phone);
 fill.style.transform=`scaleX(${next==='receiving'?percentage/100:['saved','processing'].includes(next)?1:next==='error'?.68:0})`;
 if(next==='receiving')track.setAttribute('aria-valuenow',percentage);else track.removeAttribute('aria-valuenow');
 track.setAttribute('aria-valuetext',typeof s.percent==='function'?s.percent():s.percent);
 $('.connection',phone).textContent=next==='disconnected'?'未连接':'已连接';
 for(const empty of $$('.empty-overlay',phone))empty.hidden=next!=='idle';
 const thumb=$('[data-field="thumb-percent"]',phone);thumb.hidden=!['receiving','error','saving','unknown'].includes(next);
 }
}
function toast(text){const el=$('#toast');el.textContent=text;el.classList.add('visible');clearTimeout(toastTimer);toastTimer=setTimeout(()=>el.classList.remove('visible'),3200)}
function stopDemo(){clearInterval(timer);timer=null;$('#demo-button span').textContent='模拟连续接收';$('#demo-button use').setAttribute('href','#i-play');}
function simulate(){if(timer){stopDemo();return;}percentage=4;update('receiving');$('#demo-button span').textContent='暂停模拟';$('#demo-button use').setAttribute('href','#i-stop');let ticks=0;timer=setInterval(()=>{ticks++;if(ticks<=24){percentage=Math.min(100,4+ticks*4);update('receiving')}else if(ticks<=28)update('saving');else if(ticks<=32)update('processing');else if(ticks<=36)update('saved');else{update('waiting');stopDemo()}},230)}
function setView(value){$('#concepts').dataset.view=value;for(const el of $$('[data-view]'))if(el.tagName==='BUTTON'){el.classList.toggle('active',el.dataset.view===value);el.setAttribute('aria-pressed',String(el.dataset.view===value));}}
function openPhoto(index){viewIndex=(Number(index)+6)%6;$('.viewer-photo').innerHTML=photo(viewIndex,names[viewIndex]+'示意照片');if(!$('#viewer').open)$('#viewer').showModal();}
function openSheet(title,body){$('#sheet-title').textContent=title;$('#sheet-body').innerHTML=body;$('#sheet').showModal()}
function projectSheet(phone){activePhone=phone;openSheet('拍摄项目',projects.map((name,i)=>`<button class="sheet-option" data-project-index="${i}"><span>${name}<small>${i===0?'10 月 18 日 · 12 张照片':i===1?'10 月 12 日 · 36 张照片':'新照片单独归类，原有项目保留'}</small></span>${phone.dataset.project===name?icon('check'):i===2?icon('arrow'):''}</button>`).join(''))}
function presetSheet(phone,apply=false){activePhone=phone;openSheet(apply?'套用预设':'项目默认预设',presets.map((name,i)=>`<button class="sheet-option" data-preset-index="${i}" ${apply?'data-apply-preset="true"':''}><span class="sheet-preset">${i<2?photo(i+1):icon('image')}<span>${name}<small>${i<2?'效果图仅为布局示意':'保留相机原图'}</small></span></span>${phone.dataset.preset===name?icon('check'):''}</button>`).join('')+`<p class="sheet-explanation">${apply?'每次从原图生成效果，同一预设不重复生成。':'更改仅影响之后接收的新照片，原图始终保留。'}</p>`)}
function historySheet(){openSheet('接收记录',`<div class="sheet-meta"><strong>DSC_0128.JPG</strong><span>${snapshots[state].title}</span></div><div class="sheet-meta"><strong>DSC_0127.JPG</strong><span>原图与效果均已保存</span></div><div class="sheet-meta"><strong>DSC_0126.JPG</strong><span>原图与效果均已保存</span></div><p class="sheet-explanation">设计稿中的文件名、进度和记录均为模拟数据。</p>`)}
function onPhoneAction(phone,action){switch(action){
 case'project':projectSheet(phone);break;
 case'preset':presetSheet(phone);break;
 case'info':openSheet('项目详情',`<div class="sheet-meta"><strong>项目名称</strong><span>${phone.dataset.project}</span></div><div class="sheet-meta"><strong>创建时间</strong><span>2026 年 10 月 18 日 12:28</span></div><div class="sheet-meta"><strong>照片</strong><span>12 张已保存 · 2 张待接收</span></div><div class="sheet-meta"><strong>默认预设</strong><span>${phone.dataset.preset}</span></div><div class="sheet-meta"><strong>原图容量</strong><span>218.4 MB</span></div><p class="sheet-explanation">此处可继续扩展重命名。示意信息不代表真实项目。</p>`);break;
 case'history':historySheet();break;
 case'transfer':if(state==='error'){percentage=0;simulate();toast('演示：重新接收当前原图')}else if(state==='disconnected'){openSheet('连接相机',`<p class="sheet-explanation">请先在系统 Wi-Fi 中连接相机热点，再到「设置」连接相机。连接成功后返回照片直播，保留当前项目。</p><button class="sheet-option" id="mock-reconnect"><span>模拟连接成功并返回直播</span>${icon('arrow')}</button>`)}else if(state==='idle'||state==='stopped'){simulate()}else{stopDemo();update('stopped');toast('演示已停止，项目与已保存照片保留')}break;
 case'filter':activePhone=phone;openSheet('筛选照片',`<button class="sheet-option" id="toggle-starred"><span>星标<small>${phone.dataset.starred==='true'?'再次点击取消筛选，显示全部照片':'只查看此项目中已标星的照片'}</small></span>${phone.dataset.starred==='true'?icon('check'):''}</button>`);break;
 case'select':phone.classList.toggle('selection-active');$('.text-button[data-action="select"]',phone).textContent=phone.classList.contains('selection-active')?'取消':'选择';if(!phone.classList.contains('selection-active'))for(const thumb of $$('.thumbnail',phone))thumb.classList.remove('selected');$('[data-field="selected-count"]',phone).textContent='已选 0 张';break;
 case'apply':if(!$$('.thumbnail.selected',phone).length){toast('先选择照片，再套用预设');break;}presetSheet(phone,true);break;
 case'all':openSheet('项目照片',`<div class="photo-heading"><h4>12 张已保存</h4><button class="filter-button" id="sheet-star-filter">星标 ${icon('down')}</button></div>${thumbs()}<p class="sheet-explanation">点照片可放大查看。正式版在此保留星标筛选、选择和批量套用预设。</p>`);break;
 case'camera':toast('设计范围：当前展示照片直播，相机照片沿用现有页面');break;
 case'downloads':toast('接收完成的原图也可以在「已下载」中查看');break;
 case'settings':openSheet('相机连接',`<div class="sheet-meta"><strong>Nikon Z6II</strong><span>${state==='disconnected'?'未连接':'已连接 · Wi-Fi'}</span></div><p class="sheet-explanation">连接管理继续统一放在设置。本设计不会主动关闭或重建相机会话。</p>`);break;
 case'presets':presetSheet(phone);break;
 case'live':case'album':toast('当前是照片直播设计预览');break;
 }}
mount();update();
document.addEventListener('click',event=>{
 const target=event.target.closest('button');if(!target)return;
 if(target.dataset.view){setView(target.dataset.view);return;}
 if(target.dataset.choose){chosen=target.dataset.choose;for(const concept of $$('.concept')){const active=concept.dataset.concept===chosen;concept.classList.toggle('is-chosen',active);const btn=$('.choose-button',concept);btn.classList.toggle('selected',active);btn.innerHTML=active?'已标记为偏好 '+icon('check'):'选这套方案 '+icon('arrow');}const label={a:'A · 照片优先',b:'B · 项目工作台',c:'C · 沉浸影棚'}[chosen];$('#choice-feedback').textContent=`已标记「${label}」。请在聊天里确认，也可以说想保留哪一部分。`;toast('已标记偏好，请回到聊天确认方案');return;}
 if(target.matches('.viewer-close')){$('#viewer').close();return;}
 if(target.dataset.closeSheet!==undefined){$('#sheet').close();return;}
 if(target.id==='previous-photo'){openPhoto(viewIndex-1);return;}
 if(target.id==='next-photo'){openPhoto(viewIndex+1);return;}
 if(target.dataset.photo!==undefined){const phone=target.closest('.device');if(phone?.classList.contains('selection-active')&&target.classList.contains('thumbnail')){if(target.dataset.photoId==='0'&&['receiving','unknown','saving','error'].includes(state)){toast('这张照片尚未保存，请接收完成后再选择');return;}target.classList.toggle('selected');$('[data-field="selected-count"]',phone).textContent=`已选 ${$$('.thumbnail.selected',phone).length} 张`;}else openPhoto(target.dataset.photo);return;}
 if(target.dataset.projectIndex!==undefined){const name=projects[Number(target.dataset.projectIndex)];if(Number(target.dataset.projectIndex)===2){toast('正式版输入项目名称后开始直播，历史项目保留');$('#sheet').close();return;}activePhone.dataset.project=name;$('.project-name',activePhone).textContent=name;$('#sheet').close();toast('已切换示意项目，当前接收不会被重新启动');return;}
 if(target.dataset.presetIndex!==undefined){const name=presets[Number(target.dataset.presetIndex)];activePhone.dataset.preset=name;for(const label of $$('.preset-name',activePhone))label.textContent=name;$('#sheet').close();toast(target.dataset.applyPreset?'演示：从原图套用预设，效果另存':'仅影响后续新照片，原图保留');return;}
 if(target.id==='mock-reconnect'){$('#sheet').close();update('waiting');toast('演示：连接成功，已返回当前直播项目');return;}
 if(target.id==='toggle-starred'){activePhone.dataset.starred=activePhone.dataset.starred==='true'?'false':'true';for(const thumb of $$('.photo-grid .thumbnail',activePhone))thumb.hidden=activePhone.dataset.starred==='true'&&!$('.thumb-star',thumb);$('.filter-button',activePhone).innerHTML=(activePhone.dataset.starred==='true'?'星标':'筛选')+' '+icon('down');$('.filter-button',activePhone).classList.toggle('is-active',activePhone.dataset.starred==='true');$('#sheet').close();toast(activePhone.dataset.starred==='true'?'仅显示星标照片':'显示全部照片');return;}
 if(target.id==='sheet-star-filter'){const starred=target.dataset.starred!=='true';target.dataset.starred=String(starred);for(const thumb of $$('#sheet .thumbnail'))thumb.hidden=starred&&!$('.thumb-star',thumb);toast(starred?'仅显示星标示意照片':'显示全部照片');return;}
 const phone=target.closest('.device');if(phone&&target.dataset.action)onPhoneAction(phone,target.dataset.action);
});
$('#theme-toggle').addEventListener('click',()=>{const next=document.documentElement.dataset.theme==='light'?'dark':'light';document.documentElement.dataset.theme=next;for(const phone of $$('.device'))phone.dataset.theme=next;$('#theme-toggle span').textContent=next==='light'?'深色预览':'浅色预览'});
stateSelect.addEventListener('change',()=>{stopDemo();percentage=68;update(stateSelect.value)});demoButton.addEventListener('click',simulate);
for(const dialog of $$('dialog'))dialog.addEventListener('click',event=>{if(event.target===dialog){const r=dialog.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)dialog.close()}});
document.addEventListener('keydown',event=>{if($('#viewer').open&&event.key==='ArrowRight')openPhoto(viewIndex+1);if($('#viewer').open&&event.key==='ArrowLeft')openPhoto(viewIndex-1)});
window.addEventListener('pagehide',stopDemo);
