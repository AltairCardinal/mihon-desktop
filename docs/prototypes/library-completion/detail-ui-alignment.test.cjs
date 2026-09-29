const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

async function visit(run) {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1040, height: 780 } });
    page.setDefaultTimeout(4000);
    await page.goto(pathToFileURL(path.join(__dirname, "device.html")).href + "?platform=windows");
    await page.getByTestId("manga-A").click();
    await run(page);
  } finally {
    await browser.close();
  }
}

test("详情顶栏按原版分层，次要动作从更多可达而不重复常驻", () => visit(async (page) => {
  const primary = page.getByTestId("detail-toolbar-primary");
  assert.equal(await primary.getByTestId("detail-download-menu").isVisible(), true);
  assert.equal(await primary.getByTestId("detail-filter-menu").isVisible(), true);
  assert.equal(await primary.getByTestId("detail-overflow").isVisible(), true);
  for (const id of ["detail-refresh", "detail-categories", "detail-migrate", "detail-share-link", "detail-notes", "detail-mark-all"]) {
    assert.equal(await primary.getByTestId(id).isVisible(), false, `${id} 不应常驻顶栏`);
  }
  await page.getByTestId("detail-overflow").click();
  for (const id of ["detail-refresh", "detail-categories", "detail-migrate", "detail-share-link", "detail-notes"]) {
    assert.equal(await page.getByTestId(id).isVisible(), true, `${id} 应在更多菜单中`);
  }
  assert.equal(await page.getByTestId("detail-mark-all").count(), 0);
  await page.getByTestId("detail-notes").click();
  assert.equal(await page.getByTestId("detail-notes-input").isVisible(), true);
}));

test("详情左栏接近原版信息层级，封面编辑和复制入口按需显现", () => visit(async (page) => {
  const info = page.getByTestId("detail-info-scroll");
  const order = await info.evaluate((node) => [...node.children].map((child) => child.dataset.testid || child.className));
  assert.ok(order.indexOf("detail-hero") < order.indexOf("detail-action-row"));
  assert.ok(order.indexOf("detail-action-row") < order.indexOf("detail-summary"));
  assert.equal(await page.getByTestId("detail-category-status").count(), 0);
  assert.equal(await page.getByTestId("detail-interval-status").count(), 0);
  assert.equal(await page.getByTestId("detail-cover-menu").count(), 0);
  await page.getByTestId("detail-cover-open").click();
  await page.getByTestId("detail-cover-menu").click();
  assert.equal(await page.getByTestId("cover-replace").isVisible(), true);
  await page.keyboard.press("Escape");
  assert.equal(await page.getByTestId("detail-cover-viewer").isVisible(), true);
  assert.equal(await page.getByTestId("cover-replace").count(), 0);
  await page.keyboard.press("Escape");
  assert.equal(await page.getByTestId("detail-cover-viewer").count(), 0);
  await page.getByTestId("detail-title-search").click({ button: "right" });
  assert.match(await page.getByTestId("notice").textContent(), /复制/);
}));

test("标签只保留一个可见入口，搜索与复制在操作菜单里", () => visit(async (page) => {
  await page.getByTestId("detail-tag-0").click();
  assert.equal(await page.getByTestId("detail-tag-search").isVisible(), true);
  assert.equal(await page.getByTestId("detail-tag-copy").isVisible(), true);
  await page.getByTestId("detail-tag-copy").click();
  assert.match(await page.getByTestId("notice").textContent(), /复制/);
  assert.equal(await page.getByTestId("detail-reading-mode").count(), 0);
}));

test("窄窗口详情更多菜单完整落在视口内", async () => {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 320, height: 700 } });
    await page.goto(pathToFileURL(path.join(__dirname, "device.html")).href + "?platform=android");
    await page.getByTestId("manga-A").click();
    const actionRow = await page.getByTestId("detail-action-row").evaluate((node) => node.getBoundingClientRect().height);
    assert.ok(actionRow <= 90, `动作应保持单行：${actionRow}px`);
    await page.getByTestId("detail-overflow").click();
    const bounds = await page.locator('.detail-bar .detail-popup').evaluate((node) => {
      const rect = node.getBoundingClientRect();
      return { left: rect.left, right: rect.right, viewport: window.innerWidth };
    });
    assert.ok(bounds.left >= 0 && bounds.right <= bounds.viewport, JSON.stringify(bounds));
    assert.equal(await page.getByTestId("detail-categories").isVisible(), true);
  } finally { await browser.close(); }
});

test("不适用作品不会显示迁移与分享菜单项", () => visit(async (page) => {
  for (const [scenario, manga] of [["detail-missing-source", "A"], ["smart-samples", "B"], ["detail-uncollected-tracking", "F"]]) {
    await page.evaluate((name) => window.demo.scenario(name), scenario);
    if (scenario !== "detail-uncollected-tracking") await page.getByTestId(`manga-${manga}`).click();
    await page.getByTestId("detail-overflow").click();
    assert.equal(await page.getByTestId("detail-migrate").count(), 0, `${scenario} 不应提供迁移`);
    if (scenario !== "detail-uncollected-tracking")
      assert.equal(await page.getByTestId("detail-share-link").count(), 0, `${scenario} 不应提供分享`);
  }
}));

test("封面查看器通过关闭按钮或遮罩退出后不保留旧菜单", () => visit(async (page) => {
  for (const exit of ["button", "overlay"]) {
    await page.getByTestId("detail-cover-open").click();
    await page.getByTestId("detail-cover-menu").click();
    assert.equal(await page.getByTestId("cover-replace").isVisible(), true);
    if (exit === "button") await page.getByTestId("modal-close").click();
    else await page.locator(".overlay").click({ position: { x: 2, y: 2 } });
    await page.getByTestId("detail-cover-open").click();
    assert.equal(await page.getByTestId("cover-replace").count(), 0, `${exit} 关闭后菜单应重置`);
    await page.getByTestId("modal-close").click();
  }
}));
