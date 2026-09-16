(() => {
  const M = window.AuthorIdentity, V = window.MihonSyncView;
  const platform = new URLSearchParams(location.search).get('platform') === 'android' ? 'android' : 'windows';
  const spec = V.platformSpec(platform), app = document.getElementById('app'), overlay = document.getElementById('overlay');
  let state = M.create(), page = 'manga', manga = 'v0', author = 'a', filter = '全部来源', query = '', notice = '', scenario = 'history';
  let modal = null, opener = null, plan = null, selected = [], target = 'a', followed = true, interval = 12, error = '', candidateQuery = '', displayName = '冈本伦', modalFocus = null;
  const esc = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const button = (action, text, cls = '', extra = '') => `<button data-action="${action}" class="${cls}" ${extra}>${text}</button>`;
  const current = () => state.authors.find(a => a.id === M.resolve(state, author)) || state.authors[0];
  const versions = id => state.versions.filter(v => v.author === M.resolve(state, id));
  const workNames = id => versions(id).map(v => v.title).join('、');
  const authorRow = a => `<button class="row row-button" data-author="${a.id}"><span class="avatar">${V.icon('authors')}</span><span class="grow"><strong>${esc(a.name)}</strong><span class="muted">${esc(workNames(a.id))}</span><p class="muted">${a.follow ? '已关注 · 每' + a.interval + '小时检查' : '未关注'}</p></span>${V.icon('chevron')}</button>`;
  function mangaView() {
    const v = state.versions.find(v => v.id === manga) || state.versions[0];
    return `<div class="hero"><div class="cover" aria-label="示意封面">${esc(v.title)}</div><div><span class="pill">${esc(v.source)}</span><h2>${esc(v.title)}</h2>${button('signature', esc(v.signature), 'link', 'data-testid="signature"')}<p class="muted">连载中 · 漫画</p></div></div><p class="note">相同名字自动使用同一个作者页，不区分插件或来源。点击署名查看作者。</p><h3 class="section-title">作品简介</h3><p>这是用于作者名字交互审阅的本地作品样本。封面为示意，不加载漫画图片或章节。</p>${state.unavailable ? '<p class="note warning">漫画柜来源不可用。作者、已有作品和关注仍保留。</p>' : ''}<h3 class="section-title">章节</h3><p class="muted">本原型仅演示漫画与作者之间的导航，不提供阅读或下载。</p>`;
  }
  function detailView() {
    const a = current(), all = versions(a.id), shown = all.filter(v => (filter === '全部来源' || v.source === filter) && v.title.toLowerCase().includes(query.toLowerCase()));
    const groups = new Map(); shown.forEach(v => { if (!groups.has(v.work)) groups.set(v.work, []); groups.get(v.work).push(v); });
    const sources = ['全部来源', ...new Set(all.map(v => v.source))];
    return `<div class="hero"><span class="avatar">${V.icon('authors')}</span><div><h2 data-testid="author-name">${esc(a.name)}</h2><span class="muted">${a.aliases.length ? '别名：' + a.aliases.map(esc).join(' · ') : '尚无其他名字'}</span><p class="muted">${new Set(all.map(v => v.work)).size} 部作品 · ${all.length} 个来源版本</p></div></div><div class="actions">${button('follow', a.follow ? '已关注' : '关注作者', a.follow ? '' : 'primary', 'data-testid="follow"')}${button('merge-select', '合并不同名字')}</div>${a.follow ? `<p class="muted">每 ${a.interval} 小时检查 · 全部来源 · 一个作者共用一次关注</p>` : ''}<p class="note">相同名称自动归到这里。合并过的其他名字也会作为别名，在所有插件中自动复用。</p>${state.unavailable ? '<p class="note warning">漫画柜插件不可用，已有作者、作品和关注不受影响。</p>' : ''}<h3 class="section-title">作品 <span class="text-count">${groups.size}</span></h3><div class="filters"><label>来源 <select id="source-filter">${sources.map(s => `<option ${s === filter ? 'selected' : ''}>${esc(s)}</option>`).join('')}</select></label><label>查找作品 <input id="work-search" value="${esc(query)}" placeholder="作品名" size="12"></label></div>${groups.size ? [...groups.values()].map(group => `<div class="row"><div class="cover small">${esc(group[0].title)}</div><div class="grow"><strong>${esc(group[0].title)}</strong>${group.length > 1 ? '<p class="muted">已确认同一作品 · 来源版本</p>' : ''}${group.map(v => `<div class="version"><span class="source-label">${esc(v.source)}${state.unavailable && v.source === '漫画柜' ? ' · 不可用' : ''}</span> ${button('manga', '查看漫画', '', `data-version="${v.id}"`)}</div>`).join('')}</div></div>`).join('') : '<p class="empty muted">没有符合条件的作品。可切换来源或清空搜索。</p>'}`;
  }
  function render() {
    const nav = page === 'authors' || page === 'author' ? (platform === 'android' ? 'browse' : 'authors') : page === 'manga' ? 'library' : page;
    const title = page === 'manga' ? '漫画详情' : page === 'author' ? '作者详情' : page === 'authors' ? '作者' : page === 'browse' ? '浏览' : spec.nav.find(n => n.route === page)?.label || 'Mihon';
    const inner = page === 'manga' ? mangaView() : page === 'author' ? detailView() : page === 'authors' ? `<p class="muted">相同名字共用作者；不同名字可手动合并。来源不决定人物身份。</p>${state.authors.map(authorRow).join('')}` : page === 'browse' ? '<p class="note">请选择作者页签继续本次交互审阅。</p>' : `<div class="empty"><p>本页不在作者名字原型范围内。</p>${button('home', '返回《平行天堂》')}</div>`;
    app.innerHTML = `${platform === 'windows' ? '<div class="desktop-windowbar"><span>Mihon Desktop</span><span>—　□　×</span></div>' : '<div class="android-statusbar"><span>9:41</span><span>● ▰</span></div>'}<div class="bar">${button('back', V.icon('back'), 'icon-button', 'aria-label="返回"')}<h1>${title}</h1></div>${platform === 'android' && ['browse', 'authors'].includes(page) ? `<div class="tabs">${spec.browseTabs.map(t => button(t === '作者' ? 'authors' : 'browse-boundary', t, t === '作者' && page === 'authors' ? 'selected' : '')).join('')}</div>` : ''}<main class="content">${notice ? `<p class="feedback" role="status">${esc(notice)}</p>` : ''}${inner}</main>${V.renderNav(spec, nav)}`;
  }
  function open(kind, preserve = false) { if (!preserve) opener = document.activeElement; modal = kind; error = ''; app.inert = true; drawModal(); }
  function close() { modal = null; plan = null; overlay.innerHTML = ''; app.inert = false; error = ''; if (opener?.isConnected) opener.focus(); else app.querySelector('button')?.focus(); }
  function refreshPlan() {
    selected = selected.filter(id => state.authors.some(a => a.id === id));
    if (!selected.includes(target)) target = selected[0];
    followed = state.authors.some(a => selected.includes(a.id) && a.follow);
    interval = M.recommendedInterval(state.authors.filter(a => selected.includes(a.id)));
    const allNames = state.authors.filter(a => selected.includes(a.id)).flatMap(a => [a.name, ...a.aliases]);
    if (!allNames.includes(displayName)) displayName = state.authors.find(a => a.id === target).name;
    plan = M.preview(state, { selected, target, displayName, follow: followed, interval });
  }
  function drawModal() {
    let title = '', body = '', actions = button('cancel', '取消');
    if (modal === 'merge-select') {
      title = '合并不同名字';
      const allCandidates = state.authors.filter(a => a.id !== current().id);
      const candidates = allCandidates.filter(a => [a.name, ...a.aliases].some(n => n.toLowerCase().includes(candidateQuery.toLowerCase())));
      body = `<p>当前：${esc(current().name)}。选择要并入同一作者的其他名字。</p><p class="muted">仅文本完全相同才自动复用；繁简、大小写和音译不会自动转换。</p><label>搜索名字或别名<input id="candidate-search" value="${esc(candidateQuery)}" placeholder="输入名字"></label><p class="muted">已选择 ${selected.length - 1} 个其他作者。${selected.length < 2 ? '至少勾选一位其他作者才能继续。' : '搜索不会取消已选项。'}</p>${candidates.map(a => `<label class="note inline-label"><input type="checkbox" data-select="${a.id}" ${selected.includes(a.id) ? 'checked' : ''}><span><strong>${esc(a.name)}</strong>${a.aliases.length ? '<br>别名：' + a.aliases.map(esc).join('、') : ''}<br>${esc(workNames(a.id))}<br>${a.follow ? '已关注' : '未关注'}</span></label>`).join('') || `<p class="empty">${allCandidates.length ? '没有匹配的名字，清空搜索可查看全部。' : '没有可合并的其他名字。'}</p>`}`;
      actions += button('merge-preview', '查看合并预览', 'primary', selected.length < 2 ? 'disabled' : '');
    } else if (modal === 'merge-preview') {
      title = '合并预览';
      body = `<p>合并 ${plan.authorSnapshot.length} 个作者，共 ${plan.names.length} 个名字，保留 ${plan.workCount} 部作品、${plan.versionCount} 个来源版本。</p><div class="note">${plan.authorSnapshot.map(a => `<strong>${esc(a.name)}</strong>${a.aliases.length ? '<br>已有别名：' + a.aliases.map(esc).join('、') : ''}<br>${esc(plan.versionSnapshot.filter(v => v.author === a.id).map(v => v.title).join('、'))}`).join('<br>')}</div><label>主显示名<select id="retain">${plan.names.map(n => `<option value="${esc(n)}" ${n === displayName ? 'selected' : ''}>${esc(n)}</option>`).join('')}</select></label><p>其余名字成为全局别名。任何插件今后使用其中任一名字，都自动加入这位作者；未选择的名字保持独立。</p><label><input type="checkbox" id="merge-follow" ${followed ? 'checked' : ''}>合并后关注这位作者</label><label>检查频率<select id="interval"><option value="12" ${interval === 12 ? 'selected' : ''}>每12小时</option><option value="24" ${interval === 24 ? 'selected' : ''}>每24小时</option></select></label><p class="muted">当前所选：${plan.followedCount} 个已关注，${plan.authorSnapshot.length - plan.followedCount} 个未关注。确认后统一使用上方设置，覆盖已合并名字的全部来源。</p><p class="note warning">合并前会保存恢复资料。本页仅模拟说明，不生成文件、不改用户数据。不能一键原样撤销，恢复备份可能覆盖之后的变化。</p><p class="muted">漫画条目、章节、下载及阅读记录不改变；同一作品版本使用已有确认关系，不靠名字判断。</p>`;
      actions = button('merge-back', '返回选择') + button('cancel', '取消') + button('commit', '确认合并', 'primary');
    } else {
      title = '取消关注作者？'; body = `<p>将停止关注「${esc(current().name)}」及其所有别名的全部来源作品，作者关系和作品仍保留。</p>`; actions += button('unfollow-confirm', '取消关注', 'primary');
    }
    overlay.innerHTML = `<div class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="dialog-title"><header><h2 id="dialog-title">${title}</h2>${button('cancel', V.icon('close'), 'icon-button', 'aria-label="关闭"')}</header><div class="modal-body">${body}${error ? `<p class="error" role="alert">${esc(error)}</p>${modal === 'merge-preview' ? button('refresh-preview', '刷新预览') : ''}` : ''}</div><footer>${actions}</footer></section></div>`;
    overlay.querySelector('button')?.focus();
  }
  document.addEventListener('click', event => {
    if (event.target.classList.contains('modal-backdrop')) { close(); return; }
    const b = event.target.closest('button'); if (!b) return;
    if (b.dataset.author) { author = M.resolve(state, b.dataset.author); page = 'author'; notice = ''; render(); return; }
    if (b.dataset.route) { page = b.dataset.route; notice = ''; render(); return; }
    const a = b.dataset.action;
    if (a === 'cancel') close();
    else if (a === 'signature') { author = state.versions.find(v => v.id === manga).author; page = 'author'; render(); }
    else if (a === 'merge-select') { selected = [current().id]; target = current().id; displayName = current().name; candidateQuery = ''; open('merge-select'); }
    else if (a === 'merge-back') open('merge-select', true);
    else if (a === 'merge-preview') { refreshPlan(); if (scenario === 'stale') { state.authors.find(v => v.id === current().id).follow = false; state.revision++; } open('merge-preview', true); }
    else if (a === 'refresh-preview') { state.fail = false; scenario = 'history'; refreshPlan(); error = ''; drawModal(); }
    else if (a === 'commit') {
      try { state = M.apply(state, { ...plan, target, displayName, follow: followed, interval }); author = M.resolve(state, target); notice = '已合并不同名字，所有名字都将作为别名全局复用。'; close(); page = 'author'; render(); app.querySelector('[data-testid="follow"]').focus(); }
      catch (e) { error = e.message; drawModal(); }
    } else if (a === 'follow' || a === 'unfollow-confirm') {
      if (a === 'follow' && current().follow) { open('unfollow'); return; }
      try { state = M.follow(state, current().id, a === 'follow'); if (modal) close(); notice = ''; render(); app.querySelector('[data-testid="follow"]').focus(); }
      catch (e) { if (modal) { error = e.message; drawModal(); } else { notice = e.message; render(); } }
    } else if (a === 'manga') { manga = b.dataset.version; page = 'manga'; notice = ''; render(); }
    else if (a === 'authors') { page = 'authors'; notice = ''; render(); }
    else if (a === 'home') { manga = 'v0'; page = 'manga'; notice = ''; render(); }
    else if (a === 'back') { page = page === 'author' ? 'manga' : page === 'manga' ? 'authors' : 'manga'; notice = ''; render(); }
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
    else if (e.id === 'retain') { displayName = e.value; target = plan.authorSnapshot.find(a => [a.name, ...a.aliases].includes(displayName)).id; }
    else if (e.id === 'merge-follow') followed = e.checked;
    else if (e.id === 'interval') interval = Number(e.value);
    else if (e.id === 'source-filter') { filter = e.value; render(); document.getElementById('source-filter').focus(); }
    else if (e.id === 'work-search') { query = e.value; render(); document.getElementById('work-search').focus(); }
  });
  document.addEventListener('keydown', event => {
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
    close(); state = M.create(); scenario = event.data.scenario; author = 'a'; manga = 'v0'; page = 'manga'; filter = '全部来源'; query = ''; notice = ''; selected = [];
    if (scenario === 'empty') state = M.apply(state, M.preview(state, { selected: state.authors.map(a => a.id), target: 'a', follow: true, interval: 12 }));
    state.fail = scenario === 'submit-error'; state.unavailable = scenario === 'unavailable'; render();
  });
  render();
})();
