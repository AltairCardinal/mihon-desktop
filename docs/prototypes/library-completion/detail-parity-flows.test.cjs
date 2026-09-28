const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

async function visit(run) {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1080, height: 820 } });
    page.setDefaultTimeout(4000);
    await page.goto(pathToFileURL(path.join(__dirname, "device.html")).href + "?platform=windows");
    await run(page);
  } finally {
    await browser.close();
  }
}

test("详情缺源样本与外部章节说明，不出现普通下载", () => visit(async (page) => {
  await page.evaluate(() => window.demo.scenario("detail-missing-source"));
  await page.getByTestId("manga-A").click();
  assert.match(await page.getByTestId("detail-source-line").textContent(), /缺失图源/);
  await page.getByTestId("chapter-row-A-3").click({ button: "right" });
  assert.equal(await page.getByTestId("chapter-context-download").count(), 0);
  await page.getByTestId("chapter-row-A-3").click();
  assert.match(await page.getByTestId("reader-preview").textContent(), /外部|浏览器/);
}));

test("设置默认章节选项、只作用可见集的反选与批量前序已读", () => visit(async (page) => {
  await page.getByTestId("manga-A").click();
  await page.getByTestId("detail-filter-menu").click();
  await page.getByTestId("chapter-filter-bookmark").click();
  await page.getByTestId("chapter-settings-default-open").click();
  await page.getByTestId("chapter-default-save").click();
  await page.keyboard.press("Escape");
  await page.getByTestId("chapter-row-A-1").click({ modifiers: ["Control"] });
  await page.getByTestId("detail-select-invert").click();
  assert.equal(await page.getByTestId("detail-selection").count(), 0);
  await page.getByTestId("detail-filter-menu").click();
  await page.getByTestId("chapter-filter-bookmark").click();
  await page.keyboard.press("Escape");
  await page.getByTestId("chapter-row-A-2").click({ modifiers: ["Control"] });
  await page.getByTestId("detail-batch-previous").click();
  assert.equal(await page.evaluate(() => window.demo.state.books[0].chapters[0].read), true);
  assert.equal(await page.evaluate(() => window.demo.state.books[0].chapters[1].read), false);
}));

test("迁移的复制与迁移分离，取消不修改当前作品", () => visit(async (page) => {
  await page.getByTestId("manga-A").click();
  await page.getByTestId("detail-overflow").click();
  await page.getByTestId("detail-migrate").click();
  await page.getByTestId("detail-migrate-target").click();
  await page.getByTestId("migration-query").fill("新刊");
  await page.getByTestId("migration-search").click();
  await page.getByTestId("detail-migrate-match-0").click();
  await page.getByTestId("modal-cancel").click();
  assert.equal(await page.evaluate(() => window.demo.state.books.find((book) => book.id === "A").favorite), true);
  await page.getByTestId("detail-migrate-match-0").click();
  await page.getByTestId("migration-move").click();
  await page.waitForFunction(() => window.demo.state.books.some((book) => book.id.startsWith("A-migrated")));
  assert.equal(await page.evaluate(() => window.demo.state.books.find((book) => book.id === "A").favorite), false);
  assert.equal(await page.evaluate(() => window.demo.state.books.some((book) => book.id.startsWith("A-migrated") && book.favorite)), true);
}));

test("详情顶部两段滚动提示后刷新，失败保留章节并可重试", () => visit(async (page) => {
  await page.evaluate(() => window.demo.scenario("empty-source"));
  await page.getByTestId("manga-A").click();
  const scroll = page.getByTestId("detail-chapter-scroll");
  await scroll.dispatchEvent("wheel", { deltaY: -90 });
  assert.match(await page.getByTestId("detail-pull-tip").textContent(), /再次/);
  assert.equal(await page.evaluate(() => window.demo.state.job), null);
  await page.waitForTimeout(350);
  await scroll.dispatchEvent("wheel", { deltaY: -50 });
  await page.waitForFunction(() => window.demo.state.job?.status === "done");
  assert.equal(await page.getByTestId("chapter-row-A-1").count(), 1);
  assert.match(await page.getByTestId("detail-refresh-error").textContent(), /保留|重试/);
  await page.evaluate(() => window.demo.command("source-ok"));
  await page.getByTestId("detail-overflow").click();
  await page.getByTestId("detail-refresh").click();
  await page.waitForFunction(() => window.demo.state.job?.status === "done" && window.demo.state.books[0].lastChecked);
  await page.getByTestId("detail-fetch-interval").click();
  assert.match(await page.getByRole("dialog").textContent(), /上次检查/);
  assert.doesNotMatch(await page.getByRole("dialog").textContent(), /尚未检查/);
}));

test("批量失败章节保留选择并可重试，手动已读可取消追踪同步", () => visit(async (page) => {
  await page.evaluate(() => window.demo.scenario("detail-batch-failure"));
  await page.getByTestId("manga-A").click();
  await page.getByTestId("chapter-row-A-3").click({ modifiers: ["Control"] });
  await page.getByTestId("detail-batch-download").click();
  assert.match(await page.getByTestId("notice").textContent(), /失败 1/);
  assert.match(await page.getByTestId("detail-selection-count").textContent(), /1/);
  await page.getByTestId("detail-batch-download").click();
  assert.equal(await page.getByTestId("chapter-delete-A-3").count(), 1);
  await page.getByTestId("chapter-row-A-2").click({ button: "right" });
  await page.getByTestId("chapter-context-read").click();
  assert.match(await page.getByTestId("confirm-text").textContent(), /追踪/);
  await page.getByTestId("modal-cancel").click();
  assert.equal(await page.evaluate(() => window.demo.state.books[0].tracks[0].progress || 0), 0);
  assert.equal(await page.evaluate(() => window.demo.state.books[0].chapters[1].read), true);
}));

test("迁移中有进度；目标失败可重试且失败前不改原作品", () => visit(async (page) => {
  await page.getByTestId("manga-A").click();
  await page.getByTestId("detail-overflow").click();
  await page.getByTestId("detail-migrate").click();
  await page.getByTestId("detail-migrate-target").click();
  await page.getByTestId("migration-query").fill("失败样本");
  await page.getByTestId("migration-search").click();
  await page.getByTestId("detail-migrate-match-0").click();
  await page.getByTestId("migration-move").click();
  assert.match(await page.getByTestId("migration-progress").textContent(), /正在/);
  await page.getByTestId("migration-error").waitFor();
  assert.equal(await page.evaluate(() => window.demo.state.books[0].favorite), true);
  await page.getByTestId("migration-move").click();
  await page.waitForFunction(() => window.demo.state.books.some((book) => book.id.startsWith("A-migrated")));
  assert.equal(await page.evaluate(() => window.demo.state.books[0].favorite), false);
}));

test("远端追踪失败可恢复，未收藏作品加入书架时自动匹配", () => visit(async (page) => {
  await page.evaluate(() => window.demo.scenario("detail-tracking-error"));
  await page.getByTestId("manga-A").click();
  await page.getByTestId("detail-tracking").click();
  await page.getByTestId("tracking-refresh").click();
  assert.match(await page.getByRole("dialog").textContent(), /远端追踪刷新失败/);
  await page.evaluate(() => window.demo.command("source-ok"));
  await page.getByTestId("tracking-refresh").click();
  assert.match(await page.getByRole("dialog").textContent(), /远端追踪已刷新/);
  await page.evaluate(() => window.demo.scenario("detail-uncollected-tracking"));
  await page.getByTestId("detail-library").click();
  assert.match(await page.getByTestId("notice").textContent(), /自动匹配/);
  assert.equal(await page.evaluate(() => window.demo.state.books.find((book) => book.id === "F").tracks.length), 1);
}));

test("目录增删改后缺章提示、状态承接及新增章自动下载可见", () => visit(async (page) => {
  await page.evaluate(() => window.demo.scenario("chapter-change"));
  await page.getByTestId("settings-open").click();
  await page.getByTestId("pref-autoDownloadNew").check();
  await page.getByTestId("modal-close").click();
  await page.getByTestId("manga-A").click();
  await page.getByTestId("detail-overflow").click();
  await page.getByTestId("detail-refresh").click();
  await page.waitForFunction(() => window.demo.state.job?.status === "done");
  assert.match(await page.getByTestId("chapter-count").textContent(), /缺 1 话/);
  assert.match(await page.locator(".chapter-gap").first().textContent(), /第 2/);
  assert.equal(await page.getByTestId("chapter-delete-A-4").count(), 1);
  assert.equal(await page.evaluate(() => !!window.demo.state.books[0].chapters.find((c) => c.number === 1).download), true);
}));

test("并列预览中详情筛选和追踪只影响操作设备", async () => {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1600, height: 1300 } });
    await page.goto(pathToFileURL(path.join(__dirname, "index.html")).href);
    const windows = page.frameLocator("#preview-windows");
    const android = page.frameLocator("#preview-android");
    await windows.getByTestId("manga-A").click();
    await android.getByTestId("manga-A").click();
    await windows.getByTestId("detail-filter-menu").click();
    await windows.getByTestId("chapter-filter-unread").click();
    await windows.getByTestId("modal-close").click();
    assert.match(await windows.getByTestId("chapter-count").textContent(), /2\/3/);
    assert.match(await android.getByTestId("chapter-count").textContent(), /3\/3/);
    await windows.getByTestId("detail-tracking").click();
    await windows.getByTestId("tracking-chapter-input").fill("75");
    await windows.locator(".sheet-body").evaluate((body) => { body.scrollTop = body.scrollHeight; });
    await windows.getByTestId("tracking-save").click();
    await windows.getByTestId("detail-tracking").click();
    assert.equal(await windows.getByTestId("tracking-chapter-input").inputValue(), "75");
    assert.match(await windows.getByTestId("tracking-remote-state").textContent(), /75 \/ 100 章/);
    await android.getByTestId("detail-tracking").click();
    assert.equal(await android.getByTestId("tracking-chapter-input").inputValue(), "0");
    assert.match(await android.getByTestId("tracking-remote-state").textContent(), /0 \/ 100 章/);
    assert.equal(await android.getByTestId("detail-filter-menu").count(), 1);
  } finally {
    await browser.close();
  }
});
