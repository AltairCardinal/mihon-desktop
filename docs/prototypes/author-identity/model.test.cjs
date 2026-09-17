const test = require('node:test');
const assert = require('node:assert/strict');
const M = require('./model.js');
test('全局频率默认每天，全部作者和未来作者共享且不改变关注', () => {
  let s = M.create(); assert.equal(s.frequency, 'daily');
  const follows = s.authors.map(a => a.follow);
  for (const frequency of ['weekly', 'monthly', 'daily']) { s = M.setFrequency(s, frequency); assert.equal(s.frequency, frequency); assert.deepEqual(s.authors.map(a => a.follow), follows); }
  s = M.setFrequency(s, 'monthly'); s = M.observe(s, { id: 'new', work: 'new', name: '新作者', title: '样本' });
  assert.equal(s.frequency, 'monthly'); assert.ok(s.authors.every(a => !Object.hasOwn(a, 'frequency')));
});
test('名称修改支持新名字和已有别名，旧名保留，空白和占用不能写入', () => {
  let s = M.create(); const before = structuredClone(s);
  assert.throws(() => M.rename(s, 'a', '  ')); assert.throws(() => M.rename(s, 'a', 'Okamoto Lynn')); assert.deepEqual(s, before);
  s = M.rename(s, 'a', '新主名'); assert.equal(s.authors.find(a => a.id === 'a').name, '新主名'); assert.deepEqual(s.authors.find(a => a.id === 'a').aliases, ['冈本伦']);
  s = M.rename(s, 'a', '冈本伦'); assert.deepEqual(s.authors.find(a => a.id === 'a').aliases, ['新主名']);
  s = M.observe(s, { id: 'future', work: 'future', name: '新主名', title: '样本' }); assert.equal(s.versions.at(-1).author, 'a');
});
test('同名历史与别名交集自动传递收敛，来源编号无关，空名字不建档', () => {
  let s = M.create(); assert.equal(s.authors.length, 3); assert.equal(M.resolve(s, 'b'), 'a');
  for (const sourceAuthorId of ['different', undefined]) s = M.observe(s, { id: 'new', work: 'new', title: '样本', source: '新插件', name: '冈本伦', sourceAuthorId });
  assert.equal(s.authors.length, 3); assert.equal(s.versions.filter(v => v.id === 'new').length, 1);
  s = M.observe(s, { id: 'blank', name: '  ' }); assert.equal(s.authors.length, 3);
  s.authors = [{ id: 'a', name: 'A', aliases: ['X'], follow: true }, { id: 'en', name: 'X', aliases: ['Y'], follow: false }, { id: 'tw', name: 'Y', aliases: [], follow: false }];
  const n = M.normalize(s); assert.equal(n.authors.length, 1); assert.equal(M.resolve(n, 'tw'), 'a'); assert.deepEqual(M.normalize(n), n);
});
test('直接添加内部版本校验和失败原子，成功保留名字及关注并集而不改全局频率', () => {
  let s = M.setFrequency(M.create(), 'monthly'); const plan = M.preview(s, { selected: ['en', 'a'], target: 'en' });
  s.fail = true; assert.throws(() => M.apply(s, plan)); assert.equal(s.authors.length, 3);
  s.fail = false; s.revision++; assert.throws(() => M.apply(s, plan));
  s = M.apply(s, M.preview(s, { selected: ['en', 'a'], target: 'en' }));
  assert.equal(s.frequency, 'monthly'); assert.equal(s.authors.find(a => a.id === 'en').follow, true); assert.deepEqual(s.authors.find(a => a.id === 'en').aliases, ['冈本伦']);
  assert.equal(s.authors.length, 2); assert.equal(M.resolve(s, 'b'), 'en');
  s = M.apply(s, M.preview(s, { selected: ['en', 'tw'], target: 'tw' })); assert.equal(M.resolve(s, 'b'), 'tw');
});
test('设置和名称保存失败保留原值，大小写名称严格独立', () => {
  const s = M.create(); s.fail = true; const before = structuredClone(s);
  assert.throws(() => M.setFrequency(s, 'weekly')); assert.throws(() => M.rename(s, 'a', '新名字')); assert.deepEqual(s, before);
  s.fail = false; const next = M.observe(s, { id: 'case', name: 'okamoto lynn', title: '样本' }); assert.equal(next.authors.length, 4);
});
