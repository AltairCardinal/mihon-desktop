const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

async function visit(fn) {
  const browser = await chromium.launch({ channel: "chrome", headless: true, ignoreDefaultArgs: ["--hide-scrollbars"] });
  try {
    const page = await browser.newPage({ viewport: { width: 1000, height: 800 } });
    page.setDefaultTimeout(3500);
    await page.goto(pathToFileURL(path.join(__dirname, "device.html")).href + "?platform=windows");
    await fn(page);
  } finally {
    await browser.close();
  }
}

test("远端追踪刷新和切服务同步编辑草稿，保存不退回旧进度", () => visit(async (page) => {
  await page.evaluate(() => window.demo.scenario("detail-tracking"));
  await page.getByTestId("manga-A").click();
  await page.getByTestId("detail-tracking").click();
  await page.getByTestId("tracking-refresh").click();
  assert.equal(await page.getByTestId("tracking-chapter-input").inputValue(), "12");
  await page.getByTestId("tracking-save").click();
  assert.equal(await page.evaluate(() => window.demo.state.books[0].tracks[0].progress), 12);
  await page.getByTestId("detail-tracking").click();
  await page.getByTestId("tracking-service").selectOption("MyAnimeList");
  await page.getByTestId("tracking-chapter-input").fill("75");
  await page.getByTestId("tracking-save").click();
  await page.getByTestId("detail-tracking").click();
  await page.getByTestId("tracking-service").selectOption("MyAnimeList");
  assert.equal(await page.getByTestId("tracking-chapter-input").inputValue(), "75");
}));

test("详情下拉刷新被反向滚动打断后必须重新两段触发", () => visit(async (page) => {
  await page.getByTestId("manga-A").click();
  const scroll = page.getByTestId("detail-chapter-scroll");
  await scroll.dispatchEvent("wheel", { deltaY: -90 });
  assert.match(await page.getByTestId("detail-pull-tip").textContent(), /再次/);
  await scroll.dispatchEvent("wheel", { deltaY: 24 });
  assert.equal(await page.getByTestId("detail-pull-tip").textContent(), "");
  await page.waitForTimeout(350);
  await scroll.dispatchEvent("wheel", { deltaY: -50 });
  assert.equal(await page.evaluate(() => window.demo.state.job), null);
}));

test("详情下拉刷新被面板打断后必须重新两段触发", () => visit(async (page) => {
  await page.getByTestId("manga-A").click();
  const scroll = page.getByTestId("detail-chapter-scroll");
  await scroll.dispatchEvent("wheel", { deltaY: -90 });
  await page.getByTestId("detail-filter-menu").click();
  await page.keyboard.press("Escape");
  assert.equal(await page.getByTestId("detail-pull-tip").textContent(), "");
  await page.waitForTimeout(350);
  await scroll.dispatchEvent("wheel", { deltaY: -50 });
  assert.equal(await page.evaluate(() => window.demo.state.job), null);
}));

test("筛选只移除隐藏的选章，保留仍可见章节并清理失效锚点", () => visit(async (page) => {
  await page.getByTestId("manga-A").click();
  await page.getByTestId("chapter-row-A-3").click({ modifiers: ["Control"] });
  await page.getByTestId("chapter-row-A-1").click({ modifiers: ["Control"] });
  await page.getByTestId("detail-filter-menu").click();
  await page.getByTestId("chapter-filter-unread").click();
  await page.keyboard.press("Escape");
  assert.match(await page.getByTestId("detail-selection-count").textContent(), /1/);
  assert.equal(await page.getByTestId("chapter-row-A-3").getAttribute("aria-pressed"), "true");
  await page.getByTestId("chapter-row-A-2").click({ modifiers: ["Shift"] });
  assert.match(await page.getByTestId("detail-selection-count").textContent(), /2/);
}));

test("重复收藏提示的去迁移入口打开已有收藏的迁移流程", () => visit(async (page) => {
  await page.evaluate(() => window.demo.scenario("detail-duplicate"));
  await page.getByTestId("detail-library").click();
  await page.getByTestId("detail-duplicate-migrate").click();
  assert.match(await page.getByRole("dialog").textContent(), /目标图源/);
  assert.equal(await page.evaluate(() => window.demo.state.bookId), "A-existing");
  assert.equal(await page.evaluate(() => window.demo.state.books.find((book) => book.id === "A").favorite), false);
}));

test("长章节与同步页码样本可以直接从场景入口执行", () => visit(async (page) => {
  await page.evaluate(() => window.demo.scenario("detail-long-chapters"));
  await page.getByTestId("manga-A").click();
  const scroll = page.getByTestId("detail-chapter-scroll");
  const rect = await scroll.boundingBox();
  await page.mouse.move(rect.x + rect.width - 8, rect.y + 30);
  await page.mouse.down();
  await page.mouse.move(rect.x + rect.width - 8, rect.y + rect.height + 30, { steps: 15 });
  await page.mouse.up();
  const position = await scroll.evaluate((node) => ({ top: node.scrollTop, max: node.scrollHeight - node.clientHeight }));
  assert.equal(position.top, position.max, `真实拖动应到末尾：${position.top}/${position.max}`);
  assert.equal(await page.getByTestId("chapter-row-A-200").evaluate((row) => {
    const pane = document.querySelector('[data-testid="detail-chapter-scroll"]').getBoundingClientRect();
    const target = row.getBoundingClientRect();
    return target.top < pane.bottom && target.bottom > pane.top;
  }), true);
  await page.evaluate(() => window.demo.scenario("detail-sync-progress"));
  await page.getByTestId("manga-A").click();
  assert.match(await page.getByTestId("chapter-row-A-3").textContent(), /同步/);
  await page.getByTestId("detail-continue").click();
  assert.match(await page.getByTestId("reader-preview").textContent(), /第 13 页/);
}));
