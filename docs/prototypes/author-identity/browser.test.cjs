const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH);
async function setup(run) {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1120 } });
    const errors = [], remote = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('request', req => { if (/^https?:/.test(req.url())) remote.push(req.url()); });
    await page.goto(pathToFileURL(path.resolve(__dirname, 'index.html')).href);
    const pc = page.frameLocator('#preview-windows'), phone = page.frameLocator('#preview-android');
    await pc.getByTestId('signature').waitFor(); await phone.getByTestId('signature').waitFor();
    await run(page, pc, phone);
    assert.deepEqual(errors, []); assert.deepEqual(remote, []);
  } finally { await browser.close(); }
}
const action = (f, a) => f.locator(`[data-action="${a}"]`);
test('直接添加无需预览且全局设置只在双端作者列表右上角', () => setup(async (page, pc, phone) => {
  await page.locator('#narrow').check();
  for (const f of [pc, phone]) {
    await f.getByTestId('signature').click(); assert.equal(await f.getByRole('button', { name: '作者设置', exact: true }).count(), 0);
    await action(f, 'merge-select').click(); assert.equal(await f.getByRole('button', { name: '添加', exact: true }).count(), 1);
    await f.locator('[data-select="en"]').check(); await f.getByRole('button', { name: '添加', exact: true }).click();
    assert.equal(await f.getByRole('dialog').count(), 0); assert.match(await f.locator('.hero').innerText(), /3 部作品 · 4 个来源版本/);
    assert.equal(await f.locator('body').evaluate(e => e.scrollWidth > innerWidth), false);
  }
  await pc.getByTestId('nav-authors').click(); await phone.getByTestId('nav-browse').click(); await action(phone, 'authors').click();
  for (const f of [pc, phone]) { const gear = f.getByRole('button', { name: '作者设置', exact: true }); const g = await gear.boundingBox(), b = await f.locator('.bar').boundingBox(); assert.ok(g.x > b.x + b.width / 2); }
}));
test('修改主名取消空白占用、新名和已有别名选择，原名字继续复用', () => setup(async (page, pc) => {
  await pc.getByTestId('signature').click(); await action(pc, 'rename').click(); await pc.getByLabel('主显示名称').fill('新主名'); await pc.getByRole('button', { name: '取消', exact: true }).click(); assert.equal(await pc.getByTestId('author-name').innerText(), '冈本伦');
  await action(pc, 'rename').click(); await pc.getByLabel('主显示名称').fill('  '); await action(pc, 'rename-save').click(); assert.match(await pc.getByRole('alert').innerText(), /非空/);
  await pc.getByLabel('主显示名称').fill('Okamoto Lynn'); await action(pc, 'rename-save').click(); assert.match(await pc.getByRole('alert').innerText(), /添加别名/);
  await pc.getByLabel('主显示名称').fill('新主名'); await action(pc, 'rename-save').click(); assert.equal(await pc.getByTestId('author-name').innerText(), '新主名'); assert.match(await pc.getByTestId('author-aliases').innerText(), /冈本伦/);
  await action(pc, 'rename').click(); await pc.getByLabel('已有名字').selectOption('冈本伦'); await action(pc, 'rename-save').click(); assert.equal(await pc.getByTestId('author-name').innerText(), '冈本伦');
  await page.locator('#add-same').click(); await pc.getByText('已加入同名作品', { exact: false }).waitFor(); assert.match(await pc.locator('.hero').innerText(), /4 部作品/);
}));
test('全局三频率保存取消失败、跨作者操作及另一设备隔离', () => setup(async (page, pc, phone) => {
  const settings = f => f.getByRole('button', { name: '作者设置', exact: true });
  await pc.getByTestId('nav-authors').click();
  for (const frequency of ['weekly', 'monthly', 'daily', 'monthly']) { await settings(pc).click(); await pc.getByLabel('检查频率').selectOption(frequency); await action(pc, 'settings-save').click(); assert.equal(await settings(pc).evaluate(e => e === document.activeElement), true); await settings(pc).click(); assert.equal(await pc.getByLabel('检查频率').inputValue(), frequency); await pc.getByRole('button', { name: '取消', exact: true }).click(); }
  await settings(pc).click(); await pc.getByLabel('检查频率').selectOption('weekly'); await pc.getByRole('button', { name: '取消', exact: true }).click();
  await pc.locator('[data-author="en"]').click(); await action(pc, 'merge-select').click(); await pc.locator('[data-select="a"]').check(); await action(pc, 'add').click(); await action(pc, 'rename').click(); await pc.getByLabel('主显示名称').fill('新名字'); await action(pc, 'rename-save').click();
  await pc.getByTestId('nav-authors').click(); await settings(pc).click(); assert.equal(await pc.getByLabel('检查频率').inputValue(), 'monthly'); await pc.getByRole('button', { name: '关闭', exact: true }).click();
  await phone.getByTestId('nav-browse').click(); await action(phone, 'authors').click(); await settings(phone).click(); assert.equal(await phone.getByLabel('检查频率').inputValue(), 'daily');
  await page.locator('#scenario').selectOption('submit-error'); await page.locator('#apply-scenario').click(); await pc.getByTestId('nav-authors').click(); await settings(pc).click(); await pc.getByLabel('检查频率').selectOption('weekly'); await action(pc, 'settings-save').click(); assert.match(await pc.getByRole('alert').innerText(), /保存失败/); await pc.getByRole('button', { name: '取消', exact: true }).click(); await settings(pc).click(); assert.equal(await pc.getByLabel('检查频率').inputValue(), 'daily');
}));
test('直接添加失败保留选择、过期在当前窗刷新、搜索与键盘焦点', () => setup(async (page, pc) => {
  const scenario = async value => { await page.locator('#scenario').selectOption(value); await page.locator('#apply-scenario').click(); await pc.getByTestId('signature').click(); await action(pc, 'merge-select').click(); await pc.locator('[data-select="en"]').check(); };
  await scenario('submit-error'); await action(pc, 'add').click(); assert.match(await pc.getByRole('alert').innerText(), /未改变/); assert.equal(await pc.locator('[data-select="en"]').isChecked(), true);
  await pc.getByRole('button', { name: '关闭', exact: true }).press('Escape'); assert.equal(await action(pc, 'merge-select').evaluate(e => e === document.activeElement), true);
  await scenario('stale'); const first = pc.getByRole('button', { name: '关闭', exact: true }); await first.focus(); await first.press('Shift+Tab'); assert.equal(await action(pc, 'add').evaluate(e => e === document.activeElement), true); await action(pc, 'add').press('Tab'); assert.equal(await first.evaluate(e => e === document.activeElement), true); await action(pc, 'add').click(); assert.match(await pc.getByRole('alert').innerText(), /资料已更新/); assert.equal(await pc.getByRole('dialog').getAttribute('aria-labelledby'), 'dialog-title');
  await action(pc, 'refresh-candidates').click(); await pc.getByLabel('搜索名字或别名').fill('不存在'); assert.match(await pc.getByRole('dialog').innerText(), /没有匹配/); await pc.getByLabel('搜索名字或别名').fill(''); assert.equal(await pc.locator('[data-select="en"]').isChecked(), true);
  await action(pc, 'add').click(); assert.equal(await pc.getByRole('dialog').count(), 0);
}));
