(() => {
  const M = window.AuthorIdentity, V = window.MihonSyncView;
  const platform = new URLSearchParams(location.search).get('platform') === 'android' ? 'android' : 'windows';
  const spec = V.platformSpec(platform), app = document.getElementById('app'), overlay = document.getElementById('overlay');
  let state = M.create(), page = 'manga', manga = 'v0', author = 'a', filter = '全部来源', query = '', notice = '', scenario = 'history';
  let authorScroll = { following: 0, all: 0 };
  const rememberAuthorScroll = () => { if (page === 'authors') authorScroll[authorTab] = app.querySelector('main')?.scrollTop || 0; };
  let authorTab = 'following', detailReturn = 'manga', mangaReturn = 'authors';
  let modal = null, opener = null, selected = [], target = 'a', settingsFrequency = 'daily', error = '', candidateQuery = '', renameDraft = '', sessionRevision = 0, modalFocus = null;
  const esc = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const button = (action, text, cls = '', extra = '') => `<button data-action="${action}" class="${cls}" ${extra}>${text}</button>`;
  const creatorRow = content => '<div class="creator-row"><svg class="creator-icon" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M12 6a2 2 0 1 1 0 4 2 2 0 0 1 0-4m0-2a4 4 0 1 0 0 8 4 4 0 0 0 0-8m0 10c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4m0 2c2.69 0 5.77 1.28 6 2H6c.23-.72 3.31-2 6-2"/></svg>' + content + '</div>';
  const current = () => state.authors.find(a => a.id === M.resolve(state, author)) || state.authors[0];
  const versions = id => state.versions.filter(v => v.author === M.resolve(state, id));
  const workNames = id => versions(id).map(v => v.title).join('、');
  const authorRow = a => {
    // Local design fixtures have no reading/popularity signals: retain first-seen work order.
    const works = [...new Map(versions(a.id).map(v => [v.work, null])).keys()]
      .map(id => versions(a.id).find(v => v.work === id));
    return `<button class="row row-button author-card" data-author="${a.id}" aria-label="${esc(a.name)}，${works.length}部作品，${a.follow ? '已关注' : '未关注'}"><span class="author-card-heading"><span class="grow"><strong>${esc(a.name)}</strong><span class="muted">${works.length} 部作品 · ${a.follow ? '已关注' : '未关注'}</span></span>${V.icon('chevron')}</span>${works.length ? `<span class="representative-shelf">${works.slice(0, 3).map(v => `<span class="representative-work" data-work="${esc(v.work)}"><span class="representative-cover" aria-label="${esc(v.title)}示意封面"><span>${esc(v.title)}</span></span><span class="representative-title">${esc(v.title)}</span></span>`).join('')}</span>` : '<span class="muted">暂无作品</span>'}</button>`;
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
    const a = current(), all = versions(a.id), shown = all.filter(v => (filter === '全部来源' || v.source === filter) && v.title.toLowerCase().includes(query.toLowerCase()));
    const groups = new Map(); shown.forEach(v => { if (!groups.has(v.work)) groups.set(v.work, []); groups.get(v.work).push(v); });
    const sources = ['全部来源', ...new Set(all.map(v => v.source))];
    return `<div class="hero author-hero"><div><h2 class="manga-title" data-testid="author-name" tabindex="-1">${esc(a.name)}</h2>${a.aliases.length ? `<div class="alias-links" data-testid="author-aliases">${a.aliases.map(name => creatorRow(button('alias-name', esc(name), 'creator-name', `data-alias="${esc(name)}"`))).join('')}</div>` : ''}<p class="muted">${new Set(all.map(v => v.work)).size} 部作品 · ${all.length} 个来源版本</p></div></div><div class="actions">${button('follow', a.follow ? '已关注' : '关注作者', a.follow ? '' : 'primary', 'data-testid="follow"')}${button('merge-select', '添加别名')}</div>${state.unavailable ? '<p class="note warning">漫画柜插件不可用，已有作者、作品和关注不受影响。</p>' : ''}<h3 class="section-title">作品 <span class="text-count">${groups.size}</span></h3><div class="source-filters" role="group" aria-label="来源筛选">${sources.map(s => button('filter-source', esc(s), 'filter-chip', `data-source="${esc(s)}" aria-label="${esc(s)}" aria-pressed="${s === filter}"`)).join('')}</div><div class="filters"><label>查找作品 <input id="work-search" value="${esc(query)}" placeholder="作品名" size="12"></label></div>${groups.size ? [...groups.values()].map(group => `<div class="row work-row"><div class="cover small">${esc(group[0].title)}</div><div class="grow"><strong>${esc(group[0].title)}</strong><div class="work-sources">${group.map(v => button('manga', esc(v.source) + (state.unavailable && v.source === '漫画柜' ? ' · 不可用' : ''), 'source-button', `data-version="${v.id}"`)).join('')}</div></div></div>`).join('') : '<p class="empty muted">没有符合条件的作品。可切换来源或清空搜索。</p>'}`;
  }
  function render() {
    const nav = page === 'authors' || page === 'author' ? (platform === 'android' ? 'browse' : 'authors') : page === 'manga' ? 'library' : page;
    const title = page === 'manga' ? '漫画详情' : page === 'author' ? '作者详情' : page === 'authors' ? '作者' : page === 'browse' ? '浏览' : spec.nav.find(n => n.route === page)?.label || 'Mihon';
    const inner = page === 'manga' ? mangaView() : page === 'author' ? detailView() : page === 'authors' ? authorsView() : page === 'browse' ? '<p class="note">请选择作者页签继续本次交互审阅。</p>' : `<div class="empty"><p>本页不在作者名字原型范围内。</p>${button('home', '返回《平行天堂》')}</div>`;
    app.innerHTML = `${platform === 'windows' ? '<div class="desktop-windowbar"><span>Mihon Desktop</span><span>—　□　×</span></div>' : '<div class="android-statusbar"><span>9:41</span><span>● ▰</span></div>'}<div class="bar">${button('back', V.icon('back'), 'icon-button', 'aria-label="返回"')}<h1>${title}</h1>${page === 'authors' ? button('settings', V.icon('settings'), 'icon-button author-settings', 'aria-label="作者设置"') : ''}</div>${platform === 'android' && ['browse', 'authors'].includes(page) ? `<div class="tabs">${spec.browseTabs.map(t => button(t === '作者' ? 'authors' : 'browse-boundary', t, t === '作者' && page === 'authors' ? 'selected' : '')).join('')}</div>` : ''}<main class="content">${notice ? `<p class="feedback" role="status">${esc(notice)}</p>` : ''}${inner}</main>${V.renderNav(spec, nav)}`;
    if (page === 'authors') app.querySelector('main').scrollTop = authorScroll[authorTab];
  }
  function open(kind, preserve = false) { if (!preserve) opener = document.activeElement; modal = kind; error = ''; app.inert = true; drawModal(); }
  function close() { modal = null; overlay.innerHTML = ''; app.inert = false; error = ''; if (opener?.isConnected) opener.focus(); else app.querySelector('button')?.focus(); }
  function drawModal() {
    let title = '', body = '', actions = button('cancel', '取消');
    if (modal === 'merge-select') {
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
    } else if (a === 'manga') { mangaReturn = 'author'; manga = b.dataset.version; page = 'manga'; notice = ''; render(); }
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
    if (event.data.add) {
      const alias = event.data.add === 'alias';
      if (alias && !state.authors.some(a => a.aliases.includes('Okamoto Lynn') || a.name === 'Okamoto Lynn' && a.aliases.length)) { if (!modal) { notice = '请先合并 Okamoto Lynn，再演示已合并别名的新作品。'; render(); } return; }
      state = M.observe(state, { id: alias ? 'future-alias' : 'future-same', work: alias ? 'future-alias' : 'future-same', title: alias ? '别名新作（虚构样本）' : '同名新作（虚构样本）', source: '新插件（演示）', name: alias ? 'Okamoto Lynn' : '冈本伦' });
      if (modal) { modalFocus?.focus(); return; } // Keep the open session; its snapshot is now stale.
      author = state.versions.find(v => v.id === (alias ? 'future-alias' : 'future-same')).author; page = 'author'; notice = alias ? '已加入别名作品；重复加入不增加条目。' : '已加入同名作品；未提供来源作者编号仍自动复用。'; render(); return;
    }
    if (!event.data.scenario) return;
    close(); authorScroll = { following: 0, all: 0 }; authorTab = 'following'; detailReturn = 'manga'; mangaReturn = 'authors'; state = M.create(); scenario = event.data.scenario; author = 'a'; manga = 'v0'; page = 'manga'; filter = '全部来源'; query = ''; notice = ''; selected = [];
    if (scenario === 'empty') state = M.apply(state, M.preview(state, { selected: state.authors.map(a => a.id), target: 'a' }));
    state.fail = scenario === 'submit-error'; state.unavailable = scenario === 'unavailable'; render();
  });
  render();
})();
