const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);
const M = require("./model.js");

async function visit(fn, platform = "windows", width = 1000) {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width, height: 800 } });
    page.setDefaultTimeout(2500);
    await page.goto(pathToFileURL(path.join(__dirname, "device.html")).href + `?platform=${platform}`);
    await fn(page);
  } finally { await browser.close(); }
}

async function openDetailCategories(page) {
  await page.getByTestId("detail-overflow").click();
  await page.getByTestId("detail-categories").click();
}

test("分类管理只能从设置或详情分类对话框进入，排序立即反映标签", () => visit(async p => {
  assert.equal(await p.getByTestId("category-open").count(), 0);
  await p.getByTestId("settings-open").click();
  await p.getByTestId("category-open").click();
  assert.equal(await p.getByTestId("category-manage-row-0").count(), 0);
  assert.equal(await p.getByTestId("category-up-1").isDisabled(), true);
  await p.getByTestId("category-up-2").click();
  assert.deepEqual(await p.locator(".categories button").allTextContents(), ["默认 0", "珍藏 1", "追更中 5"]);
  assert.equal(await p.getByTestId("category-1").getAttribute("class"), "active");
  await p.keyboard.press("Escape");
  assert.equal(await p.getByRole("dialog").getAttribute("aria-label"), "书架设置");
  await p.keyboard.press("Escape");
  await p.evaluate(() => window.demo.command("restart"));
  assert.deepEqual(await p.locator(".categories button").allTextContents(), ["默认 0", "珍藏 1", "追更中 5"]);
}));

test("详情分类排除系统默认，编辑丢弃草稿并进入同一管理页", () => visit(async p => {
  await p.getByTestId("manga-A").click();
  await openDetailCategories(p);
  assert.equal(await p.getByTestId("detail-category-0").count(), 0);
  await p.getByTestId("detail-category-1").uncheck();
  await p.getByTestId("detail-category-manage").click();
  assert.equal(await p.getByTestId("category-up-2").count(), 1);
  await p.getByTestId("category-up-2").click();
  await p.getByTestId("modal-close").click();
  assert.equal(await p.getByRole("dialog").count(), 0);
  assert.equal(await p.getByTestId("detail-overflow").evaluate(e => e === document.activeElement), true);
  await openDetailCategories(p);
  assert.equal(await p.getByTestId("detail-category-1").isChecked(), true);
  await p.getByTestId("detail-category-1").uncheck();
  await p.getByTestId("detail-category-save").click();
  await openDetailCategories(p);
  assert.equal(await p.getByTestId("detail-category-1").isChecked(), false);
  assert.equal(await p.getByTestId("detail-category-2").isChecked(), false);
}));

test("空自定义分类从详情提示进入管理，新建后重开才能勾选", () => visit(async p => {
  await p.evaluate(() => window.demo.scenario("single-default"));
  await p.getByTestId("manga-A").click();
  await openDetailCategories(p);
  assert.match(await p.getByRole("dialog").textContent(), /尚未创建/);
  assert.equal(await p.getByTestId("detail-category-save").count(), 0);
  await p.getByTestId("detail-category-manage").click();
  await p.locator("#category-name").fill("稍后阅读");
  await p.getByTestId("category-add").click();
  await p.getByTestId("modal-close").click();
  await openDetailCategories(p);
  await p.getByTestId("detail-category-1").check();
  await p.getByTestId("detail-category-save").click();
  await openDetailCategories(p);
  assert.equal(await p.getByTestId("detail-category-1").isChecked(), true);
}, "android", 320));

test("分类拖动使用稳定ID且保存失败不改变顺序", () => visit(async p => {
  await p.getByTestId("settings-open").click();
  await p.getByTestId("category-open").click();
  await p.getByTestId("category-drag-2").dragTo(p.getByTestId("category-manage-row-1"));
  assert.deepEqual(await p.locator(".category-manage-row").evaluateAll(rows => rows.map(row => row.dataset.id)), ["2", "1"]);
  await p.getByTestId("modal-close").click();
  await p.evaluate(() => window.demo.scenario("save-failure"));
  await p.getByTestId("settings-open").click();
  await p.getByTestId("category-open").click();
  await p.getByTestId("category-up-1").click();
  assert.deepEqual(await p.locator(".category-manage-row").evaluateAll(rows => rows.map(row => row.dataset.id)), ["2", "1"]);
  assert.match(await p.getByRole("dialog").textContent(), /保存失败/);
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

test("共用分类管理保留空名称和重名校验", () => visit(async p => {
  await p.getByTestId("settings-open").click();
  await p.getByTestId("category-open").click();
  await p.getByTestId("category-add").click();
  assert.match(await p.getByRole("dialog").textContent(), /请输入分类名称/);
  await p.locator("#category-name").fill("珍藏");
  await p.getByTestId("category-add").click();
  assert.match(await p.getByRole("dialog").textContent(), /分类已存在/);
  assert.equal(await p.locator(".category-manage-row").count(), 2);
}));

test("分类管理可重命名且作品归属仍按原分类身份保留", () => visit(async p => {
  await p.getByTestId("settings-open").click();
  await p.getByTestId("category-open").click();
  await p.getByTestId("category-rename-1").click();
  assert.equal(await p.getByTestId("category-rename-input").inputValue(), "追更中");
  await p.getByTestId("category-rename-input").fill("珍藏");
  await p.getByTestId("category-rename-save").click();
  assert.match(await p.getByRole("dialog").textContent(), /分类已存在/);
  await p.getByTestId("category-rename-input").fill("正在追更");
  await p.getByTestId("category-rename-save").click();
  assert.deepEqual(await p.locator(".categories button").allTextContents(), ["默认 0", "正在追更 5", "珍藏 1"]);
  await p.keyboard.press("Escape");
  await p.keyboard.press("Escape");
  await p.getByTestId("manga-A").click();
  await openDetailCategories(p);
  assert.equal(await p.getByTestId("detail-category-1").isChecked(), true);
  assert.match(await p.getByRole("dialog").textContent(), /正在追更/);
}));

test("重排后删除分类按稳定身份确认，取消不变且作品回到默认", () => visit(async p => {
  await p.getByTestId("settings-open").click();
  await p.getByTestId("category-open").click();
  await p.getByTestId("category-up-2").click();
  await p.getByTestId("delete-category-2").click();
  assert.match(await p.getByRole("dialog").textContent(), /作品保留/);
  await p.getByTestId("modal-cancel").click();
  assert.equal(await p.getByTestId("category-manage-row-2").count(), 1);
  assert.deepEqual(await p.evaluate(() => window.demo.state.books.find(b => b.id === "F").categories), [2]);
  await p.getByTestId("delete-category-2").click();
  await p.getByTestId("confirm-yes").click();
  assert.deepEqual(await p.evaluate(() => window.demo.state.categories.map(c => c.id)), [0, 1]);
  assert.deepEqual(await p.evaluate(() => window.demo.state.books.find(b => b.id === "F").categories), [0]);
  assert.deepEqual(await p.evaluate(() => window.demo.state.books.find(b => b.id === "A").categories), [1]);
  assert.equal(await p.getByTestId("category-manage-row-2").count(), 0);
}));
