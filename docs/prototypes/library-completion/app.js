(() => {
  "use strict";
  const M = LibraryModel,
    P = DetailParityModel,
    V = DetailParityView,
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
  let detailMenu = null,
    detailSelected = [],
    detailBookId = null,
    detailHold = null,
    suppressChapterClick = null,
    detailCategoryDraft = [],
    detailIntervalDraft = 0,
    detailNotesDraft = "",
    detailScroll = {},
    detailDescriptionExpanded = false,
    detailCoverScale = 100,
    detailSearchQuery = "",
    detailSearchScope = "",
    detailCreatorMenu = null,
    detailTagDraft = "",
    removeDownloadsDraft = false,
    duplicateBookId = null;
  let chapterSettingsTab = "filter",
    chapterAnchor = null,
    scanlatorDraft = new Set(),
    chapterDefaultApplyExisting = false,
    chapterContextId = null,
    trackingDraft = null,
    migrationDraft = null,
    detailRefreshArmed = false,
    detailRefreshDistance = 0,
    detailRefreshAt = 0,
    detailRefreshError = "";
  let wheel = { phase: "idle", distance: 0, last: 0, armed: 0, second: 0 },
    cooldownUntil = -Infinity;
  const CATEGORY_WHEEL_GAP_MS = 250;
  let categoryWheel = { last: -Infinity, direction: 0 },
    composing = false,
    categoryRenameId = null,
    categoryRenameDraft = "";
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
    "dot",
    "bookmarkFilled",
    "heartFilled",
  ]);
  function icon(name) {
    const key = name === "random" ? "swap" : name;
    const mode = filledIcons.has(key) ? "filled" : "stroked";
    const detailPaths = {
      download: "M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4",
      link: "M10 13a5 5 0 0 0 7.1 0l2-2a5 5 0 0 0-7.1-7.1l-1.1 1.1M14 11a5 5 0 0 0-7.1 0l-2 2a5 5 0 0 0 7.1 7.1l1.1-1.1",
      share: "M18 8a3 3 0 1 0-3-3 3 3 0 0 0 3 3ZM6 15a3 3 0 1 0-3-3 3 3 0 0 0 3 3Zm12 9a3 3 0 1 0-3-3 3 3 0 0 0 3 3ZM8.8 13.5l6.4 5M8.8 10.5l6.4-5",
      check: "M4 12l5 5L20 6",
      edit: "M4 20h4l11-11-4-4L4 16v4zM13 7l4 4",
      bookmark: "M6 3h12v18l-6-4-6 4V3z",
      notes: "M5 3h14v18H5zM8 8h8M8 12h8M8 16h5",
      downloadDone: "M4 16v5h16v-5M12 3v10m-4-4 4 4 4-4m-3 6 2 2 4-4",
      dot: "M12 9a3 3 0 1 0 0 6a3 3 0 0 0 0-6",
      ring: "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18",
      selectAll: "M3 7V3h4M17 3h4v4M21 17v4h-4M7 21H3v-4M7 7h10v10H7z",
      down: "M12 3v16m-6-6 6 6 6-6",
      bookmarkFilled: "M6 3h12v18l-6-4-6 4V3z",
      trash: "M4 7h16M9 7V4h6v3M6 7l1 14h10l1-14M10 11v7m4-7v7",
      doneAll: "M2 12l5 5 4-4M8 12l5 5L22 7",
      heart: "M12 21 3.5 12.5a5.5 5.5 0 0 1 8.5-7 5.5 5.5 0 0 1 8.5 7L12 21z",
      heartFilled: "M12 21 3.5 12.5a5.5 5.5 0 0 1 8.5-7 5.5 5.5 0 0 1 8.5 7L12 21z",
    };
    return `<svg class="${mode}" viewBox="0 0 24 24" aria-hidden="true"><path d="${detailPaths[key] || LibraryIcons[key] || LibraryIcons.more}"></path></svg>`;
  }
  const button = (id, label, ico, extra = "") =>
    `<button data-testid="${id}" data-action="${id}" class="${ico ? "icon" : ""}" aria-label="${esc(label)}" title="${esc(label)}" ${extra}>${ico ? icon(ico) : esc(label)}</button>`;
  const catName = () =>
    s.categories.find((c) => c.id === s.category)?.name || "默认";
  const book = () => s.books.find((b) => b.id === s.bookId);
  function markdownInline(value) {
    return esc(value)
      .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, (_, label, url) =>
        `<a href="#" data-action="detail-description-link" data-id="${url}">${label}</a>`,
      )
      .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/\+\+([^+]+)\+\+/g, "<u>$1</u>")
      .replace(/\*([^*]+)\*/g, "<em>$1</em>");
  }
  function markdown(value) {
    return String(value || "").split("\n").map((line) =>
      line.startsWith("# ")
        ? `<strong class="markdown-heading">${markdownInline(line.slice(2))}</strong>`
        : line.startsWith("- ")
          ? `<div class="markdown-list-item">• ${markdownInline(line.slice(2))}</div>`
          : /^\d+\. /.test(line)
            ? `<div class="markdown-list-item">${markdownInline(line)}</div>`
        : markdownInline(line),
    ).join("<br>");
  }
  function localCopy(label, value) {
    if (navigator.clipboard?.writeText) navigator.clipboard.writeText(value).catch(() => {});
    detailNotice(`${label}已复制（本地演示）`);
  }
  const currentSort = () => M.sortState(s).key;
  const activeFilters = () =>
    Object.values(s.filters).some(Boolean) || s.downloadOnly;
  function cover(b) {
    const colors = ["#415a81", "#725568", "#596850", "#74613f", "#506774"];
    return `<div class="cover ${b.custom ? "custom-cover" : ""}" style="--cover-color:${colors[(b.id.charCodeAt(0) + (b.custom ? b.customRevision : b.cover || 0)) % colors.length]}"><small>MIHON · LIBRARY</small><span class="cover-symbol">${esc(b.title.split(" · ")[1]?.slice(0, 2) || "故事")}</span><span class="cover-tag">${b.custom ? `自定义封面 · ${b.customRevision}` : `源封面 · ${b.cover + 1}`}</span></div>`;
  }
  function nav() {
    return `<nav class="navigation" aria-label="主导航">${[["library", "书架"], ["updates", "更新"], ["history", "历史"], ["browse", "浏览"], ...(s.platform === "windows" ? [["authors", "作者"]] : []), ["more", "更多"]].map(([id, name]) => `<button data-action="nav-${id}" data-testid="nav-${id}" class="${(s.route === "detail" ? "library" : s.route) === id ? "active" : ""}">${icon(id)}<span>${name}</span></button>`).join("")}</nav>`;
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
        ? `<div class="categories" aria-label="${categoryShortcut}">${s.categories.map((c) => `<button data-testid="category-${c.id}" data-action="category" data-id="${c.id}" class="${c.id === s.category ? "active" : ""}" title="${categoryShortcut}">${esc(c.name)}${s.prefs.counts ? " " + s.books.filter((b) => b.favorite !== false && b.categories.includes(c.id)).length : ""}</button>`).join("")}</div>`
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
    return `<header class="bar">${toolbar}<div class="actions">${button("sync-open", "同步", "sync")}${button("search-open", "搜索书架", "search")}${button("panel-open", "筛选、排序与显示", "filter")}${button("random-open", "随机打开", "random")}${button("settings-open", "书架设置", "settings")}${button("refresh", "刷新当前分类", "refresh")}</div></header>${selection}${categories}${
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
  function detailOptions(b) {
    return P.normalizeOptions(b.chapterOptions ||= {
      read: true, unread: true, bookmark: false, download: false,
      scanlators: {}, sort: "source", ascending: false, display: "name",
    });
  }
  function displayedChapters(b) {
    return P.displayedChapters(s, b);
  }
  function detailMenuMarkup(name, contents) {
    return detailMenu === name
      ? `<div class="detail-popup" role="menu" data-testid="detail-menu-${name}">${contents}</div>`
      : "";
  }
  function detailFilters(b) {
    const o = detailOptions(b);
    const check = (id, label, active) =>
      `<button role="menuitemcheckbox" aria-checked="${active}" data-action="detail-option-${id}" data-testid="chapter-filter-${id}"><span>${active ? "✓" : ""}</span>${label}</button>`;
    const sort = [["source", "源顺序"], ["number", "章节号"], ["date", "上传日期"], ["alphabet", "字母顺序"]]
      .map(([id, label]) => `<button role="menuitemradio" aria-checked="${o.sort === id}" data-action="detail-sort-${id}" data-testid="chapter-sort-${id}"><span>${o.sort === id ? (o.ascending ? "↑" : "↓") : ""}</span>${label}</button>`).join("");
    const display = [["name", "标题"], ["number", "章节号"]]
      .map(([id, label]) => `<button role="menuitemradio" aria-checked="${o.display === id}" data-action="detail-display-${id}" data-testid="chapter-display-${id}"><span>${o.display === id ? "✓" : ""}</span>${label}</button>`).join("");
    const scanlators = [...new Set(b.chapters.map((c) => c.scanlator).filter(Boolean))].sort()
      .map((name) => `<button role="menuitemcheckbox" aria-checked="${o.scanlators?.[name] !== false}" data-action="detail-scanlator" data-id="${esc(name)}" data-testid="chapter-scanlator-${esc(name)}"><span>${o.scanlators?.[name] !== false ? "✓" : ""}</span>${esc(name)}</button>`).join("");
    return `<div class="detail-popup-heading">筛选</div>${check("read", "显示已读", o.read)}${check("unread", "显示未读", o.unread)}${check("bookmark", "仅书签", o.bookmark)}${check("download", "仅已下载", o.download)}${scanlators ? `<div class="detail-popup-heading">译制组</div>${scanlators}` : ""}<div class="detail-popup-heading">排序</div>${sort}<div class="detail-popup-heading">显示</div>${display}`;
  }
  function chapterRow(c, b) {
    const o = detailOptions(b);
    const selected = detailSelected.includes(c.id);
    const title = o.display === "number" ? `第 ${c.number} 话` : c.name;
    const progress = c.read ? "已读" : c.page > 0 ? `第 ${c.page + 1} 页，继续阅读` : "未读";
    const downloadState = c.downloadStatus || (c.download ? "downloaded" : "none");
    const download = {
      downloaded: ["chapter-delete-", "删除下载", "downloadDone"],
      error: ["chapter-retry-", "重试下载", "refresh"],
      none: ["chapter-download-", "下载章节", "download"],
    }[downloadState];
    const downloadControl = b.local || c.external ? ""
      : ["queued", "downloading"].includes(downloadState)
      ? `<div class="detail-menu-anchor chapter-progress-anchor"><button class="icon chapter-progress" data-action="chapter-progress" data-id="${esc(c.id)}" data-testid="chapter-progress-${esc(c.id)}" aria-label="${downloadState === "queued" ? "排队中" : "下载中"}" title="${downloadState === "queued" ? "排队中" : "下载中"}">${icon("download")}</button>${detailMenuMarkup("chapter-progress-" + c.id, `<button data-action="chapter-cancel-${esc(c.id)}" data-testid="chapter-cancel-${esc(c.id)}">取消</button>`)}</div>`
      : button(download[0] + c.id, download[1], download[2]);
    return `<div class="chapter-row ${selected ? "selected" : ""}" data-testid="chapter-row-${esc(c.id)}" data-chapter-id="${esc(c.id)}" data-read="${c.read}" data-action="chapter" data-id="${esc(c.id)}" role="button" tabindex="0" aria-pressed="${selected}">
      ${detailSelected.length ? `<input type="checkbox" tabindex="-1" aria-label="选择 ${esc(title)}" ${selected ? "checked" : ""}>` : ""}
      <div class="chapter-main"><span class="chapter-title ${c.read ? "read" : ""}">${esc(title)}</span><small>${c.page && !c.read ? `${c.syncedProgress ? "同步至" : ""}第 ${c.page + 1} 页 · ` : ""}${esc(c.scanlator || "未知译制组")} · ${new Date(c.dateUpload || 0).toLocaleDateString("zh-CN")}${c.external ? " · 外部章节" : ""}</small></div>
      <div class="chapter-trailing">
        ${!detailSelected.length ? button("chapter-bookmark-" + c.id, c.bookmark ? "取消书签" : "添加书签", c.bookmark ? "bookmarkFilled" : "bookmark") : ""}
        ${!detailSelected.length ? downloadControl : ""}
        ${!detailSelected.length ? button("chapter-read-" + c.id, progress, c.read ? "check" : c.page > 0 ? "ring" : "dot") : ""}
      </div>
      ${detailMenu === "chapter-context" && chapterContextId === c.id ? V.chapterContext(c, b) : ""}
    </div>`;
  }
  function chapterListMarkup(chapters, b) {
    let last = null;
    const pieces = [];
    for (const chapter of chapters) {
      if (s.prefs.showChapterGaps && last !== null && Math.abs(last - chapter.number) > 1)
        pieces.push(`<p class="chapter-gap">缺少第 ${Math.min(last, chapter.number) + 1}–${Math.max(last, chapter.number) - 1} 话</p>`);
      pieces.push(chapterRow(chapter, b));
      last = chapter.number;
    }
    if (s.prefs.showChapterGaps && chapters.length && detailOptions(b).ascending === false && last > 1)
      pieces.push(`<p class="chapter-gap">缺少第 1–${last - 1} 话</p>`);
    return pieces.join("");
  }
  function detail() {
    const b = book();
    if (!b) {
      s.route = "library";
      return library();
    }
    if (detailBookId !== b.id) {
      detailSelected = [];
      detailMenu = null;
      detailBookId = b.id;
      chapterAnchor = null;
      detailDescriptionExpanded = false;
    }
    const o = detailOptions(b);
    const chapters = displayedChapters(b);
    const chapterFilterActive = [o.unreadFilter, o.bookmarkFilter, o.downloadFilter].some(Boolean) ||
      Object.values(o.scanlators || {}).some((visible) => visible === false) || s.downloadOnly;
    const visibleIds = chapters.map((chapter) => chapter.id);
    const retained = P.pruneSelection({ ids: detailSelected, anchor: chapterAnchor }, visibleIds);
    detailSelected = retained.ids;
    chapterAnchor = retained.anchor;
    const unread = P.nextReadable(s, b);
    const downloading = [["next-1", "接下来 1 话"], ["next-5", "接下来 5 话"], ["next-10", "接下来 10 话"], ["next-25", "接下来 25 话"], ["unread", "全部未读章节"], ["bookmarked", "全部书签章节"]]
      .map(([id, label]) => `<button role="menuitem" data-testid="detail-download-${id}" data-action="detail-download-${id}">${label}</button>`).join("");
    const readingModes = [["default", "默认"], ["auto", "自动"], ["ltr", "从左到右"], ["rtl", "从右到左"], ["webtoon", "条漫"]]
      .map(([id, label]) => `<button role="menuitemradio" aria-checked="${b.readingMode === label}" data-action="detail-reading-${id}" data-testid="detail-reading-${id}">${label}</button>`).join("");
    const overflow = `<button data-action="detail-refresh" data-testid="detail-refresh" role="menuitem">检查更新</button>${b.favorite ? `<button data-action="detail-categories" data-testid="detail-categories" role="menuitem">编辑分类</button>` : ""}${b.favorite && !b.local && !b.sourceMissing ? `<button data-action="detail-migrate" data-testid="detail-migrate" role="menuitem">迁移图源</button>` : ""}${!b.local && !b.sourceMissing ? `<button data-action="detail-share-link" data-testid="detail-share-link" role="menuitem">分享链接</button>` : ""}<button data-action="detail-notes" data-testid="detail-notes" role="menuitem">笔记</button><button data-action="detail-mark-all" data-testid="detail-mark-all" role="menuitem">全部标为已读</button>`;
    const toolbar = `<header class="bar detail-bar">
      ${button("detail-back", "返回书架", "back")}
      <h1 data-testid="detail-title">${esc(b.title)}</h1>
      <div class="actions" data-testid="detail-toolbar-primary">
        ${detailSelected.length ? `<strong class="detail-selected-title">已选 ${detailSelected.length} 章</strong>${button("detail-select-all", "全选当前可见章节", "selectAll")}${button("detail-select-invert", "反选当前可见章节", "swap")}${button("detail-select-close", "退出章节选择", "close")}` : `<div class="detail-menu-anchor">${button("detail-download-menu", "下载章节", "download", b.local || b.sourceMissing ? "disabled" : "")}${detailMenuMarkup("download", downloading)}</div>`}
        ${button("detail-filter-menu", "筛选、排序和显示章节", "filter", chapterFilterActive ? 'aria-pressed="true"' : "")}
        ${!detailSelected.length ? `<div class="detail-menu-anchor">${button("detail-overflow", "更多作品操作", "more", 'aria-haspopup="menu"')}${detailMenuMarkup("overflow", overflow)}</div>` : ""}
      </div>
    </header>`;
    const creator = (role, name, id) => name ? `<span class="detail-creator detail-menu-anchor">${role} ${button(`detail-${id}`, name)}${detailMenuMarkup(`creator-${id}`, `<button data-action="detail-${id}-search" data-testid="detail-${id}-search">搜索${role}</button><button data-action="detail-${id}-copy" data-testid="detail-${id}-copy">复制${role}</button>`)}</span>` : "";
    const creators = `<div class="detail-creators" data-testid="detail-creators">${creator("作者", b.author, "author")}${creator("画师", b.artist, "artist")}</div>`;
    const tags = `<div class="detail-tags" data-testid="detail-tags">${(b.genre || []).map((tag, i) => `<span class="detail-tag detail-menu-anchor"><button data-action="detail-tag-menu" data-id="${i}" data-testid="detail-tag-${i}" aria-haspopup="menu">${esc(tag)}</button>${detailMenuMarkup(`tag-${i}`, `<button data-action="detail-tag-search" data-id="${esc(tag)}" data-testid="detail-tag-search">搜索标签</button><button data-action="detail-tag-copy" data-id="${esc(tag)}" data-testid="detail-tag-copy">复制标签</button>`)}</span>`).join("")}</div>`;
    const sourceLine = `<p class="detail-source" data-testid="detail-source-line"><span>${esc(b.status || (b.complete ? "已完结" : "连载中"))}</span> · <button data-action="detail-source-entry" data-testid="detail-source-entry">${b.sourceMissing ? "缺失图源" : `来源：${esc(b.source)}`}</button> · ${esc(b.language || "未知语言")}</p>`;
    const description = b.description ? `<div class="detail-description-wrap"><div class="detail-description ${detailDescriptionExpanded ? "expanded" : ""}" data-testid="detail-description">${markdown(b.description)}</div><div class="detail-description-actions">${button("detail-description-toggle", detailDescriptionExpanded ? "收起简介" : "展开简介")}</div></div>` : "";
    const hero = `<div class="hero" data-testid="detail-hero"><div class="detail-cover-wrap"><button class="detail-cover-open" data-action="detail-cover-open" data-testid="detail-cover-open" aria-label="全屏查看封面">${cover(b)}</button></div><div class="hero-info"><div class="detail-title-row"><h2><button data-action="detail-title-search" data-testid="detail-title-search" title="按标题搜索，右键复制">${esc(b.title)}</button></h2></div>${creators}${sourceLine}</div></div>`;
    const trackSummary = b.tracks.length ? `${b.tracks.length} 个追踪` : "追踪";
    const trackDetail = b.tracks.length ? `，第 ${b.tracks[0].progress || 0} 章，${M.score(b).toFixed(1)} 分` : "";
    const actionRow = `<div class="detail-action-row" data-testid="detail-action-row"><button data-testid="detail-library" data-action="detail-library" class="detail-primary-action">${icon(b.favorite ? "heartFilled" : "heart")}<span>${b.favorite ? "已收藏" : "加入书架"}</span></button><button data-testid="detail-fetch-interval" data-action="detail-fetch-interval" class="detail-primary-action" ${b.favorite ? "" : "disabled"}>${icon("history")}<span>${b.fetchInterval ? b.fetchInterval + " 天" : "不适用"}</span></button><button data-action="detail-tracking" data-testid="detail-tracking" class="detail-primary-action" aria-label="追踪：${trackSummary}${trackDetail}">${icon("sync")}<span>${trackSummary}</span></button>${!b.local && !b.sourceMissing ? `<button data-action="detail-open-link" data-testid="detail-open-link" class="detail-primary-action" title="右键复制作品链接">${icon("link")}<span>网页</span></button>` : ""}</div>`;
    const selection = V.batchBar(b, detailSelected, chapters);
    const notes = b.notes ? `<section class="detail-notes" data-testid="detail-notes-inline"><button class="detail-notes-body" data-action="detail-notes-summary" data-testid="detail-notes-summary" aria-label="编辑作品笔记">${markdown(b.notes)}</button></section>` : "";
    const detailSummary = `<section class="detail-summary" data-testid="detail-summary">${notes}${description}${tags}</section>`;
    const totalMissing = Math.max(0, Math.max(0, ...b.chapters.map((chapter) => chapter.number)) - b.chapters.length);
    return `${toolbar}${summary()}<div class="detail" data-testid="detail-scroll" data-book-id="${esc(b.id)}"><div class="detail-pull-tip" data-testid="detail-pull-tip">${detailRefreshArmed ? "再次向上滚动检查更新" : ""}</div><div class="detail-layout" data-testid="detail-layout"><div class="detail-main" data-testid="detail-info-scroll">${hero}${actionRow}${detailRefreshError ? `<p class="detail-error" data-testid="detail-refresh-error">${esc(detailRefreshError)} ${button("detail-refresh-retry", "重试")}</p>` : ""}${detailSummary}<div class="detail-reading-mode"><span>阅读模式</span><div class="detail-menu-anchor"><button data-action="detail-reading-mode" data-testid="detail-reading-mode">${esc(b.readingMode || "默认")}</button>${detailMenuMarkup("reading", readingModes)}</div></div></div><div class="detail-chapters" data-testid="detail-chapter-scroll"><button class="detail-chapter-heading" data-action="detail-filter-menu" data-testid="chapter-count">章节 ${chapters.length}/${b.chapters.length}${s.prefs.showChapterGaps && totalMissing ? ` · 缺 ${totalMissing} 话` : ""}</button><div class="chapter-list">${chapterListMarkup(chapters, b)}</div>${b.detachedDownloads?.length ? `<p class="detail-detached">目录已移除，保留本地下载：${esc(b.detachedDownloads.join("、"))}</p>` : ""}</div></div></div>${unread && !detailSelected.length ? `<button class="detail-fab" data-action="detail-continue" data-testid="detail-continue" data-chapter-id="${esc(unread.id)}">${icon("play")}${b.chapters.some((c) => c.read) || unread.page > 0 ? "继续阅读" : "开始阅读"}</button>` : ""}${selection}`;
  }
  function rootPage() {
    const names = { updates: "更新", history: "历史", browse: "浏览", authors: "作者", more: "更多" };
    const title = names[s.route] || "书架";
    return `<header class="bar"><h1>${title}</h1></header><div class="root-page" data-testid="root-page"><h2>${title}</h2><p>已切换到「${title}」页面。本地样本保留主导航；该页面的完整业务内容不在本次详情审阅范围内。</p></div>`;
  }
  function readerView() {
    const b = book(),
      c = b.chapters.find((c) => c.id === reader);
    return `<header class="bar">${button("reader-back", "返回详情", "back")}<h1>阅读预览</h1></header><div class="reader" data-testid="reader-preview"><h2>${esc(b.title)}</h2><p>${esc(c.name)} · 第 ${c.page + 1} 页 · ${esc(b.readingMode || "默认")}</p><div class="reader-page">${c.external ? "外部章节：在浏览器打开的本地预览" : "本地阅读预览"}<br><small>不连接真实漫画或阅读引擎</small></div>${!c.external ? `${button("reader-next", "下一页")}${button("reader-finish", "标记本话已读")}` : button("reader-back", "返回详情")}</div>`;
  }
  function render() {
    const old = document.querySelector('[data-testid="library-scroll"]');
    const oldDetail = document.querySelector('[data-testid="detail-scroll"]');
    if (oldDetail) detailScroll[oldDetail.dataset.bookId] = {
      whole: oldDetail.scrollTop,
      info: oldDetail.querySelector('[data-testid="detail-info-scroll"]')?.scrollTop || 0,
      chapters: oldDetail.querySelector('[data-testid="detail-chapter-scroll"]')?.scrollTop || 0,
    };
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
    app.innerHTML = `<div class="platform-bar"><span>${s.platform === "windows" ? "Mihon Desktop" : "9:41"}</span><span>${s.platform === "windows" ? "—　□　×" : "●　▰"}</span></div><div class="app-content" id="content" ${modal ? "inert" : ""}>${s.route === "reader" ? readerView() : s.route === "detail" ? detail() : s.route === "library" ? library() : rootPage()}${s.route !== "reader" ? nav() : ""}<div class="status" role="status" data-testid="notice">${esc(s.notice)}</div></div><div id="modal-root"></div>`;
    const newDetail = document.querySelector('[data-testid="detail-scroll"]');
    if (newDetail) {
      const position = detailScroll[newDetail.dataset.bookId] || {};
      newDetail.scrollTop = position.whole || 0;
      newDetail.querySelector('[data-testid="detail-info-scroll"]').scrollTop = position.info || 0;
      const chapters = newDetail.querySelector('[data-testid="detail-chapter-scroll"]');
      chapters.scrollTop = position.chapters || 0;
      newDetail.addEventListener("wheel", onDetailWheel, { passive: false });
      chapters.addEventListener("wheel", onDetailWheel, { passive: false });
    }
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
    downloadUsesVisible: "下载动作遵守当前章节筛选",
    showChapterGaps: "显示缺章提示",
    autoDownloadNew: "更新后自动下载新增章节",
    protectTitle: "刷新时保留自定义标题",
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
    return `<h3>分类</h3>${button("category-open", "分类管理", "category")}${optionPref("defaultCategory", "新收藏默认分类", [[-1, "每次询问"], ...s.categories.map((c) => [c.id, c.name])])}${button("add-book", "添加示例收藏")}${button("policy-open", "更新分类：包含 / 排除")}${toggle("perCategory")}${s.pendingReset ? button("reset-retry", "重试完成分类排序清理") : ""}<h3>更新</h3>${optionPref(
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
    )}<p class="muted">自动更新依赖应用保持运行（包括托盘）。完全退出、关机后不会启动。</p><p class="clock">演示时间：第 ${Math.floor(s.now / 86400000)} 天 ${Math.floor(s.now / 3600000) % 24} 时 · ${s.waiting || (s.prefs.interval ? "下次更新距今 " + Math.max(0, Math.ceil((s.nextDue - s.now) / 3600000)) + " 小时" : "自动更新已关闭")}</p><h3>设备条件</h3>${["wifi", "unmetered", "power"].map(toggle).join("")}<h3>智能更新</h3>${["caughtUp", "started", "ongoing", "expected", "metadata", "protectTitle"].map(toggle).join("")}<h3>详情与章节</h3>${["downloadUsesVisible", "showChapterGaps", "autoDownloadNew"].map(toggle).join("")}<label class="setting"><span>手动标已读时同步追踪</span><select data-detail-pref="trackingOnRead" data-testid="pref-trackingOnRead">${[["ask", "每次询问"], ["auto", "自动更新"], ["off", "关闭"]].map(([id, label]) => `<option value="${id}" ${s.prefs.trackingOnRead === id ? "selected" : ""}>${label}</option>`).join("")}</select></label><p class="muted">手动更新绕过设备条件，仍遵循智能更新规则。自定义封面优先于源封面。</p>${button("refresh-all", "更新全部书架")}`;
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
    confirmSnapshot = [],
    confirmExtra = "";
  function modalContent() {
    if (modal === "detail-settings") return V.chapterSettings(book(), s, chapterSettingsTab);
    if (modal === "detail-scanlators") return V.scanlatorDialog(book(), scanlatorDraft);
    if (modal === "detail-chapter-defaults")
      return `<p>把当前作品的章节筛选、排序和显示保存为新作品默认值。</p><label class="setting"><span>同时应用到已有作品</span><input type="checkbox" data-testid="chapter-default-apply-existing" ${chapterDefaultApplyExisting ? "checked" : ""}></label>${button("chapter-default-save", "保存默认值")}${button("modal-cancel", "取消")}`;
    if (modal === "detail-categories") {
      const categories = s.categories.filter((c) => c.id !== 0);
      if (!categories.length)
        return `<p>尚未创建分类。可以先编辑分类，再为这部作品选择。</p>${button("detail-category-manage", "编辑分类")}`;
      return `<p>为「${esc(book()?.title)}」选择所属分类。不勾选时归入默认分类。</p>${categories.map((c) => `<label class="setting"><span>${esc(c.name)}</span><input type="checkbox" data-detail-category="${c.id}" data-testid="detail-category-${c.id}" ${detailCategoryDraft.includes(c.id) ? "checked" : ""}></label>`).join("")}<div class="choice">${button("detail-category-manage", "编辑")}${button("modal-cancel", "取消")}${button("detail-category-save", "确定")}</div>`;
    }
    if (modal === "detail-interval")
      return `<p>上次检查：${book().lastChecked ? new Date(book().lastChecked).toLocaleString("zh-CN") : "尚未检查"}。预计下次更新由上次检查时间和检查周期决定；这里仅展示本地样本。</p>${[0, 1, 2, 7, 14, 30].map((n) => `<label class="setting"><span>${n ? n + " 天" : "默认"}</span><input type="radio" name="detail-interval" value="${n}" data-testid="detail-interval-${n}" ${detailIntervalDraft === n ? "checked" : ""}></label>`).join("")}<div class="choice">${button("detail-interval-save", "确定")}${button("modal-cancel", "取消")}</div>`;
    if (modal === "detail-tracking") return V.tracking(book(), trackingDraft);
    if (modal === "detail-search") {
      const matches = s.books.filter((b) => b.title.includes(detailSearchQuery) || b.genre?.includes(detailSearchQuery));
      return `<div data-testid="detail-search-preview"><p>${esc(detailSearchScope)}搜索「${esc(detailSearchQuery)}」· 本地样本</p>${matches.length ? matches.map((b) => `<div class="setting">${esc(b.title)}</div>`).join("") : "<p>当前样本没有更多匹配作品。</p>"}</div>`;
    }
    if (modal === "detail-tag-scope")
      return `<p>选择搜索「${esc(detailTagDraft)}」的范围</p>${button("detail-tag-source", "在当前源搜索")}${button("detail-tag-global", "全局搜索")}`;
    if (modal === "detail-duplicate")
      return `<p>书架已有同名且来自同一图源的作品。可先查看已有作品，或继续加入本地样本。</p><div class="choice">${button("detail-duplicate-view", "查看已有作品")}${button("detail-duplicate-continue", "仍然加入")}${button("detail-duplicate-migrate", "去迁移")}</div>`;
    if (modal === "detail-cover-viewer")
      return `<div class="detail-cover-viewer" data-testid="detail-cover-viewer"><div class="detail-cover-image" style="transform:scale(${detailCoverScale / 100})">${cover(book())}</div><div class="detail-cover-controls">${button("detail-cover-zoom-out", "缩小")}${button("detail-cover-zoom-in", "放大")}<span data-testid="detail-cover-scale">${detailCoverScale}%</span>${button("detail-cover-save", "保存封面")}${button("detail-cover-share", "分享封面")}<div class="detail-menu-anchor">${button("detail-cover-menu", "编辑封面", "edit")}${detailMenuMarkup("cover", `<button data-action="cover-replace" data-testid="cover-replace">编辑封面</button><button data-action="cover-delete" data-testid="cover-delete" ${book().custom ? "" : "disabled"}>删除自定义封面</button>`)}</div></div><p class="muted">本地生成封面样本；保存与分享不操作磁盘或系统应用。</p></div>`;
    if (modal === "detail-notes")
      return `<p>编辑「${esc(book().title)}」的作品笔记。</p><div class="detail-notes-tools">${button("detail-notes-bold", "加粗")}${button("detail-notes-italic", "斜体")}${button("detail-notes-underline", "下划线")}${button("detail-notes-bullet", "项目符号")}${button("detail-notes-numbered", "有序列表")}${button("detail-notes-link", "插入链接")}</div><textarea data-testid="detail-notes-input" aria-label="作品笔记" placeholder="支持 Markdown 的本地笔记样本" rows="8">${esc(detailNotesDraft)}</textarea><div class="choice">${button("detail-notes-save", "保存")}${button("detail-notes-cancel", "取消")}</div>`;
    if (modal === "detail-migrate")
      return `<p>选择目标图源。本地样本不请求真实图源。</p>${button("detail-migrate-target", "示例备用图源 · 中文")}${button("modal-cancel", "取消")}`;
    if (modal === "detail-migrate-results") return V.migrationSearch(migrationDraft);
    if (modal === "detail-migrate-confirm") return V.migrationConfirm(book(), migrationDraft);
    if (modal === "detail-information")
      return `<div data-testid="detail-information"><p>${esc(confirmText)}</p><p class="muted">当前仅为本地交互样本；此页面未接入真实外部服务。</p></div>`;
    if (modal === "panel") return panel();
    if (modal === "settings") return settings();
    if (modal === "results") return results();
    if (modal === "policy")
      return `<p>作品同时属于包含与排除分类时，排除优先。未指定包含时更新全部。</p>${s.categories.map((c) => `<button class="tri" data-action="policy-cycle" data-id="${c.id}" data-testid="policy-${c.id}"><span>${esc(c.name)}</span><em>${draftPolicy[c.id] === 1 ? "包含" : draftPolicy[c.id] === -1 ? "排除" : "不指定"}</em></button>`).join("")}<div class="choice">${button("policy-save", "确认保存")}${button("modal-cancel", "取消")}</div>`;
    if (modal === "categories") {
      const categories = s.categories.filter((c) => c.id !== 0);
      return `<p>拖动分类或使用上移、下移调整顺序。默认分类固定在最前，不参与排序。</p>${categories.map((c, index) => `<div class="category-manage-row" data-id="${c.id}" data-testid="category-manage-row-${c.id}"><span class="category-drag" draggable="true" data-category-drag="${c.id}" data-testid="category-drag-${c.id}" aria-label="拖动${esc(c.name)}">↕</span><span class="category-name">${esc(c.name)}</span><div class="category-order-actions"><button data-action="category-up-${c.id}" data-testid="category-up-${c.id}" aria-label="上移${esc(c.name)}" ${index === 0 ? "disabled" : ""}>上移</button><button data-action="category-down-${c.id}" data-testid="category-down-${c.id}" aria-label="下移${esc(c.name)}" ${index === categories.length - 1 ? "disabled" : ""}>下移</button>${button("category-rename-" + c.id, "重命名")}${button("delete-category-" + c.id, "删除")}</div></div>`).join("")}<label class="setting"><span>新分类名称</span><input id="category-name" placeholder="分类名称"></label>${button("category-add", "添加分类")}`;
    }
    if (modal === "category-rename")
      return `<label class="setting"><span>分类名称</span><input data-testid="category-rename-input" aria-label="分类名称" value="${esc(categoryRenameDraft)}"></label><div class="choice">${button("category-rename-save", "保存")}${button("modal-cancel", "取消")}</div>`;
    if (modal === "choose-category")
      return `<p>选择本次收藏的分类</p>${s.categories.map((c) => button("add-to-" + c.id, c.name)).join("")}`;
    if (modal === "batch-category")
      return `<p>为所选 ${confirmSnapshot.length} 本作品添加分类</p>${s.categories.map((c) => button("batch-to-" + c.id, c.name)).join("")}`;
    if (modal === "confirm")
      return `<p data-testid="confirm-text">${esc(confirmText)}</p>${confirmExtra}<div class="choice">${button("confirm-yes", "确认")}${button("modal-cancel", "取消")}</div>`;
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
    "category-rename": "重命名分类",
    "choose-category": "选择分类",
    "batch-category": "修改分类",
    confirm: "确认操作",
    sync: "同步",
    updates: "最近更新",
    history: "阅读历史",
    browse: "浏览",
    authors: "作者",
    more: "更多",
    "detail-categories": "编辑分类",
    "detail-interval": "更新间隔",
    "detail-search": "搜索作品",
    "detail-tag-scope": "搜索标签",
    "detail-duplicate": "发现已有作品",
    "detail-cover-viewer": "查看封面",
    "detail-tracking": "追踪",
    "detail-settings": "章节选项",
    "detail-scanlators": "排除译制组",
    "detail-chapter-defaults": "章节默认选项",
    "detail-notes": "笔记",
    "detail-migrate": "迁移到图源",
    "detail-migrate-results": "选择匹配作品",
    "detail-migrate-confirm": "确认迁移",
    "detail-information": "详情",
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
    root.innerHTML = `<div class="overlay" data-action="overlay"><section class="sheet ${modal === "detail-cover-viewer" ? "cover-sheet" : ""}" role="dialog" aria-modal="true" aria-label="${modalTitles[modal]}"><header class="sheet-head">${modalStack.length ? button("modal-back", "返回", "back") : ""}<h2>${modalTitles[modal]}</h2>${button("modal-close", "关闭", "close")}</header>${
      modal === "panel" || modal === "detail-settings"
        ? `<div class="tabs" role="tablist" ${modal === "detail-settings" ? 'data-testid="chapter-settings-tabs"' : ""}>${[
            ["filter", "筛选"],
            ["sort", "排序"],
            ["display", "显示"],
          ]
            .map(
              ([id, name]) =>
                `<button role="tab" aria-selected="${(modal === "panel" ? panelTab : chapterSettingsTab) === id}" class="${(modal === "panel" ? panelTab : chapterSettingsTab) === id ? "active" : ""}" data-action="${modal === "panel" ? "panel-tab" : "chapter-settings-tab"}" data-id="${id}" data-testid="${modal === "panel" ? "panel-tab" : "chapter-settings-tab"}-${id}">${name}</button>`,
            )
            .join("")}</div>`
        : ""
    }<div class="sheet-body">${modalContent()}<p class="status" role="status">${esc(s.notice)}</p></div></section></div>`;
    document.querySelector(".sheet-body").scrollTop =
      modal === "panel" ? panelScroll[panelTab] || 0 : oldScroll;
    if (focus)
      document
        .querySelector(
          modal === "panel" ? `[data-testid="panel-tab-${panelTab}"]`
            : modal === "detail-settings" ? `[data-testid="chapter-settings-tab-${chapterSettingsTab}"]` : ".sheet button",
        )
        ?.focus();
    else if (focused && document.hasFocus())
      document
        .querySelector(`[data-testid="${focused}"]`)
        ?.focus({ preventScroll: true });
  }
  function openModal(name, nested = false) {
    if (s.route === "detail") cancelDetailPull();
    resetWheel();
    if (modal === name) {
      document.querySelector(".sheet button")?.focus();
      return;
    }
    if (nested && modal) modalStack.push(modal);
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
    if (modal === "detail-cover-viewer") detailMenu = null;
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
  function confirm(text, action, extra = "") {
    confirmText = text;
    confirmAction = action;
    confirmExtra = extra;
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
    if (s.prefs.chapterDefaults) base.chapterOptions = structuredClone(s.prefs.chapterDefaults);
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
  function toggleDetailChapter(id) {
    selectDetailChapter(id, { ctrl: true });
  }
  function selectDetailChapter(id, flags = {}) {
    const visible = book() ? displayedChapters(book()).map((chapter) => chapter.id) : [];
    const result = P.selectChapter({ ids: detailSelected, anchor: chapterAnchor }, visible, id, flags);
    if (result.open) {
      const chapter = book().chapters.find((item) => item.id === result.open);
      if (chapter?.external) {
        reader = chapter.id;
        s.route = "reader";
      } else {
        reader = result.open;
        s.route = "reader";
      }
    } else {
      detailSelected = result.ids;
      chapterAnchor = result.anchor;
    }
    render();
  }
  function detailNotice(message) {
    s.notice = message;
    render();
  }
  function detailInfo(message) {
    confirmText = message;
    openModal("detail-information");
  }
  function detailSearch(query, scope) {
    detailSearchQuery = query;
    detailSearchScope = scope;
    detailMenu = null;
    openModal("detail-search");
  }
  function detailExit() {
    cancelDetailPull();
    s.route = "library";
    detailSelected = [];
    detailMenu = null;
    detailBookId = null;
    render();
    document.querySelector(`[data-testid="manga-${s.bookId}"]`)?.focus({ preventScroll: true });
  }
  function formatDetailNote(prefix, suffix = "", placeholder = "文字") {
    const field = document.querySelector('[data-testid="detail-notes-input"]');
    if (!field) return;
    const start = field.selectionStart, end = field.selectionEnd;
    const selected = field.value.slice(start, end) || placeholder;
    detailNotesDraft = field.value.slice(0, start) + prefix + selected + suffix + field.value.slice(end);
    field.value = detailNotesDraft;
    field.focus();
    field.setSelectionRange(start + prefix.length, start + prefix.length + selected.length);
  }
  function detailDownload(ids) {
    const b = book();
    let changed = 0;
    let skipped = 0;
    const failed = [];
    for (const id of ids) {
      const c = b?.chapters.find((item) => item.id === id);
      if (!c || c.download || c.external || b.local || b.sourceMissing || ["queued", "downloading"].includes(c.downloadStatus)) {
        skipped++;
        continue;
      }
      if (s.failBatch) {
        s.failBatch = false;
        c.downloadStatus = "error";
        failed.push(id);
        continue;
      }
      c.downloadStatus = null;
      c.download = `${b.id}-file-${c.number}`;
      changed++;
    }
    detailSelected = failed;
    chapterAnchor = failed[0] || null;
    detailNotice(`已将 ${changed} 个章节加入本地下载样本，跳过 ${skipped} 个，失败 ${failed.length} 个${b.favorite ? "" : "；可选择加入书架"}${failed.length ? "；失败章节保留选择，可重试" : ""}`);
  }
  function maybeSyncTracking(chapterNumber) {
    const track = book()?.tracks[0];
    if (!track) return;
    const effect = P.readTrackingEffect(s.prefs.trackingOnRead, chapterNumber, track.progress || 0);
    if (effect.kind === "none") return;
    const update = () => {
      track.progress = effect.chapter;
      s.notice = `追踪进度已更新到第 ${effect.chapter} 话（本地模拟）`;
    };
    if (effect.kind === "ask") confirm(`将追踪进度更新到第 ${effect.chapter} 话？取消只保留本地已读状态。`, update);
    else update();
  }
  function cancelDetailPull() {
    detailRefreshArmed = false;
    detailRefreshDistance = 0;
    const tip = document.querySelector('[data-testid="detail-pull-tip"]');
    if (tip) tip.textContent = "";
  }
  function refreshDetail() {
    const current = book();
    cancelDetailPull();
    if (!current || current.sourceMissing) {
      detailRefreshError = "图源缺失：原有章节保留。恢复图源后可重试。";
      detailNotice(detailRefreshError);
      return;
    }
    detailRefreshError = "";
    M.start(s, "category", [current.id]);
    render();
  }
  app.addEventListener("click", (e) => {
    const el = e.target.closest("[data-action]");
    if (!el) return;
    const action = el.dataset.action,
      id = el.dataset.id;
    if (el.closest('[data-testid="detail-menu-overflow"]')) {
      detailMenu = null;
      document.querySelector('[data-testid="detail-overflow"]')?.focus({ preventScroll: true });
    }
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
      openModal("categories", true);
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
    if (action === "detail-cover-menu" || action === "detail-download-menu" || action === "detail-overflow" ||
        action === "detail-reading-mode") {
      const name = action === "detail-cover-menu" ? "cover" :
        action === "detail-download-menu" ? "download" :
        action === "detail-overflow" ? "overflow" : "reading";
      detailMenu = detailMenu === name ? null : name;
      render();
      return;
    }
    if (action === "detail-tag-menu") {
      detailMenu = detailMenu === `tag-${id}` ? null : `tag-${id}`;
      render();
      return;
    }
    if (action === "detail-filter-menu") {
      openModal("detail-settings");
      return;
    }
    if (action === "chapter-settings-tab") {
      chapterSettingsTab = id;
      renderModal();
      return;
    }
    if (action === "chapter-filter-cycle") {
      const options = detailOptions(book());
      const key = { unread: "unreadFilter", bookmark: "bookmarkFilter", download: "downloadFilter" }[id];
      options[key] = options[key] === 0 ? 1 : options[key] === 1 ? -1 : 0;
      render();
      return;
    }
    if (action === "chapter-scanlator-open") {
      scanlatorDraft = new Set(Object.entries(detailOptions(book()).scanlators)
        .filter(([, visible]) => visible === false).map(([name]) => name));
      openModal("detail-scanlators", true);
      return;
    }
    if (action === "chapter-scanlator-all" || action === "chapter-scanlator-reset") {
      scanlatorDraft = action.endsWith("all")
        ? new Set(book().chapters.map((chapter) => chapter.scanlator || "未知")) : new Set();
      renderModal(false);
      return;
    }
    if (action === "chapter-scanlator-save") {
      detailOptions(book()).scanlators = Object.fromEntries([...scanlatorDraft].map((name) => [name, false]));
      closeModal();
      return;
    }
    if (action === "chapter-settings-default-open") {
      chapterDefaultApplyExisting = false;
      openModal("detail-chapter-defaults", true);
      return;
    }
    if (action === "chapter-default-save") {
      const saved = structuredClone(detailOptions(book()));
      s.prefs.chapterDefaults = saved;
      if (chapterDefaultApplyExisting) s.books.forEach((item) => item.chapterOptions = structuredClone(saved));
      closeModal();
      s.notice = chapterDefaultApplyExisting ? "章节默认选项已应用到已有作品" : "新作品章节默认选项已保存";
      render();
      return;
    }
    if (action === "chapter-settings-reset") {
      book().chapterOptions = structuredClone(s.prefs.chapterDefaults || M.create().books[0].chapterOptions);
      render();
      return;
    }
    if (action.startsWith("detail-option-")) {
      const key = action.slice(14);
      const o = detailOptions(book());
      o[key] = !o[key];
      render();
      return;
    }
    if (action === "detail-scanlator") {
      const o = detailOptions(book());
      o.scanlators[id] = o.scanlators[id] === false;
      render();
      return;
    }
    if (action.startsWith("detail-sort-")) {
      const requested = action.slice(12), o = detailOptions(book());
      o.ascending = requested === o.sort ? !o.ascending : false;
      o.sort = requested;
      chapterAnchor = null;
      render();
      return;
    }
    if (action.startsWith("detail-display-")) {
      detailOptions(book()).display = action.slice(15);
      render();
      return;
    }
    if (action.startsWith("detail-reading-")) {
      book().readingMode = {
        default: "默认", auto: "自动", ltr: "从左到右",
        rtl: "从右到左", webtoon: "条漫",
      }[action.slice(15)];
      detailMenu = null;
      detailNotice("阅读模式已设为" + book().readingMode);
      return;
    }
    if (action.startsWith("detail-download-") && action !== "detail-download-menu") {
      const type = action.slice(16);
      const eligible = P.downloadCandidates(s, book(), type);
      detailMenu = null;
      detailDownload(eligible.map((chapter) => chapter.id));
      return;
    }
    if (action === "detail-open-link" || action === "detail-action-open-link") {
      detailInfo(book().sourceRequiresLogin
        ? "作品源链接需要登录或验证；本地浏览器预览不会提交账号。"
        : "在 Windows 浏览器打开作品源链接：本地预览，不请求真实图源。");
      return;
    }
    if (action === "detail-copy-link" || action === "detail-action-copy-link") {
      detailNotice("示例作品链接已复制（本地模拟）");
      return;
    }
    if (action === "detail-share-link" || action === "detail-action-share-link") {
      detailInfo("Windows 本地样本已复制作品链接；未向其他应用发送分享内容。");
      return;
    }
    if (action === "detail-title-search") {
      detailSearch(book().title, "全局");
      return;
    }
    if (action === "detail-title-copy") {
      localCopy("标题", book().title);
      return;
    }
    if (action === "detail-author-menu" || action === "detail-artist-menu") {
      const name = "creator-" + (action === "detail-author-menu" ? "author" : "artist");
      detailMenu = detailMenu === name ? null : name;
      render();
      return;
    }
    if (action === "detail-author" || action === "detail-artist") {
      detailInfo(`${action === "detail-author" ? "作者" : "画师"}：${book()[action === "detail-author" ? "author" : "artist"]}。作者资料页不在此本地样本中。`);
      return;
    }
    if (["detail-author-search", "detail-artist-search"].includes(action)) {
      const role = action === "detail-author-search" ? "作者" : "画师";
      detailSearch(book()[role === "作者" ? "author" : "artist"], `当前图源 · ${role}`);
      return;
    }
    if (["detail-author-copy", "detail-artist-copy"].includes(action)) {
      const role = action === "detail-author-copy" ? "作者" : "画师";
      detailMenu = null;
      localCopy(role, book()[role === "作者" ? "author" : "artist"]);
      return;
    }
    if (action === "detail-source-entry") {
      if (book().sourceMissing) detailNotice("缺失图源：请在扩展管理中恢复对应图源后重试");
      else {
        s.notice = `已进入「${book().source}」图源搜索样本`;
        render();
        detailSearch(book().title, `当前图源 · ${book().source}`);
      }
      return;
    }
    if (action === "detail-tag-search") {
      detailMenu = null;
      detailTagDraft = id;
      el.closest(".detail-tag")?.querySelector('[data-action="detail-tag-menu"]')?.focus();
      openModal("detail-tag-scope");
      return;
    }
    if (action === "detail-tag-source" || action === "detail-tag-global") {
      detailSearch(detailTagDraft, action === "detail-tag-source" ? "当前图源 · 标签" : "全局 · 标签");
      return;
    }
    if (action === "detail-tag-copy") {
      detailMenu = null;
      el.closest(".detail-tag")?.querySelector('[data-action="detail-tag-menu"]')?.focus();
      localCopy("标签", id);
      return;
    }
    if (action === "detail-description-toggle") {
      detailDescriptionExpanded = !detailDescriptionExpanded;
      render();
      return;
    }
    if (action === "detail-description-copy") {
      localCopy("简介", book().description || "");
      return;
    }
    if (action === "detail-description-link") {
      e.preventDefault();
      detailInfo(`本地链接预览：${id}。本地样本不打开外部网站。`);
      return;
    }
    if (action === "detail-cover-open") {
      detailCoverScale = 100;
      openModal("detail-cover-viewer");
      return;
    }
    if (action === "detail-cover-zoom-in" || action === "detail-cover-zoom-out") {
      detailCoverScale = Math.min(250, Math.max(50, detailCoverScale + (action === "detail-cover-zoom-in" ? 25 : -25)));
      renderModal(false);
      return;
    }
    if (action === "detail-cover-save" || action === "detail-cover-share") {
      detailNotice(`本地封面样本${action === "detail-cover-save" ? "保存" : "分享"}演示：未调用系统或写入磁盘`);
      return;
    }
    if (action === "detail-library") {
      const b = book();
      if (b.favorite) {
        removeDownloadsDraft = false;
        confirm("作品已在书架。取消收藏这部作品？可选择是否删除本地下载。取消不会修改作品或文件。", () => {
          b.favorite = false;
          let removed = 0;
          if (removeDownloadsDraft) b.chapters.forEach((c) => {
            if (c.download) removed++;
            c.download = null;
            c.downloadStatus = null;
          });
          s.notice = removeDownloadsDraft ? `已取消收藏并移除 ${removed} 个本地下载样本` : "已取消收藏；本地下载保留";
        }, `<label class="setting"><span>同时删除本地下载样本</span><input type="checkbox" data-testid="detail-remove-downloads"></label>`);
        return;
      }
      duplicateBookId = s.books.find((other) => other.id !== b.id && other.favorite && other.title === b.title && other.source === b.source)?.id || null;
      if (duplicateBookId) openModal("detail-duplicate");
      else {
        b.favorite = true;
        if (b.autoTrackMatch && !b.tracks.length)
          b.tracks.push({ name: "AniList", scale: 10, value: 0, progress: 0, totalChapters: 100, status: "阅读中" });
        detailNotice(b.autoTrackMatch ? "已加入书架，并自动匹配 AniList 追踪样本" : "已加入书架");
      }
      return;
    }
    if (action === "detail-duplicate-view") {
      const target = duplicateBookId;
      closeModal(true);
      s.bookId = target;
      detailBookId = null;
      render();
      return;
    }
    if (action === "detail-duplicate-continue") {
      book().favorite = true;
      closeModal(true);
      detailNotice("已确认重复作品并加入书架（本地样本）");
      return;
    }
    if (action === "detail-duplicate-migrate") {
      const existing = duplicateBookId;
      closeModal(true);
      s.bookId = existing;
      detailBookId = null;
      render();
      const target = book();
      if (target) {
        migrationDraft = { source: "示例备用图源", query: target.title, page: 1,
          results: null, match: null, error: "", options: {
            chapters: true, categories: true, cover: true, notes: true, removeDownloads: false,
          } };
        openModal("detail-migrate");
      }
      return;
    }
    if (action === "detail-categories") {
      detailCategoryDraft = [...book().categories];
      openModal("detail-categories");
      return;
    }
    if (action === "detail-category-manage") {
      // Upstream dismisses the chooser before navigating to CategoryScreen.
      detailCategoryDraft = [];
      closeModal(true);
      openModal("categories");
      return;
    }
    if (action === "detail-category-save") {
      const b = book();
      const chosen = detailCategoryDraft.filter((id) => id !== 0 && s.categories.some((c) => c.id === id));
      b.categories = chosen.length ? chosen : [0];
      closeModal();
      detailNotice("作品分类已更新");
      return;
    }
    if (action === "detail-fetch-interval") {
      detailIntervalDraft = book().fetchInterval || 0;
      openModal("detail-interval");
      return;
    }
    if (action === "detail-interval-save") {
      book().fetchInterval = detailIntervalDraft;
      closeModal();
      detailNotice(`作品更新间隔已保存：${detailIntervalDraft ? detailIntervalDraft + " 天" : "默认"}`);
      return;
    }
    if (action === "detail-tracking") {
      const active = book().tracks[0];
      trackingDraft = {
        service: active?.name || "AniList", query: book().title,
        chapter: active?.progress || 0, status: active?.status || "阅读中",
        score: active ? Number(active.value / active.scale * 10).toFixed(1) : "0",
        startDate: active?.startDate || "", finishDate: active?.finishDate || "",
        private: !!active?.private, results: null,
      };
      openModal("detail-tracking");
      return;
    }
    if (action === "tracking-search-submit") {
      trackingDraft.results = [`${trackingDraft.query || book().title} · 匹配作品`, `${book().title} · 另一版本`];
      renderModal(false);
      return;
    }
    if (action.startsWith("tracking-result-")) {
      trackingDraft.query = trackingDraft.results[Number(action.slice(16))];
      trackingDraft.results = null;
      renderModal(false);
      return;
    }
    if (action === "tracking-save") {
      const current = book().tracks.find((track) => track.name === trackingDraft.service);
      const chapter = Number(trackingDraft.chapter), total = current?.totalChapters || 100;
      if (!P.validateTrackerChapter(chapter, total)) {
        s.notice = `追踪章节必须在 0–${total} 之间`;
        renderModal(false);
        return;
      }
      const updated = {
        name: trackingDraft.service, scale: 10, value: Number(trackingDraft.score) || 0,
        progress: chapter, totalChapters: total, status: trackingDraft.status,
        startDate: trackingDraft.startDate, finishDate: trackingDraft.finishDate,
        private: trackingDraft.private, title: trackingDraft.query,
        url: `https://example.org/tracking/${encodeURIComponent(book().id)}`,
      };
      if (current) Object.assign(current, updated);
      else book().tracks.push(updated);
      closeModal(true);
      detailNotice(`${trackingDraft.service} 追踪进度已保存到第 ${chapter} 话（本地模拟）`);
      return;
    }
    if (action === "tracking-unbind") {
      book().tracks = book().tracks.filter((track) => track.name !== trackingDraft.service);
      closeModal(true);
      detailNotice("追踪已解除绑定（本地模拟）");
      return;
    }
    if (action === "tracking-open-link" || action === "tracking-copy-link") {
      detailInfo(action === "tracking-open-link" ? "在浏览器打开追踪作品链接：本地预览。" : "追踪作品链接已复制（本地模拟）。");
      return;
    }
    if (action === "tracking-refresh") {
      if (book().trackRefreshError) s.notice = "远端追踪刷新失败；可重试，原进度保留";
      else {
        const current = book().tracks.find((track) => track.name === trackingDraft.service);
        if (current) {
          current.progress = Math.max(current.progress || 0, book().remoteProgress || 12);
          trackingDraft.chapter = current.progress;
        }
        s.notice = `远端追踪已刷新：第 ${current?.progress || 0} 话（本地样本）`;
      }
      renderModal(false);
      return;
    }
    if (action === "detail-notes" || action === "detail-notes-summary") {
      detailNotesDraft = book().notes || "";
      openModal("detail-notes");
      document.querySelector('[data-testid="detail-notes-input"]')?.focus();
      return;
    }
    if (action === "detail-notes-cancel") {
      detailNotesDraft = "";
      closeModal();
      return;
    }
    if (action.startsWith("detail-notes-") && !["detail-notes-save", "detail-notes-input"].includes(action)) {
      const formats = {
        "detail-notes-bold": ["**", "**"],
        "detail-notes-italic": ["*", "*"],
        "detail-notes-underline": ["++", "++"],
        "detail-notes-bullet": ["- ", ""],
        "detail-notes-numbered": ["1. ", ""],
        "detail-notes-link": ["[", "](https://example.org)"],
      };
      if (formats[action]) formatDetailNote(...formats[action]);
      return;
    }
    if (action === "detail-notes-save") {
      if (s.failSave) {
        s.failSave = false;
        detailNotice("保存失败：笔记草稿仍在，可重试");
        return;
      }
      book().notes = detailNotesDraft;
      closeModal();
      detailNotice("作品笔记已保存");
      return;
    }
    if (action === "detail-migrate") {
      if (!book().favorite || book().sourceMissing || book().local) {
        detailInfo("迁移要求作品已加入书架、图源可用且不是本地作品。请先恢复图源或加入书架。");
        return;
      }
      migrationDraft = { source: "示例备用图源", query: book().title, page: 1,
        results: null, match: null, error: "", options: {
          chapters: true, categories: true, cover: true, notes: true, removeDownloads: false,
        } };
      openModal("detail-migrate");
      return;
    }
    if (action === "detail-migrate-target") {
      openModal("detail-migrate-results", true);
      return;
    }
    if (action === "migration-search" || action === "migration-next-page") {
      if (action === "migration-next-page") migrationDraft.page++;
      migrationDraft.error = migrationDraft.query.toLowerCase().includes("error") ? "目标图源返回错误；可修改搜索词并重试" : "";
      migrationDraft.results = migrationDraft.error || migrationDraft.query.toLowerCase().includes("empty") ? [] : [
        { title: `${migrationDraft.query} · 第 ${migrationDraft.page} 页`, source: migrationDraft.source },
        { title: `${book().title} · 候选版本`, source: migrationDraft.source },
      ];
      renderModal(false);
      return;
    }
    if (action === "migration-change-source") {
      migrationDraft.source = migrationDraft.source === "示例备用图源" ? "示例第三图源" : "示例备用图源";
      migrationDraft.page = 1;
      migrationDraft.results = null;
      s.notice = `目标图源：${migrationDraft.source}`;
      renderModal(false);
      return;
    }
    if (action === "detail-migrate-match" || action.startsWith("detail-migrate-match-")) {
      migrationDraft.match = migrationDraft.results?.[Number(action.split("-").at(-1)) || 0] ||
        { title: book().title, source: migrationDraft.source };
      openModal("detail-migrate-confirm", true);
      return;
    }
    if (action === "migration-copy" || action === "migration-move") {
      const sourceBook = book();
      const draft = migrationDraft;
      draft.progress = action === "migration-move" ? "正在迁移作品状态…" : "正在复制作品状态…";
      draft.error = "";
      renderModal(false);
      setTimeout(() => {
        if (modal !== "detail-migrate-confirm" || migrationDraft !== draft) return;
        if (!draft.attempted && draft.match.title.includes("失败")) {
          draft.attempted = true;
          draft.progress = "";
          draft.error = "迁移失败：目标图源暂不可写。原作品未修改，可重试。";
          renderModal(false);
          return;
        }
        const candidate = structuredClone(sourceBook);
        candidate.id = `${sourceBook.id}-migrated-${s.books.length}`;
        candidate.title = draft.match.title;
        candidate.source = draft.source;
        candidate.chapters = sourceBook.chapters.map((chapter) => ({ ...chapter,
          id: `${candidate.id}-${chapter.number}`, url: `/migrated/${chapter.number}`,
          read: false, bookmark: false, page: 0, download: null, downloadStatus: null,
        }));
        const result = P.migrate(sourceBook, candidate, draft.options, action === "migration-move");
        Object.assign(sourceBook, result.current);
        s.books.push(result.target);
        s.bookId = result.target.id;
        detailSelected = [];
        chapterAnchor = null;
        closeModal(true);
        detailNotice(action === "migration-move" ? "迁移完成：旧收藏已移除，目标作品已建立" : "复制完成：原收藏保留，目标作品已建立");
      }, 350);
      return;
    }
    if (action === "detail-mark-all") {
      const target = book().id, ids = book().chapters.map((c) => c.id);
      confirm(`将这部作品的 ${ids.length} 个章节全部标为已读？`, () => {
        const b = s.books.find((x) => x.id === target);
        b?.chapters.filter((c) => ids.includes(c.id)).forEach((c) => { c.read = true; c.page = 0; });
        if (b) b.unread = b.chapters.filter((c) => !c.read).length;
        s.notice = `已将 ${ids.length} 个章节标为已读`;
      });
      return;
    }
    if (action === "detail-select-all") {
      detailSelected = displayedChapters(book()).map((c) => c.id);
      chapterAnchor = detailSelected[0] || null;
      render();
      return;
    }
    if (action === "detail-select-invert") {
      const selected = new Set(detailSelected);
      detailSelected = displayedChapters(book()).map((chapter) => chapter.id)
        .filter((chapterId) => !selected.has(chapterId));
      chapterAnchor = detailSelected[0] || null;
      render();
      return;
    }
    if (action === "detail-select-close") {
      detailSelected = [];
      chapterAnchor = null;
      render();
      return;
    }
    if (action.startsWith("detail-batch-")) {
      const b = book(), visible = displayedChapters(b).map((chapter) => chapter.id);
      const ids = detailSelected.filter((chapterId) => visible.includes(chapterId));
      const selected = b.chapters.filter((chapter) => ids.includes(chapter.id));
      const plan = P.batchPlan(b.chapters, ids, b);
      if (action === "detail-batch-bookmark") {
        const shouldBookmark = selected.some((c) => !c.bookmark);
        selected.forEach((c) => c.bookmark = shouldBookmark);
        detailSelected = [];
        detailNotice(`已${shouldBookmark ? "添加" : "移除"} ${selected.length} 个章节的书签，跳过 0 个`);
      } else if (action === "detail-batch-read" || action === "detail-batch-unread") {
        const read = action === "detail-batch-read";
        selected.forEach((c) => { c.read = read; if (read) c.page = 0; });
        b.unread = b.chapters.filter((c) => !c.read).length;
        detailSelected = [];
        if (read) maybeSyncTracking(Math.max(0, ...selected.map((chapter) => chapter.number)));
        detailNotice(`已将 ${selected.length} 个章节标为${read ? "已读" : "未读"}，跳过 0 个`);
      } else if (action === "detail-batch-previous" || action === "detail-batch-below") {
        const prior = P.previousChapters(b.chapters, selected[0]?.id);
        prior.forEach((chapter) => { chapter.read = true; chapter.page = 0; });
        b.unread = b.chapters.filter((c) => !c.read).length;
        detailSelected = [];
        maybeSyncTracking(Math.max(0, ...prior.map((chapter) => chapter.number)));
        detailNotice(`已将之前 ${prior.length} 个章节标为已读；当前章节不变`);
      } else if (action === "detail-batch-delete") {
        const snapshot = plan.toDelete.map((chapter) => chapter.id);
        confirmSnapshot = snapshot;
        confirm(`所选 ${ids.length} 章中，${snapshot.length} 章有本机下载文件。确认删除这些文件样本？其余 ${ids.length - snapshot.length} 章跳过；阅读记录保留。`, () => {
          let removed = 0;
          b.chapters.filter((c) => snapshot.includes(c.id) && c.download)
            .forEach((c) => { c.download = null; removed++; });
          detailSelected = [];
          s.notice = `已删除 ${removed} 个本地下载，跳过 ${ids.length - removed} 个，失败 0 个`;
        });
      } else if (action === "detail-batch-download") detailDownload(ids);
      return;
    }
    if (action.startsWith("chapter-context-")) {
      const c = book().chapters.find((item) => item.id === chapterContextId);
      if (!c) return;
      detailMenu = null;
      if (action === "chapter-context-read") {
        c.read = !c.read;
        if (c.read) { c.page = 0; maybeSyncTracking(c.number); }
        book().unread = book().chapters.filter((item) => !item.read).length;
        detailNotice(c.read ? "章节已标为已读" : "章节已标为未读");
      } else if (action === "chapter-context-bookmark") {
        c.bookmark = !c.bookmark;
        detailNotice(c.bookmark ? "已添加书签" : "已移除书签");
      } else if (action === "chapter-context-download" || action === "chapter-context-now") {
        detailDownload([c.id]);
      } else if (action === "chapter-context-cancel") {
        c.downloadStatus = null;
        detailNotice("已取消排队下载");
      } else if (action === "chapter-context-delete") {
        confirm(`删除「${c.name}」的本机下载文件样本？`, () => {
          c.download = null;
          s.notice = "本地下载已删除";
        });
      }
      return;
    }
    if (action.startsWith("chapter-bookmark-")) {
      const c = book().chapters.find((item) => item.id === action.slice(17));
      if (c) { c.bookmark = !c.bookmark; detailNotice(c.bookmark ? "已添加书签" : "已取消书签"); }
      return;
    }
    if (action === "chapter-progress") {
      const name = "chapter-progress-" + id;
      detailMenu = detailMenu === name ? null : name;
      render();
      return;
    }
    if (action.startsWith("chapter-download-")) {
      detailDownload([action.slice(17)]);
      return;
    }
    if (action.startsWith("chapter-delete-")) {
      const chapterId = action.slice(15), chapter = book().chapters.find((c) => c.id === chapterId);
      if (chapter) confirm(`删除「${chapter.name}」在本机的下载文件？`, () => {
        chapter.download = null;
        s.notice = "本地下载已删除";
      });
      return;
    }
    if (action.startsWith("chapter-cancel-")) {
      const c = book().chapters.find((item) => item.id === action.slice(15));
      if (c) { c.downloadStatus = null; detailMenu = null; detailNotice("已取消章节下载"); }
      return;
    }
    if (action.startsWith("chapter-retry-")) {
      const c = book().chapters.find((item) => item.id === action.slice(14));
      if (c) { c.downloadStatus = null; detailDownload([c.id]); }
      return;
    }
    if (action.startsWith("chapter-read-")) {
      reader = action.slice(13);
      s.route = "reader";
      render();
      return;
    }
    if (action === "detail-back") {
      if (detailSelected.length) {
        detailSelected = [];
        chapterAnchor = null;
        render();
        return;
      }
      detailExit();
      return;
    }
    if (action === "reader-back") {
      s.route = "detail";
      reader = null;
      render();
      return;
    }
    if (action === "detail-continue") {
      reader = P.nextReadable(s, book())?.id || null;
      if (reader) {
        s.route = "reader";
        render();
      }
      return;
    }
    if (action === "chapter") {
      if (suppressChapterClick === id) {
        suppressChapterClick = null;
        return;
      }
      selectDetailChapter(id, { ctrl: e.ctrlKey, shift: e.shiftKey });
      return;
    }
    if (action === "reader-next" || action === "reader-finish") {
      const c = book().chapters.find((c) => c.id === reader);
      if (action === "reader-next") c.page++;
      else {
        c.read = true;
        book().unread = book().chapters.filter((x) => !x.read).length;
        s.route = "detail";
        maybeSyncTracking(c.number);
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
      detailMenu = null;
      book().custom = true;
      book().customRevision = (book().customRevision || 0) + 1;
      s.notice = "已使用本地自定义封面样本";
      render();
      return;
    }
    if (action === "cover-delete") {
      detailMenu = null;
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
    if (action === "detail-refresh" || action === "detail-refresh-retry") {
      refreshDetail();
      return;
    }
    if (action === "refresh" || action === "refresh-all") {
      M.start(
        s,
        action === "refresh-all" ? "all" : "category",
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
    if (action.startsWith("category-up-") || action.startsWith("category-down-")) {
      const id = Number(action.split("-").at(-1));
      const categories = s.categories.filter((c) => c.id !== 0);
      const index = categories.findIndex((c) => c.id === id);
      M.reorderCategory(s, id, index + (action.startsWith("category-up-") ? -1 : 1));
      render();
      return;
    }
    if (action.startsWith("category-rename-") && action !== "category-rename-save") {
      categoryRenameId = Number(action.slice("category-rename-".length));
      categoryRenameDraft = s.categories.find((c) => c.id === categoryRenameId)?.name || "";
      openModal("category-rename", true);
      document.querySelector('[data-testid="category-rename-input"]')?.focus();
      return;
    }
    if (action === "category-rename-save") {
      categoryRenameDraft = document.querySelector('[data-testid="category-rename-input"]')?.value.trim() || "";
      if (!categoryRenameDraft || s.categories.some((c) => c.id !== categoryRenameId && c.name === categoryRenameDraft)) {
        s.notice = categoryRenameDraft ? "分类已存在" : "请输入分类名称";
        renderModal(false);
        return;
      }
      s.categories.find((c) => c.id === categoryRenameId).name = categoryRenameDraft;
      closeModal();
      s.notice = "分类名称已保存";
      render();
      return;
    }
    if (action === "category-add") {
      const name = document.querySelector("#category-name").value.trim();
      if (!name || s.categories.some((c) => c.name === name)) {
        s.notice = name ? "分类已存在" : "请输入分类名称";
        renderModal(false);
        return;
      }
      s.categories.push({
        id: Math.max(0, ...s.categories.map((c) => c.id)) + 1,
        name,
      });
      s.notice = "分类已添加";
      render();
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
      s.books.forEach((item) => { item.sourceMissing = false; item.trackRefreshError = false; });
      detailRefreshError = "";
      s.notice = "书架已重新载入";
      render();
      return;
    }
    if (action.startsWith("nav-")) {
      s.route = action.slice(4);
      reader = null;
      detailSelected = [];
      detailMenu = null;
      render();
      document.querySelector(`[data-testid="${action}"]`)?.focus({ preventScroll: true });
      return;
    }
  });
  app.addEventListener("dragstart", (event) => {
    const handle = event.target.closest("[data-category-drag]");
    if (modal !== "categories" || !handle) return;
    event.dataTransfer.setData("text/plain", handle.dataset.categoryDrag);
    event.dataTransfer.effectAllowed = "move";
  });
  app.addEventListener("dragover", (event) => {
    if (modal !== "categories" || !event.target.closest(".category-manage-row")) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = "move";
  });
  app.addEventListener("drop", (event) => {
    const row = event.target.closest(".category-manage-row");
    if (modal !== "categories" || !row) return;
    event.preventDefault();
    const categories = s.categories.filter((c) => c.id !== 0);
    M.reorderCategory(
      s,
      Number(event.dataTransfer.getData("text/plain")),
      categories.findIndex((c) => c.id === Number(row.dataset.id)),
    );
    render();
  });

  app.addEventListener("input", (e) => {
    if (e.target.dataset.trackField && trackingDraft) {
      const key = e.target.dataset.trackField;
      trackingDraft[key] = e.target.type === "checkbox" ? e.target.checked : e.target.value;
      return;
    }
    if (e.target.dataset.migrationField && migrationDraft) {
      migrationDraft[e.target.dataset.migrationField] = e.target.value;
      return;
    }
    if (e.target.dataset.testid === "detail-notes-input") {
      detailNotesDraft = e.target.value;
      return;
    }
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
    if (e.target.dataset.trackField && trackingDraft) {
      const field = e.target.dataset.trackField;
      trackingDraft[field] = e.target.type === "checkbox" ? e.target.checked : e.target.value;
      if (field === "service") {
        const linked = book().tracks.find((track) => track.name === trackingDraft.service);
        trackingDraft.chapter = linked?.progress || 0;
        trackingDraft.status = linked?.status || "阅读中";
        trackingDraft.score = linked ? Number(linked.value / linked.scale * 10).toFixed(1) : "0";
        trackingDraft.startDate = linked?.startDate || "";
        trackingDraft.finishDate = linked?.finishDate || "";
        trackingDraft.private = !!linked?.private;
        trackingDraft.query = linked?.title || book().title;
        trackingDraft.results = null;
        renderModal(false);
      }
      return;
    }
    if (e.target.dataset.scanlatorName !== undefined) {
      if (e.target.checked) scanlatorDraft.add(e.target.dataset.scanlatorName);
      else scanlatorDraft.delete(e.target.dataset.scanlatorName);
      return;
    }
    if (e.target.dataset.testid === "chapter-default-apply-existing") {
      chapterDefaultApplyExisting = e.target.checked;
      return;
    }
    if (e.target.dataset.migrationOption !== undefined) {
      migrationDraft.options[e.target.dataset.migrationOption] = e.target.checked;
      return;
    }
    if (e.target.dataset.detailPref === "trackingOnRead") {
      s.prefs.trackingOnRead = e.target.value;
      return;
    }
    if (e.target.dataset.detailCategory !== undefined) {
      const categoryId = Number(e.target.dataset.detailCategory);
      detailCategoryDraft = e.target.checked
        ? [...new Set([...detailCategoryDraft, categoryId])]
        : detailCategoryDraft.filter((x) => x !== categoryId);
      return;
    }
    if (e.target.name === "detail-interval") {
      detailIntervalDraft = Number(e.target.value);
      return;
    }
    if (e.target.dataset.testid === "detail-remove-downloads") {
      removeDownloadsDraft = e.target.checked;
      return;
    }
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
    const chapter = e.target.closest('[data-action="chapter"]');
    if (chapter && e.button === 0 && !e.target.closest("button")) {
      const start = { x: e.clientX, y: e.clientY, id: chapter.dataset.id };
      detailHold = {
        ...start,
        timer: setTimeout(() => {
          selectDetailChapter(start.id, { long: true, shift: e.shiftKey });
          suppressChapterClick = start.id;
          setTimeout(() => suppressChapterClick = null, 600);
          detailHold = null;
        }, 500),
      };
      return;
    }
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
    if (detailHold && Math.hypot(e.clientX - detailHold.x, e.clientY - detailHold.y) > 8) {
      clearTimeout(detailHold.timer);
      detailHold = null;
    }
    if (hold && Math.hypot(e.clientX - hold.x, e.clientY - hold.y) > 8) {
      clearTimeout(hold.timer);
      hold = null;
    }
  });
  ["pointerup", "pointercancel"].forEach((type) =>
    app.addEventListener(type, () => {
      if (detailHold) clearTimeout(detailHold.timer);
      detailHold = null;
      if (hold) clearTimeout(hold.timer);
      hold = null;
    }),
  );
  app.addEventListener("contextmenu", (e) => {
    const title = e.target.closest('[data-action="detail-title-search"]');
    if (title) {
      e.preventDefault();
      localCopy("标题", book().title);
      return;
    }
    const web = e.target.closest('[data-action="detail-open-link"]');
    if (web) {
      e.preventDefault();
      detailNotice("示例作品链接已复制（本地模拟）");
      return;
    }
    const description = e.target.closest('[data-testid="detail-description"]');
    if (description && !e.target.closest("a")) {
      e.preventDefault();
      localCopy("简介", book().description);
      return;
    }
    const creator = e.target.closest('[data-action="detail-author"], [data-action="detail-artist"]');
    if (creator) {
      e.preventDefault();
      const name = creator.dataset.action.slice(7);
      detailMenu = `creator-${name}`;
      render();
      return;
    }
    const chapter = e.target.closest('[data-action="chapter"]');
    if (chapter) {
      e.preventDefault();
      chapterContextId = chapter.dataset.id;
      detailMenu = "chapter-context";
      render();
      return;
    }
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
        if (modal === "detail-cover-viewer" && detailMenu === "cover") {
          detailMenu = null;
          renderModal(false);
          document.querySelector('[data-testid="detail-cover-menu"]')?.focus({ preventScroll: true });
          return;
        }
        if (modal === "detail-cover-viewer") detailMenu = null;
        closeModal();
        return;
      }
      if (s.route === "detail" && detailMenu) {
        detailMenu = null;
        render();
        return;
      }
      if (s.route === "detail" && detailSelected.length) {
        detailSelected = [];
        chapterAnchor = null;
        render();
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
        detailExit();
        return;
      }
      if (s.route !== "library") {
        s.route = "library";
        render();
        document.querySelector('[data-testid="nav-library"]')?.focus({ preventScroll: true });
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
          ".sheet button:not(:disabled),.sheet input:not(:disabled),.sheet select:not(:disabled),.sheet textarea:not(:disabled)",
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
  function onDetailWheel(e) {
    const scroller = e.currentTarget;
    const active = window.matchMedia("(max-width: 760px)").matches
      ? document.querySelector('[data-testid="detail-scroll"]')
      : document.querySelector('[data-testid="detail-chapter-scroll"]');
    if (scroller !== active) return;
    if (e.ctrlKey || e.shiftKey || e.altKey || e.deltaY >= 0 || scroller.scrollTop > 0 ||
        detailSelected.length || modal || s.route !== "detail" || s.job?.status === "running") {
      cancelDetailPull();
      return;
    }
    e.preventDefault();
    const now = performance.now();
    if (detailRefreshArmed && now - detailRefreshAt > 3000) cancelDetailPull();
    detailRefreshDistance += Math.min(100, Math.abs(e.deltaY));
    const tip = document.querySelector('[data-testid="detail-pull-tip"]');
    if (!detailRefreshArmed && detailRefreshDistance >= 80) {
      detailRefreshArmed = true;
      detailRefreshDistance = 0;
      detailRefreshAt = now;
      if (tip) tip.textContent = "再次向上滚动检查更新";
      return;
    }
    if (detailRefreshArmed && now - detailRefreshAt >= 300 && detailRefreshDistance >= 48)
      refreshDetail();
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
      if (s.route === "detail" && s.job?.ids.includes(s.bookId) && !s.job.detailHandled) {
        const result = s.job.results[s.bookId];
        if (result) {
          s.job.detailHandled = true;
          const current = book();
          if (result.status === "success" && current) {
            current.lastChecked = Date.now();
            detailRefreshError = "";
            if (s.job.rules.metadata) {
              current.author = current.sourceAuthor || "更新后的作者";
              current.status = current.sourceStatus || "连载中";
              if (!s.job.rules.protectTitle && current.sourceTitle) current.title = current.sourceTitle;
            }
            if (s.job.rules.autoDownloadNew) {
              const added = current.chapters.filter((chapter) => chapter.number > 3 && !chapter.download);
              added.forEach((chapter) => chapter.download = `${current.id}-file-${chapter.number}`);
              s.notice = `目录检查完成；新增 ${added.length} 章已加入下载样本`;
            }
          } else detailRefreshError = `${result.reason}；原数据保留，可重试`;
        }
      }
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
      detailSelected = [];
      detailMenu = null;
      detailBookId = null;
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
      s.books.forEach((item) => { item.sourceMissing = false; item.trackRefreshError = false; });
      detailRefreshError = "";
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
    if (name === "detail-content") {
      s.books[0].description = "**星海手记** 在失落的灯塔发现地图。第一段只是开端。\n\n旅人沿海岸寻找同伴，逐渐拼出一封跨越多年的信。\n\n[查看世界设定](https://example.org/story) 记录了源端的补充信息。\n\n最后，他们决定回到最初的港口。";
      s.books[0].status = "暂停连载";
      s.books[1].status = "未知";
      s.books[0].sourceTitle = "星海手记 · 新版";
      s.books[0].sourceAuthor = "林舟（更新）";
      s.books[0].sourceStatus = "连载中";
    }
    if (name === "detail-missing-source") {
      s.books[0].sourceMissing = true;
      s.books[0].language = "日文";
      s.books[0].sourceRequiresLogin = true;
      s.books[0].chapters[2].external = true;
      s.books[0].chapters[2].downloadStatus = null;
    }
    if (name === "detail-auth-source") s.books[0].sourceRequiresLogin = true;
    if (name === "detail-duplicate") {
      const duplicate = structuredClone(s.books[0]);
      duplicate.id = "A-existing";
      s.books.push(duplicate);
      s.books[0].favorite = false;
      s.bookId = "A";
      s.route = "detail";
    }
    if (name === "detail-tracking") {
      s.books[0].tracks[0].progress = 8;
      s.books[0].tracks[0].totalChapters = 100;
      s.books[0].remoteProgress = 12;
      s.books[5].favorite = false;
      s.books[5].autoTrackMatch = true;
    }
    if (name === "detail-tracking-error") s.books[0].trackRefreshError = true;
    if (name === "detail-migration") {
      s.books[0].notes = "**旧图源笔记**";
      s.books[0].custom = true;
      s.books[0].customRevision = 1;
    }
    if (name === "detail-batch-failure") s.failBatch = true;
    if (name === "detail-uncollected-tracking") {
      s.books[5].favorite = false;
      s.books[5].autoTrackMatch = true;
      s.bookId = "F";
      s.route = "detail";
    }
    if (name === "detail-long-chapters") {
      const base = s.books[0].chapters[2];
      s.books[0].chapters = Array.from({ length: 200 }, (_, index) => {
        const number = index + 1;
        return { ...base, id: `A-${number}`, number, name: `第 ${number} 话`,
          sourceOrder: number, url: `/chapter/${number}`, read: number === 1,
          bookmark: number === 1, page: 0, download: null, downloadStatus: null,
          dateUpload: 1700000000000 + number * 86400000 };
      });
      s.books[0].unread = 199;
    }
    if (name === "detail-sync-progress") {
      s.books[0].chapters[1].read = true;
      s.books[0].chapters[2].page = 12;
      s.books[0].chapters[2].syncedProgress = true;
      s.books[0].unread = 1;
    }
    if (!["save-failure", "sort-interrupted", "broken-store"].includes(name)) {
      const scroll = document.querySelector('[data-testid="library-scroll"]');
      if (scroll) scroll.scrollTop = 0;
      cooldownUntil = -Infinity;
    }
    modal = null;
    modalStack = [];
    reader = null;
    detailSelected = [];
    chapterAnchor = null;
    detailRefreshArmed = false;
    detailRefreshError = "";
    detailMenu = null;
    detailBookId = null;
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
