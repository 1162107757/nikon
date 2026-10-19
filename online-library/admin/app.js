import { api, ApiError } from './api.js';
import { SUGGESTED_CATEGORIES, normalizeCategory, categoryNames, categoryOptions, categoryLabel, categoryMatches } from './categories.js';
import { mountReleases } from './releases.js';
import { mountCommunity } from './community.js';

const $ = selector => document.querySelector(selector);
const main = $('#main'), editor = $('#editor'), confirmBox = $('#confirm'), userDetail = $('#user-detail');
const state = { session: null, items: [], loaded: false, loading: false, error: '', query: '', status: 'all', category: null, sort: 'updated', page: 1 };
const users = { items: [], total: 0, loaded: false, loading: false, error: '', query: '', status: 'all', page: 1, pageSize: 20, summary: null, qqLogin: null };
const pageSize = 20;
let editorToken = 0, editorBusy = false, editorDirty = false, editorCleanup = null, noticeTimer, confirmResolve = null, resumeEditorAfterLogin = false;
const pageNames = { presets: '预设管理', users: '用户管理', community: '社区管理', releases: 'App 版本' };
let currentPage = Object.hasOwn(pageNames, location.hash.slice(1)) ? location.hash.slice(1) : 'presets', userRequest = 0, userController = null, userSearchTimer, detailRequest = 0, detailController = null, detailScrollPosition = null, mountedModule = null;
const shapes = {
  plus: '<path d="M12 5v14M5 12h14"/>', search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4 4"/>',
  copy: '<rect x="8" y="8" width="12" height="13" rx="2"/><path d="M16 8V4a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v11a1 1 0 0 0 1 1h4"/>',
  close: '<path d="m6 6 12 12M6 18 18 6"/>', library: '<rect x="4" y="4" width="16" height="16" rx="3"/><circle cx="9" cy="9" r="1.5"/><path d="m5 17 5-5 4 4 3-3 3 4"/>',
  alert: '<circle cx="12" cy="12" r="9"/><path d="M12 7v6M12 17h.01"/>', link: '<path d="m10 14 4-4M8 16l-1 1a4 4 0 0 1-6-6l4-4a4 4 0 0 1 6 0M16 8l1-1a4 4 0 0 1 6 6l-4 4a4 4 0 0 1-6 0"/>',
  users: '<circle cx="9" cy="8" r="3"/><path d="M3 20v-2a6 6 0 0 1 12 0v2M16 5a3 3 0 0 1 0 6M17 14a5 5 0 0 1 4 5v1"/>',
};
function icon(name) { const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('aria-hidden', 'true'); svg.classList.add('icon'); svg.innerHTML = shapes[name] || shapes.library; return svg; }
function h(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [name, value] of Object.entries(attrs)) {
    if (name === 'class') node.className = value;
    else if (name.startsWith('on')) node.addEventListener(name.slice(2).toLowerCase(), value);
    else if (value === true) node.setAttribute(name, '');
    else if (value !== false && value != null) node.setAttribute(name, String(value));
  }
  for (const child of children.flat()) if (child != null) node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  return node;
}
function button(text, action, style = '', glyph) { return h('button', { type: 'button', class: `button ${style}`, onclick: action }, glyph ? icon(glyph) : null, text); }
function notice(message, error = false) { clearTimeout(noticeTimer); const box = $('#notice'); box.textContent = message; box.className = `notice${error ? ' error' : ''}`; box.hidden = false; noticeTimer = setTimeout(() => { box.hidden = true; }, error ? 8000 : 4500); }
function message(error) { return error instanceof Error ? error.message : '操作未完成，请稍后重试。'; }
function fileSize(value) { const bytes = Number(value); return !Number.isFinite(bytes) ? '大小未知' : bytes < 1024 ? `${bytes} B` : bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KiB` : `${(bytes / 1024 / 1024).toFixed(1)} MiB`; }
function presetFormat(preset) { return preset?.file?.fileFormat === 'NP3' || preset?.fileFormat === 'NP3' ? 'NP3' : 'NCP'; }
function presetFileLabel(preset) { return `${presetFormat(preset)} · ${preset.file.originalName} · ${fileSize(preset.file.byteLength)}`; }
function date(value) { if (!value) return '尚未记录'; const parsed = new Date(value); return Number.isNaN(parsed.getTime()) ? '时间未知' : new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(parsed); }
function safeUrl(value) { try { const url = new URL(value, location.href); return ['http:', 'https:'].includes(url.protocol) ? url.href : ''; } catch { return ''; } }
function previewUrl(asset) { return asset?.adminUrl || asset?.url ? safeUrl(asset.adminUrl || asset.url) : ''; }
function status(preset) { const names = { draft: '草稿', published: '已上架', unpublished: '已下架' }; return h('span', { class: `status ${['draft', 'published', 'unpublished'].includes(preset.status) ? preset.status : ''}` }, `${names[preset.status] || '未知状态'}${preset.hasUnpublishedChanges && preset.status === 'published' ? ' · 待更新' : ''}`); }
function empty(title, detail, action, actionText = '重试', glyph = 'library') { return h('div', { class: 'empty-state' }, h('div', { class: 'empty-symbol' }, icon(glyph)), h('h2', {}, title), h('p', {}, detail), action ? button(actionText, action) : null); }
function banner(title, detail) { return h('div', { class: 'error-banner', role: 'alert' }, icon('alert'), h('div', {}, h('strong', {}, title), h('p', {}, detail))); }
function setSession(value) {
  state.session = value;
  const authenticated = Boolean(value?.authenticated);
  document.body.classList.toggle('authenticated', authenticated);
  $('#sidebar').hidden = !authenticated; $('#workspace-location').hidden = !authenticated;
  $('#account').textContent = value?.user?.username || '管理员'; $('#account').hidden = !authenticated; $('#logout').hidden = !authenticated;
  $('#environment').textContent = value?.environmentId || '';
  for (const item of document.querySelectorAll('[data-admin-only]')) item.hidden = !authenticated;
  if (!authenticated) { mountedModule?.destroy(); mountedModule = null; }
  if (!authenticated) { cancelUsers(); closeUserDetail(); users.items = []; users.loaded = false; users.summary = null; users.qqLogin = null; }
}
function sessionExpired(error) {
  if (error?.status !== 401) return false;
  resumeEditorAfterLogin = editor.open || resumeEditorAfterLogin;
  if (editor.open) editor.close();
  setSession(null); state.items = []; state.loaded = false;
  renderLogin(resumeEditorAfterLogin ? '登录已过期。重新登录后会回到编辑页，未保存的内容仍保留。' : '登录已过期，请重新登录。');
  return true;
}
function select(options, label, value) { const node = h('select', { 'aria-label': label }, options.map(([key, text]) => h('option', { value: key }, text))); node.value = value; return node; }
function theme(value) { if (value === 'system') delete document.documentElement.dataset.theme; else document.documentElement.dataset.theme = value; try { localStorage.setItem('preset-admin-theme', value); } catch {} }
try { const saved = localStorage.getItem('preset-admin-theme'); if (['dark', 'light', 'system'].includes(saved)) $('#theme').value = saved; } catch {}
theme($('#theme').value); $('#theme').addEventListener('change', event => theme(event.target.value));

async function boot() {
  main.replaceChildren(h('div', { class: 'initial-state', role: 'status' }, h('span', { class: 'spinner' }), h('p', {}, '正在连接管理服务…')));
  try {
    const session = await api.session(); setSession(session);
    if (session.authenticated) await showPage(currentPage);
    else { const health = await api.health(); renderLogin(health.configured === false ? '管理服务尚未配置。请完成服务端数据库与管理员设置后重新连接。' : ''); }
  }
  catch (error) { renderLogin(message(error)); }
}
function renderLogin(problem = '') {
  const username = h('input', { id: 'username', name: 'username', autocomplete: 'username', required: true, maxlength: 64, placeholder: '管理员账号' });
  const password = h('input', { id: 'password', name: 'password', type: 'password', autocomplete: 'current-password', required: true });
  const feedback = h('div', { class: 'field-error', role: 'alert' });
  const submit = h('button', { type: 'submit', class: 'button' }, '登录');
  const form = h('form', { class: 'login-card' }, field('账号', username), field('密码', password), feedback, submit);
  form.addEventListener('submit', async event => {
    event.preventDefault(); if (!form.reportValidity()) return; submit.disabled = true; submit.textContent = '正在登录…'; feedback.textContent = '';
    try {
      const session = await api.login(username.value.trim(), password.value); if (!session.authenticated) throw new ApiError('账号尚未获得管理员访问权限。');
      password.value = ''; setSession(session); await showPage(resumeEditorAfterLogin ? 'presets' : currentPage);
      if (resumeEditorAfterLogin && state.session?.authenticated) { resumeEditorAfterLogin = false; editor.showModal(); notice('登录已恢复，未保存的内容仍保留'); }
    }
    catch (error) { feedback.textContent = message(error); password.value = ''; password.focus(); }
    finally { submit.disabled = false; submit.textContent = '登录'; }
  });
  main.replaceChildren(h('section', { class: 'login-wrap' }, h('div', { class: 'login-intro' }, h('img', { src: './mark.svg', alt: '', width: 52, height: 52 }), h('h1', {}, '登录管理后台'), h('p', {}, '管理预设、用户、社区与 App 版本。')), problem ? banner(problem.startsWith('登录已过期') ? '需要重新登录' : '管理服务暂不可用', problem) : null, form,
    problem ? button('重新连接', boot, 'quiet') : null, h('p', { class: 'login-foot' }, '仅限管理员访问。登录状态由当前站点安全保存。')));
}
$('#logout').addEventListener('click', async () => { if (editor.open && !(await dismissEditor())) return; try { await api.logout(); state.items = []; state.loaded = false; users.items = []; users.loaded = false; setSession(null); renderLogin(); notice('已退出登录'); } catch (error) { if (!sessionExpired(error)) notice(message(error), true); } });

async function showPage(page) {
  if (!state.session?.authenticated) return;
  mountedModule?.destroy(); mountedModule = null;
  currentPage = Object.hasOwn(pageNames, page) ? page : 'presets';
  for (const item of document.querySelectorAll('[data-page]')) { if (item.dataset.page === currentPage) item.setAttribute('aria-current', 'page'); else item.removeAttribute('aria-current'); }
  $('#current-page-name').textContent = pageNames[currentPage];
  document.title = `${$('#current-page-name').textContent} · 相机预设`;
  if (currentPage === 'releases' || currentPage === 'community') { cancelUsers(); closeUserDetail(); mountedModule = (currentPage === 'releases' ? mountReleases : mountCommunity)(main, { api, onError: sessionExpired }); resetWorkspacePosition(); }
  else if (currentPage === 'users') { renderUsers(); resetWorkspacePosition(); await loadUsers(); }
  else { cancelUsers(); closeUserDetail(); renderLibrary(); resetWorkspacePosition(); if (!state.loaded) await loadPresets(); }
}
function resetWorkspacePosition() { main.focus({ preventScroll: true }); window.scrollTo({ left: 0, top: 0, behavior: 'instant' }); }
for (const link of document.querySelectorAll('[data-page]')) link.addEventListener('click', async event => {
  event.preventDefault(); if (editor.open && !(await dismissEditor())) return;
  if (link.dataset.page === currentPage) return;
  location.hash = link.dataset.page;
});
addEventListener('hashchange', () => { showPage(location.hash.slice(1)); });

function renderLibrary() {
  const search = h('input', { type: 'search', placeholder: '搜索名称、分类、简介或机型', value: state.query, 'aria-label': '搜索预设' });
  const filter = select([['all', '全部状态'], ['published', '已上架'], ['draft', '草稿'], ['unpublished', '已下架']], '按状态筛选', state.status);
  const categoryFilter = select(categoryOptions(state.items, state.category), '按分类筛选', state.category === null ? 'all' : `category:${state.category}`); categoryFilter.id = 'category-filter';
  const sort = select([['updated', '最近更新'], ['name', '名称 A–Z'], ['order', '目录排序']], '排序', state.sort); sort.className = 'sort';
  search.addEventListener('input', () => { state.query = search.value; state.page = 1; renderRows(); });
  filter.addEventListener('change', () => { state.status = filter.value; state.page = 1; renderRows(); });
  categoryFilter.addEventListener('change', () => { state.category = categoryFilter.value === 'all' ? null : categoryFilter.value.slice('category:'.length); state.page = 1; renderRows(); });
  sort.addEventListener('change', () => { state.sort = sort.value; state.page = 1; renderRows(); });
  main.replaceChildren(h('div', { class: 'page-heading' }, h('div', {}, h('h1', {}, '预设管理'), h('p', {}, '上传预设、维护效果图，并管理 App 在线库的上架内容。')), h('div', { class: 'heading-actions' }, button('App 目录地址', showCatalog, 'secondary', 'copy'), button('新增预设', () => openEditor(), '', 'plus'))),
    h('div', { class: 'toolbar preset-toolbar' }, h('div', { class: 'search' }, icon('search'), search), filter, categoryFilter, sort), h('p', { class: 'summary', id: 'summary', 'aria-live': 'polite' }), h('section', { class: 'library', id: 'library', 'aria-label': '预设列表' }), h('div', { class: 'footer-note' }, h('span', {}, '草稿只在此处可见。上架后，App 才会更新目录。'), button('刷新列表', loadPresets, 'quiet')));
  renderRows();
}
async function loadPresets() {
  if (state.loading) return; state.loading = true; state.error = ''; renderRows();
  const session = state.session;
  try { const result = await api.presets(); if (state.session !== session) return; if (!Array.isArray(result.items)) throw new ApiError('预设列表格式不正确，请联系服务管理员。'); state.items = result.items; state.loaded = true; }
  catch (error) { if (state.session === session && !sessionExpired(error)) state.error = message(error); }
  finally { state.loading = false; renderRows(); }
}
function renderRows() {
  const library = $('#library'), summary = $('#summary'); if (!library) return;
  const categoryFilter = $('#category-filter');
  if (categoryFilter) { categoryFilter.replaceChildren(...categoryOptions(state.items, state.category).map(([value, label]) => h('option', { value }, label))); categoryFilter.value = state.category === null ? 'all' : `category:${state.category}`; }
  if (state.loading && !state.loaded) { summary.textContent = '正在读取预设…'; library.replaceChildren(...Array.from({ length: 3 }, () => h('div', { class: 'skeleton-row', 'aria-hidden': 'true' }, h('div', { class: 'skeleton-block' }), h('div', { class: 'skeleton-lines' })))); return; }
  if (state.error) { summary.textContent = ''; library.replaceChildren(empty('列表暂时无法读取', state.error, loadPresets, '重新读取', 'alert')); return; }
  let items = [...state.items]; const query = state.query.trim().toLocaleLowerCase();
  if (query) items = items.filter(preset => [preset.name, preset.category, preset.description, presetFormat(preset), ...(preset.compatibleModels || [])].join(' ').toLocaleLowerCase().includes(query));
  if (state.status !== 'all') items = items.filter(preset => preset.status === state.status);
  items = items.filter(preset => categoryMatches(preset, state.category));
  items.sort((a, b) => state.sort === 'name' ? String(a.name).localeCompare(String(b.name), 'zh-CN') : state.sort === 'order' ? (a.sortOrder || 0) - (b.sortOrder || 0) || String(a.id).localeCompare(String(b.id)) : (new Date(b.updatedAt).getTime() || 0) - (new Date(a.updatedAt).getTime() || 0));
  const totalPages = Math.max(1, Math.ceil(items.length / pageSize)); state.page = Math.min(state.page, totalPages);
  const published = state.items.filter(preset => preset.status === 'published').length;
  summary.textContent = query || state.status !== 'all' || state.category !== null ? `找到 ${items.length} 款预设` : `${state.items.length} 款预设 · ${published} 款已上架${state.loading ? ' · 正在刷新…' : ''}`;
  if (!items.length) { library.replaceChildren(state.items.length ? empty('没有找到匹配的预设', '试试其他关键词，或更改分类与状态筛选。') : empty('第一款预设，从这里开始', '添加 NCP 或 NP3 文件、效果图与介绍，先保存草稿，再决定何时上架。', () => openEditor(), '添加预设')); return; }
  const head = h('div', { class: 'list-head', 'aria-hidden': 'true' }, h('span', {}, '预设'), h('span', {}, '状态'), h('span', { class: 'date-column' }, '更新时间'), h('span', {}, '操作'));
  library.replaceChildren(head, ...items.slice((state.page - 1) * pageSize, state.page * pageSize).map(presetRow));
  if (totalPages > 1) { const previous = button('上一页', () => { state.page--; renderRows(); }, 'secondary'), next = button('下一页', () => { state.page++; renderRows(); }, 'secondary'); previous.disabled = state.page <= 1; next.disabled = state.page >= totalPages; library.append(h('nav', { class: 'pagination', 'aria-label': '列表分页' }, previous, h('span', { class: 'muted' }, `${state.page} / ${totalPages}`), next)); }
}
function presetRow(preset) {
  const url = previewUrl(preset.preview);
  const image = url ? h('img', { class: 'preset-image', src: url, alt: `${preset.name} 效果图`, loading: 'lazy', decoding: 'async', referrerpolicy: 'same-origin' }) : h('div', { class: 'preset-image image-missing' }, '未添加效果图');
  if (url) image.addEventListener('error', () => image.replaceWith(h('div', { class: 'preset-image image-missing' }, '效果图暂不可用')), { once: true });
  const models = Array.isArray(preset.compatibleModels) ? preset.compatibleModels : [];
  return h('article', { class: 'preset-row' }, h('div', { class: 'preset-main' }, image, h('div', { class: 'preset-copy' }, h('h2', { class: 'preset-name' }, preset.name || '未命名预设'), h('p', { class: 'preset-category' }, `${presetFormat(preset)} · ${categoryLabel(preset)}`), h('p', { class: 'preset-description' }, preset.description || '还没有添加简介'), h('p', { class: 'preset-models' }, models.length ? models.slice(0, 3).join(' · ') + (models.length > 3 ? ` 等 ${models.length} 款` : '') : '未标注兼容机型'))), status(preset), h('time', { class: 'time', datetime: preset.updatedAt || '' }, date(preset.updatedAt)), h('button', { type: 'button', class: 'row-edit', 'aria-label': `编辑${preset.name || '预设'}`, onclick: () => openEditor(preset) }, '编辑'));
}
async function showCatalog() {
  const url = safeUrl(state.session?.catalogUrl || '');
  if (!state.session?.catalogUrl || !url) { notice('服务尚未提供 App 目录地址，请先完成目录配置。', true); return; }
  try { await navigator.clipboard.writeText(url); notice('已复制 App 目录地址'); }
  catch { await confirmDialog('App 目录地址', '复制下方地址，在 App 的在线预设目录中使用。', { input: url, label: '完成', cancel: false }); }
}

function cancelUsers() { clearTimeout(userSearchTimer); userRequest++; userController?.abort(); userController = null; users.loading = false; }
function userStatus(user) { return h('span', { class: `status ${user.status === 'active' ? 'published' : user.status === 'disabled' ? 'unpublished' : ''}` }, user.status === 'active' ? '正常' : user.status === 'disabled' ? '已停用' : '未知状态'); }
function userSource(user) { return user.provider === 'password' ? '账号密码' : user.provider === 'qq' ? 'QQ 登录' : '未知来源'; }
function fullDate(value) { if (!value) return '尚未登录'; const parsed = new Date(value); return Number.isNaN(parsed.getTime()) ? '时间未知' : new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(parsed); }
function avatar(user, large = false) {
  const fallback = h('span', { class: `user-avatar${large ? ' large' : ''}`, 'aria-hidden': 'true' }, icon('users'));
  const url = safeUrl(user.avatarUrl || '');
  if (!url || new URL(url).protocol !== 'https:') return fallback;
  const image = h('img', { class: `user-avatar${large ? ' large' : ''}`, src: url, alt: '', referrerpolicy: 'no-referrer', loading: 'lazy', decoding: 'async' });
  image.addEventListener('error', () => image.replaceWith(fallback), { once: true }); return image;
}
function renderUsers() {
  const search = h('input', { type: 'search', placeholder: '搜索用户名、昵称或用户 ID', value: users.query, 'aria-label': '搜索用户' });
  const filter = select([['all', '全部状态'], ['active', '正常'], ['disabled', '已停用']], '按用户状态筛选', users.status);
  search.addEventListener('input', () => { users.query = search.value; users.page = 1; cancelUsers(); userSearchTimer = setTimeout(loadUsers, 300); });
  search.addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); clearTimeout(userSearchTimer); loadUsers(); } });
  filter.addEventListener('change', () => { users.status = filter.value; users.page = 1; loadUsers(); });
  main.replaceChildren(h('div', { class: 'page-heading' }, h('div', {}, h('h1', {}, '用户管理'), h('p', {}, '查看 App 用户资料、注册与登录记录，管理账号状态。')), h('div', { class: 'heading-actions' }, button('刷新用户', loadUsers, 'secondary'))),
    h('section', { id: 'qq-login-status', class: 'integration-note', 'aria-label': 'QQ 登录接入状态' }),
    h('div', { class: 'toolbar user-toolbar' }, h('div', { class: 'search' }, icon('search'), search), filter),
    h('div', { id: 'user-summary', class: 'summary user-summary', 'aria-live': 'polite' }),
    h('section', { id: 'users-list', class: 'library users-library', 'aria-label': 'App 用户列表' }),
    h('p', { class: 'footer-note' }, '此处展示 App 普通用户。后台管理员账号单独管理。'));
  renderUserRows();
}
async function loadUsers() {
  clearTimeout(userSearchTimer); userController?.abort(); userController = new AbortController(); const controller = userController, token = ++userRequest;
  if (!state.session?.authenticated || currentPage !== 'users') return;
  users.loading = true; users.error = ''; renderUserRows();
  try {
    const result = await api.users({ q: users.query.trim(), status: users.status, page: users.page, pageSize: users.pageSize }, { signal: controller.signal });
    if (token !== userRequest || currentPage !== 'users' || !state.session?.authenticated) return;
    if (!Array.isArray(result.items) || !Number.isSafeInteger(result.total) || result.total < 0 || !Number.isSafeInteger(result.page) || result.page < 1 || !Number.isSafeInteger(result.pageSize) || result.pageSize < 1 || result.items.some(user => !user || typeof user.id !== 'string')) throw new ApiError('用户列表格式不正确，请联系服务管理员。');
    users.items = result.items; users.total = result.total; users.page = result.page; users.pageSize = result.pageSize; users.summary = result.summary || null; users.qqLogin = result.qqLogin || null; users.loaded = true;
  } catch (error) { if (token === userRequest && error.name !== 'AbortError' && !sessionExpired(error)) users.error = message(error); }
  finally { if (token === userRequest) { users.loading = false; renderUserRows(); } }
}
function renderUserRows() {
  const list = $('#users-list'), summary = $('#user-summary'), integration = $('#qq-login-status'); if (!list || !summary || !integration) return;
  const configured = users.qqLogin?.configured === true;
  integration.replaceChildren(icon('link'), h('div', {}, h('strong', {}, 'App 账号登录'), h('p', {}, users.qqLogin ? `用户可在 App 中使用账号密码注册登录。${users.qqLogin.message}` : users.error ? '接入状态暂时无法读取，重新读取用户列表后将更新。' : '正在读取接入状态…')), h('span', { class: `status ${configured ? 'published' : ''}` }, configured ? 'QQ 已配置' : users.qqLogin ? 'QQ 待接入' : '读取中'));
  list.setAttribute('aria-busy', String(users.loading));
  if (users.loading) { summary.textContent = '正在读取用户…'; list.replaceChildren(...Array.from({ length: 3 }, () => h('div', { class: 'skeleton-row user-skeleton', 'aria-hidden': 'true' }, h('div', { class: 'user-avatar' }), h('div', { class: 'skeleton-lines' })))); return; }
  if (users.error) { summary.textContent = ''; list.replaceChildren(empty('用户暂时无法读取', users.error, loadUsers, '重新读取', 'alert')); return; }
  summary.replaceChildren(h('span', {}, users.query.trim() || users.status !== 'all' ? `找到 ${users.total} 位用户` : `共 ${users.total} 位用户`));
  if (users.summary && Number.isSafeInteger(users.summary.active) && Number.isSafeInteger(users.summary.disabled)) summary.append(h('span', {}, `正常 ${users.summary.active} · 已停用 ${users.summary.disabled}`));
  if (!users.items.length) { list.replaceChildren(users.query.trim() || users.status !== 'all' ? empty('没有找到匹配的用户', '试试其他用户名、昵称或用户 ID，或更改状态筛选。', null, '', 'users') : empty('暂无用户', '用户在 App 中注册后，会显示在这里。', null, '', 'users')); return; }
  const head = h('div', { class: 'user-list-head', 'aria-hidden': 'true' }, h('span', {}, '用户'), h('span', {}, '来源 / 状态'), h('span', {}, '注册时间'), h('span', {}, '最近登录'), h('span', {}, '操作'));
  list.replaceChildren(head, ...users.items.map(user => h('article', { class: 'user-row' },
    h('div', { class: 'user-identity' }, avatar(user), h('div', { class: 'user-copy' }, h('h2', {}, user.nickname || user.username || '未设置昵称'), h('p', { title: user.id }, user.username ? `账号 ${user.username} · ${user.id}` : user.id))),
    h('div', { class: 'user-source' }, h('span', {}, userSource(user)), userStatus(user)),
    h('div', { class: 'user-time', 'data-label': '注册时间' }, h('time', { datetime: user.createdAt || '' }, fullDate(user.createdAt))),
    h('div', { class: 'user-time', 'data-label': '最近登录' }, h('time', { datetime: user.lastLoginAt || '' }, fullDate(user.lastLoginAt)), h('small', {}, `累计登录 ${Number.isSafeInteger(user.loginCount) && user.loginCount >= 0 ? user.loginCount : 0} 次`)),
    h('button', { class: 'row-edit', type: 'button', 'aria-label': `查看${user.nickname || '用户'}的详细信息`, onclick: () => openUserDetail(user.id) }, '详情'))));
  const pages = Math.max(1, Math.ceil(users.total / users.pageSize));
  if (pages > 1) { const previous = button('上一页', () => { users.page--; loadUsers(); }, 'secondary'), next = button('下一页', () => { users.page++; loadUsers(); }, 'secondary'); previous.disabled = users.page <= 1; next.disabled = users.page >= pages; list.append(h('nav', { class: 'pagination', 'aria-label': '用户列表分页' }, previous, h('span', { class: 'muted' }, `${users.page} / ${pages}`), next)); }
}
function closeUserDetail() {
  detailRequest++; detailController?.abort(); detailController = null;
  if (userDetail.open) {
    userDetail.close();
    if (detailScrollPosition) window.scrollTo({ ...detailScrollPosition, behavior: 'instant' });
  }
  detailScrollPosition = null;
}
userDetail.addEventListener('cancel', event => { event.preventDefault(); closeUserDetail(); });
async function openUserDetail(id) {
  detailController?.abort(); detailController = new AbortController(); const controller = detailController, token = ++detailRequest;
  const close = button('关闭', closeUserDetail, 'quiet'); close.setAttribute('aria-label', '关闭用户详情');
  const content = h('div', { class: 'editor-body' });
  userDetail.replaceChildren(h('div', { class: 'editor-head' }, h('h2', { id: 'user-detail-title' }, '用户详情'), close), content);
  if (!userDetail.open) { detailScrollPosition = { left: window.scrollX, top: window.scrollY }; userDetail.showModal(); }
  const active = () => token === detailRequest && userDetail.open && state.session?.authenticated;
  function details(user) {
    const feedback = h('p', { class: 'field-error', role: 'alert' });
    const toggle = button(user.status === 'disabled' ? '启用账号' : '停用账号', async () => {
      const disabled = user.status !== 'disabled';
      if (!(await confirmDialog(disabled ? `停用「${user.nickname || '此用户'}」？` : `启用「${user.nickname || '此用户'}」？`, disabled ? '此用户的 App 登录会话将失效，在重新启用前无法登录。用户资料与历史记录会保留。' : '此用户将可以重新登录 App。旧登录会话不会恢复。', { label: disabled ? '确认停用' : '确认启用', danger: disabled })) || !active()) return;
      toggle.disabled = true; toggle.textContent = '正在更新…'; feedback.textContent = '';
      try { const result = await api.setUserStatus(user.id, disabled ? 'disabled' : 'active', user.updatedAt); if (!active()) return; if (!result.user?.id) throw new ApiError('用户状态返回格式不正确，请重新打开详情核对。'); details(result.user); notice(disabled ? '账号已停用，App 登录会话已撤销' : '账号已启用'); await loadUsers(); }
      catch (error) {
        if (!active() || sessionExpired(error)) return;
        if (error.status === 409) { feedback.textContent = '用户资料已被更新，请重新读取后再操作。'; content.append(button('重新读取详情', () => openUserDetail(user.id), 'secondary')); }
        else feedback.textContent = message(error);
      } finally { if (toggle.isConnected) { toggle.disabled = false; toggle.textContent = user.status === 'disabled' ? '启用账号' : '停用账号'; } }
    }, user.status === 'disabled' ? '' : 'secondary');
    const rows = [['用户 ID', user.id], ...(user.username ? [['用户名', user.username]] : []), ['登录来源', userSource(user)], ...(user.provider === 'qq' ? [['QQ 标识', user.qqOpenIdMasked || '尚未记录']] : []), ['注册时间', fullDate(user.createdAt)], ['最近登录', fullDate(user.lastLoginAt)], ['累计登录', `${Number.isSafeInteger(user.loginCount) && user.loginCount >= 0 ? user.loginCount : 0} 次`], ['最近更新', fullDate(user.updatedAt)]];
    content.replaceChildren(h('div', { class: 'user-profile' }, avatar(user, true), h('div', {}, h('h3', {}, user.nickname || '未设置昵称'), userStatus(user))), h('dl', { class: 'user-properties' }, rows.flatMap(([label, value]) => [h('dt', {}, label), h('dd', {}, value)])), h('div', { class: 'user-account-actions' }, h('h3', {}, '账号状态'), h('p', {}, user.status === 'disabled' ? '账号已停用，无法登录 App。启用后可重新登录。' : '账号正常，允许登录 App。'), toggle, feedback));
  }
  content.replaceChildren(h('div', { class: 'initial-state', role: 'status' }, h('span', { class: 'spinner' }), h('p', {}, '正在读取用户详情…')));
  try { const result = await api.user(id, { signal: controller.signal }); if (!active()) return; if (!result.user?.id) throw new ApiError('用户详情格式不正确，请联系服务管理员。'); details(result.user); }
  catch (error) { if (active() && error.name !== 'AbortError' && !sessionExpired(error)) content.replaceChildren(empty('用户详情暂时无法读取', message(error), () => openUserDetail(id), '重新读取', 'alert')); }
}
function field(label, input, helper) { if (!input.id) input.id = `field-${Math.random().toString(36).slice(2)}`; return h('div', { class: 'field' }, h('label', { for: input.id }, label), input, helper ? h('small', {}, helper) : null); }

function confirmDialog(title, detail, { label = '确认', danger = false, input = '', cancel = true } = {}) {
  if (confirmBox.open) { confirmResolve?.(false); confirmBox.close(); }
  return new Promise(resolve => {
    confirmResolve = resolve;
    const done = value => { confirmResolve = null; confirmBox.close(); resolve(value); };
    const field = input ? h('input', { readonly: true, value: input, 'aria-label': title, onclick: event => event.target.select() }) : null;
    // Native replaceChildren stringifies null; only include the optional input when present.
    confirmBox.replaceChildren(...[h('h2', { id: 'confirm-title' }, title), h('p', {}, detail), field, h('div', { class: 'confirm-actions' }, cancel ? button('取消', () => done(false), 'secondary') : null, button(label, () => done(true), danger ? 'danger' : ''))].filter(node => node != null));
    confirmBox.oncancel = event => { event.preventDefault(); done(false); }; confirmBox.showModal(); field?.select();
  });
}
async function dismissEditor() {
  if (editorBusy) { notice('正在保存，请稍候。'); return false; }
  if (editorDirty && !(await confirmDialog('放弃未保存的修改？', '文件与介绍还没有保存，关闭后将保留上次保存的版本。', { label: '放弃修改', danger: true }))) return false;
  editorToken++; editorDirty = false; editorCleanup?.(); editorCleanup = null; editor.close(); return true;
}
editor.addEventListener('cancel', event => { event.preventDefault(); dismissEditor(); });

function openEditor(initial = null) {
  if (editor.open) return;
  const token = ++editorToken; let preset = initial, imageObjectUrl = '', tab = 'details'; const uploads = new Map();
  editorDirty = false; editorBusy = false;
  const active = () => editor.open && token === editorToken;
  const close = button('关闭', dismissEditor, 'quiet'); close.setAttribute('aria-label', '关闭预设编辑');
  const title = h('h2', { id: 'editor-title' }, preset ? '编辑预设' : '新增预设');
  const revision = h('p', { class: 'muted' }, preset ? `当前版本 ${preset.revision} · ${preset.status === 'published' ? '修改先存为草稿' : '保存后可上架'}` : '先保存草稿，再发布到 App');
  const details = h('button', { id: 'details-tab', type: 'button', role: 'tab', 'aria-controls': 'editor-panel', 'aria-selected': true }, '详细信息'), history = h('button', { id: 'history-tab', type: 'button', role: 'tab', 'aria-controls': 'editor-panel', 'aria-selected': false, disabled: !preset }, '版本记录');
  const tabs = h('div', { class: 'editor-tabs', role: 'tablist', 'aria-label': '预设编辑内容' }, details, history);
  const body = h('div', { id: 'editor-panel', class: 'editor-body', role: 'tabpanel', 'aria-labelledby': 'details-tab' });
  const save = button(preset ? '保存修改' : '保存草稿', saveDraft);
  const publishLabel = () => preset?.status === 'published' ? preset.hasUnpublishedChanges ? '发布更新' : '已上架' : '上架';
  const publish = button(publishLabel(), () => changeStatus('publish'), 'tint'); publish.hidden = !preset; publish.disabled = preset?.status === 'published' && !preset.hasUnpublishedChanges;
  const unpublish = button('下架', () => changeStatus('unpublish'), 'quiet'); unpublish.hidden = preset?.status !== 'published';
  const footer = h('div', { class: 'editor-footer' }, unpublish, h('div', { class: 'footer-actions' }, publish, save));
  const name = h('input', { name: 'name', required: true, maxlength: 160, value: preset?.name || '', placeholder: '给这款色彩一个名字' });
  const category = h('input', { name: 'category', list: 'preset-category-options', value: preset?.category || '', placeholder: '选择或输入分类', autocomplete: 'off' });
  const categorySuggestions = h('datalist', { id: 'preset-category-options' }, [...new Set([...SUGGESTED_CATEGORIES, ...categoryNames(state.items)])].map(value => h('option', { value })));
  function validateCategoryField() { try { normalizeCategory(category.value); category.setCustomValidity(''); } catch (error) { category.setCustomValidity(message(error)); } }
  category.addEventListener('input', validateCategoryField);
  const description = h('textarea', { name: 'description', maxlength: 2000, placeholder: '描述色彩、适合的场景与使用建议' }, preset?.description || '');
  const models = h('input', { name: 'models', value: (preset?.compatibleModels || []).join('，'), placeholder: '以逗号分隔多个机型' });
  const order = h('input', { name: 'sortOrder', type: 'number', min: -1000000, max: 1000000, step: 1, value: preset?.sortOrder || 0 });
  const ncp = h('input', { type: 'file', name: 'ncp', accept: '.ncp,.np3', 'aria-label': '选择或替换 NCP 或 NP3 文件' });
  const preview = h('input', { type: 'file', name: 'preview', accept: 'image/jpeg,image/png,image/webp', 'aria-label': '选择或替换效果图' });
  const ncpMeta = h('div', { class: 'file-meta' }, preset?.file ? presetFileLabel(preset) : '尚未选择文件');
  const previewBox = h('div');
  const feedback = h('div', { class: 'field-error', role: 'alert' });
  const fields = h('fieldset', {}, h('div', { class: 'asset-section' }, h('div', { class: 'section-title' }, '效果图', h('span', { class: 'muted' }, 'JPEG / PNG / WebP')), previewBox, field('上传或替换效果图', preview, '不超过 4 MiB。使用能够真实表达预设效果的图片。')), field('预设名称', name), field('分类', category, '每款预设可选一个分类，最多 24 个字符。留空为未分类，发布后在 App 生效。'), categorySuggestions, field('简介', description), field('兼容机型', models, '仅填写已核对的机型；留空表示未标注，不代表通用兼容。'), h('div', { class: 'asset-section' }, h('div', { class: 'section-title' }, '预设文件'), field('上传或替换 NCP / NP3', ncp, '不超过 1 MiB。格式按文件内容核验。NP3 目前用于下载保存。替换后旧版本保留。'), ncpMeta), field('目录排序', order, '数字越小越靠前。'), feedback);
  const form = h('form', {}, fields); form.addEventListener('submit', event => { event.preventDefault(); saveDraft(); });
  form.addEventListener('input', () => { editorDirty = true; }); form.addEventListener('change', () => { editorDirty = true; });
  ncp.addEventListener('change', () => { const file = ncp.files[0]; ncpMeta.textContent = file ? `${file.name} · ${fileSize(file.size)} · 保存时核验格式` : preset?.file ? presetFileLabel(preset) : '尚未选择文件'; });
  preview.addEventListener('change', () => {
    if (imageObjectUrl) URL.revokeObjectURL(imageObjectUrl); imageObjectUrl = '';
    const file = preview.files[0]; if (file && file.size <= 4 * 1024 * 1024 && ['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) imageObjectUrl = URL.createObjectURL(file);
    drawPreview();
  });
  editorCleanup = () => { if (imageObjectUrl) URL.revokeObjectURL(imageObjectUrl); };
  function drawPreview() { const url = imageObjectUrl || previewUrl(preset?.preview); previewBox.replaceChildren(url ? h('img', { class: 'asset-preview', src: url, alt: '当前效果图预览' }) : h('div', { class: 'asset-preview preview-placeholder' }, icon('library'), '添加一张效果图')); }
  drawPreview();
  function setBusy(value, text = '正在保存…') { editorBusy = value; fields.disabled = value; save.disabled = value; publish.disabled = value || preset?.status === 'published' && !preset.hasUnpublishedChanges; unpublish.disabled = value; details.disabled = value; history.disabled = value || !preset; close.disabled = value; save.textContent = value ? text : preset ? '保存修改' : '保存草稿'; }
  function showEditorError(error) {
    feedback.replaceChildren(h('p', {}, message(error)));
    if (error?.status === 409) feedback.append(button('读取最新版本', async () => {
      if (!(await confirmDialog('读取最新版本？', '当前未保存的修改将放弃。读取最新版本后，可以继续编辑。', { label: '读取最新版本' })) || !active()) return;
      setBusy(true, '正在读取…');
      try {
        const result = await api.presets(); if (!active()) return;
        const latest = result.items?.find(item => item.id === preset.id); if (!latest) throw new ApiError('这份预设暂时无法读取，请稍后再试。');
        state.items = result.items; editorDirty = false; setBusy(false); await dismissEditor(); openEditor(latest); renderRows();
      } catch (failure) { if (active() && !sessionExpired(failure)) feedback.textContent = message(failure); }
      finally { if (token === editorToken) setBusy(false); }
    }, 'quiet'));
  }
  function showDetails() { tab = 'details'; details.setAttribute('aria-selected', 'true'); history.setAttribute('aria-selected', 'false'); body.setAttribute('aria-labelledby', 'details-tab'); body.replaceChildren(form); }
  async function showHistory() {
    if (!preset || editorBusy) return; tab = 'history'; details.setAttribute('aria-selected', 'false'); history.setAttribute('aria-selected', 'true'); body.setAttribute('aria-labelledby', 'history-tab'); body.replaceChildren(h('div', { class: 'initial-state' }, h('span', { class: 'spinner' }), h('p', {}, '正在读取版本记录…')));
    try {
      const result = await api.versions(preset.id); if (!active() || tab !== 'history') return;
      if (!Array.isArray(result.items)) throw new ApiError('版本记录格式不正确。');
      body.replaceChildren(h('p', { class: 'history-intro' }, '每次保存都会保留版本。替换文件后，仍需上架才能更新 App。'), button('替换文件', () => { showDetails(); ncp.focus(); ncp.click(); }, 'secondary'), ...result.items.map(versionCard));
      if (!result.items.length) body.append(empty('还没有版本记录', '保存预设后，版本记录会显示在这里。'));
    } catch (error) { if (active() && tab === 'history' && !sessionExpired(error)) body.replaceChildren(empty('版本记录暂不可用', message(error), showHistory)); }
  }
  function versionCard(version) { return h('article', { class: 'version' }, h('div', { class: 'version-head' }, h('h3', {}, `版本 ${version.revision}`), version.isPublished ? h('span', { class: 'status published' }, '当前上架') : null), h('dl', {}, h('dt', {}, '名称'), h('dd', {}, version.name), h('dt', {}, '分类'), h('dd', {}, categoryLabel(version)), h('dt', {}, '保存于'), h('dd', {}, date(version.createdAt)), h('dt', {}, '格式'), h('dd', {}, presetFormat(version)), h('dt', {}, '文件'), h('dd', {}, version.file?.originalName || '文件缺失'), h('dt', {}, '大小'), h('dd', {}, fileSize(version.file?.byteLength)), h('dt', {}, '校验值'), h('dd', { class: 'hash' }, version.file?.sha256 || '未提供'))); }
  details.addEventListener('click', showDetails); history.addEventListener('click', showHistory);
  tabs.addEventListener('keydown', event => { if (['ArrowLeft', 'ArrowRight'].includes(event.key)) { event.preventDefault(); const target = document.activeElement === details ? history : details; if (!target.disabled) { target.focus(); target.click(); } } });
  async function upload(kind, file) { if (!file) return null; if (uploads.has(file)) return uploads.get(file); const result = await api.upload(kind, file); if (!result.asset?.id) throw new ApiError('上传结果缺少文件记录，未保存预设。'); uploads.set(file, result.asset); return result.asset; }
  async function saveDraft() {
    if (!active() || editorBusy) return; showDetails(); validateCategoryField(); if (!form.reportValidity()) return;
    const file = ncp.files[0], picture = preview.files[0];
    if (!preset?.file && !file) { feedback.textContent = '请先选择 NCP 或 NP3 文件。'; ncp.focus(); return; }
    if (file && (!/\.(ncp|np3)$/i.test(file.name) || file.size > 1024 * 1024 || !file.size)) { feedback.textContent = '请选择有效且不超过 1 MiB 的 NCP 或 NP3 文件。'; return; }
    if (picture && (!['image/jpeg', 'image/png', 'image/webp'].includes(picture.type) || !picture.size || picture.size > 4 * 1024 * 1024)) { feedback.textContent = '请选择不超过 4 MiB 的 JPEG、PNG 或 WebP 效果图。'; return; }
    feedback.textContent = ''; setBusy(true, file || picture ? '正在上传并保存…' : '正在保存…');
    try {
      const fileAsset = await upload('ncp', file) || preset?.file; const previewAsset = await upload('preview', picture) || preset?.preview;
      const body = { name: name.value.trim(), category: normalizeCategory(category.value), description: description.value.trim(), compatibleModels: [...new Set(models.value.split(/[,，、;；\n]+/).map(model => model.trim()).filter(Boolean))], sortOrder: Number(order.value), fileAssetId: fileAsset.id, previewAssetId: previewAsset?.id || null };
      const result = preset ? await api.updatePreset(preset.id, { ...body, expectedRevision: preset.revision }) : await api.createPreset(body);
      if (!active()) return; if (!result.preset?.id) throw new ApiError('保存结果格式不正确，请刷新列表核对。');
      preset = result.preset; category.value = preset.category || ''; editorDirty = false; title.textContent = '编辑预设'; revision.textContent = `当前版本 ${preset.revision} · ${preset.status === 'published' ? '修改先存为草稿，发布更新后才在 App 生效' : '已保存草稿，尚未上架'}`;
      publish.hidden = false; publish.textContent = publishLabel(); unpublish.hidden = preset.status !== 'published'; ncp.value = ''; preview.value = ''; ncpMeta.textContent = presetFileLabel(preset);
      if (imageObjectUrl) URL.revokeObjectURL(imageObjectUrl); imageObjectUrl = ''; uploads.clear(); drawPreview(); notice('已保存，App 上架内容尚未改变'); await loadPresets();
    } catch (error) { if (active() && !sessionExpired(error)) showEditorError(error); }
    finally { if (token === editorToken) setBusy(false); }
  }
  async function changeStatus(action) {
    if (!preset || editorBusy) return;
    if (editorDirty) { notice('请先保存修改，再更新上架状态。', true); return; }
    const publishAction = action === 'publish';
    if (!(await confirmDialog(publishAction ? `上架「${preset.name}」？` : `下架「${preset.name}」？`, publishAction ? '当前保存的版本将出现在 App 在线预设目录中。' : '此预设将从 App 在线目录移除，文件与版本记录保留。', { label: publishAction ? '确认上架' : '确认下架', danger: !publishAction })) || !active()) return;
    setBusy(true, publishAction ? '正在上架…' : '正在下架…'); feedback.textContent = '';
    try {
      const result = publishAction ? await api.publish(preset.id, preset.revision) : await api.unpublish(preset.id, preset.revision); if (!active()) return;
      if (!result.preset?.id) throw new ApiError('状态结果格式不正确，请刷新列表核对。'); preset = result.preset;
      publish.textContent = publishLabel(); unpublish.hidden = preset.status !== 'published'; revision.textContent = `当前版本 ${preset.revision} · ${preset.status === 'published' ? '已上架' : '已下架，版本保留'}`; notice(publishAction ? '已上架，App 目录已更新' : '已下架，文件与版本已保留'); await loadPresets();
    } catch (error) { if (active() && !sessionExpired(error)) { showDetails(); showEditorError(error); } }
    finally { if (token === editorToken) setBusy(false); }
  }
  editor.replaceChildren(h('div', { class: 'editor-head' }, h('div', {}, title, revision), close), tabs, body, footer); showDetails(); editor.showModal(); name.focus();
}

boot();
addEventListener('beforeunload', event => { if (editorDirty || editorBusy) { event.preventDefault(); event.returnValue = ''; } });
