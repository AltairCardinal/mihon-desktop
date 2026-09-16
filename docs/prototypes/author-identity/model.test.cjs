const test = require('node:test');
const assert = require('node:assert/strict');
const M = require('./model.js');
test('归集三个档案保留作品并集与旧入口', () => {
  const state = M.create();
  const next = M.apply(state, M.preview(state, 'merge', { target: 'b', follow: true }));
  assert.equal(next.authors.length, 1);
  assert.equal(next.authors[0].id, 'b');
  assert.equal(next.versions.length, 3);
  assert.equal(M.resolve(next, 'a'), 'b');
});
test('失败、过期与同步限制不产生部分归集，取消预览无副作用', () => {
  for (const mode of ['fail', 'sync', 'stale', 'cancel']) {
    const state = M.create();
    const plan = M.preview(state, 'merge', { target: 'a', follow: true });
    if (mode === 'stale') state.revision++;
    else if (mode !== 'cancel') state[mode] = true;
    const original = structuredClone(state);
    if (mode !== 'cancel') assert.throws(() => M.apply(state, plan));
    assert.deepEqual(state, original);
  }
});
test('跨源同一作品版本与一次关注共用，文字选择不泛化，同名独立', () => {
  let state = M.create();
  state = M.apply(state, M.preview(state, 'merge', { target: 'a', follow: true }));
  state = M.apply(state, M.preview(state, 'link', { target: 'a' }));
  assert.equal(state.versions.length, 4);
  assert.equal(new Set(state.versions.map(v => v.work)).size, 3);
  state = M.apply(state, M.preview(state, 'text', { target: 'a', selected: ['t1'] }));
  assert.deepEqual(state.pending.map(p => p.id), ['t2']);
  state = M.apply(state, M.preview(state, 'reject', { target: 'a' }));
  assert.equal(state.authors.length, 1);
  state = M.apply(state, M.preview(state, 'distinct', { target: 'a' }));
  assert.equal(state.authors.length, 2);
  assert.equal(state.versions.find(v => v.id === 'other').author, 'other');
  assert.equal(state.authors.find(a => a.id === 'a').follow, true);
});
test('部分归集只改变勾选档案，保留档案必须在范围内', () => {
  const state = M.create();
  const next = M.apply(state, M.preview(state, 'merge', { target: 'b', selected: ['a', 'b'], follow: false }));
  assert.equal(next.authors.length, 2);
  assert.equal(next.versions.find(v => v.id === 'v2').author, 'c');
  assert.throws(() => M.apply(state, M.preview(state, 'merge', { target: 'c', selected: ['a', 'b'] })));
});
test('分次归集后所有旧入口沿重定向找到最终作者', () => {
  let state = M.create();
  state = M.apply(state, M.preview(state, 'merge', { target: 'a', selected: ['a', 'b'] }));
  state = M.apply(state, M.preview(state, 'merge', { target: 'c', selected: ['a', 'c'] }));
  assert.equal(M.resolve(state, 'b'), 'c');
});
test('关注同步开启时同样禁止跨源和文字身份绑定', () => {
  const state = M.create(); state.sync = true;
  const before = structuredClone(state);
  for (const kind of ['link', 'text']) assert.throws(() => M.apply(state, M.preview(state, kind, { target: 'a', selected: ['t1'] })));
  assert.deepEqual(state, before);
});
test('归集预览区分作品与版本，刷新重新读取实际变化而不纳入未选档案', () => {
  let state = M.create();
  state = M.apply(state, M.preview(state, 'link', { target: 'a' }));
  const plan = M.preview(state, 'merge', { target: 'a', selected: ['a', 'b'], follow: true });
  assert.equal(plan.workCount, 2); assert.equal(plan.versionCount, 3);
  state.authors.find(a => a.id === 'b').follow = false; state.revision++;
  assert.throws(() => M.apply(state, plan));
  const fresh = M.preview(state, 'merge', { target: 'a', selected: ['a', 'b'] });
  assert.equal(fresh.followedCount, 0);
  assert.equal(fresh.authorSnapshot.length, 2);
  assert.equal(plan.followedCount, 1);
});
