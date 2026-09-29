(function (root) {
  "use strict";
  function create() {
    const names = [
      "A · 星海手记",
      "B · 雨后书店",
      "C · 夜行电车",
      "D · 森林来信",
      "E · 云端旅人",
      "F · 岛屿日记",
    ];
    return {
      platform: "windows",
      route: "library",
      bookId: null,
      category: 1,
      categories: [
        { id: 0, name: "默认" },
        { id: 1, name: "追更中" },
        { id: 2, name: "珍藏" },
      ],
      books: names.map((title, i) => ({
        id: String.fromCharCode(65 + i),
        title,
        categories: i < 5 ? [1] : [2],
        unread: i === 1 ? 0 : 2,
        source: "示例图源",
        language: "中文",
        status: i === 3 ? "已完结" : "连载中",
        sourceMissing: false,
        author: ["林舟", "秋原", "许禾", "青山", "云岚", "南风"][i],
        artist: ["星野", "绘里", "夜灯", "木禾", "远山", "海音"][i],
        genre: ["冒险", i % 2 ? "日常" : "奇幻", "成长"],
        description: `${title} 的故事从一次意外相遇展开。主角在日常和未知之间寻找自己的方向，也逐渐发现同伴留下的线索。这里展示图源简介的本地样本。`,
        favorite: true,
        fetchInterval: 0,
        readingMode: "默认",
        notes: "",
        chapterOptions: {
          read: true,
          unread: true,
          bookmark: false,
          download: false,
          scanlators: {},
          sort: "source",
          ascending: false,
          display: "name",
        },
        started: i !== 2,
        complete: i === 3,
        due: i !== 4,
        cover: 0,
        custom: false,
        customRevision: 0,
        readAt: [3, 6, 1, 4, 2, 5][i],
        updatedAt: [1, 3, 5, 2, 6, 4][i],
        fetchedAt: i,
        addedAt: 6 - i,
        tracks:
          i === 0
            ? [{ name: "AniList", scale: 100, value: 80 }]
            : i === 1
              ? [{ name: "MyAnimeList", scale: 10, value: 9 }]
              : [],
        chapters: [1, 2, 3].map((n) => ({
          id: `${String.fromCharCode(65 + i)}-${n}`,
          number: n,
          sourceOrder: 3 - n,
          name: `第 ${n} 话`,
          url: `/chapter/${n}`,
          read: i === 1 || n === 1,
          bookmark: n === 1,
          page: n === 1 ? 7 : 0,
          download: n === 3 ? null : `${String.fromCharCode(65 + i)}-file-${n}`,
          downloadStatus: n === 3 ? ({ 1: "error", 2: "queued", 3: "downloading" }[i] || null) : null,
          scanlator: null,
          dateUpload: 1700000000000 + n * 86400000,
        })),
      })),
      selected: [],
      anchor: null,
      query: null,
      filters: {},
      categorySort: {},
      categoryReverse: {},
      source: "ok",
      notice: "",
      job: null,
      now: 0,
      nextDue: 0,
      waiting: "",
      device: { online: true, wifi: true, unmetered: true, power: true },
      prefs: {
        layout: "compact",
        columns: 0,
        portrait: 0,
        unreadBadge: true,
        downloadBadge: true,
        localBadge: true,
        languageBadge: false,
        continueRead: true,
        tabs: true,
        counts: true,
        defaultCategory: -1,
        categoryPolicy: {},
        perCategory: true,
        interval: 0,
        wifi: false,
        unmetered: false,
        power: false,
        caughtUp: false,
        started: false,
        ongoing: false,
        expected: false,
        metadata: false,
        downloadUsesVisible: true,
        showChapterGaps: true,
        autoDownloadNew: false,
        trackingOnRead: "ask",
        protectTitle: false,
        chapterDefaults: null,
      },
      failSave: false,
      sortInterrupted: false,
      pendingReset: false,
      seed: 0,
      downloadOnly: false,
      trackerNames: ["AniList", "MyAnimeList"],
      scroll: {},
    };
  }
  function score(book) {
    const values = book.tracks
      .filter((t) => t.value > 0 && [10, 100, 5].includes(t.scale))
      .map((t) => (t.value / t.scale) * 10);
    return values.length
      ? values.reduce((a, b) => a + b, 0) / values.length
      : 0;
  }
  function visible(s) {
    let books = s.books.filter(
      (b) =>
        b.favorite !== false &&
        b.categories.includes(s.category) &&
        (!s.query || b.title.toLowerCase().includes(s.query.toLowerCase())),
    );
    const checks = {
      unread: (b) => b.unread > 0,
      download: (b) => b.chapters.some((c) => c.download),
      started: (b) => b.started,
      bookmark: (b) => b.chapters.some((c) => c.bookmark),
      complete: (b) => b.complete,
      due: (b) => b.due,
      custom: (b) => !!b.customPeriod,
      AniList: (b) => b.tracks.some((t) => t.name === "AniList"),
      MyAnimeList: (b) => b.tracks.some((t) => t.name === "MyAnimeList"),
    };
    for (const [key, mode] of Object.entries(s.filters))
      if (mode && checks[key])
        books = books.filter((b) => checks[key](b) === (mode === 1));
    if (s.downloadOnly) books = books.filter(checks.download);
    const { key: sort, reverse } = sortState(s);
    const values = {
      chapters: (b) => b.chapters.length,
      read: (b) => b.readAt,
      updated: (b) => b.updatedAt,
      unread: (b) => b.unread,
      latest: (b) => Math.max(0, ...b.chapters.map((c) => c.number)),
      fetched: (b) => b.fetchedAt,
      added: (b) => b.addedAt,
    };
    books.sort((a, b) =>
      sort === "score"
        ? score(a) - score(b)
        : sort === "random"
          ? hash(a.id + s.seed) - hash(b.id + s.seed)
          : values[sort]
            ? values[sort](a) - values[sort](b)
            : a.title.localeCompare(b.title),
    );
    return reverse ? books.reverse() : books;
  }
  function sortState(s) {
    return s.prefs.perCategory
      ? {
          key: s.categorySort[s.category] || "title",
          reverse: !!s.categoryReverse[s.category],
        }
      : { key: s.prefs.sort || "title", reverse: !!s.prefs.reverse };
  }
  function setSort(s, key) {
    if (s.failSave) return save(s, "sort", key);
    const current = sortState(s);
    const reverse =
      key === current.key && key !== "random"
        ? !current.reverse
        : current.reverse;
    if (key === "random") s.seed++;
    if (s.prefs.perCategory) {
      s.categorySort[s.category] = key;
      s.categoryReverse[s.category] = reverse;
    } else {
      s.prefs.sort = key;
      s.prefs.reverse = reverse;
    }
    s.notice = "排序已保存";
    return true;
  }
  function hash(str) {
    const n = [...str].reduce((v, c) => (v * 31 + c.charCodeAt(0)) % 65521, 7);
    return (Math.sin(n * 12.9898) * 43758.5453) % 1;
  }
  function select(s, id, event = {}) {
    const ids = visible(s).map((b) => b.id);
    if (!ids.includes(id) || event.alt) return;
    const selected = new Set(s.selected);
    if (event.shift || event.long) {
      const valid =
        s.anchor &&
        s.anchor.category === s.category &&
        ids.includes(s.anchor.id);
      if (!valid) s.anchor = { id, category: s.category };
      const a = ids.indexOf(s.anchor.id),
        b = ids.indexOf(id);
      if (event.shift && !event.ctrl && !event.long && s.platform !== "android")
        ids.forEach((x) => selected.delete(x));
      ids
        .slice(Math.min(a, b), Math.max(a, b) + 1)
        .forEach((x) => selected.add(x));
    } else if (event.ctrl || selected.size) {
      if (selected.has(id)) selected.delete(id);
      else selected.add(id);
      s.anchor = { id, category: s.category };
    } else {
      s.selected = [];
      s.anchor = null;
      s.bookId = id;
      s.route = "detail";
      return;
    }
    s.selected = [...selected];
    if (!selected.size) s.anchor = null;
  }
  function save(s, key, value) {
    if (s.failSave) {
      s.failSave = false;
      s.notice = "保存失败，已恢复原设置。请重试。";
      return false;
    }
    if (key === "perCategory" && !value) {
      s.pendingReset = true;
      if (s.sortInterrupted) {
        s.sortInterrupted = false;
        s.notice = "分类排序清理中断，保留开启。模拟重新打开后继续恢复。";
        return false;
      }
      s.categorySort = {};
      s.categoryReverse = {};
      s.pendingReset = false;
    }
    s.prefs[key] = value;
    s.notice = "设置已保存";
    if (key === "interval") {
      s.nextDue = s.now;
      if (!value) s.waiting = "";
    }
    return true;
  }
  function candidates(s, type) {
    if (type === "category")
      return s.books.filter((b) => b.favorite !== false && b.categories.includes(s.category));
    const policy = s.prefs.categoryPolicy,
      included = Object.keys(policy)
        .filter((id) => policy[id] === 1)
        .map(Number);
    return s.books.filter(
      (b) =>
        b.favorite !== false &&
        !b.categories.some((id) => policy[id] === -1) &&
        (!included.length || b.categories.some((id) => included.includes(id))),
    );
  }
  function reason(s, b, p = s.prefs) {
    if (b.favorite === false) return "作品已取消收藏";
    if (b.local) return "本地作品不请求网络";
    if (p.ongoing && b.complete) return "已完结";
    if (p.caughtUp && b.unread > 0) return "尚未追平";
    if (p.started && !b.started && b.chapters.length) return "尚未开始阅读";
    if (p.expected && !b.due) return "尚未到预计更新期";
    return "";
  }
  function deviceReason(s) {
    const fields = [
      ["online", "网络连接"],
      ["wifi", "Wi-Fi"],
      ["unmetered", "非计量网络"],
      ["power", "接通电源 / 充电"],
    ];
    for (const [key, label] of fields)
      if ((key === "online" || s.prefs[key]) && s.device[key] !== true)
        return `${label}${s.device[key] === null ? "状态未知" : "条件不满足"}，等待恢复`;
    return "";
  }
  function start(s, type, ids) {
    if (s.job && ["running", "waiting"].includes(s.job.status)) {
      s.notice = "更新已在进行";
      return false;
    }
    if (s.brokenStore) {
      s.notice = "更新记录无法读取，未覆盖原记录。请在演示栏恢复记录后重试。";
      return false;
    }
    if (type === "scheduled" && (s.waiting = deviceReason(s))) return false;
    const work = ids || candidates(s, type).map((b) => b.id);
    if (s.job) {
      s.archivedJobs ||= [];
      s.archivedJobs.push(structuredClone(s.job));
    }
    s.job = {
      type,
      ids: [...work],
      results: {},
      status: "running",
      rules: structuredClone(s.prefs),
      category: s.category,
    };
    s.waiting = "";
    s.notice = "更新已开始";
    if (!work.length) finish(s);
    return true;
  }
  function finish(s) {
    s.job.status = "done";
    if (s.job.type === "scheduled")
      s.nextDue = s.now + s.prefs.interval * 3600000;
    s.notice = "更新完成，可查看逐本结果";
  }
  function syncBook(s, b) {
    if (b.pendingCheckpoint) {
      b.pendingCheckpoint = false;
      return "已恢复目录提交结果；未重复请求或新增章节";
    }
    if (b.pendingFile) {
      if (s.source === "file-failure")
        throw Error("目录已更新，但下载文件关联失败；重试只补此步骤");
      b.pendingFile = false;
      return "已补全下载文件关联；未重复请求目录或添加章节";
    }
    if (s.source === "partial-failure" && b.id === "B")
      throw Error("图源暂时不可用（HTTP 500）");
    if (!s.device.online) throw Error("网络不可用，请恢复网络后重试");
    if (s.source === "empty-source")
      throw Error("图源返回空目录，保留原有章节");
    if (s.source === "malformed-source")
      throw Error("目录不完整，保留原有章节");
    if (s.source === "transaction-failure" && b.id === "A")
      throw Error("本地目录保存失败，已保留完整旧目录");
    if (
      ["chapter-change", "file-failure", "checkpoint-gap"].includes(s.source)
    ) {
      if (s.source === "chapter-change" && b.chapters.some((c) => c.number === 4) && !b.chapters.some((c) => c.number === 2)) {
        if (s.job.rules.metadata) {
          b.description = "已从图源刷新简介";
          b.cover++;
        }
        return "目录无变化：新增 0 · 改名 0 · 移除 0；可识别换链已承接原章节身份";
      }
      const first = b.chapters.find((c) => c.number === 1);
      b.detachedDownloads = [
        ...new Set([
          ...(b.detachedDownloads || []),
          ...b.chapters
            .filter((c) => c.number === 2 && c.download)
            .map((c) => c.download),
        ]),
      ];
      b.chapters = [
        {
          ...b.chapters.find((c) => c.number === 3),
          name: "第 3 话 · 更名与重排",
          url: "/chapter/3",
          sourceOrder: 0,
          scanlator: "新版译制组",
          dateUpload: 1700600000000,
          memo: "源目录重新发布",
        },
        {
          ...(b.chapters.find((c) => c.number === 4) || {
            id: b.id + "-4",
            number: 4,
            read: false,
            bookmark: false,
            page: 0,
            download: null,
          }),
          name: "第 4 话 · 新章节",
          url: "/new/4",
          sourceOrder: 1,
          scanlator: "新版译制组",
          dateUpload: 1700700000000,
        },
        { ...first, name: "第 1 话 · 新地址", url: "/new/1", sourceOrder: 2 },
      ];
    }
    if (s.job.rules.metadata) {
      b.description = "已从图源刷新简介";
      b.cover++;
    }
    b.unread = b.chapters.filter((c) => !c.read).length;
    b.updated = (b.updated || 0) + 1;
    b.updatedAt = s.now;
    if (s.source === "file-failure" && b.id === "A") {
      b.pendingFile = true;
      throw Error("目录已更新，但下载文件关联失败；重试只补此步骤");
    }
    return s.source === "chapter-change"
      ? "新增 1 · 改名 2 · 移除 1 · 可识别换链 1 · 源顺序重排；阅读、书签和下载关联保留"
      : "目录检查完成";
  }
  function step(s) {
    const job = s.job;
    if (!job || !["running", "waiting"].includes(job.status)) return;
    if (job.type === "scheduled" && (s.waiting = deviceReason(s))) {
      job.status = "waiting";
      return;
    }
    s.waiting = "";
    job.status = "running";
    const id = job.ids.find((id) => !job.results[id]);
    if (!id) {
      finish(s);
      return;
    }
    const b = s.books.find((b) => b.id === id),
      skip = b ? reason(s, b, job.rules) : "作品已取消收藏";
    if (skip)
      job.results[id] = {
        status: "skipped",
        reason: skip,
        title: b?.title || id,
      };
    else
      try {
        const message = syncBook(s, b);
        if (s.source === "checkpoint-gap" && !b.gapDemonstrated) {
          b.gapDemonstrated = true;
          b.pendingCheckpoint = true;
          job.status = "interrupted";
          s.notice = "目录已保存，更新记录写入前中断；请模拟重新打开或继续恢复";
          return;
        }
        job.results[id] = {
          status: "success",
          reason: message,
          title: b.title,
        };
      } catch (error) {
        job.results[id] = {
          status: "failed",
          reason: error.message,
          title: b.title,
        };
      }
    if (Object.keys(job.results).length === job.ids.length) finish(s);
  }
  function cancel(s) {
    if (s.job) {
      s.job.status = "cancelled";
      s.notice = "已停止后续更新，已完成的结果保留";
      if (s.job.type === "scheduled")
        s.nextDue = s.now + s.prefs.interval * 3600000;
    }
  }
  function retry(s) {
    const old = s.job;
    if (!old) return;
    const ids = old.ids.filter((id) => old.results[id]?.status === "failed");
    if (ids.length) {
      start(s, "retry", ids);
      s.job.rules = old.rules;
    } else s.notice = "没有失败项";
  }
  function resume(s) {
    if (!s.job) return;
    s.job.status = "running";
    s.job.legacy = false;
    s.notice = "继续原更新范围";
  }
  function tick(s, ms) {
    s.now = Math.max(0, s.now + ms);
    if (s.job && ["running", "waiting"].includes(s.job.status)) return;
    if (s.prefs.interval > 0 && s.now >= s.nextDue) start(s, "scheduled");
  }
  function restart(s) {
    const r = structuredClone(s);
    r.route = "library";
    r.selected = [];
    r.anchor = null;
    if (r.pendingReset) {
      r.categorySort = {};
      r.categoryReverse = {};
      r.prefs.perCategory = false;
      r.pendingReset = false;
      r.notice = "已恢复分类排序清理；旧排序不会重新出现";
    } else if (["running", "interrupted"].includes(r.job?.status)) {
      r.job.status = "running";
      r.notice = "已恢复未完成的更新，已完成作品不会重复请求";
    } else r.notice = "已重新打开本地演示；保留设置与更新记录";
    return r;
  }
  function scenario(s, name) {
    if (name === "save-failure") {
      s.failSave = true;
      s.notice = "下一次设置保存将失败";
      return;
    }
    if (name === "sort-interrupted") {
      s.sortInterrupted = true;
      s.prefs.perCategory = true;
      s.categorySort = { 1: "score" };
      s.notice = "关闭按分类排序后，模拟中断；用重新打开恢复";
      return;
    }
    if (name === "broken-store") {
      s.brokenStore = true;
      s.notice = "更新记录损坏样本";
      return;
    }
    const platform = s.platform;
    Object.keys(s).forEach((key) => delete s[key]);
    Object.assign(s, create());
    s.platform = platform;
    if (name === "large")
      s.books = Array.from({ length: 1000 }, (_, i) => ({
        ...structuredClone(s.books[i % 6]),
        id: "book-" + i,
        title: `${String(i + 1).padStart(4, "0")} · 书架故事`,
        categories: [1],
      }));
    if (name === "single-default" || name === "single-custom") {
      const id = name === "single-default" ? 0 : 1;
      s.categories = [{ id, name: id ? "追更中" : "默认" }];
      s.category = id;
      s.books.forEach((b) => (b.categories = [id]));
    }
    if (name === "single-tracker") s.trackerNames = ["AniList"];
    if (name === "multi-tracker")
      s.books[2].tracks = [
        { name: "AniList", scale: 100, value: 70 },
        { name: "MyAnimeList", scale: 10, value: 9 },
      ];
    if (name === "custom-cover") {
      s.books[0].custom = true;
      s.books[0].customRevision = 1;
    }
    if (
      [
        "partial-failure",
        "chapter-change",
        "empty-source",
        "malformed-source",
        "transaction-failure",
        "file-failure",
        "checkpoint-gap",
      ].includes(name)
    )
      s.source = name;
    if (name === "chapter-change") {
      s.books[0].chapters[2].page = 4;
    }
    if (name === "empty-library") s.books = [];
    if (name === "loading") s.loading = true;
    if (name === "load-error") s.loadError = true;
    if (name === "download-only") {
      s.downloadOnly = true;
      s.books[2].chapters.forEach((c) => (c.download = null));
    }
    if (name === "smart-samples") {
      s.books[0].chapters = [];
      s.books[0].started = false;
      s.books[0].unread = 0;
      s.books[1].local = true;
      s.books[1].source = "本地";
    }
    if (name === "multi-category") s.books[0].categories = [0, 1];
    if (name === "custom-period") {
      s.customPeriod = true;
      s.books[0].customPeriod = true;
    }
    if (name === "device-wait") {
      s.prefs.interval = 6;
      s.prefs.wifi = true;
      s.prefs.power = true;
      s.device.wifi = null;
      s.device.power = false;
      tick(s, 0);
    }
    if (name === "legacy-task") {
      start(s, "category", ["A", "B", "C"]);
      s.job.status = "legacy";
      s.job.legacy = true;
      s.notice = "旧更新缺少触发信息，请明确选择继续旧更新";
    }
    if (name === "legacy-no-scope") {
      s.job = {
        type: "unknown",
        ids: [],
        results: {},
        status: "unrecoverable",
      };
      s.notice = "原记录保留；无法确定旧更新范围";
    }
  }
  function reorderCategory(s, id, targetIndex) {
    const categories = s.categories.filter((c) => c.id !== 0);
    const index = categories.findIndex((c) => c.id === id);
    if (
      index < 0 ||
      !Number.isInteger(targetIndex) ||
      targetIndex < 0 ||
      targetIndex >= categories.length
    ) return false;
    if (s.failSave) {
      s.failSave = false;
      s.notice = "分类顺序保存失败，请重试";
      return false;
    }
    const [category] = categories.splice(index, 1);
    categories.splice(targetIndex, 0, category);
    s.categories = [...s.categories.filter((c) => c.id === 0), ...categories];
    s.notice = "分类顺序已保存";
    return true;
  }
  const api = {
    create,
    reorderCategory,
    visible,
    select,
    scenario,
    start,
    step,
    retry,
    cancel,
    resume,
    restart,
    save,
    candidates,
    tick,
    score,
    sortState,
    setSort,
    reason,
    deviceReason,
  };
  if (typeof module === "object") module.exports = api;
  else root.LibraryModel = api;
})(typeof window === "object" ? window : globalThis);
