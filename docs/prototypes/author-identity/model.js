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
  // The prototype keeps a small, explicit script table so the interaction can
  // demonstrate strict equivalence without introducing fuzzy title matching.
  const scriptPhrases = [
    ['詭譎屋', '诡谲屋'],
    ['情有獨鍾', '情有独钟'],
    ['極黑的布倫希爾德', '极黑的布伦希尔德'],
    ['外傳', '外传'],
  ];
  const scriptCharacters = new Map(Object.entries({
    詭: '诡', 譎: '谲', 極: '极', 黑: '黑', 布: '布', 倫: '伦', 希: '希', 爾: '尔', 德: '德',
    獨: '独', 鍾: '钟', 傳: '传', 後: '后', 發: '发', 體: '体', 國: '国', 間: '间', 門: '门',
    經: '经', 續: '续', 來: '来', 這: '这', 個: '个', 與: '与', 對: '对', 無: '无', 新: '新',
  }));
  function normalizeWorkTitle(title) {
    let result = String(title ?? '');
    for (const [traditional, simplified] of scriptPhrases) result = result.split(traditional).join(simplified);
    return [...result]
      .map(character => scriptCharacters.get(character) || character)
      .join('')
      .replace(/[\p{P}\p{Z}\s]+/gu, '');
  }
  function isExcluded(state, author, work) {
    return (state.presentationExclusions || []).some(item => item.author === author && item.work === work);
  }
  function matchesPresentation(version, query, source) {
    if (source && version.source !== source) return false;
    const value = String(query || '').trim();
    if (!value) return true;
    const raw = version.title.toLocaleLowerCase();
    const normalized = normalizeWorkTitle(version.title).toLocaleLowerCase();
    const needle = value.toLocaleLowerCase();
    const normalizedNeedle = normalizeWorkTitle(value).toLocaleLowerCase();
    return raw.includes(needle) || normalized.includes(normalizedNeedle);
  }
  function presentationGroups(state, authorId, options = {}) {
    const root = resolve(state, authorId);
    const versions = state.versions.filter(version => resolve(state, version.author) === root);
    const byWork = new Map();
    for (const version of versions) {
      if (!byWork.has(version.work)) byWork.set(version.work, []);
      byWork.get(version.work).push(version);
    }
    const groups = [];
    for (const members of byWork.values()) {
      const first = members[0];
      const normalizedTitle = normalizeWorkTitle(first.title);
      const existing = groups.find(group => {
        if (group.normalizedTitle !== normalizedTitle || group.excluded || isExcluded(state, root, first.work)) return false;
        if (group.canonicalWork && first.canonicalWork && group.canonicalWork !== first.canonicalWork) return false;
        return !group.versions.some(version => isExcluded(state, root, version.work));
      });
      if (existing) {
        existing.versions.push(...members);
        existing.workIds.add(first.work);
        existing.autoMerged = existing.workIds.size > 1;
      } else {
        groups.push({
          id: first.work,
          title: first.title,
          normalizedTitle,
          canonicalWork: first.canonicalWork || null,
          versions: [...members],
          workIds: new Set([first.work]),
          excluded: isExcluded(state, root, first.work),
          autoMerged: false,
        });
      }
    }
    const query = options.query || '';
    const source = options.source && options.source !== '全部来源' ? options.source : '';
    return groups.filter(group => group.versions.some(version => matchesPresentation(version, query, source)));
  }
  function presentationGroup(state, authorId, groupId) {
    return presentationGroups(state, authorId).find(group => group.id === groupId || group.workIds.has(groupId));
  }
  function splitPresentation(input, authorId, work) {
    const state = structuredClone(input);
    const root = resolve(state, authorId);
    if (!state.versions.some(version => resolve(state, version.author) === root && version.work === work)) return state;
    state.presentationExclusions ||= [];
    if (!state.presentationExclusions.some(item => item.author === root && item.work === work)) state.presentationExclusions.push({ author: root, work });
    state.revision++;
    return state;
  }
  function undoPresentationSplit(input, authorId, work) {
    const state = structuredClone(input);
    const root = resolve(state, authorId);
    state.presentationExclusions = (state.presentationExclusions || []).filter(item => !(item.author === root && item.work === work));
    state.revision++;
    return state;
  }
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
      today: '2026-09-18', workDates: {}, discoveries: [],
      presentationExclusions: [],
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
    state.versions.push({ id: 'v0-other', work: 'w0', title: '平行天堂', source: '备用图源（演示）', author: 'a', signature: '冈本伦', sourceKey: 'text', favorite: false, chapterCount: 286, listedAt: null, latestChapterAt: null, firstSeenAt: state.today });
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
    const knownWork = state.versions.some(v => v.work === observation.work);
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
    if (!knownWork && author.follow && observation.work) state.discoveries.push(observation.work);
    updateWorkDates(state);
    state.revision++;
    return state;
  }
  function unseenWorks(state, id) {
    const author = state.authors.find(a => a.id === resolve(state, id));
    return author?.follow ? [...new Set(state.discoveries.filter(work => state.versions.some(v => v.author === author.id && v.work === work)))] : [];
  }
  function markSeen(input, work) {
    const state = structuredClone(input);
    state.discoveries = state.discoveries.filter(id => id !== work);
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
  return { create, normalize, observe, preview, apply, resolve, follow, setFrequency, rename, workDate, latestDate, assessChapterDates, refreshDates, unseenWorks, markSeen, normalizeWorkTitle, presentationGroups, presentationGroup, splitPresentation, undoPresentationSplit };
});
