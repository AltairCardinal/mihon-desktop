(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.AuthorIdentity = factory();
})(globalThis, function () {
  const titles = ['平行天堂', '极黑的布伦希尔德', '变异体少女'];
  function create() {
    return {
      revision: 0, redirects: {}, linked: false, distinct: false, rejected: false,
      sync: false, fail: false, unavailable: false,
      authors: ['a', 'b', 'c'].map((id, i) => ({ id, name: '冈本伦', follow: i === 1, interval: [24, 12, 24][i] })),
      versions: titles.map((title, i) => ({ id: 'v' + i, work: 'w' + i, title, source: '漫画柜', author: ['a', 'b', 'c'][i] })),
      pending: [{ id: 't1', title: '平行天堂 · 文字版', work: 'text1' }, { id: 't2', title: '极黑的布伦希尔德 · 文字版', work: 'text2' }],
    };
  }
  function preview(state, kind, options = {}) {
    const plan = { revision: state.revision, kind, ...options };
    if (kind === 'merge') {
      plan.selected = (options.selected || ['a', 'b', 'c']).filter(id => state.authors.some(a => a.id === id));
      plan.authorSnapshot = structuredClone(state.authors.filter(a => plan.selected.includes(a.id)));
      plan.versionSnapshot = structuredClone(state.versions.filter(v => plan.selected.includes(v.author)));
      plan.workCount = new Set(plan.versionSnapshot.map(v => v.work)).size;
      plan.versionCount = plan.versionSnapshot.length;
      plan.followedCount = plan.authorSnapshot.filter(a => a.follow).length;
    }
    return plan;
  }
  function resolve(state, id) {
    const seen = new Set();
    while (state.redirects[id] && !seen.has(id)) { seen.add(id); id = state.redirects[id]; }
    return id;
  }
  function apply(state, plan) {
    if (plan.revision !== state.revision) throw Error('预览已过期，请刷新预览后再确认。');
    if (state.sync && ['merge', 'link', 'text'].includes(plan.kind)) throw Error('作者关注同步已开启，当前版本暂不支持归集或新增作者关联。原记录与同步设置保持不变。');
    if (state.fail) throw Error('提交失败，未改变任何作者或作品。请重试。');
    const next = structuredClone(state);
    const target = resolve(next, plan.target || next.authors[0].id);
    if (plan.kind === 'merge') {
      const selected = next.authors.filter(a => (plan.selected || ['a', 'b', 'c']).includes(a.id));
      if (selected.length < 2 || !selected.some(a => a.id === target)) throw Error('至少选择两个档案，保留档案必须在本次范围内。');
      selected.forEach(a => { if (a.id !== target) next.redirects[a.id] = target; });
      next.versions.forEach(v => { if (selected.some(a => a.id === v.author)) v.author = target; });
      const retained = next.authors.find(a => a.id === target);
      retained.follow = plan.follow;
      retained.interval = plan.interval || 12;
      next.authors = next.authors.filter(a => a.id === target || !selected.includes(a));
    } else if (plan.kind === 'link' && !next.linked) {
      next.linked = true;
      next.versions.push({ id: 'md', work: 'w0', title: 'Parallel Paradise', source: 'MangaDex', author: target });
    } else if (plan.kind === 'text') {
      const chosen = next.pending.filter(p => (plan.selected || []).includes(p.id));
      chosen.forEach(p => next.versions.push({ ...p, source: '文字图源（演示）', author: target }));
      next.pending = next.pending.filter(p => !chosen.includes(p));
    } else if (plan.kind === 'reject') next.rejected = true;
    else if (plan.kind === 'distinct' && !next.distinct) {
      next.distinct = true;
      next.rejected = true;
      next.authors.push({ id: 'other', name: '冈本伦（同名演示人物）', follow: false, interval: 24 });
      next.versions.push({ id: 'other', work: 'other', title: '海边手记（虚构样本）', source: '文字图源（演示）', author: 'other' });
    } else if (plan.kind === 'follow') next.authors.find(a => a.id === target).follow = plan.follow;
    next.revision++;
    return next;
  }
  return { create, preview, apply, resolve };
});
