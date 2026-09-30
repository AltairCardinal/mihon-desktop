const { openCoverMenu, openMoreSettings, openLibraryMenuAction } = require("./detail-test-helpers.cjs");
const test = require("node:test");
const assert = require("node:assert/strict");
const { pathToFileURL } = require("node:url");
const path = require("node:path");
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

test("演示设备条件在载入和切换目标时回读，保留控件焦点且不跨端写入", () =>
  run(
    async (p) => {
      await p.getByTestId("scenario").selectOption("device-wait");
      await p.getByTestId("apply-scenario").click();
      await p.waitForTimeout(150);
      assert.equal(
        await p.locator('[data-device="wifi"]').inputValue(),
        "null",
      );
      assert.equal(
        await p.locator('[data-device="power"]').inputValue(),
        "false",
      );
      await p.locator("#target").selectOption("android");
      await p.waitForTimeout(150);
      assert.equal(
        await p.locator('[data-device="wifi"]').inputValue(),
        "true",
      );
      await p.locator('[data-device="wifi"]').selectOption("false");
      await p.locator("#target").selectOption("windows");
      await p.locator("#target").focus();
      await p.waitForTimeout(150);
      assert.equal(
        await p.locator('[data-device="wifi"]').inputValue(),
        "null",
      );
      assert.equal(
        await p
          .locator("#target")
          .evaluate((e) => e === document.activeElement),
        true,
      );
      await p.locator("#target").selectOption("android");
      await p.waitForTimeout(150);
      assert.equal(
        await p.locator('[data-device="wifi"]').inputValue(),
        "false",
      );
    },
    "index.html",
    1440,
  ));

test("审查修复：真实排序控件使用相同方向状态，切分类与清理后不串值", () =>
  run(async (p) => {
    await p.evaluate(() => window.demo.scenario("multi-tracker"));
    await p.getByTestId("panel-open").click();
    await p.getByTestId("panel-tab-sort").click();
    await p.getByTestId("sort-score").click();
    assert.match(await p.getByTestId("sort-score").textContent(), /升序/);
    const scores = () => p.locator(".book .score").allTextContents();
    assert.deepEqual(
      (await scores()).filter((x) => /\d/.test(x)).map(parseFloat),
      [8, 8, 9],
    );
    await p.getByTestId("sort-score").click();
    assert.match(await p.getByTestId("sort-score").textContent(), /降序/);
    assert.deepEqual(
      (await scores()).filter((x) => /\d/.test(x)).map(parseFloat),
      [9, 8, 8],
    );
    await p.keyboard.press("Escape");
    await p.getByTestId("category-2").click();
    await p.getByTestId("panel-open").click();
    await p.getByTestId("panel-tab-sort").click();
    assert.match(await p.getByTestId("sort-title").textContent(), /升序/);
    await p.keyboard.press("Escape");
    await openMoreSettings(p);
    await p.getByTestId("pref-perCategory").uncheck();
    await p.getByTestId("pref-perCategory").check();
    await p.getByTestId("modal-close").click();
    await p.getByTestId("nav-library").click();
    await p.getByTestId("category-1").click();
    await p.getByTestId("panel-open").click();
    await p.getByTestId("panel-tab-sort").click();
    assert.match(await p.getByTestId("sort-title").textContent(), /升序/);
  }));

test("审查修复：替换自定义封面产生独立版本且源更新不覆盖它", () =>
  run(async (p) => {
    await p.getByTestId("manga-A").click();
    await openCoverMenu(p);
    await p.getByTestId("cover-replace").click();
    await p.getByTestId("modal-close").click();
    const first = await p.locator(".hero .cover").screenshot();
    await openCoverMenu(p);
    await p.getByTestId("cover-replace").click();
    await p.getByTestId("modal-close").click();
    const second = await p.locator(".hero .cover").screenshot();
    assert.notDeepEqual(first, second);
    await p.evaluate(() => window.demo.command("source-cover"));
    assert.deepEqual(await p.locator(".hero .cover").screenshot(), second);
    await openCoverMenu(p);
    await p.getByTestId("cover-delete").click();
    await p.getByTestId("modal-close").click();
    assert.match(await p.locator(".hero .cover").textContent(), /源封面 · 2/);
  }));

test("审查修复：任务结束后反向滚动持续阻止重新武装，静默后恢复", () =>
  run(async (p) => {
    const sc = p.getByTestId("library-scroll");
    await sc.dispatchEvent("wheel", { deltaY: -90 });
    await p.waitForTimeout(430);
    await sc.dispatchEvent("wheel", { deltaY: -60 });
    await p.waitForFunction(() => window.demo.state.job?.status === "done");
    for (let i = 0; i < 5; i++) {
      await p.waitForTimeout(240);
      await sc.dispatchEvent("wheel", { deltaY: 1 });
    }
    await sc.dispatchEvent("wheel", { deltaY: -90 });
    assert.equal(await p.getByTestId("wheel-hint").textContent(), "");
    await p.waitForTimeout(830);
    await sc.dispatchEvent("wheel", { deltaY: -90 });
    assert.match(await p.getByTestId("wheel-hint").textContent(), /再次/);
  }));

test("元数据刷新完成立即更新书架封面，保留自定义封面", () =>
  run(async (p) => {
    await openMoreSettings(p);
    await p.getByTestId("pref-metadata").check();
    await p.getByTestId("modal-close").click();
    await p.getByTestId("nav-library").click();
    await openLibraryMenuAction(p, "refresh");
    await p.waitForTimeout(1700);
    assert.match(await p.getByTestId("manga-A").textContent(), /源封面 · 2/);
  }));

test("滚轮武装有绝对有效期，持续惯性不能延长有效期", () =>
  run(async (p) => {
    const sc = p.getByTestId("library-scroll");
    await sc.dispatchEvent("wheel", { deltaY: -80 });
    for (let i = 0; i < 10; i++) {
      await p.waitForTimeout(340);
      await sc.dispatchEvent("wheel", { deltaY: -1 });
    }
    await p.waitForTimeout(420);
    await sc.dispatchEvent("wheel", { deltaY: -48 });
    assert.equal(await p.getByTestId("update-details").count(), 0);
    assert.match(await p.getByTestId("wheel-hint").textContent(), /准备/);
  }));

test("键盘不抢搜索和弹层；滚轮修饰键及空库不刷新", () =>
  run(async (p) => {
    const sc = p.getByTestId("library-scroll");
    await sc.focus();
    await p.keyboard.press("Control+ArrowRight");
    assert.equal(
      await p.getByTestId("category-1").getAttribute("class"),
      "active",
    );
    await p.getByTestId("search-open").click();
    await p.keyboard.press("Control+ArrowLeft");
    assert.equal(
      await p.getByTestId("category-1").getAttribute("class"),
      "active",
    );
    await p.keyboard.press("Escape");
    await p.getByTestId("category-1").click();
    for (const modifier of ["altKey", "shiftKey"]) {
      await sc.dispatchEvent("wheel", { deltaY: -100, [modifier]: true });
      assert.equal(await p.getByTestId("wheel-hint").textContent(), "");
    }
    await p.getByTestId("panel-open").click();
    await p.keyboard.press("Control+ArrowRight");
    assert.equal(
      await p.getByTestId("category-1").getAttribute("class"),
      "active",
    );
    await p.keyboard.press("Escape");
    await p.evaluate(() => window.demo.scenario("empty-library"));
    await sc.dispatchEvent("wheel", { deltaY: -100 });
    assert.equal(await p.getByTestId("wheel-hint").textContent(), "");
  }));
async function run(fn, file = "device.html?platform=windows", width = 1000) {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width, height: 800 } });
    page.setDefaultTimeout(2500);
    const [name, query] = file.split("?");
    await page.goto(
      pathToFileURL(path.join(__dirname, name)).href +
        (query ? "?" + query : ""),
    );
    await fn(page);
  } finally {
    await browser.close();
  }
}

test("详情结构：顶栏、封面菜单、元信息和动作行；阅读模式在预览内", () =>
  run(async (p) => {
    await p.getByTestId("manga-A").click();
    assert.match(await p.getByTestId("detail-title").textContent(), /星海手记/);
    await p.getByTestId("detail-overflow").click();
    for (const id of ["detail-download-menu", "detail-open-link", "detail-share-link", "detail-filter-menu", "detail-refresh", "detail-migrate", "detail-notes", "detail-library", "detail-categories", "detail-fetch-interval", "detail-tracking"]) {
      assert.equal(await p.getByTestId(id).count(), 1, `${id} 缺失`);
    }
    assert.equal(await p.getByTestId("detail-mark-all").count(), 0);
    assert.equal(await p.getByTestId("detail-reading-mode").count(), 0);
    await p.keyboard.press("Escape");
    await p.getByTestId("detail-open-link").click({ button: "right" });
    assert.match(await p.getByTestId("notice").textContent(), /复制/);
    assert.match(await p.getByTestId("detail-creators").textContent(), /作者/);
    assert.match(await p.getByTestId("detail-tags").textContent(), /冒险/);
    await openCoverMenu(p);
    assert.equal(await p.getByTestId("cover-replace").isVisible(), true);
    await p.getByTestId("cover-replace").click();
    await p.getByTestId("modal-close").click();
    assert.match(await p.locator(".hero .cover").textContent(), /自定义封面/);
    await p.getByTestId("chapter-row-A-3").click();
    await p.getByTestId("reader-reading-mode").click();
    await p.getByTestId("detail-reading-rtl").click();
    assert.match(await p.getByTestId("reader-reading-mode").textContent(), /从右到左/);
  }));

for (const [label, platform, width] of [["windows", "windows", 1000], ["android", "android", 390], ["android 320px", "android", 320]]) {
  test(`${label} 阅读预览模式菜单完整可见且末项可点击`, () =>
    run(async (p) => {
      await p.getByTestId("manga-A").click();
      await p.getByTestId("chapter-row-A-3").click();
      await p.getByTestId("reader-reading-mode").click();
      const menu = p.getByTestId("detail-menu-reader-mode");
      assert.equal(await menu.isVisible(), true);
      const bounds = await menu.boundingBox();
      assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= width,
        "阅读模式菜单必须完整落在窗口的左右边界内");
      const allItemsReachable = await menu.locator('[role="menuitemradio"]').evaluateAll((items) =>
        items.every((item) => {
          const rect = item.getBoundingClientRect();
          const x = rect.left + rect.width / 2;
          const y = rect.top + rect.height / 2;
          const hit = document.elementFromPoint(x, y);
          return rect.top >= 0 && rect.bottom <= innerHeight && (hit === item || item.contains(hit));
        }));
      assert.equal(allItemsReachable, true, "全部阅读模式选项都必须可见、可点");
      const visible = await p.getByTestId("detail-reading-webtoon").evaluate((item) => {
        const rect = item.getBoundingClientRect();
        const x = rect.left + rect.width / 2;
        const y = rect.top + rect.height / 2;
        const hit = document.elementFromPoint(x, y);
        const nav = document.querySelector(".navigation")?.getBoundingClientRect();
        return rect.top >= 0 && rect.bottom <= innerHeight && (!nav || rect.bottom <= nav.top) &&
          (hit === item || item.contains(hit));
      });
      assert.equal(visible, true, "最后一个阅读模式选项不能被滚动容器或底部导航遮挡");
      await p.getByTestId("detail-reading-webtoon").click();
      assert.match(await p.getByTestId("reader-reading-mode").textContent(), /条漫/);
    }, `device.html?platform=${platform}`, width));
}

test("阅读模式审核链接直达阅读预览菜单且加载新样式", () =>
  run(async (p) => {
    assert.equal(await p.getByTestId("reader-preview").isVisible(), true);
    assert.equal(await p.getByTestId("detail-reading-mode").count(), 0);
    assert.equal(await p.getByTestId("detail-menu-reader-mode").isVisible(), true);
    const resources = await p.evaluate(() => ({
      stylesheet: document.querySelector('link[rel="stylesheet"]')?.href,
      app: document.querySelector('script[src^="app.js"]')?.src,
    }));
    assert.match(resources.stylesheet, /styles\.css\?v=/);
    assert.match(resources.app, /app\.js\?v=/);
    await p.getByTestId("detail-reading-webtoon").click();
    assert.match(await p.getByTestId("reader-reading-mode").textContent(), /条漫/);
  }, "device.html?platform=windows&review=reading-mode&v=menu-fix", 1000));

test("并列预览使用新版设备资源地址", () =>
  run(async (p) => {
    for (const platform of ["windows", "android"]) {
      assert.match(await p.locator(`#preview-${platform}`).getAttribute("src"), /[?&]v=upstream-themes-20260930a/);
      const device = p.frameLocator(`#preview-${platform}`);
      assert.match(await device.locator('link[rel="stylesheet"]').getAttribute("href"), /styles\.css\?v=upstream-themes-20260930a/);
      for (const script of ["model.js", "detail-parity-model.js", "detail-parity-view.js", "detail-icons.js", "app.js"]) {
        assert.match(await device.locator(`script[src^="${script}"]`).getAttribute("src"), new RegExp(`${script.replaceAll(".", "\\.")}\\?v=upstream-themes-20260930a`));
      }
    }
  }, "index.html?v=upstream-themes-20260930a", 1440));

test("目标详情章节：三态筛选排序、阅读进度与独立下载书签动作", () =>
  run(async (p) => {
    await p.getByTestId("manga-A").click();
    assert.match(await p.getByTestId("chapter-count").textContent(), /3/);
    assert.equal(await p.getByTestId("chapter-row-A-1").count(), 1);
    await p.getByTestId("chapter-row-A-1").click({ button: "right" });
    await p.getByTestId("chapter-context-bookmark").click();
    assert.equal(await p.getByTestId("chapter-row-A-1").count(), 1);
    await p.getByTestId("detail-filter-menu").click();
    await p.getByTestId("chapter-filter-unread").click();
    assert.equal(await p.getByTestId("chapter-row-A-1").count(), 0);
    assert.match(await p.getByTestId("chapter-count").textContent(), /共 2 章/);
    await p.getByTestId("chapter-settings-tab-sort").click();
    await p.getByTestId("chapter-sort-number").click();
    const order = await p.locator('[data-testid^="chapter-row-"]').evaluateAll((rows) => rows.map((r) => r.dataset.chapterId));
    assert.deepEqual(order, ["A-3", "A-2"]);
    await p.getByTestId("chapter-settings-tab-display").click();
    await p.getByTestId("chapter-display-number").click();
    assert.match(await p.getByTestId("chapter-row-A-3").textContent(), /第 3 话/);
    await p.keyboard.press("Escape");
    await p.getByTestId("chapter-download-A-3").click();
    assert.equal(await p.getByTestId("chapter-delete-A-3").count(), 1);
  }));

test("目标详情章节选择：长按、普通点击、批量标记、追踪询问与逐层返回", () =>
  run(async (p) => {
    await p.getByTestId("manga-A").click();
    await p.getByTestId("chapter-row-A-2").dispatchEvent("pointerdown", { button: 0, pointerId: 1, clientX: 60, clientY: 500 });
    await p.waitForTimeout(550);
    await p.getByTestId("chapter-row-A-2").dispatchEvent("pointerup", { button: 0, pointerId: 1 });
    assert.match(await p.getByTestId("detail-selection-count").textContent(), /1/);
    assert.equal(await p.getByTestId("detail-continue").count(), 0);
    await p.getByTestId("chapter-row-A-3").click();
    assert.match(await p.getByTestId("detail-selection-count").textContent(), /2/);
    await p.getByTestId("detail-batch-read").click();
    if (await p.getByTestId("modal-cancel").count()) await p.getByTestId("modal-cancel").click();
    assert.equal(await p.getByTestId("detail-selection-count").count(), 0);
    assert.match(await p.getByTestId("chapter-row-A-2").getAttribute("data-read"), /true/);
    await p.getByTestId("chapter-row-A-1").click();
    assert.equal(await p.getByTestId("reader-back").count(), 1);
    await p.keyboard.press("Escape");
    assert.equal(await p.getByTestId("detail-back").count(), 1);
    await p.keyboard.press("Escape");
    assert.equal(await p.getByTestId("manga-A").count(), 1);
  }));

test("现有详情辅助入口：下载菜单、分类、更新间隔、追踪、笔记和确认", () =>
  run(async (p) => {
    await p.getByTestId("manga-A").click();
    await p.getByTestId("detail-download-menu").click();
    await p.getByTestId("detail-download-next-1").click();
    assert.match(await p.getByTestId("notice").textContent(), /下载/);
    await p.getByTestId("detail-overflow").click();
    await p.getByTestId("detail-categories").click();
    await p.getByTestId("detail-category-2").check();
    await p.getByTestId("detail-category-save").click();
    assert.match(await p.getByTestId("notice").textContent(), /分类/);
    await p.getByTestId("detail-fetch-interval").click();
    await p.getByTestId("detail-interval-7").check();
    await p.getByTestId("detail-interval-save").click();
    await p.getByTestId("detail-tracking").click();
    assert.match(await p.getByRole("dialog").textContent(), /AniList/);
    await p.getByTestId("modal-close").click();
    await p.getByTestId("detail-overflow").click();
    await p.getByTestId("detail-notes").click();
    await p.getByTestId("detail-notes-input").fill("审阅记录");
    await p.getByTestId("detail-notes-save").click();
    await p.getByTestId("chapter-row-A-3").click({ modifiers: ["Control"] });
    await p.getByTestId("detail-select-all").click();
    assert.match(await p.getByTestId("detail-selection-count").textContent(), /3/);
    await p.getByTestId("detail-batch-read").click();
    if (await p.getByTestId("modal-cancel").count()) await p.getByTestId("modal-cancel").click();
    assert.equal(await p.getByTestId("detail-continue").count(), 0);
  }));

test("目标详情标记之前章节已读，不包含当前章节", () =>
  run(async (p) => {
    await p.getByTestId("manga-A").click();
    await p.getByTestId("chapter-row-A-3").dispatchEvent("pointerdown", { button: 0, pointerId: 1, clientX: 60, clientY: 500 });
    await p.waitForTimeout(550);
    await p.getByTestId("chapter-row-A-3").dispatchEvent("pointerup", { button: 0, pointerId: 1 });
    await p.getByTestId("detail-batch-previous").click();
    if (await p.getByTestId("modal-cancel").count()) await p.getByTestId("modal-cancel").click();
    assert.equal(await p.getByTestId("chapter-row-A-3").getAttribute("data-read"), "false");
    assert.equal(await p.getByTestId("chapter-row-A-2").getAttribute("data-read"), "true");
  }));

test("现有详情章节下载状态：失败可重试，排队与下载中可取消", () =>
  run(async (p) => {
    await p.getByTestId("manga-B").click();
    await p.getByTestId("chapter-retry-B-3").click();
    assert.equal(await p.getByTestId("chapter-delete-B-3").count(), 1);
    await p.getByTestId("detail-back").click();
    await p.getByTestId("manga-C").click();
    assert.equal(await p.getByTestId("chapter-cancel-C-3").count(), 0);
    await p.getByTestId("chapter-progress-C-3").click();
    await p.getByTestId("chapter-cancel-C-3").click();
    assert.equal(await p.getByTestId("chapter-download-C-3").count(), 1);
    await p.getByTestId("detail-back").click();
    await p.getByTestId("manga-D").click();
    await p.getByTestId("chapter-progress-D-3").click();
    await p.getByTestId("chapter-cancel-D-3").click();
    assert.equal(await p.getByTestId("chapter-download-D-3").count(), 1);
  }));

test("详情叠加 L08：更新显示源重排、改名换链与已读书签进度下载保留", () =>
  run(async (p) => {
    await p.evaluate(() => window.demo.scenario("chapter-change"));
    await p.getByTestId("manga-A").click();
    const ids = () => p.locator('[data-testid^="chapter-row-"]').evaluateAll((rows) => rows.map((row) => row.dataset.chapterId));
    assert.deepEqual(await ids(), ["A-3", "A-2", "A-1"]);
    await p.getByTestId("detail-overflow").click();
    await p.getByTestId("detail-refresh").click();
    await p.waitForFunction(() => window.demo.state.job?.status === "done");
    assert.deepEqual(await ids(), ["A-3", "A-4", "A-1"]);
    assert.match(await p.getByTestId("chapter-row-A-3").textContent(), /更名与重排/);
    assert.match(await p.getByTestId("chapter-row-A-3").textContent(), /新版译制组/);
    assert.match(await p.getByTestId("chapter-row-A-3").textContent(), /第 5 页/);
    assert.equal(await p.getByTestId("chapter-row-A-2").count(), 0);
    assert.equal(await p.getByTestId("chapter-row-A-1").locator(".chapter-bookmark-mark").count(), 1);
    assert.equal(await p.getByTestId("chapter-delete-A-1").count(), 1);
    await p.getByTestId("update-details").click();
    assert.match(await p.getByTestId("update-results").textContent(), /新增 1.*改名 2.*移除 1/);
    await p.getByTestId("modal-close").click();
    await p.getByTestId("detail-overflow").click();
    await p.getByTestId("detail-refresh").click();
    await p.waitForFunction(() => window.demo.state.job?.status === "done");
    assert.deepEqual(await ids(), ["A-3", "A-4", "A-1"]);
    await p.getByTestId("update-details").click();
    assert.match(await p.getByTestId("update-results").textContent(), /无变化：新增 0/);
  }));

test("详情叠加 L02：四种书架布局共用自定义封面版本，删除后回到最新源封面", () =>
  run(async (p) => {
    await p.getByTestId("manga-A").click();
    await openCoverMenu(p);
    await p.getByTestId("cover-replace").click();
    await p.getByTestId("modal-close").click();
    const detailColor = await p.locator(".hero .cover").evaluate((e) => e.style.getPropertyValue("--cover-color"));
    await p.getByTestId("detail-back").click();
    await p.getByTestId("panel-open").click();
    await p.getByTestId("panel-tab-display").click();
    for (const layout of ["compact", "comfortable", "cover-only", "list"]) {
      await p.getByTestId("layout-" + layout).click();
      const cover = p.getByTestId("manga-A").locator(".cover");
      assert.match(await cover.textContent(), /自定义封面/);
      assert.equal(await cover.evaluate((e) => e.style.getPropertyValue("--cover-color")), detailColor);
    }
    await p.keyboard.press("Escape");
    await p.evaluate(() => window.demo.command("source-cover"));
    await p.getByTestId("manga-A").click();
    assert.match(await p.locator(".hero .cover").textContent(), /自定义封面/);
    await openCoverMenu(p);
    await p.getByTestId("cover-delete").click();
    await p.getByTestId("modal-close").click();
    assert.match(await p.locator(".hero .cover").textContent(), /源封面 · 2/);
    await p.getByTestId("detail-back").click();
    assert.match(await p.getByTestId("manga-A").textContent(), /源封面 · 2/);
  }));

test("详情叠加 S06/L02：默认不改作品信息，开启后刷新简介源封面且自定义封面优先", () =>
  run(async (p) => {
    await p.getByTestId("manga-A").click();
    const title = await p.getByTestId("detail-title").textContent();
    const oldDescription = await p.locator(".detail-description").textContent();
    await openCoverMenu(p);
    await p.getByTestId("cover-replace").click();
    await p.getByTestId("modal-close").click();
    await p.getByTestId("detail-overflow").click();
    await p.getByTestId("detail-refresh").click();
    await p.waitForFunction(() => window.demo.state.job?.status === "done");
    assert.equal(await p.locator(".detail-description").textContent(), oldDescription);
    await p.getByTestId("detail-back").click();
    await openMoreSettings(p);
    await p.getByTestId("pref-metadata").check();
    await p.getByTestId("modal-close").click();
    await p.getByTestId("nav-library").click();
    await p.getByTestId("manga-A").click();
    await p.getByTestId("detail-overflow").click();
    await p.getByTestId("detail-refresh").click();
    await p.waitForFunction(() => window.demo.state.job?.status === "done");
    assert.equal(await p.getByTestId("detail-title").textContent(), title);
    assert.match(await p.locator(".detail-description").textContent(), /已从图源刷新简介/);
    assert.match(await p.locator(".hero .cover").textContent(), /自定义封面/);
    await openCoverMenu(p);
    await p.getByTestId("cover-delete").click();
    await p.getByTestId("modal-close").click();
    assert.match(await p.locator(".hero .cover").textContent(), /源封面 · 2/);
  }));
test("真实点击：Ctrl 与连续 Shift 收缩，多选普通单击增减，零选择后打开", () =>
  run(async (p) => {
    await p.getByTestId("manga-B").click({ modifiers: ["Control"] });
    await p.getByTestId("manga-E").click({ modifiers: ["Shift"] });
    assert.match(await p.getByTestId("selection-count").textContent(), /4/);
    await p.getByTestId("manga-C").click({ modifiers: ["Shift"] });
    assert.match(await p.getByTestId("selection-count").textContent(), /2/);
    await p.getByTestId("manga-D").click();
    assert.equal(await p.getByTestId("detail-back").count(), 0);
    assert.match(await p.getByTestId("selection-count").textContent(), /3/);
    await p.getByTestId("manga-B").click();
    await p.getByTestId("manga-C").click();
    await p.getByTestId("manga-D").click();
    assert.equal(await p.getByTestId("selection-count").count(), 0);
    assert.equal(await p.getByTestId("detail-back").count(), 0);
    await p.getByTestId("manga-D").click();
    assert.equal(await p.getByTestId("detail-back").count(), 1);
    await p.getByTestId("detail-back").click();
    assert.equal(await p.getByTestId("selection-count").count(), 0);
  }));
test("面板即时偏好、三标签、背景 inert、Escape 还焦与角标独立续读", () =>
  run(async (p) => {
    await p.getByTestId("panel-open").click();
    assert.equal(await p.locator("#content").getAttribute("inert"), "");
    await p.getByTestId("panel-tab-display").click();
    await p.getByTestId("pref-unreadBadge").uncheck();
    await p.keyboard.press("Escape");
    assert.equal(
      await p
        .getByTestId("panel-open")
        .evaluate((e) => e === document.activeElement),
      true,
    );
    assert.equal(await p.getByTestId("unread-A").count(), 0);
    await p.getByTestId("continue-A").click();
    assert.equal(await p.getByTestId("reader-back").count(), 1);
  }));
test("两段原生 wheel 才启动；实际更新结果可重试", () =>
  run(async (p) => {
    await p.evaluate(() => window.demo.scenario("partial-failure"));
    await p
      .getByTestId("library-scroll")
      .dispatchEvent("wheel", { deltaY: -90 });
    assert.match(await p.getByTestId("wheel-hint").textContent(), /再次/);
    assert.equal(await p.getByTestId("update-details").count(), 0);
    await p.waitForTimeout(430);
    await p
      .getByTestId("library-scroll")
      .dispatchEvent("wheel", { deltaY: -60 });
    await p.getByTestId("update-details").click();
    await p.waitForTimeout(1800);
    assert.match(
      await p.getByTestId("update-results").textContent(),
      /图源暂时不可用/,
    );
    await p.evaluate(() => window.demo.command("source-ok"));
    await p.getByTestId("retry-failed").click();
    await p.waitForTimeout(400);
    assert.match(await p.getByTestId("update-results").textContent(), /成功 1/);
  }));
test("双端 file 入口、设置隔离和 320px / 200% 字号可达", () =>
  run(
    async (p) => {
      const pc = p.frameLocator("#preview-windows"),
        phone = p.frameLocator("#preview-android");
      await pc.getByTestId("search-open").click();
      await pc.getByTestId("library-query").fill("星海");
      await openMoreSettings(phone);
      await phone.getByTestId("pref-interval").selectOption("48");
      assert.equal(await pc.getByTestId("library-query").inputValue(), "星海");
      await p.getByTestId("font-toggle").click();
      await phone.getByTestId("modal-close").click();
      assert.equal(
        await phone
          .locator("body")
          .evaluate((e) => e.scrollWidth <= e.clientWidth),
        true,
      );
    },
    "index.html",
    1440,
  ));
test("分类位置按稳定ID恢复，面板重绘保留页内滚动", () =>
  run(async (p) => {
    await p.evaluate(() => window.demo.scenario("large"));
    const sc = p.getByTestId("library-scroll");
    await sc.evaluate((e) => (e.scrollTop = 1700));
    const old = await sc.evaluate((e) => e.scrollTop);
    await p.getByTestId("category-2").click();
    await p.getByTestId("category-1").click();
    assert.equal(await sc.evaluate((e) => e.scrollTop), old);
    await p.getByTestId("panel-open").click();
    await p.getByTestId("panel-tab-display").click();
    await p
      .locator(".sheet-body")
      .evaluate((e) => (e.scrollTop = e.scrollHeight));
    const prior = await p.locator(".sheet-body").evaluate((e) => e.scrollTop);
    await p.getByTestId("pref-counts").click();
    assert.equal(
      await p.locator(".sheet-body").evaluate((e) => e.scrollTop),
      prior,
    );
  }));
test("顶部滚轮：同段不确认、反向取消、超时清除、任务后冷却可再次武装", () =>
  run(async (p) => {
    const sc = p.getByTestId("library-scroll"),
      hint = p.getByTestId("wheel-hint");
    await sc.dispatchEvent("wheel", { deltaY: -80 });
    await sc.dispatchEvent("wheel", { deltaY: -80 });
    assert.equal(await p.getByTestId("update-details").count(), 0);
    await sc.dispatchEvent("wheel", { deltaY: 10 });
    assert.equal(await hint.textContent(), "");
    await sc.dispatchEvent("wheel", { deltaY: -40 });
    assert.match(await hint.textContent(), /准备/);
    await p.waitForTimeout(3250);
    assert.equal(await hint.textContent(), "");
    await sc.dispatchEvent("wheel", { deltaY: -80 });
    await p.waitForTimeout(420);
    await sc.dispatchEvent("wheel", { deltaY: -48 });
    await p.waitForTimeout(2400);
    await sc.dispatchEvent("wheel", { deltaY: -80 });
    assert.match(await hint.textContent(), /再次/);
  }));
test("设置失败与草稿取消通过真实入口、默认分类影响新收藏", () =>
  run(async (p) => {
    await openMoreSettings(p);
    await p.getByTestId("pref-defaultCategory").selectOption("2");
    await p.getByTestId("add-book").click();
    await p.getByTestId("nav-library").click();
    await p.getByTestId("category-2").click();
    assert.equal(await p.locator(".book").count(), 2);
    await openMoreSettings(p);
    await p.getByTestId("policy-open").click();
    await p.getByTestId("policy-0").click();
    await p.getByTestId("modal-back").click();
    await p.getByTestId("policy-open").click();
    assert.match(await p.getByTestId("policy-0").textContent(), /不指定/);
    await p.getByTestId("modal-close").click();
    await p.getByTestId("nav-library").click();
    await p.evaluate(() => window.demo.scenario("save-failure"));
    await p.getByTestId("panel-open").click();
    await p.getByTestId("panel-tab-display").click();
    await p.getByTestId("layout-list").click();
    assert.equal(await p.locator(".books.compact").count(), 1);
    assert.match(await p.getByRole("dialog").textContent(), /保存失败/);
  }));
test("Android 长按与选择模式普通点击保留平台语义", () =>
  run(
    async (p) => {
      await p.getByTestId("manga-A").dispatchEvent("pointerdown", {
        button: 0,
        pointerId: 1,
        clientX: 20,
        clientY: 100,
      });
      await p.waitForTimeout(540);
      await p
        .getByTestId("manga-A")
        .dispatchEvent("pointerup", { button: 0, pointerId: 1 });
      await p.getByTestId("manga-B").click();
      assert.match(await p.getByTestId("selection-count").textContent(), /2/);
      assert.equal(await p.getByTestId("detail-back").count(), 0);
    },
    "device.html?platform=android",
    320,
  ));
test("周期发布门禁可打开，封面变化来自应用外指令", () =>
  run(async (p) => {
    await p.evaluate(() => window.demo.scenario("custom-period"));
    await p.getByTestId("panel-open").click();
    await p.getByTestId("filter-custom").click();
    assert.equal(await p.locator(".book").count(), 1);
    await p.keyboard.press("Escape");
    await p.getByTestId("manga-A").click();
    await openCoverMenu(p);
    await p.getByTestId("cover-replace").click();
    await p.getByTestId("modal-close").click();
    await p.evaluate(() => window.demo.command("source-cover"));
    assert.match(await p.locator(".hero .cover").textContent(), /自定义/);
    await openCoverMenu(p);
    await p.getByTestId("cover-delete").click();
    await p.getByTestId("modal-close").click();
    assert.match(await p.locator(".hero .cover").textContent(), /源封面 · 2/);
  }));
test("改变网格列数保持可见作品锚点，更新进度不夺走弹层按钮焦点", () =>
  run(async (p) => {
    await p.evaluate(() => window.demo.scenario("large"));
    await p.getByTestId("library-scroll").evaluate((e) => (e.scrollTop = 1400));
    const first = () =>
      p.evaluate(() => {
        const sc = document.querySelector(".scroll-content");
        return [...sc.querySelectorAll(".book")].find(
          (e) =>
            e.getBoundingClientRect().bottom > sc.getBoundingClientRect().top,
        )?.dataset.book;
      });
    const id = await first();
    await p.getByTestId("panel-open").click();
    await p.getByTestId("panel-tab-display").click();
    await p.getByTestId("pref-columns").fill("6");
    await p.keyboard.press("Escape");
    assert.equal(await first(), id);
    await p.evaluate(() => window.demo.scenario("partial-failure"));
    await openLibraryMenuAction(p, "refresh");
    await p.getByTestId("update-details").click();
    await p.getByTestId("update-cancel").focus();
    await p.waitForTimeout(350);
    assert.equal(
      await p
        .getByTestId("update-cancel")
        .evaluate((e) => e === document.activeElement),
      true,
    );
  }));
