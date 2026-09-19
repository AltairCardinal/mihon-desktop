(() => {
  const M = window.AuthorIdentity, V = window.MihonSyncView;
  const platform = new URLSearchParams(location.search).get('platform') === 'android' ? 'android' : 'windows';
  const spec = V.platformSpec(platform), app = document.getElementById('app'), overlay = document.getElementById('overlay');
  let state = M.create(), page = 'manga', manga = 'v0', author = 'a', filter = '全部来源', query = '', notice = '', scenario = 'history';
  let authorScroll = { following: 0, all: 0 };
  const rememberAuthorScroll = () => { if (page === 'authors') authorScroll[authorTab] = app.querySelector('main')?.scrollTop || 0; };
  let displayMode = 'list', selectedWork = '', deferredDiscovery = false, splitTarget = '', undoSplit = null;
  let authorTab = 'following', detailReturn = 'manga', mangaReturn = 'authors';
  let modal = null, opener = null, selected = [], target = 'a', settingsFrequency = 'daily', error = '', candidateQuery = '', renameDraft = '', sessionRevision = 0, modalFocus = null;
  const esc = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const button = (action, text, cls = '', extra = '') => `<button data-action="${action}" class="${cls}" ${extra}>${text}</button>`;
  const creatorRow = content => '<div class="creator-row"><svg class="creator-icon" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M12 6a2 2 0 1 1 0 4 2 2 0 0 1 0-4m0-2a4 4 0 1 0 0 8 4 4 0 0 0 0-8m0 10c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4m0 2c2.69 0 5.77 1.28 6 2H6c.23-.72 3.31-2 6-2"/></svg>' + content + '</div>';
  const current = () => state.authors.find(a => a.id === M.resolve(state, author)) || state.authors[0];
  const versions = id => state.versions.filter(v => v.author === M.resolve(state, id));
  const workNames = id => versions(id).map(v => v.title).join('、');
  const collectedGroup = group => group.versions.some(v => v.favorite);
  const badge = favorite => favorite ? '<span class="collected-badge" role="img" aria-label="已收藏" title="已收藏"><svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-6 2h2v5l-1-.75L14 9V4zm6 12H8V4h4v9l3-2.25L18 13V4h2v12z"/></svg></span>' : '';
  const unseen = id => M.unseenWorks(state, id);
  const pendingAuthors = () => state.authors.filter(a => unseen(a.id).length);
  const discoveryBanner = () => { const pending = pendingAuthors(); return pending.length ? `<aside class="discovery-banner" role="status"><span>发现 ${new Set(pending.flatMap(a => unseen(a.id))).size} 部新作<span class="discovery-names">${pending.map(a => esc(a.name)).join('、')}</span></span>${button('view-new', '查看新作')}</aside>` : ''; };
  const groupDate = group => {
    const dates = group.versions.map(v => M.workDate(state, v.work)).sort((a, b) => a.date.localeCompare(b.date));
    const d = dates[0] || { date: state.today, label: '首次发现', source: null };
    return `<span class="work-date"${d.source ? ` title="日期来源：${esc(d.source)}"` : ''}>${esc(d.label)} ${esc(d.date)}</span>`;
  };
  const groupUnseen = group => group.versions.some(v => unseen(current().id).includes(v.work));
  const workItem = group => {
    return `<button class="row work-row work-item" data-action="work" data-work="${esc(group.id)}"><span class="work-cover-wrap"><span class="cover small">${esc(group.title)}</span>${badge(collectedGroup(group))}${displayMode === 'compact' ? `<strong class="cover-title">${esc(group.title)}</strong>` : ''}</span><span class="grow">${displayMode !== 'compact' ? `<strong>${esc(group.title)}</strong>` : ''}${groupUnseen(group) ? '<span class="new-work-badge">新作 · 未查看</span>' : ''}${groupDate(group)}<span class="source-summary">${group.versions.length} 个来源版本</span></span></button>`;
  };
  const authorRow = a => {
    // Local design fixtures have no reading/popularity signals: retain first-seen work order.
    const works = M.presentationGroups(state, a.id);
    return `<button class="row row-button author-card" data-author="${a.id}" aria-label="${esc(a.name)}，${works.length}部作品，${a.follow ? '已关注' : '未关注'}"><span class="author-card-heading"><span class="grow"><strong>${esc(a.name)}</strong><span class="muted">${works.length} 部作品 · ${a.follow ? '已关注' : '未关注'}</span></span>${unseen(a.id).length ? `<span class="new-count">${unseen(a.id).length} 部新作</span>` : ''}${V.icon('chevron')}</span>${works.length ? `<span class="representative-shelf">${works.slice(0, 3).map(group => `<span class="representative-work" data-work="${esc(group.id)}"><span class="representative-cover" aria-label="${esc(group.title)}示意封面"><span>${esc(group.title)}</span>${badge(collectedGroup(group))}</span><span class="representative-title">${esc(group.title)}</span></span>`).join('')}</span>` : '<span class="muted">暂无作品</span>'}</button>`;
  };
  function authorsView() {
    const shown = state.authors.filter(a => authorTab === 'all' || a.follow);
    return `<div class="tabs author-tabs" role="tablist" aria-label="作者列表">${button('following-authors', '关注', authorTab === 'following' ? 'selected' : '', `role="tab" tabindex="${authorTab === 'following' ? 0 : -1}" aria-selected="${authorTab === 'following'}" aria-controls="author-list"`)}${button('all-authors', '全部', authorTab === 'all' ? 'selected' : '', `role="tab" tabindex="${authorTab === 'all' ? 0 : -1}" aria-selected="${authorTab === 'all'}" aria-controls="author-list"`)}</div><section id="author-list" role="tabpanel" aria-label="${authorTab === 'following' ? '关注' : '全部'}">${shown.length ? shown.map(authorRow).join('') : `<div class="empty"><p>${authorTab === 'following' ? '关注作者以获取他们的新作提示' : '暂无作者'}</p>${authorTab === 'following' ? button('all-authors', '查看全部作者', 'primary') : ''}</div>`}</section>`;
  }
  function mangaView() {
    const v = state.versions.find(v => v.id === manga) || state.versions[0];
    return `<div class="hero"><div class="cover" aria-label="示意封面">${esc(v.title)}</div><div><span class="pill">${esc(v.source)}</span><h2 class="manga-title">${esc(v.title)}</h2>${creatorRow(button('signature', esc(v.signature), 'creator-name', 'data-testid="signature"'))}<p class="muted">连载中 · 漫画</p></div></div><p class="note">相同名字自动使用同一个作者页，不区分插件或来源。点击署名查看作者。</p><h3 class="section-title">作品简介</h3><p>这是用于作者名字交互审阅的本地作品样本。封面为示意，不加载漫画图片或章节。</p>${state.unavailable ? '<p class="note warning">漫画柜来源不可用。作者、已有作品和关注仍保留。</p>' : ''}<h3 class="section-title">章节</h3><p class="muted">本原型仅演示漫画与作者之间的导航，不提供阅读或下载。</p>`;
  }
  function detailView() {
    const a = current(), all = versions(a.id), allGroups = M.presentationGroups(state, a.id), groups = M.presentationGroups(state, a.id, { source: filter, query });
    const sources = ['全部来源', ...new Set(all.map(v => v.source))];
    return `<div class="hero author-hero"><div><h2 class="manga-title" data-testid="author-name" tabindex="-1">${esc(a.name)}</h2>${a.aliases.length ? `<div class="alias-links" data-testid="author-aliases">${a.aliases.map(name => creatorRow(button('alias-name', esc(name), 'creator-name', `data-alias="${esc(name)}"`))).join('')}</div>` : ''}<p class="muted">${allGroups.length} 部作品 · ${all.length} 个来源版本</p></div></div><div class="actions">${button('follow', a.follow ? '已关注' : '关注作者', a.follow ? '' : 'primary', 'data-testid="follow"')}${button('merge-select', '添加别名')}</div>${state.unavailable ? '<p class="note warning">漫画柜插件不可用，已有作者、作品和关注不受影响。</p>' : ''}<div class="works-toolbar"><h3 class="section-title">作品 <span class="text-count">${groups.length}</span></h3>${button('display', '显示方式', 'display-button', 'aria-label="显示方式"')}</div><div class="source-filters" role="group" aria-label="来源筛选">${sources.map(s => button('filter-source', esc(s), 'filter-chip', `data-source="${esc(s)}" aria-label="${esc(s)}" aria-pressed="${s === filter}"`)).join('')}</div><div class="filters"><label>查找作品 <input id="work-search" value="${esc(query)}" placeholder="作品名" size="12"></label></div>${groups.length ? `<div class="work-collection" data-display="${displayMode}">${[...groups].sort((a, b) => Number(groupUnseen(b)) - Number(groupUnseen(a))).map(workItem).join('')}</div>` : '<p class="empty muted">没有符合条件的作品。可切换来源或清空搜索。</p>'}`;
  }
  function render() {
    const nav = page === 'authors' || page === 'author' ? (platform === 'android' ? 'browse' : 'authors') : page === 'manga' ? 'library' : page;
    const title = page === 'manga' ? '漫画详情' : page === 'author' ? '作者详情' : page === 'authors' ? '作者' : page === 'browse' ? '浏览' : spec.nav.find(n => n.route === page)?.label || 'Mihon';
    const inner = page === 'manga' ? mangaView() : page === 'author' ? detailView() : page === 'authors' ? authorsView() : page === 'browse' ? '<p class="note">请选择作者页签继续本次交互审阅。</p>' : `<div class="empty"><p>本页不在作者名字原型范围内。</p>${button('home', '返回《平行天堂》')}</div>`;
    const feedback = notice ? `<p class="feedback" role="status">${esc(notice)}${undoSplit ? button('undo-split', '撤销', 'text-button', 'data-testid="undo-split"') : ''}</p>` : '';
    app.innerHTML = `${platform === 'windows' ? '<div class="desktop-windowbar"><span>Mihon Desktop</span><span>—　□　×</span></div>' : '<div class="android-statusbar"><span>9:41</span><span>● ▰</span></div>'}<div class="bar">${button('back', V.icon('back'), 'icon-button', 'aria-label="返回"')}<h1>${title}</h1>${page === 'authors' ? button('settings', V.icon('settings'), 'icon-button author-settings', 'aria-label="作者设置"') : ''}</div>${platform === 'android' && ['browse', 'authors'].includes(page) ? `<div class="tabs">${spec.browseTabs.map(t => button(t === '作者' ? 'authors' : 'browse-boundary', t, t === '作者' && page === 'authors' ? 'selected' : '')).join('')}</div>` : ''}<main class="content">${discoveryBanner()}${feedback}${inner}</main>${V.renderNav(spec, nav)}`;
    if (page === 'authors') app.querySelector('main').scrollTop = authorScroll[authorTab];
  }
  function open(kind, preserve = false) { if (!preserve) opener = document.activeElement; modal = kind; error = ''; app.inert = true; drawModal(); }
  function close() { const work = opener?.dataset.work, action = opener?.dataset.action; modal = null; overlay.innerHTML = ''; app.inert = false; error = ''; if (opener?.isConnected) opener.focus(); else app.querySelector('button')?.focus(); if (deferredDiscovery) { deferredDiscovery = false; render(); const restored = [...app.querySelectorAll('button')].find(b => b.dataset.action === action && (!work || b.dataset.work === work)); restored?.focus(); } }
  function drawModal() {
    let title = '', body = '', actions = button('cancel', '取消');
    if (modal === 'sources') {
      title = '选择漫画源';
      const group = M.presentationGroup(state, current().id, selectedWork);
      const items = group?.versions || [];
      body = `<p class="muted">此作品的全部来源版本</p><div class="source-versions">${items.map(v => {
        const latest = M.latestDate(state, v);
        return button('manga', `<span class="work-cover-wrap"><span class="cover small">${esc(v.title)}</span>${badge(v.favorite)}</span><span class="grow"><strong>${esc(v.source)}</strong><span class="version-title">${esc(v.title)}</span><span class="version-meta">${v.chapterCount == null ? '章节数未知' : esc(v.chapterCount) + ' 章'} · ${v.favorite ? '已收藏' : '未收藏'}</span><span class="version-meta">${esc(latest.label)}${latest.date ? ' ' + esc(latest.date) : ''}</span>${state.unavailable && v.source === '漫画柜' ? '<span class="version-meta">来源不可用 · 查看本地详情</span>' : ''}</span>`, 'version-choice', `data-version="${v.id}"`);
      }).join('')}</div>${group?.autoMerged ? `<div class="modal-secondary-actions">${button('split-menu', '分开显示', 'text-button')}</div>` : ''}`;
    } else if (modal === 'display') {
      title = '显示方式';
      body = `<div class="display-choices">${[['list', '列表'], ['comfortable', '舒适网格'], ['compact', '紧凑网格']].map(([value, name]) => button('display-select', `${displayMode === value ? '✓ ' : ''}${name}`, '', `data-display="${value}" aria-pressed="${displayMode === value}"`)).join('')}</div>`;
    } else if (modal === 'split') {
      title = '分开显示';
      const group = M.presentationGroup(state, current().id, selectedWork);
      const items = group?.versions || [];
      body = `<p>选择要单独显示的来源版本。原始标题和来源信息会保留。</p><div class="split-versions">${items.map(v => `<label class="note inline-label"><input type="radio" name="split-version" data-split="${esc(v.work)}" ${splitTarget === v.work ? 'checked' : ''}><span><strong>${esc(v.title)}</strong><br>${esc(v.source)}</span></label>`).join('')}</div>`;
      actions += button('split-confirm', '确认分开', 'primary', splitTarget ? '' : 'disabled');
    } else if (modal === 'merge-select') {
      title = '添加别名';
      const allCandidates = state.authors.filter(a => a.id !== current().id);
      const candidates = allCandidates.filter(a => [a.name, ...a.aliases].some(n => n.toLowerCase().includes(candidateQuery.toLowerCase())));
      body = `<p>当前：${esc(current().name)}。选择要并入同一作者的其他名字。</p><p class="muted">仅文本完全相同才自动复用；繁简、大小写和音译不会自动转换。</p><label>搜索名字或别名<input id="candidate-search" value="${esc(candidateQuery)}" placeholder="输入名字"></label><p class="muted">已选择 ${selected.length - 1} 个其他作者。${selected.length < 2 ? '至少勾选一位其他作者才能继续。' : '搜索不会取消已选项。'}</p>${candidates.map(a => `<label class="note inline-label"><input type="checkbox" data-select="${a.id}" ${selected.includes(a.id) ? 'checked' : ''}><span><strong>${esc(a.name)}</strong>${a.aliases.length ? '<br>别名：' + a.aliases.map(esc).join('、') : ''}<br>${esc(workNames(a.id))}<br>${a.follow ? '已关注' : '未关注'}</span></label>`).join('') || `<p class="empty">${allCandidates.length ? '没有匹配的名字，清空搜索可查看全部。' : '没有可合并的其他名字。'}</p>`}`;
      actions += button('add', '添加', 'primary', selected.length < 2 ? 'disabled' : '');
    } else if (modal === 'rename') {
      title = '设置显示名称';
      body = `<p>要把${esc(renameDraft)}设为该作者的显示名称吗</p>`;
      actions += button('rename-save', '确定', 'primary');
    } else if (modal === 'settings') {
      title = '作者设置';
      body = `<p class="muted">应用于全部作者</p><label>检查频率<select id="frequency"><option value="daily" ${settingsFrequency === 'daily' ? 'selected' : ''}>每天</option><option value="weekly" ${settingsFrequency === 'weekly' ? 'selected' : ''}>每周</option><option value="monthly" ${settingsFrequency === 'monthly' ? 'selected' : ''}>每月</option></select></label>`;
      actions += button('settings-save', '保存', 'primary');
    } else {
      title = '取消关注作者？'; body = `<p>将停止关注「${esc(current().name)}」及其所有别名的全部来源作品，作者关系和作品仍保留。</p>`; actions += button('unfollow-confirm', '取消关注', 'primary');
    }
    overlay.innerHTML = `<div class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="dialog-title"><header><h2 id="dialog-title">${title}</h2>${button('cancel', V.icon('close'), 'icon-button', 'aria-label="关闭"')}</header><div class="modal-body">${body}${error ? `<p class="error" role="alert">${esc(error)}</p>${modal === 'merge-select' ? button('refresh-candidates', '刷新候选') : ''}` : ''}</div><footer>${actions}</footer></section></div>`;
    overlay.querySelector('button')?.focus();
  }
  document.addEventListener('click', event => {
    if (event.target.classList.contains('modal-backdrop')) { close(); return; }
    const b = event.target.closest('button'); if (!b) return;
    if (b.dataset.author) { rememberAuthorScroll(); detailReturn = 'authors'; filter = '全部来源'; query = ''; author = M.resolve(state, b.dataset.author); page = 'author'; notice = ''; render(); return; }
    if (b.dataset.route) { rememberAuthorScroll(); if (b.dataset.route === 'authors') authorTab = 'following'; page = b.dataset.route; notice = ''; render(); return; }
    const a = b.dataset.action;
    if (a === 'cancel') close();
    else if (a === 'view-new') { const next = pendingAuthors()[0]; if (next) { rememberAuthorScroll(); author = next.id; detailReturn = 'authors'; page = 'author'; filter = '全部来源'; query = ''; notice = ''; render(); app.querySelector('.new-work-badge')?.closest('button')?.focus(); } }
    else if (a === 'work') { selectedWork = b.dataset.work; open('sources'); }
    else if (a === 'split-menu') { splitTarget = ''; open('split', true); }
    else if (a === 'split-confirm') {
      if (!splitTarget) return;
      state = M.splitPresentation(state, current().id, splitTarget);
      undoSplit = { author: current().id, work: splitTarget };
      notice = '已分开显示'; splitTarget = ''; close(); render();
    }
    else if (a === 'undo-split') {
      if (!undoSplit) return;
      state = M.undoPresentationSplit(state, undoSplit.author, undoSplit.work);
      undoSplit = null; notice = '已撤销分开显示'; render();
    }
    else if (a === 'display') open('display');
    else if (a === 'display-select') { displayMode = b.dataset.display; close(); render(); app.querySelector('[data-action="display"]').focus(); }
    else if (a === 'settings') { settingsFrequency = state.frequency; open('settings'); }
    else if (a === 'settings-save') {
      try { state = M.setFrequency(state, settingsFrequency); notice = '检查频率已保存'; close(); render(); app.querySelector('[data-action="settings"]').focus(); }
      catch (e) { error = e.message; drawModal(); }
    }
    else if (a === 'signature') { detailReturn = 'manga'; filter = '全部来源'; query = ''; author = state.versions.find(v => v.id === manga).author; page = 'author'; render(); }
    else if (a === 'merge-select') {
      selected = [current().id]; target = current().id; candidateQuery = ''; sessionRevision = state.revision; open('merge-select');
      if (scenario === 'stale') { state.authors.find(v => v.id === current().id).follow = false; state.revision++; }
    }
    else if (a === 'refresh-candidates') { state.fail = false; scenario = 'history'; selected = selected.filter(id => state.authors.some(v => v.id === id)); sessionRevision = state.revision; error = ''; drawModal(); }
    else if (a === 'add') {
      try { state = M.apply(state, { ...M.preview(state, { selected, target }), revision: sessionRevision }); author = M.resolve(state, target); notice = '别名已添加'; close(); page = 'author'; render(); app.querySelector('[data-action="merge-select"]').focus(); }
      catch (e) { error = e.message; drawModal(); }
    } else if (a === 'alias-name') { renameDraft = b.dataset.alias; if (current().aliases.includes(renameDraft)) open('rename'); }
    else if (a === 'rename-save') {
      try { state = M.rename(state, current().id, renameDraft); notice = '显示名称已更新'; close(); render(); app.querySelector('[data-testid="author-name"]').focus(); }
      catch (e) { error = e.message; drawModal(); }
    } else if (a === 'follow' || a === 'unfollow-confirm') {
      if (a === 'follow' && current().follow) { open('unfollow'); return; }
      try { state = M.follow(state, current().id, a === 'follow'); if (modal) close(); notice = ''; render(); app.querySelector('[data-testid="follow"]').focus(); }
      catch (e) { if (modal) { error = e.message; drawModal(); } else { notice = e.message; render(); } }
    } else if (a === 'manga') { if (modal) close(); mangaReturn = 'author'; manga = b.dataset.version; state = M.markSeen(state, state.versions.find(v => v.id === manga).work); page = 'manga'; notice = ''; render(); }
    else if (a === 'following-authors' || a === 'all-authors') { rememberAuthorScroll(); authorTab = a === 'following-authors' ? 'following' : 'all'; notice = ''; render(); app.querySelector(`[data-action="${a}"]`).focus(); }
    else if (a === 'filter-source') { const offset = app.querySelector('.source-filters').scrollLeft; filter = b.dataset.source; render(); const chips = app.querySelector('.source-filters'); chips.scrollLeft = offset; [...chips.querySelectorAll('button')].find(item => item.dataset.source === filter)?.focus({ preventScroll: true }); }
    else if (a === 'authors') { authorTab = 'following'; page = 'authors'; notice = ''; render(); }
    else if (a === 'home') { manga = 'v0'; page = 'manga'; notice = ''; render(); }
    else if (a === 'back') { page = page === 'author' ? detailReturn : page === 'manga' ? mangaReturn : 'manga'; notice = ''; render(); }
    else if (a === 'browse-boundary') { notice = '本页签不在作者交互原型范围内，请进入作者页签。'; render(); }
  });
  document.addEventListener('focusin', event => { if (overlay.contains(event.target)) modalFocus = event.target; });
  document.addEventListener('input', event => {
    if (event.target.id !== 'candidate-search') return;
    const caret = event.target.selectionStart; candidateQuery = event.target.value; drawModal();
    const input = overlay.querySelector('#candidate-search'); input.focus(); input.setSelectionRange(caret, caret);
  });
  document.addEventListener('change', event => {
    const e = event.target;
    if (e.dataset.select) { selected = e.checked ? [...selected, e.dataset.select] : selected.filter(s => s !== e.dataset.select); drawModal(); overlay.querySelector(`[data-select="${e.dataset.select}"]`).focus(); }
    else if (e.dataset.split) { splitTarget = e.dataset.split; drawModal(); overlay.querySelector(`[data-split="${e.dataset.split}"]`).focus(); }
    else if (e.id === 'frequency') settingsFrequency = e.value;
    else if (e.id === 'work-search') { query = e.value; render(); document.getElementById('work-search').focus(); }
  });
  document.addEventListener('keydown', event => {
    if (!modal && event.target.matches('.author-tabs [role=tab]') && ['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
      event.preventDefault(); rememberAuthorScroll(); authorTab = event.key === 'Home' ? 'following' : event.key === 'End' ? 'all' : authorTab === 'following' ? 'all' : 'following'; render(); app.querySelector('.author-tabs [aria-selected="true"]').focus({ preventScroll: true }); return;
    }
    if (!modal) return;
    if (event.key === 'Escape') { event.preventDefault(); close(); }
    if (event.key === 'Tab') {
      const items = [...overlay.querySelectorAll('button:not(:disabled),input,select')], first = items[0], last = items.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
  });
  window.addEventListener('message', event => {
    if (event.source !== parent || event.data?.channel !== 'author-demo') return;
    if (event.data.theme) { document.body.className = 'device theme-' + event.data.theme; return; }
    if (event.data.discoverNew) {
      const existed = state.versions.some(v => v.work === 'discovered-work');
      state = M.observe(state, { id: 'discovered-main', work: 'discovered-work', title: '雨后的新世界（虚构新作）', source: '漫画柜', sourceKey: 'cabinet', name: '冈本伦', chapterCount: 3, listedAt: state.today, latestChapterAt: state.today });
      state = M.observe(state, { id: 'discovered-other', work: 'discovered-work', title: '雨后的新世界（虚构新作）', source: '备用图源（演示）', sourceKey: 'text', name: '冈本伦', chapterCount: 2, latestChapterAt: null });
      notice = existed ? '检查完成，没有发现其他新作' : '检查完成，作品列表已更新';
      if (!modal) render(); else deferredDiscovery = true;
      return;
    }
    if (event.data.refreshDates) { state = M.refreshDates(state); notice = '已模拟次日刷新，首次发现日期保持不变'; if (!modal) render(); else drawModal(); return; }
    if (event.data.add) {
      const alias = event.data.add === 'alias';
      const script = event.data.add === 'script';
      if (alias && !state.authors.some(a => a.aliases.includes('Okamoto Lynn') || a.name === 'Okamoto Lynn' && a.aliases.length)) { if (!modal) { notice = '请先合并 Okamoto Lynn，再演示已合并别名的新作品。'; render(); } return; }
      const id = alias ? 'future-alias' : script ? 'future-script' : 'future-same';
      const work = alias ? 'future-alias' : script ? 'script-hans' : 'future-same';
      const title = alias ? '别名新作（虚构样本）' : script ? '诡谲屋' : '同名新作（虚构样本）';
      state = M.observe(state, { id, work, title, source: script ? '新增图源（演示）' : '新插件（演示）', name: alias ? 'Okamoto Lynn' : '冈本伦' });
      if (modal) { modalFocus?.focus(); return; } // Keep the open session; its snapshot is now stale.
      author = state.versions.find(v => v.id === id).author; page = 'author'; notice = script ? '' : alias ? '已加入别名作品；重复加入不增加条目。' : '已加入同名作品；未提供来源作者编号仍自动复用。'; render(); return;
    }
    if (!event.data.scenario) return;
    close(); authorScroll = { following: 0, all: 0 }; authorTab = 'following'; detailReturn = 'manga'; mangaReturn = 'authors'; state = M.create(); scenario = event.data.scenario; author = 'a'; manga = 'v0'; page = 'manga'; filter = '全部来源'; query = ''; notice = ''; selected = []; splitTarget = ''; undoSplit = null;
    if (scenario === 'empty') state = M.apply(state, M.preview(state, { selected: state.authors.map(a => a.id), target: 'a' }));
    if (scenario === 'script-equivalent') {
      state.versions.push(
        { id: 'script-hans', work: 'script-hans', title: '诡谲屋', source: '拷贝漫画', author: 'a', sourceKey: 'text', favorite: false, chapterCount: 10, listedAt: null, latestChapterAt: null, firstSeenAt: state.today },
        { id: 'script-hant', work: 'script-hant', title: '詭譎屋', source: '漫画柜', author: 'a', sourceKey: 'text', favorite: false, chapterCount: 11, listedAt: null, latestChapterAt: null, firstSeenAt: state.today },
        { id: 'script-punctuation-a', work: 'script-punctuation-a', title: '诡谲屋 外传', source: '拷贝漫画', author: 'a', sourceKey: 'text', favorite: false, chapterCount: null, listedAt: null, latestChapterAt: null, firstSeenAt: state.today },
        { id: 'script-punctuation-b', work: 'script-punctuation-b', title: '《詭譎屋：外傳》', source: '漫画柜', author: 'a', sourceKey: 'text', favorite: false, chapterCount: null, listedAt: null, latestChapterAt: null, firstSeenAt: state.today },
      );
    }
    state.fail = scenario === 'submit-error'; state.unavailable = scenario === 'unavailable'; render();
  });
  render();
})();
