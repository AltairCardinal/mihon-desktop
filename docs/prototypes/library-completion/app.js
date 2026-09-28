(() => {
  "use strict";
  const M = LibraryModel,
    app = document.querySelector("#app");
  let s = M.create();
  s.platform =
    new URLSearchParams(location.search).get("platform") === "android"
      ? "android"
      : "windows";
  document.body.classList.add(s.platform);
  let modal = null,
    modalStack = [],
    trigger = null,
    panelTab = "filter",
    panelScroll = {},
    reader = null,
    hold = null,
    suppressClick = false;
  let wheel = { phase: "idle", distance: 0, last: 0, armed: 0, second: 0 },
    cooldownUntil = -Infinity;
  const CATEGORY_WHEEL_GAP_MS = 250;
  let categoryWheel = { last: -Infinity, direction: 0 },
    composing = false;
  const categoryShortcut =
    s.platform === "windows"
      ? "Ctrl + 滚轮切换分类：向上上一类，向下下一类"
      : "切换分类";
  const esc = (x) =>
    String(x ?? "").replace(
      /[&<>"']/g,
      (c) =>
        ({
          "&": "&amp;",
          "<": "&lt;",
          ">": "&gt;",
          '"': "&quot;",
          "'": "&#39;",
        })[c],
    );
  const filledIcons = new Set([
    "library",
    "updates",
    "history",
    "browse",
    "authors",
    "more",
    "settings",
    "swap",
  ]);
  function icon(name) {
    const key = name === "random" ? "swap" : name;
    const mode = filledIcons.has(key) ? "filled" : "stroked";
    return `<svg class="${mode}" viewBox="0 0 24 24" aria-hidden="true"><path d="${LibraryIcons[key] || LibraryIcons.more}"></path></svg>`;
  }
  const button = (id, label, ico, extra = "") =>
    `<button data-testid="${id}" data-action="${id}" class="${ico ? "icon" : ""}" aria-label="${esc(label)}" title="${esc(label)}" ${extra}>${ico ? icon(ico) : esc(label)}</button>`;
  const catName = () =>
    s.categories.find((c) => c.id === s.category)?.name || "默认";
  const book = () => s.books.find((b) => b.id === s.bookId);
  const currentSort = () => M.sortState(s).key;
  const activeFilters = () =>
    Object.values(s.filters).some(Boolean) || s.downloadOnly;
  function cover(b) {
    const colors = ["#415a81", "#725568", "#596850", "#74613f", "#506774"];
    return `<div class="cover ${b.custom ? "custom-cover" : ""}" style="--cover-color:${colors[(b.id.charCodeAt(0) + (b.custom ? b.customRevision : b.cover || 0)) % colors.length]}"><small>MIHON · LIBRARY</small><span class="cover-symbol">${esc(b.title.split(" · ")[1]?.slice(0, 2) || "故事")}</span><span class="cover-tag">${b.custom ? `自定义封面 · ${b.customRevision}` : `源封面 · ${b.cover + 1}`}</span></div>`;
  }
  function nav() {
    return `<nav class="navigation" aria-label="主导航">${[["library", "书架"], ["updates", "更新"], ["history", "历史"], ["browse", "浏览"], ...(s.platform === "windows" ? [["authors", "作者"]] : []), ["more", "更多"]].map(([id, name]) => `<button data-action="nav-${id}" data-testid="nav-${id}" class="${s.route === "library" && id === "library" ? "active" : ""}">${icon(id)}<span>${name}</span></button>`).join("")}</nav>`;
  }
  function summary() {
    if (!s.job)
      return s.waiting ? `<div class="job-bar">${esc(s.waiting)}</div>` : "";
    const vals = Object.values(s.job.results),
      count = (status) => vals.filter((r) => r.status === status).length;
    const status = {
      running: "正在更新",
      waiting: "等待设备条件",
      done: "更新完成",
      cancelled: "更新已取消",
      legacy: "旧更新等待确认",
      unrecoverable: "旧更新范围未知",
      interrupted: "更新中断，已保留目录",
    }[s.job.status];
    return `<div class="job-bar"><span>${status} · 已处理 ${vals.length}/${s.job.ids.length} · 成功 ${count("success")}，失败 ${count("failed")}，跳过 ${count("skipped")}</span><progress value="${vals.length}" max="${s.job.ids.length || 1}"></progress>${button("update-details", "查看详情")}<span>${esc(s.waiting)}</span></div>`;
  }
  function library() {
    const cards = M.visible(s),
      columns = innerWidth > innerHeight ? s.prefs.columns : s.prefs.portrait;
    const toolbar =
      s.query !== null
        ? `${button("search-close", "关闭搜索", "back")}<input aria-label="搜索书架" data-testid="library-query" id="query" placeholder="搜索书架" value="${esc(s.query)}">`
        : `<h1>书架</h1>`;
    const categories =
      s.prefs.tabs && (s.categories.length > 1 || s.categories[0]?.id !== 0)
        ? `<div class="categories" aria-label="${categoryShortcut}">${s.categories.map((c) => `<button data-testid="category-${c.id}" data-action="category" data-id="${c.id}" class="${c.id === s.category ? "active" : ""}" title="${categoryShortcut}">${esc(c.name)}${s.prefs.counts ? " " + s.books.filter((b) => b.categories.includes(c.id)).length : ""}</button>`).join("")}</div>`
        : "";
    const selection = s.selected.length
      ? `<div class="selection"><strong data-testid="selection-count">已选 ${s.selected.length}</strong>${button("select-close", "退出选择", "close")}${button("select-all", "全选")}${button("select-invert", "反选")}${button("batch-category", "分类")}${button("batch-download", "下载")}${button("batch-delete", "取消收藏")}</div>`
      : "";
    let content = cards
      .map(
        (b) =>
          `<article class="book ${s.selected.includes(b.id) ? "selected" : ""}" data-book="${b.id}"><button class="manga-hit" data-action="manga" data-id="${b.id}" data-testid="manga-${b.id}" aria-label="${esc(b.title)}" aria-pressed="${s.selected.includes(b.id)}">${cover(b)}<span class="book-title">${esc(b.title)}</span></button><div class="badges">${s.prefs.unreadBadge && b.unread ? `<span class="badge" data-testid="unread-${b.id}">${b.unread}</span>` : ""}${s.prefs.downloadBadge ? `<span class="badge">↓${b.chapters.filter((c) => c.download).length}</span>` : ""}${s.prefs.localBadge && b.local ? '<span class="badge">本地</span>' : ""}${s.prefs.languageBadge ? '<span class="badge">中</span>' : ""}</div>${s.prefs.continueRead && b.unread ? `<button class="continue" data-action="continue" data-id="${b.id}" data-testid="continue-${b.id}" aria-label="继续阅读 ${esc(b.title)}">${icon("play")}</button>` : ""}<div class="score">${b.tracks.length ? M.score(b).toFixed(1) + " / 10" : "未评分"}</div></article>`,
      )
      .join("");
    if (s.loading) content = '<div class="empty">正在载入书架…</div>';
    else if (s.loadError)
      content = `<div class="empty">书架载入失败${button("load-retry", "重试")}</div>`;
    else if (!cards.length)
      content =
        '<div class="empty">没有符合条件的作品<br><small>可以清除搜索或筛选，也可以添加本地示例收藏。</small></div>';
    return `<header class="bar">${toolbar}<div class="actions">${button("sync-open", "同步", "sync")}${button("search-open", "搜索书架", "search")}${button("panel-open", "筛选、排序与显示", "filter")}${button("random-open", "随机打开", "random")}${button("category-open", "分类管理", "category")}${button("settings-open", "书架设置", "settings")}${button("refresh", "刷新当前分类", "refresh")}</div></header>${selection}${categories}${
      activeFilters()
        ? '<div class="filter-notice" data-testid="filter-active">筛选已生效 · ' +
          Object.entries(s.filters)
            .filter(([, v]) => v)
            .map(([k, v]) =>
              esc((filterNames[k] || k) + (v === 1 ? "：包含" : "：排除")),
            )
            .join("、") +
          (s.downloadOnly ? " · 仅下载" : "") +
          "</div>"
        : ""
    }${summary()}<div class="scroll-wrap"><div class="wheel-hint" data-testid="wheel-hint"></div><div tabindex="0" class="scroll-content" data-scroll-key="${scrollKey()}" data-testid="library-scroll"><div class="books ${s.prefs.layout}" ${columns ? `style="--columns:${columns}"` : ""}>${content}</div></div></div>`;
  }
  function detail() {
    const b = book();
    if (!b) {
      s.route = "library";
      return library();
    }
    return `<header class="bar">${button("detail-back", "返回书架", "back")}<h1>作品详情</h1>${button("detail-refresh", "刷新目录", "refresh")}</header>${summary()}<div class="detail"><div class="hero">${cover(b)}<div class="hero-info"><h2>${esc(b.title)}</h2><p>${esc(b.source)} · ${b.complete ? "已完结" : "连载中"}</p><p>${esc(b.description || "来自本地样本的作品简介。标题受保护，更新不会改写。")}</p><p>${b.chapters.length} 话 · ${b.unread} 话未读 · 更新 ${b.updated || 0} 次</p>${button("cover-replace", "替换自定义封面")}${button("cover-delete", "删除自定义封面")}</div></div>${b.unread ? button("detail-continue", "继续阅读") : ""}<h3>章节目录</h3>${b.chapters.map((c) => `<div class="chapter" data-testid="chapter-${c.id}"><button data-action="chapter" data-id="${c.id}">${esc(c.name)}<small>${esc(c.url)} · ${c.read ? "已读" : "未读"} · 第 ${c.page} 页</small><small>${c.bookmark ? "已加书签" : "无书签"} · ${c.download ? "已下载 " + esc(c.download) : "未下载"} · 配对 ${esc(c.id)}</small></button>${button("bookmark-" + c.id, c.bookmark ? "取消书签" : "书签")}</div>`).join("")}${b.detachedDownloads?.length ? `<p>目录已移除，但保留本地下载：${esc(b.detachedDownloads.join("、"))}</p>` : ""}</div>`;
  }
  function readerView() {
    const b = book(),
      c = b.chapters.find((c) => c.id === reader);
    return `<header class="bar">${button("reader-back", "返回详情", "back")}<h1>阅读预览</h1></header><div class="reader"><h2>${esc(b.title)}</h2><p>${esc(c.name)} · 第 ${c.page + 1} 页</p><div class="reader-page">本地阅读预览<br><small>不连接真实漫画或阅读引擎</small></div>${button("reader-next", "下一页")}${button("reader-finish", "标记本话已读")}</div>`;
  }
  function render() {
    const old = document.querySelector('[data-testid="library-scroll"]');
    s.positions ||= {};
    if (old) {
      s.scroll[old.dataset.scrollKey] = old.scrollTop;
      const top = old.getBoundingClientRect().top;
      const first = [...old.querySelectorAll(".book")].find(
        (card) => card.getBoundingClientRect().bottom > top,
      );
      if (first)
        s.positions[old.dataset.scrollKey] = {
          id: first.dataset.book,
          offset: first.getBoundingClientRect().top - top,
        };
    }
    const sheetScroll = document.querySelector(".sheet-body")?.scrollTop || 0;
    if (modal === "panel") panelScroll[panelTab] = sheetScroll;
    const focus = document.activeElement?.getAttribute("data-testid");
    app.innerHTML = `<div class="platform-bar"><span>${s.platform === "windows" ? "Mihon Desktop" : "9:41"}</span><span>${s.platform === "windows" ? "—　□　×" : "●　▰"}</span></div><div class="app-content" id="content" ${modal ? "inert" : ""}>${s.route === "reader" ? readerView() : s.route === "detail" ? detail() : library()}${nav()}<div class="status" role="status" data-testid="notice">${esc(s.notice)}</div></div><div id="modal-root"></div>`;
    const scroll = document.querySelector('[data-testid="library-scroll"]');
    if (scroll) {
      scroll.scrollTop = s.scroll[scrollKey()] || 0;
      const anchor = s.positions[scrollKey()];
      const card =
        anchor &&
        [...scroll.querySelectorAll(".book")].find(
          (card) => card.dataset.book === anchor.id,
        );
      if (card) {
        const box = card.getBoundingClientRect();
        const offset = Math.max(anchor.offset, -box.height + 1);
        scroll.scrollTop +=
          box.top - scroll.getBoundingClientRect().top - offset;
      }
      scroll.addEventListener("wheel", onWheel, { passive: false });
      scroll.addEventListener("scroll", () => {
        if (scroll.scrollTop > 0) resetWheel();
      });
    }
    if (modal) {
      renderModal(false);
      document.querySelector(".sheet-body").scrollTop = sheetScroll;
    }
    if (focus && document.hasFocus())
      document
        .querySelector(`[data-testid="${focus}"]`)
        ?.focus({ preventScroll: true });
  }
  const scrollKey = () =>
    `${s.category}:${s.prefs.layout === "list" ? "list" : "grid"}`;
  const filterNames = {
    download: "已下载",
    unread: "未读",
    started: "已开始",
    bookmark: "有书签",
    complete: "已完结",
    due: "预计更新期",
    custom: "自定义更新周期",
    AniList: "AniList",
    MyAnimeList: "MyAnimeList",
  };
  const prefLabels = {
    unreadBadge: "未读数角标",
    downloadBadge: "下载数角标",
    localBadge: "本地作品角标",
    languageBadge: "语言角标",
    continueRead: "继续阅读按钮",
    tabs: "显示分类标签",
    counts: "显示分类数量",
    perCategory: "按分类设置排序",
    wifi: "仅 Wi-Fi",
    unmetered: "仅非计量网络",
    power: "接通电源 / 充电",
    caughtUp: "仅更新已追平作品",
    started: "仅更新已开始作品",
    ongoing: "仅更新未完结作品",
    expected: "仅在预计更新期更新",
    metadata: "更新时刷新作品信息",
  };
  const toggle = (key) =>
    `<label class="setting"><span>${prefLabels[key]}</span><input type="checkbox" data-pref="${key}" data-testid="pref-${key}" ${s.prefs[key] ? "checked" : ""} ${key === "perCategory" && s.pendingReset ? "disabled" : ""}></label>`;
  function optionPref(key, label, values) {
    return `<label class="setting"><span>${label}</span><select data-pref="${key}" data-testid="pref-${key}">${values.map(([value, text]) => `<option value="${value}" ${String(s.prefs[key]) === String(value) ? "selected" : ""}>${esc(text)}</option>`).join("")}</select></label>`;
  }
  function panel() {
    if (panelTab === "filter")
      return `<p class="muted">点击依次切换：不指定 → 包含 → 排除</p>${Object.keys(
        filterNames,
      )
        .filter(
          (k) =>
            !["AniList", "MyAnimeList"].includes(k) &&
            (k !== "custom" || s.customPeriod),
        )
        .concat(s.trackerNames)
        .map(
          (k) =>
            `<button class="tri" data-action="filter" data-id="${k}" data-testid="filter-${k}" aria-pressed="${s.filters[k] === 1 ? "true" : s.filters[k] === -1 ? "mixed" : "false"}" ${s.downloadOnly && k === "download" ? "disabled" : ""}><span>${s.trackerNames.length === 1 && s.trackerNames.includes(k) ? "已追踪" : filterNames[k]}</span><em>${s.downloadOnly && k === "download" ? "包含 · 已锁定" : s.filters[k] === 1 ? "包含" : s.filters[k] === -1 ? "排除" : "不指定"}</em></button>`,
        )
        .join(
          "",
        )}<p>追踪服务只显示本地样本中已登录的服务。${s.customPeriod ? "自定义周期筛选已开放。" : "自定义周期筛选尚未开放。"}</p>${button("filter-reset", "清除筛选")}`;
    if (panelTab === "sort")
      return `<p class="muted">${s.prefs.perCategory ? "当前分类：" + catName() : "全部分类共用排序"}</p>${[
        ["title", "标题"],
        ["chapters", "章节数"],
        ["read", "最近阅读"],
        ["updated", "最近更新"],
        ["unread", "未读数"],
        ["latest", "最新章节"],
        ["fetched", "获取时间"],
        ["added", "加入时间"],
        ["score", "追踪评分"],
        ["random", "随机"],
      ]
        .map(
          ([id, name]) =>
            `<button class="tri" data-action="sort" data-id="${id}" data-testid="sort-${id}"><span>${name}</span><em>${currentSort() === id ? (id === "random" ? "再次点击重新排列" : M.sortState(s).reverse ? "↓ 降序" : "↑ 升序") : ""}</em></button>`,
        )
        .join("")}`;
    return `<h3>布局</h3><div class="choice">${[
      ["compact", "紧凑网格"],
      ["comfortable", "舒适网格"],
      ["cover-only", "仅封面"],
      ["list", "列表"],
    ]
      .map(
        ([id, name]) =>
          `<button class="${s.prefs.layout === id ? "active" : ""}" data-action="layout" data-id="${id}" data-testid="layout-${id}">${name}</button>`,
      )
      .join("")}</div>${optionPref(
      "columns",
      "横向列数",
      Array.from({ length: 11 }, (_, i) => [i, i ? String(i) : "自动"]),
    )}${optionPref(
      "portrait",
      "纵向列数",
      Array.from({ length: 11 }, (_, i) => [i, i ? String(i) : "自动"]),
    )}<h3>角标与操作</h3>${["downloadBadge", "unreadBadge", "localBadge", "languageBadge", "continueRead", "tabs", "counts"].map(toggle).join("")}`;
  }
  let draftPolicy = {};
  function settings() {
    return `<h3>分类</h3>${optionPref("defaultCategory", "新收藏默认分类", [[-1, "每次询问"], ...s.categories.map((c) => [c.id, c.name])])}${button("add-book", "添加示例收藏")}${button("policy-open", "更新分类：包含 / 排除")}${toggle("perCategory")}${s.pendingReset ? button("reset-retry", "重试完成分类排序清理") : ""}<h3>更新</h3>${optionPref(
      "interval",
      "自动更新周期",
      [
        [0, "关闭"],
        [6, "6 小时"],
        [12, "12 小时"],
        [24, "24 小时"],
        [48, "48 小时"],
        [72, "72 小时"],
        [168, "每周"],
      ],
    )}<p class="muted">自动更新依赖应用保持运行（包括托盘）。完全退出、关机后不会启动。</p><p class="clock">演示时间：第 ${Math.floor(s.now / 86400000)} 天 ${Math.floor(s.now / 3600000) % 24} 时 · ${s.waiting || (s.prefs.interval ? "下次更新距今 " + Math.max(0, Math.ceil((s.nextDue - s.now) / 3600000)) + " 小时" : "自动更新已关闭")}</p><h3>设备条件</h3>${["wifi", "unmetered", "power"].map(toggle).join("")}<h3>智能更新</h3>${["caughtUp", "started", "ongoing", "expected", "metadata"].map(toggle).join("")}<p class="muted">手动更新绕过设备条件，仍遵循智能更新规则。作品标题和自定义封面受到保护。</p>${button("refresh-all", "更新全部书架")}`;
  }
  function results() {
    const j = s.job;
    if (!j) return "<p>暂无更新记录</p>";
    const count = (type) =>
      Object.values(j.results).filter((r) => r.status === type).length;
    return `<div data-testid="update-results"><p>成功 ${count("success")}，失败 ${count("failed")}，跳过 ${count("skipped")} · 未处理 ${j.ids.length - Object.keys(j.results).length}</p><p>${esc(s.waiting)}</p>${j.ids
      .map((id) => {
        const r = j.results[id];
        return `<div class="result ${r?.status || ""}"><strong>${esc(r?.title || s.books.find((b) => b.id === id)?.title || id)}</strong>${r ? { success: "成功", failed: "失败", skipped: "跳过" }[r.status] + " · " + esc(r.reason) : "未处理"}</div>`;
      })
      .join(
        "",
      )}</div>${["running", "waiting"].includes(j.status) ? button("update-cancel", "停止后续更新") : ""}${count("failed") ? button("retry-failed", "重试失败项") : ""}${["cancelled", "interrupted"].includes(j.status) ? button("update-resume", "继续未处理项") : ""}${j.status === "legacy" ? button("update-resume", "继续旧更新") : ""}${j.status === "unrecoverable" ? "<p>旧记录没有确定作品范围，无法恢复。原记录保留，请重新发起更新。</p>" + button("refresh-all", "重新发起全库更新") : ""}`;
  }
  let confirmAction = null,
    confirmText = "",
    confirmSnapshot = [];
  function modalContent() {
    if (modal === "panel") return panel();
    if (modal === "settings") return settings();
    if (modal === "results") return results();
    if (modal === "policy")
      return `<p>作品同时属于包含与排除分类时，排除优先。未指定包含时更新全部。</p>${s.categories.map((c) => `<button class="tri" data-action="policy-cycle" data-id="${c.id}" data-testid="policy-${c.id}"><span>${esc(c.name)}</span><em>${draftPolicy[c.id] === 1 ? "包含" : draftPolicy[c.id] === -1 ? "排除" : "不指定"}</em></button>`).join("")}<div class="choice">${button("policy-save", "确认保存")}${button("modal-cancel", "取消")}</div>`;
    if (modal === "categories")
      return `<p>默认分类不能删除。删除自定义分类会将无分类作品归入默认。</p>${s.categories.map((c) => `<div class="setting"><span>${esc(c.name)}</span>${c.id ? button("delete-category-" + c.id, "删除") : ""}</div>`).join("")}<label class="setting"><span>新分类名称</span><input id="category-name" placeholder="分类名称"></label>${button("category-add", "添加分类")}`;
    if (modal === "choose-category")
      return `<p>选择本次收藏的分类</p>${s.categories.map((c) => button("add-to-" + c.id, c.name)).join("")}`;
    if (modal === "batch-category")
      return `<p>为所选 ${confirmSnapshot.length} 本作品添加分类</p>${s.categories.map((c) => button("batch-to-" + c.id, c.name)).join("")}`;
    if (modal === "confirm")
      return `<p>${esc(confirmText)}</p><div class="choice">${button("confirm-yes", "确认")}${button("modal-cancel", "取消")}</div>`;
    if (modal === "sync")
      return `<p>本地演示未连接同步空间。</p><p>本轮只保留书架同步入口和返回路径，不模拟生产同步协议。</p>${button("sync-local", "检查本地状态")}`;
    return `<p>${esc(modal === "browse" ? "从下方添加一本本地示例作品；不连接真实图源。" : "此入口保留导航上下文。完整内容不在书架交互审阅范围内。")}</p>${modal === "browse" ? button("add-book", "添加示例收藏") : ""}`;
  }
  const modalTitles = {
    panel: "书架选项",
    settings: "书架设置",
    results: "更新详情",
    policy: "更新分类",
    categories: "分类管理",
    "choose-category": "选择分类",
    "batch-category": "修改分类",
    confirm: "确认操作",
    sync: "同步",
    updates: "最近更新",
    history: "阅读历史",
    browse: "浏览",
    authors: "作者",
    more: "更多",
  };
  function renderModal(focus = true) {
    const root = document.querySelector("#modal-root");
    if (!modal) {
      root.innerHTML = "";
      return;
    }
    const focused = document.activeElement?.closest(".sheet")
      ? document.activeElement.getAttribute("data-testid")
      : null;
    const prev = document.querySelector(".sheet-body");
    const oldScroll = prev?.scrollTop || 0;
    root.innerHTML = `<div class="overlay" data-action="overlay"><section class="sheet" role="dialog" aria-modal="true" aria-label="${modalTitles[modal]}"><header class="sheet-head">${modalStack.length ? button("modal-back", "返回", "back") : ""}<h2>${modalTitles[modal]}</h2>${button("modal-close", "关闭", "close")}</header>${
      modal === "panel"
        ? `<div class="tabs" role="tablist">${[
            ["filter", "筛选"],
            ["sort", "排序"],
            ["display", "显示"],
          ]
            .map(
              ([id, name]) =>
                `<button role="tab" aria-selected="${panelTab === id}" class="${panelTab === id ? "active" : ""}" data-action="panel-tab" data-id="${id}" data-testid="panel-tab-${id}">${name}</button>`,
            )
            .join("")}</div>`
        : ""
    }<div class="sheet-body">${modalContent()}<p class="status" role="status">${esc(s.notice)}</p></div></section></div>`;
    document.querySelector(".sheet-body").scrollTop =
      modal === "panel" ? panelScroll[panelTab] || 0 : oldScroll;
    if (focus)
      document
        .querySelector(
          modal === "panel"
            ? `[data-testid="panel-tab-${panelTab}"]`
            : ".sheet button",
        )
        ?.focus();
    else if (focused && document.hasFocus())
      document
        .querySelector(`[data-testid="${focused}"]`)
        ?.focus({ preventScroll: true });
  }
  function openModal(name, nested = false) {
    resetWheel();
    if (modal === name) {
      document.querySelector(".sheet button")?.focus();
      return;
    }
    if (nested) modalStack.push(modal);
    else {
      modalStack = [];
      trigger = document.activeElement?.getAttribute("data-testid");
      panelTab = "filter";
      panelScroll = {};
    }
    modal = name;
    document.querySelector("#content").inert = true;
    renderModal();
  }
  function closeModal(all = false) {
    if (!all && modalStack.length) {
      modal = modalStack.pop();
      renderModal();
      return;
    }
    modal = null;
    modalStack = [];
    s.notice = "";
    document.querySelector("#modal-root").innerHTML = "";
    document.querySelector("#content").inert = false;
    const target = trigger;
    render();
    (
      (target && document.querySelector(`[data-testid="${target}"]`)) ||
      document.querySelector('[data-testid="library-scroll"]')
    )?.focus({ preventScroll: true });
  }
  function confirm(text, action) {
    confirmText = text;
    confirmAction = action;
    openModal("confirm", true);
  }
  function changeCategory(id) {
    const scroll = document.querySelector('[data-testid="library-scroll"]');
    if (scroll) s.scroll[scrollKey()] = scroll.scrollTop;
    resetWheel();
    s.category = id;
    render();
    s.notice = "当前分类：" + catName();
    document.querySelector('[data-testid="notice"]').textContent = s.notice;
  }
  function continueBook(id) {
    if (s.selected.length) {
      M.select(s, id, { ctrl: true });
      render();
      return;
    }
    s.bookId = id;
    const c = book()?.chapters.find((c) => !c.read);
    if (c) {
      reader = c.id;
      s.route = "reader";
      resetWheel();
      render();
    }
  }
  function addBook(category) {
    const base = structuredClone(M.create().books[0]);
    base.id = "new-" + (s.books.length + 1);
    base.title = "新收藏 · 本地故事 " + (s.books.length + 1);
    base.categories = [category];
    base.chapters = [];
    base.unread = 0;
    s.books.push(base);
    s.notice =
      "已添加到「" +
      (s.categories.find((c) => c.id === category)?.name || "默认") +
      "」";
    closeModal();
    render();
  }
  app.addEventListener("click", (e) => {
    const el = e.target.closest("[data-action]");
    if (!el) return;
    const action = el.dataset.action,
      id = el.dataset.id;
    if (action === "overlay") {
      if (e.target === el) closeModal(true);
      return;
    }
    if (action === "manga") {
      if (suppressClick === id) {
        suppressClick = false;
        return;
      }
      suppressClick = false;
      if (e.altKey || e.button !== 0) return;
      resetWheel();
      M.select(s, id, { ctrl: e.ctrlKey, shift: e.shiftKey, alt: e.altKey });
      render();
      return;
    }
    if (action === "continue") {
      continueBook(id);
      return;
    }
    if (action === "panel-open" || action === "nav-library") {
      if (s.route === "library") openModal("panel");
      else {
        s.route = "library";
        reader = null;
        render();
      }
      return;
    }
    if (action === "panel-tab") {
      panelScroll[panelTab] = document.querySelector(".sheet-body").scrollTop;
      panelTab = id;
      renderModal();
      return;
    }
    if (action === "modal-close") {
      closeModal(true);
      return;
    }
    if (action === "modal-back" || action === "modal-cancel") {
      closeModal();
      return;
    }
    if (action === "settings-open") {
      openModal("settings");
      return;
    }
    if (action === "sync-open") {
      openModal("sync");
      return;
    }
    if (action === "category-open") {
      openModal("categories");
      return;
    }
    if (action === "search-open") {
      s.query = "";
      resetWheel();
      render();
      document.querySelector("#query").focus();
      return;
    }
    if (action === "search-close") {
      s.query = null;
      resetWheel();
      render();
      return;
    }
    if (action === "category") {
      changeCategory(Number(id));
      return;
    }
    if (action === "filter") {
      const old = s.filters[id] || 0;
      if (s.failSave) {
        s.failSave = false;
        s.notice = "保存失败，筛选已恢复原值";
      } else {
        s.filters[id] = old === 0 ? 1 : old === 1 ? -1 : 0;
        s.notice = "筛选已保存";
      }
      resetWheel();
      render();
      document.querySelector(`[data-testid="filter-${id}"]`)?.focus();
      return;
    }
    if (action === "filter-reset") {
      s.filters = {};
      render();
      return;
    }
    if (action === "layout") {
      M.save(s, "layout", id);
      render();
      return;
    }
    if (action === "sort") {
      M.setSort(s, id);
      render();
      return;
    }
    if (action === "random-open") {
      const list = M.visible(s);
      if (list.length) {
        s.bookId = list[Math.floor(Math.random() * list.length)].id;
        s.selected = [];
        s.anchor = null;
        s.route = "detail";
        render();
      }
      return;
    }
    if (action === "select-close") {
      s.selected = [];
      s.anchor = null;
      render();
      return;
    }
    if (action === "select-all" || action === "select-invert") {
      const set = new Set(s.selected);
      M.visible(s).forEach((b) =>
        action === "select-invert" && set.has(b.id)
          ? set.delete(b.id)
          : set.add(b.id),
      );
      s.selected = [...set];
      s.anchor = null;
      render();
      return;
    }
    if (action === "batch-delete") {
      confirmSnapshot = [...s.selected];
      confirm(
        `取消收藏所选 ${confirmSnapshot.length} 本作品？不会删除已下载文件。`,
        () => {
          s.books = s.books.filter((b) => !confirmSnapshot.includes(b.id));
          s.selected = [];
          s.anchor = null;
          s.notice = "已取消收藏";
        },
      );
      return;
    }
    if (action === "batch-category") {
      confirmSnapshot = [...s.selected];
      openModal("batch-category");
      return;
    }
    if (action.startsWith("batch-to-")) {
      const c = Number(action.slice(9));
      s.books
        .filter((b) => confirmSnapshot.includes(b.id))
        .forEach((b) => (b.categories = [...new Set([...b.categories, c])]));
      closeModal();
      s.notice = "已添加分类";
      render();
      return;
    }
    if (action === "batch-download") {
      s.books
        .filter((b) => s.selected.includes(b.id))
        .forEach((b) =>
          b.chapters.forEach(
            (c) => (c.download ||= b.id + "-file-" + c.number),
          ),
        );
      s.notice = "所选作品已加入本地下载样本";
      render();
      return;
    }
    if (action === "detail-back") {
      s.route = "library";
      render();
      document
        .querySelector(`[data-testid="manga-${s.bookId}"]`)
        ?.focus({ preventScroll: true });
      return;
    }
    if (action === "reader-back") {
      s.route = "detail";
      reader = null;
      render();
      return;
    }
    if (action === "detail-continue") {
      continueBook(s.bookId);
      return;
    }
    if (action === "chapter") {
      reader = id;
      s.route = "reader";
      render();
      return;
    }
    if (action === "reader-next" || action === "reader-finish") {
      const c = book().chapters.find((c) => c.id === reader);
      if (action === "reader-next") c.page++;
      else {
        c.read = true;
        book().unread = book().chapters.filter((x) => !x.read).length;
        s.route = "detail";
      }
      render();
      return;
    }
    if (action.startsWith("bookmark-")) {
      const c = book().chapters.find((c) => c.id === action.slice(9));
      c.bookmark = !c.bookmark;
      render();
      return;
    }
    if (action === "cover-replace") {
      book().custom = true;
      book().customRevision = (book().customRevision || 0) + 1;
      s.notice = "已使用本地自定义封面样本";
      render();
      return;
    }
    if (action === "cover-delete") {
      book().custom = false;
      s.notice = "已恢复最新源封面";
      render();
      return;
    }
    if (action === "cover-source") {
      book().cover++;
      s.notice = "同地址源封面已更新；自定义封面优先";
      render();
      return;
    }
    if (
      action === "refresh" ||
      action === "refresh-all" ||
      action === "detail-refresh"
    ) {
      M.start(
        s,
        action === "refresh-all" ? "all" : "category",
        action === "detail-refresh" ? [s.bookId] : undefined,
      );
      if (modal) closeModal(true);
      resetWheel();
      render();
      return;
    }
    if (action === "update-details") {
      openModal("results");
      return;
    }
    if (action === "retry-failed") {
      M.retry(s);
      render();
      return;
    }
    if (action === "update-cancel") {
      M.cancel(s);
      render();
      return;
    }
    if (action === "update-resume") {
      M.resume(s);
      render();
      return;
    }
    if (action === "policy-open") {
      draftPolicy = structuredClone(s.prefs.categoryPolicy);
      openModal("policy", true);
      return;
    }
    if (action === "policy-cycle") {
      draftPolicy[id] =
        draftPolicy[id] === 1 ? -1 : draftPolicy[id] === -1 ? 0 : 1;
      renderModal();
      return;
    }
    if (action === "policy-save") {
      if (M.save(s, "categoryPolicy", structuredClone(draftPolicy)))
        closeModal();
      else renderModal();
      return;
    }
    if (action === "reset-retry") {
      M.save(s, "perCategory", false);
      render();
      return;
    }
    if (action === "add-book") {
      if (s.prefs.defaultCategory === -1) openModal("choose-category", true);
      else addBook(s.prefs.defaultCategory);
      return;
    }
    if (action.startsWith("add-to-")) {
      addBook(Number(action.slice(7)));
      return;
    }
    if (action === "category-add") {
      const name = document.querySelector("#category-name").value.trim();
      if (name) {
        s.categories.push({
          id: Math.max(...s.categories.map((c) => c.id)) + 1,
          name,
        });
        s.notice = "分类已添加";
        render();
      }
      return;
    }
    if (action.startsWith("delete-category-")) {
      const c = Number(action.slice(16));
      confirm("删除此分类？作品保留，无其他分类的作品移至默认分类。", () => {
        s.categories = s.categories.filter((x) => x.id !== c);
        if (!s.categories.some((x) => x.id === 0))
          s.categories.unshift({ id: 0, name: "默认" });
        s.books.forEach((b) => {
          b.categories = b.categories.filter((x) => x !== c);
          if (!b.categories.length) b.categories = [0];
        });
        if (s.prefs.defaultCategory === c) s.prefs.defaultCategory = -1;
        delete s.prefs.categoryPolicy[c];
        delete s.categorySort[c];
        delete s.categoryReverse[c];
        if (s.category === c) s.category = 0;
        s.notice = "分类已删除，默认收藏设置已回退";
      });
      return;
    }
    if (action === "confirm-yes") {
      const run = confirmAction;
      confirmAction = null;
      closeModal();
      run?.();
      render();
      return;
    }
    if (action === "sync-local") {
      s.notice = "本地样本状态已检查；未发送任何数据";
      renderModal();
      return;
    }
    if (action === "load-retry") {
      s.loading = false;
      s.loadError = false;
      s.notice = "书架已重新载入";
      render();
      return;
    }
    if (action.startsWith("nav-")) {
      openModal(action.slice(4));
    }
  });
  app.addEventListener("input", (e) => {
    if (e.target.id === "query") {
      s.query = e.target.value;
      const pos = e.target.selectionStart;
      resetWheel();
      render();
      document.querySelector("#query").focus();
      document.querySelector("#query").setSelectionRange(pos, pos);
    }
  });
  app.addEventListener("change", (e) => {
    const key = e.target.dataset.pref;
    if (!key) return;
    const value =
      e.target.type === "checkbox" ? e.target.checked : Number(e.target.value);
    const focus = e.target.dataset.testid;
    M.save(s, key, value);
    render();
    document.querySelector(`[data-testid="${focus}"]`)?.focus();
  });
  app.addEventListener("pointerdown", (e) => {
    const el = e.target.closest('[data-action="manga"]');
    if (!el || e.button !== 0 || e.altKey) return;
    const start = { x: e.clientX, y: e.clientY, id: el.dataset.id };
    hold = {
      ...start,
      timer: setTimeout(() => {
        M.select(s, start.id, { long: true });
        suppressClick = start.id;
        setTimeout(() => (suppressClick = false), 600);
        hold = null;
        resetWheel();
        render();
      }, 500),
    };
  });
  app.addEventListener("pointermove", (e) => {
    if (hold && Math.hypot(e.clientX - hold.x, e.clientY - hold.y) > 8) {
      clearTimeout(hold.timer);
      hold = null;
    }
  });
  ["pointerup", "pointercancel"].forEach((type) =>
    app.addEventListener(type, () => {
      if (hold) clearTimeout(hold.timer);
      hold = null;
    }),
  );
  app.addEventListener("contextmenu", (e) => {
    if (e.target.closest('[data-action="manga"]')) {
      e.preventDefault();
      if (hold) clearTimeout(hold.timer);
      hold = null;
      s.notice = s.selected.length
        ? "多选中：单击增减选择，Shift 选择范围"
        : "单击打开作品，Ctrl 点击进入多选";
      document.querySelector('[data-testid="notice"]').textContent = s.notice;
    }
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      e.preventDefault();
      if (modal) {
        closeModal();
        return;
      }
      if (s.selected.length) {
        s.selected = [];
        s.anchor = null;
        render();
        return;
      }
      if (s.route === "reader") {
        s.route = "detail";
        render();
        return;
      }
      if (s.route === "detail") {
        s.route = "library";
        render();
        return;
      }
      if (s.query !== null) {
        s.query = null;
        render();
      }
      return;
    }
    if (modal && e.key === "Tab") {
      const nodes = [
        ...document.querySelectorAll(
          ".sheet button:not(:disabled),.sheet input:not(:disabled),.sheet select:not(:disabled)",
        ),
      ];
      const first = nodes[0],
        last = nodes.at(-1);
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
      return;
    }
  });
  document.addEventListener("compositionstart", () => {
    composing = true;
  });
  document.addEventListener("compositionend", () => {
    composing = false;
  });
  document.addEventListener("keyup", (e) => {
    if (e.key === "Control") categoryWheel = { last: -Infinity, direction: 0 };
  });
  function resetWheel() {
    wheel = { phase: "idle", distance: 0, last: 0, armed: 0, second: 0 };
    const hint = document.querySelector('[data-testid="wheel-hint"]');
    if (hint) hint.textContent = "";
  }
  function onWheel(e) {
    const now = performance.now(),
      scroll = e.currentTarget;
    if (e.ctrlKey) {
      if (["running", "waiting"].includes(s.job?.status) || now < cooldownUntil)
        cooldownUntil = now + 800;
      resetWheel();
      const editing =
        document.activeElement?.matches("input, textarea, select") ||
        document.activeElement?.isContentEditable;
      if (
        s.platform !== "windows" || s.route !== "library" || modal ||
        editing || composing || e.altKey || e.shiftKey || !e.deltaY ||
        Math.abs(e.deltaX) > Math.abs(e.deltaY)
      ) return;
      // Consume only the library category gesture; other contexts retain browser behavior.
      e.preventDefault();
      const direction = Math.sign(e.deltaY);
      const sameBurst =
        categoryWheel.direction === direction &&
        now - categoryWheel.last < CATEGORY_WHEEL_GAP_MS;
      categoryWheel = { last: now, direction };
      if (sameBurst) return;
      const index = s.categories.findIndex((category) => category.id === s.category);
      const next = index + direction;
      if (index >= 0 && next >= 0 && next < s.categories.length)
        changeCategory(s.categories[next].id);
      return;
    }
    categoryWheel = { last: -Infinity, direction: 0 };
    // Cooldown belongs to the task, not to the cancellable gesture hint.
    // Every wheel direction extends the required quiet interval.
    if (s.job && ["running", "waiting"].includes(s.job.status)) {
      cooldownUntil = now + 800;
      resetWheel();
      return;
    }
    if (now < cooldownUntil) {
      cooldownUntil = now + 800;
      resetWheel();
      return;
    }
    if (
      e.ctrlKey ||
      e.altKey ||
      e.shiftKey ||
      Math.abs(e.deltaX) > Math.abs(e.deltaY) ||
      e.deltaY >= 0 ||
      scroll.scrollTop > 0 ||
      modal ||
      s.selected.length ||
      s.loading ||
      s.loadError ||
      !M.candidates(s, "category").length
    ) {
      resetWheel();
      return;
    }
    if (wheel.phase === "cooldown") resetWheel();
    if (
      now - wheel.last > 3000 ||
      (wheel.phase === "armed" && now - wheel.armed > 3000)
    )
      resetWheel();
    const amount = Math.min(
      100,
      Math.abs(e.deltaY) *
        (e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? scroll.clientHeight : 1),
    );
    const gap = now - wheel.last;
    wheel.last = now;
    const hint = document.querySelector('[data-testid="wheel-hint"]');
    if (wheel.phase === "idle") wheel.phase = "hinting";
    if (wheel.phase === "hinting") {
      wheel.distance += amount;
      hint.textContent =
        "继续向上滚动，准备刷新当前分类 · " +
        Math.min(100, Math.round((wheel.distance / 80) * 100)) +
        "%";
      if (wheel.distance >= 80) {
        wheel.phase = "armed";
        wheel.armed = now;
        hint.textContent = "再次向上滚动，刷新「" + catName() + "」";
      }
      return;
    }
    if (
      wheel.phase === "armed" &&
      now - wheel.armed >= 300 &&
      (gap >= 400 || wheel.second > 0)
    ) {
      wheel.second += amount;
      if (wheel.second >= 48) {
        M.start(s, "category");
        resetWheel();
        wheel.phase = "cooldown";
        render();
      }
    }
  }
  window.addEventListener("blur", () => {
    resetWheel();
    categoryWheel = { last: -Infinity, direction: 0 };
    composing = false;
  });
  setInterval(() => {
    if (
      ["hinting", "armed"].includes(wheel.phase) &&
      (performance.now() - wheel.last > 3000 ||
        (wheel.phase === "armed" && performance.now() - wheel.armed > 3000))
    )
      resetWheel();
  }, 200);
  setInterval(() => {
    const before = s.job?.status;
    if (s.job && ["running", "waiting"].includes(s.job.status)) {
      const completed = Object.keys(s.job.results).length,
        reason = s.waiting;
      M.step(s);
      if (
        before !== s.job.status ||
        completed !== Object.keys(s.job.results).length ||
        reason !== s.waiting
      ) {
        if (s.job.status === "done") cooldownUntil = performance.now() + 800;
        if (
          s.job.status === "done" &&
          !modal &&
          s.route !== "reader" &&
          !["INPUT", "SELECT", "TEXTAREA"].includes(
            document.activeElement?.tagName,
          )
        )
          render();
        // Update only task presentation: editing, focus, selection and scroll remain owned by this device.
        document
          .querySelectorAll(".job-bar")
          .forEach((el) => (el.outerHTML = summary()));
        if (modal === "results") renderModal(false);
        const notice = document.querySelector('[data-testid="notice"]');
        if (notice) notice.textContent = s.notice;
      }
    } else if (s.prefs.interval) {
      M.tick(s, 0);
      if (before !== s.job?.status) render();
    }
  }, 280);
  function command(name) {
    if (name === "restart") {
      s = M.restart(s);
      modal = null;
      modalStack = [];
      reader = null;
      resetWheel();
    } else if (name === "source-cover") {
      const b = book() || s.books[0];
      if (b) b.cover++;
      s.notice = "源封面已更新，自定义封面优先";
    } else if (name === "source-ok") {
      s.source = "ok";
      s.brokenStore = false;
      s.loading = false;
      s.loadError = false;
      s.notice = "图源和更新记录已恢复，可重试";
    } else
      M.tick(
        s,
        name === "advance-hour"
          ? 3600000
          : name === "advance-day"
            ? 86400000
            : name === "rewind"
              ? -3600000
              : 0,
      );
    render();
  }
  function reportDeviceState() {
    if (parent !== window)
      parent.postMessage(
        { libraryDemoState: true, device: { ...s.device } },
        "*",
      );
  }
  function scenario(name) {
    categoryWheel = { last: -Infinity, direction: 0 };
    M.scenario(s, name);
    if (!["save-failure", "sort-interrupted", "broken-store"].includes(name)) {
      const scroll = document.querySelector('[data-testid="library-scroll"]');
      if (scroll) scroll.scrollTop = 0;
      cooldownUntil = -Infinity;
    }
    modal = null;
    modalStack = [];
    reader = null;
    resetWheel();
    render();
    reportDeviceState();
  }
  window.demo = {
    scenario,
    command,
    get state() {
      return s;
    },
  };
  window.addEventListener("message", (e) => {
    const d = e.data;
    if (!d?.libraryDemo || e.source !== parent) return;
    if (d.theme) {
      document.body.classList.toggle("theme-light", d.theme === "light");
      document.body.classList.toggle("theme-dark", d.theme !== "light");
    }
    if (d.font) document.documentElement.style.fontSize = 14 * d.font + "px";
    if (d.scenario) scenario(d.scenario);
    if (d.command) command(d.command);
    if (d.device) {
      Object.assign(s.device, d.device);
      M.tick(s, 0);
      render();
      reportDeviceState();
    }
    if (d.requestDeviceState) reportDeviceState();
  });
  render();
})();
