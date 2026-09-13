(function () {
  'use strict';

  const preview = window.frameElement?.dataset.platform ? window.parent.MihonPreview : null;
  const model = preview ? window.parent.MihonSyncModel : window.MihonSyncModel;
  const view = window.MihonSyncView;
  const root = document.getElementById('app');
  let sheetDrag = null;
  let selectionPress = null;
  let suppressSelectionClickUntil = 0;
  const state = preview ? preview.connect() : model.createDemoState();
  state.selectedDevice = 'desktop-b';
  const UPDATE_IDS = ['manga-star', 'manga-dawn', 'manga-night'];
  state.ui = {
    platform: 'windows', theme: 'dark', route: 'library', browseTab: 'sources',
    detail: null, reader: false, busy: false, timerId: null, busyDeviceId: null,
    notice: 'Windows Desktop 原生界面预览；同步仍是离线演示。', tone: 'info', filter: false, calendar: false,
    readUpdates: { 'desktop-b': ['manga-night'], 'phone-a': ['manga-night'] },
  };

  const esc = (value) => String(value).replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
  const spec = () => view.platformSpec(state.ui.platform);
  const currentDevice = () => model.getDevice(state, state.selectedDevice);
  const book = (id) => model.CATALOG[id] || { title: id, source: '本地演示源', author: '未知作者' };
  const creator = (id) => model.AUTHORS[id] || { name: id, detail: '作者识别信息仅用于演示' };
  const isWindows = () => state.ui.platform === 'windows';
  const readIds = (deviceId) => {
    if (!state.ui.readUpdates) state.ui.readUpdates = {};
    if (!state.ui.readUpdates[deviceId]) state.ui.readUpdates[deviceId] = [];
    return state.ui.readUpdates[deviceId];
  };
  const isUpdateUnread = (deviceId, objectId) => !readIds(deviceId).includes(objectId);
  const unreadUpdateCount = (deviceId) => UPDATE_IDS.filter((id) => isUpdateUnread(deviceId, id)).length;
  function markUpdateRead(deviceId, objectId) {
    const ids = readIds(deviceId);
    if (!ids.includes(objectId)) ids.push(objectId);
  }
  function markAllUpdatesRead(deviceId) {
    state.ui.readUpdates[deviceId] = UPDATE_IDS.slice();
  }
  function pendingDecisionCount(device) { return device.confirmations.length; }
  function syncWorkCount(device) {
    return interactions.connected() ? pendingDecisionCount(device) : 0;
  }
  function navIndicators() { return { unreadCount: unreadUpdateCount(currentDevice().id) }; }
  function navigateRoute(route) {
    clearBatchSelection();
    state.ui.syncOpen = false; state.ui.syncSettingsOpen = false;
    state.ui.route = route; state.ui.detail = null; state.ui.reader = false;
  }
  function renderLibrarySyncButton() {
    const current = currentDevice();
    const count = syncWorkCount(current);
    const busy = (state.ui.busy && state.ui.busyDeviceId === current.id) || interactions.busy();
    const label = '同步' + (busy ? '；更新中' : '') + (count ? '；' + count + ' 项需要手动处理' : '');
    return `<button class="m-icon-button library-sync-button" data-action="open-sync" data-testid="library-sync" data-syncing="${busy}" aria-label="${label}" title="${label}">${view.icon('sync')}${count ? `<span class="library-sync-count" data-testid="library-sync-count" aria-hidden="true">${count > 99 ? '99+' : count}</span>` : ''}</button>`;
  }
  const button = (label, attrs, className) => `<button class="m-button ${className || ''}" ${attrs || ''}>${label}</button>`;
  const iconButton = (name, label, attrs, className) => `<button class="m-icon-button ${className || ''}" aria-label="${esc(label)}" title="${esc(label)}" ${attrs || ''}>${view.icon(name)}</button>`;
  const iconLabel = (name, label, attrs, className) => `<button class="m-icon-label ${className || ''}" ${attrs || ''}>${view.icon(name, label)}</button>`;
  const interactions = window.MihonSyncInteractions.create({ state, currentDevice, isWindows, esc, view, button, render });

  function notice(message, tone) {
    state.ui.notice = message;
    state.ui.tone = tone || 'info';
  }

  function activeStatus(device) {
    if (state.ui.busy && device.id === state.ui.busyDeviceId) return 'syncing';
    if (!state.online) return 'offline';
    if (device.confirmations.length || device.conflicts.length) return 'attention';
    if (device.pendingOutgoing.length) return 'pending';
    return 'idle';
  }

  function statusText(device) {
    const status = activeStatus(device);
    return status === 'syncing' ? '更新中' : status === 'offline' ? '离线，操作会保留' : status === 'attention' ? '有待处理' : status === 'pending' ? '有待上传' : '已准备好';
  }

  function topBar(title, actions, options) {
    const opts = options || {};
    return `<header class="native-appbar ${opts.compact ? 'compact' : ''} ${opts.back ? 'has-back' : ''}">${opts.back ? iconButton('back', '返回', 'data-action="back" data-testid="app-back"') : ''}<h1>${esc(title)}</h1><div class="appbar-actions">${actions || ''}</div></header>`;
  }

  function renderPreviewTools() {
    const windows = state.ui.platform === 'windows';
    return `<details class="preview-tools" data-testid="preview-tools"><summary><span class="tool-summary-icon">${view.icon('settings')}</span><strong>演示预览</strong><span class="tool-summary-muted">应用外控制 · ${windows ? 'Windows Desktop' : 'Android 手机'} · ${state.ui.theme === 'light' ? '浅色' : '深色'}</span></summary><div class="preview-tool-panel"><div class="tool-group"><span>平台</span><div class="tool-choice">${button('Windows Desktop', 'data-platform="windows" data-testid="platform-windows"', windows ? 'is-selected' : '')}${button('Android 手机', 'data-platform="android" data-testid="platform-android"', windows ? '' : 'is-selected')}</div></div><div class="tool-group"><span>主题</span><div class="tool-choice">${button('浅色', 'data-theme="light" data-testid="theme-light"', state.ui.theme === 'light' ? 'is-selected' : '')}${button('深色', 'data-theme="dark" data-testid="theme-dark"', state.ui.theme === 'dark' ? 'is-selected' : '')}</div></div><div class="tool-group"><span>同步触发</span><div class="tool-choice">${button('模拟启动同步', 'data-action="sync-startup" data-testid="startup-sync"', 'm-button-tonal')}${button('模拟定期到期', 'data-action="sync-periodic" data-testid="periodic-sync"', 'm-button-tonal')}</div></div><div class="tool-group tool-actions"><span>模拟条件</span>${iconLabel(state.online ? 'cloud' : 'close', state.online ? '切换离线' : '恢复在线', 'data-action="toggle-online" data-testid="network-toggle"')}${button('120 项待处理', 'data-action="many-pending" data-testid="many-pending"', 'm-button-tonal')}${button('重置演示', 'data-action="reset" data-testid="reset-demo"', 'tool-reset')}</div><small class="preview-boundary">本地离线演示，不连接 Git 或系统后台任务。</small></div></details>`;
  }

  function renderWindowShell(content) {
    const platform = state.ui.platform;
    const shell = platform === 'android' ? 'android-shell' : 'windows-shell';
    const frame = platform === 'android' ? `<div class="android-statusbar"><span>9:41</span><span class="status-icons">${view.icon('wifi')}${view.icon('signal')}${view.icon('battery')}</span></div>` : `<div class="desktop-windowbar"><span class="desktop-title"><img src="./mihon-desktop.png" alt="Mihon Desktop 图标"><span>Mihon Desktop 0.11.19.33 · 本地原型</span></span><span class="window-controls" aria-hidden="true"><i></i><i></i><i class="window-close"></i></span></div>`;
    const nav = state.ui.reader || (state.ui.detail && platform === 'android') ? '' : view.renderNav(spec(), state.ui.route, navIndicators());
    return `<section class="app-window ${shell}" data-platform="${platform}" data-testid="app-window">${frame}<div class="app-body">${content}</div>${nav}${platform === 'android' ? '<div class="gesture-area" aria-hidden="true"></div>' : ''}${state.ui.syncOpen ? renderSyncSheet() : ''}</section>`;
  }

  function renderUpdatesActions() {
    const actions = [iconButton('filter', '筛选', 'data-action="filter" data-testid="updates-filter"'), iconButton('calendar', '查看即将更新', 'data-action="calendar" data-testid="updates-calendar"')];
    if (isWindows() && unreadUpdateCount(currentDevice().id) > 0) actions.push(iconButton('check', '全部标为已读', 'data-action="mark-all" data-testid="updates-mark-all"'));
    actions.push(iconButton('refresh', '刷新更新', 'data-action="refresh" data-testid="updates-refresh"'));
    return actions.join('');
  }

  function renderUpdateList() {
    const current = currentDevice();
    const items = UPDATE_IDS;
    if (isWindows()) {
      return `<div class="updates-list desktop-updates-list"><div class="date-heading">今天</div>${items.map((id, index) => renderWindowsUpdate(id, index, current)).join('')}<div class="date-heading yesterday">昨天</div>${renderWindowsUpdate('manga-night', 3, current)}</div>`;
    }
    return `<div class="updates-list android-updates-list"><div class="previous-updates">上次更新</div><div class="date-heading">今天</div>${items.map((id, index) => renderAndroidUpdate(id, index, current)).join('')}<div class="date-heading yesterday">昨天</div>${renderAndroidUpdate('manga-night', 3, current)}</div>`;
  }

  function renderWindowsUpdate(id, index, current) {
    const unread = isUpdateUnread(current.id, id);
    const item = book(id); const position = current.readPositions[id];
    return `<article class="desktop-update-card" data-action="open-manga" data-object="${id}" tabindex="0" data-testid="desktop-update-${id}"><div class="cover windows-cover cover-${(index % 3) + 1}">${esc(item.title.slice(0, 2))}</div><div class="update-main"><strong>${esc(item.title)}</strong><span class="chapter ${unread ? 'unread' : ''}">${unread ? '第 ' + (12 - index) + ' 话 · 未读' : '第 ' + (12 - index) + ' 话'}</span><small>${esc(item.author)} · ${position ? `读到第 ${position.page} 页` : '尚未阅读'}</small></div><time>${index === 0 ? '刚刚' : `${index + 1} 小时前`}</time><div class="update-actions">${iconButton('download', '下载章节', 'data-action="download" data-object="' + id + '"')}${unread ? iconButton('check', '标为已读', 'data-action="mark-read" data-testid="mark-read-' + id + '" data-object="' + id + '"') : ''}</div></article>`;
  }

  function renderAndroidUpdate(id, index, current) {
    const unread = isUpdateUnread(current.id, id);
    const item = book(id); const position = current.readPositions[id];
    return `<article class="android-update-row" data-action="open-manga" data-object="${id}" tabindex="0" data-testid="android-update-${id}"><div class="cover android-cover cover-${(index % 3) + 1}">${esc(item.title.slice(0, 2))}</div><div class="update-main"><strong>${esc(item.title)}</strong><span class="chapter ${unread ? 'unread' : ''}">${unread ? '第 ' + (12 - index) + ' 话 · 未读' : '第 ' + (12 - index) + ' 话'}</span></div><span class="unread-dot ${unread ? 'is-unread' : ''}"></span>${iconButton('download', '下载章节', 'data-action="download" data-object="' + id + '"', 'row-icon')}</article>`;
  }

  function renderUpdatesPage() {
    return `<div class="route-view updates-route">${topBar(isWindows() ? '最近更新' : '更新', renderUpdatesActions())}<div class="route-scroll"><div class="updates-content">${state.ui.filter ? '<div class="filter-row"><span>未读</span><span>已下载</span><span>已开始</span></div>' : ''}${state.ui.calendar ? '<div class="inline-message">即将更新：演示源暂未安排新的章节。</div>' : ''}${renderUpdateList()}</div></div></div>`;
  }

  function clearBatchSelection() {
    state.ui.selecting = false; state.ui.selectedIds = []; state.ui.batchReview = null; state.ui.batchMenu = false;
  }
  function togglePendingSelection(id, range = false) {
    const ids = currentDevice().confirmations.map(item => item.id);
    if (!ids.includes(id)) return;
    const selected = new Set(state.ui.selectedIds || []);
    if (range && selected.size && !selected.has(id)) {
      const indexes = ids.map((value, index) => selected.has(value) ? index : -1).filter(index => index >= 0);
      const target = ids.indexOf(id);
      for (let i = Math.min(target, ...indexes); i <= Math.max(target, ...indexes); i += 1) selected.add(ids[i]);
    } else if (selected.has(id)) selected.delete(id);
    else selected.add(id);
    state.ui.selecting = true; state.ui.selectedIds = [...selected];
  }
  function startBatch(choice, all) {
    const selected = new Set(state.ui.selectedIds || []);
    const ids = currentDevice().confirmations.filter(item => all || selected.has(item.id)).map(item => item.id);
    state.ui.batchMenu = false;
    if (ids.length) state.ui.batchReview = { choice, ids, deviceId: state.selectedDevice };
  }
  function runBatch() {
    const review = state.ui.batchReview;
    if (!review || review.deviceId !== state.selectedDevice) return;
    clearBatchSelection();
    // Pace the existing demo actions so the progress, stop and resume controls can be reviewed.
    interactions.startBatch(review.ids.length, (start, end) => {
      const existing = new Set(model.getDevice(state, review.deviceId).confirmations.map(item => item.id));
      const chunk = review.ids.slice(start, end);
      chunk.filter(id => existing.has(id)).forEach(id => (review.choice === 'confirm' ? model.confirmCancellation : model.ignoreCancellation)(state, review.deviceId, id));
      return chunk.filter(id => !existing.has(id)).length;
    });
  }
  function renderBatchReview() {
    const review = state.ui.batchReview;
    if (!review) return '';
    const ids = new Set(review.ids);
    const items = currentDevice().confirmations.filter(item => ids.has(item.id));
    const authors = items.filter(item => item.kind === 'author-remove').length;
    const manga = items.length - authors;
    return `<div class="batch-review-overlay"><section class="batch-review" role="alertdialog" aria-modal="true" aria-labelledby="batch-review-title" aria-describedby="batch-review-detail" tabindex="-1"><h2 id="batch-review-title">${review.choice === 'confirm' ? '在此设备取消' : '保留在此设备'} ${items.length} 项？</h2><p id="batch-review-detail">${manga} 本漫画的收藏，${authors} 位作者的关注。</p><p>${review.choice === 'confirm' ? '仅移除本设备的收藏与关注，阅读历史和阅读模式保留。' : '本设备保留收藏与关注，不会反向更改来源设备。'}</p><p class="sheet-footnote">仅处理本次选择；新到达的条目不会包含在内。其他变动照常同步。</p><div class="batch-review-actions">${button('返回', 'data-action="batch-cancel" data-testid="batch-cancel"', 'm-button-text')}${button(review.choice === 'confirm' ? '在此设备取消' : '保留在此设备', 'data-action="batch-run" data-testid="batch-run"', 'm-button-tonal')}</div></section></div>`;
  }
  function renderSyncHeader(settings) {
    const subpage = interactions.screen();
    return `<header class="sheet-header">${subpage || settings ? iconButton('back', '返回上一页', `data-action="${subpage ? 'ix-back' : 'close-sync-settings'}" data-testid="sync-settings-back"`) : ''}<div class="sheet-title"><h2 id="sync-sheet-title">${subpage ? interactions.title() : settings ? '同步设置' : '同步'}</h2><p>${esc(currentDevice().name)} · 书架</p></div><div class="appbar-actions">${subpage || settings ? '' : iconButton('settings', '同步设置', 'data-action="sync-settings" data-testid="sync-settings"')}${iconButton('close', '关闭同步', 'data-action="close-sync" data-testid="sync-close"')}</div></header>`;
  }
  function renderPendingToolbar(pending) {
    if (state.ui.selecting) {
      const selectionHeader = `<div class="sheet-header selection-header">${iconButton('close', '退出选择', 'data-action="selection-cancel" data-testid="selection-cancel"')}<div class="sheet-title"><h2 id="selection-title" data-testid="selection-count">已选 ${(state.ui.selectedIds || []).length} 项</h2></div><div class="appbar-actions">${iconButton('selectAll', '全选', 'data-action="selection-all" data-testid="selection-all"')}${iconButton('flipToBack', '反选', 'data-action="selection-invert" data-testid="selection-invert"')}</div></div>`;
      return `<div class="pending-toolbar">${selectionHeader}${renderBatchBar()}</div>`;
    }
    return `<div class="pending-toolbar"><div class="pending-toolbar-heading"><strong>待手动处理的同步 · ${pending}</strong>${renderBatchTools()}</div></div>`;
  }

  function renderBatchTools() {
    if (!currentDevice().confirmations.length || state.ui.selecting || interactions.batchActive()) return '';
    return `<div class="batch-tools">${button('多选', 'data-action="batch-select" data-testid="batch-select"', 'm-button-text')}<div class="batch-menu">${iconButton('more', '全部处理', 'data-action="batch-menu" data-testid="batch-menu" aria-expanded="' + Boolean(state.ui.batchMenu) + '"')}${state.ui.batchMenu ? `<div class="batch-menu-items">${button('全部在此设备取消', 'data-action="batch-all-confirm" data-testid="batch-all-confirm"', 'm-button-text')}${button('全部保留在此设备', 'data-action="batch-all-ignore" data-testid="batch-all-ignore"', 'm-button-text')}</div>` : ''}</div></div>`;
  }
  function renderBatchBar() {
    if (!state.ui.selecting || state.ui.syncSettingsOpen) return '';
    const disabled = (state.ui.selectedIds || []).length ? '' : ' disabled';
    return `<div class="batch-action-bar">${button('保留所选', 'data-action="batch-ignore" data-testid="batch-ignore"' + disabled, 'm-button-text')}${button('在此设备取消所选', 'data-action="batch-confirm" data-testid="batch-confirm"' + disabled, 'm-button-tonal')}</div>`;
  }

  function renderSyncSheet() {
    const settings = state.ui.syncSettingsOpen;
    const content = interactions.screen() ? interactions.renderScreen() : settings ? renderSyncSettingsPage() : `<div class="sync-panel-scroll">${renderSyncPage()}</div>`;
    return `<div class="sheet-layer"><button class="sheet-scrim" tabindex="-1" aria-label="关闭同步" data-action="close-sync" data-testid="sync-scrim"></button><section class="sync-settings-sheet sync-panel-sheet" data-settings="${Boolean(settings || interactions.screen())}" role="dialog" aria-modal="true" aria-labelledby="sync-sheet-title" tabindex="-1"><div class="sheet-drag-handle" data-sheet-drag data-testid="sync-drag" aria-hidden="true"><span></span></div>${renderSyncHeader(settings)}${content}${isWindows() ? '' : '<div class="gesture-area" aria-hidden="true"></div>'}${renderBatchReview()}</section></div>`;
  }

  function closeSyncLayer() {
    if (state.ui.batchReview) state.ui.batchReview = null;
    else if (interactions.screen()) interactions.back();
    else if (state.ui.selecting) clearBatchSelection();
    else if (state.ui.syncSettingsOpen) state.ui.syncSettingsOpen = false;
    else state.ui.syncOpen = false;
  }

  function renderSyncPage() {
    if (!interactions.connected()) return interactions.unconfigured();
    const current = currentDevice();
    const pending = current.confirmations.length;
    const pendingIds = new Set(current.pendingOutgoing);
    const uploads = state.shared.operations.filter(op => op.sourceDevice === current.id && pendingIds.has(op.id));
    const membership = uploads.filter(op => op.kind.startsWith('favorite-') || op.kind.startsWith('author-')).length;
    const reading = uploads.filter(op => op.kind === 'read-position').length;
    const result = state.ui.syncResult;
    const busy = state.ui.busy && state.ui.busyDeviceId === current.id;
    return `<section class="sync-content" data-testid="sync-panel">
      ${interactions.status({ total: current.pendingOutgoing.length, membership, reading, pending, busy, online: state.online })}
      <div class="sync-action-row">${button('立即同步', 'data-action="sync-manual" data-testid="manual-sync"', 'm-button-primary')}</div>
      ${result ? `<div class="snackbar-inline ${result.ok ? 'success' : 'failure'}" data-testid="sync-result">${view.icon(result.ok ? 'check' : 'info')}<span>${esc(result.message)}</span></div>` : ''}
      ${interactions.summary()}${interactions.importStatus()}
      ${pending ? `<div class="sync-list pending-list">${renderPendingToolbar(pending)}${current.confirmations.map(renderConfirmation).join('')}</div>` : '<div class="sync-empty">当前没有待确认的取消操作</div>'}
      <div class="sync-record-link">${button('查看同步记录', 'data-action="ix-activity" data-testid="ix-activity"', 'm-button-text')}</div>
    </section>`;
  }

  function renderSetting(action, title, checked, detail) {
    return `<div class="native-list-row setting-row"><div class="row-copy"><strong>${title}</strong><small>${detail}</small></div><button class="native-switch ${checked ? 'is-on' : ''}" role="switch" aria-label="${title}" aria-checked="${checked}" data-action="${action}" data-testid="${action}"><span></span></button></div>`;
  }

  function renderSyncSettingsPage() {
    const current = currentDevice();
    return `<div class="sheet-settings-content sync-settings-page">${interactions.settings()}<p class="settings-section-label">自动同步 · 仅此设备</p>${renderSetting('startup-setting', '启动时自动同步', current.settings.startupSync, '应用启动后在后台同步，不影响当前操作')}${renderSetting('periodic-setting', '后台定期同步', current.settings.periodicSync, isWindows() ? '应用运行期间执行' : '系统允许时自动同步')}${interactions.settingsFooter()}</div>`;
  }

  function loadManyPending() {
    const ui = state.ui;
    if (ui.timerId) window.clearTimeout(ui.timerId);
    const deviceId = state.selectedDevice;
    model.resetDemo(state);
    state.ui = { ...ui, busy: false, timerId: null, busyDeviceId: null, route: 'library', detail: null, reader: false, syncOpen: true, syncSettingsOpen: false, syncScroll: 0 };
    state.selectedDevice = deviceId;
    clearBatchSelection(); state.ui.batchResult = null; state.ui.syncResult = null;
    const sourceId = deviceId === 'desktop-b' ? 'phone-a' : 'desktop-b';
    const target = currentDevice();
    target.confirmations.slice().forEach(item => model.ignoreCancellation(state, deviceId, item.id));
    const titles = ['远山来信', '雨后的图书馆', '沿海列车', '星光放映室', '风中旅人', '夏日回声', '森林里的钟表店', '月下航线'];
    for (let i = 0; i < 120; i += 1) {
      const id = 'demo-many-' + i;
      if (i < 80) {
        model.CATALOG[id] = { title: titles[i % titles.length] + ' · 第 ' + (Math.floor(i / titles.length) + 1) + ' 卷', author: '演示作者', source: 'Mihon 演示源' };
        model.localFavorite(state, sourceId, id);
      } else {
        model.AUTHORS[id] = { name: ['青木', '白川', '秋原', '北野'][i % 4] + '工作室 ' + (i - 79), detail: '演示作者' };
        model.localFollow(state, sourceId, id);
      }
    }
    model.syncDevice(state, sourceId, 'manual'); model.syncDevice(state, deviceId, 'manual');
    for (let i = 0; i < 120; i += 1) {
      const id = 'demo-many-' + i;
      if (i < 80) model.localUnfavorite(state, sourceId, id);
      else model.localUnfollow(state, sourceId, id);
    }
    model.syncDevice(state, sourceId, 'manual'); model.syncDevice(state, deviceId, 'manual');
    notice('已切换到 120 项待处理示例：80 项取消收藏、40 项取消关注。重置演示可恢复普通场景。');
  }

  function renderConfirmation(item) {
    const isAuthor = item.kind === 'author-remove';
    const selected = (state.ui.selectedIds || []).includes(item.id);
    const title = isAuthor ? `取消关注「${creator(item.objectId).name}」` : `取消收藏《${book(item.objectId).title}》`;
    return `<article class="native-confirmation ${selected ? 'is-selected' : ''}" data-pending-id="${esc(item.id)}" data-testid="confirmation-${esc(item.id)}"><button class="confirmation-icon selection-toggle" role="checkbox" aria-label="选择${esc(title)}" aria-checked="${selected}" data-select-id="${esc(item.id)}" data-testid="select-${esc(item.id)}">${view.icon(selected ? 'check' : isAuthor ? 'authors' : 'bookmark')}</button><div class="row-copy"><strong>${esc(title)}</strong><small>来自 ${esc(item.sourceName)} · 确认前保留本设备状态</small></div>${state.ui.selecting ? '' : `<div class="confirmation-actions">${button('在此设备取消', `data-confirm="${esc(item.id)}" data-testid="confirm-${esc(item.id)}"`, 'm-button-danger')}${button('保留在此设备', `data-ignore="${esc(item.id)}" data-testid="ignore-${esc(item.id)}"`, 'm-button-text')}</div>`}</article>`;
  }

  function renderLibrary() {
    const current = currentDevice();
    const ids = current.favorites.filter((id) => model.CATALOG[id]);
    const content = ids.length ? `<div class="library-grid ${isWindows() ? 'desktop-library-grid' : 'android-library-grid'}">${ids.map((id, index) => renderMangaCard(id, index, current)).join('')}</div>` : '<div class="library-empty"><strong>书架为空</strong><span>从更新或浏览页面加入漫画。</span></div>';
    return `<div class="route-view library-route">${topBar('书架', renderLibrarySyncButton() + iconButton('search', '搜索书架', 'data-action="search"') + iconButton('filter', '筛选书架', 'data-action="library-filter"'))}<div class="route-scroll library-scroll"><div class="library-header"><strong>全部</strong><span>${current.favorites.length} 本漫画</span>${state.ui.notice.includes('搜索') ? '<input class="inline-search" aria-label="搜索书架" placeholder="搜索标题" autofocus>' : ''}</div>${content}</div></div>`;
  }

  function renderMangaCard(id, index, current) {
    const item = book(id); const favorite = current.favorites.includes(id); const position = current.readPositions[id];
    return `<article class="manga-card" data-action="open-manga" data-object="${id}" tabindex="0" data-testid="manga-card-${id}"><div class="manga-cover cover-${(index % 3) + 1}"><span>${esc(item.title.slice(0, 2))}</span>${position ? '<span class="cover-progress"></span>' : ''}</div><div class="manga-card-body"><strong>${esc(item.title)}</strong><small>${esc(item.author)}</small><span>${position ? `续读第 ${position.page} 页` : '未开始阅读'}</span></div><button class="card-bookmark ${favorite ? 'is-on' : ''}" aria-label="${favorite ? '取消收藏' : '收藏'}${esc(item.title)}" data-action="card-favorite" data-object="${id}">${view.icon('bookmark')}</button></article>`;
  }

  function renderMangaDetail() {
    const id = state.ui.detail; const item = book(id); const current = currentDevice(); const favorite = current.favorites.includes(id); const position = current.readPositions[id];
    const description = { 'manga-star': '星海边缘的守夜人，为寻找失落航线踏上远行。', 'manga-dawn': '一间穿行于城市清晨的邮局，收集没有地址的信。', 'manga-night': '记录夜行者见闻的短篇故事集。' }[id];
    const authorId = id === 'manga-dawn' ? 'author-river' : 'author-lin';
    const action = (iconName, label, attrs, className) => button(`${view.icon(iconName)}${label}`, attrs, className);
    return `<div class="route-view detail-route">${topBar(item.title, iconButton(favorite ? 'bookmark' : 'plus', favorite ? '取消收藏' : '收藏', 'data-action="detail-favorite" data-testid="detail-favorite"'), { back: true })}<div class="route-scroll detail-scroll"><section class="detail-header"><div class="detail-cover cover-1">${esc(item.title.slice(0, 2))}</div><div class="detail-copy"><h2>${esc(item.title)}</h2><button class="author-chip" data-action="open-author" data-object="${authorId}">${view.icon('authors')}<span>${esc(item.author)}</span></button><p>${esc(item.source)} · ${favorite ? '已收藏' : '未收藏'}</p><span class="detail-state">${position ? `最近读到第 ${position.page} 页` : '尚未阅读'}</span><div class="detail-description"><strong>简介</strong><p>${description}</p><div class="detail-tags"><span>冒险</span><span>连载</span><span>离线样本</span></div></div></div></section><div class="detail-action-bar">${action(favorite ? 'bookmark' : 'plus', favorite ? '取消收藏' : '加入书架', 'data-action="detail-favorite" data-testid="detail-favorite-text"', 'm-button-tonal')}${action('category', '分类', 'data-action="detail-category"', 'm-button-text')}${action('refresh', '更新', 'data-action="detail-refresh"', 'm-button-text')}${action('authors', '追踪', 'data-action="detail-track"', 'm-button-text')}${action('external', '浏览器', 'data-action="detail-browser"', 'm-button-text')}</div><div class="detail-section"><div class="detail-section-title"><strong>章节</strong><span>12 章</span></div>${[12, 11, 10, 9].map((chapter) => `<div class="chapter-row"><span class="chapter-status ${chapter === 12 ? 'unread' : ''}"></span><div><strong>第 ${chapter} 话</strong><small>${chapter === 12 ? '今天更新' : '已读 · 7 天前'}</small></div>${iconButton('download', '下载第 ' + chapter + ' 话', 'data-action="download"')}</div>`).join('')}</div>${button(view.icon('play') + '继续阅读', 'data-action="read-detail" data-testid="continue-reading-fab"', 'continue-fab')}</div></div>`;
  }

  function renderReader() {
    const id = state.ui.detail || 'manga-star'; const item = book(id); const current = currentDevice(); const position = current.readPositions[id] || { chapterId: 'chapter-1', page: 1 };
    return `<div class="route-view reader-route">${topBar(item.title, iconButton('settings', '阅读设置', 'data-action="reader-settings"'), { back: true })}<div class="reader-content"><div class="reader-page"><span class="reader-page-label">${esc(item.title)} · ${position.page}</span><div class="reader-illustration">${view.icon('reader')}<strong>正在阅读</strong><small>第 ${position.page} 页</small></div></div><div class="reader-controls"><span>第 ${position.page} 页</span>${button('记录当前位置', 'data-action="record-reading" data-testid="record-reading"', 'm-button-primary')}${button('模拟下一页', 'data-action="reader-next" data-testid="reader-next"', 'm-button-tonal')}<label>模式<select data-action="reader-mode" aria-label="阅读模式"><option ${current.modes.manga[id] === '单页' ? 'selected' : ''}>单页</option><option ${current.modes.manga[id] === '双页' ? 'selected' : ''}>双页</option><option ${current.modes.manga[id] === '条漫' ? 'selected' : ''}>条漫</option></select></label></div></div></div>`;
  }

  function renderHistory() {
    const current = currentDevice();
    return `<div class="route-view history-route">${topBar('历史', iconButton('search', '搜索历史', 'data-action="search-history"'))}<div class="route-scroll simple-scroll"><div class="simple-list-heading">最近阅读</div>${(current.readHistory.length ? current.readHistory : ['manga-star', 'manga-night']).map((id) => `<button class="history-row" data-action="open-manga" data-object="${id}"><span class="row-leading">${view.icon('history')}</span><span class="row-copy"><strong>${esc(book(id).title)}</strong><small>${current.readPositions[id] ? `第 ${current.readPositions[id].page} 页 · 可继续阅读` : '阅读历史示例'}</small></span>${view.icon('chevron')}</button>`).join('')}</div></div>`;
  }

  function renderBrowse() {
    const active = state.ui.browseTab;
    const labels = { sources: '图源', authors: '作者', extensions: '插件', migration: '迁移' };
    const tabRoutes = isWindows() ? ['sources', 'extensions'] : ['sources', 'authors', 'extensions', 'migration'];
    const content = active === 'authors' ? renderAuthorList(true) : active === 'extensions' ? renderExtensionList() : active === 'migration' ? renderMigrationList() : renderSourceList();
    return `<div class="route-view browse-route">${topBar('浏览', iconButton('search', '搜索浏览内容', 'data-action="search-browse"'))}<div class="route-scroll browse-scroll"><div class="browse-tabs" role="tablist">${tabRoutes.map((route, i) => `<button class="browse-tab ${active === route ? 'is-active' : ''}" data-browse-tab="${route}" role="tab" aria-selected="${active === route}">${labels[route]}</button>`).join('')}</div>${content}</div></div>`;
  }

  function renderSourceList() {
    return `<div class="source-list"><div class="simple-list-heading">已安装图源</div>${['Mihon 演示源', '本地演示源'].map((name, i) => `<div class="source-row"><span class="source-logo">${i ? '本' : 'M'}</span><span class="row-copy"><strong>${name}</strong><small>${i ? '可离线查看样本' : '最近检查：今天'}</small></span><span class="source-state">${i ? '离线' : '可用'}</span></div>`).join('')}</div>`;
  }

  function renderAuthorList(fromBrowse) {
    const current = currentDevice(); const ids = ['author-river', 'author-lin'];
    return `<div class="author-list"><div class="simple-list-heading">${fromBrowse ? '作者' : '全部作者'}</div>${current.following.length ? `<div class="author-group-label">已关注</div>${current.following.map((id) => renderAuthorRow(id, current, true)).join('')}` : ''}<div class="author-group-label">其他作者</div>${ids.map((id) => renderAuthorRow(id, current, false)).join('')}</div>`;
  }

  function renderAuthorRow(id, current, followed) {
    const item = creator(id); return `<button class="author-row" data-action="open-author" data-object="${id}" data-testid="author-row-${id}"><span class="author-avatar">${esc(item.name.slice(0, 1))}</span><span class="row-copy"><strong>${esc(item.name)}</strong><small>${followed || current.following.includes(id) ? '已关注 · 可查看作者详情' : item.detail}</small></span>${view.icon('chevron')}</button>`;
  }

  function renderExtensionList() { return `<div class="simple-list"><div class="simple-list-heading">插件</div><div class="empty-inline">当前没有可安装插件。</div></div>`; }
  function renderMigrationList() { return `<div class="simple-list"><div class="simple-list-heading">迁移</div><div class="empty-inline">选择来源图源后开始迁移。</div>${button('查看迁移帮助', 'data-action="more-item" data-message="迁移帮助已打开。"', 'm-button-tonal')}</div>`; }

  function renderAuthors() {
    return `<div class="route-view authors-route">${topBar('作者', iconButton('search', '搜索作者', 'data-action="search-authors"'))}<div class="route-scroll simple-scroll">${renderAuthorList(false)}</div></div>`;
  }

  function renderAuthorDetail() {
    const id = state.ui.detail; const item = creator(id); const current = currentDevice(); const followed = current.following.includes(id);
    return `<div class="route-view author-detail-route">${topBar(item.name, iconButton('refresh', '检查作者新作品', 'data-action="author-refresh"'), { back: true })}<div class="route-scroll detail-scroll"><div class="author-detail-header"><span class="author-avatar large">${esc(item.name.slice(0, 1))}</span><div><h2>${esc(item.name)}</h2><p>${esc(item.detail)}</p><span>${followed ? '本设备已关注' : '本设备未关注'}</span></div>${button(followed ? '取消关注' : '关注', 'data-action="toggle-follow" data-testid="author-follow"', followed ? 'm-button-tonal' : 'm-button-primary')}</div><div class="detail-section"><div class="detail-section-title"><strong>作者作品</strong><span>共 2 部</span></div>${['manga-star', 'manga-dawn'].map((bookId) => `<button class="author-work-row" data-action="open-manga" data-object="${bookId}"><span class="cover mini-cover cover-1">${esc(book(bookId).title.slice(0, 2))}</span><span class="row-copy"><strong>${esc(book(bookId).title)}</strong><small>${esc(book(bookId).source)} · 查看漫画详情</small></span>${view.icon('chevron')}</button>`).join('')}</div></div></div>`;
  }

  function renderMore() {
    const desktopRows = [['visibilityOff', '无痕模式', '浏览时隐藏阅读记录'], ['download', '下载队列', '管理待下载章节'], ['swap', '迁移', '在图源之间迁移漫画'], ['chart', '统计', '查看书架与阅读数据'], ['settings', '设置', '主题与阅读偏好'], ['info', '关于', '版本与开源信息']];
    const androidRows = [['download', '已下载', '查看已下载章节'], ['visibilityOff', '无痕模式', '浏览时隐藏阅读记录'], ['download', '下载队列', '管理待下载章节'], ['category', '分类', '管理书架分类'], ['chart', '统计', '查看书架与阅读数据'], ['storage', '数据与存储', '管理本地数据'], ['settings', '设置', '主题与阅读偏好'], ['info', '关于', '版本与开源信息'], ['help', '帮助', '查看帮助内容'], ['donate', '捐赠', '支持 Mihon 项目']];
    const rows = isWindows() ? desktopRows : androidRows;
    const logo = isWindows() ? '' : `<div class="more-logo-header"><span class="more-logo-mark">M</span><div><strong>Mihon</strong><small>漫画阅读器</small></div></div>`;
    return `<div class="route-view more-route">${topBar('更多', '')}<div class="route-scroll simple-scroll">${logo}<div class="more-list">${rows.map(([iconName, title, detail]) => `<button class="more-row" data-action="more-item" data-message="${detail}"><span class="row-leading">${view.icon(iconName)}</span><span class="row-copy"><strong>${title}</strong><small>${detail}</small></span>${view.icon('chevron')}</button>`).join('')}</div></div></div>`;
  }

  function renderRoute() {
    if (state.ui.reader) return renderReader();
    if (state.ui.detail) return state.ui.detail.startsWith('author-') ? renderAuthorDetail() : renderMangaDetail();
    if (state.ui.route === 'library') return renderLibrary();
    if (state.ui.route === 'updates') return renderUpdatesPage();
    if (state.ui.route === 'history') return renderHistory();
    if (state.ui.route === 'browse') return renderBrowse();
    if (state.ui.route === 'authors') return renderAuthors();
    return renderMore();
  }

  function render(publish = true) {
    if (!state.ui.syncOpen) { state.ui.syncResult = null; state.ui.batchResult = null; interactions.close(); }
    const liveIds = new Set(currentDevice().confirmations.map(item => item.id));
    const previousSelected = state.ui.selectedIds || [];
    state.ui.selectedIds = previousSelected.filter(id => liveIds.has(id));
    if (previousSelected.length && !state.ui.selectedIds.length) state.ui.selecting = false;
    const previousSheet = root.querySelector('[role="dialog"]');
    const wasSettings = previousSheet?.dataset.settings === 'true';
    const focusId = document.activeElement?.dataset.testid;
    const selection = document.activeElement instanceof HTMLInputElement ? [document.activeElement.selectionStart, document.activeElement.selectionEnd] : null;
    const scroll = root.querySelector('.sync-panel-scroll');
    if (scroll) state.ui.syncScroll = scroll.scrollTop;
    root.innerHTML = `<div class="prototype-root platform-${state.ui.platform} theme-${state.ui.theme}">${renderPreviewTools()}${renderWindowShell(renderRoute())}<div class="prototype-notice ${state.ui.tone}">${view.icon(state.ui.tone === 'failure' ? 'info' : 'cloud')}<span data-testid="notice">${esc(state.ui.notice)}</span></div></div>`;
    if (state.ui.syncOpen) {
      root.querySelectorAll('.app-window > .app-body, .app-window > .native-navigation, .app-window > .gesture-area').forEach(el => { el.inert = true; });
      const review = root.querySelector('[role="alertdialog"]');
      if (review) root.querySelectorAll('.sync-panel-sheet > :not(.batch-review-overlay)').forEach(el => { el.inert = true; });
      const controls = [...(review || root.querySelector('[role="dialog"]')).querySelectorAll('[data-testid]')].filter(el => !el.closest('[inert]'));
      const batchFallback = review ? 'batch-cancel' : state.ui.selecting ? 'selection-cancel' : null;
      const fallback = batchFallback || (state.ui.syncSettingsOpen || interactions.screen() ? 'sync-settings-back' : wasSettings ? 'sync-settings' : 'sync-close');
      if (!preview || document.hasFocus()) {
        const focused = controls.find(el => el.dataset.testid === focusId) || root.querySelector('[data-testid="' + fallback + '"]');
        focused?.focus({ preventScroll: true });
        if (focused instanceof HTMLInputElement && selection && selection[0] !== null) focused.setSelectionRange(...selection);
      }
      const panel = root.querySelector('.sync-panel-scroll');
      if (panel) panel.scrollTop = state.ui.syncScroll || 0;
    } else if (previousSheet && (!preview || document.hasFocus())) root.querySelector('[data-testid="library-sync"]')?.focus({ preventScroll: true });
    if (preview && publish) preview.refreshOthers(window);
  }

  function scheduleSync(trigger) {
    if (state.ui.busy) { notice('同步正在进行，可以继续切换页面；请稍候查看本轮结果。'); render(); return; }
    const deviceId = state.selectedDevice; state.ui.busy = true; state.ui.busyDeviceId = deviceId; notice(`${model.TRIGGER_LABELS[trigger]}已开始；页面仍可继续操作。`); render();
    const timer = window.setTimeout(() => {
      if (state.ui.timerId !== timer) return;
      const result = model.triggerSync(state, deviceId, trigger);
      interactions.didSync(result.ok);
      if (result.ok) {
        const count = model.getDevice(state, deviceId).confirmations.length;
        result.message = '同步完成，本设备的变动已同步。' + (count ? `还有 ${count} 项取消操作待确认。` : '');
      }
      if (state.ui.syncOpen && state.selectedDevice === deviceId) state.ui.syncResult = result;
      state.ui.timerId = null; state.ui.busy = false; state.ui.busyDeviceId = null; notice(result.message, result.ok ? 'success' : 'failure'); render();
    }, 420);
    state.ui.timerId = timer;
  }

  function switchPlatform(platform) {
    clearBatchSelection(); state.ui.batchResult = null; state.ui.syncResult = null;
    state.ui.syncOpen = false; state.ui.syncSettingsOpen = false;
    if (state.ui.reader) model.setReadingActive(state, state.selectedDevice, false);
    state.ui.platform = platform; state.selectedDevice = platform === 'windows' ? 'desktop-b' : 'phone-a'; state.ui.detail = null; state.ui.reader = false; state.ui.route = 'library'; state.ui.browseTab = 'sources';
    notice(platform === 'windows' ? '已切换到 Windows Desktop 原生外壳。' : '已切换到 Android 手机预览；作者入口在浏览页签内。');
  }

  function leaveCurrentRoute() {
    if (!state.ui.detail && !state.ui.reader) return;
    if (state.ui.reader) model.setReadingActive(state, state.selectedDevice, false);
    state.ui.reader = false; state.ui.detail = null; render();
  }

  function handleAction(action, target) {
    const current = currentDevice();
    if (interactions.handle(action, target)) return;
    if (action === 'toggle-online') { model.setOnline(state, !state.online); notice(state.online ? '模拟网络已恢复，可以重试待上传操作。' : '模拟网络已断开；本地操作仍可继续。', state.online ? 'success' : 'failure'); }
    else if (action === 'reset') { const platform = state.ui.platform; const theme = state.ui.theme; if (state.ui.timerId) window.clearTimeout(state.ui.timerId); model.resetDemo(state); state.selectedDevice = platform === 'windows' ? 'desktop-b' : 'phone-a'; state.ui = { platform, theme, route: 'library', browseTab: 'sources', detail: null, reader: false, busy: false, timerId: null, busyDeviceId: null, notice: '演示已重置；已恢复初始待确认示例。', tone: 'success', filter: false, calendar: false, readUpdates: { 'desktop-b': ['manga-night'], 'phone-a': ['manga-night'] } }; }
    else if (action === 'back') { if (state.ui.reader) { state.ui.reader = false; model.setReadingActive(state, current.id, false); } else { state.ui.detail = null; } }
    else if (action === 'open-manga') { state.ui.detail = target.dataset.object; state.ui.reader = false; }
    else if (action === 'open-author') { state.ui.detail = target.dataset.object; state.ui.reader = false; }
    else if (action === 'card-favorite' || action === 'detail-favorite') { const id = target.dataset.object || state.ui.detail; current.favorites.includes(id) ? model.localUnfavorite(state, current.id, id) : model.localFavorite(state, current.id, id); notice(current.favorites.includes(id) ? `已收藏《${book(id).title}》，等待同步。` : `已取消收藏《${book(id).title}》，接收端会请求确认。`, 'success'); }
    else if (action === 'read-detail') { const id = state.ui.detail || 'manga-star'; markUpdateRead(current.id, id); state.ui.reader = true; model.setReadingActive(state, current.id, true); }
    else if (action === 'record-reading') { const id = state.ui.detail || 'manga-star'; markUpdateRead(current.id, id); const old = current.readPositions[id]; model.localRead(state, current.id, id, old ? old.chapterId : 'chapter-1', old ? old.page : 1); notice('当前位置已保存到本设备，下一次同步会上传。', 'success'); }
    else if (action === 'adopt-remote') { const suggestion = current.remoteSuggestions[0]; if (suggestion) model.adoptRemotePosition(state, current.id, suggestion.objectId); notice('已采用远端阅读位置，当前阅读画面已更新。', 'success'); }
    else if (action === 'reader-next') { const id = state.ui.detail || 'manga-star'; markUpdateRead(current.id, id); const old = current.readPositions[id] || { chapterId: 'chapter-1', page: 1 }; model.localRead(state, current.id, id, old.chapterId, old.page + 1); notice('已翻到下一页；阅读模式仍由本设备保留。', 'success'); }
    else if (action === 'toggle-follow') { const id = state.ui.detail; current.following.includes(id) ? model.localUnfollow(state, current.id, id) : model.localFollow(state, current.id, id); notice(current.following.includes(id) ? `已关注作者「${creator(id).name}」，等待同步。` : `已取消关注作者「${creator(id).name}」，接收端会请求确认。`, 'success'); }
    else if (action === 'sync-manual') scheduleSync('manual');
    else if (action === 'sync-startup') scheduleSync('startup');
    else if (action === 'sync-periodic') scheduleSync('periodic');
    else if (action === 'startup-setting') { current.settings.startupSync = !current.settings.startupSync; notice(`启动自动同步已${current.settings.startupSync ? '开启' : '关闭'}。`); }
    else if (action === 'periodic-setting') { current.settings.periodicSync = !current.settings.periodicSync; notice(`后台定期同步已${current.settings.periodicSync ? '开启' : '关闭'}。`); }
    else if (action === 'filter') { state.ui.filter = !state.ui.filter; notice(state.ui.filter ? '筛选已展开：可查看未读、已下载和已开始。' : '筛选已收起。'); }
    else if (action === 'calendar') { state.ui.calendar = !state.ui.calendar; notice(state.ui.calendar ? '已打开即将更新提示。' : '已收起即将更新提示。'); }
    else if (action === 'mark-all') { markAllUpdatesRead(current.id); notice('本设备更新列表已全部标为已读。', 'success'); }
    else if (action === 'refresh') notice('已刷新离线样本列表；真实源请求不在本原型范围。', 'success');
    else if (action === 'download') notice('下载入口可用；本地原型不会写入漫画文件。');
    else if (action === 'mark-read') { markUpdateRead(current.id, target.dataset.object); notice(`《${book(target.dataset.object).title}》已标为已读。`, 'success'); }
    else if (action === 'search' || action === 'search-history' || action === 'search-browse' || action === 'search-authors') notice('搜索入口已打开；示例数据可通过页面列表查看。');
    else if (action === 'library-filter') notice('书架筛选入口已打开；当前展示全部离线样本。');
    else if (action === 'author-refresh') notice('已刷新作者离线样本；真实发现请求不在本原型范围。');
    else if (action === 'detail-category') notice('分类入口已打开；当前原型保留页面路径。');
    else if (action === 'detail-refresh') notice('已刷新章节列表；真实源请求不在本原型范围。', 'success');
    else if (action === 'detail-track') notice('追踪入口已打开；本地原型保留作者追踪路径。');
    else if (action === 'detail-browser') notice('浏览器入口已保留；本地原型不会打开外部网页。');
    else if (action === 'reader-settings') notice('阅读设置保留为设备本地；不会随同步覆盖。');
    else if (action === 'batch-menu') state.ui.batchMenu = !state.ui.batchMenu;
    else if (action === 'batch-select') { state.ui.selecting = true; state.ui.selectedIds = []; }
    else if (action === 'selection-cancel') clearBatchSelection();
    else if (action === 'selection-all') state.ui.selectedIds = current.confirmations.map(item => item.id);
    else if (action === 'selection-invert') { const selected = new Set(state.ui.selectedIds || []); state.ui.selectedIds = current.confirmations.filter(item => !selected.has(item.id)).map(item => item.id); }
    else if (action === 'batch-confirm' || action === 'batch-ignore') startBatch(action === 'batch-confirm' ? 'confirm' : 'ignore', false);
    else if (action === 'batch-all-confirm' || action === 'batch-all-ignore') startBatch(action === 'batch-all-confirm' ? 'confirm' : 'ignore', true);
    else if (action === 'batch-cancel') state.ui.batchReview = null;
    else if (action === 'batch-run') runBatch();
    else if (action === 'many-pending') loadManyPending();
    else if (action === 'open-sync') { clearBatchSelection(); interactions.close(); state.ui.syncResult = null; state.ui.batchResult = null; state.ui.syncOpen = true; state.ui.syncSettingsOpen = false; state.ui.syncScroll = 0; }
    else if (action === 'close-sync') { clearBatchSelection(); state.ui.syncOpen = false; state.ui.syncSettingsOpen = false; }
    else if (action === 'sync-settings') state.ui.syncSettingsOpen = true;
    else if (action === 'close-sync-settings') state.ui.syncSettingsOpen = false;
    else if (action === 'more-item') notice(target.dataset.message || '该页面提供离线样本结构。');
    else if (action === 'dismiss-suggestion') { current.remoteSuggestions.shift(); notice('已收起远端位置提示；不会自动翻页。'); }
  }

  root.addEventListener('click', (event) => {
    const row = event.target.closest('[data-pending-id]');
    const selector = event.target.closest('[data-select-id]');
    if (row && Date.now() < suppressSelectionClickUntil) { event.preventDefault(); return; }
    if (row && (selector || state.ui.selecting)) {
      togglePendingSelection(row.dataset.pendingId, event.shiftKey); render(); return;
    }
    const target = event.target.closest('[data-action], [data-route], [data-updates-tab], [data-browse-tab], button[data-platform], button[data-theme], [data-confirm], [data-ignore], [data-conflict]'); if (!target) return;
    if (target.dataset.platform) switchPlatform(target.dataset.platform);
    else if (target.dataset.theme) { state.ui.theme = target.dataset.theme; notice(`已切换${state.ui.theme === 'light' ? '浅色' : '深色'}主题。`); }
    else if (target.dataset.route) navigateRoute(target.dataset.route);
    else if (target.dataset.browseTab) state.ui.browseTab = target.dataset.browseTab;
    else if (target.dataset.confirm) { model.confirmCancellation(state, state.selectedDevice, target.dataset.confirm); notice('已确认取消，仅改变当前接收设备。', 'success'); }
    else if (target.dataset.ignore) { model.ignoreCancellation(state, state.selectedDevice, target.dataset.ignore); notice('已忽略本次取消；不反向恢复来源设备。'); }
    else if (target.dataset.conflict) { model.resolveConflict(state, state.selectedDevice, target.dataset.conflict, target.dataset.choice); notice('冲突已处理，新决定已进入待上传队列。', 'success'); }
    else if (target.dataset.action) handleAction(target.dataset.action, target);
    render();
  });

  root.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && event.target.matches('[data-action="open-manga"], [data-action="open-author"]')) { event.target.click(); }
    if (event.key === 'Escape' && (state.ui.detail || state.ui.reader) && !event.defaultPrevented) { event.preventDefault(); leaveCurrentRoute(); }
  });

  window.addEventListener('keydown', (event) => {
    if (state.ui.syncOpen) {
      if (event.key === 'Escape') { event.preventDefault(); closeSyncLayer(); render(); }
      if (event.key === 'Tab') {
        const activeDialog = root.querySelector('[role="alertdialog"]') || root.querySelector('[role="dialog"]');
        const controls = [...activeDialog.querySelectorAll('button:not(:disabled), input:not(:disabled), select:not(:disabled), summary')].filter(el => !el.closest('[inert]') && el.getClientRects().length);
        const index = controls.indexOf(document.activeElement);
        event.preventDefault();
        controls[(index + (event.shiftKey ? -1 : 1) + controls.length) % controls.length].focus();
      }
      return;
    }
    if (event.key === 'Escape' && (state.ui.detail || state.ui.reader) && !event.defaultPrevented) { event.preventDefault(); leaveCurrentRoute(); }
  });

  root.addEventListener('pointerdown', (event) => {
    const row = event.target.closest('[data-pending-id]');
    if (row && !event.target.closest('button') && event.button === 0 && !state.ui.batchReview) {
      const id = row.dataset.pendingId;
      selectionPress = { x: event.clientX, y: event.clientY, timer: setTimeout(() => {
        togglePendingSelection(id, true); suppressSelectionClickUntil = Date.now() + 650;
        selectionPress = null; render();
      }, 450) };
    }
    const handle = event.target.closest('[data-sheet-drag]');
    if (!handle || event.button !== 0) return;
    sheetDrag = { pointerId: event.pointerId, y: event.clientY, distance: 0 };
    handle.setPointerCapture(event.pointerId);
  });
  root.addEventListener('pointermove', (event) => {
    if (selectionPress && Math.hypot(event.clientX - selectionPress.x, event.clientY - selectionPress.y) > 8) { clearTimeout(selectionPress.timer); selectionPress = null; }
    if (!sheetDrag || sheetDrag.pointerId !== event.pointerId) return;
    sheetDrag.distance = Math.max(0, event.clientY - sheetDrag.y);
    const sheet = root.querySelector('.sync-settings-sheet');
    if (sheet) sheet.style.transform = `translateY(${sheetDrag.distance}px)`;
  });
  root.addEventListener('pointerup', (event) => {
    if (selectionPress) { clearTimeout(selectionPress.timer); selectionPress = null; }
    if (!sheetDrag || sheetDrag.pointerId !== event.pointerId) return;
    if (sheetDrag.distance >= 56) { clearBatchSelection(); state.ui.syncOpen = false; state.ui.syncSettingsOpen = false; render(); }
    else root.querySelector('.sync-settings-sheet')?.style.removeProperty('transform');
    sheetDrag = null;
  });
  root.addEventListener('pointercancel', () => {
    if (selectionPress) { clearTimeout(selectionPress.timer); selectionPress = null; }
    sheetDrag = null;
    root.querySelector('.sync-settings-sheet')?.style.removeProperty('transform');
  });

  root.addEventListener('change', (event) => {
    const target = event.target;
    if (target.dataset.action === 'reader-mode') { model.setReadingMode(state, state.selectedDevice, state.ui.detail || 'manga-star', target.value); notice(`阅读模式已设为${target.value}，仅保存在本设备。`, 'success'); render(); }
  });

  root.addEventListener('input', event => interactions.input(event.target));
  window.__mihonSyncDemo = { state, model, view, render, scheduleSync, showInteractionScenario: interactions.showScenario };
  if (preview) {
    switchPlatform(window.frameElement.dataset.platform);
    state.ui.theme = preview.theme;
    document.body.classList.add('embedded-preview');
  }
  render();
})();
