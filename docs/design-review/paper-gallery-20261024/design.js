/*
 * Huevia · 纸白印样 — 原型行为
 * 照片用的是生产模板原图 app/src/main/res/drawable-nodpi/preset_template.png。
 * 每格的色彩差异由 CSS filter 近似模拟，仅供排版比较，不代表 NCP/NP3 的真实渲染结果。
 */

const TEMPLATE = '../../../app/src/main/res/drawable-nodpi/preset_template.png';

const CATEGORIES = ['全部', '人像', '风景', '胶片', '黑白', '未分类'];

/* 在线与我的两套样本。edge 为齿孔印字行，order 为网格顺序。 */
const DATA = {
  online: {
    status: '<span><i class="dot"></i>在线目录 · 241 款</span><a href="#" data-act="noop">刷新 ›</a>',
    items: [
      { name: 'Portra 400',    edge: 'NCP · 胶片 · 模板模拟',   grade: 'saturate(.92) contrast(.95) sepia(.14) hue-rotate(-6deg) brightness(1.04)' },
      { name: 'Autumn Larch',  edge: 'NCP · 风景 · 模板模拟',   grade: 'saturate(1.22) contrast(1.08) hue-rotate(-8deg)' },
      { name: 'Silver Pan',    edge: 'NCP · 黑白 · 模板模拟',   grade: 'grayscale(1) contrast(1.14) brightness(1.02)' },
      { name: 'Soft Skin',     edge: 'NP3 · 人像 · 模板模拟',   grade: 'saturate(.95) contrast(.93) sepia(.08) brightness(1.06)' },
      { name: 'Teal Harbour',  edge: 'NCP · 风景 · 模板模拟',   grade: 'saturate(1.1) hue-rotate(12deg) contrast(1.05)' },
      { name: 'Cinestill 800', edge: 'NP3 · 胶片 · 模板模拟',   grade: 'saturate(1.15) contrast(1.1) sepia(.1) hue-rotate(-12deg)' },
    ],
  },
  mine: {
    status: '<span><i class="dot"></i>仅保存在本机</span><a href="#" data-act="noop">登录后同步云端 ›</a>',
    items: [
      { name: 'LIBRARY_55', edge: 'NCP · 胶片 · 模板模拟',   grade: 'saturate(.9) contrast(.97) sepia(.16) brightness(1.03)' },
      { name: 'LIBRARY_54', edge: 'NCP · 未分类 · 模板模拟', grade: 'saturate(1.05) contrast(1.02)' },
      { name: '午后窗边',    edge: 'NCP · 人像 · 模板模拟',   grade: 'saturate(.97) contrast(.94) sepia(.1) brightness(1.07)' },
      { name: 'LIBRARY_52', edge: 'NP3 · 风景 · 模板模拟',   grade: 'saturate(1.2) contrast(1.07) hue-rotate(-5deg)' },
      { name: '黑白重调',    edge: 'NCP · 黑白 · 模板模拟',   grade: 'grayscale(1) contrast(1.2)' },
      { name: 'LIBRARY_50', edge: 'NCP · 未分类 · 待校验',    grade: 'saturate(.88) contrast(1) brightness(.99)' },
    ],
  },
};

const SWATCHES = {
  light: [
    ['纸白 paper',       '#FBFBFA'], ['凹面 paper-sunk', '#F2F2F0'],
    ['墨 ink',           '#17181A'], ['次墨 ink-soft',    '#46484C'],
    ['淡墨 ink-muted',   '#6E7178'], ['发丝线 hairline',  '#E3E3E1'],
    ['蓝晒青 accent',    '#1B4D8F'], ['青底 accent-wash', '#EAF0F8'],
    ['成功 ok',          '#2F6B4F'], ['错误 error',       '#9F2F24'],
  ],
  dark: [
    ['纸白 paper',       '#141416'], ['凹面 paper-sunk', '#1D1E21'],
    ['墨 ink',           '#ECECEA'], ['次墨 ink-soft',    '#AFB2B7'],
    ['淡墨 ink-muted',   '#7E8188'], ['发丝线 hairline',  '#2A2B2E'],
    ['蓝晒青 accent',    '#7FA8E8'], ['青底 accent-wash', '#1C2A3D'],
    ['成功 ok',          '#86C3A3'], ['错误 error',       '#F0998C'],
  ],
};

const state = { tab: 'mine', category: '全部', theme: 'light', nav: 'flush', accent: 'cyan' };

const screen = document.getElementById('screen');

/* ── 渲染 ── */

function renderStatus() {
  document.getElementById('statusline').innerHTML = DATA[state.tab].status;
}

function renderFilters() {
  const all = DATA[state.tab].items;
  const shown = visible(all);
  document.getElementById('filters').innerHTML =
    CATEGORIES.map(c =>
      `<button class="filter" data-act="cat" data-val="${c}" aria-pressed="${c === state.category}">${c}</button>`
    ).join('') +
    `<span class="count">${shown.length} 份</span>`;
}

function visible(items) {
  if (state.category === '全部') return items;
  return items.filter(i => i.edge.includes(state.category));
}

function renderSheet() {
  const items = visible(DATA[state.tab].items);
  document.getElementById('sheet').innerHTML = items.map(i => `
    <button class="frame" aria-pressed="false">
      <span class="frame-img"><img src="${TEMPLATE}" alt="${i.name} 效果预览" style="filter:${i.grade}"></span>
      <span class="frame-name">${i.name}</span>
      <span class="edge">${i.edge}</span>
    </button>
  `).join('');
}

function renderSwatches() {
  for (const key of ['light', 'dark']) {
    document.getElementById('swatches-' + key).innerHTML = SWATCHES[key].map(([n, hex]) => `
      <div class="swatch">
        <div class="chip" style="background:${hex}"></div>
        <div class="meta"><b>${n}</b><span>${hex}</span></div>
      </div>
    `).join('');
  }
}

function applyShell() {
  // 外观与底栏变体都挂在 .phone 上，便于截图时整机切换
  const phone = screen.closest('.phone');
  phone.classList.toggle('theme-dark', state.theme === 'dark');
  phone.classList.toggle('nav-float', state.nav === 'float');

  // 强调色对照：暖赭变体仅替换单个 token
  const warm = state.accent === 'warm';
  phone.style.setProperty('--accent', warm
    ? (state.theme === 'dark' ? '#E8A98B' : '#8C4A2F')
    : (state.theme === 'dark' ? '#7FA8E8' : '#1B4D8F'));
  phone.style.setProperty('--accent-wash', warm
    ? (state.theme === 'dark' ? '#3A2A20' : '#F7EDE6')
    : (state.theme === 'dark' ? '#1C2A3D' : '#EAF0F8'));
}

function renderAll() {
  renderStatus();
  renderFilters();
  renderSheet();
  applyShell();
}

/* ── 交互 ── */

document.addEventListener('click', e => {
  const el = e.target.closest('[data-act]');
  if (!el) {
    const frame = e.target.closest('.frame');
    if (frame) frame.setAttribute('aria-pressed', frame.getAttribute('aria-pressed') === 'true' ? 'false' : 'true');
    return;
  }
  const act = el.dataset.act;
  const val = el.dataset.val;

  if (act === 'noop') { e.preventDefault(); return; }

  if (act === 'tab') {
    state.tab = val; state.category = '全部';
    document.querySelectorAll('[data-act="tab"]').forEach(b =>
      b.setAttribute('aria-selected', String(b.dataset.val === val)));
    renderAll();
  }

  if (act === 'cat') { state.category = val; renderFilters(); renderSheet(); }

  if (act === 'theme' || act === 'nav' || act === 'accent') {
    state[act] = val;
    document.querySelectorAll(`[data-act="${act}"]`).forEach(b =>
      b.setAttribute('aria-pressed', String(b.dataset.val === val)));
    applyShell();
  }

  if (act === 'refresh') {
    el.animate([{ transform: 'rotate(0)' }, { transform: 'rotate(360deg)' }],
      { duration: 520, easing: 'cubic-bezier(.2,.7,.3,1)' });
  }
});

renderSwatches();
renderAll();
