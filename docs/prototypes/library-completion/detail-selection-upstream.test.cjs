const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

async function detail(width, run) {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width, height: 800 } });
    await page.goto(pathToFileURL(path.join(__dirname, "device.html")).href + "?platform=windows&review=detail-parity");
    await run(page);
  } finally { await browser.close(); }
}

test("宽屏双栏无分割线，审阅窗中的左栏与封面维持原版视觉比例", () => detail(1000, async (page) => {
  const metrics = await page.evaluate(() => {
    const left = document.querySelector('[data-testid="detail-info-scroll"]');
    const cover = document.querySelector('[data-testid="detail-cover-open"]');
    return { left: left.getBoundingClientRect().width, cover: cover.getBoundingClientRect().width, border: getComputedStyle(left).borderRightWidth, toolbarBorder: getComputedStyle(document.querySelector(".detail-bar")).borderBottomWidth };
  });
  assert.equal(metrics.border, "0px");
  assert.equal(metrics.toolbarBorder, "0px");
  assert.ok(metrics.left <= 360, JSON.stringify(metrics));
  assert.ok(metrics.cover <= 220, JSON.stringify(metrics));
}));

test("无可获取译制组时副标题与排除筛选均不显示占位名称", () => detail(1000, async (page) => {
  const row = page.getByTestId("chapter-row-A-3");
  assert.doesNotMatch(await row.locator("small").textContent(), /示例汉化组|示例译制组|未知译制组/);
  await page.getByTestId("detail-filter-menu").click();
  assert.equal(await page.getByTestId("chapter-scanlator-open").count(), 0);
  assert.equal(await page.locator("[data-scanlator-name]").count(), 0);
}));

test("章节取得明确译制组后才显示副标题和排除筛选项", () => detail(1000, async (page) => {
  await page.evaluate(() => {
    window.demo.state.books[0].chapters[2].scanlator = "真实译制组";
    window.demo.command("noop");
  });
  assert.match(await page.getByTestId("chapter-row-A-3").locator("small").textContent(), /真实译制组/);
  await page.getByTestId("detail-filter-menu").click();
  await page.getByTestId("chapter-scanlator-open").click();
  assert.equal(await page.locator('[data-scanlator-name="真实译制组"]').count(), 1);
  assert.equal(await page.locator('[data-scanlator-name="未知"]').count(), 0);
}));

test("详情子页始终隐藏根导航，退出选择后返回书架才恢复", () => detail(1000, async (page) => {
  assert.equal(await page.getByTestId("nav-library").count(), 0);
  const floatingGap = await page.evaluate(() => document.querySelector(".app-content").getBoundingClientRect().bottom - document.querySelector('[data-testid="detail-continue"]').getBoundingClientRect().bottom);
  assert.ok(floatingGap <= 28, `继续阅读按钮应靠近详情页底边：${floatingGap}`);
  await page.getByTestId("chapter-row-A-3").click({ modifiers: ["Control"] });
  assert.equal(await page.getByTestId("nav-library").count(), 0);
  await page.getByTestId("detail-select-close").click();
  assert.equal(await page.getByTestId("nav-library").count(), 0);
  await page.getByTestId("detail-back").click();
  assert.equal(await page.getByTestId("nav-library").count(), 1);
}));

test("章节多选上方替换为关闭、纯数字、全选和反选；下方为右半宽图标操作栏", () => detail(1000, async (page) => {
  await page.getByTestId("chapter-row-A-3").click({ modifiers: ["Control"] });
  const bar = page.locator(".detail-bar");
  assert.equal((await bar.getByTestId("detail-title").textContent()).trim(), "1");
  assert.equal(await bar.getByTestId("detail-select-close").count(), 1);
  assert.equal(await bar.getByTestId("detail-select-all").count(), 1);
  assert.equal(await bar.getByTestId("detail-select-invert").count(), 1);
  assert.equal(await bar.getByTestId("detail-filter-menu").count(), 0);
  assert.equal(await bar.getByTestId("detail-download-menu").count(), 0);
  assert.equal(await page.getByTestId("chapter-count").isDisabled(), true);
  assert.equal(await page.getByTestId("chapter-row-A-3").locator('input[type="checkbox"]').count(), 0);
  const bottom = page.getByTestId("detail-selection");
  const widths = await page.evaluate(() => [document.querySelector('[data-testid="detail-selection"]').getBoundingClientRect().width, document.querySelector('[data-testid="detail-layout"]').getBoundingClientRect().width]);
  assert.ok(Math.abs(widths[0] - widths[1] / 2) < 2, JSON.stringify(widths));
  assert.ok(await bottom.getByTestId("detail-batch-bookmark").locator("svg").count());
  assert.equal(await bottom.getByTestId("detail-batch-bookmark").locator(".detail-action-label").isVisible(), false);
  assert.equal(await page.getByTestId("detail-continue").count(), 0);
  await bar.getByTestId("detail-select-close").click();
  assert.equal(await bottom.count(), 0);
  assert.notEqual((await bar.getByTestId("detail-title").textContent()).trim(), "1");
}));

test("不同章节集合只显示原版允许的底栏操作", () => detail(1000, async (page) => {
  const actions = async () => page.getByTestId("detail-selection").locator("button").evaluateAll((nodes) => nodes.map((node) => node.dataset.testid));
  await page.getByTestId("chapter-row-A-1").click({ modifiers: ["Control"] });
  assert.deepEqual(await actions(), ["detail-batch-bookmark", "detail-batch-unread", "detail-batch-previous", "detail-batch-delete"]);
  assert.equal(await page.getByTestId("detail-batch-bookmark").getAttribute("aria-label"), "移除书签");
  await page.getByTestId("chapter-row-A-3").click({ modifiers: ["Control"] });
  assert.deepEqual(await actions(), ["detail-batch-bookmark", "detail-batch-read", "detail-batch-unread", "detail-batch-download", "detail-batch-delete"]);
  assert.equal(await page.getByTestId("detail-batch-bookmark").getAttribute("aria-label"), "添加书签");
  await page.getByTestId("detail-select-close").click();
  await page.getByTestId("chapter-row-A-3").click({ modifiers: ["Control"] });
  assert.deepEqual(await actions(), ["detail-batch-bookmark", "detail-batch-read", "detail-batch-previous", "detail-batch-download"]);
}));

test("窄屏多选栏全宽、Escape 仅退出选择且滚轮不能触发刷新", () => detail(390, async (page) => {
  await page.getByTestId("chapter-row-A-3").click({ modifiers: ["Control"] });
  const sizes = await page.evaluate(() => [document.querySelector('[data-testid="detail-selection"]').getBoundingClientRect().width, document.querySelector('[data-testid="detail-layout"]').getBoundingClientRect().width]);
  assert.ok(Math.abs(sizes[0] - sizes[1]) < 2, JSON.stringify(sizes));
  await page.getByTestId("detail-scroll").hover();
  await page.mouse.wheel(0, -100);
  await page.waitForTimeout(350);
  await page.mouse.wheel(0, -60);
  assert.equal((await page.getByTestId("detail-pull-tip").textContent()).trim(), "");
  assert.equal(await page.evaluate(() => window.demo.state.job), null);
  await page.keyboard.press("Escape");
  assert.equal(await page.getByTestId("detail-selection").count(), 0);
  assert.equal(await page.getByTestId("detail-scroll").count(), 1);
}));

test("添加与移除书签图标在24像素画布内完整显示", () => detail(1200, async (page) => {
  for (const [chapter, label] of [["A-3", "添加书签"], ["A-1", "移除书签"]]) {
    await page.getByTestId(`chapter-row-${chapter}`).click({ modifiers: ["Control"] });
    const button = page.getByTestId("detail-batch-bookmark");
    assert.equal(await button.getAttribute("aria-label"), label);
    const box = await button.locator("svg path").evaluate((node) => {
      const bounds = node.getBBox();
      return { left: bounds.x, top: bounds.y, right: bounds.x + bounds.width, bottom: bounds.y + bounds.height };
    });
    assert.ok(box.left >= 1.5 && box.top >= 1.5 && box.right <= 22.5 && box.bottom <= 22.5, JSON.stringify({ label, box }));
    await page.getByTestId("detail-select-close").click();
  }
}));

test("关闭选择及反选清零后焦点回到可见的详情返回按钮", () => detail(1000, async (page) => {
  await page.getByTestId("chapter-row-A-3").click({ modifiers: ["Control"] });
  await page.getByTestId("detail-select-close").click();
  assert.equal(await page.evaluate(() => document.activeElement?.dataset.testid), "detail-back");
  await page.getByTestId("chapter-row-A-3").click({ modifiers: ["Control"] });
  await page.getByTestId("detail-select-all").click();
  await page.getByTestId("detail-select-invert").click();
  assert.equal(await page.getByTestId("detail-selection").count(), 0);
  assert.equal(await page.evaluate(() => document.activeElement?.dataset.testid), "detail-back");
}));

test("窄屏批量操作退出选择后焦点落到可见控件", () => detail(390, async (page) => {
  await page.getByTestId("chapter-row-A-3").click({ modifiers: ["Control"] });
  await page.getByTestId("detail-batch-bookmark").click();
  assert.equal(await page.getByTestId("detail-selection").count(), 0);
  assert.equal(await page.evaluate(() => document.activeElement?.dataset.testid), "detail-back");
}));

test("长按底栏图标短暂显示动作文字且不执行该动作", () => detail(1000, async (page) => {
  await page.getByTestId("chapter-row-A-3").click({ modifiers: ["Control"] });
  const button = page.getByTestId("detail-batch-bookmark");
  await button.hover();
  await page.mouse.down();
  await page.waitForTimeout(600);
  assert.equal(await button.locator(".detail-action-label").isVisible(), true);
  assert.match(await button.locator(".detail-action-label").textContent(), /添加书签/);
  await page.mouse.up();
  assert.equal(await page.evaluate(() => window.demo.state.books[0].chapters[2].bookmark), false);
  await page.waitForTimeout(1050);
  assert.equal(await button.locator(".detail-action-label").isVisible(), false);
}));

test("排队和下载中章节选择时仍显示下载图标，点击说明已跳过而不重复建任务", () => detail(1000, async (page) => {
  for (const [bookId, state] of [["C", "queued"], ["D", "downloading"]]) {
    await page.getByTestId("detail-back").click();
    await page.getByTestId(`manga-${bookId}`).click();
    const id = `${bookId}-3`;
    assert.equal(await page.getByTestId(`chapter-row-${id}`).locator(`.chapter-download-indicator[data-download-state="${state}"]`).count(), 1);
    await page.getByTestId(`chapter-row-${id}`).click({ modifiers: ["Control"] });
    const action = page.getByTestId("detail-batch-download");
    assert.equal(await action.count(), 1);
    await action.click();
    assert.match(await page.getByTestId("notice").textContent(), /跳过 1 个/);
    assert.equal(await page.evaluate((chapterId) => window.demo.state.books.flatMap((book) => book.chapters).find((chapter) => chapter.id === chapterId).download, id), null);
  }
}));
