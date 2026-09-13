(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.MihonSyncModel = factory();
})(typeof globalThis === 'object' ? globalThis : this, function () {
  const CATALOG = {
    'manga-star': { title: '星海骑士', source: 'Mihon 演示源', author: '林澈' },
    'manga-dawn': { title: '黎明邮局', source: 'Mihon 演示源', author: '白河' },
    'manga-night': { title: '夜航手札', source: '本地演示源', author: '周野' },
  };
  const AUTHORS = {
    'author-river': { name: '白河', detail: '作者关注与归档会分开保留' },
    'author-lin': { name: '林澈', detail: '作者识别信息仅用于演示' },
  };
  const TRIGGER_LABELS = { manual: '手动同步', startup: '启动自动同步', periodic: '后台定期同步' };

  function unique(values) {
    return Array.from(new Set(values));
  }

  function now(state) {
    state.clock += 1;
    return `模拟时间 ${String(state.clock).padStart(2, '0')}:00`;
  }

  function makeDevice(id, name, kind) {
    return {
      id, name, kind,
      favorites: [], following: [], authorArchive: [],
      readPositions: {}, readHistory: [],
      modes: { global: '单页', manga: {} }, readingActive: false, currentReadingPositions: {}, pendingRemotePositions: {}, remoteSuggestions: [],
      pendingOutgoing: [], confirmations: [], ignoredOperations: [], appliedOperations: [], conflicts: [],
      settings: { startupSync: true, periodicSync: true, periodMinutes: 60 },
      status: 'idle', lastResult: null, lastTrigger: null, log: [],
    };
  }

  function makeBlankState() {
    return {
      online: true, clock: 0, nextOperation: 0, selectedDevice: 'phone-a', activePanel: 'sync',
      shared: { operations: [], lastExchange: null },
      devices: { 'phone-a': makeDevice('phone-a', '手机 A', 'Android'), 'desktop-b': makeDevice('desktop-b', '电脑 B', 'Desktop') },
      demo: { seeded: false, boundary: '仅为本地模拟：没有真实远端、Git 服务、后台调度或凭据。' },
    };
  }

  function createDemoState(options) {
    const state = makeBlankState();
    if (options && options.seedDemo === false) return state;
    const phone = state.devices['phone-a'];
    const desktop = state.devices['desktop-b'];
    phone.favorites = ['manga-star', 'manga-dawn'];
    desktop.favorites = ['manga-star', 'manga-dawn', 'manga-night'];
    phone.following = ['author-river']; desktop.following = ['author-river'];
    phone.authorArchive = ['author-river']; desktop.authorArchive = ['author-river'];
    phone.readPositions['manga-star'] = { chapterId: 'chapter-2', page: 18 };
    desktop.readPositions['manga-night'] = { chapterId: 'chapter-1', page: 6 };
    phone.readHistory = ['manga-star']; desktop.readHistory = ['manga-night'];
    phone.modes.global = '条漫'; phone.modes.manga['manga-star'] = '双页';
    desktop.modes.global = '单页'; desktop.modes.manga['manga-star'] = '单页';
    state.demo.seeded = true;
    localUnfavorite(state, 'phone-a', 'manga-dawn');
    syncDevice(state, 'phone-a', 'manual'); syncDevice(state, 'desktop-b', 'manual');
    phone.log.unshift('示例已准备：手机 A 取消了「黎明邮局」，电脑 B 等待接收端确认。');
    return state;
  }

  function getDevice(state, deviceId) {
    if (!state.devices[deviceId]) throw new Error(`未知设备：${deviceId}`);
    return state.devices[deviceId];
  }

  function objectTitle(objectId, kind) {
    if (kind.startsWith('author')) return AUTHORS[objectId] ? AUTHORS[objectId].name : objectId;
    return CATALOG[objectId] ? CATALOG[objectId].title : objectId;
  }

  function addLog(device, message) { device.log.unshift(message); device.log = device.log.slice(0, 12); }

  function invalidateForDecision(device, objectId) {
    device.confirmations = device.confirmations.filter((item) => item.objectId !== objectId);
    device.conflicts = device.conflicts.filter((item) => item.objectId !== objectId);
  }

  function operationLabel(op) {
    const title = objectTitle(op.objectId, op.kind);
    const labels = {
      'favorite-add': `收藏《${title}》`, 'favorite-remove': `取消收藏《${title}》`,
      'author-add': `关注作者「${title}」`, 'author-remove': `取消关注作者「${title}」`,
      'read-position': `阅读位置更新：${title}`,
    };
    return labels[op.kind] || op.kind;
  }

  function enqueue(state, device, kind, objectId, payload) {
    state.nextOperation += 1;
    const op = { id: `${device.id}-op-${state.nextOperation}`, seq: state.nextOperation, sourceDevice: device.id, kind, objectId, payload: payload || null, createdAt: now(state), sent: false };
    state.shared.operations.push(op); device.pendingOutgoing.push(op.id);
    addLog(device, `已记录本地操作：${operationLabel(op)}；等待同步。`);
    return op;
  }

  function setOnline(state, online) {
    state.online = Boolean(online);
    Object.values(state.devices).forEach((device) => {
      device.status = online ? (device.confirmations.length || device.conflicts.length ? 'attention' : 'idle') : 'offline';
      addLog(device, online ? '模拟网络已恢复。' : '模拟网络已断开；本地操作仍可继续。');
    });
    return state.online;
  }

  function localFavorite(state, deviceId, objectId, force) {
    const device = getDevice(state, deviceId);
    if (!force && device.favorites.includes(objectId) && !device.confirmations.some((item) => item.objectId === objectId) && !device.conflicts.some((item) => item.objectId === objectId)) return null;
    invalidateForDecision(device, objectId); device.favorites.push(objectId); return enqueue(state, device, 'favorite-add', objectId);
  }

  function localUnfavorite(state, deviceId, objectId, force) {
    const device = getDevice(state, deviceId); if (!force && !device.favorites.includes(objectId)) return null;
    invalidateForDecision(device, objectId); device.favorites = device.favorites.filter((id) => id !== objectId); return enqueue(state, device, 'favorite-remove', objectId);
  }

  function localFollow(state, deviceId, objectId, force) {
    const device = getDevice(state, deviceId);
    if (!force && device.following.includes(objectId) && !device.confirmations.some((item) => item.objectId === objectId) && !device.conflicts.some((item) => item.objectId === objectId)) return null;
    invalidateForDecision(device, objectId); device.following.push(objectId); device.authorArchive = unique(device.authorArchive.concat(objectId));
    return enqueue(state, device, 'author-add', objectId);
  }

  function localUnfollow(state, deviceId, objectId, force) {
    const device = getDevice(state, deviceId); if (!force && !device.following.includes(objectId)) return null;
    invalidateForDecision(device, objectId); device.following = device.following.filter((id) => id !== objectId); return enqueue(state, device, 'author-remove', objectId);
  }

  function localRead(state, deviceId, objectId, chapterId, page) {
    const device = getDevice(state, deviceId); const next = { chapterId, page: Number(page) }; const previous = device.readPositions[objectId];
    if (previous && previous.chapterId === next.chapterId && previous.page === next.page) return null;
    device.readPositions[objectId] = next;
    if (device.readingActive) device.currentReadingPositions[objectId] = { chapterId: next.chapterId, page: next.page };
    device.readHistory = unique([objectId].concat(device.readHistory));
    return enqueue(state, device, 'read-position', objectId, next);
  }

  function setReadingActive(state, deviceId, active) {
    const device = getDevice(state, deviceId);
    device.readingActive = Boolean(active);
    if (device.readingActive && !device.currentReadingPositions['manga-star'] && device.readPositions['manga-star']) {
      device.currentReadingPositions['manga-star'] = { ...device.readPositions['manga-star'] };
    }
  }
  function setReadingMode(state, deviceId, objectId, mode) { getDevice(state, deviceId).modes.manga[objectId] = mode; }

  function hasLocalOperation(state, device, objectId, kinds, afterSeq) {
    return state.shared.operations.find((op) => op.sourceDevice === device.id && op.objectId === objectId && kinds.includes(op.kind) && op.seq > afterSeq);
  }

  function addConflict(state, device, incoming, local) {
    const id = `conflict:${incoming.id}:${local.id}`;
    if (device.conflicts.some((item) => item.id === id)) return device.conflicts.find((item) => item.id === id);
    const item = {
      id, objectId: incoming.objectId, objectTitle: objectTitle(incoming.objectId, incoming.kind), incomingId: incoming.id,
      localId: local.id, remoteDevice: incoming.sourceDevice, summary: `「${objectTitle(incoming.objectId, incoming.kind)}」存在相反的收藏决定`,
      incomingKind: incoming.kind, localKind: local.kind, incomingPayload: incoming.payload, localPayload: local.payload, createdAt: incoming.createdAt,
    };
    device.conflicts.push(item); device.status = 'attention'; addLog(device, `发现冲突：${item.summary}；请选择保留本地或采用远端。`); return item;
  }

  function applyIncoming(state, device, op) {
    if (device.appliedOperations.includes(op.id) || device.ignoredOperations.includes(op.id)) return { skipped: true };
    if (op.sourceDevice === device.id) { device.appliedOperations.push(op.id); return { applied: false }; }
    if (op.kind === 'favorite-add' || op.kind === 'author-add') {
      const field = op.kind === 'favorite-add' ? 'favorites' : 'following'; const opposite = op.kind === 'favorite-add' ? ['favorite-remove'] : ['author-remove'];
      const localOpposite = hasLocalOperation(state, device, op.objectId, opposite, op.seq);
      if (localOpposite) addConflict(state, device, op, localOpposite);
      else device.confirmations = device.confirmations.filter((item) => item.objectId !== op.objectId);
      device[field] = unique(device[field].concat(op.objectId)); if (op.kind === 'author-add') device.authorArchive = unique(device.authorArchive.concat(op.objectId));
      device.appliedOperations.push(op.id); addLog(device, `已接收并应用：${operationLabel(op)}。`); return { applied: true };
    }
    if (op.kind === 'favorite-remove' || op.kind === 'author-remove') {
      const field = op.kind === 'favorite-remove' ? 'favorites' : 'following'; const opposite = op.kind === 'favorite-remove' ? ['favorite-add'] : ['author-add'];
      const localOpposite = hasLocalOperation(state, device, op.objectId, opposite, op.seq);
      if (localOpposite) { addConflict(state, device, op, localOpposite); device.appliedOperations.push(op.id); return { conflict: true }; }
      device.appliedOperations.push(op.id);
      if (!device[field].includes(op.objectId)) { addLog(device, `已接收：${operationLabel(op)}；本设备原本没有该对象，无需变更。`); return { applied: false }; }
      device.confirmations.push({ id: op.id, objectId: op.objectId, kind: op.kind, sourceDevice: op.sourceDevice, sourceName: getDevice(state, op.sourceDevice).name, operation: op, status: 'pending', message: op.kind === 'favorite-remove' ? '确认后仅移除本设备的收藏，阅读历史与模式保留。' : '确认后仅取消本设备的关注，作者归档保留.' });
      device.status = 'attention'; addLog(device, `收到待确认操作：${operationLabel(op)}；确认前保留本设备状态。`); return { confirmation: true };
    }
    if (op.kind === 'read-position') {
      const previous = device.readPositions[op.objectId];
      if (device.readingActive) {
        if (!device.currentReadingPositions[op.objectId] && previous) device.currentReadingPositions[op.objectId] = { ...previous };
        device.pendingRemotePositions[op.objectId] = { ...op.payload };
        const suggestion = { id: op.id, objectId: op.objectId, position: op.payload, sourceDevice: op.sourceDevice };
        if (!device.remoteSuggestions.some((item) => item.id === op.id)) device.remoteSuggestions.push(suggestion);
      }
      device.readPositions[op.objectId] = { chapterId: op.payload.chapterId, page: op.payload.page }; device.readHistory = unique([op.objectId].concat(device.readHistory));
      device.appliedOperations.push(op.id); addLog(device, previous ? `阅读位置已更新：${objectTitle(op.objectId, op.kind)}；阅读中仅提示采用，不强制翻页。` : `已接收阅读记录：${objectTitle(op.objectId, op.kind)}。`); return { applied: true };
    }
    device.appliedOperations.push(op.id); return { applied: false };
  }

  function syncDevice(state, deviceId, trigger) {
    const device = getDevice(state, deviceId); const triggerName = trigger || 'manual';
    if (!state.online) {
      device.status = 'offline'; device.lastTrigger = triggerName;
      device.lastResult = { ok: false, trigger: triggerName, sent: 0, received: 0, applied: 0, message: '当前处于离线模拟，待上传操作已保留。' };
      addLog(device, `${TRIGGER_LABELS[triggerName]}失败：离线；可继续操作，联网后重试。`); return device.lastResult;
    }
    const sendCount = device.pendingOutgoing.length;
    const pendingIds = new Set(device.pendingOutgoing);
    state.shared.operations.forEach((op) => { if (pendingIds.has(op.id)) op.sent = true; });
    device.pendingOutgoing = [];
    const received = state.shared.operations.filter((op) => op.sent && !device.appliedOperations.includes(op.id) && !device.ignoredOperations.includes(op.id));
    let applied = 0; let confirmations = 0; let conflicts = 0;
    received.forEach((op) => { const result = applyIncoming(state, device, op); if (result.applied) applied += 1; if (result.confirmation) confirmations += 1; if (result.conflict) conflicts += 1; });
    state.shared.lastExchange = now(state); device.status = device.confirmations.length || device.conflicts.length ? 'attention' : 'idle'; device.lastTrigger = triggerName;
    device.lastResult = { ok: true, trigger: triggerName, sent: sendCount, received: received.length, applied, confirmations, conflicts, message: `${TRIGGER_LABELS[triggerName]}完成：上传 ${sendCount} 条，接收 ${received.length} 条。` };
    addLog(device, device.lastResult.message); return device.lastResult;
  }

  function triggerSync(state, deviceId, trigger) {
    const device = getDevice(state, deviceId);
    if (trigger === 'startup' && !device.settings.startupSync) return { ok: true, skipped: true, trigger, message: '启动自动同步已关闭，本次不执行。' };
    if (trigger === 'periodic' && !device.settings.periodicSync) return { ok: true, skipped: true, trigger, message: '后台定期同步已关闭，本次不执行。' };
    return syncDevice(state, deviceId, trigger);
  }

  function findConfirmation(device, id) {
    const index = device.confirmations.findIndex((item) => item.id === id); if (index < 0) throw new Error(`未知待确认操作：${id}`); return { item: device.confirmations[index], index };
  }

  function confirmCancellation(state, deviceId, id) {
    const device = getDevice(state, deviceId); const found = findConfirmation(device, id); const field = found.item.kind === 'favorite-remove' ? 'favorites' : 'following';
    device[field] = device[field].filter((objectId) => objectId !== found.item.objectId); device.confirmations.splice(found.index, 1); device.status = device.confirmations.length || device.conflicts.length ? 'attention' : 'idle';
    addLog(device, `已确认：${operationLabel(found.item.operation)}；仅影响本设备。`); return found.item;
  }

  function ignoreCancellation(state, deviceId, id) {
    const device = getDevice(state, deviceId); const found = findConfirmation(device, id); device.ignoredOperations.push(id); device.confirmations.splice(found.index, 1); device.status = device.confirmations.length || device.conflicts.length ? 'attention' : 'idle';
    addLog(device, `已忽略：${operationLabel(found.item.operation)}；本设备保留现状，不反向传播。`); return found.item;
  }

  function resolveConflict(state, deviceId, id, choice) {
    const device = getDevice(state, deviceId); const index = device.conflicts.findIndex((item) => item.id === id); if (index < 0) throw new Error(`未知冲突：${id}`);
    const conflict = device.conflicts[index]; const op = state.shared.operations.find((item) => item.id === (choice === 'remote' ? conflict.incomingId : conflict.localId)); device.conflicts.splice(index, 1);
    if (op.kind === 'favorite-add') localFavorite(state, deviceId, op.objectId, true); else if (op.kind === 'favorite-remove') localUnfavorite(state, deviceId, op.objectId, true); else if (op.kind === 'author-add') localFollow(state, deviceId, op.objectId, true); else if (op.kind === 'author-remove') localUnfollow(state, deviceId, op.objectId, true); else if (op.kind === 'read-position') localRead(state, deviceId, op.objectId, op.payload.chapterId, op.payload.page);
    device.status = device.confirmations.length || device.conflicts.length ? 'attention' : 'idle'; addLog(device, `已处理冲突：${choice === 'remote' ? '采用远端决定' : '保留本地决定'}，新决定等待同步。`); return conflict;
  }

  function adoptRemotePosition(state, deviceId, objectId) {
    const device = getDevice(state, deviceId);
    const next = device.pendingRemotePositions[objectId] || (device.remoteSuggestions.find((item) => item.objectId === objectId) || {}).position;
    if (!next) return null;
    device.currentReadingPositions[objectId] = { ...next };
    device.readPositions[objectId] = { ...next };
    delete device.pendingRemotePositions[objectId];
    device.remoteSuggestions = device.remoteSuggestions.filter((item) => item.objectId !== objectId);
    addLog(device, `已采用远端阅读位置：${objectTitle(objectId, 'read-position')}；当前阅读画面已更新。`);
    return next;
  }

  function resetDemo(state) { const fresh = createDemoState(); Object.keys(state).forEach((key) => delete state[key]); Object.assign(state, fresh); return state; }

  return { AUTHORS, CATALOG, TRIGGER_LABELS, createDemoState, getDevice, setOnline, localFavorite, localUnfavorite, localFollow, localUnfollow, localRead, setReadingActive, setReadingMode, adoptRemotePosition, syncDevice, triggerSync, confirmCancellation, ignoreCancellation, resolveConflict, resetDemo, operationLabel };
});
