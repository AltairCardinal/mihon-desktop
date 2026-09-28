(function (root) {
  "use strict";
  const esc = (value) => String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[char]);
  const action = (id, label, extra = "") =>
    `<button type="button" data-action="${esc(id)}" data-testid="${esc(id)}" ${extra}>${esc(label)}</button>`;
  const triLabel = (value, included, excluded) =>
    value === 1 ? included : value === -1 ? excluded : "不限";

  function chapterSettings(book, state, tab) {
    const options = root.DetailParityModel.normalizeOptions(book.chapterOptions);
    if (tab === "filter") {
      const rows = [
        ["unread", "阅读状态", options.unreadFilter, "仅未读", "仅已读"],
        ["bookmark", "书签", options.bookmarkFilter, "仅有书签", "排除书签"],
        ["download", "下载", options.downloadFilter, "仅已下载", "仅未下载"],
      ];
      return `${rows.map(([id, label, value, included, excluded]) =>
        `<button class="tri" data-action="chapter-filter-cycle" data-id="${id}" data-testid="chapter-filter-${id}" ${id === "download" && state.downloadOnly ? "disabled" : ""}><span>${label}</span><em>${triLabel(value, included, excluded)}</em></button>`,
      ).join("")}${state.downloadOnly ? `<p class="muted">全局仅下载已开启，下载筛选由书架设置控制。</p>` : ""}${action("chapter-scanlator-open", "排除译制组")}${action("chapter-settings-default-open", "设为默认")}${action("chapter-settings-reset", "重置为默认")}`;
    }
    if (tab === "sort") {
      return [["source", "源顺序"], ["number", "章节号"], ["date", "上传日期"], ["alphabet", "字母顺序"]]
        .map(([id, label]) => `<button class="tri" data-action="detail-sort-${id}" data-testid="chapter-sort-${id}"><span>${label}</span><em>${options.sort === id ? options.ascending ? "升序 ↑" : "降序 ↓" : ""}</em></button>`).join("");
    }
    return [["name", "标题"], ["number", "章节号"]]
      .map(([id, label]) => `<button class="tri" data-action="detail-display-${id}" data-testid="chapter-display-${id}" aria-checked="${options.display === id}"><span>${label}</span><em>${options.display === id ? "已选" : ""}</em></button>`).join("");
  }

  function scanlatorDialog(book, draft) {
    const names = [...new Set(book.chapters.map((chapter) => chapter.scanlator || "未知"))].sort();
    return `<p>勾选要排除的译制组；确认后才改变章节列表。</p>${names.map((name) =>
      `<label class="setting"><span>${esc(name)}</span><input type="checkbox" data-scanlator-name="${esc(name)}" ${draft.has(name) ? "checked" : ""}></label>`,
    ).join("")}<div class="choice">${action("chapter-scanlator-all", "全选排除")}${action("chapter-scanlator-reset", "重置")}${action("chapter-scanlator-save", "确定")}${action("modal-cancel", "取消")}</div>`;
  }

  function batchBar(book, selected, displayed) {
    if (!selected.length) return "";
    const visibleIds = new Set(displayed.map((chapter) => chapter.id));
    const included = book.chapters.filter((chapter) => selected.includes(chapter.id) && visibleIds.has(chapter.id));
    const anyUnbookmarked = included.some((chapter) => !chapter.bookmark);
    const anyUnread = included.some((chapter) => !chapter.read);
    const anyRead = included.some((chapter) => chapter.read || chapter.page > 0);
    const plan = root.DetailParityModel.batchPlan(book.chapters, selected, book);
    return `<div class="detail-selection" data-testid="detail-selection">${action("detail-select-close", "退出选择")}
      <strong data-testid="detail-selection-count">已选 ${included.length}</strong>
      ${action("detail-batch-bookmark", anyUnbookmarked ? "添加书签" : "移除书签")}
      ${anyUnread ? action("detail-batch-read", "标记已读") : ""}
      ${anyRead ? action("detail-batch-unread", "标记未读") : ""}
      ${included.length === 1 ? action("detail-batch-previous", "标记之前已读") : ""}
      ${plan.toDownload.length ? action("detail-batch-download", `下载未下载项 (${plan.toDownload.length})`) : ""}
      ${plan.toDelete.length ? action("detail-batch-delete", `删除已下载项 (${plan.toDelete.length})`) : ""}
      </div>`;
  }

  function chapterContext(chapter, book) {
    const pending = ["queued", "downloading"].includes(chapter.downloadStatus);
    return `<div class="detail-popup chapter-context" role="menu" data-testid="chapter-context-menu">
      ${action("chapter-context-read", chapter.read ? "标记未读" : "标记已读")}
      ${action("chapter-context-bookmark", chapter.bookmark ? "移除书签" : "添加书签")}
      ${book.local || book.sourceMissing || chapter.external ? "" : pending
        ? action("chapter-context-cancel", "取消下载")
        : chapter.download ? action("chapter-context-delete", "删除下载")
          : `${action("chapter-context-download", "下载")}${action("chapter-context-now", "立即下载")}`}
    </div>`;
  }

  function tracking(book, draft) {
    const service = draft.service || book.tracks[0]?.name || "AniList";
    const current = book.tracks.find((item) => item.name === service) || null;
    const options = ["AniList", "MyAnimeList", "MangaUpdates"];
    return `<p class="muted">本地追踪服务样本，不连接真实账号。</p>
      <label class="setting">服务 <select data-testid="tracking-service" data-track-field="service">${options.map((name) =>
        `<option value="${name}" ${service === name ? "selected" : ""}>${name}</option>`,
      ).join("")}</select></label>
      <p data-testid="tracking-remote-state">${current ? `${esc(current.status || "阅读中")} · ${current.progress || 0} / ${current.totalChapters || 100} 章 · ${Number(current.value / current.scale * 10).toFixed(1)} / 10` : "当前服务尚未绑定"}</p>
      <label class="setting">搜索作品 <input data-testid="tracking-search" data-track-field="query" value="${esc(draft.query)}"></label>
      ${action("tracking-search-submit", "搜索并选择作品")}${draft.results ? `<div data-testid="tracking-results">${draft.results.map((name, index) => action(`tracking-result-${index}`, name)).join("")}</div>` : ""}
      <label class="setting">追踪章节 <input type="number" min="0" step="1" data-testid="tracking-chapter-input" data-track-field="chapter" value="${esc(draft.chapter)}"></label>
      <p class="muted">范围按追踪服务总章节数 ${current?.totalChapters || 100} 判断，作品章节条目数不限制输入。</p>
      <label class="setting">状态 <select data-track-field="status">${["阅读中", "已完成", "计划阅读", "暂停"].map((name) => `<option ${draft.status === name ? "selected" : ""}>${name}</option>`).join("")}</select></label>
      <label class="setting">评分 <input type="number" min="0" max="10" step="0.1" data-track-field="score" value="${esc(draft.score)}"></label>
      <label class="setting">开始日期 <input type="date" data-track-field="startDate" value="${esc(draft.startDate || "")}"></label>
      <label class="setting">结束日期 <input type="date" data-track-field="finishDate" value="${esc(draft.finishDate || "")}"></label>
      <label class="setting">私密记录 <input type="checkbox" data-track-field="private" ${draft.private ? "checked" : ""}></label>
      <div class="choice">${action("tracking-save", current ? "保存追踪" : "绑定追踪")}${current ? `${action("tracking-open-link", "打开追踪链接")}${action("tracking-copy-link", "复制追踪链接")}${action("tracking-refresh", "刷新远端进度")}${action("tracking-unbind", "解除绑定")}` : ""}</div>`;
  }

  function migrationSearch(draft) {
    return `<p>从目标图源搜索漫画；本地结果按查询词和页码演示。</p>
      <label class="setting">搜索词 <input data-testid="migration-query" data-migration-field="query" value="${esc(draft.query)}"></label>
      <div class="choice">${action("migration-search", "搜索")}${action("migration-change-source", "更换图源")}</div>
      <p data-testid="migration-result-summary">${draft.error ? esc(draft.error) : draft.results?.length ? `第 ${draft.page} 页 · ${draft.results.length} 个结果` : "请输入搜索词并搜索"}</p>
      ${(draft.results || []).map((result, index) => action(`detail-migrate-match-${index}`, result.title)).join("")}
      ${draft.results?.length ? action("migration-next-page", "下一页") : ""}`;
  }

  function migrationConfirm(sourceBook, draft) {
    const rows = [
      ["chapters", "复制章节阅读状态"], ["categories", "复制分类"],
      ["cover", "复制自定义封面"], ["notes", "复制笔记"],
      ["removeDownloads", "迁移后删除旧下载"],
    ];
    return `<p>从「${esc(sourceBook.title)}」到「${esc(draft.match?.title)}」。目标作品将拥有独立身份与章节。</p>
      ${draft.progress ? `<p role="status" data-testid="migration-progress">${esc(draft.progress)}</p>` : ""}
      ${draft.error ? `<p role="alert" data-testid="migration-error">${esc(draft.error)}</p>` : ""}
      ${rows.map(([key, label]) => `<label class="setting"><span>${label}</span><input type="checkbox" data-migration-option="${key}" ${draft.options[key] ? "checked" : ""}></label>`).join("")}
      <div class="choice">${action("migration-copy", draft.error ? "重试复制" : "复制", draft.progress ? "disabled" : "")}${action("migration-move", draft.error ? "重试迁移" : "迁移", draft.progress ? "disabled" : "")}${action("modal-cancel", "取消")}</div>`;
  }

  root.DetailParityView = { esc, action, chapterSettings, scanlatorDialog, batchBar, chapterContext, tracking, migrationSearch, migrationConfirm };
})(typeof window === "undefined" ? globalThis : window);
