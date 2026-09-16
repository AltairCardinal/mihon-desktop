(() => {
  const M = window.AuthorIdentity, V = window.MihonSyncView;
  const platform = new URLSearchParams(location.search).get('platform') === 'android' ? 'android' : 'windows';
  const spec = V.platformSpec(platform), app = document.getElementById('app'), overlay = document.getElementById('overlay');
  let state = M.create(), page = 'manga', manga = 'v0', author = 'a', filter = '全部来源', query = '', notice = '', scenario = 'history';
  let modal = null, opener = null, plan = null, selected = [], target = 'a', follow = true, interval = 12, dialogError = '', loadingTimer = null;
  const esc = value => String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
  const button = (action, text, cls = '', extra = '') => `<button data-action="${action}" class="${cls}" ${extra}>${text}</button>`;
  const current = () => state.authors.find(a => a.id === M.resolve(state, author)) || state.authors[0];
  const historic = () => state.authors.filter(a => ['a', 'b', 'c'].includes(a.id));
  const versions = id => state.versions.filter(v => v.author === M.resolve(state, id));
  const workNames = id => versions(id).map(v => v.title).join('、');
  function authorRow(a) {
    return `<button class="row row-button" data-author="${a.id}"><span class="avatar">${V.icon('authors')}</span><span class="grow"><strong>${esc(a.name)}</strong><span class="muted">${esc(workNames(a.id))}</span><p class="muted">${[...new Set(versions(a.id).map(v => v.source))].join(' · ')} · ${a.follow ? '已关注 · 每' + a.interval + '小时检查' : '未关注'}</p></span>${V.icon('chevron')}</button>`;
  }
  function mangaView() {
    const v = state.versions.find(v => v.id === manga) || state.versions[0];
    return `<div class="hero"><div class="cover" aria-label="示意封面">${esc(v.title)}</div><div><span class="pill">${esc(v.source)}</span><h2>${esc(v.title)}</h2>${button('signature', v.source === 'MangaDex' ? 'Okamoto Lynn' : '冈本伦', 'link', 'data-testid="signature"')}<p class="muted">连载中 · 漫画</p></div></div><p class="note">${v.id === 'v0' && historic().length > 1 ? '点击作者署名，查看已有作者档案与作品依据。' : '已确认的署名直接进入同一作者页，关注对这位作者生效。'}</p><h3 class="section-title">作品简介</h3><p>这是用于作者身份交互审阅的本地作品样本。封面为示意，不加载漫画图片或章节。</p>${state.unavailable ? '<p class="note warning">此来源不可用。作者档案、已有作品和关注仍保留；恢复插件后可继续访问来源。</p>' : ''}<h3 class="section-title">章节</h3><p class="muted">本原型仅演示漫画与作者之间的导航，不提供阅读或下载。</p>`;
  }
  function detailView() {
    const a = current(), all = versions(a.id), shown = all.filter(v => (filter === '全部来源' || v.source === filter) && v.title.toLowerCase().includes(query.toLowerCase()));
    const groups = new Map(); shown.forEach(v => { if (!groups.has(v.work)) groups.set(v.work, []); groups.get(v.work).push(v); });
    return `<div class="hero"><span class="avatar">${V.icon('authors')}</span><div><h2 data-testid="author-name">${esc(a.name)}</h2><span class="muted">${a.id === 'other' ? '虚构人物 · 与当前作者保持独立' : '别名：岡本倫 · Okamoto Lynn'}</span><p class="muted">${new Set(all.map(v => v.work)).size} 部作品 · ${all.length} 个来源版本</p></div></div><div class="actions">${button('follow', a.follow ? '已关注' : '关注作者', a.follow ? '' : 'primary', 'data-testid="follow"')}${a.id !== 'other' ? button('sources', '关联其他来源') : ''}</div>${a.follow ? `<p class="muted">每 ${a.interval} 小时检查 · 所有已关联来源 · 一次关注共用</p>` : ''}${state.unavailable ? '<p class="note warning">漫画柜插件不可用，已有作者、作品和关注不受影响。</p>' : ''}${a.id !== 'other' ? `<div class="actions">${button('pending', '待确认署名 · ' + state.pending.length)}${historic().length > 1 ? button('merge-select', '整理作者') : ''}</div>` : ''}<h3 class="section-title">作品 <span class="text-count">${groups.size}</span></h3><div class="filters"><label>来源 <select id="source-filter">${['全部来源', '漫画柜', 'MangaDex', '文字图源（演示）'].map(s => `<option ${s === filter ? 'selected' : ''}>${s}</option>`).join('')}</select></label><label>查找作品 <input id="work-search" value="${esc(query)}" placeholder="作品名" size="12"></label></div>${groups.size ? [...groups.values()].map(group => `<div class="row"><div class="cover small">${esc(group[0].title)}</div><div class="grow"><strong>${esc(group[0].title)}</strong>${group.length > 1 ? '<p class="muted">已确认同一作品 · 来源版本</p>' : ''}${group.map(v => `<div class="version"><span class="source-label">${esc(v.source)}</span> ${button('manga', '查看漫画', '', `data-version="${v.id}"`)}</div>`).join('')}</div></div>`).join('') : '<p class="empty muted">没有符合条件的作品。可切换来源或清空搜索。</p>'}`;
  }
  function render() {
    const nav = page === 'authors' || page === 'author' ? (platform === 'android' ? 'browse' : 'authors') : page === 'manga' ? 'library' : page;
    const title = page === 'manga' ? '漫画详情' : page === 'author' ? '作者详情' : page === 'authors' ? '作者' : page === 'browse' ? '浏览' : spec.nav.find(n => n.route === page)?.label || 'Mihon';
    const inner = page === 'manga' ? mangaView() : page === 'author' ? detailView() : page === 'authors' ? `<p class="muted">按作者查看作品与关注。来源已确认后共用同一档案。</p>${state.authors.map(authorRow).join('')}` : page === 'browse' ? '<p class="note">请选择作者页签继续本次交互审阅。</p>' : `<div class="empty"><p>本页不在作者身份原型范围内。</p>${button('home', '返回《平行天堂》')}</div>`;
    app.innerHTML = `${platform === 'windows' ? '<div class="desktop-windowbar"><span>Mihon Desktop</span><span>—　□　×</span></div>' : '<div class="android-statusbar"><span>9:41</span><span>● ▰</span></div>'}<div class="bar">${button('back', V.icon('back'), 'icon-button', 'aria-label="返回"')}<h1>${title}</h1></div>${platform === 'android' && ['browse', 'authors'].includes(page) ? `<div class="tabs">${spec.browseTabs.map(t => button(t === '作者' ? 'authors' : 'browse-boundary', t, t === '作者' && page === 'authors' ? 'selected' : '')).join('')}</div>` : ''}<main class="content">${notice ? `<p class="feedback" role="status">${esc(notice)}</p>` : ''}${inner}</main>${V.renderNav(spec, nav)} `;
  }
  function open(kind, preserveOpener = false) {
    if (!preserveOpener) opener = document.activeElement;
    modal = kind; dialogError = ''; app.inert = true; drawModal();
    clearTimeout(loadingTimer);
    if (kind === 'sources' && scenario === 'loading') loadingTimer = setTimeout(() => {
      if (modal === 'sources' && scenario === 'loading') { scenario = 'confirmed'; drawModal(); }
    }, 1200);
  }
  function close() {
    clearTimeout(loadingTimer); modal = null; overlay.innerHTML = ''; app.inert = false; plan = null; dialogError = '';
    if (opener?.isConnected) opener.focus(); else app.querySelector('button')?.focus();
  }
  function makePlan(kind) {
    plan = M.preview(state, kind, { target: target, selected: [...selected], follow, interval });
    if (scenario === 'stale' && kind === 'merge') {
      // The scenario changes real in-memory follow data after taking the snapshot.
      state.authors.find(a => a.id === 'b').follow = false;
      state.authors.find(a => a.id === 'b').interval = 24;
      state.revision++;
    }
  }
  function drawModal() {
    let title = '', body = '', actions = button('cancel', '取消');
    if (modal === 'choose') {
      title = '选择作者'; body = '<p class="muted">存在多个历史档案，请根据作品和来源辨认。</p>' + historic().map(authorRow).join(''); actions += button('merge-select', '整理作者', 'primary');
    } else if (modal === 'merge-select') {
      title = '整理作者'; body = '<p>选择确认为同一人的历史档案。未勾选的档案保持原样。</p>' + historic().map(a => `<label class="note inline-label"><input type="checkbox" data-merge="${a.id}" ${selected.includes(a.id) ? 'checked' : ''}><span><strong>冈本伦 · ${esc(workNames(a.id))}</strong><br>漫画柜 · ${a.follow ? '已关注，每12小时检查' : '未关注'}<br><span class="muted">作品署名指向漫画柜同一作者页</span></span></label>`).join(''); actions += button('merge-preview', '查看归集预览', 'primary', selected.length < 2 ? 'disabled' : '');
    } else if (modal === 'merge-preview') {
      title = '归集预览';
      const snapshot = plan.authorSnapshot, versionSnapshot = plan.versionSnapshot;
      const summary = `${plan.followedCount} 个已关注，${snapshot.length - plan.followedCount} 个未关注`;
      const frequencySummary = snapshot.filter(a => a.follow).map(a => `每${a.interval}小时`).join('、') || '暂无启用的检查计划';
      body = `<p>将 ${snapshot.length} 个作者档案归为同一人，保留 ${plan.workCount} 部作品、${plan.versionCount} 个来源版本。旧作者入口会转到保留的档案。</p><div class="note">${snapshot.map(a => '冈本伦 · ' + esc(versionSnapshot.filter(v => v.author === a.id).map(v => v.title).join('、'))).join('<br>')}</div><label>保留的作者档案<select id="retain">${state.authors.filter(a => selected.includes(a.id)).map(a => `<option value="${a.id}" ${target === a.id ? 'selected' : ''}>冈本伦 · ${esc(workNames(a.id))}</option>`).join('')}</select></label><label><input type="checkbox" id="merge-follow" ${follow ? 'checked' : ''}>归集后关注这位作者</label><label>检查频率<select id="interval"><option value="12" ${interval === 12 ? 'selected' : ''}>每12小时</option><option value="24" ${interval === 24 ? 'selected' : ''}>每24小时</option></select></label><p class="muted">当前所选：${summary}；${frequencySummary}。归集后按上方选择应用，来源范围为本次档案已有的全部来源。</p><p class="note warning">确认前会保存可恢复备份。本页仅模拟说明，不生成备份文件、不改用户数据。归集不能一键原样撤销；恢复备份可能覆盖之后的变化。</p><p class="muted">只整理作者关系。漫画、章节、下载、阅读记录及合作作者不改变。</p>${state.sync ? '<p class="error">作者关注同步已开启。当前版本暂不支持归集；不会更改同步设置。可取消预览。</p>' : ''}`;
      actions = button('merge-select', '返回选择') + button('cancel', '取消') + button('commit', '确认归集', 'primary', state.sync ? 'disabled' : '');
    } else if (modal === 'sources') {
      title = '关联其他来源';
      if (scenario === 'loading') { body = '<div role="status">正在获取作品署名与来源依据…</div><div class="inline-progress"></div>'; }
      else if (scenario === 'evidence-error') { body = '<p class="error">暂时无法获取 MangaDex 依据。现有作者与关注未改变。</p>'; actions += button('retry-evidence', '重试', 'primary'); }
      else if (scenario === 'empty' || state.linked) body = '<p class="empty">暂无其他可关联的来源候选。已有来源与作者保持不变。</p>';
      else { body = `<p class="muted">候选只提供线索，确认前不会关联。</p><label class="note inline-label"><input type="checkbox" id="link-select" ${selected.includes('md') ? 'checked' : ''}><span><strong>Okamoto Lynn</strong><br>MangaDex · Parallel Paradise<br>同一作品已确认，作品角色与别名相符。</span></label>${button('evidence', '查看依据', 'link')}`; actions += button('link-preview', '预览关联', 'primary', selected.includes('md') ? '' : 'disabled'); }
    } else if (modal === 'evidence') {
      title = '关联依据'; body = '<p class="note">本地证据样本，不访问来源网站。</p><p>漫画柜：平行天堂 → 冈本伦作者页<br>MangaDex：Parallel Paradise → Okamoto Lynn 作者页</p><p>两份来源版本已确认属于同一作品；作者角色和别名一致。不同来源的作者标识只用于溯源，仍需确认是同一人。</p>'; actions = button('sources-return', '返回候选');
    } else if (modal === 'link-preview') {
      title = '确认关联来源'; body = `<p>MangaDex 的 Okamoto Lynn 将关联到「${esc(current().name)}」。</p><p class="note">Parallel Paradise 将作为《平行天堂》的另一个来源版本展示。已确认同一作品，不按标题猜测。</p><p>两个来源的署名将打开同一作者页，沿用此作者的关注状态。今后相同来源作者标识的作品复用此档案；仅名字相同仍需确认。</p>`; actions += button('commit', '确认关联', 'primary', state.sync ? 'disabled' : '');
    } else if (modal === 'pending') {
      title = '待确认署名'; body = `<p class="muted">文字图源（演示）仅提供名字。本次确认只关联勾选作品，不把同名署名全部归为一人。</p><div class="actions">${button('select-all', '全选')}${button('select-none', '取消全选')}</div>${state.pending.map(p => `<label class="note inline-label"><input type="checkbox" data-pending="${p.id}" ${selected.includes(p.id) ? 'checked' : ''}><span><strong>${p.title}</strong><br>署名：冈本伦 · 文字图源（演示）<br>未确认同一作品，关联后保留独立来源条目</span></label>`).join('') || '<p class="empty">暂无待确认作品。</p>'}${!state.rejected ? `<div class="note"><strong>冈本伦（同名演示人物）</strong><p>海边手记（虚构样本） · 创作领域与本作者不同</p>${button('reject-preview', '不是这位作者／保持独立')}</div>` : `<p class="note">海边手记（虚构样本）已保持独立，不进入当前作者作品集。</p>${!state.distinct ? button('distinct-preview', '为同名人物创建另一位作者') : ''}`}`; actions += button('text-preview', `确认所选 ${selected.length} 部作品`, 'primary', selected.length ? '' : 'disabled');
    } else if (modal === 'text-preview') {
      title = '确认这些作品的作者'; body = `<p>将以下 ${selected.length} 部作品的署名关联到「${esc(current().name)}」：</p><div class="note">${state.pending.filter(p => selected.includes(p.id)).map(p => p.title).join('<br>')}</div><p>未勾选作品仍待确认；未来同名作品不会自动关联。不会认定这些作品与现有作品是同一本。</p>`; actions += button('commit', '确认关联所选', 'primary', state.sync ? 'disabled' : '');
    } else if (modal === 'reject-preview' || modal === 'distinct-preview') {
      title = modal === 'reject-preview' ? '保持独立' : '创建另一位作者'; body = `<p>「冈本伦（同名演示人物）」与当前作者是不同人。</p><p class="note">海边手记（虚构样本）不会加入当前作者的作品或关注范围。${modal === 'distinct-preview' ? '将建立独立作者页，默认不关注。' : '保留为独立署名，可稍后为其创建另一位作者。'}</p>`; actions += button('commit', modal === 'reject-preview' ? '确认保持独立' : '确认创建', 'primary');
    } else if (modal === 'unfollow') {
      title = '取消关注作者？'; body = `<p>将停止关注「${esc(current().name)}」的所有已关联来源。作品与作者关系仍保留。</p>`; actions += button('commit', '取消关注', 'primary');
    }
    if (state.sync && ['link-preview', 'text-preview'].includes(modal)) body += '<p class="error">作者关注同步已开启，当前版本暂不支持新增作者关联。原记录与同步设置保持不变。</p>';
    overlay.innerHTML = `<div class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="dialog-title"><header><h2 id="dialog-title">${title}</h2>${button('cancel', V.icon('close'), 'icon-button', 'aria-label="关闭"')}</header><div class="modal-body">${body}${dialogError ? `<p class="error" role="alert">${esc(dialogError)}</p>${button('refresh-preview', '刷新预览')}` : ''}</div><footer>${actions}</footer></section></div>`;
    overlay.querySelector('button')?.focus();
  }
  function commit() {
    try {
      if (modal === 'merge-preview') Object.assign(plan, { target, follow, interval });
      state = M.apply(state, plan);
      const type = plan.kind;
      author = M.resolve(state, target); page = 'author'; filter = '全部来源'; query = '';
      notice = type === 'merge' ? '已归集作者档案，保留全部所选作品；旧入口已转到此作者。' : type === 'link' ? '已关联 MangaDex。两种来源署名共用此作者与关注。' : type === 'text' ? `已关联 ${selected.length} 部作品，其他署名仍待确认。` : type === 'reject' ? '已保持独立，不会加入这位作者。' : type === 'distinct' ? '已创建独立作者，可从作者列表查看。' : '已取消关注，作品与作者关系保留。';
      close(); render(); app.querySelector('[data-testid="follow"]')?.focus();
    } catch (error) { dialogError = error.message; drawModal(); }
  }
  document.addEventListener('click', event => {
    if (event.target.classList.contains('modal-backdrop')) { close(); return; }
    const b = event.target.closest('button'); if (!b) return;
    if (b.dataset.author) { author = M.resolve(state, b.dataset.author); close(); page = 'author'; notice = ''; render(); return; }
    if (b.dataset.route) { page = b.dataset.route; notice = ''; render(); return; }
    const action = b.dataset.action;
    if (action === 'cancel') return close();
    if (action === 'signature') { const v = state.versions.find(v => v.id === manga); if (v.id === 'v0' && historic().length > 1) open('choose'); else { author = v.author; page = 'author'; render(); } }
    else if (action === 'merge-select') { if (!['merge-select', 'merge-preview'].includes(modal)) selected = historic().map(a => a.id); open('merge-select', !!modal); }
    else if (action === 'merge-preview') { target = selected.includes(target) ? target : selected[0]; follow = state.authors.some(a => selected.includes(a.id) && a.follow); makePlan('merge'); open('merge-preview', true); }
    else if (action === 'sources') { selected = []; target = current().id; open('sources'); }
    else if (['sources-return', 'retry-evidence'].includes(action)) { if (action !== 'sources-return') scenario = 'confirmed'; open('sources', true); }
    else if (action === 'evidence') open('evidence', true);
    else if (action === 'link-preview') { makePlan('link'); open('link-preview', true); }
    else if (action === 'pending') { selected = []; target = current().id; open('pending'); }
    else if (action === 'select-all' || action === 'select-none') { selected = action === 'select-all' ? state.pending.map(p => p.id) : []; drawModal(); }
    else if (action === 'text-preview') { makePlan('text'); open('text-preview', true); }
    else if (action === 'reject-preview' || action === 'distinct-preview') { makePlan(action === 'reject-preview' ? 'reject' : 'distinct'); open(action, true); }
    else if (action === 'commit') commit();
    else if (action === 'refresh-preview') {
      state.fail = false; scenario = 'confirmed';
      const kind = plan.kind;
      if (kind === 'merge') {
        selected = selected.filter(id => state.authors.some(a => a.id === id));
        if (!selected.includes(target)) target = selected[0];
        follow = state.authors.some(a => selected.includes(a.id) && a.follow);
        interval = Math.min(...state.authors.filter(a => selected.includes(a.id)).map(a => a.interval));
      }
      makePlan(kind); dialogError = ''; drawModal();
    }
    else if (action === 'follow') { target = current().id; if (current().follow) { plan = M.preview(state, 'follow', { target, follow: false }); open('unfollow'); } else { try { state = M.apply(state, M.preview(state, 'follow', { target, follow: true })); notice = ''; } catch (error) { notice = error.message; } render(); app.querySelector('[data-testid="follow"]').focus(); } }
    else if (action === 'manga') { manga = b.dataset.version; page = 'manga'; notice = ''; render(); }
    else if (action === 'authors') { page = 'authors'; notice = ''; render(); }
    else if (action === 'home') { manga = 'v0'; page = 'manga'; notice = ''; render(); }
    else if (action === 'back') { page = page === 'author' ? 'manga' : page === 'manga' ? 'authors' : 'manga'; notice = ''; render(); }
    else if (action === 'browse-boundary') { notice = '此页签不在作者身份原型范围内，请进入作者页签。'; render(); }
  });
  document.addEventListener('change', event => {
    const e = event.target;
    if (e.dataset.merge || e.dataset.pending) { const id = e.dataset.merge || e.dataset.pending; selected = e.checked ? [...selected, id] : selected.filter(s => s !== id); const focusKey = e.dataset.merge ? 'merge' : 'pending'; drawModal(); overlay.querySelector(`[data-${focusKey}="${id}"]`)?.focus(); }
    else if (e.id === 'link-select') { selected = e.checked ? ['md'] : []; drawModal(); overlay.querySelector('#link-select')?.focus(); }
    else if (e.id === 'retain') target = e.value;
    else if (e.id === 'merge-follow') follow = e.checked;
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
    if (!event.data.scenario) return;
    close(); state = M.create(); scenario = event.data.scenario; author = 'a'; manga = 'v0'; page = 'manga'; filter = '全部来源'; query = ''; notice = ''; selected = []; target = 'a'; interval = 12;
    if (['confirmed', 'loading', 'evidence-error', 'empty', 'unavailable'].includes(scenario)) { state = M.apply(state, M.preview(state, 'merge', { target: 'a', follow: true })); page = 'author'; }
    state.fail = scenario === 'submit-error'; state.sync = scenario === 'sync'; state.unavailable = scenario === 'unavailable'; render();
  });
  render();
})();
