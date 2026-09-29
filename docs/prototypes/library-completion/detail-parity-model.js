(function (root) {
  "use strict";
  function normalizeOptions(options = {}) {
    if (options.unreadFilter === undefined) {
      options.unreadFilter = options.read === false && options.unread !== false ? 1
        : options.unread === false && options.read !== false ? -1 : 0;
    }
    if (options.bookmarkFilter === undefined) options.bookmarkFilter = options.bookmark ? 1 : 0;
    if (options.downloadFilter === undefined) options.downloadFilter = options.download ? 1 : 0;
    options.scanlators ||= {};
    options.sort ||= "source";
    options.display ||= "name";
    options.ascending ??= false;
    return options;
  }

  function sortedChapters(chapters, options) {
    const compare = {
      source: (a, b) => (b.sourceOrder ?? 0) - (a.sourceOrder ?? 0),
      number: (a, b) => a.number - b.number,
      date: (a, b) => (a.dateUpload || 0) - (b.dateUpload || 0),
      alphabet: (a, b) => a.name.localeCompare(b.name, "zh"),
    }[options.sort] || ((a, b) => a.number - b.number);
    return [...chapters].sort((a, b) => (options.ascending ? 1 : -1) * compare(a, b));
  }

  function displayedChapters(state, book) {
    const options = normalizeOptions(book.chapterOptions ||= {});
    const tri = (value, flag) => value === 0 || (value === 1 ? flag : !flag);
    return sortedChapters(book.chapters.filter((chapter) =>
      tri(options.unreadFilter, !chapter.read) &&
      tri(options.bookmarkFilter, !!chapter.bookmark) &&
      (state.downloadOnly || book.local ? !!chapter.download || !!book.local : tri(options.downloadFilter, !!chapter.download)) &&
      (!chapter.scanlator?.trim() || options.scanlators[chapter.scanlator.trim()] !== false),
    ), options);
  }

  function storyOrder(chapters, book) {
    const options = normalizeOptions(book.chapterOptions ||= {});
    const displayOrder = sortedChapters(chapters, options);
    return options.ascending ? displayOrder : displayOrder.reverse();
  }

  function downloadable(chapter, book) {
    return !book.local && !book.sourceMissing && !chapter.external && !chapter.download &&
      !["queued", "downloading"].includes(chapter.downloadStatus);
  }

  function downloadCandidates(state, book, type) {
    const universe = state.prefs?.downloadUsesVisible === false
      ? book.chapters : displayedChapters(state, book);
    const eligible = storyOrder(universe, book).filter((chapter) =>
      downloadable(chapter, book) &&
      (type === "bookmarked" ? !!chapter.bookmark : !chapter.read),
    );
    const count = type.startsWith("next-") ? Number(type.slice(5)) : eligible.length;
    return eligible.slice(0, count);
  }

  function nextReadable(state, book) {
    return storyOrder(displayedChapters(state, book), book)
      .find((chapter) => !chapter.read) || null;
  }

  function selectChapter(selection, visibleIds, id, event = {}) {
    if (!visibleIds.includes(id)) return selection;
    const chosen = new Set(selection.ids);
    let anchor = selection.anchor;
    if (event.shift || event.long && chosen.size) {
      if (!visibleIds.includes(anchor)) anchor = id;
      const start = visibleIds.indexOf(anchor), end = visibleIds.indexOf(id);
      visibleIds.slice(Math.min(start, end), Math.max(start, end) + 1)
        .forEach((value) => chosen.add(value));
    } else if (event.ctrl || event.long || chosen.size) {
      if (chosen.has(id)) chosen.delete(id);
      else chosen.add(id);
      anchor = id;
    } else {
      return { ids: [], anchor: null, open: id };
    }
    return { ids: visibleIds.filter((value) => chosen.has(value)), anchor: chosen.size ? anchor : null };
  }

  function pruneSelection(selection, visibleIds) {
    const ids = selection.ids.filter((id) => visibleIds.includes(id));
    return { ids, anchor: ids.length && visibleIds.includes(selection.anchor) ? selection.anchor : null };
  }

  function previousChapters(chapters, pointerId) {
    const pointer = chapters.find((chapter) => chapter.id === pointerId);
    return pointer ? chapters.filter((chapter) => chapter.number < pointer.number)
      .sort((a, b) => a.number - b.number) : [];
  }

  function batchPlan(chapters, selectedIds, book = {}) {
    const selected = chapters.filter((chapter) => selectedIds.includes(chapter.id));
    return {
      selected,
      toDownload: selected.filter((chapter) => downloadable(chapter, book)),
      toDelete: selected.filter((chapter) => !!chapter.download),
      missing: selectedIds.length - selected.length,
    };
  }

  function migrate(original, listedTarget, options, replace) {
    const current = structuredClone(original);
    const target = structuredClone(listedTarget);
    target.id ||= `${original.id}-migrated`;
    target.favorite = true;
    target.categories = options.categories ? [...original.categories] : [];
    target.notes = options.notes ? original.notes : "";
    target.custom = !!options.cover && !!original.custom;
    target.chapters = target.chapters.map((chapter) => {
      const previous = original.chapters.find((old) => old.number === chapter.number);
      return previous && options.chapters
        ? { ...chapter, read: previous.read, bookmark: previous.bookmark, page: previous.page }
        : chapter;
    });
    if (replace) {
      current.favorite = false;
      if (options.removeDownloads) {
        current.chapters.forEach((chapter) => { chapter.download = null; });
      }
    }
    return { current, target };
  }

  function validateTrackerChapter(chapter, serviceTotal) {
    return Number.isFinite(chapter) && chapter >= 0 &&
      (serviceTotal == null || serviceTotal <= 0 || chapter <= serviceTotal);
  }

  function readTrackingEffect(policy, chapter, current = 0) {
    if (!Number.isFinite(chapter) || chapter <= current || policy === "off") return { kind: "none" };
    return { kind: policy === "auto" ? "update" : "ask", chapter };
  }
  const api = {
    normalizeOptions, displayedChapters, downloadCandidates, nextReadable,
    selectChapter, pruneSelection, previousChapters, batchPlan, migrate,
    validateTrackerChapter, readTrackingEffect,
  };
  if (typeof module === "object") module.exports = api;
  root.DetailParityModel = api;
})(typeof window === "undefined" ? globalThis : window);
