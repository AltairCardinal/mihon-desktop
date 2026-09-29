const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);
const M = require("./model.js");

async function visit(fn, platform = "windows", width = 1000, review = "") {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width, height: 800 } });
    page.setDefaultTimeout(2500);
    await page.goto(pathToFileURL(path.join(__dirname, "device.html")).href + `?platform=${platform}${review ? `&review=${review}` : ""}`);
    await fn(page);
  } finally { await browser.close(); }
}

async function openDetailCategories(page) {
  await page.getByTestId("detail-overflow").click();
  await page.getByTestId("detail-categories").click();
}

async function openManager(p) {
  await p.getByTestId("nav-more").click();
  await p.getByTestId("category-open").click();
}
async function keyboardMove(p, id, key = "ArrowUp") {
  await p.getByTestId(`category-drag-${id}`).focus();
  await p.keyboard.press(`Alt+${key}`);
}
const rowIds = p => p.locator(".category-manage-row").evaluateAll(rows => rows.map(row => row.dataset.id));

test("分类审核直达链接打开新管理页，返回更多仍保留原入口", () => visit(async p => {
  assert.equal(await p.getByTestId("category-page").isVisible(), true);
  assert.equal(await p.locator("header.bar h1").textContent(), "编辑分类");
  await p.getByTestId("category-back").click();
  assert.equal(await p.getByTestId("category-open").isVisible(), true);
  assert.equal(await p.getByTestId("category-open").evaluate(e => e === document.activeElement), true);
}, "windows", 1000, "categories"));

test("更多审核直达链接直接显示分类入口位置", () => visit(async p => {
  assert.equal(await p.getByTestId("category-open").isVisible(), true);
  assert.deepEqual(await p.getByTestId("more-library-group").locator("button").allTextContents(), ["下载队列", "分类", "统计", "数据与存储"]);
}, "windows", 1000, "more"));

test("更多页单一标题和原版同组顺序，邻项说明样本边界且分类真实进入", () => visit(async p => {
  await p.getByTestId("nav-more").click();
  assert.equal(await p.getByRole("heading", { name: "更多", exact: true }).count(), 1);
  assert.deepEqual(await p.getByTestId("more-library-group").locator("button").allTextContents(), ["下载队列", "分类", "统计", "数据与存储"]);
  assert.equal(await p.getByTestId("more-library-group").getByRole("separator").count(), 2);
  for (const [id, name] of [["downloads", "下载队列"], ["stats", "统计"], ["storage", "数据与存储"]]) {
    await p.getByTestId(`more-${id}`).click();
    assert.match(await p.getByRole("dialog").textContent(), new RegExp(name));
    assert.match(await p.getByRole("dialog").textContent(), /本地.*样本.*未提供/);
    await p.keyboard.press("Escape");
    assert.equal(await p.getByTestId(`more-${id}`).evaluate(e => e === document.activeElement), true);
  }
  await p.getByTestId("category-open").click();
  assert.equal(await p.getByTestId("category-page").isVisible(), true);
}));

test("分类从更多进入独立子页，设置无入口，键盘排序返回后反映标签", () => visit(async p => {
  assert.equal(await p.getByTestId("category-open").count(), 0);
  await p.getByTestId("settings-open").click();
  assert.equal(await p.getByTestId("category-open").count(), 0);
  await p.keyboard.press("Escape");
  await openManager(p);
  assert.equal(await p.getByRole("dialog").count(), 0);
  assert.equal(await p.getByTestId("category-page").isVisible(), true);
  assert.equal(await p.locator("header.bar h1").textContent(), "编辑分类");
  assert.equal(await p.getByTestId("nav-library").count(), 0);
  assert.equal(await p.getByTestId("category-manage-row-0").count(), 0);
  assert.equal(await p.getByTestId("category-up-2").count(), 0);
  assert.equal(await p.locator("#category-name").count(), 0);
  await keyboardMove(p, 2);
  assert.deepEqual(await rowIds(p), ["2", "1"]);
  assert.equal(await p.getByTestId("category-drag-2").evaluate(e => e === document.activeElement), true);
  await p.keyboard.press("Escape");
  assert.equal(await p.getByRole("heading", { name: "更多", exact: true }).isVisible(), true);
  assert.equal(await p.getByTestId("category-open").evaluate(e => e === document.activeElement), true);
  await p.getByTestId("nav-library").click();
  assert.deepEqual(await p.locator(".categories button").allTextContents(), ["默认 0", "珍藏 1", "追更中 5"]);
  assert.equal(await p.getByTestId("category-1").getAttribute("class"), "active");
  await p.evaluate(() => window.demo.command("restart"));
  assert.deepEqual(await p.locator(".categories button").allTextContents(), ["默认 0", "珍藏 1", "追更中 5"]);
}));

test("详情编辑先丢弃分类草稿再进分类页，返回详情恢复焦点", () => visit(async p => {
  await p.getByTestId("manga-A").click();
  await openDetailCategories(p);
  assert.equal(await p.getByTestId("detail-category-0").count(), 0);
  await p.getByTestId("detail-category-1").uncheck();
  await p.getByTestId("detail-category-manage").click();
  assert.equal(await p.getByRole("dialog").count(), 0);
  assert.equal(await p.getByTestId("category-page").isVisible(), true);
  await keyboardMove(p, 2);
  await p.getByTestId("category-back").click();
  assert.equal(await p.getByTestId("detail-overflow").evaluate(e => e === document.activeElement), true);
  await openDetailCategories(p);
  assert.equal(await p.getByTestId("detail-category-1").isChecked(), true);
  await p.getByTestId("detail-category-1").uncheck();
  await p.getByTestId("detail-category-save").click();
  await openDetailCategories(p);
  assert.equal(await p.getByTestId("detail-category-1").isChecked(), false);
  assert.equal(await p.getByTestId("detail-category-2").isChecked(), false);
}));

test("Android空分类页的添加FAB打开输入弹窗，取消不新增，确认后可分配", () => visit(async p => {
  await p.evaluate(() => window.demo.scenario("single-default"));
  await p.getByTestId("manga-A").click();
  await openDetailCategories(p);
  assert.match(await p.getByRole("dialog").textContent(), /尚未创建/);
  await p.getByTestId("detail-category-manage").click();
  assert.equal(await p.getByTestId("category-empty").isVisible(), true);
  const back = await p.getByTestId("category-back").boundingBox();
  const title = await p.locator("header.bar h1").boundingBox();
  assert.ok(Math.abs(back.y + back.height / 2 - title.y - title.height / 2) < 2, "窄屏分类 AppBar 返回与标题同排居中");
  assert.ok(title.x >= back.x + back.width, "标题位于返回箭头右侧");
  await p.getByTestId("category-add").click();
  assert.equal(await p.getByTestId("category-create-save").isDisabled(), true);
  await p.locator("#category-name").fill("稍后阅读");
  await p.getByTestId("modal-cancel").click();
  assert.equal(await p.getByTestId("category-empty").isVisible(), true);
  await p.getByTestId("category-add").click();
  await p.locator("#category-name").fill("稍后阅读");
  await p.getByTestId("category-create-save").click();
  assert.equal(await p.getByRole("dialog").count(), 0);
  assert.match(await p.getByTestId("category-manage-row-1").textContent(), /稍后阅读/);
  await p.getByTestId("category-back").click();
  await openDetailCategories(p);
  await p.getByTestId("detail-category-1").check();
  await p.getByTestId("detail-category-save").click();
  await openDetailCategories(p);
  assert.equal(await p.getByTestId("detail-category-1").isChecked(), true);
}, "android", 320));

test("分类拖动使用稳定ID，排序失败保持原序并可重试", () => visit(async p => {
  await openManager(p);
  await p.getByTestId("category-drag-2").dragTo(p.getByTestId("category-manage-row-1"));
  assert.deepEqual(await rowIds(p), ["2", "1"]);
  await p.evaluate(() => window.demo.state.failSave = true);
  await keyboardMove(p, 1);
  assert.deepEqual(await rowIds(p), ["2", "1"]);
  assert.match(await p.getByTestId("notice").textContent(), /保存失败/);
  await keyboardMove(p, 1);
  assert.deepEqual(await rowIds(p), ["1", "2"]);
}));

test("新建重命名确认仅有效输入可用，整行和编辑图标等价且归属不变", () => visit(async p => {
  await openManager(p);
  await p.getByTestId("category-add").click();
  for (const name of ["", "   ", "珍藏"]) {
    await p.locator("#category-name").fill(name);
    assert.equal(await p.getByTestId("category-create-save").isDisabled(), true);
    assert.equal(await p.getByTestId("category-name-hint").textContent(), name === "珍藏" ? "分类已存在" : "必填");
    assert.equal(await p.locator("#category-name").getAttribute("aria-invalid"), String(name === "珍藏"));
  }
  await p.keyboard.press("Escape");
  await p.getByTestId("category-manage-row-1").click();
  assert.equal(await p.getByTestId("category-rename-input").inputValue(), "追更中");
  for (const name of ["追更中", "", "珍藏"]) {
    await p.getByTestId("category-rename-input").fill(name);
    assert.equal(await p.getByTestId("category-rename-save").isDisabled(), true);
    assert.equal(await p.getByTestId("category-name-hint").textContent(), name === "珍藏" ? "分类已存在" : "必填");
  }
  await p.getByTestId("category-rename-input").fill("正在追更");
  assert.equal(await p.getByTestId("category-name-hint").textContent(), "必填");
  assert.equal(await p.getByTestId("category-rename-input").getAttribute("aria-invalid"), "false");
  assert.equal(await p.getByTestId("category-rename-save").isEnabled(), true);
  await p.getByTestId("category-rename-save").click();
  await p.getByTestId("category-rename-1").click();
  assert.equal(await p.getByTestId("category-rename-input").inputValue(), "正在追更");
  await p.keyboard.press("Escape");
  assert.equal(await p.getByTestId("category-rename-1").evaluate(e => e === document.activeElement), true);
  await p.getByTestId("category-manage-row-1").focus();
  await p.keyboard.press("Enter");
  assert.equal(await p.getByTestId("category-rename-input").inputValue(), "正在追更");
  await p.keyboard.press("Escape");
  assert.equal(await p.getByTestId("category-manage-row-1").evaluate(e => e === document.activeElement), true);
  await p.getByTestId("category-back").click();
  await p.getByTestId("nav-library").click();
  assert.deepEqual(await p.locator(".categories button").allTextContents(), ["默认 0", "正在追更 5", "珍藏 1"]);
  await p.getByTestId("manga-A").click();
  await openDetailCategories(p);
  assert.equal(await p.getByTestId("detail-category-1").isChecked(), true);
}));

test("Android触摸拖动在窄屏大字体下排序，卡片按钮保持可达", () => visit(async p => {
  await p.evaluate(() => document.documentElement.style.fontSize = "28px");
  await openManager(p);
  const handle = await p.getByTestId("category-drag-2").boundingBox();
  const target = await p.getByTestId("category-manage-row-1").boundingBox();
  const session = await p.context().newCDPSession(p);
  await session.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ x: handle.x + handle.width / 2, y: handle.y + handle.height / 2 }] });
  await session.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: [{ x: target.x + target.width / 2, y: target.y + target.height / 2 }] });
  await session.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
  assert.deepEqual(await rowIds(p), ["2", "1"]);
  assert.equal(await p.getByTestId("category-page").evaluate(e => e.scrollWidth <= e.clientWidth), true);
  await p.getByTestId("category-rename-2").click();
  assert.equal(await p.getByTestId("category-rename-input").inputValue(), "珍藏");
}, "android", 320));

test("重排后按分类名称确认删除，取消不变且无归属作品回默认", () => visit(async p => {
  await openManager(p);
  await keyboardMove(p, 2);
  await p.getByTestId("delete-category-2").click();
  assert.match(await p.getByRole("dialog").textContent(), /珍藏/);
  await p.getByTestId("modal-cancel").click();
  assert.equal(await p.getByTestId("category-manage-row-2").count(), 1);
  assert.equal(await p.getByTestId("delete-category-2").evaluate(e => e === document.activeElement), true);
  assert.deepEqual(await p.evaluate(() => window.demo.state.books.find(b => b.id === "F").categories), [2]);
  await p.getByTestId("delete-category-2").click();
  await p.getByTestId("confirm-yes").click();
  assert.deepEqual(await p.evaluate(() => window.demo.state.categories.map(c => c.id)), [0, 1]);
  assert.deepEqual(await p.evaluate(() => window.demo.state.books.find(b => b.id === "F").categories), [0]);
  assert.deepEqual(await p.evaluate(() => window.demo.state.books.find(b => b.id === "A").categories), [1]);
  assert.equal(await p.getByTestId("category-manage-row-2").count(), 0);
  assert.equal(await p.getByTestId("category-manage-row-1").evaluate(e => e === document.activeElement), true);
  await p.getByTestId("delete-category-1").click();
  await p.getByTestId("confirm-yes").click();
  assert.equal(await p.getByTestId("category-add").evaluate(e => e === document.activeElement), true);
}));

test("分类顺序模型保护默认和稳定身份，拒绝无效目标", () => {
  const s = M.create();
  s.selected = ["A", "F"];
  assert.equal(M.reorderCategory(s, 2, 0), true);
  assert.deepEqual(s.categories.map(c => c.id), [0, 2, 1]);
  assert.equal(s.category, 1);
  assert.deepEqual(s.selected, ["A", "F"]);
  assert.equal(M.reorderCategory(s, 0, 1), false);
  assert.equal(M.reorderCategory(s, 2, -1), false);
  assert.equal(M.reorderCategory(s, 999, 0), false);
  assert.deepEqual(s.categories.map(c => c.id), [0, 2, 1]);
});
