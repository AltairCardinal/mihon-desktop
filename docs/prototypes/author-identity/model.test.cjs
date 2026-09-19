const test = require('node:test');
const assert = require('node:assert/strict');
const M = require('./model.js');
test('默认同作品多源可见，新作按作品去重、查看后不重复提醒且仅提醒关注作者', () => {
  let s = M.create();
  assert.equal(s.versions.filter(v => v.author === 'a' && v.work === 'w0').length, 2);
  assert.deepEqual(M.unseenWorks(s, 'a'), []);
  const observation = { id: 'discovery', work: 'discovery', name: '冈本伦', title: '新作' };
  s = M.observe(s, observation);
  s = M.observe(s, { ...observation, id: 'discovery-other' });
  assert.deepEqual(M.unseenWorks(s, 'a'), ['discovery']);
  s = M.markSeen(s, 'discovery'); s = M.observe(s, observation);
  assert.deepEqual(M.unseenWorks(s, 'a'), []);
  s = M.observe(s, { ...observation, id: 'old-other', work: 'w0' });
  assert.deepEqual(M.unseenWorks(s, 'a'), []);
  s = M.follow(s, 'a', false);
  s = M.observe(s, { ...observation, id: 'unfollowed', work: 'unfollowed' });
  s = M.follow(s, 'a', true);
  assert.deepEqual(M.unseenWorks(s, 'a'), []);
});
test('日期优先可信上架字段，未知或异常日期使用不可后移的首次发现日期', () => {
  let s = M.create();
  assert.deepEqual(M.workDate(s, 'w0'), { date: '2017-03-18', label: '上架', source: '漫画柜' });
  assert.equal(M.workDate(s, 'w2').label, '首次发现');
  const first = M.workDate(s, 'w2').date;
  s = M.refreshDates(s); assert.equal(M.workDate(s, 'w2').date, first);
  s = M.observe(s, { id: 'fresh', work: 'fresh', name: '冈本伦', source: '无日期样本', listedAt: s.today, firstSeenAt: '2099-01-01' });
  assert.deepEqual(M.workDate(s, 'fresh'), { date: s.today, label: '首次发现', source: null });
  const original = M.workDate(s, 'fresh').date;
  s.today = '2026-09-25'; s = M.observe(s, { id: 'fresh', work: 'fresh', name: '冈本伦', source: '无日期样本', listedAt: s.today });
  assert.equal(M.workDate(s, 'fresh').date, original);
});
test('日期可信度按字段独立，后续上架日期不推迟、较早证据可更正且收藏不被刷新覆盖', () => {
  let s = M.create(); const v = s.versions.find(v => v.id === 'v0');
  assert.ok(v.favorite); assert.equal(M.latestDate(s, v).date, '2026-09-17');
  s = M.observe(s, { ...v, name: '冈本伦', listedAt: '2018-01-01', favorite: false });
  assert.equal(M.workDate(s, 'w0').date, '2017-03-18');
  assert.equal(s.versions.find(v => v.id === 'v0').favorite, true);
  s = M.observe(s, { ...v, name: '冈本伦', listedAt: '2017-02-01' });
  assert.equal(M.workDate(s, 'w0').date, '2017-02-01');
  assert.equal(M.latestDate(s, s.versions.find(v => v.id === 'v2')).label, '日期待核实');
  const first = M.workDate(s, 'w0'); s = M.refreshDates(s);
  assert.deepEqual(M.workDate(s, 'w0'), first);
  assert.equal(M.latestDate(s, s.versions.find(v => v.id === 'v0')).date, null);
});
test('多作品多章节跨日稳定证据才可入日期可信列表，全章随刷新日漂移应降级', () => {
  const samples = [1, 2, 3].map(id => ({ work: 's' + id, beforeAt: '2026-09-18', afterAt: '2026-09-20', before: ['2025-01-01', '2025-02-01', '2025-03-01'], after: ['2025-01-01', '2025-02-01', '2025-03-01'] }));
  assert.equal(M.assessChapterDates(samples), 'trusted');
  assert.equal(M.assessChapterDates(samples.slice(0, 1)), 'unknown');
  assert.equal(M.assessChapterDates(samples.map(v => ({ ...v, after: ['2026-09-20', '2026-09-20', '2026-09-20'] }))), 'suspect');
  assert.equal(M.assessChapterDates(samples.map(v => ({ ...v, afterAt: v.beforeAt }))), 'unknown');
});
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

test('严格简繁同名自动形成展示组，保留原始标题并支持分开显示后撤销', () => {
  let s = M.create();
  s.versions.push(
    { id: 'script-hans', work: 'script-hans', title: '诡谲屋', source: '简体源', author: 'a', sourceKey: 'text', favorite: false, chapterCount: 10, listedAt: null, latestChapterAt: null, firstSeenAt: s.today },
    { id: 'script-hant', work: 'script-hant', title: '詭譎屋', source: '繁體源', author: 'a', sourceKey: 'text', favorite: false, chapterCount: 11, listedAt: null, latestChapterAt: null, firstSeenAt: s.today },
  );
  assert.equal(M.normalizeWorkTitle('詭譎屋'), M.normalizeWorkTitle('诡谲屋'));
  assert.equal(M.normalizeWorkTitle('詭譎屋:外傳'), '诡谲屋:外传');
  assert.notEqual(M.normalizeWorkTitle('詭譎屋:外傳'), M.normalizeWorkTitle('诡谲屋 外传'));
  let groups = M.presentationGroups(s, 'a');
  let scriptGroup = groups.find(group => group.versions.some(v => v.id === 'script-hans'));
  assert.ok(scriptGroup);
  assert.equal(scriptGroup.versions.length, 2);
  assert.deepEqual(scriptGroup.versions.map(v => v.title), ['诡谲屋', '詭譎屋']);
  assert.equal(scriptGroup.autoMerged, true);
  s = M.splitPresentation(s, 'a', 'script-hant');
  groups = M.presentationGroups(s, 'a');
  assert.equal(groups.filter(group => group.versions.some(v => ['script-hans', 'script-hant'].includes(v.id))).length, 2);
  assert.deepEqual(s.presentationExclusions, [{ author: 'a', work: 'script-hant' }]);
  s = M.undoPresentationSplit(s, 'a', 'script-hant');
  scriptGroup = M.presentationGroups(s, 'a').find(group => group.versions.some(v => v.id === 'script-hans'));
  assert.equal(scriptGroup.versions.length, 2);
});
