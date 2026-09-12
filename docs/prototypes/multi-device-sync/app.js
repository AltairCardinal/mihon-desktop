(function () {
  'use strict';

  const model = window.MihonSyncModel;
  const root = document.getElementById('app');
  const state = model.createDemoState();
  state.selectedDevice = 'desktop-b';
  state.ui = { busy: false, timerId: null, busyDeviceId: null, notice: '这是可离线打开的模拟演示。', configured: true };

  const esc = (value) => String(value).replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
  const device = () => model.getDevice(state, state.selectedDevice);
  const title = (id) => (model.CATALOG[id] || {}).title || id;
  const author = (id) => (model.AUTHORS[id] || {}).name || id;
  const statusLabel = (item) => item === 'attention' ? '待处理' : item === 'offline' ? '离线' : item === 'syncing' ? '同步中' : '空闲';
  const countAll = (key) => Object.values(state.devices).reduce((sum, item) => sum + item[key].length, 0);

  function setNotice(message, tone) {
    state.ui.notice = message;
    state.ui.tone = tone || 'info';
  }

  function activeStatus(item) {
    if (state.ui.busy && item.id === state.ui.busyDeviceId) return 'syncing';
    if (!state.online) return 'offline';
    if (item.confirmations.length || item.conflicts.length) return 'attention';
    if (item.pendingOutgoing.length) return 'attention';
    return item.status;
  }

  function button(label, attrs, className) {
    return `<button class="button ${className || ''}" ${attrs || ''}>${label}</button>`;
  }

  function renderDeviceLab() {
    const current = device();
    const devices = Object.values(state.devices).map((item) => {
      const active = item.id === state.selectedDevice;
      return `<button class="device-card ${active ? 'is-selected' : ''}" data-device="${item.id}" data-testid="device-${item.id}" aria-pressed="${active}">
        <span class="device-icon ${item.kind === 'Desktop' ? 'icon-desktop' : 'icon-phone'}" aria-hidden="true"></span>
        <span class="device-copy"><strong>${esc(item.name)}</strong><small>${esc(item.kind)} · ${statusLabel(activeStatus(item))}</small></span>
        <span class="status-dot ${activeStatus(item)}" aria-hidden="true"></span>
      </button>`;
    }).join('');
    return `<aside class="lab-panel" aria-label="设备实验台">
      <div class="lab-heading"><div><span class="eyebrow">演示辅助</span><h2>设备实验台</h2></div><span class="simulation-mark">模拟</span></div>
      <p class="lab-intro">先在一台设备操作并点「立即同步」，再切另一台设备点「立即同步」查看结果。</p>
      <div class="device-list">${devices}</div>
      <div class="lab-divider"></div>
      <div class="lab-section-title"><span>当前设备操作</span><span class="tiny-label">${esc(current.name)}</span></div>
      <div class="lab-actions">
        ${button(current.favorites.includes('manga-star') ? '取消收藏《星海骑士》' : '收藏《星海骑士》', 'data-action="favorite" data-testid="toggle-favorite"', 'button-soft')}
        ${button(current.following.includes('author-river') ? '取消关注白河' : '关注作者白河', 'data-action="follow" data-testid="toggle-follow"', 'button-soft')}
        ${button('记录阅读到第 42 页', 'data-action="read" data-testid="record-reading"', 'button-soft')}
        ${button(current.readingActive ? '结束阅读中状态' : '模拟正在阅读', 'data-action="reader" data-testid="toggle-reader"', 'button-soft')}
      </div>
      <label class="mode-control">本设备阅读模式（不会同步）<select data-action="mode" data-testid="reading-mode"><option ${current.modes.manga['manga-star'] === '单页' ? 'selected' : ''}>单页</option><option ${current.modes.manga['manga-star'] === '双页' ? 'selected' : ''}>双页</option><option ${current.modes.manga['manga-star'] === '条漫' ? 'selected' : ''}>条漫</option></select></label>
      <p class="local-note"><span class="note-icon" aria-hidden="true">⌁</span>收藏、阅读、关注先写入本地；离线时也不会丢失。</p>
    </aside>`;
  }

  function renderUpdates() {
    const current = device();
    const cards = ['manga-star', 'manga-dawn', 'manga-night'].map((id, index) => {
      const book = model.CATALOG[id];
      const favorite = current.favorites.includes(id);
      const position = current.readPositions[id];
      return `<article class="update-row">
        <div class="cover cover-${index + 1}" aria-hidden="true"><span>${esc(book.title.slice(0, 2))}</span></div>
        <div class="update-copy"><div class="row-overline">${esc(book.source)} · 今日 ${index + 1} 章</div><h3>${esc(book.title)}</h3><p>${esc(book.author)} · ${position ? `读到 ${esc(position.chapterId)} 第 ${position.page} 页` : '尚未记录阅读位置'}</p></div>
        <div class="row-actions">${button(favorite ? '已收藏' : '收藏', `data-action="${favorite ? 'unfavorite' : 'favorite-book'}" data-object="${id}"`, favorite ? 'button-quiet is-on' : 'button-quiet')}</div>
      </article>`;
    }).join('');
    return `<section class="panel-content updates-panel" data-testid="updates-panel">
      <div class="content-heading"><div><span class="eyebrow">更新</span><h2>今天有新的章节</h2><p>原有更新内容保留在这里，同步是旁边的平级页签。</p></div><span class="count-pill">3 个更新</span></div>
      <div class="update-list">${cards}</div>
      <div class="info-strip"><span class="info-glyph" aria-hidden="true">i</span><span>阅读漫画不会自动收藏。取消收藏也不会删除阅读历史、下载或本地阅读模式。</span></div>
    </section>`;
  }

  function renderCounts(current) {
    const failures = current.lastResult && current.lastResult.ok === false ? 1 : 0;
    return `<div class="metric-grid">
      <div class="metric-card"><span class="metric-icon purple" aria-hidden="true">↑</span><div><strong>${current.pendingOutgoing.length}</strong><span>待发送</span></div></div>
      <div class="metric-card"><span class="metric-icon amber" aria-hidden="true">!</span><div><strong>${current.confirmations.length}</strong><span>待确认</span></div></div>
      <div class="metric-card"><span class="metric-icon blue" aria-hidden="true">◇</span><div><strong>${current.conflicts.length}</strong><span>冲突</span></div></div>
      <div class="metric-card"><span class="metric-icon coral" aria-hidden="true">↻</span><div><strong>${failures}</strong><span>失败</span></div></div>
    </div>`;
  }

  function renderConfirmation(item) {
    const isAuthor = item.kind === 'author-remove';
    return `<article class="todo-card" data-testid="confirmation-${item.id}">
      <div class="todo-icon amber-icon">${isAuthor ? '人' : '书'}</div><div class="todo-main"><div class="todo-label">接收端确认 · ${esc(item.sourceName)}</div><h3>${isAuthor ? `取消关注「${esc(author(item.objectId))}」` : `取消收藏《${esc(title(item.objectId))}》`}</h3><p>${esc(item.message)}</p></div>
      <div class="todo-actions">${button('确认取消', `data-confirm="${esc(item.id)}" data-testid="confirm-${esc(item.id)}"`, 'button-danger')}${button('忽略本次', `data-ignore="${esc(item.id)}" data-testid="ignore-${esc(item.id)}"`, 'button-quiet')}</div>
    </article>`;
  }

  function renderConflict(item) {
    return `<article class="todo-card conflict-card" data-testid="conflict-${esc(item.id)}">
      <div class="todo-icon blue-icon">≋</div><div class="todo-main"><div class="todo-label">需要选择 · ${esc(item.remoteDevice)}</div><h3>${esc(item.summary)}</h3><p>时间只用于解释；选择会生成新的本地决定，旧操作不会偷偷覆盖后续动作。</p></div>
      <div class="todo-actions">${button('保留本地', `data-conflict="${esc(item.id)}" data-choice="local"`, 'button-quiet')}${button('采用远端', `data-conflict="${esc(item.id)}" data-choice="remote"`, 'button-primary')}</div>
    </article>`;
  }

  function renderReading(current) {
    const position = current.readPositions['manga-star'];
    const currentPosition = current.currentReadingPositions['manga-star'] || position;
    const pendingPosition = current.pendingRemotePositions['manga-star'];
    const suggestion = current.remoteSuggestions[0];
    const text = (item) => item ? `第 ${esc(item.chapterId.replace('chapter-', ''))} 章 · 第 ${item.page} 页` : '暂无续读位置';
    return `<div class="subsection reading-card" data-testid="reading-status"><div class="subsection-heading"><div><span class="eyebrow">阅读同步</span><h3>续读位置与阅读模式分开</h3></div><span class="local-badge">设备本地模式</span></div><div class="reading-line"><div class="book-mark cover-1">星海</div><div>${current.readingActive ? `<strong>当前画面：${text(currentPosition)}</strong><p>远端位置到达时不会自动翻页。</p><div class="next-position">下次续读：${text(pendingPosition || position)}</div>` : `<strong>下次续读：${text(position)}</strong><p>进入详情时可以使用已同步的位置。</p>`}</div></div>${suggestion ? `<div class="suggestion"><span><b>远端位置提示</b><small>不会改变当前画面</small></span><span>${text(suggestion.position)}</span><button data-action="adopt-remote" class="text-button text-button-primary">采用此位置</button><button data-action="dismiss-suggestion" class="text-button">知道了</button></div>` : ''}</div>`;
  }

  function renderSync() {
    const current = device();
    const status = activeStatus(current);
    const result = current.lastResult;
    const pending = current.confirmations.length + current.conflicts.length;
    const setupText = state.ui.configured ? `同步空间 · Mihon 演示空间 · ${esc(current.name)}` : '还没有配置同步空间';
    return `<section class="panel-content sync-panel" data-testid="sync-panel">
      <div class="sync-heading"><div><span class="eyebrow">同步中心</span><h2>让每台设备都知道你的决定</h2><p>本轮按“接收端分别确认”规则演示。每台设备可以独立保留差异。</p></div><div class="heading-status ${status}"><span class="status-dot ${status}"></span><span data-testid="sync-status">${esc(!state.online ? '离线，操作会保留' : status === 'syncing' ? '同步中…' : pending ? `有 ${pending} 项待处理` : current.pendingOutgoing.length ? '有操作待发送' : '已准备好')}</span></div></div>
      <div class="config-row"><div class="config-mark" aria-hidden="true">⌘</div><div><strong>${setupText}</strong><span>Git 只是候选方案；本演示没有连接任何服务。</span></div>${button(state.ui.configured ? '查看设置' : '配置同步', 'data-action="configure" data-testid="configure-sync"', 'button-quiet')}</div>
      ${renderCounts(current)}
      <div class="sync-toolbar"><div><span class="eyebrow">交换操作</span><h3>选择一个方式，把本地决定送到其他设备</h3></div><div class="trigger-buttons">${button('立即同步', 'data-trigger="manual" data-testid="manual-sync"', 'button-primary')}${button('模拟启动同步', 'data-trigger="startup" data-testid="startup-sync"', 'button-quiet')}${button('模拟定期到期', 'data-trigger="periodic" data-testid="periodic-sync"', 'button-quiet')}</div></div>
      ${result ? `<div class="result-banner ${result.ok ? 'success' : 'failure'}" data-testid="sync-result"><span class="result-icon">${result.ok ? '✓' : '!'}</span><div><strong>${esc(result.message)}</strong><span>${result.ok ? '确认与冲突仍需在本设备单独处理。' : '本地操作没有删除，联网后可重试。'}</span></div></div>` : ''}
      ${pending ? `<div class="subsection todo-section"><div class="subsection-heading"><div><span class="eyebrow">待处理</span><h3>需要你的决定</h3></div><span class="count-pill amber-count">${pending} 项</span></div>${current.confirmations.map(renderConfirmation).join('')}${current.conflicts.map(renderConflict).join('')}</div>` : `<div class="empty-state"><span class="empty-check">✓</span><div><strong>当前设备没有待确认项目</strong><p>其他设备的同步仍会在这里显示，后台操作不会打断当前页面。</p></div></div>`}
      ${renderReading(current)}
      <div class="subsection settings-card" data-testid="settings-card"><div class="subsection-heading"><div><span class="eyebrow">本设备设置</span><h3>自动触发保持独立</h3></div><span class="tiny-label">${esc(current.name)}</span></div><div class="setting-row"><div><strong>启动时自动同步</strong><span>模拟启动按钮会读取这个开关</span></div><button class="switch ${current.settings.startupSync ? 'is-on' : ''}" data-action="startup-setting" role="switch" aria-label="启动时自动同步" aria-checked="${current.settings.startupSync}" data-testid="startup-setting"><span></span></button></div><div class="setting-row"><div><strong>后台定期同步</strong><span>周期 ${current.settings.periodMinutes} 分钟；演示可立即触发</span></div><button class="switch ${current.settings.periodicSync ? 'is-on' : ''}" data-action="periodic-setting" role="switch" aria-label="后台定期同步" aria-checked="${current.settings.periodicSync}" data-testid="periodic-setting"><span></span></button></div></div>
      <div class="boundary-note"><span class="note-icon">◇</span><div><strong>演示边界</strong><p>${esc(state.demo.boundary)} 失败恢复、系统后台精度、跨源匹配和真实凭据不在本轮范围。</p></div></div>
    </section>`;
  }

  function render() {
    const current = device();
    root.innerHTML = `<header class="topbar"><div class="brand"><span class="brand-glyph">m</span><span>Mihon</span><span class="brand-divider"></span><span class="brand-context">同步演示</span></div><div class="top-actions"><span class="offline-badge"><span class="status-dot ${state.online ? 'online' : 'offline'}"></span>${state.online ? '模拟在线' : '模拟离线'}</span><button class="icon-button" data-action="toggle-online" data-testid="network-toggle" aria-label="${state.online ? '切换为离线' : '恢复模拟在线'}">${state.online ? '↯' : '⌁'}</button><button class="reset-button" data-action="reset" data-testid="reset-demo">重置演示</button></div></header><main class="workspace">${renderDeviceLab()}<section class="product-panel"><div class="product-top"><div class="breadcrumb"><span>更新</span><span class="breadcrumb-arrow">/</span><strong>${state.activePanel === 'updates' ? '章节更新' : '同步'}</strong></div><div class="product-caption">${esc(current.name)} <span>·</span> ${statusLabel(activeStatus(current))}</div></div><nav class="page-tabs" aria-label="更新页内导航"><button class="page-tab ${state.activePanel === 'updates' ? 'is-active' : ''}" data-panel="updates" data-testid="updates-tab">更新<span class="tab-underline"></span></button><button class="page-tab ${state.activePanel === 'sync' ? 'is-active' : ''}" data-panel="sync" data-testid="sync-tab">同步${countAll('confirmations') ? `<span class="tab-count">${countAll('confirmations')}</span>` : ''}<span class="tab-underline"></span></button></nav><div class="notice-line ${state.ui.tone || 'info'}"><span class="notice-dot"></span><span data-testid="notice">${esc(state.ui.notice)}</span></div>${state.activePanel === 'updates' ? renderUpdates() : renderSync()}</section></main><footer class="bottom-nav"><button class="bottom-item" data-action="bookshelf" data-testid="bookshelf-nav"><span class="bottom-icon">▦</span><span>书架</span></button><button class="bottom-item is-current" data-panel="updates" data-testid="updates-bottom-nav"><span class="bottom-icon">◷</span><span>更新</span></button><button class="bottom-item" data-action="more" data-testid="more-nav"><span class="bottom-icon">⋯</span><span>更多</span></button></footer>`;
  }

  function scheduleSync(trigger) {
    if (state.ui.busy) { setNotice('同步正在进行，可以继续切换设备或记录阅读；请稍候查看本轮结果。', 'info'); render(); return; }
    const runDeviceId = state.selectedDevice;
    state.ui.busy = true; state.ui.busyDeviceId = runDeviceId; setNotice(`${model.TRIGGER_LABELS[trigger]}已开始；操作不会被阻塞。`, 'info'); render();
    const timerId = window.setTimeout(() => {
      if (state.ui.timerId !== timerId) return;
      const result = model.triggerSync(state, runDeviceId, trigger);
      state.ui.timerId = null; state.ui.busy = false; state.ui.busyDeviceId = null;
      setNotice(result.message, result.ok ? 'success' : 'failure'); render();
    }, 420);
    state.ui.timerId = timerId;
  }

  function handleAction(action, target) {
    const current = device();
    if (action === 'toggle-online') { model.setOnline(state, !state.online); setNotice(state.online ? '模拟网络已恢复，可以重试待发送操作。' : '模拟网络已断开；继续收藏、阅读和关注不会丢失。', state.online ? 'success' : 'failure'); }
    else if (action === 'reset') { if (state.ui.timerId) window.clearTimeout(state.ui.timerId); model.resetDemo(state); state.selectedDevice = 'desktop-b'; state.ui = { busy: false, timerId: null, busyDeviceId: null, notice: '演示已重置：已恢复一个有待确认示例的初始状态。', tone: 'success', configured: true }; }
    else if (action === 'configure') { state.ui.configured = true; state.ui.focusSettings = true; setNotice('已定位到当前设备设置；真实服务连接留待需求确认。', 'success'); }
    else if (action === 'bookshelf') { setNotice('书架入口在此演示中仅展示导航反馈；同步仍从更新页内进入。'); }
    else if (action === 'more') { setNotice('更多入口在此演示中保留为导航占位；当前范围聚焦更新与同步。'); }
    else if (action === 'favorite') { current.favorites.includes('manga-star') ? model.localUnfavorite(state, current.id, 'manga-star') : model.localFavorite(state, current.id, 'manga-star'); setNotice('本地收藏已更新，等待下一次交换。'); }
    else if (action === 'favorite-book') { model.localFavorite(state, current.id, target.dataset.object); setNotice(`已收藏《${title(target.dataset.object)}》，不会因另一端缺失而取消。`, 'success'); }
    else if (action === 'unfavorite') { model.localUnfavorite(state, current.id, target.dataset.object); setNotice(`已取消收藏《${title(target.dataset.object)}》，接收端仍需确认。`); }
    else if (action === 'follow') { current.following.includes('author-river') ? model.localUnfollow(state, current.id, 'author-river') : model.localFollow(state, current.id, 'author-river'); setNotice('本地作者关注已更新，等待下一次交换。'); }
    else if (action === 'read') { model.localRead(state, current.id, 'manga-star', 'chapter-3', 42); setNotice('阅读位置已保存在本设备；下一次同步会发送。', 'success'); }
    else if (action === 'reader') { model.setReadingActive(state, current.id, !current.readingActive); setNotice(current.readingActive ? '已标记为正在阅读；远端位置只显示提示。' : '已结束正在阅读状态。'); }
    else if (action === 'adopt-remote') { model.adoptRemotePosition(state, current.id, 'manga-star'); setNotice('已采用远端位置，当前阅读画面与下次续读位置都已更新。', 'success'); }
    else if (action === 'dismiss-suggestion') { current.remoteSuggestions.shift(); setNotice('已收起远端位置提示；不会自动跳页。'); }
    else if (action === 'startup-setting') { current.settings.startupSync = !current.settings.startupSync; setNotice(`启动自动同步已${current.settings.startupSync ? '开启' : '关闭'}。`); }
    else if (action === 'periodic-setting') { current.settings.periodicSync = !current.settings.periodicSync; setNotice(`后台定期同步已${current.settings.periodicSync ? '开启' : '关闭'}。`); }
  }

  root.addEventListener('click', (event) => {
    const target = event.target.closest('button'); if (!target) return;
    if (target.dataset.device) { state.selectedDevice = target.dataset.device; setNotice(`已切换到${device().name}；操作和设置按设备分别保留。`); }
    else if (target.dataset.panel) { state.activePanel = target.dataset.panel; setNotice(state.activePanel === 'sync' ? '同步页显示实际模拟状态、待确认和冲突。' : '更新页保留原有章节列表和收藏操作。'); }
    else if (target.dataset.trigger) scheduleSync(target.dataset.trigger);
    else if (target.dataset.confirm) { model.confirmCancellation(state, state.selectedDevice, target.dataset.confirm); setNotice('已确认取消，仅改变当前接收设备；其他设备不会被代替确认。', 'success'); }
    else if (target.dataset.ignore) { model.ignoreCancellation(state, state.selectedDevice, target.dataset.ignore); setNotice('已忽略本次取消；当前设备保留状态，也不会反向恢复来源设备。'); }
    else if (target.dataset.conflict) { model.resolveConflict(state, state.selectedDevice, target.dataset.conflict, target.dataset.choice); setNotice('冲突已处理，新决定已进入待发送队列。', 'success'); }
    else if (target.dataset.action) handleAction(target.dataset.action, target);
    render();
    if (state.ui.focusSettings) { state.ui.focusSettings = false; window.requestAnimationFrame(() => document.querySelector('[data-testid="settings-card"]')?.scrollIntoView({ behavior: 'smooth', block: 'center' })); }
  });

  root.addEventListener('change', (event) => {
    const target = event.target; if (target.dataset.action === 'mode') { model.setReadingMode(state, state.selectedDevice, 'manga-star', target.value); setNotice(`本设备的《星海骑士》阅读模式已设为${target.value}；不会发送到其他设备。`, 'success'); render(); }
  });

  window.__mihonSyncDemo = { state, model, render, scheduleSync };
  render();
})();
