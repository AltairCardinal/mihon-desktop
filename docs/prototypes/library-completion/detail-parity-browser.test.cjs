const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

async function run(fn, platform = "windows", width = 1080) {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width, height: 850 } });
    page.setDefaultTimeout(4000);
    await page.goto(pathToFileURL(path.join(__dirname, "device.html")).href + `?platform=${platform}`);
    await fn(page);
  } finally {
    await browser.close();
  }
}

test("章节统一面板三页即时生效，三态筛选和重开保留", () => run(async (p) => {
  await p.getByTestId("manga-A").click();
  await p.getByTestId("detail-filter-menu").click();
  assert.equal(await p.getByTestId("chapter-settings-tabs").count(), 1);
  await p.getByTestId("chapter-filter-unread").click();
  assert.match(await p.getByTestId("chapter-filter-unread").textContent(), /仅未读/);
  assert.match(await p.getByTestId("chapter-count").textContent(), /2\/3/);
  await p.getByTestId("chapter-settings-tab-sort").click();
  await p.getByTestId("chapter-sort-number").click();
  await p.getByTestId("chapter-settings-tab-display").click();
  await p.getByTestId("chapter-display-number").click();
  await p.keyboard.press("Escape");
  assert.equal(await p.evaluate(() => document.activeElement?.dataset.testid), "detail-filter-menu");
  await p.getByTestId("detail-filter-menu").click();
  assert.equal(await p.getByTestId("chapter-settings-tab-display").getAttribute("aria-selected"), "true");
  await p.getByTestId("chapter-settings-tab-filter").click();
  assert.match(await p.getByTestId("chapter-filter-unread").textContent(), /仅未读/);
}));

test("Ctrl 与反向 Shift 选章，反选和隐藏后锚点失效", () => run(async (p) => {
  await p.getByTestId("manga-A").click();
  await p.getByTestId("chapter-row-A-3").click({ modifiers: ["Control"] });
  await p.getByTestId("chapter-row-A-1").click({ modifiers: ["Shift"] });
  assert.match(await p.getByTestId("detail-selection-count").textContent(), /3/);
  await p.getByTestId("detail-select-invert").click();
  assert.equal(await p.getByTestId("detail-selection").count(), 0);
  await p.getByTestId("chapter-row-A-1").click({ modifiers: ["Control"] });
  await p.getByTestId("detail-filter-menu").click();
  await p.getByTestId("chapter-filter-unread").click();
  await p.keyboard.press("Escape");
  assert.equal(await p.getByTestId("detail-selection").count(), 0);
  await p.getByTestId("chapter-row-A-3").click({ modifiers: ["Shift"] });
  assert.match(await p.getByTestId("detail-selection-count").textContent(), /1/);
}));

test("混合下载集合分别操作，批量删除以确认快照处理", () => run(async (p) => {
  await p.getByTestId("manga-A").click();
  await p.getByTestId("chapter-row-A-1").click({ modifiers: ["Control"] });
  await p.getByTestId("chapter-row-A-3").click({ modifiers: ["Control"] });
  assert.equal(await p.getByTestId("detail-batch-download").count(), 1);
  assert.equal(await p.getByTestId("detail-batch-delete").count(), 1);
  await p.getByTestId("detail-batch-delete").click();
  assert.match(await p.getByTestId("confirm-text").textContent(), /1.*本机/);
  await p.getByTestId("modal-cancel").click();
  assert.equal(await p.evaluate(() => !!window.demo.state.books[0].chapters[0].download), true);
  await p.getByTestId("detail-batch-delete").click();
  await p.getByTestId("confirm-yes").click();
  assert.equal(await p.evaluate(() => !!window.demo.state.books[0].chapters[0].download), false);
}));

test("后续下载跳过已下载，书签含已读，继续阅读遵守可见章节", () => run(async (p) => {
  await p.getByTestId("manga-A").click();
  await p.getByTestId("detail-download-menu").click();
  await p.getByTestId("detail-download-bookmarked").click();
  assert.match(await p.getByTestId("notice").textContent(), /书签|下载/);
  await p.getByTestId("detail-filter-menu").click();
  await p.getByTestId("chapter-filter-download").click();
  await p.keyboard.press("Escape");
  const target = await p.getByTestId("detail-continue").getAttribute("data-chapter-id");
  assert.ok(target);
  assert.equal(await p.getByTestId(`chapter-row-${target}`).count(), 1);
}));

test("追踪预填查询、服务章节上限及手动已读询问", () => run(async (p) => {
  await p.getByTestId("manga-A").click();
  await p.getByTestId("detail-tracking").click();
  assert.match(await p.getByTestId("tracking-search").inputValue(), /星海手记/);
  await p.getByTestId("tracking-chapter-input").fill("75");
  await p.getByTestId("tracking-save").click();
  assert.match(await p.getByTestId("notice").textContent(), /75/);
}));

test("迁移搜索和复制拥有独立目标记录，取消不改当前作品", () => run(async (p) => {
  await p.getByTestId("manga-A").click();
  await p.getByTestId("detail-overflow").click();
  await p.getByTestId("detail-migrate").click();
  await p.getByTestId("detail-migrate-target").click();
  await p.getByTestId("migration-query").fill("新刊");
  await p.getByTestId("migration-search").click();
  await p.getByTestId("detail-migrate-match-0").click();
  await p.getByTestId("migration-copy").click();
  await p.waitForFunction(() => window.demo.state.books.some((b) => b.id.startsWith("A-migrated")));
  assert.equal(await p.evaluate(() => window.demo.state.books.find((b) => b.id === "A").source), "示例图源");
  assert.equal(await p.evaluate(() => window.demo.state.books.filter((b) => b.id.startsWith("A-migrated")).length), 1);
}));
