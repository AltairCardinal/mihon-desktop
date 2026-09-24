(() => {
  const platform = new URLSearchParams(location.search).get('platform') === 'android' ? 'android' : 'windows';
  const view = MihonSyncView;
  const spec = view.platformSpec(platform);
  const app = document.getElementById('app');
  const books = [
    { id: 'star', title: '星海手记', author: '示例作者', unread: true, initials: '星海', color: 'a' },
    { id: 'rain', title: '雨后书店', author: '示例作者', unread: false, initials: '雨后', color: 'b' },
    { id: 'night', title: '夜行电车', author: '示例作者', unread: true, initials: '夜行', color: 'c' },
  ];
  const chapters = [{ id: 'c3', label: '第 3 话 · 新的旅程' }, { id: 'c2', label: '第 2 话 · 约定' }, { id: 'c1', label: '第 1 话 · 相遇' }];
  const state = { route: 'library', bookId: null, query: null, filterOpen: false, unreadOnly: false, selected: new Set(), downloaded: new Set(books.flatMap(book => chapters.map(ch => `${book.id}:${ch.id}`))), selectionMode: false, dialog: false, sheet: null, startup: true, periodic: true, theme: 'dark', notice: '' };
  const chapterKey = id => `${state.bookId}:${id}`;
  const icon = name => view.icon(name);
  const nav = () => `<nav class="native-navigation" aria-label="${platform === 'windows' ? 'Desktop' : '手机'}主导航">${spec.nav.map(item => `<button type="button" class="native-nav-item ${item.route === 'library' ? 'is-selected' : ''}" data-testid="nav-${item.route}" ${item.route !== 'library' ? 'disabled title="未纳入本次实验"' : 'aria-current="page"'}><span class="nav-icon-anchor"><span class="nav-icon-wrap">${icon(item.icon)}</span></span><span class="nav-label">${item.label}</span></button>`).join('')}</nav>`;
  const btn = (id, label, glyph, extra = '') => `<button type="button" class="icon-button" data-testid="${id}" data-action="${id}" aria-label="${label}" title="${label}" ${extra}>${icon(glyph)}</button>`;
  const escape = text => String(text).replace(/[&<>"']/g, x => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' })[x]);
  function header(title, actions = '', leading = '') {
    return `<header class="app-bar">${leading}<h1 ${title === '书架' ? 'data-testid="library-title"' : ''}>${escape(title)}</h1><div class="bar-actions">${actions}</div></header>`;
  }
  function library() {
    const visible = books.filter(book => (!state.unreadOnly || book.unread) && (state.query === null || book.title.includes(state.query.trim())));
    const search = state.query !== null;
    const actions = `${btn('sync-open','同步','sync')}${btn('search-open','搜索书架','search')}${btn('filter-open','筛选书架','filter')}`;
    const bar = search ? `<header class="app-bar search-bar">${btn('search-close','关闭搜索','back')}<input data-testid="library-query" aria-label="搜索书架" placeholder="搜索书架" value="${escape(state.query)}" autocomplete="off">${btn('search-clear','清空搜索','close')}</header>` : header('书架', actions);
    const filter = state.filterOpen ? `<section class="filter-panel" aria-label="书架筛选"><p>筛选 · 当前书架</p><button type="button" data-action="filter-unread" data-testid="filter-unread" aria-pressed="${state.unreadOnly}">未读 ${state.unreadOnly ? '✓' : ''}</button><button type="button" data-action="filter-reset" data-testid="filter-reset">重置筛选</button></section>` : '';
    const chip = state.unreadOnly ? `<div class="active-filter" data-testid="filter-active">仅未读 <button type="button" data-action="filter-reset" aria-label="清除未读筛选">×</button></div>` : '';
    const cards = visible.length ? `<div class="book-grid">${visible.map(book => `<button type="button" class="book-card" data-testid="manga-card" data-id="${book.id}"><span class="book-cover cover-${book.color}" aria-hidden="true">${book.initials}</span><strong>${book.title}</strong><small>${book.unread ? '未读章节' : '已读'}</small></button>`).join('')}</div>` : `<div class="empty-state" data-testid="library-no-results"><strong>没有符合条件的漫画</strong><p>试试清空搜索或重置筛选。</p><button type="button" data-action="filter-reset">重置筛选</button></div>`;
    return `${bar}<main class="scroll-content"><div class="section-label">全部 · ${visible.length} 本漫画</div>${filter}${chip}${cards}</main>`;
  }
  function detail() {
    const book = books.find(item => item.id === state.bookId) || books[0];
    const selecting = state.selectionMode;
    const leading = btn('detail-back','返回书架','back');
    const actions = selecting ? `${btn('select-all','全选章节','selectAll')}${btn('select-cancel','退出多选','close')}` : btn('select-enter','选择章节','checklist');
    const bar = header(selecting ? `已选 ${state.selected.size} 项` : book.title, actions, leading).replace('class="app-bar"','class="app-bar"' + (selecting ? ' data-testid="selection-count"' : ''));
    return `${bar}<main class="scroll-content detail-content"><section class="manga-hero"><span class="hero-cover cover-${book.color}" aria-hidden="true">${book.initials}</span><div><h2>${book.title}</h2><p>${book.author} · 示例来源</p><p>本地交互样本，共 3 个章节</p></div></section><div class="section-heading"><h3>章节</h3><span>3 话</span></div><div class="chapter-list">${chapters.map(ch => `<button type="button" class="chapter-row ${state.selected.has(ch.id) ? 'is-selected' : ''}" data-testid="chapter-row" data-id="${ch.id}" aria-pressed="${state.selected.has(ch.id)}"><span>${escape(ch.label)}</span><small>${state.selected.has(ch.id) ? '已选 · ' : ''}${state.downloaded.has(chapterKey(ch.id)) ? '已下载' : '未下载'}</small></button>`).join('')}</div></main>${selecting && state.selected.size && [...state.selected].some(id => state.downloaded.has(chapterKey(id))) ? `<footer class="selection-actions"><button type="button" data-action="remove-selected" data-testid="remove-selected">移除所选章节下载</button></footer>` : ''}`;
  }
  function sheet() {
    if (!state.sheet) return '';
    const settings = state.sheet === 'settings';
    return `<div class="sheet-layer"><button type="button" class="scrim" data-action="sync-close" aria-label="关闭同步面板"></button><section class="sheet" role="dialog" aria-modal="true" aria-label="${settings ? '同步设置' : '同步'}"><header class="sheet-header">${settings ? btn('sync-back','返回同步','back') : ''}<h2 data-testid="${settings ? 'sync-settings-title' : 'sync-title'}">${settings ? '同步设置' : '同步'}</h2>${settings ? '' : btn('sync-settings','同步设置','settings')}${btn('sync-close','关闭同步','close')}</header>${settings ? `<div class="sheet-content"><p class="section-label">自动同步 · 仅此设备</p><label class="setting-row"><span><strong>启动时自动同步</strong><small>应用启动后在后台同步</small></span><input type="checkbox" data-testid="startup-toggle" data-action="startup-toggle" ${state.startup ? 'checked' : ''}></label><label class="setting-row"><span><strong>后台定期同步</strong><small>${platform === 'windows' ? '应用运行期间执行' : '系统允许时自动同步'}</small></span><input type="checkbox" data-testid="periodic-toggle" data-action="periodic-toggle" ${state.periodic ? 'checked' : ''}></label><p class="boundary">设置仅影响当前演示设备，不启动真实任务。</p></div>` : `<div class="sheet-content"><p>本地同步演示</p><p class="boundary">此实验只展示设置入口与返回层级。完整同步交互请查看旧 DEMO。</p></div>`}</section></div>`;
  }
  function confirmation() {
    if (!state.dialog) return '';
    const applicable = [...state.selected].filter(id => state.downloaded.has(chapterKey(id))).length;
    return `<div class="dialog-layer"><div class="scrim"></div><section class="confirm-dialog" role="dialog" aria-modal="true" aria-label="移除章节下载"><h2>移除章节下载</h2><p>已选 ${state.selected.size} 个章节，其中 ${applicable} 个已下载。只移除本设备上已下载的章节；漫画和阅读记录会保留。</p><div class="dialog-actions"><button type="button" data-action="remove-cancel" data-testid="remove-cancel">取消</button><button type="button" data-action="remove-confirm" data-testid="remove-confirm" ${applicable ? '' : 'disabled'}>确认移除</button></div></section></div>`;
  }
  function render(focus = null) {
    document.body.classList.toggle('theme-light', state.theme === 'light');
    document.body.classList.toggle('theme-dark', state.theme === 'dark');
    const modal = Boolean(state.sheet || state.dialog);
    app.innerHTML = `<div class="app-window ${platform}">${platform === 'android' ? '<div class="status-bar" aria-hidden="true">9:41 ▰ ▰ ▰</div>' : '<div class="window-bar">Mihon Desktop <span>— □ ×</span></div>'}<div class="app-content" ${modal ? 'inert' : ''}>${state.route === 'library' ? library() : detail()}</div><div class="nav-wrap" ${modal ? 'inert' : ''}>${nav()}</div>${state.notice ? `<div class="snackbar" role="status">${escape(state.notice)}</div>` : ''}${sheet()}${confirmation()}</div>`;
    if (focus) app.querySelector(focus)?.focus();
  }
  function back() {
    const previousDialog = state.dialog;
    const previousSheet = state.sheet;
    if (state.dialog) state.dialog = false;
    else if (state.sheet === 'settings') state.sheet = 'main';
    else if (state.sheet) state.sheet = null;
    else if (state.selectionMode) { state.selectionMode = false; state.selected.clear(); }
    else if (state.query !== null) state.query = null;
    else if (state.route === 'detail') { state.route = 'library'; state.bookId = null; }
    const focus = previousDialog ? '[data-testid="remove-selected"]' : previousSheet === 'settings' ? '[data-testid="sync-settings"]' : previousSheet ? '[data-testid="sync-open"]' : null;
    render(focus);
  }
  app.addEventListener('click', event => {
    const card = event.target.closest('[data-testid="manga-card"]');
    if (card) { state.route = 'detail'; state.bookId = card.dataset.id; state.query = null; render(); return; }
    const chapter = event.target.closest('[data-testid="chapter-row"]');
    if (chapter) { if (state.selectionMode) { state.selected.has(chapter.dataset.id) ? state.selected.delete(chapter.dataset.id) : state.selected.add(chapter.dataset.id); if (!state.selected.size) state.selectionMode = false; render(); } else { state.notice = '阅读器未纳入本次实验。'; render(); } return; }
    const action = event.target.closest('[data-action]')?.dataset.action;
    if (!action) return;
    let focus = null;
    switch (action) {
      case 'search-open': state.query = ''; focus = '[data-testid="library-query"]'; break;
      case 'search-clear': state.query = ''; focus = '[data-testid="library-query"]'; break;
      case 'search-close': state.query = null; break;
      case 'filter-open': state.filterOpen = !state.filterOpen; break;
      case 'filter-unread': state.unreadOnly = !state.unreadOnly; break;
      case 'filter-reset': state.unreadOnly = false; break;
      case 'detail-back': back(); return;
      case 'select-enter': state.selectionMode = true; state.selected.add(chapters[0].id); break;
      case 'select-cancel': state.selectionMode = false; state.selected.clear(); break;
      case 'select-all': state.selected = new Set(chapters.map(ch => ch.id)); break;
      case 'remove-selected': state.dialog = true; break;
      case 'remove-cancel': state.dialog = false; break;
      case 'remove-confirm': { const count = [...state.selected].filter(id => state.downloaded.delete(chapterKey(id))).length; state.dialog = false; state.notice = `已移除 ${count} 个章节的演示下载。`; state.selected.clear(); state.selectionMode = false; break; }
      case 'sync-open': state.sheet = 'main'; break;
      case 'sync-settings': state.sheet = 'settings'; break;
      case 'sync-back': state.sheet = 'main'; break;
      case 'sync-close': state.sheet = null; break;
      default: return;
    }
    if (action === 'sync-open') focus = '[data-testid="sync-close"]';
    if (action === 'sync-settings') focus = '[data-testid="sync-back"]';
    if (action === 'sync-back') focus = '[data-testid="sync-settings"]';
    if (action === 'sync-close') focus = '[data-testid="sync-open"]';
    if (action === 'remove-selected') focus = '[data-testid="remove-cancel"]';
    if (action === 'remove-cancel') focus = '[data-testid="remove-selected"]';
    render(focus);
  });
  app.addEventListener('input', event => { if (event.target.matches('[data-testid="library-query"]')) { const input = event.target; const cursor = input.selectionStart; state.query = input.value; render('[data-testid="library-query"]'); app.querySelector('[data-testid="library-query"]').setSelectionRange(cursor,cursor); } });
  app.addEventListener('change', event => { if (event.target.matches('[data-testid="startup-toggle"]')) state.startup = event.target.checked; if (event.target.matches('[data-testid="periodic-toggle"]')) state.periodic = event.target.checked; });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') { event.preventDefault(); back(); return; }
    if (event.key !== 'Tab') return;
    const modal = app.querySelector('[role="dialog"]');
    if (!modal) return;
    const controls = [...modal.querySelectorAll('button:not(:disabled), input:not(:disabled)')];
    if (!controls.length) return;
    const first = controls[0], last = controls[controls.length - 1];
    if (!modal.contains(document.activeElement) || (event.shiftKey && document.activeElement === first)) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  });
  window.addEventListener('message', event => { if (event.data?.type === 'trial-theme' && ['dark','light'].includes(event.data.theme)) { state.theme = event.data.theme; render(); } });
  render();
})();
