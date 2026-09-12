const test = require('node:test');
const assert = require('node:assert/strict');

const {
  createDemoState,
  getDevice,
  setOnline,
  localFavorite,
  localUnfavorite,
  localFollow,
  localUnfollow,
  localRead,
  setReadingActive,
  setReadingMode,
  adoptRemotePosition,
  syncDevice,
  triggerSync,
  confirmCancellation,
  ignoreCancellation,
  resolveConflict,
  resetDemo,
} = require('./sync-model.js');

function blank() {
  return createDemoState({ seedDemo: false });
}

function exchange(state, first = 'phone-a', second = 'desktop-b') {
  syncDevice(state, first);
  syncDevice(state, second);
}

test('缺失的收藏不会被推断为取消，操作重复交换只入库一次', () => {
  const state = blank();
  state.devices[secondDevice(state)].favorites = [];

  localFavorite(state, 'phone-a', 'manga-star');
  const before = syncDevice(state, 'desktop-b');
  assert.equal(before.ok, true);
  assert.equal(getDevice(state, 'desktop-b').favorites.includes('manga-star'), false);
  assert.equal(getDevice(state, 'desktop-b').confirmations.length, 0);

  exchange(state);
  exchange(state);
  assert.equal(getDevice(state, 'desktop-b').favorites.includes('manga-star'), true);
  assert.equal(state.shared.operations.filter((op) => op.objectId === 'manga-star').length, 1);
});

test('收藏取消在接收端确认，忽略保留本端且不会反向传播', () => {
  const state = blank();
  localFavorite(state, 'phone-a', 'manga-star');
  exchange(state);
  assert.equal(getDevice(state, 'desktop-b').favorites.includes('manga-star'), true);

  localUnfavorite(state, 'phone-a', 'manga-star');
  exchange(state);
  const confirmation = getDevice(state, 'desktop-b').confirmations[0];
  assert.equal(getDevice(state, 'desktop-b').favorites.includes('manga-star'), true);
  assert.equal(confirmation.kind, 'favorite-remove');

  ignoreCancellation(state, 'desktop-b', confirmation.id);
  exchange(state, 'desktop-b', 'phone-a');
  assert.equal(getDevice(state, 'desktop-b').favorites.includes('manga-star'), true);
  assert.equal(getDevice(state, 'desktop-b').confirmations.length, 0);
  assert.equal(state.shared.operations.filter((op) => op.kind === 'favorite-add').length, 1);
});

test('作者取消与漫画取消使用相同的接收端确认规则', () => {
  const state = blank();
  localFollow(state, 'phone-a', 'author-river');
  exchange(state);
  localUnfollow(state, 'phone-a', 'author-river');
  exchange(state);
  const confirmation = getDevice(state, 'desktop-b').confirmations.find((item) => item.kind === 'author-remove');
  assert.ok(confirmation);
  assert.equal(getDevice(state, 'desktop-b').following.includes('author-river'), true);
  confirmCancellation(state, 'desktop-b', confirmation.id);
  assert.equal(getDevice(state, 'desktop-b').following.includes('author-river'), false);
  assert.equal(getDevice(state, 'desktop-b').authorArchive.includes('author-river'), true);
});

test('阅读位置传播但阅读模式和正在阅读的页码由设备本地保留', () => {
  const state = blank();
  setReadingMode(state, 'phone-a', 'manga-star', '双页');
  setReadingMode(state, 'desktop-b', 'manga-star', '条漫');
  localRead(state, 'desktop-b', 'manga-star', 'chapter-1', 6);
  syncDevice(state, 'desktop-b');
  setReadingActive(state, 'desktop-b', true);
  localRead(state, 'phone-a', 'manga-star', 'chapter-3', 42);
  exchange(state);
  const desktop = getDevice(state, 'desktop-b');
  assert.deepEqual(desktop.readPositions['manga-star'], { chapterId: 'chapter-3', page: 42 });
  assert.deepEqual(desktop.currentReadingPositions['manga-star'], { chapterId: 'chapter-1', page: 6 });
  assert.deepEqual(desktop.pendingRemotePositions['manga-star'], { chapterId: 'chapter-3', page: 42 });
  assert.equal(desktop.modes.manga['manga-star'], '条漫');
  assert.equal(desktop.readingActive, true);
  assert.equal(desktop.remoteSuggestions.length, 1);
  adoptRemotePosition(state, 'desktop-b', 'manga-star');
  assert.deepEqual(desktop.currentReadingPositions['manga-star'], { chapterId: 'chapter-3', page: 42 });
  assert.equal(desktop.remoteSuggestions.length, 0);
});

test('手动、启动和定期触发共享同一同步逻辑，开关关闭时不执行', () => {
  const state = blank();
  setOnline(state, false);
  localFavorite(state, 'phone-a', 'manga-star');
  assert.equal(triggerSync(state, 'phone-a', 'manual').ok, false);
  assert.equal(getDevice(state, 'phone-a').pendingOutgoing.length, 1);
  setOnline(state, true);
  const desktop = getDevice(state, 'desktop-b');
  desktop.settings.startupSync = false;
  desktop.settings.periodicSync = false;
  assert.equal(triggerSync(state, 'desktop-b', 'startup').skipped, true);
  assert.equal(triggerSync(state, 'desktop-b', 'periodic').skipped, true);
  assert.equal(triggerSync(state, 'phone-a', 'manual').ok, true);
  assert.equal(triggerSync(state, 'desktop-b', 'manual').ok, true);
  assert.equal(getDevice(state, 'desktop-b').favorites.includes('manga-star'), true);
});

test('离线同步失败保留待发送操作，恢复后重试不重复应用', () => {
  const state = blank();
  localFavorite(state, 'phone-a', 'manga-star');
  setOnline(state, false);
  const failed = syncDevice(state, 'phone-a');
  assert.equal(failed.ok, false);
  assert.match(failed.message, /离线/);
  assert.equal(getDevice(state, 'phone-a').pendingOutgoing.length, 1);
  setOnline(state, true);
  syncDevice(state, 'phone-a');
  syncDevice(state, 'desktop-b');
  syncDevice(state, 'desktop-b');
  assert.equal(getDevice(state, 'desktop-b').favorites.filter((id) => id === 'manga-star').length, 1);
  assert.equal(getDevice(state, 'phone-a').pendingOutgoing.length, 0);
});

test('相反操作形成可解释冲突，选择结果成为新的本地决定', () => {
  const state = blank();
  localFavorite(state, 'phone-a', 'manga-star');
  localUnfavorite(state, 'phone-a', 'manga-star');
  localFavorite(state, 'desktop-b', 'manga-star');
  exchange(state);
  const desktop = getDevice(state, 'desktop-b');
  assert.equal(desktop.conflicts.length, 1);
  assert.match(desktop.conflicts[0].summary, /收藏/);
  const conflictId = desktop.conflicts[0].id;
  resolveConflict(state, 'desktop-b', conflictId, 'remote');
  assert.equal(getDevice(state, 'desktop-b').favorites.includes('manga-star'), false);
  assert.equal(getDevice(state, 'desktop-b').conflicts.length, 0);
  assert.equal(getDevice(state, 'desktop-b').pendingOutgoing.length, 1);
});

test('明确重新收藏会使旧的待确认取消失效', () => {
  const state = blank();
  localFavorite(state, 'phone-a', 'manga-star');
  exchange(state);
  localUnfavorite(state, 'phone-a', 'manga-star');
  exchange(state);
  assert.equal(getDevice(state, 'desktop-b').confirmations.length, 1);
  localFavorite(state, 'desktop-b', 'manga-star');
  assert.equal(getDevice(state, 'desktop-b').confirmations.length, 0);
  assert.equal(getDevice(state, 'desktop-b').favorites.includes('manga-star'), true);
});

test('远端重新收藏到达后，旧取消确认立即失效且不能取消新收藏', () => {
  const state = blank();
  localFavorite(state, 'phone-a', 'manga-star');
  exchange(state);
  localUnfavorite(state, 'phone-a', 'manga-star');
  exchange(state);
  const staleId = getDevice(state, 'desktop-b').confirmations[0].id;
  localFavorite(state, 'phone-a', 'manga-star');
  exchange(state);
  assert.equal(getDevice(state, 'desktop-b').confirmations.length, 0);
  assert.equal(getDevice(state, 'desktop-b').favorites.includes('manga-star'), true);
  assert.throws(() => confirmCancellation(state, 'desktop-b', staleId), /未知待确认操作/);
});

test('有因果先后的取消进入待确认，并发相反操作才进入冲突', () => {
  const causal = blank();
  localFavorite(causal, 'phone-a', 'manga-star');
  exchange(causal);
  localUnfavorite(causal, 'desktop-b', 'manga-star');
  exchange(causal, 'desktop-b', 'phone-a');
  assert.equal(getDevice(causal, 'phone-a').confirmations.length, 1);
  assert.equal(getDevice(causal, 'phone-a').conflicts.length, 0);

  const concurrent = blank();
  localFavorite(concurrent, 'phone-a', 'manga-star');
  localUnfavorite(concurrent, 'phone-a', 'manga-star');
  localFavorite(concurrent, 'desktop-b', 'manga-star');
  exchange(concurrent);
  assert.equal(getDevice(concurrent, 'desktop-b').conflicts.length, 1);
});

test('冲突选择保留本地即使状态相同，也会产生新的待发送决定', () => {
  const state = blank();
  localFavorite(state, 'phone-a', 'manga-star');
  localUnfavorite(state, 'phone-a', 'manga-star');
  localFavorite(state, 'desktop-b', 'manga-star');
  exchange(state);
  const conflict = getDevice(state, 'desktop-b').conflicts[0];
  resolveConflict(state, 'desktop-b', conflict.id, 'local');
  assert.equal(getDevice(state, 'desktop-b').favorites.includes('manga-star'), true);
  assert.equal(getDevice(state, 'desktop-b').pendingOutgoing.length, 1);
});

test('重置恢复有内容的可演示初始状态并保留模拟边界说明', () => {
  const state = blank();
  localFavorite(state, 'phone-a', 'manga-star');
  assert.equal(state.shared.operations.length, 1);
  resetDemo(state);
  assert.equal(state.shared.operations.length, 1);
  assert.equal(state.devices['phone-a'].favorites.includes('manga-star'), true);
  const seeded = createDemoState();
  assert.equal(seeded.demo.seeded, true);
  assert.match(seeded.demo.boundary, /模拟/);
  assert.ok(seeded.devices['desktop-b'].confirmations.length > 0);
});

function secondDevice(state) {
  return state.devices['desktop-b'] ? 'desktop-b' : Object.keys(state.devices)[1];
}
