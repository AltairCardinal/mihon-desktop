const test = require("node:test");
const assert = require("node:assert/strict");
const P = require("./detail-parity-model.js");

function book() {
  return {
    id: "A", title: "星海手记", source: "旧源", favorite: true,
    categories: [1], custom: true, notes: "保留笔记",
    chapterOptions: { read: true, unread: true, bookmark: false, download: false, scanlators: {}, sort: "number", ascending: true, display: "name" },
    chapters: [
      { id: "A-1", number: 1, sourceOrder: 2, name: "一", dateUpload: 1, read: true, bookmark: true, download: "file-1", page: 0, scanlator: "甲" },
      { id: "A-2", number: 2, sourceOrder: 1, name: "二", dateUpload: 2, read: false, bookmark: true, download: "file-2", page: 0, scanlator: "甲" },
      { id: "A-3", number: 3, sourceOrder: 0, name: "三", dateUpload: 3, read: false, bookmark: false, download: null, page: 0, scanlator: "乙" },
      { id: "A-4", number: 4, sourceOrder: -1, name: "四", dateUpload: 4, read: false, bookmark: true, download: null, page: 0, scanlator: "乙" },
    ],
  };
}

test("三态章节筛选、全局仅下载、译制组排除和当前顺序共同决定可见列表", () => {
  const b = book();
  const s = { downloadOnly: false, prefs: {} };
  const o = P.normalizeOptions(b.chapterOptions);
  assert.equal(o.unreadFilter, 0);
  o.unreadFilter = 1;
  o.bookmarkFilter = 1;
  assert.deepEqual(P.displayedChapters(s, b).map((c) => c.id), ["A-2", "A-4"]);
  o.bookmarkFilter = -1;
  assert.deepEqual(P.displayedChapters(s, b).map((c) => c.id), ["A-3"]);
  o.bookmarkFilter = 0;
  s.downloadOnly = true;
  assert.deepEqual(P.displayedChapters(s, b).map((c) => c.id), ["A-2"]);
  o.scanlators["甲"] = false;
  assert.deepEqual(P.displayedChapters(s, b).map((c) => c.id), []);
});

test("下载接下来章节先排除已下载，书签包含已读，筛选偏好改变工作集", () => {
  const b = book();
  const s = { downloadOnly: false, prefs: { downloadUsesVisible: false } };
  assert.deepEqual(P.downloadCandidates(s, b, "next-1").map((c) => c.id), ["A-3"]);
  b.chapters[0].download = null;
  assert.deepEqual(P.downloadCandidates(s, b, "bookmarked").map((c) => c.id), ["A-1", "A-4"]);
  P.normalizeOptions(b.chapterOptions).bookmarkFilter = 1;
  s.prefs.downloadUsesVisible = true;
  assert.deepEqual(P.downloadCandidates(s, b, "next-1").map((c) => c.id), ["A-4"]);
});

test("继续阅读遵守当前可见范围，隐藏或已读项不成为目标", () => {
  const b = book();
  const s = { downloadOnly: true, prefs: {} };
  assert.equal(P.nextReadable(s, b)?.id, "A-2");
  P.normalizeOptions(b.chapterOptions).bookmarkFilter = -1;
  assert.equal(P.nextReadable(s, b), null);
});

test("Ctrl 进入选择、反向 Shift 范围与筛选后锚点失效", () => {
  const ids = ["A-1", "A-2", "A-3", "A-4"];
  let state = { ids: [], anchor: null };
  state = P.selectChapter(state, ids, "A-4", { ctrl: true });
  assert.deepEqual(state.ids, ["A-4"]);
  state = P.selectChapter(state, ids, "A-2", { shift: true });
  assert.deepEqual(state.ids, ["A-2", "A-3", "A-4"]);
  state = P.pruneSelection(state, ["A-1"]);
  assert.deepEqual(state, { ids: [], anchor: null });
  state = P.selectChapter(state, ["A-1"], "A-1", { shift: true });
  assert.deepEqual(state, { ids: ["A-1"], anchor: "A-1" });
  state = P.selectChapter(state, ["A-1"], "A-1", {});
  assert.deepEqual(state, { ids: [], anchor: null });
});

test("标记之前已读排除指针，混合下载批量分别处理适用集合", () => {
  const b = book();
  assert.deepEqual(P.previousChapters(b.chapters, "A-3").map((c) => c.id), ["A-1", "A-2"]);
  const plan = P.batchPlan(b.chapters, ["A-2", "A-3", "missing"]);
  assert.deepEqual(plan.toDownload.map((c) => c.id), ["A-3"]);
  assert.deepEqual(plan.toDelete.map((c) => c.id), ["A-2"]);
  assert.equal(plan.missing, 1);
});

test("复制与迁移保留原作品身份；选项控制类别、笔记、章节状态和旧下载", () => {
  const original = book();
  const target = { source: "新源", url: "/new", title: "星海新刊", chapters: [
    { id: "N-1", number: 1, name: "一", read: false, bookmark: false, page: 0, download: null },
    { id: "N-2", number: 2, name: "二", read: false, bookmark: false, page: 0, download: null },
  ] };
  const options = { chapters: true, categories: true, cover: true, notes: true, removeDownloads: true };
  const copied = P.migrate(original, target, options, false);
  assert.equal(copied.current.source, "旧源");
  assert.equal(copied.current.chapters[0].download, "file-1");
  assert.equal(copied.target.source, "新源");
  assert.equal(copied.target.chapters[0].read, true);
  assert.deepEqual(copied.target.categories, [1]);
  assert.equal(copied.target.notes, "保留笔记");
  const moved = P.migrate(original, target, options, true);
  assert.equal(moved.current.favorite, false);
  assert.equal(moved.current.chapters[0].download, null);
  assert.equal(original.favorite, true);
  assert.equal(target.chapters[0].read, false);
});

test("追踪章节上限来自服务，手动已读遵守自动/询问/关闭策略", () => {
  assert.equal(P.validateTrackerChapter(75, 100, 3), true);
  assert.equal(P.validateTrackerChapter(101, 100, 3), false);
  assert.deepEqual(P.readTrackingEffect("ask", 75, 30), { kind: "ask", chapter: 75 });
  assert.deepEqual(P.readTrackingEffect("auto", 75, 30), { kind: "update", chapter: 75 });
  assert.deepEqual(P.readTrackingEffect("off", 75, 30), { kind: "none" });
});
