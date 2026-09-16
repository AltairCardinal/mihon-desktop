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
test('同名直接进入作者页，不同名字合并后跨源共用并接纳未来别名', () => setup(async (page, pc, phone) => {
  await pc.getByTestId('signature').click();
  assert.equal(await pc.getByRole('dialog').count(), 0);
  assert.match(await pc.locator('.hero').innerText(), /3 部作品 · 3 个来源版本/);
  await action(pc, 'merge-select').click(); await pc.locator('[data-select="en"]').check();
  await action(pc, 'merge-preview').click(); await action(pc, 'commit').click();
  assert.match(await pc.locator('.hero').innerText(), /3 部作品 · 4 个来源版本/);
  await pc.locator('[data-version="md"]').click(); await pc.getByTestId('signature').click();
  assert.equal(await pc.getByTestId('author-name').innerText(), '冈本伦');
  await page.locator('#add-alias').click(); await pc.getByText('已加入别名作品', { exact: false }).waitFor();
  assert.match(await pc.locator('.hero').innerText(), /4 部作品 · 5 个来源版本/);
  await page.locator('#add-alias').click(); assert.match(await pc.locator('.hero').innerText(), /4 部作品 · 5 个来源版本/);
  await pc.getByTestId('nav-authors').click(); assert.equal(await pc.locator('[data-author]').count(), 2);
  await phone.getByTestId('signature').click(); assert.match(await phone.locator('.hero').innerText(), /3 部作品 · 3 个来源版本/);
}));
test('取消失败过期、蒙层焦点、320px与同名作品自动复用', () => setup(async (page, pc, phone) => {
  await page.locator('#narrow').check(); await phone.getByTestId('signature').click(); await action(phone, 'merge-select').click();
  const first = phone.getByRole('dialog').locator('button').first(); await first.focus(); await first.press('Shift+Tab');
  assert.equal(await phone.getByRole('dialog').locator('button:not(:disabled)').last().evaluate(e => e === document.activeElement), true);
  await first.press('Escape'); assert.equal(await action(phone, 'merge-select').evaluate(e => e === document.activeElement), true);
  assert.equal(await phone.locator('body').evaluate(e => e.scrollWidth > innerWidth), false);
  const scenario = async value => { await page.locator('#scenario').selectOption(value); await page.locator('#apply-scenario').click(); await pc.getByTestId('signature').waitFor(); await pc.getByTestId('signature').click(); };
  await scenario('submit-error'); await action(pc, 'merge-select').click(); await pc.locator('[data-select="en"]').check(); await action(pc, 'merge-preview').click(); await action(pc, 'commit').click();
  assert.match(await pc.getByRole('alert').innerText(), /未改变/);
  await pc.locator('.modal-backdrop').click({ position: { x: 3, y: 3 } });
  assert.match(await pc.locator('.hero').innerText(), /3 部作品 · 3 个来源版本/);
  await scenario('stale'); await action(pc, 'merge-select').click(); await pc.locator('[data-select="en"]').check(); await action(pc, 'merge-preview').click(); await action(pc, 'commit').click();
  assert.match(await pc.getByRole('alert').innerText(), /过期/); await action(pc, 'refresh-preview').click(); assert.equal(await pc.locator('#merge-follow').isChecked(), false);
  await action(pc, 'commit').click(); await page.locator('#add-same').click(); await pc.getByText('已加入同名作品', { exact: false }).waitFor();
  assert.match(await pc.locator('.hero').innerText(), /4 部作品 · 5 个来源版本/);
  await page.locator('#theme').selectOption('light'); assert.equal(await phone.locator('body').getAttribute('class'), 'device theme-light');
}));
test('未合并别名不越界、主名选择保留别名、空状态与来源不可用', () => setup(async (page, pc, phone) => {
  await page.locator('#add-alias').click(); await pc.getByText('请先合并 Okamoto Lynn', { exact: false }).waitFor();
  await pc.getByTestId('signature').click(); assert.match(await pc.locator('.hero').innerText(), /3 部作品 · 3 个来源版本/);
  await action(pc, 'merge-select').click(); await pc.locator('[data-select="en"]').check(); await pc.locator('[data-select="tw"]').check(); await action(pc, 'merge-preview').click();
  await pc.locator('#retain').selectOption('Okamoto Lynn'); await action(pc, 'commit').click();
  assert.equal(await pc.getByTestId('author-name').innerText(), 'Okamoto Lynn'); assert.match(await pc.locator('.hero').innerText(), /冈本伦 · 岡本倫/);
  await action(pc, 'merge-select').click(); assert.match(await pc.getByRole('dialog').innerText(), /没有可合并/); await pc.getByRole('button', { name: '关闭', exact: true }).click();
  await page.locator('#scenario').selectOption('unavailable'); await page.locator('#apply-scenario').click(); await pc.getByTestId('signature').click();
  await pc.getByText('漫画柜插件不可用', { exact: false }).waitFor(); assert.equal(await pc.getByTestId('follow').innerText(), '已关注');
  await phone.getByTestId('nav-browse').click(); await action(phone, 'authors').click(); assert.equal(await phone.locator('[data-author]').count(), 3);
}));
test('候选搜索不丢选择，外部新增保持当前合并会话并刷新真实范围', () => setup(async (page, pc) => {
  await pc.getByTestId('nav-authors').click(); await pc.locator('[data-author="en"]').click();
  await action(pc, 'merge-select').click(); await pc.locator('[data-select="a"]').check();
  await pc.locator('#candidate-search').fill('不存在');
  assert.match(await pc.getByRole('dialog').innerText(), /没有匹配/);
  await pc.locator('#candidate-search').fill(''); assert.equal(await pc.locator('[data-select="a"]').isChecked(), true);
  await action(pc, 'merge-preview').click();
  const focusBefore = await pc.getByRole('dialog').locator('button').first().getAttribute('data-action');
  await page.locator('#add-same').click();
  assert.equal(await pc.getByRole('dialog').count(), 1); assert.equal(await pc.getByTestId('author-name').innerText(), 'Okamoto Lynn');
  assert.equal(await pc.getByRole('dialog').evaluate(() => document.activeElement.getAttribute('data-action')), focusBefore);
  await action(pc, 'commit').click(); assert.match(await pc.getByRole('alert').innerText(), /过期/);
  await action(pc, 'refresh-preview').click(); assert.match(await pc.getByRole('dialog').innerText(), /4 部作品、5 个来源版本/);
  await action(pc, 'commit').click();
  await action(pc, 'merge-select').click(); await pc.locator('[data-select="tw"]').check(); await action(pc, 'merge-preview').click();
  await pc.locator('#retain').selectOption('冈本伦'); await action(pc, 'commit').click(); assert.equal(await pc.getByTestId('author-name').innerText(), '冈本伦');
  assert.match(await pc.locator('.hero').innerText(), /Okamoto Lynn/);
}));
