/* Local visual prototype only. No camera, phone-storage or network operations. */
const icon = (name, cls = '') => `<svg class="icon ${cls}" aria-hidden="true"><use href="#i-${name}"/></svg>`;
const reduced = matchMedia('(prefers-reduced-motion: reduce)');
const springs = new WeakMap();
function spring(element, target, apply, start = 0) {
  let s = springs.get(element);
  if (!s) { s = { value: start, velocity: 0, target, raf: 0, last: 0 }; springs.set(element, s); }
  s.target = target;
  if (reduced.matches) { cancelAnimationFrame(s.raf); s.raf = 0; s.value = target; s.velocity = 0; apply(target); return; }
  if (s.raf) return;
  s.last = performance.now();
  const step = now => {
    const dt = Math.min((now - s.last) / 1000, .025); s.last = now;
    s.velocity += ((s.target - s.value) * 390 - s.velocity * 39) * dt;
    s.value += s.velocity * dt; apply(s.value);
    if (Math.abs(s.target - s.value) < .08 && Math.abs(s.velocity) < .4) { s.value = s.target; s.velocity = 0; apply(s.target); s.raf = 0; }
    else s.raf = requestAnimationFrame(step);
  }; s.raf = requestAnimationFrame(step);
}
function statusbar() { return `<div class="statusbar"><span>9:41</span><div class="status-icons"><span class="status-signal"><i></i><i></i><i></i><i></i></span>${icon('wifi')}<span class="battery"></span></div></div>`; }
function segments(options, selected = 0) { return `<div class="segmented" role="tablist"><span class="selection" style="transform:translateX(${selected * 100}%)"></span>${options.map((o, i) => `<button role="tab" aria-selected="${i === selected}" data-segment="${i}">${o}</button>`).join('')}</div>`; }
function tabbar(active) { return `<div class="bottom-fade"></div><nav class="tabbar glass" aria-label="应用导航">${[['library','预设','library'],['album','相册','album'],['settings','设置','settings']].map(([route,title,glyph]) => `<button class="nav-item" data-route="${route}" aria-selected="${active === route}">${icon(glyph)}<span>${title}</span></button>`).join('')}</nav>`; }
const photos = [0,1,2,3,4,5,2,4,1];
const names = ['雾色山野', '城市漫游', '日光漫步'];
let emptyMode = false;
const cameraState = { connected: true, mode: 'wifi', host: '192.168.1.1', port: '15740', endpoint: '' };
const escaped = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function connectionStatus(context = '') {
  const connected = cameraState.connected && (context !== 'album' || cameraState.mode === 'wifi');
  return `<span class="connection-indicator ${connected?'online':'offline'}" role="status"><i class="status-dot" aria-hidden="true"></i>${connected?'已连接':'未连接'}</span>`;
}
const state = new Map();
const apps = [...document.querySelectorAll('.app')];
function initial(app) { return { screen: app.dataset.start, source: 0, photo: 0, selected: new Set(), selection: false, downloaded: false, zoom: 1, chrome: true }; }
function emptyMarkup() { return `<div class="empty-state"><div class="empty-symbol">${icon('library')}</div><h3>留住你的第一种色彩</h3><p>从手机添加 NCP，或配置在线预设库，开始整理你的摄影风格。</p><button class="filled-button" data-action="add">${icon('plus')}添加预设</button></div>`; }
function library(app) {
  const s = state.get(app);
  return `${statusbar()}<div class="app-scroll"><header class="app-title"><h2>预设库</h2><div class="title-actions">${connectionStatus()}<button class="circle-button blue" data-action="add" aria-label="添加预设">${icon('plus')}</button></div></header><p class="app-description">${emptyMode ? '你的色彩收藏，从这里开始' : '把喜欢的色彩，带进相机'}</p>${segments(['我的预设','在线预设'],s.source)}${emptyMode ? emptyMarkup() : s.source === 1 ? `<div class="empty-state"><div class="empty-symbol">${icon('folder')}</div><h3>在线预设库尚未配置</h3><p>配置预设库地址后，即可浏览真实样片并下载预设。</p><button class="filled-button" data-action="configure">前往设置</button></div>` : `<div class="section-heading"><h3>最近添加</h3><button class="text-action" data-action="sort">全部 ${icon('chevron')}</button></div><button class="preset-hero photo photo-0" data-preset="0" aria-label="查看雾色山野示例预设"><span class="category-tag">风景</span><span class="preset-caption glass"><span><strong>雾色山野</strong><small>柔和对比 · 冷调风景</small></span><span class="preset-open">${icon('arrow')}</span></span></button><div class="small-presets"><button class="small-preset" data-preset="1"><span class="small-cover photo photo-1"></span><strong>城市漫游</strong><small>暖色街景</small></button><button class="small-preset" data-preset="2"><span class="small-cover photo photo-2"></span><strong>日光漫步</strong><small>自然色彩</small></button></div>`}</div>${tabbar('library')}<div class="home-indicator"></div>`;
}
function tile(photoIndex, i, selected, selecting) { return `<button class="photo-tile photo photo-${photoIndex} ${selected?'selected':''}" data-photo="${i}" aria-label="${selecting ? selected?'取消选择':'选择':'查看'}照片 ${i+1}" ${selecting?`aria-pressed="${selected}"`:''}>${selecting?`<span class="selection-mark">${selected?icon('check'):''}</span>`:''}</button>`; }
function album(app) {
  const s = state.get(app);
  const displayed = s.source === 1 ? [0,3,4] : photos;
  const toolbar = s.selection ? `<div class="tabbar glass select-toolbar"><span>已选 ${s.selected.size} 张</span><button class="filled-button" data-action="download-selection" ${!s.selected.size?'disabled':''}>${icon('down')}下载原图</button></div>` : tabbar('album');
  const available = s.source === 1 || (cameraState.connected && cameraState.mode === 'wifi');
  const content = available ? `<div class="photo-heading"><h3>今天</h3><span class="photo-heading-actions"><span>${Math.min(displayed.length,6)} 张照片</span><button class="text-action" data-action="select">${s.selection?'完成':'选择'}</button></span></div><div class="photo-grid">${displayed.slice(0,6).map((p,i)=>tile(p,i,s.selected.has(i),s.selection)).join('')}</div>${displayed.length > 6 ? `<div class="photo-heading"><h3>昨天</h3><span>3 张照片</span></div><div class="photo-grid">${displayed.slice(6).map((p,i)=>tile(p,i+6,s.selected.has(i+6),s.selection)).join('')}</div>`:''}<p class="album-note">${s.source===1?'保存的原图可离线查看与另存。':'浏览只读取预览图，原图由你决定下载。'}</p>` : `<div class="empty-state"><div class="empty-symbol">${icon('camera')}</div><h3>相机照片尚未连接</h3><p>${cameraState.mode==='usb'&&cameraState.connected?'USB 连接用于预设导入。':'已下载照片仍可离线查看。'}请在底部“设置”中连接相机 Wi-Fi，再回来浏览。</p></div>`;
  return `${statusbar()}<div class="app-scroll"><header class="app-title album-head"><h2>相册</h2>${connectionStatus('album')}</header><p class="app-description">${s.source === 1 ? '已保存到手机，随时离线查看' : '发现镜头里，值得留下的瞬间'}</p>${segments(['相机照片','已下载'],s.source)}${content}</div>${toolbar}<div class="home-indicator"></div>`;
}
function settings(app) {
  const mode = cameraState.mode === 'wifi' ? 'Wi-Fi' : 'USB';
  const row = (glyph,title,subtitle,action,current='') => `<button class="settings-row" data-action="${action}"><span class="settings-icon ${glyph}">${icon(glyph)}</span><span class="settings-row-copy"><strong>${title}</strong>${subtitle?`<small>${subtitle}</small>`:''}</span>${current?`<span class="row-current">${current}</span>`:icon('chevron')}</button>`;
  return `${statusbar()}<div class="app-scroll settings-scroll"><header class="app-title"><h2>设置</h2></header><p class="app-description">相机连接与应用偏好</p><section class="settings-camera" aria-label="当前相机"><div class="settings-camera-top"><span class="device-avatar">${icon('camera')}</span><div class="camera-identity"><small>${cameraState.connected?'当前相机':'上次使用的相机'}</small><h3>Nikon Z6II</h3></div></div><div class="settings-camera-status">${connectionStatus()}${cameraState.connected?`<span class="camera-mode">${mode}</span>`:''}</div><div class="settings-device-footer"><span>${cameraState.connected?(cameraState.mode==='wifi'?'通过 Wi-Fi 浏览照片与导入预设':'USB 用于预设导入，相册需 Wi-Fi'):'选择下方连接方式，连接你的相机'}</span>${cameraState.connected?'<button class="disconnect-link" data-action="disconnect">断开连接</button>':''}</div></section><section class="settings-section"><h3>相机连接</h3><div class="settings-group">${row('wifi','Wi-Fi 无线连接','浏览照片、导入预设','wifi',cameraState.connected&&cameraState.mode==='wifi'?'使用中':'')}${row('usb','USB 数据线连接','导入预设','usb',cameraState.connected&&cameraState.mode==='usb'?'使用中':'')}</div></section><section class="settings-section"><h3>应用</h3><div class="settings-group">${row('folder','在线预设库','','catalog',cameraState.endpoint?'已配置':'未配置')}${row('info','使用帮助','','help')}${row('settings','关于','','about')}</div></section><p class="album-note settings-demo-note">连接状态为设计示例，不会访问真实相机。</p></div>${tabbar('settings')}<div class="home-indicator"></div>`;
}
function viewer(app) {
  const s = state.get(app), p = photos[s.photo] ?? 0;
  const canDownload = s.downloaded || (cameraState.connected && cameraState.mode==='wifi');
  return `${statusbar()}<div class="viewer-stage" aria-label="照片预览，轻点显隐操作，双击缩放" role="button" tabindex="0"><div class="viewer-photo-wrap"><div class="viewer-photo photo photo-${p}" style="transform:scale(${s.zoom})"></div></div></div><div class="viewer-chrome"><header class="viewer-top"><button class="circle-button glass" data-action="back" aria-label="返回相册">${icon('back')}</button><span class="viewer-name">DSC_0${842+s.photo}.JPG</span><button class="circle-button glass" data-action="info" aria-label="查看照片信息">${icon('info')}</button></header><span class="viewer-count">${s.photo+1} / 9</span><span class="viewer-hint">轻点隐藏操作 · 双击缩放</span><div class="viewer-bottom glass"><span class="viewer-label"><strong>${s.downloaded?'原图已保存':'轻量预览'}</strong><small>${s.downloaded?'可离线查看':canDownload?'320 × 200 · 原图 24.6 MB':'相机 Wi-Fi 未连接'}</small></span><button class="filled-button" data-action="download" ${canDownload?'':'disabled'}>${icon(s.downloaded?'check':'down')}${s.downloaded?'已保存':'下载原图'}</button></div></div><div class="home-indicator"></div>`;
}
function render(app) {
  const s = state.get(app);
  app.className = `app ${s.screen === 'viewer'?'viewer':''} ${s.chrome?'':'chr-hidden'} ${s.downloaded?'downloaded':''}`;
  app.innerHTML = s.screen === 'library' ? library(app) : s.screen === 'album' ? album(app) : s.screen === 'settings' ? settings(app) : viewer(app);
  if(s.screen==='viewer')setViewerChrome(app,s.chrome);
  wire(app);
}
function setViewerChrome(app,shown) {
  state.get(app).chrome=shown;
  app.classList.toggle('chr-hidden',!shown);
  const chrome=app.querySelector('.viewer-chrome');
  if(chrome){chrome.inert=!shown;chrome.setAttribute('aria-hidden',String(!shown));}
}
function go(app, screen) { const s=state.get(app); s.screen=screen; s.source=0; s.selection=false; s.selected.clear(); s.chrome=true; s.zoom=1; s.downloaded=false; render(app); }
function toast(app, message) { app.querySelector('.demo-toast')?.remove(); const el=document.createElement('div'); el.className='demo-toast'; el.role='status'; el.textContent=message; app.append(el); setTimeout(()=>el.remove(),2400); }
function showSheet(app, title, content) {
  app.querySelector('.sheet-shade')?.remove();
  const shade=document.createElement('div'); shade.className='sheet-shade';
  shade.innerHTML=`<section class="sheet" role="dialog" aria-modal="true" aria-label="${title}"><span class="sheet-handle" aria-hidden="true"></span><header class="sheet-head"><h3>${title}</h3><button class="circle-button" data-close aria-label="关闭">${icon('close')}</button></header>${content}</section>`;
  app.append(shade); const sheet=shade.firstElementChild; const previousFocus=document.activeElement;
  const close=()=>{shade.remove(); if(previousFocus?.isConnected)previousFocus.focus({preventScroll:true});};
  shade.addEventListener('click',e=>{if(e.target===shade||e.target.closest('[data-close]'))close();});
  shade.addEventListener('keydown',e=>{if(e.key==='Escape')close();if(e.key==='Tab'){const list=[...sheet.querySelectorAll('button:not(:disabled),input,a[href]')];if(!list.length)return;const first=list[0],last=list.at(-1);if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}}});
  spring(sheet,0,v=>sheet.style.transform=`translateY(${v}px)`,90);
  sheet.querySelector('[data-close]').focus({preventScroll:true});
  sheet.querySelectorAll('[data-sheet-action]').forEach(b=>b.onclick=()=>sheetAction(app,sheet,b.dataset.sheetAction,close));
  const handle=sheet.querySelector('.sheet-handle');let drag=null;
  handle.onpointerdown=e=>{const old=springs.get(sheet);if(old){cancelAnimationFrame(old.raf);old.raf=0;}drag={start:e.clientY,last:e.clientY,time:e.timeStamp,velocity:0,y:0};handle.setPointerCapture(e.pointerId);};
  handle.onpointermove=e=>{if(!drag)return;drag.y=Math.max(0,e.clientY-drag.start);drag.velocity=(e.clientY-drag.last)/Math.max(1,e.timeStamp-drag.time)*1000;drag.last=e.clientY;drag.time=e.timeStamp;sheet.style.transform=`translateY(${drag.y}px)`;};
  const release=()=>{if(!drag)return;if(drag.y>90||drag.velocity>650){close();}else{const old=springs.get(sheet);if(old){old.value=drag.y;old.velocity=drag.velocity;}spring(sheet,0,v=>sheet.style.transform=`translateY(${v}px)`,drag.y);}drag=null;};handle.onpointerup=release;handle.onpointercancel=release;
}
function syncConnection(app, connected, mode = cameraState.mode) {
  cameraState.connected=connected;cameraState.mode=mode;
  apps.forEach(other=>{const scroll=other.querySelector('.app-scroll')?.scrollTop??0,s=state.get(other);if((!connected||mode!=='wifi')&&s.screen==='album'&&s.source===0){s.selection=false;s.selected.clear();}render(other);const next=other.querySelector('.app-scroll');if(next)next.scrollTop=scroll;});
  toast(app,connected?`交互演示：已通过 ${mode==='wifi'?'Wi-Fi':'USB'} 连接`:'交互演示：已断开连接');
}
function openConnection(app,mode) {
  if(mode==='usb') {showSheet(app,'USB 数据线连接',`<p class="sheet-copy">使用支持数据传输的数据线连接手机和相机，并允许手机访问 USB 设备。</p><div class="info-line"><span>连接用途</span><strong>预设导入</strong></div><p class="demo-callout">相册浏览需要 Wi-Fi。此操作仅演示界面，不会访问真实 USB 设备。</p><button class="filled-button" data-sheet-action="mock-usb">读取相机</button>`);return;}
  showSheet(app,'Wi-Fi 无线连接',`<p class="sheet-copy">开启相机 Wi-Fi，在手机系统中连接相机网络，再返回读取相机。</p><div class="connection-fields"><label><span>相机 IP 地址</span><input name="camera-host" inputmode="decimal" autocomplete="off" value="${escaped(cameraState.host)}" aria-label="相机 IP 地址"></label><label><span>端口</span><input name="camera-port" inputmode="numeric" autocomplete="off" value="${escaped(cameraState.port)}" aria-label="相机端口"></label></div><p class="connection-error" data-connection-error role="status" hidden></p><p class="demo-callout">可浏览照片与导入预设。当前地址和连接仅为交互示例，不会访问真实相机。</p><button class="filled-button" data-sheet-action="mock-wifi">连接相机</button>`);
}
function sheetAction(app,sheet,action,close) {
  if(action==='mock-wifi') {
    const host=sheet.querySelector('[name="camera-host"]').value.trim(),port=sheet.querySelector('[name="camera-port"]').value.trim();
    if(!/^\d{1,3}(\.\d{1,3}){3}$/.test(host)||host.split('.').some(x=>+x>255)||!/^\d+$/.test(port)||+port<1||+port>65535) {const e=sheet.querySelector('[data-connection-error]');e.textContent='请填写有效的 IP 地址和 1–65535 的端口。';e.hidden=false;return;}
    cameraState.host=host;cameraState.port=port;close();syncConnection(app,true,'wifi');return;
  }
  if(action==='mock-catalog') {
    const field=sheet.querySelector('[name="catalog-url"]');
    try{if(new URL(field.value.trim()).protocol!=='https:')throw new Error();}catch{field.setCustomValidity('请输入有效的 HTTPS 地址。');field.reportValidity();return;}
    cameraState.endpoint=field.value.trim();close();apps.forEach(render);toast(app,'交互演示：已保存预设库地址');return;
  }
  close();
  if(action==='mock-usb')syncConnection(app,true,'usb');
  else if(action==='import'){if(!cameraState.connected){go(app,'settings');toast(app,'请先在设置中连接相机');}else toast(app,'交互演示：使用当前连接检查兼容性与空槽');}
  else toast(app,'设计示例：此处打开手机文件选择器');
}
function wire(app) {
  const s=state.get(app);
  app.querySelectorAll('[data-route]').forEach(b=>b.onclick=()=>go(app,b.dataset.route));
  app.querySelectorAll('[data-segment]').forEach(b=>b.onclick=()=>{
    const selected=Number(b.dataset.segment), indicator=b.parentElement.querySelector('.selection');
    if(s.source===selected)return;
    const old=springs.get(indicator), start=old?.value ?? s.source*100, velocity=old?.velocity ?? 0;
    if(old)cancelAnimationFrame(old.raf);
    const y=app.querySelector('.app-scroll').scrollTop;s.source=selected;s.selection=false;s.selected.clear();render(app);
    app.querySelector('.app-scroll').scrollTop=y;
    const next=app.querySelector('.selection');next.style.transform=`translateX(${start}%)`;
    springs.set(next,{value:start,velocity,target:selected*100,raf:0,last:0});
    spring(next,selected*100,v=>next.style.transform=`translateX(${v}%)`,start);
  });
  app.querySelectorAll('[data-preset]').forEach(b=>b.onclick=()=>{const i=+b.dataset.preset;showSheet(app,'预设详情',`<div class="preset-detail-cover photo photo-${i}"></div><h4 class="detail-title">${names[i]}</h4><p class="sheet-copy">${['柔和对比，让风景保留安静的层次。','温暖色调，收藏城市里的微光。','自然明亮，适合晴日里的随手记录。'][i]}</p><div class="info-line"><span>文件格式</span><strong>NCP · 示例预设</strong></div><p class="demo-callout">照片与名称为设计示例，不代表该预设的真实拍摄效果。</p><button class="filled-button" data-sheet-action="import">导入相机 ${icon('arrow')}</button>`);});
  app.querySelectorAll('[data-photo]').forEach(b=>b.onclick=()=>{const i=+b.dataset.photo;if(s.selection){s.selected.has(i)?s.selected.delete(i):s.selected.add(i);const y=app.querySelector('.app-scroll').scrollTop;render(app);app.querySelector('.app-scroll').scrollTop=y;}else{s.photo=s.source===1?[0,3,4][i]:i;s.downloaded=s.source===1;s.returnSource=s.source;s.returnScroll=app.querySelector('.app-scroll').scrollTop;s.screen='viewer';s.chrome=true;s.zoom=1;render(app);}});
  app.querySelectorAll('[data-action]').forEach(b=>b.onclick=()=>{
    switch(b.dataset.action){
      case 'connect':case 'settings':go(app,'settings');break;
      case 'wifi':case 'usb':openConnection(app,b.dataset.action);break;
      case 'disconnect':syncConnection(app,false);break;
      case 'catalog':showSheet(app,'在线预设库',`<p class="sheet-copy">填写预设库的 HTTPS 目录地址。配置后可浏览真实样片并下载预设。</p><div class="connection-fields"><label><span>目录地址</span><input name="catalog-url" type="url" autocomplete="off" placeholder="https://example.com/catalog.json" value="${escaped(cameraState.endpoint)}" aria-label="在线预设库地址" oninput="this.setCustomValidity('')"></label></div><p class="demo-callout">此处仅保存本次页面内的演示状态，不会请求网络。</p><button class="filled-button" data-sheet-action="mock-catalog">保存地址</button>`);break;
      case 'help':showSheet(app,'使用帮助',`<div class="info-line"><span>Wi-Fi 连接</span><strong>浏览照片、导入预设</strong></div><div class="info-line"><span>USB 连接</span><strong>导入预设</strong></div><p class="sheet-copy">所有相机连接在“设置”中管理。浏览只读取预览图，点击“下载原图”才保存完整照片。已下载照片可离线查看。</p><button class="filled-button" data-close>知道了</button>`);break;
      case 'about':showSheet(app,'关于',`<p class="sheet-copy">相机预设 · Apple 风格视觉提案<br>本页面的照片、预设与连接状态均为设计示例，尚未替换手机中的应用界面。</p><button class="filled-button" data-close>知道了</button>`);break;
      case 'add':showSheet(app,'添加预设',`<p class="sheet-copy">选择手机中的 NCP 文件，加入你的色彩收藏。</p><button class="choice-row" data-sheet-action="file"><span class="choice-icon">${icon('folder')}</span><span class="choice-text"><strong>从手机文件添加</strong><small>支持兼容的 NCP 文件</small></span>${icon('chevron')}</button>`);break;
      case 'configure':go(app,'settings');break;
      case 'sort':toast(app,'设计示例：当前按最近添加排序');break;
      case 'select':s.selection=!s.selection;s.selected.clear();render(app);break;
      case 'download-selection':toast(app,`交互演示：下载所选 ${s.selected.size} 张原图`);break;
      case 'back':s.screen='album';s.source=s.returnSource??0;s.chrome=true;s.zoom=1;s.selection=false;s.selected.clear();render(app);app.querySelector('.app-scroll').scrollTop=s.returnScroll??0;break;
      case 'info':showSheet(app,'照片信息',`<div class="info-line"><span>文件名称</span><strong>DSC_0${842+s.photo}.JPG</strong></div><div class="info-line"><span>当前显示</span><strong>${s.downloaded?'已下载原图':'预览图 · 320 × 200'}</strong></div><div class="info-line"><span>原图大小</span><strong>24.6 MB · 示例</strong></div><p class="demo-callout">这里只演示界面。浏览不自动传输原图，点击下载才保存完整照片。</p>`);break;
      case 'download':if(s.downloaded){toast(app,'设计示例：原图已保存在手机');return;}s.downloaded=true;app.classList.add('downloaded');b.innerHTML=`${icon('check')}已保存`;app.querySelector('.viewer-label').innerHTML='<strong>原图已保存</strong><small>可离线查看</small>';toast(app,'交互演示：原图已保存');break;
    }
  });
  const stage=app.querySelector('.viewer-stage');
  if(stage){let clickTimer=0;stage.onclick=()=>{clearTimeout(clickTimer);clickTimer=setTimeout(()=>setViewerChrome(app,!s.chrome),240);};stage.ondblclick=e=>{e.preventDefault();clearTimeout(clickTimer);s.zoom=s.zoom===1?2:1;const photo=stage.querySelector('.viewer-photo');spring(photo,s.zoom,v=>photo.style.transform=`scale(${v})`,s.zoom===1?2:1);};stage.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();setViewerChrome(app,!s.chrome);}};}
}
function reset(){Object.assign(cameraState,{connected:true,mode:'wifi',host:'192.168.1.1',port:'15740',endpoint:''});apps.forEach(app=>{state.set(app,initial(app));render(app);});}
reset();
document.querySelector('#theme-toggle').onclick=()=>{const dark=document.documentElement.dataset.theme!=='dark';document.documentElement.dataset.theme=dark?'dark':'light';const b=document.querySelector('#theme-toggle');b.innerHTML=`${icon(dark?'sun':'moon')}<span>${dark?'浅色外观':'深色外观'}</span>`;b.setAttribute('aria-label',`切换为${dark?'浅色':'深色'}外观`);};
document.querySelector('#empty-toggle').onclick=()=>{emptyMode=!emptyMode;document.querySelector('#empty-toggle').textContent=emptyMode?'查看示例内容':'查看空状态';apps.filter(app=>state.get(app).screen==='library').forEach(render);};
document.querySelector('#reset').onclick=()=>{emptyMode=false;document.querySelector('#empty-toggle').textContent='查看空状态';reset();};
document.querySelectorAll('[data-phone]').forEach(b=>{if(b.tagName!=='BUTTON')return;b.onclick=()=>{document.querySelectorAll('.mobile-screens button').forEach(x=>x.classList.toggle('active',x===b));document.querySelectorAll('.device-card').forEach(x=>x.classList.toggle('mobile-active',x.dataset.phone===b.dataset.phone));};});
