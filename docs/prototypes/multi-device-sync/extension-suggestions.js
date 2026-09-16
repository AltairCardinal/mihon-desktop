(function () {
  'use strict';
  // IDs remain strings, including values beyond Number.MAX_SAFE_INTEGER.
  const sources = [
    { id: '9007199254740993', name: '星海漫画', url: 'https://star.example' },
    { id: '9007199254740994', name: '黎明漫画', url: 'https://dawn.example' },
    { id: '9007199254740995', name: '星海短篇', url: 'https://short.example' },
  ];
  const catalog = [
    { id: 'aurora', name: '星海', pkg: 'demo.extension.aurora', repo: '演示仓库 A', sources: [sources[0], sources[2]] },
    { id: 'ink', name: '黎明', pkg: 'demo.extension.ink', repo: '演示仓库 A', sources: [sources[1]] },
    { id: 'archive', name: '旧日漫画', pkg: 'demo.extension.archive', repo: '演示仓库 A', sources: [{ id: '880', name: '旧日漫画', url: '' }] },
  ];
  const identity = item => `${item.repo}:${item.pkg}`;
  function match(favorites, installed, ignored) {
    const counts = new Map();
    for (const item of favorites) if (item.sourceId !== '0') counts.set(item.sourceId, (counts.get(item.sourceId) || 0) + 1);
    return catalog.filter(item => !installed.includes(item.id) && !ignored.includes(identity(item)))
      .map(item => ({ ...item, matched: item.sources.filter(source => counts.has(source.id)), count: item.sources.reduce((n, source) => n + (counts.get(source.id) || 0), 0) }))
      .filter(item => item.count);
  }
  window.MihonExtensionSuggestions = { match, create };
  function create({ state, currentDevice, isWindows, esc, render }) {
    const devices = new Map();
    const key = () => 'mihon-demo-extension-suggestions-v1-' + currentDevice().id;
    function local() {
      const id = currentDevice().id;
      if (!devices.has(id)) {
        let saved = {};
        try { saved = JSON.parse(localStorage.getItem(key()) || '{}'); } catch (_) { /* File storage may be unavailable. */ }
        devices.set(id, { expanded: saved.expanded !== false, ignored: Array.isArray(saved.ignored) ? saved.ignored : [], installed: ['archive'], search: '', scene: 'default', installer: 'system', permission: true, failNext: false, statuses: {}, batch: null, dialog: null, undo: null });
      }
      return devices.get(id);
    }
    function save() { const d = local(); try { localStorage.setItem(key(), JSON.stringify({ expanded: d.expanded, ignored: d.ignored })); } catch (_) { d.message = '浏览器未允许本地存储，本次选择只在当前页面保留。'; } }
    function favorites() {
      const mapping = { 'manga-star': sources[0].id, 'manga-dawn': sources[1].id, 'manga-night': '0' };
      const result = currentDevice().favorites.map(id => ({ sourceId: mapping[id] || '0' }));
      if (local().scene !== 'default') result.push({ sourceId: sources[2].id }, { sourceId: sources[1].id }, { sourceId: '880' }, { sourceId: '999' });
      return result;
    }
    function items() { return match(favorites(), local().installed, local().ignored); }
    function visible() { const q = local().search.toLowerCase(); return items().filter(item => (item.name + item.pkg + item.matched.map(s => s.name).join(' ')).toLowerCase().includes(q)); }
    const find = id => catalog.find(item => item.id === id);
    const busy = id => ['排队中', '下载中', '等待系统确认', '安装中'].includes(local().statuses[id]);
    function button(label, action, id = '', disabled = false) { return `<button class="m-button m-button-text" data-ext="${action}" data-id="${id}" data-testid="ext-${action}${id ? '-' + id : ''}" ${disabled ? 'disabled' : ''}>${label}</button>`; }
    function row(item, suggested) {
      const d = local(); const status = d.statuses[item.id];
      return `<article class="ext-row" data-testid="ext-${suggested ? 'row' : 'available'}-${item.id}"><div class="ext-avatar">${esc(item.name[0])}</div><div class="ext-copy"><strong>${esc(item.name)}</strong><small>中文 · 1.4.2 · ${esc(item.repo)}</small>${suggested ? `<span>${item.matched.map(s => esc(s.name)).join('、')} · ${item.count} 部收藏</span>` : ''}${status ? `<small class="ext-state" role="status">${esc(status)}</small>` : ''}</div><div class="ext-actions">${suggested ? button('忽略', 'ignore', item.id, busy(item.id)) + button(isWindows() ? '打开网站' : 'WebView', 'web', item.id) : ''}${button(status === '安装失败，请重试' ? '重试' : busy(item.id) ? status : '安装', 'install', item.id, busy(item.id))}</div></article>`;
    }
    function progress() {
      const b = local().batch;
      if (!b) return '';
      const done = b.success + b.failed + b.cancelled + b.skipped;
      return `<div class="ext-progress" data-testid="ext-progress" role="status"><strong>${b.paused ? '已暂停' : b.active ? '正在安装' : '安装结果'} · ${done}/${b.ids.length}</strong><span>成功 ${b.success} · 失败 ${b.failed} · 取消 ${b.cancelled} · 跳过 ${b.skipped}</span>${b.reason ? `<span>${esc(b.reason)}</span>` : ''}${b.paused ? button('继续剩余', 'resume') : ''}${b.active ? button('停止后续安装', 'stop') : ''}</div>`;
    }
    function renderList() {
      const d = local(); const all = items(); const list = visible();
      return `<div class="extension-page" data-testid="extension-page"><label class="ext-search">搜索插件<input data-ext-search aria-label="搜索插件" placeholder="插件、图源或包名" value="${esc(d.search)}"></label>${d.scene === 'incomplete' ? `<div class="ext-note">部分仓库加载失败，建议可能不完整。${button('重试', 'catalog-retry')}</div>` : ''}${all.length || d.batch || d.scene !== 'default' ? `<section class="ext-suggestions"><div class="ext-heading">${button(`${d.expanded ? '▾' : '▸'} 建议安装 (${d.search ? list.length + '/' : ''}${all.length})`, 'toggle').replace('data-ext=', `aria-expanded="${d.expanded}" aria-controls="ext-items" data-ext=`)}${button(`${d.search ? '安装匹配项' : '全部安装'} (${list.filter(i => !busy(i.id)).length})`, 'all', '', !list.some(i => !busy(i.id)) || Boolean(d.batch?.active))}</div>${progress()}${d.expanded ? `<div id="ext-items"><p class="ext-hint">根据本机收藏匹配 · 不受语言筛选影响</p><div class="ext-suggestion-scroll">${list.map(item => row(item, true)).join('') || '<p class="ext-hint">没有匹配的建议</p>'}</div>${d.scene !== 'default' ? `<p class="ext-hint">另有 1 个缺失图源未匹配到可安装插件。${button('查看缺失图源', 'missing')}</p>` : ''}</div>` : ''}</section>` : ''}${d.undo ? `<div class="ext-snackbar" role="status">已忽略此插件建议${button('撤销', 'undo')}</div>` : ''}${d.message ? `<p class="ext-note" role="status">${esc(d.message)}</p>` : ''}<div class="simple-list-heading">已安装</div>${d.installed.map(id => `<div class="ext-installed"><strong>${esc(find(id).name)}</strong><small>${id === 'archive' ? '已安装 · 加载失败，请检查插件' : '已安装 · 可用'}</small></div>`).join('')}<div class="simple-list-heading">可用</div>${catalog.filter(item => !d.installed.includes(item.id)).map(item => row(item, false)).join('')}</div>`;
    }
    function modal() {
      const d = local(); const dialog = d.dialog;
      if (!dialog) return '';
      let title; let content;
      if (dialog.type === 'review') { title = '安装这些插件？'; content = `<p>本次共 ${dialog.ids.length} 个插件</p><ul>${dialog.ids.map(id => `<li>${esc(find(id).name)} · ${esc(find(id).repo)}</li>`).join('')}</ul><p>${isWindows() ? '下载后安装到当前电脑。' : d.installer === 'private' ? '私有安装：仅当前应用可用。' : '系统安装：Android 可能要求逐个确认安装。'}</p>${button('返回', 'close')}${button('开始安装', 'start')}`; }
      else if (dialog.type === 'system') { title = '系统安装确认（模拟）'; content = `<p>要安装「${esc(find(dialog.id).name)}」吗？</p><p>当前 ${d.batch.index + 1}/${d.batch.ids.length} · 一次只处理一个插件</p>${button('取消', 'system-cancel')}${button('安装', 'system-install')}`; }
      else if (dialog.type === 'permission') { title = '需要安装权限'; content = `<p>允许当前应用安装插件后，返回继续。未授权前不会处理后续插件。</p>${button('暂不授权', 'close')}${button('打开设置（模拟授权）', 'grant')}`; }
      else if (dialog.type === 'sources') { title = '选择图源网站'; content = dialog.sources.map(source => button(esc(source.name), 'site', source.id)).join('') + button('返回', 'close'); }
      else if (dialog.type === 'site') { title = isWindows() ? '网站预览（模拟外部浏览器）' : 'WebView（本地模拟）'; content = `<div class="ext-address">${esc(dialog.source.url)}</div><div class="ext-site"><strong>${esc(dialog.source.name)}</strong><p>本地网站示意</p><p>正式版本将打开图源网站；无需先安装插件，网站可能仍要求登录。</p></div>${button('关闭', 'close')}`; }
      else { title = '未匹配的图源'; content = `<p>未知图源 · 1 部收藏</p><p>当前仓库目录尚未找到对应插件。正式版本可前往迁移或仓库管理。</p>${button('返回', 'close')}`; }
      return `<div class="ext-modal-layer"><div class="ext-modal-scrim"></div><section class="ext-dialog" role="dialog" aria-modal="true" aria-label="${title}" tabindex="-1"><h2>${title}</h2>${content}</section></div>`;
    }
    function begin(ids) {
      const d = local(); if (d.batch?.active) return;
      const unique = [...new Set(ids)].filter(id => !d.installed.includes(id) && !busy(id));
      d.dialog = null; d.batch = { ids: unique, index: 0, active: true, paused: false, success: 0, failed: 0, cancelled: 0, skipped: 0, installer: d.installer, windows: isWindows() };
      unique.forEach(id => { d.statuses[id] = '排队中'; }); advance(d);
    }
    function advance(d) {
      const b = d.batch; if (!b || !b.active || b.paused) return;
      if (b.stopAfterCurrent) { b.active = false; b.paused = false; render(false); return; }
      if (b.index >= b.ids.length) { b.active = false; b.reason = '本次处理已完成；失败或取消项可单独重试。'; render(false); return; }
      const id = b.ids[b.index];
      if (d.installed.includes(id)) { b.skipped++; b.index++; advance(d); return; }
      if (!b.windows && b.installer !== d.installer) { b.paused = true; b.reason = '安装方式已变更，请确认后继续。'; render(false); return; }
      if (!b.windows && d.installer === 'system' && !d.permission) { b.paused = true; b.reason = '需要允许安装权限'; d.dialog = { type: 'permission' }; render(false); return; }
      d.statuses[id] = '下载中'; render(false);
      d.timer = setTimeout(() => {
        if (!b.active || b.paused) return;
        if (!b.windows && b.installer !== d.installer) { b.paused = true; b.reason = '安装方式已变更，请确认后继续。'; render(false); return; }
        if (d.failNext) { d.failNext = false; d.statuses[id] = '安装失败，请重试'; b.failed++; b.index++; advance(d); return; }
        if (!b.windows && d.installer === 'system') { d.statuses[id] = '等待系统确认'; d.dialog = { type: 'system', id }; render(false); }
        else finish(d, id);
      }, 400);
    }
    function finish(d, id) { d.statuses[id] = '安装中'; d.dialog = null; render(false); d.timer = setTimeout(() => { d.installed.push(id); delete d.statuses[id]; d.batch.success++; d.batch.index++; advance(d); render(false); }, 300); }
    function action(action, id) {
      const d = local(); d.message = null;
      if (action === 'toggle') { d.expanded = !d.expanded; save(); }
      if (action === 'ignore' && !busy(id)) { d.ignored.push(identity(find(id))); d.undo = id; save(); }
      if (action === 'undo') { d.ignored = d.ignored.filter(value => value !== identity(find(d.undo))); d.undo = null; save(); }
      if (action === 'all') d.dialog = { type: 'review', ids: visible().filter(item => !busy(item.id)).map(item => item.id) };
      if (action === 'start') begin(d.dialog.ids);
      if (action === 'install') { if (d.batch?.active) d.message = '当前批次尚未结束，请先完成或停止后续安装。'; else begin([id]); }
      if (action === 'system-install') finish(d, d.dialog.id);
      if (action === 'system-cancel') { d.statuses[d.dialog.id] = '已取消，可重新安装'; d.dialog = null; d.batch.cancelled++; d.batch.index++; d.batch.paused = !d.batch.stopAfterCurrent; d.batch.active = !d.batch.stopAfterCurrent; d.batch.reason = d.batch.stopAfterCurrent ? '已停止后续安装，当前安装已取消。' : '你取消了当前安装，后续安装已暂停。'; }
      if (action === 'resume') { d.batch.paused = false; d.batch.reason = ''; d.batch.installer = d.installer; advance(d); }
      if (action === 'stop') {
        const b = d.batch; const system = d.dialog?.type === 'system';
        const from = b.index + (system || d.statuses[b.ids[b.index]] === '安装中' ? 1 : 0);
        for (const item of b.ids.slice(from)) delete d.statuses[item];
        b.skipped += b.ids.length - from;
        if (from === b.index) { clearTimeout(d.timer); b.active = false; b.paused = false; }
        b.reason = system ? '已停止后续安装，当前系统确认仍需处理。' : '已停止后续安装，已成功的插件保留。';
        if (from > b.index) b.stopAfterCurrent = true;
      }
      if (action === 'close') d.dialog = null;
      if (action === 'grant') { d.permission = true; d.dialog = null; d.batch.reason = '已允许安装，请继续剩余。'; }
      if (action === 'web') { const item = items().find(item => item.id === id); if (item.matched.length > 1) d.dialog = { type: 'sources', sources: item.matched }; else d.dialog = { type: 'site', source: item.matched[0] }; }
      if (action === 'site') d.dialog = { type: 'site', source: sources.find(source => source.id === id) };
      if (action === 'missing') d.dialog = { type: 'missing' };
      if (action === 'catalog-retry') { d.scene = 'sample'; d.message = '仓库目录已刷新；仍有 1 个图源尚未匹配。'; }
      render(false);
      document.querySelector('.ext-dialog button')?.focus();
    }
    function scene(value) {
      const d = local();
      if (value === 'reset') { clearTimeout(d.timer); try { localStorage.removeItem(key()); } catch (_) {} devices.delete(currentDevice().id); }
      else if (value === 'private') { d.installer = 'private'; d.message = '当前安装方式：私有安装，仅此应用可用。'; }
      else if (value === 'permission') { d.installer = 'system'; d.permission = false; d.message = '当前未允许安装未知应用。'; }
      else if (value === 'failure') { d.failNext = true; d.message = '下一项安装将模拟下载失败。'; }
      else d.scene = value;
      state.ui.route = 'browse'; state.ui.browseTab = 'extensions'; state.ui.detail = null; state.ui.reader = false; state.ui.syncOpen = false; render(false);
    }
    return { renderList, modal, action, scene, input(target) { if (target.matches('[data-ext-search]')) { local().search = target.value; render(false); } }, reset() { for (const d of devices.values()) clearTimeout(d.timer); scene('reset'); }, close() { local().undo = null; local().dialog = null; }, local };
  }
})();
