const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

async function run(fn, width = 1100, height = 820) {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width, height } });
    page.setDefaultTimeout(2500);
    await page.goto(
      pathToFileURL(path.join(__dirname, "device.html")).href +
        "?platform=windows",
    );
    await fn(page);
  } finally {
    await browser.close();
  }
}

test("书架顶栏将同步保留在更多菜单之前，更新和随机操作进入溢出菜单", () =>
  run(async (page) => {
    assert.deepEqual(
      await page
        .locator('[data-testid="library-primary-actions"] > *')
        .evaluateAll((items) => items.map((item) =>
          item.dataset.testid || item.querySelector("[data-testid]")?.dataset.testid,
        )),
      ["sync-open", "search-open", "panel-open", "more-open"],
    );
    assert.equal(
      await page.getByTestId("sync-open").locator("svg").getAttribute("data-mihon-icon"),
      "cloudSync",
    );
    assert.equal(await page.getByTestId("settings-open").count(), 0);
    assert.equal(await page.getByTestId("refresh").count(), 0);
    assert.equal(await page.getByTestId("random-open").count(), 0);

    await page.getByTestId("more-open").click();
    assert.deepEqual(
      await page
        .locator('[data-testid="library-more-menu"] [role="menuitem"]')
        .evaluateAll((items) => items.map((item) => item.dataset.testid)),
      ["refresh-all", "refresh", "random-open"],
    );
    await page.keyboard.press("Escape");
    assert.equal(await page.getByTestId("library-more-menu").count(), 0);
    assert.equal(
      await page.getByTestId("more-open").evaluate((item) => item === document.activeElement),
      true,
    );
    await page.getByTestId("more-open").click();
    await page.getByTestId("refresh-all").click();
    assert.equal(await page.evaluate(() => window.demo.state.job?.type), "all");

    await page.reload();
    await page.getByTestId("more-open").click();
    await page.getByTestId("refresh").click();
    assert.equal(await page.evaluate(() => window.demo.state.job?.type), "category");

    await page.reload();
    await page.getByTestId("more-open").click();
    await page.getByTestId("random-open").click();
    assert.equal(await page.evaluate(() => window.demo.state.route), "detail");
  }),
);

test("筛选、排序和显示面板遵循 Mihon 三页签与即时设置结构", () =>
  run(async (page) => {
    await page.getByTestId("panel-open").click();
    assert.deepEqual(
      await page.locator('[role="tablist"] [role="tab"]').allTextContents(),
      ["筛选", "排序", "显示"],
    );
    assert.equal(await page.getByTestId("filter-reset").count(), 0);
    assert.equal(await page.getByTestId("filter-download").isDisabled(), false);
    await page.getByTestId("panel-tab-sort").click();
    assert.deepEqual(
      await page.locator('[data-testid^="sort-"]').evaluateAll((items) =>
        items.map((item) => item.dataset.testid),
      ),
      [
        "sort-title",
        "sort-chapters",
        "sort-read",
        "sort-updated",
        "sort-unread",
        "sort-latest",
        "sort-fetched",
        "sort-added",
        "sort-score",
        "sort-random",
      ],
    );
    await page.getByTestId("panel-tab-display").click();
    assert.deepEqual(
      await page.locator('[data-testid^="layout-"]').evaluateAll((items) =>
        items.map((item) => item.dataset.testid),
      ),
      ["layout-compact", "layout-comfortable", "layout-cover-only", "layout-list"],
    );
    const columns = page.getByTestId("pref-columns");
    assert.equal(await columns.getAttribute("type"), "range");
    assert.equal(await page.getByTestId("pref-portrait").count(), 0);
    await columns.fill("6");
    assert.equal(await columns.inputValue(), "6");
    assert.equal(await page.locator('[data-testid="layout-compact"]').count(), 1);
  }),
);

test("宽屏改用 Mihon 左侧导航栏，窄屏保留底部导航", () =>
  run(async (page) => {
    const wide = await page.locator(".navigation").evaluate((nav) => {
      const rect = nav.getBoundingClientRect();
      const page = document.querySelector(".page-content").getBoundingClientRect();
      return {
        direction: getComputedStyle(nav).flexDirection,
        navRight: rect.right,
        pageLeft: page.left,
      };
    });
    assert.equal(wide.direction, "column");
    assert.ok(wide.navRight <= wide.pageLeft);
    assert.deepEqual(
      await page.locator(".navigation [data-testid^='nav-']").evaluateAll((items) =>
        items.map((item) => item.dataset.testid),
      ),
      ["nav-library", "nav-updates", "nav-history", "nav-browse", "nav-more"],
    );
    assert.equal(await page.getByTestId("nav-authors").count(), 0);

    await page.setViewportSize({ width: 390, height: 820 });
    const narrow = await page.locator(".navigation").evaluate((nav) => ({
      direction: getComputedStyle(nav).flexDirection,
      top: nav.getBoundingClientRect().top,
      pageBottom: document.querySelector(".page-content").getBoundingClientRect().bottom,
    }));
    assert.equal(narrow.direction, "row");
    assert.ok(narrow.top >= narrow.pageBottom);
  }),
);

test("更多页按 Mihon 分组排列，作者作为浏览页签而非主导航", () =>
  run(async (page) => {
    await page.getByTestId("nav-more").click();
    assert.deepEqual(
      await page.locator("[data-testid='more-content'] .more-list-item").evaluateAll((items) =>
        items.map((item) => item.dataset.action),
      ),
      [
        "more-download-only",
        "more-incognito",
        "more-downloads",
        "category-open",
        "more-stats",
        "more-storage",
        "settings-open",
        "more-about",
        "more-help",
        "more-donate",
      ],
    );
    assert.equal(await page.getByTestId("more-downloaded-only").getAttribute("aria-checked"), "false");
    await page.getByTestId("more-downloaded-only").click();
    assert.equal(await page.getByTestId("more-downloaded-only").getAttribute("aria-checked"), "true");
    await page.getByTestId("category-open").click();
    assert.equal(await page.getByTestId("category-page").count(), 1);
    await page.getByTestId("category-back").click();
    assert.equal(await page.locator("[data-testid='more-content']").count(), 1);

    await page.getByTestId("nav-browse").click();
    assert.deepEqual(
      await page.locator("[data-testid='browse-tabs'] [role='tab']").allTextContents(),
      ["图源", "作者", "扩展", "迁移"],
    );
    await page.getByTestId("browse-tab-authors").click();
    assert.equal(await page.getByTestId("browse-authors-page").count(), 1);
    assert.equal(await page.getByTestId("nav-browse").getAttribute("aria-current"), "page");
    assert.equal(await page.getByTestId("nav-authors").count(), 0);
  }),
);
