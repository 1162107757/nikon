const base = new URL('../', import.meta.url), byId = id => document.getElementById(id);
const legacyOrigin = 'https://nikon-d8girdjsr252fd90e-1252602548.ap-shanghai.app.tcloudbase.com';
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;

export function releaseDownloadUrl(release, currentBase = base) {
  if (!release || !/^[a-f0-9]{64}$/.test(release.sha256) || !/^[a-f0-9]{64}$/.test(release.signerSha256)
      || !Number.isSafeInteger(release.versionCode) || release.versionCode < 1
      || !Number.isSafeInteger(release.byteLength) || release.byteLength < 1 || release.byteLength > 4 * 1024 * 1024) {
    throw new Error('版本信息不完整，请稍后重试。');
  }
  try {
    if (typeof release.id !== 'string' || !UUID.test(release.id) || typeof release.downloadUrl !== 'string') throw new Error();
    const current = new URL(currentBase), canonical = new URL(release.downloadUrl);
    const path = `releases/${release.id}/file`;
    const currentRoute = canonical.origin === current.origin && canonical.pathname === current.pathname + path;
    const legacyRoute = canonical.origin === legacyOrigin && canonical.pathname === '/preset-library/' + path;
    if (!['http:', 'https:'].includes(current.protocol) || current.username || current.password || current.search || current.hash
        || !current.pathname.endsWith('/') || canonical.username || canonical.password || canonical.search || canonical.hash
        || release.downloadUrl.includes('?') || release.downloadUrl.includes('#')
        || release.downloadUrl !== canonical.href || !(currentRoute || legacyRoute)) throw new Error();
    // Follow only the current site's validated release route; canonical metadata stays compatible with old Android builds.
    return new URL(path, current);
  } catch { throw new Error('下载地址校验失败，请稍后重试。'); }
}
let activeChannel = new URL(location.href).searchParams.get('channel') === 'beta' ? 'beta' : 'stable';
let serial = 0;
const android = sdk => ({24:'7.0',25:'7.1',26:'8.0',27:'8.1',28:'9',29:'10',30:'11',31:'12',32:'12L',33:'13',34:'14',35:'15',36:'16',37:'17'}[sdk] || `API ${sdk}`);
function pageUrl() { const url = new URL('./', import.meta.url); if (activeChannel === 'beta') url.searchParams.set('channel', 'beta'); return url.href; }
function drawQr() {
  const target = byId('qr'); target.replaceChildren();
  if (!globalThis.qrcodegen) { target.textContent = '请使用下方链接'; return; }
  const qr = globalThis.qrcodegen.QrCode.encodeText(pageUrl(), globalThis.qrcodegen.QrCode.Ecc.MEDIUM), ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg'); svg.setAttribute('viewBox', `0 0 ${qr.size + 8} ${qr.size + 8}`); svg.setAttribute('shape-rendering', 'crispEdges');
  const path = document.createElementNS(ns, 'path'); let d = '';
  for (let y = 0; y < qr.size; y++) for (let x = 0; x < qr.size; x++) if (qr.getModule(x,y)) d += `M${x+4},${y+4}h1v1h-1z`;
  path.setAttribute('d', d); path.setAttribute('fill', '#20242B'); svg.append(path); target.append(svg);
}
async function latest(channel) {
  const response = await fetch(new URL(`releases/latest?channel=${channel}&packageName=cc.huevia`, base), { credentials:'omit', cache:'no-store', headers:{Accept:'application/json'} });
  const value = await response.json(); if (!response.ok) throw new Error(value?.error?.message || '版本暂时无法读取，请稍后重试。');
  return value;
}
function chooseChannel(channel) {
  activeChannel = channel;
  document.querySelectorAll('[data-channel]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.channel === channel)));
  history.replaceState(null, '', pageUrl());
}
function render(data, fallback = false) {
  const release = data.release;
  byId('release').hidden = !release; byId('details').hidden = !release; byId('empty').hidden = Boolean(release);
  byId('channel-note').textContent = fallback ? '暂无正式版，以下为测试版，功能仍在调整。' : activeChannel === 'beta' ? '测试版用于提前体验，功能仍在调整。' : '';
  if (!release) {
    byId('empty-title').textContent = data.reason === 'withdrawn' ? '当前版本已撤回' : `暂未发布${activeChannel === 'beta' ? '测试版' : '正式版'}`;
    byId('empty-copy').textContent = data.reason === 'withdrawn' ? '请等待修复版本上架。已下载的旧包不会被自动替换。' : '安装包上架后，会显示在这里。'; return;
  }
  const download = releaseDownloadUrl(release);
  byId('version').textContent = `Huevia ${release.versionName}`; byId('channel-label').textContent = activeChannel === 'beta' ? '测试版' : '正式版';
  byId('download-button').href = download.href;
  const time = new Intl.DateTimeFormat('zh-CN', {year:'numeric',month:'long',day:'numeric'}).format(new Date(release.publishedAt));
  byId('metadata').textContent = `Android ${android(release.minSdk)} 及以上 · ${(release.byteLength/1048576).toFixed(2)} MiB · ${time}`;
  byId('notes').textContent = release.notes; byId('sha256').textContent = release.sha256; byId('package').textContent = release.packageName; byId('signer').textContent = release.signerSha256;
  drawQr();
}
async function load({allowFallback = false} = {}) {
  const request = ++serial; byId('loading').hidden = false; byId('empty').hidden = true; byId('release').hidden = true; byId('details').hidden = true;
  try {
    let data = await latest(activeChannel), fallback = false;
    if (allowFallback && activeChannel === 'stable' && data.reason === 'no_release') {
      const beta = await latest('beta');
      if (beta.release) { if (request !== serial) return; chooseChannel('beta'); data = beta; fallback = true; }
    }
    if (request !== serial) return; render(data, fallback);
  } catch(error) {
    if (request !== serial) return;
    byId('release').hidden = true; byId('details').hidden = true; byId('empty').hidden = false;
    byId('empty-title').textContent = '暂时无法读取版本'; byId('empty-copy').textContent = error.message;
  } finally { if (request === serial) byId('loading').hidden = true; }
}
document.querySelectorAll('[data-channel]').forEach(button => button.addEventListener('click', () => { chooseChannel(button.dataset.channel); load(); }));
byId('retry').addEventListener('click', () => load());
byId('copy-link').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText(pageUrl()); byId('copy-status').textContent = '链接已复制'; }
  catch { byId('copy-status').textContent = '请复制浏览器地址栏中的链接。'; }
});
chooseChannel(activeChannel); load({allowFallback:true});
