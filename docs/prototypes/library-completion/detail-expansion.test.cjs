const { openCoverMenu } = require("./detail-test-helpers.cjs");
const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);

async function run(fn, width = 1000, platform = "windows") {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width, height: 800 } });
    page.setDefaultTimeout(3000);
    await page.goto(pathToFileURL(path.join(__dirname, "device.html")).href + `?platform=${platform}`);
    await page.getByTestId("manga-A").click();
    await fn(page);
  } finally {
    await browser.close();
  }
}

test("详情作品信息：标题、作者画师、标签可搜索复制，来源语言和完整状态有反馈", () =>
  run(async (page) => {
    await page.getByTestId("detail-title-search").click();
    assert.match(await page.getByTestId("detail-search-preview").textContent(), /星海手记/);
    await page.keyboard.press("Escape");
    await page.getByTestId("detail-title-search").click({ button: "right" });
    assert.match(await page.getByTestId("notice").textContent(), /标题.*复制/);
    await page.getByTestId("detail-author").click({ button: "right" });
    await page.getByTestId("detail-author-search").click();
    assert.match(await page.getByTestId("detail-search-preview").textContent(), /作者.*林舟/);
    await page.keyboard.press("Escape");
    await page.getByTestId("detail-author").click({ button: "right" });
    await page.getByTestId("detail-author-copy").click();
    await page.getByTestId("detail-artist").click({ button: "right" });
    await page.getByTestId("detail-artist-copy").click();
    assert.match(await page.getByTestId("notice").textContent(), /画师.*复制/);
    assert.match(await page.getByTestId("detail-source-line").textContent(), /中文/);
    await page.getByTestId("detail-source-entry").click();
    assert.match(await page.getByTestId("notice").textContent(), /图源/);
    await page.keyboard.press("Escape");
    await page.getByTestId("detail-tag-0").click();
    await page.getByTestId("detail-tag-search").click();
    await page.getByTestId("detail-tag-source").click();
    assert.match(await page.getByTestId("detail-search-preview").textContent(), /冒险/);
    await page.keyboard.press("Escape");
    await page.getByTestId("detail-tag-0").click();
    await page.getByTestId("detail-tag-copy").click();
    assert.match(await page.getByTestId("notice").textContent(), /标签.*复制/);
    await page.evaluate(() => {
      window.demo.state.books[0].status = "已获授权";
      window.demo.command("noop");
    });
    assert.match(await page.getByTestId("detail-source-line").textContent(), /已获授权/);
    await page.evaluate(() => {
      window.demo.state.books[0].sourceMissing = true;
      window.demo.command("noop");
    });
    await page.getByTestId("detail-source-entry").click();
    assert.match(await page.getByTestId("notice").textContent(), /缺失.*图源/);
  }));

test("详情简介：六行折叠可展开，Markdown 链接预览和复制保持纯文本安全", () =>
  run(async (page) => {
    await page.evaluate(() => {
      window.demo.state.books[0].description = "# 前言\n**重点** [资料](https://example.org/read)\n" + "漫长的简介。".repeat(45);
      window.demo.command("noop");
    });
    assert.equal(await page.getByTestId("detail-description").evaluate((e) => e.classList.contains("expanded")), false);
    await page.getByTestId("detail-description-toggle").click();
    assert.equal(await page.getByTestId("detail-description").evaluate((e) => e.classList.contains("expanded")), true);
    assert.equal(await page.getByTestId("detail-description").locator("strong").count(), 2);
    await page.getByTestId("detail-description").locator("a").click();
    assert.match(await page.getByTestId("detail-information").textContent(), /本地.*链接/);
    await page.keyboard.press("Escape");
    await page.getByTestId("detail-description").click({ button: "right" });
    assert.match(await page.getByTestId("notice").textContent(), /简介.*复制/);
    await page.getByTestId("detail-description-toggle").click();
    assert.equal(await page.getByTestId("detail-description").evaluate((e) => e.classList.contains("expanded")), false);
  }));

test("详情封面：点击全屏、缩放、保存分享模拟，删除按自定义封面状态可用", () =>
  run(async (page) => {
    await openCoverMenu(page);
    assert.equal(await page.getByTestId("cover-delete").isDisabled(), true);
    await page.keyboard.press("Escape");
    assert.equal(await page.getByTestId("detail-cover-viewer").isVisible(), true);
    await page.getByTestId("detail-cover-zoom-in").click();
    assert.match(await page.getByTestId("detail-cover-scale").textContent(), /125%/);
    await page.getByTestId("detail-cover-save").click();
    assert.match(await page.getByTestId("notice").textContent(), /本地.*保存/);
    await page.getByTestId("detail-cover-share").click();
    assert.match(await page.getByTestId("notice").textContent(), /本地.*分享/);
    await page.keyboard.press("Escape");
    assert.equal(await page.getByTestId("detail-cover-viewer").count(), 0);
    await openCoverMenu(page);
    await page.getByTestId("cover-replace").click();
    await page.getByTestId("modal-close").click();
    await openCoverMenu(page);
    assert.equal(await page.getByTestId("cover-delete").isEnabled(), true);
  }));

test("详情收藏与分类：重复收藏有反馈，移出可选删下载，分类弹层可管理", () =>
  run(async (page) => {
    await page.getByTestId("detail-library").click();
    assert.match(await page.locator(".sheet").textContent(), /取消收藏.*下载/);
    await page.getByTestId("modal-cancel").click();
    assert.match(await page.getByTestId("detail-library").textContent(), /已收藏/);
    await page.getByTestId("detail-library").click();
    await page.getByTestId("detail-remove-downloads").check();
    await page.getByTestId("confirm-yes").click();
    assert.match(await page.getByTestId("detail-library").textContent(), /加入书架/);
    assert.equal(await page.evaluate(() => window.demo.state.books[0].chapters.some((c) => c.download)), false);
    await page.getByTestId("detail-library").click();
    assert.match(await page.getByTestId("notice").textContent(), /已加入书架/);
    await page.getByTestId("detail-library").click();
    await page.getByTestId("confirm-yes").click();
    await page.getByTestId("detail-library").click();
    assert.match(await page.getByTestId("notice").textContent(), /已加入书架/);
    await page.getByTestId("detail-overflow").click();
    await page.getByTestId("detail-categories").click();
    await page.getByTestId("detail-category-manage").click();
    await page.locator("#category-name").fill("周末看");
    await page.getByTestId("category-add").click();
    await page.getByTestId("modal-close").click();
    await page.getByTestId("detail-overflow").click();
    await page.getByTestId("detail-categories").click();
    await page.getByTestId("detail-category-3").check();
    await page.getByTestId("detail-category-save").click();
    assert.match(await page.getByTestId("notice").textContent(), /分类.*更新/);
  }));

test("详情更新间隔和内联笔记：立即反馈、Markdown 工具、保存失败恢复", () =>
  run(async (page) => {
    await page.getByTestId("detail-fetch-interval").click();
    await page.getByTestId("detail-interval-7").check();
    await page.getByTestId("detail-interval-save").click();
    assert.match(await page.getByTestId("detail-fetch-interval").textContent(), /7 天/);
    await page.getByTestId("detail-overflow").click();
    await page.getByTestId("detail-notes").click();
    await page.getByTestId("detail-notes-input").fill("记住这个角色");
    await page.getByTestId("detail-notes-bold").click();
    assert.match(await page.getByTestId("detail-notes-input").inputValue(), /\*\*/);
    await page.getByTestId("detail-notes-save").click();
    assert.match(await page.getByTestId("detail-notes-inline").textContent(), /记住这个角色/);
    await page.getByTestId("detail-overflow").click();
    await page.getByTestId("detail-notes").click();
    await page.getByTestId("detail-notes-input").fill("会失败的新内容");
    await page.evaluate(() => window.demo.state.failSave = true);
    await page.getByTestId("detail-notes-save").click();
    assert.match(await page.getByTestId("notice").textContent(), /保存失败/);
    assert.match(await page.getByTestId("detail-notes-input").inputValue(), /会失败/);
    assert.doesNotMatch(await page.evaluate(() => window.demo.state.books[0].notes), /会失败/);
  }));

test("详情布局与返回：宽屏双栏、窄屏单列、长列表滚动条、底导航和 Escape 一层一层退", () =>
  run(async (page) => {
    assert.equal(await page.getByTestId("detail-layout").evaluate((e) => getComputedStyle(e).gridTemplateColumns.split(" ").length), 2);
    await page.evaluate(() => {
      const b = window.demo.state.books[0];
      b.chapters = Array.from({ length: 80 }, (_, i) => ({ ...b.chapters[0], id: `A-long-${i}`, number: i + 1, name: `第 ${i + 1} 话` }));
      window.demo.command("noop");
    });
    assert.equal(await page.getByTestId("detail-chapter-scroll").evaluate((e) => e.scrollHeight > e.clientHeight), true);
    assert.equal(await page.getByTestId("nav-library").isVisible(), true);
    await page.getByTestId("detail-cover-open").click();
    await page.keyboard.press("Escape");
    assert.equal(await page.getByTestId("detail-back").count(), 1);
    await page.keyboard.press("Escape");
    assert.equal(await page.getByTestId("manga-A").count(), 1);
    await page.getByTestId("manga-A").click();
    await page.setViewportSize({ width: 360, height: 700 });
    assert.equal(await page.getByTestId("detail-layout").evaluate((e) => getComputedStyle(e).gridTemplateColumns.split(" ").length), 1);
  }));

test("详情重复收藏先展示已有作品，页面底导航真实切换且分类回显", () =>
  run(async (page) => {
    await page.evaluate(() => {
      const s = window.demo.state;
      s.books[0].favorite = false;
      s.books[1].title = s.books[0].title;
      s.books[1].source = s.books[0].source;
      window.demo.command("noop");
    });
    await page.getByTestId("detail-library").click();
    assert.match(await page.locator(".sheet").textContent(), /已有同名/);
    await page.getByTestId("detail-duplicate-view").click();
    assert.equal(await page.getByTestId("detail-scroll").getAttribute("data-book-id"), "B");
    await page.getByTestId("detail-overflow").click();
    await page.getByTestId("detail-categories").click();
    await page.getByTestId("detail-category-2").check();
    await page.getByTestId("detail-category-save").click();
    await page.getByTestId("detail-overflow").click();
    await page.getByTestId("detail-categories").click();
    assert.equal(await page.getByTestId("detail-category-2").isChecked(), true);
    await page.getByTestId("modal-close").click();
    await page.getByTestId("nav-updates").click();
    assert.match(await page.getByTestId("root-page").textContent(), /更新/);
    assert.equal(await page.getByTestId("detail-scroll").count(), 0);
    await page.getByTestId("nav-library").click();
    assert.equal(await page.getByTestId("manga-B").count(), 1);
  }));

test("详情笔记工具生成粗体斜体下划线与两类列表，取消和 Escape 丢弃草稿", () =>
  run(async (page) => {
    await page.getByTestId("detail-overflow").click();
    await page.getByTestId("detail-notes").click();
    const formats = [
      ["bold", "**文字**"],
      ["italic", "*文字*"],
      ["underline", "++文字++"],
      ["bullet", "- 文字"],
      ["numbered", "1. 文字"],
    ];
    for (const [id, expected] of formats) {
      await page.getByTestId("detail-notes-input").fill("");
      await page.getByTestId(`detail-notes-${id}`).click();
      assert.equal(await page.getByTestId("detail-notes-input").inputValue(), expected);
    }
    await page.getByTestId("detail-notes-input").fill("**粗体**\n*斜体*\n++下划线++\n- 项目\n1. 顺序");
    await page.getByTestId("detail-notes-save").click();
    assert.equal(await page.getByTestId("detail-notes-inline").locator("strong").count(), 1);
    assert.equal(await page.getByTestId("detail-notes-inline").locator("em").count(), 1);
    assert.equal(await page.getByTestId("detail-notes-inline").locator("u").count(), 1);
    assert.match(await page.getByTestId("detail-notes-inline").textContent(), /• 项目/);
    await page.getByTestId("detail-notes-summary").click();
    await page.getByTestId("detail-notes-input").fill("不保存");
    await page.keyboard.press("Escape");
    assert.doesNotMatch(await page.getByTestId("detail-notes-inline").textContent(), /不保存/);
  }));
