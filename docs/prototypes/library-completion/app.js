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
  let detailMenu = null,
    detailSelected = [],
    detailBookId = null,
    detailHold = null,
    suppressChapterClick = null,
    detailCategoryDraft = [],
    detailIntervalDraft = 0,
    detailNotesDraft = "",
    detailScroll = {};
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
  function detailOptions(b) {
    return (b.chapterOptions ||= {
      read: true, unread: true, bookmark: false, download: false,
      scanlators: {}, sort: "source", ascending: false, display: "name",
    });
  }
  function displayedChapters(b) {
    const o = detailOptions(b);
    const rows = b.chapters.filter((c) =>
      ((o.read && c.read) || (o.unread && !c.read)) &&
      (!o.bookmark || c.bookmark) &&
      (!o.download || !!c.download) &&
      o.scanlators?.[c.scanlator || "未知"] !== false
    );
    const compare = {
      source: (a, z) => (z.sourceOrder ?? 0) - (a.sourceOrder ?? 0),
      number: (a, z) => a.number - z.number,
      date: (a, z) => (a.dateUpload || 0) - (z.dateUpload || 0),
      alphabet: (a, z) => a.name.localeCompare(z.name, "zh"),
    }[o.sort];
    return rows.sort((a, z) => (o.ascending ? 1 : -1) * compare(a, z));
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
    const downloadControl = ["queued", "downloading"].includes(downloadState)
      ? `<div class="detail-menu-anchor chapter-progress-anchor"><button class="icon chapter-progress" data-action="chapter-progress" data-id="${esc(c.id)}" data-testid="chapter-progress-${esc(c.id)}" aria-label="${downloadState === "queued" ? "排队中" : "下载中"}" title="${downloadState === "queued" ? "排队中" : "下载中"}">${icon("download")}</button>${detailMenuMarkup("chapter-progress-" + c.id, `<button data-action="chapter-cancel-${esc(c.id)}" data-testid="chapter-cancel-${esc(c.id)}">取消</button>`)}</div>`
      : button(download[0] + c.id, download[1], download[2]);
    return `<div class="chapter-row ${selected ? "selected" : ""}" data-testid="chapter-row-${esc(c.id)}" data-chapter-id="${esc(c.id)}" data-read="${c.read}" data-action="chapter" data-id="${esc(c.id)}" role="button" tabindex="0" aria-pressed="${selected}">
      ${detailSelected.length ? `<input type="checkbox" tabindex="-1" aria-label="选择 ${esc(title)}" ${selected ? "checked" : ""}>` : ""}
      <div class="chapter-main"><span class="chapter-title ${c.read ? "read" : ""}">${esc(title)}</span>${c.page && !c.read || c.scanlator ? `<small>${c.page && !c.read ? `第 ${c.page + 1} 页` : ""}${c.page && !c.read && c.scanlator ? " · " : ""}${esc(c.scanlator || "")}</small>` : ""}</div>
      <div class="chapter-trailing">
        ${button("chapter-bookmark-" + c.id, c.bookmark ? "取消书签" : "添加书签", c.bookmark ? "bookmarkFilled" : "bookmark")}
        ${downloadControl}
        ${!detailSelected.length ? button("chapter-read-" + c.id, progress, c.read ? "check" : c.page > 0 ? "ring" : "dot") : ""}
      </div>
    </div>`;
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
    }
    const o = detailOptions(b);
    const chapters = displayedChapters(b);
    const unread = b.chapters.find((c) => !c.read);
    const downloading = [["next-1", "接下来 1 话"], ["next-5", "接下来 5 话"], ["next-10", "接下来 10 话"], ["next-25", "接下来 25 话"], ["unread", "全部未读章节"], ["bookmarked", "未读书签章节"]]
      .map(([id, label]) => `<button role="menuitem" data-testid="detail-download-${id}" data-action="detail-download-${id}">${label}</button>`).join("");
    const readingModes = [["default", "默认"], ["auto", "自动"], ["ltr", "从左到右"], ["rtl", "从右到左"], ["webtoon", "条漫"]]
      .map(([id, label]) => `<button role="menuitemradio" aria-checked="${b.readingMode === label}" data-action="detail-reading-${id}" data-testid="detail-reading-${id}">${label}</button>`).join("");
    const toolbar = `<header class="bar detail-bar">
      ${button("detail-back", "返回书架", "back")}
      <h1 data-testid="detail-title">${esc(b.title)}</h1>
      <div class="actions">
        <div class="detail-menu-anchor">${button("detail-download-menu", "下载章节", "download")}${detailMenuMarkup("download", downloading)}</div>
        ${!b.local ? `${button("detail-open-link", "在浏览器打开", "link")}${button("detail-copy-link", "复制链接", "link")}${button("detail-share-link", "分享链接", "share")}` : ""}
        <button data-testid="detail-mark-all" data-action="detail-mark-all" class="detail-mark-all">全部标为已读</button>
        ${detailSelected.length ? `${button("detail-select-all", "全选当前可见章节", "selectAll")}${button("detail-select-close", "退出章节选择", "close")}` : ""}
        <div class="detail-menu-anchor">${button("detail-filter-menu", "筛选、排序和显示章节", "filter")}${detailMenuMarkup("filter", detailFilters(b))}</div>
        ${button("detail-refresh", "检查更新", "refresh")}
        ${button("detail-migrate", "迁移图源", "swap")}
        ${button("detail-notes", "笔记", "notes")}
      </div>
    </header>`;
    const creators = `<div class="detail-creators" data-testid="detail-creators">${b.author ? `<span>作者 ${button("detail-author", b.author)}</span>` : ""}${b.artist ? `<span>画师 ${button("detail-artist", b.artist)}</span>` : ""}</div>`;
    const tags = `<div class="detail-tags" data-testid="detail-tags">${(b.genre || []).map((tag, i) => `<span class="detail-tag"><button data-action="detail-tag-search" data-id="${esc(tag)}" data-testid="detail-tag-${i}">${esc(tag)}</button><button class="tag-copy" data-action="detail-tag-copy" data-id="${esc(tag)}" aria-label="复制标签 ${esc(tag)}" title="复制标签 ${esc(tag)}">${icon("link")}</button></span>`).join("")}</div>`;
    const hero = `<div class="hero"><div class="detail-cover-wrap">${cover(b)}<div class="detail-cover-edit detail-menu-anchor">${button("detail-cover-menu", "编辑封面", "edit")}${detailMenuMarkup("cover", `<button data-action="cover-replace" data-testid="cover-replace">编辑封面</button><button data-action="cover-delete" data-testid="cover-delete">删除自定义封面</button>`)}</div></div><div class="hero-info"><h2>${esc(b.title)}</h2>${creators}<p class="detail-source">${b.complete ? "已完结" : "连载中"} · 来源：${esc(b.source)}</p><p class="detail-description">${esc(b.description || "")}</p>${tags}</div></div>`;
    const actionRow = `<div class="detail-action-row"><button data-testid="detail-library" data-action="detail-library" class="detail-library-action">${icon(b.favorite ? "heartFilled" : "heart")}<span>${b.favorite ? "已加入书架" : "加入书架"}</span></button>${b.favorite ? `${button("detail-categories", "编辑分类", "category")}${button("detail-fetch-interval", "编辑更新间隔", "history")}` : ""}${button("detail-tracking", "追踪", "sync")}${!b.local ? `${button("detail-action-open-link", "在浏览器打开", "link")}${button("detail-action-copy-link", "复制链接", "link")}${button("detail-action-share-link", "分享链接", "share")}` : ""}</div>`;
    const allSelectedDownloaded = detailSelected.every((id) => b.chapters.find((c) => c.id === id)?.download);
    const selection = detailSelected.length ? `<div class="detail-selection" data-testid="detail-selection"><button data-action="detail-select-close" aria-label="退出章节选择">${icon("close")}</button><strong data-testid="detail-selection-count">已选 ${detailSelected.length}</strong>${button("detail-batch-bookmark", "为所选章节添加书签", "bookmarkFilled")}${button("detail-batch-read", "标记已读", "doneAll")}${button("detail-batch-unread", "标记未读", "ring")}${button("detail-batch-below", "所选及以下标记已读", "down")}${button("detail-batch-download", allSelectedDownloaded ? "删除所选下载" : "下载所选章节", allSelectedDownloaded ? "trash" : "download")}</div>` : "";
    return `${toolbar}${summary()}<div class="detail" data-testid="detail-scroll" data-book-id="${esc(b.id)}">${hero}${actionRow}<div class="detail-reading-mode"><span>阅读模式</span><div class="detail-menu-anchor"><button data-action="detail-reading-mode" data-testid="detail-reading-mode">${esc(b.readingMode || "默认")}</button>${detailMenuMarkup("reading", readingModes)}</div></div><div class="detail-chapter-heading" data-testid="chapter-count">Chapters (${chapters.length}${chapters.length !== b.chapters.length ? "/" + b.chapters.length : ""})</div><div class="chapter-list">${chapters.map((c) => chapterRow(c, b)).join("")}</div>${b.detachedDownloads?.length ? `<p class="detail-detached">目录已移除，保留本地下载：${esc(b.detachedDownloads.join("、"))}</p>` : ""}</div>${unread && !detailSelected.length ? `<button class="detail-fab" data-action="detail-continue" data-testid="detail-continue">${icon("play")}${b.chapters.some((c) => c.read) || unread.page > 0 ? "继续阅读" : "开始阅读"}</button>` : ""}${selection}`;
  }
  function readerView() {
    const b = book(),
      c = b.chapters.find((c) => c.id === reader);
    return `<header class="bar">${button("reader-back", "返回详情", "back")}<h1>阅读预览</h1></header><div class="reader"><h2>${esc(b.title)}</h2><p>${esc(c.name)} · 第 ${c.page + 1} 页</p><div class="reader-page">本地阅读预览<br><small>不连接真实漫画或阅读引擎</small></div>${button("reader-next", "下一页")}${button("reader-finish", "标记本话已读")}</div>`;
  }
  function render() {
    const old = document.querySelector('[data-testid="library-scroll"]');
    const oldDetail = document.querySelector('[data-testid="detail-scroll"]');
    if (oldDetail) detailScroll[oldDetail.dataset.bookId] = oldDetail.scrollTop;
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
    app.innerHTML = `<div class="platform-bar"><span>${s.platform === "windows" ? "Mihon Desktop" : "9:41"}</span><span>${s.platform === "windows" ? "—　□　×" : "●　▰"}</span></div><div class="app-content" id="content" ${modal ? "inert" : ""}>${s.route === "reader" ? readerView() : s.route === "detail" ? detail() : library()}${s.route !== "reader" ? nav() : ""}<div class="status" role="status" data-testid="notice">${esc(s.notice)}</div></div><div id="modal-root"></div>`;
    const newDetail = document.querySelector('[data-testid="detail-scroll"]');
    if (newDetail) newDetail.scrollTop = detailScroll[newDetail.dataset.bookId] || 0;
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
    if (modal === "detail-categories")
      return `<p>为「${esc(book()?.title)}」选择所属分类。</p>${s.categories.map((c) => `<label class="setting"><span>${esc(c.name)}</span><input type="checkbox" data-detail-category="${c.id}" data-testid="detail-category-${c.id}" ${detailCategoryDraft.includes(c.id) ? "checked" : ""}></label>`).join("")}<div class="choice">${button("detail-category-save", "确定")}${button("modal-cancel", "取消")}</div>`;
    if (modal === "detail-interval")
      return `<p>仅影响当前作品的预计更新周期。</p>${[0, 1, 2, 7, 14, 30].map((n) => `<label class="setting"><span>${n ? n + " 天" : "默认"}</span><input type="radio" name="detail-interval" value="${n}" data-testid="detail-interval-${n}" ${detailIntervalDraft === n ? "checked" : ""}></label>`).join("")}<div class="choice">${button("detail-interval-save", "确定")}${button("modal-cancel", "取消")}</div>`;
    if (modal === "detail-tracking")
      return `<p>当前作品的追踪服务样本</p>${book()?.tracks.length ? book().tracks.map((t) => `<div class="setting"><span>${esc(t.name)}</span><strong>${(t.value / t.scale * 10).toFixed(1)} / 10</strong></div>`).join("") : "<p>尚未追踪这部作品。</p>"}<p class="muted">此演示不连接追踪服务。</p>`;
    if (modal === "detail-notes")
      return `<p>${esc(book()?.title)}</p><textarea data-testid="detail-notes-input" aria-label="作品笔记" placeholder="写下这部作品的笔记" rows="6">${esc(detailNotesDraft)}</textarea><div class="choice">${button("detail-notes-save", "保存")}${button("modal-cancel", "取消")}</div>`;
    if (modal === "detail-migrate")
      return `<p>选择目标图源。本地样本仅演示入口与确认流程。</p>${button("detail-migrate-target", "示例备用图源 · 中文")}${button("modal-cancel", "取消")}`;
    if (modal === "detail-migrate-results")
      return `<p>找到 1 个同名结果</p>${button("detail-migrate-match", book()?.title || "示例作品")}${button("modal-cancel", "取消")}`;
    if (modal === "detail-information")
      return `<p>${esc(confirmText)}</p><p class="muted">当前仅为本地交互样本；此页面未接入真实外部服务。</p>`;
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
    "detail-categories": "编辑分类",
    "detail-interval": "更新间隔",
    "detail-tracking": "追踪",
    "detail-notes": "笔记",
    "detail-migrate": "迁移到图源",
    "detail-migrate-results": "选择匹配作品",
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
  function toggleDetailChapter(id) {
    if (!book()?.chapters.some((c) => c.id === id)) return;
    detailSelected = detailSelected.includes(id)
      ? detailSelected.filter((x) => x !== id)
      : [...detailSelected, id];
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
  function detailDownload(ids) {
    const b = book();
    let changed = 0;
    for (const id of ids) {
      const c = b?.chapters.find((item) => item.id === id);
      if (!c || c.download || c.downloadStatus === "queued" || c.downloadStatus === "downloading") continue;
      c.downloadStatus = null;
      c.download = `${b.id}-file-${c.number}`;
      changed++;
    }
    detailSelected = [];
    detailNotice(`已将 ${changed} 个章节加入本地下载样本`);
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
    if (action === "detail-cover-menu" || action === "detail-download-menu" ||
        action === "detail-filter-menu" || action === "detail-reading-mode") {
      const name = action === "detail-cover-menu" ? "cover" :
        action === "detail-download-menu" ? "download" :
        action === "detail-filter-menu" ? "filter" : "reading";
      detailMenu = detailMenu === name ? null : name;
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
      detailMenu = null;
      render();
      return;
    }
    if (action.startsWith("detail-display-")) {
      detailOptions(book()).display = action.slice(15);
      detailMenu = null;
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
      const chapters = [...book().chapters].sort((a, z) => a.number - z.number);
      const eligible = type === "bookmarked"
        ? chapters.filter((c) => c.bookmark && !c.read)
        : chapters.filter((c) => !c.read);
      const limit = type.startsWith("next-") ? Number(type.slice(5)) : eligible.length;
      detailMenu = null;
      detailDownload(eligible.slice(0, limit).map((c) => c.id));
      return;
    }
    if (action === "detail-open-link" || action === "detail-action-open-link") {
      detailInfo("在浏览器打开作品源链接：本地样本不请求真实图源。");
      return;
    }
    if (action === "detail-copy-link" || action === "detail-action-copy-link") {
      detailNotice("示例作品链接已复制（本地模拟）");
      return;
    }
    if (action === "detail-share-link" || action === "detail-action-share-link") {
      detailInfo("分享作品源链接：本地样本不调用系统分享。");
      return;
    }
    if (action === "detail-author" || action === "detail-artist") {
      detailInfo(`${action === "detail-author" ? "作者" : "画师"}：${book()[action === "detail-author" ? "author" : "artist"]}。作者资料页不在此本地样本中。`);
      return;
    }
    if (action === "detail-tag-search") {
      detailInfo(`搜索标签「${id}」：搜索结果页不在此本地样本中。`);
      return;
    }
    if (action === "detail-tag-copy") {
      detailNotice(`已复制标签「${id}」（本地模拟）`);
      return;
    }
    if (action === "detail-library") {
      book().favorite = !book().favorite;
      detailNotice(book().favorite ? "已加入书架" : "已取消收藏；本地下载文件保留");
      return;
    }
    if (action === "detail-categories") {
      detailCategoryDraft = [...book().categories];
      openModal("detail-categories");
      return;
    }
    if (action === "detail-category-save") {
      const b = book();
      b.categories = detailCategoryDraft.length ? [...detailCategoryDraft] : [0];
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
      detailNotice("作品更新间隔已保存");
      return;
    }
    if (action === "detail-tracking") {
      openModal("detail-tracking");
      return;
    }
    if (action === "detail-notes") {
      detailNotesDraft = book().notes || "";
      openModal("detail-notes");
      return;
    }
    if (action === "detail-notes-save") {
      book().notes = detailNotesDraft;
      closeModal();
      detailNotice("作品笔记已保存");
      return;
    }
    if (action === "detail-migrate") {
      openModal("detail-migrate");
      return;
    }
    if (action === "detail-migrate-target") {
      openModal("detail-migrate-results", true);
      return;
    }
    if (action === "detail-migrate-match") {
      confirm("确认把此作品迁移到「示例备用图源」？本地样本只修改展示来源。", () => {
        book().source = "示例备用图源";
        s.notice = "作品来源已更新（本地模拟）";
      });
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
      render();
      return;
    }
    if (action === "detail-select-close") {
      detailSelected = [];
      render();
      return;
    }
    if (action.startsWith("detail-batch-")) {
      const b = book(), ids = [...detailSelected];
      const selected = b.chapters.filter((c) => ids.includes(c.id));
      if (action === "detail-batch-bookmark") {
        const shouldBookmark = selected.some((c) => !c.bookmark);
        selected.forEach((c) => c.bookmark = shouldBookmark);
        detailSelected = [];
        detailNotice(`已${shouldBookmark ? "添加" : "取消"} ${selected.length} 个章节的书签`);
      } else if (action === "detail-batch-read" || action === "detail-batch-unread") {
        const read = action === "detail-batch-read";
        selected.forEach((c) => { c.read = read; if (read) c.page = 0; });
        b.unread = b.chapters.filter((c) => !c.read).length;
        detailSelected = [];
        detailNotice(`已将 ${selected.length} 个章节标为${read ? "已读" : "未读"}`);
      } else if (action === "detail-batch-below") {
        const rows = displayedChapters(b);
        const first = rows.findIndex((c) => ids.includes(c.id));
        if (first >= 0) rows.slice(first).forEach((c) => c.read = true);
        b.unread = b.chapters.filter((c) => !c.read).length;
        detailSelected = [];
        detailNotice("所选及以下章节已标为已读");
      } else if (selected.every((c) => c.download)) {
        confirmSnapshot = ids;
        confirm(`删除所选 ${ids.length} 个章节在本机的下载文件？阅读记录和作品保留。`, () => {
          let removed = 0;
          b.chapters.filter((c) => confirmSnapshot.includes(c.id) && c.download)
            .forEach((c) => { c.download = null; removed++; });
          detailSelected = [];
          s.notice = `已移除 ${removed} 个本地下载`;
        });
      } else detailDownload(ids);
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
      s.route = "library";
      detailSelected = [];
      detailMenu = null;
      detailBookId = null;
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
      if (suppressChapterClick === id) {
        suppressChapterClick = null;
        return;
      }
      if (detailSelected.length) {
        toggleDetailChapter(id);
        return;
      }
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
          toggleDetailChapter(start.id);
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
      if (s.route === "detail" && detailMenu) {
        detailMenu = null;
        render();
        return;
      }
      if (s.route === "detail" && detailSelected.length) {
        detailSelected = [];
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
    detailSelected = [];
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
