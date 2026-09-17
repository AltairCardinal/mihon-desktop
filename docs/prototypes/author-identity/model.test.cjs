const test = require('node:test');
const assert = require('node:assert/strict');
const M = require('./model.js');
test('历史相同名称初始化自动收敛，不同文本保持独立', () => {
  const s = M.create();
  assert.equal(s.authors.filter(a => a.name === '冈本伦').length, 1);
  assert.equal(s.authors.length, 3);
  assert.equal(M.resolve(s, 'b'), 'a');
  assert.equal(s.authors.find(a => a.id === 'a').follow, true);
});
test('相同名称跨来源及缺失ID复用且作品观察幂等，空白不建档', () => {
  let s = M.create();
  for (const sourceAuthorId of ['different', undefined]) s = M.observe(s, { id: 'new', work: 'new', title: '未来作品（虚构样本）', source: '任意来源', name: '冈本伦', sourceAuthorId });
  assert.equal(s.authors.length, 3); assert.equal(s.versions.filter(v => v.id === 'new').length, 1);
  assert.equal(s.versions.find(v => v.id === 'new').author, 'a');
  s = M.observe(s, { id: 'blank', name: '  ', title: '无署名作品' }); assert.equal(s.authors.length, 3);
  s = M.observe(s, { id: 'case', name: 'okamoto lynn', title: '大小写样本', source: '样本' }); assert.equal(s.authors.length, 4);
});
test('不同名称合并后别名全局复用，未选名称保持独立', () => {
  let s = M.create(); const p = M.preview(s, { selected: ['a', 'en'], target: 'a', initiator: 'a' });
  assert.equal(p.workCount, 3); assert.equal(p.versionCount, 4);
  s = M.apply(s, p); assert.equal(s.authors.length, 2); assert.equal(M.resolve(s, 'tw'), 'tw');
  s = M.observe(s, { id: 'future', work: 'future', title: '别名新作（虚构样本）', source: '另一个插件', name: 'Okamoto Lynn' });
  assert.equal(s.versions.find(v => v.id === 'future').author, 'a'); assert.equal(s.authors.length, 2);
});
test('取消无改变，失败与过期原子，刷新重新读取范围', () => {
  const s = M.create(), before = structuredClone(s); const p = M.preview(s, { selected: ['a', 'en'], target: 'a' });
  assert.deepEqual(s, before); s.fail = true; assert.throws(() => M.apply(s, p)); assert.equal(s.authors.length, 3);
  s.fail = false; s.authors[0].follow = false; s.revision++; assert.throws(() => M.apply(s, p));
  const fresh = M.preview(s, { selected: ['a', 'en'], target: 'a' }); assert.equal(fresh.followedCount, 0);
});
test('多次合并旧入口沿链到最终人物，历史收敛可重复执行', () => {
  let s = M.create(); s = M.apply(s, M.preview(s, { selected: ['a', 'en'], target: 'en' }));
  s = M.apply(s, M.preview(s, { selected: ['en', 'tw'], target: 'tw' }));
  assert.equal(M.resolve(s, 'b'), 'tw'); assert.equal(s.authors.length, 1);
  assert.deepEqual(M.normalize(s), s);
});
test('更换主名仍保留全部名字，来源ID完全不改变作者', () => {
  let s = M.create(); s = M.apply(s, M.preview(s, { selected: ['a', 'en', 'tw'], target: 'en', initiator: 'a' }));
  assert.equal(s.authors[0].name, 'Okamoto Lynn');
  assert.deepEqual(new Set(s.authors[0].aliases), new Set(['冈本伦', '岡本倫']));
  for (const [i, name] of ['冈本伦', '岡本倫', 'Okamoto Lynn'].entries()) s = M.observe(s, { id: 'proof' + i, work: 'proof' + i, title: '名称样本', source: '任意插件', sourceAuthorId: i, name });
  assert.equal(s.authors.length, 1); assert.ok(s.versions.every(v => v.author === 'en'));
});
test('历史已接受别名传递收敛且未关注周期不缩短已关注计划', () => {
  const s = M.create(); s.authors = [
    { id: 'a', name: 'A', aliases: ['X'], follow: true, frequency: 'monthly' },
    { id: 'en', name: 'X', aliases: ['Y'], follow: false, frequency: 'daily' },
    { id: 'tw', name: 'Y', aliases: [], follow: false, frequency: 'daily' },
  ];
  const next = M.normalize(s); assert.equal(next.authors.length, 1); assert.equal(next.authors[0].frequency, 'monthly');
  assert.equal(M.recommendedFrequency([{ follow: true, frequency: 'monthly' }, { follow: true, frequency: 'weekly' }]), 'weekly');
  assert.equal(M.recommendedFrequency([{ follow: true, frequency: 'weekly' }, { follow: true, frequency: 'daily' }]), 'daily');
  assert.equal(M.recommendedFrequency([{ follow: false, frequency: 'monthly' }]), 'daily');
  assert.deepEqual(new Set([next.authors[0].name, ...next.authors[0].aliases]), new Set(['A', 'X', 'Y']));
  assert.equal(M.resolve(next, 'tw'), 'a'); assert.deepEqual(M.normalize(next), next);
});
test('分次合并可选择此前别名作为主名，预览保留完整名称集合', () => {
  let s = M.create(); s = M.apply(s, M.preview(s, { selected: ['a', 'en'], target: 'a', initiator: 'a' }));
  const plan = M.preview(s, { selected: ['a', 'tw'], target: 'a', displayName: 'Okamoto Lynn', initiator: 'a' });
  assert.deepEqual(new Set(plan.names), new Set(['冈本伦', 'Okamoto Lynn', '岡本倫']));
  s = M.apply(s, plan); assert.equal(s.authors[0].name, 'Okamoto Lynn');
  assert.deepEqual(new Set(s.authors[0].aliases), new Set(['冈本伦', '岡本倫']));
});
test('设置只修改当前作者频率，失败原子，别名合并沿用发起者且关注取并集', () => {
  let s = M.create(); s = M.setFrequency(s, 'en', 'monthly');
  assert.equal(s.authors.find(a => a.id === 'en').follow, false);
  assert.equal(s.authors.find(a => a.id === 'a').frequency, 'daily');
  s = M.apply(s, M.preview(s, { selected: ['en', 'a'], initiator: 'en', target: 'a', displayName: '冈本伦' }));
  assert.equal(s.authors.find(a => a.id === 'a').frequency, 'monthly'); assert.equal(s.authors.find(a => a.id === 'a').follow, true);
  s = M.setFrequency(s, 'en', 'weekly'); assert.equal(s.authors.find(a => a.id === 'a').frequency, 'weekly');
  s.fail = true; assert.throws(() => M.setFrequency(s, 'a', 'daily')); assert.equal(s.authors.find(a => a.id === 'a').frequency, 'weekly');
});
