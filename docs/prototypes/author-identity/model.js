(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.AuthorIdentity = factory();
})(globalThis, function () {
  function resolve(state, id) {
    const seen = new Set();
    while (state.redirects[id] && !seen.has(id)) { seen.add(id); id = state.redirects[id]; }
    return id;
  }
  function names(author) { return [author.name, ...author.aliases]; }
  function mergeInto(state, selected, target, follow, interval, displayName) {
    const retained = state.authors.find(a => a.id === target);
    const members = state.authors.filter(a => selected.includes(a.id));
    const allNames = [...new Set(members.flatMap(names))];
    retained.name = displayName || retained.name;
    retained.aliases = allNames.filter(n => n !== retained.name);
    retained.follow = follow; retained.interval = interval;
    members.forEach(a => { if (a.id !== target) state.redirects[a.id] = target; });
    state.versions.forEach(v => { if (selected.includes(v.author)) v.author = target; });
    state.authors = state.authors.filter(a => !selected.includes(a.id) || a.id === target);
  }
  function recommendedInterval(authors) {
    const enabled = authors.filter(a => a.follow);
    return enabled.length ? Math.min(...enabled.map(a => a.interval)) : 24;
  }
  function normalize(input) {
    const state = structuredClone(input);
    let changed = true;
    while (changed) {
      changed = false;
      for (const original of [...state.authors]) {
        if (!state.authors.some(a => a.id === original.id)) continue;
        const accepted = new Set(names(original));
        const same = state.authors.filter(a => names(a).some(n => accepted.has(n)));
        if (same.length > 1) {
          mergeInto(state, same.map(a => a.id), original.id, same.some(a => a.follow), recommendedInterval(same));
          changed = true;
          break;
        }
      }
    }
    return state;
  }
  function create() {
    return normalize({
      revision: 0, redirects: {}, fail: false, unavailable: false,
      authors: ['a', 'b', 'c', 'en', 'tw'].map((id, i) => ({ id, name: ['冈本伦', '冈本伦', '冈本伦', 'Okamoto Lynn', '岡本倫'][i], aliases: [], follow: i === 1, interval: i === 1 ? 12 : 24 })),
      versions: [
        { id: 'v0', work: 'w0', title: '平行天堂', source: '漫画柜', author: 'a', signature: '冈本伦' },
        { id: 'v1', work: 'w1', title: '极黑的布伦希尔德', source: '文字图源（演示）', author: 'b', signature: '冈本伦' },
        { id: 'v2', work: 'w2', title: '变异体少女', source: '漫画柜', author: 'c', signature: '冈本伦' },
        { id: 'md', work: 'w0', title: 'Parallel Paradise', source: 'MangaDex', author: 'en', signature: 'Okamoto Lynn' },
        { id: 'tw', work: 'w1', title: '極黑的布倫希爾德', source: '演示来源', author: 'tw', signature: '岡本倫' },
      ],
    });
  }
  function observe(input, observation) {
    if (!observation.name || !observation.name.trim()) return structuredClone(input);
    const state = structuredClone(input);
    let author = state.authors.find(a => names(a).includes(observation.name));
    if (!author) {
      author = { id: 'name-' + observation.name, name: observation.name, aliases: [], follow: false, interval: 24 };
      state.authors.push(author);
    }
    const version = { ...observation, author: author.id, signature: observation.name };
    const index = state.versions.findIndex(v => v.id === observation.id);
    if (index < 0) state.versions.push(version); else state.versions[index] = version;
    state.revision++;
    return state;
  }
  function preview(state, options = {}) {
    const selected = (options.selected || []).filter(id => state.authors.some(a => a.id === id));
    const authorSnapshot = structuredClone(state.authors.filter(a => selected.includes(a.id)));
    const versionSnapshot = structuredClone(state.versions.filter(v => selected.includes(v.author)));
    return { ...options, names: [...new Set(authorSnapshot.flatMap(names))], selected, revision: state.revision, authorSnapshot, versionSnapshot,
      workCount: new Set(versionSnapshot.map(v => v.work)).size, versionCount: versionSnapshot.length,
      followedCount: authorSnapshot.filter(a => a.follow).length };
  }
  function apply(input, plan) {
    if (plan.revision !== input.revision) throw Error('预览已过期，请刷新预览后重新确认。');
    if (input.fail) throw Error('提交失败，未改变任何作者或作品。请重试。');
    if (plan.selected.length < 2 || !plan.selected.includes(plan.target)) throw Error('请选择至少两个不同名字，并从中选择主显示名。');
    if (plan.displayName && !plan.names.includes(plan.displayName)) throw Error('主显示名必须属于本次名称范围。');
    const state = structuredClone(input);
    mergeInto(state, plan.selected, plan.target, !!plan.follow, plan.interval || recommendedInterval(plan.authorSnapshot), plan.displayName);
    state.revision++;
    return state;
  }
  function follow(input, id, enabled) {
    if (input.fail) throw Error('提交失败，关注状态未改变。');
    const state = structuredClone(input); state.authors.find(a => a.id === resolve(state, id)).follow = enabled; state.revision++; return state;
  }
  return { create, normalize, observe, preview, apply, resolve, follow, recommendedInterval };
});
