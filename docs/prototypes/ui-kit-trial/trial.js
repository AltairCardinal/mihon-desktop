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
  const state = {
    route: 'library', bookId: null, query: null, filterOpen: false, unreadFilter: 'all',
    selected: new Set(), anchorId: null,
    downloaded: new Set(books.flatMap(book => chapters.map(ch => `${book.id}:${ch.id}`))),
    read: new Set(), positions: {}, selectionMode: false, dialog: false, deleteSnapshot: null,
    sheet: null, startup: true, periodic: true, theme: 'dark', notice: '',
    scroll: { library: 0, detail: 0, sheet: 0 }, returnBookId: null, readerTrigger: null,
  };
  const chapterKey = id => `${state.bookId}:${id}`;
  const selectedKey = id => `${state.bookId}:${id}`;
  const selectedIds = () => chapters.filter(ch => state.selected.has(selectedKey(ch.id))).map(ch => ch.id);
  const selectionCount = () => selectedIds().length;
  const nextUnreadChapter = book => book.unread
    ? chapters.find(ch => !state.read.has(`${book.id}:${ch.id}`))?.id : null;
  const filterLabels = { all: '全部', include: '仅未读', exclude: '排除未读' };
  const filterAria = { all: 'false', include: 'true', exclude: 'mixed' };
  let longPressTimer = null;
  let suppressClickUntil = 0;
  const icon = name => view.icon(name);
  function nav() {
    const items = spec.nav.map(item => `<button type="button"
      class="native-nav-item ${item.route === 'library' ? 'is-selected' : ''}"
      data-testid="nav-${item.route}"
      ${item.route !== 'library' ? 'disabled title="未纳入本次实验"' : 'aria-current="page"'}>
      <span class="nav-icon-anchor"><span class="nav-icon-wrap">${icon(item.icon)}</span></span>
      <span class="nav-label">${item.label}</span>
    </button>`).join('');
    return `<nav class="native-navigation" aria-label="${platform === 'windows' ? 'Desktop' : '手机'}主导航">${items}</nav>`;
  }
  const btn = (id, label, glyph, extra = '') => `<button type="button" class="icon-button" data-testid="${id}" data-action="${id}" aria-label="${label}" title="${label}" ${extra}>${icon(glyph)}</button>`;
  const escape = text => String(text).replace(/[&<>"']/g, x => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' })[x]);
  function header(title, actions = '', leading = '') {
    return `<header class="app-bar">${leading}<h1 ${title === '书架' ? 'data-testid="library-title"' : ''}>${escape(title)}</h1><div class="bar-actions">${actions}</div></header>`;
  }
  function library() {
    const visible = books.filter(book => {
      const matchesUnread = state.unreadFilter === 'all' ||
        (state.unreadFilter === 'include' && book.unread) ||
        (state.unreadFilter === 'exclude' && !book.unread);
      return matchesUnread && (state.query === null || book.title.includes(state.query.trim()));
    });
    const search = state.query !== null;
    const actions = [
      btn('sync-open', '同步', 'sync'),
      btn('search-open', '搜索书架', 'search'),
      btn('filter-open', '筛选书架', 'filter'),
    ].join('');
    const bar = search ? `<header class="app-bar search-bar">
      ${btn('search-close', '关闭搜索', 'back')}
      <input data-testid="library-query" aria-label="搜索书架" placeholder="搜索书架"
        value="${escape(state.query)}" autocomplete="off">
      ${btn('search-clear', '清空搜索', 'close')}
    </header>` : header('书架', actions);
    const filter = state.filterOpen ? `<section class="filter-panel" aria-label="书架筛选">
      <p>筛选 · 当前书架</p>
      <button type="button" data-action="filter-unread" data-testid="filter-unread"
        aria-pressed="${filterAria[state.unreadFilter]}">未读：${filterLabels[state.unreadFilter]}</button>
      <button type="button" data-action="filter-reset" data-testid="filter-reset">重置筛选</button>
      <button type="button" data-action="filter-close" data-testid="filter-close">关闭</button>
    </section>` : '';
    const chip = state.unreadFilter !== 'all' ? `<div class="active-filter" data-testid="filter-active">
      ${filterLabels[state.unreadFilter]}
      <button type="button" data-action="filter-reset" aria-label="清除未读筛选">×</button>
    </div>` : '';
    const cards = visible.length ? `<div class="book-grid">${visible.map(book => `<button type="button"
      class="book-card" data-testid="manga-card" data-id="${book.id}">
      <span class="book-cover cover-${book.color}" aria-hidden="true">${book.initials}</span>
      <strong>${book.title}</strong><small>${book.unread ? '未读章节' : '已读'}</small>
    </button>`).join('')}</div>` : `<div class="empty-state" data-testid="library-no-results">
      <strong>没有符合条件的漫画</strong>
      <p>试试清空搜索或重置筛选。</p>
      <button type="button" data-action="filter-reset">重置筛选</button>
    </div>`;
    return `${bar}<main class="scroll-content" data-scroll="library"><div class="section-label">全部 · ${visible.length} 本漫画</div>${filter}${chip}${cards}</main>`;
  }
  function detail() {
    const book = books.find(item => item.id === state.bookId) || books[0];
    const selecting = state.selectionMode;
    const leading = btn('detail-back','返回书架','back');
    const actions = selecting
      ? `${btn('select-all','全选章节','selectAll')}${btn('select-invert','反选章节','flipToBack')}${btn('select-cancel','退出多选','close')}`
      : btn('select-enter','选择章节','checklist');
    const bar = header(selecting ? `已选 ${selectionCount()} 项` : book.title, actions, leading)
      .replace('class="app-bar"', 'class="app-bar"' + (selecting ? ' data-testid="selection-count"' : ''));
    const progress = state.positions[book.id]
      ? `<p data-testid="detail-progress">读到第 ${state.positions[book.id].page} 页</p>` : '';
    const hero = `<section class="manga-hero">
      <span class="hero-cover cover-${book.color}" aria-hidden="true">${book.initials}</span>
      <div><h2>${book.title}</h2><p>${book.author} · 示例来源</p>${progress}<p>本地交互样本，共 3 个章节</p></div>
    </section>`;
    const rows = chapters.map(ch => {
      const selected = state.selected.has(selectedKey(ch.id));
      const downloaded = state.downloaded.has(chapterKey(ch.id));
      return `<button type="button" class="chapter-row ${selected ? 'is-selected' : ''}"
        data-testid="chapter-row" data-id="${ch.id}" aria-pressed="${selected}">
        <span>${escape(ch.label)}</span><small>${selected ? '已选 · ' : ''}${downloaded ? '已下载' : '未下载'}</small>
      </button>`;
    }).join('');
    const removable = selectedIds().some(id => state.downloaded.has(chapterKey(id)));
    const footer = selecting && removable
      ? `<footer class="selection-actions"><button type="button" data-action="remove-selected" data-testid="remove-selected">移除所选章节下载</button></footer>` : '';
    const fab = !selecting && nextUnreadChapter(book)
      ? `<button type="button" class="continue-fab" data-testid="continue-read" data-action="continue-read">${icon('play')}继续阅读</button>` : '';
    return `${bar}<main class="scroll-content detail-content" data-scroll="detail">
      ${hero}<div class="section-heading"><h3>章节</h3><span>3 话</span></div>
      <div class="chapter-list">${rows}</div></main>${fab}${footer}`;
  }
  function reader() {
    const book = books.find(item => item.id === state.bookId) || books[0];
    const position = state.positions[book.id] || { chapterId: chapters[0].id, page: 1 };
    return `${header('阅读预览', '', btn('reader-back', '返回漫画详情', 'back'))}
      <main class="scroll-content reader-content" data-testid="reader-preview">
        <p>${book.title} · ${chapters.find(ch => ch.id === position.chapterId)?.label}</p>
        <div class="reader-page">本地阅读预览<br>第 ${position.page} 页<br><small>不加载真实图片</small></div>
        <button type="button" data-action="reader-next" data-testid="reader-next">下一页</button>
      </main>`;
  }
  function sheet() {
    if (!state.sheet) return '';
    const settings = state.sheet === 'settings';
    const title = settings ? '同步设置' : '同步';
    const header = `<header class="sheet-header">
      ${settings ? btn('sync-back', '返回同步', 'back') : ''}
      <h2 data-testid="${settings ? 'sync-settings-title' : 'sync-title'}">${title}</h2>
      ${settings ? '' : btn('sync-settings', '同步设置', 'settings')}
      ${btn('sync-close', '关闭同步', 'close')}
    </header>`;
    const content = settings ? `<div class="sheet-content">
      <p class="section-label">自动同步 · 仅此设备</p>
      <label class="setting-row">
        <span><strong>启动时自动同步</strong><small>应用启动后在后台同步</small></span>
        <input type="checkbox" data-testid="startup-toggle" data-action="startup-toggle" ${state.startup ? 'checked' : ''}>
      </label>
      <label class="setting-row">
        <span><strong>后台定期同步</strong><small>${platform === 'windows' ? '应用运行期间执行' : '系统允许时自动同步'}</small></span>
        <input type="checkbox" data-testid="periodic-toggle" data-action="periodic-toggle" ${state.periodic ? 'checked' : ''}>
      </label>
      <p class="boundary">设置仅影响当前演示设备，不启动真实任务。</p>
    </div>` : `<div class="sheet-content">
      <p>本地同步演示</p>
      <p class="boundary">此实验只展示设置入口与返回层级。完整同步交互请查看旧 DEMO。</p>
    </div>`;
    return `<div class="sheet-layer">
      <button type="button" class="scrim" data-action="sync-close" aria-label="关闭同步面板"></button>
      <section class="sheet" role="dialog" aria-modal="true" aria-label="${title}">${header}${content}</section>
    </div>`;
  }
  function confirmation() {
    if (!state.dialog) return '';
    const snapshot = state.deleteSnapshot;
    return `<div class="dialog-layer"><button class="scrim" data-action="remove-cancel" aria-label="取消移除"></button>
      <section class="confirm-dialog" role="dialog" aria-modal="true" aria-label="移除章节下载">
        <h2>移除章节下载</h2>
        <p>已选 ${snapshot.ids.length} 个章节，其中 ${snapshot.applicable.length} 个已下载。只移除本设备上已下载的章节；漫画和阅读记录会保留。</p>
        <div class="dialog-actions">
          <button type="button" data-action="remove-cancel" data-testid="remove-cancel">取消</button>
          <button type="button" data-action="remove-confirm" data-testid="remove-confirm" ${snapshot.applicable.length ? '' : 'disabled'}>确认移除</button>
        </div>
      </section></div>`;
  }
  function render(focus = null) {
    const oldScroll = app.querySelector('[data-scroll]');
    if (oldScroll) state.scroll[oldScroll.dataset.scroll] = oldScroll.scrollTop;
    const oldSheet = app.querySelector('.sheet-content');
    if (oldSheet) state.scroll.sheet = oldSheet.scrollTop;
    const active = app.contains(document.activeElement) ? document.activeElement : null;
    const restoreFocus = document.hasFocus() && active?.dataset.testid
      ? `[data-testid="${active.dataset.testid}"]${active.dataset.id ? `[data-id="${active.dataset.id}"]` : ''}` : null;
    document.body.classList.toggle('theme-light', state.theme === 'light');
    document.body.classList.toggle('theme-dark', state.theme === 'dark');
    const modal = Boolean(state.sheet || state.dialog);
    const content = state.route === 'library' ? library() : state.route === 'detail' ? detail() : reader();
    app.innerHTML = `<div class="app-window ${platform}">
      ${platform === 'android' ? '<div class="status-bar" aria-hidden="true">9:41 ▰ ▰ ▰</div>' : '<div class="window-bar">Mihon Desktop <span>— □ ×</span></div>'}
      <div class="app-content" ${modal ? 'inert' : ''}>${content}</div>
      <div class="nav-wrap" ${modal ? 'inert' : ''}>${nav()}</div>
      ${state.notice ? `<div class="snackbar" role="status">${escape(state.notice)}</div>` : ''}
      ${sheet()}${confirmation()}
    </div>`;
    const newScroll = app.querySelector('[data-scroll]');
    if (newScroll) newScroll.scrollTop = state.scroll[newScroll.dataset.scroll] || 0;
    const newSheet = app.querySelector('.sheet-content');
    if (newSheet) newSheet.scrollTop = state.scroll.sheet || 0;
    app.querySelector(focus || restoreFocus)?.focus({ preventScroll: true });
  }
  function back() {
    const previousDialog = state.dialog;
    const previousSheet = state.sheet;
    const previousRoute = state.route;
    if (state.dialog) { state.dialog = false; state.deleteSnapshot = null; }
    else if (state.sheet === 'settings') state.sheet = 'main';
    else if (state.sheet) state.sheet = null;
    else if (state.selectionMode) { state.selectionMode = false; state.selected.clear(); }
    else if (state.route === 'reader') state.route = 'detail';
    else if (state.route === 'library' && state.filterOpen) state.filterOpen = false;
    else if (state.route === 'library' && state.query !== null) state.query = null;
    else if (state.route === 'detail') { state.route = 'library'; state.returnBookId = state.bookId; state.bookId = null; }
    const focus = previousDialog ? '[data-testid="remove-selected"]'
      : previousSheet === 'settings' ? '[data-testid="sync-settings"]'
      : previousSheet ? '[data-testid="sync-open"]'
      : state.route === 'library' && state.returnBookId
        ? `[data-testid="manga-card"][data-id="${state.returnBookId}"]`
      : previousRoute === 'reader'
        ? state.readerTrigger === 'continue'
          ? '[data-testid="continue-read"]'
          : `[data-testid="chapter-row"][data-id="${state.readerTrigger}"]`
        : null;
    render(focus);
  }
  function selectRange(targetId) {
    const ids = chapters.map(ch => ch.id);
    let anchorIndex = ids.indexOf(state.anchorId);
    const targetIndex = ids.indexOf(targetId);
    if (targetIndex < 0) return;
    if (anchorIndex < 0) {
      state.anchorId = targetId;
      anchorIndex = targetIndex;
    }
    for (const id of ids.slice(Math.min(anchorIndex, targetIndex), Math.max(anchorIndex, targetIndex) + 1)) {
      state.selected.add(selectedKey(id));
    }
    state.selectionMode = true;
  }
  function openReader(chapterId, trigger = chapterId) {
    const prior = state.positions[state.bookId];
    state.positions[state.bookId] = prior?.chapterId === chapterId ? prior : { chapterId, page: 1 };
    state.route = 'reader';
    state.readerTrigger = trigger;
    state.notice = '';
    render('[data-testid="reader-next"]');
  }
  app.addEventListener('click', event => {
    const card = event.target.closest('[data-testid="manga-card"]');
    if (card) {
      state.route = 'detail'; state.bookId = card.dataset.id; state.returnBookId = card.dataset.id;
      state.selected.clear(); state.selectionMode = false; state.anchorId = null;
      state.scroll.detail = 0;
      render();
      return;
    }
    const chapter = event.target.closest('[data-testid="chapter-row"]');
    if (chapter) {
      if (Date.now() < suppressClickUntil) return;
      const id = chapter.dataset.id;
      if (!state.selectionMode) { openReader(id); return; }
      if (platform === 'windows' && event.shiftKey) selectRange(id);
      else {
        const key = selectedKey(id);
        if (state.selected.has(key)) state.selected.delete(key);
        else state.selected.add(key);
        state.anchorId = id;
      }
      if (!selectionCount()) { state.selectionMode = false; state.anchorId = null; }
      render();
      return;
    }
    const action = event.target.closest('[data-action]')?.dataset.action;
    if (!action) return;
    let focus = null;
    switch (action) {
      case 'search-open': state.query = ''; focus = '[data-testid="library-query"]'; break;
      case 'search-clear': state.query = ''; focus = '[data-testid="library-query"]'; break;
      case 'search-close': state.query = null; break;
      case 'filter-open': state.filterOpen = !state.filterOpen; break;
      case 'filter-close': state.filterOpen = false; focus = '[data-testid="filter-open"]'; break;
      case 'filter-unread': state.unreadFilter = { all: 'include', include: 'exclude', exclude: 'all' }[state.unreadFilter]; break;
      case 'filter-reset': state.unreadFilter = 'all'; break;
      case 'detail-back': back(); return;
      case 'select-enter':
        state.selectionMode = true;
        state.anchorId = chapters[0].id;
        state.selected.add(selectedKey(state.anchorId));
        break;
      case 'select-cancel':
        state.selectionMode = false; state.selected.clear(); state.anchorId = null;
        break;
      case 'select-all': state.selected = new Set(chapters.map(ch => selectedKey(ch.id))); break;
      case 'select-invert':
        state.selected = new Set(chapters.filter(ch => !state.selected.has(selectedKey(ch.id))).map(ch => selectedKey(ch.id)));
        if (!selectionCount()) { state.selectionMode = false; state.anchorId = null; }
        break;
      case 'remove-selected': {
        const ids = selectedIds();
        state.deleteSnapshot = { bookId: state.bookId, ids, applicable: ids.filter(id => state.downloaded.has(chapterKey(id))) };
        state.dialog = true;
        break;
      }
      case 'remove-cancel': state.dialog = false; state.deleteSnapshot = null; break;
      case 'remove-confirm': {
        const snapshot = state.deleteSnapshot;
        const removed = snapshot.applicable.filter(id => state.downloaded.delete(`${snapshot.bookId}:${id}`)).length;
        const skipped = snapshot.ids.length - removed;
        state.dialog = false; state.deleteSnapshot = null;
        state.notice = `已移除 ${removed} 个章节的演示下载，跳过 ${skipped} 个。`;
        state.selected.clear(); state.selectionMode = false; state.anchorId = null;
        break;
      }
      case 'continue-read': {
        const book = books.find(item => item.id === state.bookId);
        const target = nextUnreadChapter(book);
        if (target) openReader(target, 'continue');
        return;
      }
      case 'reader-next': {
        const position = state.positions[state.bookId];
        position.page = Math.min(position.page + 1, 3);
        if (position.page === 3) state.read.add(chapterKey(position.chapterId));
        render('[data-testid="reader-next"]');
        return;
      }
      case 'reader-back': back(); return;
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
  app.addEventListener('pointerdown', event => {
    const row = event.target.closest('[data-testid="chapter-row"]');
    if (!row || platform !== 'android') return;
    const id = row.dataset.id;
    const startX = event.clientX, startY = event.clientY;
    longPressTimer = { startX, startY, timer: setTimeout(() => {
      longPressTimer = null;
      suppressClickUntil = Date.now() + 700;
      if (state.selectionMode) selectRange(id);
      else { state.selectionMode = true; state.anchorId = id; state.selected.add(selectedKey(id)); }
      render();
    }, 450) };
  });
  app.addEventListener('pointermove', event => {
    if (!longPressTimer) return;
    if (Math.hypot(event.clientX - longPressTimer.startX, event.clientY - longPressTimer.startY) > 10) {
      clearTimeout(longPressTimer.timer);
      longPressTimer = null;
    }
  });
  for (const name of ['pointerup', 'pointercancel']) {
    app.addEventListener(name, () => {
      if (longPressTimer) clearTimeout(longPressTimer.timer);
      longPressTimer = null;
    });
  }
  app.addEventListener('input', event => {
    if (!event.target.matches('[data-testid="library-query"]')) return;
    const input = event.target;
    const cursor = input.selectionStart;
    state.query = input.value;
    render('[data-testid="library-query"]');
    app.querySelector('[data-testid="library-query"]').setSelectionRange(cursor, cursor);
  });
  app.addEventListener('change', event => {
    if (event.target.matches('[data-testid="startup-toggle"]')) state.startup = event.target.checked;
    if (event.target.matches('[data-testid="periodic-toggle"]')) state.periodic = event.target.checked;
  });
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
  window.addEventListener('message', event => {
    if (event.data?.type !== 'trial-theme' || !['dark', 'light'].includes(event.data.theme)) return;
    state.theme = event.data.theme;
    document.body.classList.toggle('theme-light', state.theme === 'light');
    document.body.classList.toggle('theme-dark', state.theme === 'dark');
  });
  render();
})();
