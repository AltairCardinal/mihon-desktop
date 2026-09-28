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
    await p.getByTestId("modal-close").click();
    await p.getByTestId("category-2").click();
    await p.getByTestId("panel-open").click();
    await p.getByTestId("panel-tab-sort").click();
    assert.match(await p.getByTestId("sort-title").textContent(), /升序/);
    await p.getByTestId("modal-close").click();
    await p.getByTestId("settings-open").click();
    await p.getByTestId("pref-perCategory").uncheck();
    await p.getByTestId("pref-perCategory").check();
    await p.getByTestId("modal-close").click();
    await p.getByTestId("category-1").click();
    await p.getByTestId("panel-open").click();
    await p.getByTestId("panel-tab-sort").click();
    assert.match(await p.getByTestId("sort-title").textContent(), /升序/);
  }));

test("审查修复：替换自定义封面产生独立版本且源更新不覆盖它", () =>
  run(async (p) => {
    await p.getByTestId("manga-A").click();
    await p.getByTestId("cover-replace").click();
    const first = await p.locator(".hero .cover").screenshot();
    await p.getByTestId("cover-replace").click();
    const second = await p.locator(".hero .cover").screenshot();
    assert.notDeepEqual(first, second);
    await p.evaluate(() => window.demo.command("source-cover"));
    assert.deepEqual(await p.locator(".hero .cover").screenshot(), second);
    await p.getByTestId("cover-delete").click();
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
    await p.getByTestId("settings-open").click();
    await p.getByTestId("pref-metadata").check();
    await p.getByTestId("modal-close").click();
    await p.getByTestId("refresh").click();
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
      await phone.getByTestId("settings-open").click();
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
    await p.getByTestId("settings-open").click();
    await p.getByTestId("pref-defaultCategory").selectOption("2");
    await p.getByTestId("add-book").click();
    await p.getByTestId("category-2").click();
    assert.equal(await p.locator(".book").count(), 2);
    await p.getByTestId("settings-open").click();
    await p.getByTestId("policy-open").click();
    await p.getByTestId("policy-0").click();
    await p.getByTestId("modal-back").click();
    await p.getByTestId("policy-open").click();
    assert.match(await p.getByTestId("policy-0").textContent(), /不指定/);
    await p.getByTestId("modal-close").click();
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
    await p.getByTestId("modal-close").click();
    await p.getByTestId("manga-A").click();
    await p.getByTestId("cover-replace").click();
    await p.evaluate(() => window.demo.command("source-cover"));
    assert.match(await p.locator(".hero .cover").textContent(), /自定义/);
    await p.getByTestId("cover-delete").click();
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
    await p.getByTestId("pref-columns").selectOption("6");
    await p.getByTestId("modal-close").click();
    assert.equal(await first(), id);
    await p.evaluate(() => window.demo.scenario("partial-failure"));
    await p.getByTestId("refresh").click();
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
