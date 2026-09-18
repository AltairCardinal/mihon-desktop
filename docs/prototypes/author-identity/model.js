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
  function mergeInto(state, selected, target, follow, displayName) {
    const retained = state.authors.find(a => a.id === target);
    const members = state.authors.filter(a => selected.includes(a.id));
    const allNames = [...new Set(members.flatMap(names))];
    retained.name = displayName || retained.name;
    retained.aliases = allNames.filter(n => n !== retained.name);
    retained.follow = follow;
    members.forEach(a => { if (a.id !== target) state.redirects[a.id] = target; });
    state.versions.forEach(v => { if (selected.includes(v.author)) v.author = target; });
    state.authors = state.authors.filter(a => !selected.includes(a.id) || a.id === target);
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
          mergeInto(state, same.map(a => a.id), original.id, same.some(a => a.follow));
          changed = true;
          break;
        }
      }
    }
    return state;
  }
  function create() {
    const state = normalize({
      today: '2026-09-18', workDates: {},
      // Demonstration evidence only, not a rating of the real plugins with these display names.
      dateSources: {
        cabinet: { listing: 'trusted', chapters: 'trusted' },
        text: { listing: 'unknown', chapters: 'unknown' },
        unreliable: { listing: 'suspect', chapters: 'suspect' },
        dex: { listing: 'trusted', chapters: 'trusted' },
      },
      revision: 0, redirects: {}, fail: false, unavailable: false, frequency: 'daily',
      authors: ['a', 'b', 'c', 'en', 'tw'].map((id, i) => ({ id, name: ['冈本伦', '冈本伦', '冈本伦', 'Okamoto Lynn', '岡本倫'][i], aliases: [], follow: i === 1 })),
      versions: [
        { id: 'v0', work: 'w0', title: '平行天堂', source: '漫画柜', author: 'a', signature: '冈本伦' },
        { id: 'v1', work: 'w1', title: '极黑的布伦希尔德', source: '文字图源（演示）', author: 'b', signature: '冈本伦' },
        { id: 'v2', work: 'w2', title: '变异体少女', source: '漫画柜', author: 'c', signature: '冈本伦' },
        { id: 'md', work: 'w0', title: 'Parallel Paradise', source: 'MangaDex', author: 'en', signature: 'Okamoto Lynn' },
        { id: 'tw', work: 'w1', title: '極黑的布倫希爾德', source: '演示来源', author: 'tw', signature: '岡本倫' },
      ],
    });
    const fixtures = [
      { sourceKey: 'cabinet', favorite: true, chapterCount: 300, listedAt: '2017-03-18', latestChapterAt: '2026-09-17' },
      { sourceKey: 'text', favorite: false, chapterCount: 181, listedAt: null, latestChapterAt: null },
      { sourceKey: 'unreliable', favorite: false, chapterCount: 12, listedAt: '2026-09-18', latestChapterAt: '2026-09-18' },
      { sourceKey: 'dex', favorite: false, chapterCount: 295, listedAt: '2018-06-10', latestChapterAt: '2026-09-15' },
      { sourceKey: 'text', favorite: true, chapterCount: null, listedAt: null, latestChapterAt: null },
    ];
    state.versions.forEach((v, i) => Object.assign(v, fixtures[i], { firstSeenAt: '2026-09-18' }));
    updateWorkDates(state);
    return state;
  }
  function validDate(value) {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    const instant = new Date(value + 'T00:00:00Z');
    return Number.isFinite(instant.getTime()) && instant.toISOString().slice(0, 10) === value;
  }
  function updateWorkDates(state) {
    state.workDates ||= {};
    for (const v of state.versions) {
      const trusted = state.dateSources?.[v.sourceKey]?.listing === 'trusted';
      const accepted = trusted && validDate(v.listedAt) && v.listedAt <= v.firstSeenAt;
      const candidate = accepted ? { date: v.listedAt, label: '上架', source: v.source } : { date: v.firstSeenAt, label: '首次发现', source: null };
      const old = state.workDates[v.work];
      if (!old || candidate.date < old.date || candidate.date === old.date && accepted && old.label === '首次发现') state.workDates[v.work] = candidate;
    }
  }
  function workDate(state, work) {
    return state.workDates[work] || { date: state.today, label: '首次发现', source: null };
  }
  function latestDate(state, version) {
    if (!version.latestChapterAt) return { date: null, label: '日期未提供' };
    if (state.dateSources?.[version.sourceKey]?.chapters !== 'trusted' || !validDate(version.latestChapterAt) || version.latestChapterAt > state.today) return { date: null, label: '日期待核实' };
    return { date: version.latestChapterAt, label: '最新章节' };
  }
  function assessChapterDates(samples) {
    const distinct = [...new Map(samples.map(s => [s.work, s])).values()];
    const comparable = distinct.filter(s => validDate(s.beforeAt) && validDate(s.afterAt) && new Date(s.afterAt) - new Date(s.beforeAt) >= 86400000 && s.before.length >= 3 && s.before.length === s.after.length);
    if (comparable.some(s => s.after.every(d => d === s.afterAt) && s.before.some((d, i) => d !== s.after[i]))) return 'suspect';
    const stable = comparable.filter(s => new Set(s.before).size >= 3 && s.before.every((d, i) => validDate(d) && d < s.beforeAt && d === s.after[i]));
    return stable.length >= 3 ? 'trusted' : 'unknown';
  }
  function refreshDates(input) {
    const state = structuredClone(input);
    const next = new Date(state.today + 'T00:00:00Z'); next.setUTCDate(next.getUTCDate() + 1); state.today = next.toISOString().slice(0, 10);
    // Existing chapter IDs all shifting to the fetch date is a negative signal.
    const sample = [{ work: 'w0', beforeAt: input.today, afterAt: state.today, before: ['2025-01-01', '2025-02-01', '2025-03-01'], after: [state.today, state.today, state.today] }];
    state.dateSources.cabinet.chapters = assessChapterDates(sample);
    state.versions = state.versions.map(v => ['cabinet', 'unreliable'].includes(v.sourceKey) ? { ...v, latestChapterAt: v.latestChapterAt ? state.today : null } : v);
    updateWorkDates(state); state.revision++;
    return state;
  }
  function observe(input, observation) {
    if (!observation.name || !observation.name.trim()) return structuredClone(input);
    const state = structuredClone(input);
    let author = state.authors.find(a => names(a).includes(observation.name));
    if (!author) {
      author = { id: 'name-' + observation.name, name: observation.name, aliases: [], follow: false };
      state.authors.push(author);
    }
    const index = state.versions.findIndex(v => v.id === observation.id);
    const previous = state.versions[index];
    const version = { favorite: false, chapterCount: null, ...previous, ...observation, author: author.id, signature: observation.name,
      firstSeenAt: previous?.firstSeenAt || state.today, favorite: previous?.favorite ?? false };
    if (index < 0) state.versions.push(version); else state.versions[index] = version;
    updateWorkDates(state);
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
    if (plan.revision !== input.revision) throw Error('作者资料已更新，请刷新候选后重新添加。');
    if (input.fail) throw Error('提交失败，未改变任何作者或作品。请重试。');
    if (plan.selected.length < 2 || !plan.selected.includes(plan.target)) throw Error('请至少选择一位其他作者。');
    if (plan.displayName && !plan.names.includes(plan.displayName)) throw Error('主显示名必须属于本次名称范围。');
    const state = structuredClone(input);
    mergeInto(state, plan.selected, plan.target, plan.selected.some(id => state.authors.find(a => a.id === id).follow), plan.displayName);
    state.revision++;
    return state;
  }
  function follow(input, id, enabled) {
    if (input.fail) throw Error('提交失败，关注状态未改变。');
    const state = structuredClone(input); state.authors.find(a => a.id === resolve(state, id)).follow = enabled; state.revision++; return state;
  }
  function setFrequency(input, frequency) {
    if (input.fail) throw Error('保存失败，检查频率未改变。');
    if (!['daily', 'weekly', 'monthly'].includes(frequency)) throw Error('请选择有效的检查频率。');
    const state = structuredClone(input);
    state.frequency = frequency;
    state.revision++; return state;
  }
  function rename(input, id, name) {
    if (input.fail) throw Error('保存失败，名称未改变。');
    if (!name || !name.trim()) throw Error('请输入非空名称。');
    const rootId = resolve(input, id);
    if (input.authors.some(a => a.id !== rootId && names(a).includes(name))) throw Error('该名字已属于另一位作者，请先通过添加别名关联。');
    const state = structuredClone(input), author = state.authors.find(a => a.id === rootId);
    const allNames = [...new Set([...names(author), name])];
    author.name = name; author.aliases = allNames.filter(n => n !== name); state.revision++; return state;
  }
  return { create, normalize, observe, preview, apply, resolve, follow, setFrequency, rename, workDate, latestDate, assessChapterDates, refreshDates };
});
