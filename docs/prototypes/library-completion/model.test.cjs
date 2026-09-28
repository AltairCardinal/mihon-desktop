const test = require("node:test");
const assert = require("node:assert/strict");
const M = require("./model.js");

test("审查修复：评分升序与降序统一，分类排序方向隔离并清理", () => {
  const s = M.create();
  M.scenario(s, "multi-tracker");
  s.categorySort[1] = "score";
  const scored = () =>
    M.visible(s)
      .filter((b) => b.tracks.length)
      .map(M.score);
  assert.deepEqual(scored(), [8, 8, 9]);
  s.categoryReverse = { 1: true };
  assert.deepEqual(scored(), [9, 8, 8]);
  s.category = 2;
  s.books
    .filter((b) => b.id === "A" || b.id === "B")
    .forEach((b) => b.categories.push(2));
  s.categorySort[2] = "score";
  assert.deepEqual(scored(), [8, 9]);
  M.save(s, "perCategory", false);
  assert.deepEqual(s.categoryReverse, {});
  M.save(s, "perCategory", true);
  s.category = 1;
  assert.deepEqual(
    M.visible(s).map((b) => b.id),
    ["A", "B", "C", "D", "E"],
  );
});

test("审查修复：中断清理恢复同时移除分类排序方向", () => {
  const s = M.create();
  s.categoryReverse = { 1: true, 2: false };
  s.sortInterrupted = true;
  M.save(s, "perCategory", false);
  assert.deepEqual(M.restart(s).categoryReverse, {});
});

test("重新载入场景清除故障条件，目录同步采用最新阅读状态", () => {
  const s = M.create();
  M.scenario(s, "loading");
  M.scenario(s, "baseline");
  assert.equal(!!s.loading, false);
  M.scenario(s, "custom-period");
  M.scenario(s, "baseline");
  assert.equal(!!s.customPeriod, false);
  s.source = "chapter-change";
  M.start(s, "category");
  s.books[0].chapters[0].page = 19;
  s.books[0].chapters[0].bookmark = false;
  M.step(s);
  const chapter = s.books[0].chapters.find((c) => c.number === 1);
  assert.equal(chapter.page, 19);
  assert.equal(chapter.bookmark, false);
});

test("未知范围旧更新保留，用户新请求不会伪称恢复", () => {
  const s = M.create();
  M.scenario(s, "legacy-no-scope");
  const old = structuredClone(s.job);
  M.start(s, "all");
  assert.deepEqual(s.archivedJobs[0], old);
  assert.equal(s.job.type, "all");
});

test("固定锚点替换与追加、普通点击退出，隐藏选择保留", () => {
  const s = M.create();
  M.select(s, "B", { ctrl: true });
  M.select(s, "E", { shift: true });
  assert.deepEqual(s.selected, ["B", "C", "D", "E"]);
  M.select(s, "C", { shift: true });
  assert.deepEqual(s.selected, ["B", "C"]);
  M.select(s, "E", { ctrl: true });
  M.select(s, "D", { ctrl: true, shift: true });
  assert.deepEqual(new Set(s.selected), new Set(["B", "C", "D", "E"]));
  s.category = 2;
  M.select(s, "F", { shift: true });
  assert.ok(s.selected.includes("B"));
  assert.ok(s.selected.includes("F"));
  M.select(s, "F", {});
  assert.equal(s.route, "detail");
  assert.equal(s.selected.length, 0);
});
test("更新逐本失败隔离、重试固定集合、取消与重启", () => {
  const s = M.create();
  M.scenario(s, "partial-failure");
  M.start(s, "all");
  while (s.job.status === "running") M.step(s);
  assert.equal(s.job.results.B.status, "failed");
  assert.equal(s.job.results.C.status, "success");
  s.source = "ok";
  M.retry(s);
  assert.deepEqual(s.job.ids, ["B"]);
  M.step(s);
  assert.equal(s.job.results.B.status, "success");
  M.start(s, "all");
  M.step(s);
  M.cancel(s);
  assert.equal(s.job.status, "cancelled");
  const r = M.restart(s);
  assert.equal(r.job.status, "cancelled");
});
test("目录变化保留章节状态、空响应不删库、元数据保护", () => {
  const s = M.create();
  const before = structuredClone(s.books[0].chapters);
  s.source = "chapter-change";
  M.start(s, "category");
  M.step(s);
  const c = s.books[0].chapters.find((c) => c.number === 1);
  assert.equal(c.url, "/new/1");
  assert.equal(c.page, 7);
  assert.equal(c.bookmark, true);
  assert.equal(c.download, "A-file-1");
  assert.equal(c.id, before[0].id);
  assert.equal(
    s.books[0].chapters.some((c) => c.number === 2),
    false,
  );
  s.source = "empty-source";
  s.job = null;
  const snapshot = structuredClone(s.books[0].chapters);
  M.start(s, "category");
  M.step(s);
  assert.deepEqual(s.books[0].chapters, snapshot);
  assert.equal(s.job.results.A.status, "failed");
});
test("设置原子失败、分类排序恢复、更新排除优先", () => {
  const s = M.create();
  s.failSave = true;
  assert.equal(M.save(s, "layout", "list"), false);
  assert.equal(s.prefs.layout, "compact");
  M.save(s, "categoryPolicy", { 0: 1, 1: -1 });
  assert.equal(
    M.candidates(s, "all").some((b) => b.id === "A"),
    false,
  );
  s.sortInterrupted = true;
  M.save(s, "perCategory", false);
  assert.equal(s.prefs.perCategory, true);
  const r = M.restart(s);
  assert.equal(r.prefs.perCategory, false);
  assert.deepEqual(r.categorySort, {});
});
test("设备等待、周期合并和手动绕过、评分规范化", () => {
  const s = M.create();
  s.prefs.interval = 48;
  s.prefs.wifi = true;
  s.device.wifi = null;
  M.tick(s, 49 * 3600000);
  assert.match(s.waiting, /Wi-Fi/);
  assert.equal(s.job, null);
  s.device.wifi = true;
  M.tick(s, 0);
  assert.equal(s.job.type, "scheduled");
  M.cancel(s);
  M.tick(s, 0);
  assert.equal(s.job.status, "cancelled");
  M.start(s, "category");
  assert.equal(s.job.status, "running");
  assert.equal(M.score(s.books[0]), 8);
  assert.equal(M.score(s.books[1]), 9);
});
test("真实模型边界：仅下载、零章节、空库和目录事务及文件恢复", () => {
  const s = M.create();
  M.scenario(s, "download-only");
  assert.equal(s.downloadOnly, true);
  M.scenario(s, "empty-library");
  assert.equal(s.books.length, 0);
  M.scenario(s, "smart-samples");
  s.prefs.started = true;
  M.start(s, "all");
  while (s.job.status === "running") M.step(s);
  assert.equal(s.job.results.A.status, "success");
  assert.equal(s.job.results.B.status, "skipped");
  M.scenario(s, "transaction-failure");
  const chapters = structuredClone(s.books[0].chapters);
  M.start(s, "all");
  M.step(s);
  assert.equal(s.job.results.A.status, "failed");
  assert.deepEqual(s.books[0].chapters, chapters);
  M.scenario(s, "file-failure");
  M.start(s, "all");
  while (s.job.status === "running") M.step(s);
  assert.equal(s.job.results.A.status, "failed");
  assert.equal(s.books[0].chapters.length, 3);
  s.source = "ok";
  M.retry(s);
  M.step(s);
  assert.equal(s.books[0].updated, 1);
  assert.equal(s.job.results.A.status, "success");
});
test("评分以外十种排序是真实数据顺序，随机再次排列", () => {
  const s = M.create();
  s.categorySort[1] = "random";
  const before = M.visible(s).map((b) => b.id);
  s.seed++;
  assert.notDeepEqual(
    M.visible(s).map((b) => b.id),
    before,
  );
  s.categorySort[1] = "chapters";
  s.books[0].chapters = [];
  assert.equal(M.visible(s)[0].id, "A");
});
test("提交后中断重启不重复目录，自动更新逐本等待后继续", () => {
  const s = M.create();
  M.scenario(s, "checkpoint-gap");
  M.start(s, "category");
  M.step(s);
  assert.equal(s.job.status, "interrupted");
  assert.equal(s.books[0].updated, 1);
  const r = M.restart(s);
  M.step(r);
  assert.equal(r.books[0].updated, 1);
  assert.equal(r.job.results.A.status, "success");
  const a = M.create();
  a.prefs.interval = 6;
  a.prefs.power = true;
  M.tick(a, 0);
  M.step(a);
  a.device.power = false;
  M.step(a);
  assert.equal(a.job.status, "waiting");
  assert.equal(Object.keys(a.job.results).length, 1);
  a.device.power = true;
  M.step(a);
  assert.equal(a.job.status, "running");
  assert.equal(Object.keys(a.job.results).length, 2);
});
