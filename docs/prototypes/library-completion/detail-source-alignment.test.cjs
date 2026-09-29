const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

async function detail(platform, width, run) {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width, height: 800 } });
    await page.goto(pathToFileURL(path.join(__dirname, "device.html")).href + `?platform=${platform}`);
    await page.getByTestId("manga-A").click();
    await run(page);
  } finally { await browser.close(); }
}

test("直达详情审核入口打开作品A且没有旧弹层", async () => {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1000, height: 800 } });
    await page.goto(pathToFileURL(path.join(__dirname, "device.html")).href + "?platform=windows&review=detail-parity");
    assert.equal(await page.getByTestId("detail-scroll").getAttribute("data-book-id"), "A");
    assert.equal(await page.getByTestId("detail-reading-mode").count(), 0);
    assert.equal(await page.getByTestId("detail-menu-overflow").count(), 0);
  } finally { await browser.close(); }
});

test("宽屏阅读模式菜单贴近触发按钮且选项可点击", async () => {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1000, height: 800 } });
    await page.goto(pathToFileURL(path.join(__dirname, "device.html")).href + "?platform=windows&review=reading-mode");
    const button = page.getByTestId("reader-reading-mode");
    const menu = page.getByTestId("detail-menu-reader-mode");
    const [trigger, popup] = await Promise.all([button.boundingBox(), menu.boundingBox()]);
    assert.ok(trigger && popup, "直达入口应展开阅读模式菜单");
    const overlap = Math.min(trigger.x + trigger.width, popup.x + popup.width) - Math.max(trigger.x, popup.x);
    assert.ok(overlap > 0, `菜单应与触发按钮水平相邻：${JSON.stringify({ trigger, popup })}`);
    assert.ok(popup.y >= trigger.y + trigger.height && popup.y - trigger.y - trigger.height <= 8,
      `菜单应紧贴按钮下方：${JSON.stringify({ trigger, popup })}`);
    await page.getByTestId("detail-reading-rtl").click();
    assert.match(await button.textContent(), /从右到左/);
  } finally { await browser.close(); }
});

test("离开阅读预览后重进不会自动展开旧模式菜单", () => detail("windows", 1000, async (page) => {
  await page.getByTestId("chapter-row-A-3").click();
  await page.getByTestId("reader-reading-mode").click();
  assert.equal(await page.getByTestId("detail-menu-reader-mode").count(), 1);
  await page.getByTestId("reader-back").click();
  await page.getByTestId("chapter-row-A-3").click();
  assert.equal(await page.getByTestId("reader-reading-mode").count(), 1);
  assert.equal(await page.getByTestId("detail-menu-reader-mode").count(), 0);
  await page.getByTestId("reader-reading-mode").click();
  await page.getByTestId("reader-finish").click();
  if (await page.getByTestId("modal-cancel").count()) await page.getByTestId("modal-cancel").click();
  await page.getByTestId("chapter-row-A-3").click();
  assert.equal(await page.getByTestId("detail-menu-reader-mode").count(), 0);
}));

test("零章节场景保留章节头并显示共0章", async () => {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1000, height: 800 } });
    await page.goto(pathToFileURL(path.join(__dirname, "device.html")).href + "?platform=windows");
    await page.evaluate(() => window.demo.scenario("smart-samples"));
    await page.getByTestId("manga-A").click();
    assert.equal((await page.getByTestId("chapter-count").textContent()).trim(), "共 0 章");
    assert.equal(await page.getByTestId("detail-missing-chapters").count(), 0);
  } finally { await browser.close(); }
});

test("宽屏审阅窗口左栏按450dp的0.8视觉比例适配且封面占内容宽65%", () => detail("windows", 1200, async (page) => {
  const boxes = await page.evaluate(() => {
    const rect = (testid) => document.querySelector(`[data-testid="${testid}"]`).getBoundingClientRect();
    const layout = rect("detail-layout");
    const left = rect("detail-info-scroll");
    const cover = rect("detail-cover-open");
    const title = rect("detail-title-search");
    return { layout: layout.width, left: left.width, cover: cover.width, coverLeft: cover.left, leftEdge: left.left, titleTop: title.top, coverBottom: cover.bottom };
  });
  assert.ok(Math.abs(boxes.left - Math.min(boxes.layout / 2, 360)) < 2, JSON.stringify(boxes));
  assert.ok(Math.abs(boxes.cover - (boxes.left - 32) * .65) < 3, JSON.stringify(boxes));
  assert.ok(Math.abs(boxes.coverLeft + boxes.cover / 2 - boxes.leftEdge - boxes.left / 2) < 3, JSON.stringify(boxes));
  assert.ok(boxes.titleTop >= boxes.coverBottom + 14, JSON.stringify(boxes));
  const coverRatio = await page.getByTestId("detail-cover-open").locator(".cover").evaluate((node) => {
    const box = node.getBoundingClientRect();
    return box.width / box.height;
  });
  assert.ok(Math.abs(coverRatio - .7) < .02, `Desktop 封面应为 7:10：${coverRatio}`);
  assert.equal(await page.getByTestId("detail-reading-mode").count(), 0);
}));

test("窄屏封面不超过100且标题位于右侧，阅读预览仍可进入", () => detail("android", 390, async (page) => {
  const boxes = await page.evaluate(() => {
    const cover = document.querySelector('[data-testid="detail-cover-open"]').getBoundingClientRect();
    const title = document.querySelector('[data-testid="detail-title-search"]').getBoundingClientRect();
    return { coverWidth: cover.width, coverRight: cover.right, titleLeft: title.left, titleTop: title.top, coverBottom: cover.bottom };
  });
  assert.ok(boxes.coverWidth <= 101 && boxes.coverWidth >= 90, JSON.stringify(boxes));
  assert.ok(boxes.titleLeft > boxes.coverRight && boxes.titleTop < boxes.coverBottom, JSON.stringify(boxes));
  const coverRatio = await page.getByTestId("detail-cover-open").locator(".cover").evaluate((node) => {
    const box = node.getBoundingClientRect();
    return box.width / box.height;
  });
  assert.ok(Math.abs(coverRatio - 2 / 3) < .02, `Android 封面应为 2:3：${coverRatio}`);
  assert.equal(await page.getByTestId("detail-reading-mode").count(), 0);
  await page.getByTestId("chapter-row-A-3").click();
  assert.equal(await page.getByTestId("reader-back").count(), 1);
}));

test("章节头只显示当前处理数量，缺章警示另起一行；行内状态按原版布局", () => detail("windows", 1000, async (page) => {
  assert.equal((await page.getByTestId("chapter-count").textContent()).trim(), "共 3 章");
  const readRow = page.getByTestId("chapter-row-A-1");
  assert.equal(await readRow.locator(".chapter-unread-dot").count(), 0);
  assert.equal(await readRow.locator(".chapter-title.read").count(), 1);
  assert.equal(await readRow.locator(".chapter-main small.read").count(), 1);
  await page.getByTestId("detail-filter-menu").click();
  await page.getByTestId("chapter-filter-unread").click();
  await page.keyboard.press("Escape");
  assert.equal((await page.getByTestId("chapter-count").textContent()).trim(), "共 2 章");
  assert.match(await page.getByTestId("detail-missing-chapters").textContent(), /缺少 1 章/);
  const row = page.getByTestId("chapter-row-A-3");
  assert.equal(await row.locator(".chapter-unread-dot").count(), 1);
  assert.equal(await row.locator(".chapter-trailing button").count(), 1);
  assert.equal(await row.locator('[data-testid^="chapter-read-"]').count(), 0);
  assert.equal(await row.locator('[data-testid^="chapter-bookmark-"]').count(), 0);
  await row.click({ button: "right" });
  await page.getByTestId("chapter-context-bookmark").click();
  assert.equal(await row.locator(".chapter-bookmark-mark").count(), 1);
}));

test("下载尾部单一指示区分未下载、完成、进行中与失败", () => detail("windows", 1000, async (page) => {
  for (const [bookId, chapterId, state] of [["A", "A-3", "none"], ["A", "A-1", "downloaded"], ["C", "C-3", "queued"], ["D", "D-3", "downloading"], ["B", "B-3", "error"]]) {
    if (bookId !== "A") {
      await page.getByTestId("detail-back").click();
      await page.getByTestId(`manga-${bookId}`).click();
    }
    const trailing = page.getByTestId(`chapter-row-${chapterId}`).locator(".chapter-trailing");
    assert.equal(await trailing.locator("button").count(), 1);
    assert.equal(await trailing.locator(`.chapter-download-indicator[data-download-state="${state}"]`).count(), 1, `${bookId} ${state}`);
    if (state === "none") {
      const icon = trailing.locator('svg[data-mihon-icon="Drawable.ic_download_chapter_24dp"]');
      assert.equal(await icon.count(), 1, "未下载应复用 Android 下载圆圈 drawable");
      assert.equal(await icon.locator("path").count(), 2, "圆圈与箭头分别复用 drawable 原始两条路径");
      assert.match(await icon.locator("path").first().getAttribute("d"), /M11\.99,2/);
      assert.match(await icon.locator("path").last().getAttribute("d"), /M18\.041,12/);
    }
    if (state === "downloaded") assert.equal(await trailing.locator("svg.filled").count(), 1, "完成应为实心 CheckCircle");
    if (state === "queued" || state === "downloading") {
      const border = await trailing.locator(".chapter-progress").evaluate((node) => getComputedStyle(node).borderTopWidth);
      assert.equal(border, "0px", "进度圆环只绘制一次");
    }
  }
}));

test("本地作品和选章模式保留禁用的尾部下载标记", () => detail("windows", 1000, async (page) => {
  await page.getByTestId("chapter-row-A-3").click({ modifiers: ["Control"] });
  const selectedIndicator = page.getByTestId("chapter-row-A-3").locator(".chapter-download-indicator");
  assert.equal(await selectedIndicator.count(), 1);
  assert.equal(await selectedIndicator.locator("button").isDisabled(), true);
  await page.evaluate(() => window.demo.scenario("smart-samples"));
  await page.getByTestId("manga-B").click();
  const localIndicator = page.getByTestId("chapter-row-B-3").locator(".chapter-download-indicator");
  assert.equal(await localIndicator.count(), 1);
  assert.equal(await localIndicator.locator("button").isDisabled(), true);
}));

test("更多菜单无全部标已读；选择全章后批量标已读仍可用", () => detail("windows", 1000, async (page) => {
  await page.getByTestId("detail-overflow").click();
  assert.equal(await page.getByTestId("detail-mark-all").count(), 0);
  await page.keyboard.press("Escape");
  await page.getByTestId("chapter-row-A-3").click({ modifiers: ["Control"] });
  await page.getByTestId("detail-select-all").click();
  assert.match(await page.getByTestId("detail-selection-count").textContent(), /3/);
  await page.getByTestId("detail-batch-read").click();
  assert.equal(await page.evaluate(() => window.demo.state.books.find((book) => book.id === "A").chapters.every((chapter) => chapter.read)), true);
}));
